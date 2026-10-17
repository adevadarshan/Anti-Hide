/*
 * AntiHide V2 — production dashboard.
 *
 * The CSRF token lives in this one JavaScript memory variable for the lifetime of the page and is
 * re-fetched from GET /api/v1/session on every load. It is deliberately never written to browser
 * storage, never placed in a URL, and never logged.
 *
 * No inline script and no on* attribute anywhere: the server sends a strict Content-Security-Policy
 * with script-src 'self', so any inline code would be a console error.
 */
'use strict';

const CLIENT_APP_VERSION = 'v2.5';
const RING_CEILING_MS = 180000; // the state machine watchdog

let activeCsrfToken = null;
let eventSource = null;
let pollTimer = null;
let watchdogTimer = null;
let tickerTimer = null;
let reconnectAttempt = 0;
let lastFrameAt = 0;
let currentServerBootId = null;
let lastAppliedStateVersion = 0;
let clockOffsetMs = 0;          // serverNow() - laptopNow(), so the timer survives clock skew
let transport = 'BOOTING';
let ringInFlight = false;

const HEARTBEAT_DEADLINE_MS = 20000;
const POLL_INTERVAL_MS = 2000;
const SSE_REOPEN_INTERVAL_MS = 5000;
const COMMAND_TIMEOUT_MS = 2500;
// Every retry reuses one commandId: a response lost in transit must never replay the command
// against a phone that already acted on it (Security Invariant #6).
const COMMAND_RETRY_DELAYS_MS = [0, 600, 1500];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const el = (id) => document.getElementById(id);
const counters = { frames: 0, heartbeats: 0, boots: null };

function log(message) {
  const item = document.createElement('li');
  item.textContent = new Date().toISOString().slice(11, 23) + '  ' + message;
  const list = el('log');
  list.insertBefore(item, list.firstChild);
  while (list.childElementCount > 40) list.removeChild(list.lastChild);
}

function setResult(id, message, isError) {
  const node = el(id);
  node.textContent = message;
  node.classList.toggle('error', Boolean(isError));
}

const TRANSPORT_CLASS = {
  CONNECTED_SSE: 'sse',
  CONNECTED_POLLING: 'polling',
  RECONNECTING: 'reconnecting',
  PAIRING_REQUIRED: 'pairing',
  BOOTING: 'booting',
};

function setConnectionStatus(state) {
  transport = state;
  el('status').textContent = state;
  el('banner').className = 'banner ' + (TRANSPORT_CLASS[state] || 'booting');
  el('d-transport').textContent = state;
  el('pair-card').classList.toggle('hidden', state !== 'PAIRING_REQUIRED');
  if (state === 'PAIRING_REQUIRED') {
    el('retry-note').textContent = 'Pair this origin to control the alarm.';
    el('retry-note').classList.remove('hidden');
  }
  refreshCommandButtons();
}

/** STOP is never blocked: only a missing token makes it genuinely unsendable. */
function refreshCommandButtons() {
  el('ring-button').disabled = activeCsrfToken === null || ringInFlight;
  el('ring-button').textContent = ringInFlight ? 'Sending RING...' : 'RING PHONE';
  el('stop-button').disabled = activeCsrfToken === null;
  el('echo-button').disabled = activeCsrfToken === null;
  el('harness-button').disabled = activeCsrfToken === null;
  el('dup-button').disabled = activeCsrfToken === null;
}

function uuid4() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' +
    hex.slice(16, 20) + '-' + hex.slice(20);
}

async function api(path, options) {
  const started = performance.now();
  const response = await fetch(path, Object.assign({ credentials: 'same-origin', cache: 'no-store' }, options));
  const latency = Math.round(performance.now() - started);
  el('latency').textContent = latency + ' ms';
  el('d-http').textContent = response.status;
  el('d-latency').textContent = latency + ' ms';
  let payload = null;
  try {
    payload = await response.json();
  } catch (ignored) {
    payload = null;
  }
  return { response, payload };
}

// ---------------------------------------------------------------- state rendering

function fmtElapsed(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}

function serverNow() {
  return Date.now() + clockOffsetMs;
}

function renderTimer() {
  const node = el('ring-timer');
  if (el('alarm-state').textContent !== 'RINGING') {
    node.textContent = '—';
    return;
  }
  const started = Number(node.dataset.startedAt || 0);
  if (!started) {
    node.textContent = '00:00';
    return;
  }
  node.textContent = fmtElapsed(serverNow() - started);
}

function startTicker() {
  if (tickerTimer !== null) return;
  tickerTimer = setInterval(() => {
    renderTimer();
    const age = lastFrameAt ? Math.round((Date.now() - lastFrameAt) / 1000) : null;
    el('heartbeat-age').textContent = age === null ? 'Heartbeat —' : 'Heartbeat ' + age + 's ago';
    if (transport === 'RECONNECTING') {
      el('retry-note').textContent = 'Retrying… last seen ' + (age === null ? 'never' : age + 's ago');
      el('retry-note').classList.remove('hidden');
    } else if (transport !== 'PAIRING_REQUIRED') {
      el('retry-note').classList.add('hidden');
    }
  }, 1000);
}

function applySnapshot(payload) {
  if (!payload) return;
  const s = payload;

  if (typeof s.appVersion === 'string' && s.appVersion !== CLIENT_APP_VERSION) {
    // Deliberately a prompt, not an automatic reload: an auto-reload resets this page's state, so
    // a genuine version mismatch would reload forever and brick the dashboard. One click, once.
    el('update-text').textContent = 'Phone runs ' + s.appVersion + ', this tab was built for ' + CLIENT_APP_VERSION + '.';
    el('update-banner').classList.remove('hidden');
    log('app version mismatch: phone ' + s.appVersion + ', tab ' + CLIENT_APP_VERSION);
    return;
  }

  // A new serverBootId means the Android service restarted, so its stateVersion numbering is
  // unrelated to what we last saw (Security Invariant #9).
  if (typeof s.serverBootId === 'string' && s.serverBootId.length > 0 && s.serverBootId !== currentServerBootId) {
    if (currentServerBootId !== null) {
      log('server restarted, bootId ' + s.serverBootId.slice(0, 8) + ' stateVersion reset');
    }
    currentServerBootId = s.serverBootId;
    lastAppliedStateVersion = 0;
  }
  if (s.stateVersion !== undefined && s.stateVersion !== null) {
    const incoming = Number(s.stateVersion);
    if (Number.isFinite(incoming)) {
      if (incoming < lastAppliedStateVersion) return; // stale, ignore
      lastAppliedStateVersion = incoming;
    }
  }
  if (typeof s.serverTimeEpochMs === 'number') clockOffsetMs = s.serverTimeEpochMs - Date.now();

  if (s.alarmState !== undefined) {
    const ringing = s.alarmState === 'RINGING';
    el('alarm-state').textContent = ringing ? 'RINGING' : 'IDLE';
    el('alarm-card').className = 'card ' + (ringing ? 'ringing' : 'idle');
    if (ringing) {
      const started = Number(s.ringStartedAtEpochMs || 0);
      el('ring-timer').dataset.startedAt = String(started);
    } else {
      el('ring-timer').dataset.startedAt = '';
    }
    renderTimer();
  }
  if (s.stateVersion !== undefined) {
    el('state-version').textContent = s.stateVersion;
    el('d-version').textContent = s.stateVersion;
  }
  if (s.lastStopSource !== undefined) {
    el('last-stop').textContent = s.lastStopSource || '—';
    el('d-stop').textContent = s.lastStopSource || '—';
  }
  if (s.serverBootId !== undefined) el('d-boot').textContent = String(s.serverBootId).slice(0, 18);
  if (s.appVersion !== undefined) el('d-appversion').textContent = s.appVersion;
  if (typeof s.bootCount === 'number') {
    counters.boots = s.bootCount;
    el('boot-count').textContent = s.bootCount;
  }
}

async function bootstrapSession() {
  let result;
  try {
    result = await api('/api/v1/session');
  } catch (ignored) {
    setConnectionStatus('RECONNECTING');
    el('retry-note').textContent = 'Cannot reach the phone. Retrying…';
    el('retry-note').classList.remove('hidden');
    return false;
  }
  const { response, payload } = result;
  if (response.status === 200 && payload && payload.authenticated === true) {
    activeCsrfToken = payload.csrfToken;
    applySnapshot(payload);
    refreshCommandButtons();
    setConnectionStatus('RECONNECTING');
    log('session restored, stateVersion ' + payload.stateVersion);
    openStream();
    return true;
  }
  activeCsrfToken = null;
  stopStream();
  stopPolling();
  setConnectionStatus('PAIRING_REQUIRED');
  log('pairing required (HTTP ' + response.status + ')');
  return false;
}

// ---------------------------------------------------------------- SSE + fallback

function openStream() {
  stopStream();
  lastFrameAt = Date.now();
  const source = new EventSource('/api/v1/events', { withCredentials: true });
  eventSource = source;

  source.onopen = () => {
    reconnectAttempt = 0;
    setConnectionStatus('CONNECTED_SSE');
    stopPolling();
    log('SSE open');
  };
  source.onmessage = onFrame;
  // The server also sends ': heartbeat' comments, which EventSource deliberately hides from JS.
  // A named event is the only form a browser surfaces, so it is what resets the watchdog.
  source.addEventListener('heartbeat', onHeartbeat);
  source.onerror = () => {
    source.close();
    eventSource = null;
    onStreamError();
  };

  // A silent tun0 drop never fires onerror, so liveness is policed on a timer instead.
  watchdogTimer = setInterval(() => {
    if (Date.now() - lastFrameAt > HEARTBEAT_DEADLINE_MS) {
      log('heartbeat watchdog fired after 20s of silence');
      source.close();
      eventSource = null;
      onStreamError();
    }
  }, 5000);
}

function noteLiveness() {
  lastFrameAt = Date.now();
}

function onFrame(event) {
  noteLiveness();
  counters.frames += 1;
  el('sse-frames').textContent = counters.frames;
  if (event && typeof event.data === 'string' && event.data.length > 0) {
    try { applySnapshot(JSON.parse(event.data)); } catch (ignored) { /* watchdog still governs */ }
  }
}

function onHeartbeat(event) {
  noteLiveness();
  counters.heartbeats += 1;
  el('heartbeats').textContent = counters.heartbeats;
  if (event && typeof event.data === 'string' && event.data.length > 0) {
    try { applySnapshot(JSON.parse(event.data)); } catch (ignored) { /* liveness proven by the event */ }
  }
}

function onStreamError() {
  clearInterval(watchdogTimer);
  watchdogTimer = null;
  setConnectionStatus('RECONNECTING');
  startPolling();
  reconnectAttempt += 1;
  setTimeout(() => {
    if (activeCsrfToken !== null) openStream();
  }, SSE_REOPEN_INTERVAL_MS);
}

function handleUnauthorized(where) {
  activeCsrfToken = null;
  stopStream();
  stopPolling();
  setConnectionStatus('PAIRING_REQUIRED');
  log('unauthorized on ' + where + ', pairing required');
}

function stopStream() {
  clearInterval(watchdogTimer);
  watchdogTimer = null;
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
}

function startPolling() {
  if (pollTimer !== null) return;
  pollTimer = setInterval(pollStatus, POLL_INTERVAL_MS);
  pollStatus();
}

async function pollStatus() {
  let result;
  try {
    result = await statusWithTimeout();
  } catch (ignored) {
    setConnectionStatus('RECONNECTING');
    return;
  }
  const { response, payload } = result;
  if (response.status === 401) return handleUnauthorized('GET /api/v1/status');
  if (response.status === 200 && payload) {
    applySnapshot(payload);
    setConnectionStatus('CONNECTED_POLLING');
  } else {
    setConnectionStatus('RECONNECTING');
  }
}

async function statusWithTimeout() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), COMMAND_TIMEOUT_MS);
  try {
    return await api('/api/v1/status', { signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

// ---------------------------------------------------------------- commands

async function sendCommand(action, commandId) {
  if (activeCsrfToken === null) return null;
  const id = commandId || uuid4();
  let attempts = 0;
  for (let attempt = 0; attempt < COMMAND_RETRY_DELAYS_MS.length; attempt += 1) {
    if (attempt > 0) await sleep(COMMAND_RETRY_DELAYS_MS[attempt]);
    attempts += 1;
    let reply;
    try {
      reply = await postCommand(action, id);
    } catch (error) {
      log(action + ' attempt ' + attempts + ' failed, retrying');
      continue;
    }
    if (reply.response.status === 403) {
      log(action + ' CSRF rejected, refreshing token once');
      await refreshCsrf();
      try {
        reply = await postCommand(action, id);
      } catch (error) {
        continue;
      }
    }
    applySnapshot(reply.payload);
    if (reply.response.status !== 200) {
      setResult('alarm-result', action + ' rejected (HTTP ' + reply.response.status + ')', true);
    }
    return reply;
  }
  // Never leave the button spinning: say what happened and let the user click again.
  setResult('alarm-result', action + ' failed after ' + attempts + ' attempts — check the phone is reachable', true);
  log(action + ' gave up after ' + attempts + ' attempts');
  return null;
}

async function postCommand(action, commandId) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), COMMAND_TIMEOUT_MS);
  try {
    return await api('/api/v1/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-AntiHide-CSRF': activeCsrfToken },
      body: JSON.stringify({ commandId: commandId, action: action }),
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

async function refreshCsrf() {
  try {
    const { response, payload } = await api('/api/v1/session');
    if (response.status === 200 && payload && payload.csrfToken) activeCsrfToken = payload.csrfToken;
  } catch (ignored) {
    // The retry below fails on its own if the network is still down.
  }
}

async function ringPhone() {
  if (ringInFlight) return;
  ringInFlight = true;
  refreshCommandButtons();
  try {
    const r = await sendCommand('RING');
    if (r) {
      setResult('alarm-result', 'RING HTTP ' + r.response.status +
        ' · stateVersion ' + (r.payload ? r.payload.stateVersion : '?'), false);
      log('RING sent');
    }
  } finally {
    ringInFlight = false;
    refreshCommandButtons();
  }
}

async function stopPhone() {
  const r = await sendCommand('STOP');
  if (r) {
    setResult('alarm-result', 'STOP HTTP ' + r.response.status +
      ' · stateVersion ' + (r.payload ? r.payload.stateVersion : '?'), false);
    log('STOP sent');
  }
}

async function duplicateRingTest() {
  const id = uuid4();
  const before = el('state-version').textContent;
  const flags = [];
  for (let i = 0; i < 3; i++) {
    const r = await sendCommand('RING', id);
    flags.push(r && r.payload ? r.payload.duplicate : '?');
  }
  setResult('alarm-result', 'duplicate flags [' + flags.join(',') + '] · stateVersion ' +
    before + ' -> ' + el('state-version').textContent, false);
  await stopPhone();
}

async function sendEchoCommand() {
  const { response, payload } = await api('/api/v1/echo-command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-AntiHide-CSRF': activeCsrfToken },
    body: JSON.stringify({ commandId: uuid4(), payload: 'PING_TEST' }),
  });
  applySnapshot(payload);
  setResult('command-result', 'Echo HTTP ' + response.status +
    ' · duplicate ' + (payload ? payload.duplicate : '?'), response.status !== 200);
}

async function runReconnectHarness() {
  let reconnects = 0;
  for (let i = 1; i <= 25; i += 1) {
    stopStream();
    const { response, payload } = await api('/api/v1/session');
    if (response.status === 200 && payload && payload.authenticated === true) {
      activeCsrfToken = payload.csrfToken;
      reconnects += 1;
    }
    await sendEchoCommand();
    openStream();
    await sleep(200);
  }
  setResult('command-result', 'harness: ' + reconnects + '/25 reconnects', reconnects !== 25);
}

// ---------------------------------------------------------------- diagnostics drawer

async function loadDiagnostics() {
  el('diag-button').disabled = true;
  try {
    const { response, payload } = await api('/api/v1/diagnostics');
    if (response.status === 401) {
      setResult('command-result', 'Diagnostics require pairing (HTTP 401)', true);
      return;
    }
    if (response.status !== 200 || !payload) {
      setResult('command-result', 'Diagnostics HTTP ' + response.status, true);
      return;
    }
    const list = el('server-log');
    list.textContent = '';
    const rows = [
      'serverBootId ' + payload.serverBootId,
      'appVersion ' + payload.appVersion,
      'stateVersion ' + payload.stateVersion + ' · ' + payload.alarmState + ' · ' + payload.armState,
      'volume ' + payload.volumePercent + '%',
      'SSE clients ' + payload.activeSseClients + ' · session slots ' + payload.activeSessionSlots + '/4',
      'bound: ' + (payload.boundAddresses || []).join(', '),
      '',
    ].concat(payload.recentEvents || []);
    rows.forEach((row) => {
      const item = document.createElement('li');
      item.textContent = row;
      list.appendChild(item);
    });
    setResult('command-result', 'Diagnostics refreshed · ' + (payload.recentEvents || []).length + ' sanitized events', false);
  } catch (error) {
    setResult('command-result', 'Diagnostics request failed — phone unreachable', true);
  } finally {
    el('diag-button').disabled = false;
  }
}

// ---------------------------------------------------------------- pairing

function formatPairCode(raw) {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8);
  return clean.length > 4 ? clean.slice(0, 4) + '-' + clean.slice(4) : clean;
}

async function submitPairing() {
  const code = el('pair-code').value.trim().toUpperCase();
  el('pair-button').disabled = true;
  try {
    const { response, payload } = await api('/api/v1/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code }),
    });
    if (response.status === 200 && payload && payload.authenticated === true) {
      setResult('pair-result', 'Paired.', false);
      log('paired, opening session bootstrap');
      await bootstrapSession();
      return;
    }
    const reason = response.status === 429
      ? 'Pairing window is closed. Open a new window on the phone and try again.'
      : response.status === 400
        ? 'That code is malformed. Enter the 8 characters shown on the phone.'
        : 'Incorrect or expired code.';
    setResult('pair-result', reason, true);
  } catch (error) {
    setResult('pair-result', 'Cannot reach the phone.', true);
  } finally {
    el('pair-button').disabled = false;
  }
}

// ---------------------------------------------------------------- lifecycle

function onLifecycleWake(reason) {
  log('wake: ' + reason);
  if (activeCsrfToken === null) {
    bootstrapSession();
    return;
  }
  if (eventSource === null) openStream();
  statusWithTimeout().then(
    (result) => {
      if (result.response.status === 401) return handleUnauthorized('wake status');
      if (result.response.status === 200 && result.payload) applySnapshot(result.payload);
    },
    () => {
      setConnectionStatus('RECONNECTING');
      startPolling();
    },
  );
}

el('ring-button').addEventListener('click', ringPhone);
el('stop-button').addEventListener('click', stopPhone);
el('dup-button').addEventListener('click', duplicateRingTest);
el('echo-button').addEventListener('click', sendEchoCommand);
el('harness-button').addEventListener('click', runReconnectHarness);
el('diag-button').addEventListener('click', loadDiagnostics);
el('reload-button').addEventListener('click', () => location.reload());
el('pair-button').addEventListener('click', submitPairing);
el('pair-code').addEventListener('input', (event) => {
  event.target.value = formatPairCode(event.target.value);
});
el('pair-code').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') submitPairing();
});
window.addEventListener('online', () => onLifecycleWake('online'));
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) onLifecycleWake('visible');
});

startTicker();
refreshCommandButtons();
bootstrapSession();
log('page loaded');