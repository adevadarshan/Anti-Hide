# AntiHide V2

A personal, self-hosted Android phone finder. A browser on your laptop reaches
your phone across a private [Tailscale](https://tailscale.com) network and
triggers a loud alarm on the phone when you find it — no cloud service, no
account, no GPS, no location tracking, and no third party in the path.

The phone runs a small HTTP + Server-Sent Events server bound to the Tailscale
`tun0` interface. The laptop browser talks to it directly over your tailnet.

---

## Release baseline

| | |
| --- | --- |
| Application ID | `com.antihide.v2` |
| Version name | `1.0-slice1` (as declared in `app/build.gradle.kts`) |
| Version code | `1` |
| Architecture | Single-module Android app (`:app`) |
| Minimum Android | 8.0 Oreo (API 26) |
| Target / compile SDK | 35 (Android 15) |

---

## What is implemented

- **Tailnet-only HTTP/SSE server** bound to the phone's Tailscale interface, with
  stateless idempotent commands.
- **Two-layer network admission** decided on raw socket bytes, with no reverse
  DNS anywhere in the decision path.
- **Four-stage authentication**: physical pairing window, session issuance,
  reload bootstrap, per-command CSRF.
- **Foreground service** with a `specialUse` foreground-service type, keeping the
  server reachable with the screen off.
- **Start-on-boot** receiver that restarts the service after a reboot.
- **Alarm with remote and local stop**, a watchdog timeout, and wake/wifi lock
  handling.
- **Live dashboard** (vanilla JS, no build step, no framework) served by the app,
  with SSE push and a polling fallback.
- **Sanitized diagnostics**: the in-app event log is scrubbed of session values
  and IP tails before it is ever shown or served.

---

## Architecture

```
laptop browser  ──HTTP/SSE over Tailscale (tun0)──▶  Android phone
                                                       │
                            ┌──────────────────────────┴─────────────────────┐
                            │  AntiHideForegroundService (foregroundService) │
                            │    ├─ TailscaleInterfaceServer  HTTP + SSE     │
                            │    ├─ SseBroadcaster            fan-out + log │
                            │    └─ AntiHideStateMachine      alarm state    │
                            │                                                 │
                            │  Layer 1  RawSocketAdmissionVerifier            │
                            │           raw peer bytes → 100.64.0.0/10 or    │
                            │           fd7a:115c:a1e0::/48, else drop        │
                            │                                                 │
                            │  Layer 2  SessionAuthManager                    │
                            │           pairing → cookie → CSRF              │
                            └─────────────────────────────────────────────────┘
```

### HTTP API

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/` | none | Dashboard HTML |
| `GET` | `/app.js`, `/styles.css` | none | Dashboard assets |
| `GET` | `/api/v1/ping` | none | Liveness probe |
| `POST` | `/api/v1/pair` | pairing window + code | Exchange code for a session cookie |
| `GET` | `/api/v1/session` | cookie + peer binding | Reload bootstrap; returns the CSRF token |
| `GET` | `/api/v1/status` | cookie + peer binding | Polling fallback (2 s) |
| `GET` | `/api/v1/events` | cookie + peer binding | SSE state stream with heartbeats |
| `GET` | `/api/v1/diagnostics` | cookie + peer binding | Sanitized diagnostics drawer |
| `POST` | `/api/v1/command` | cookie + CSRF | `RING` / `STOP` |
| `POST` | `/api/v1/echo-command` | cookie + CSRF | Idempotency test harness |

`/api/v1/ping` and the static dashboard are deliberately unauthenticated: they
expose no state and reveal nothing about the phone.

---

## Technology stack

| Layer | Choice |
| --- | --- |
| Language | Kotlin 1.9.24 |
| UI | Jetpack Compose (BOM 2024.06.00), Material 3 |
| HTTP server | Hand-written HTTP/1.1 parser and server on raw `ServerSocket` — no web framework |
| Networking | Android `Network`/`VpnService` socket factory for `tun0` binding |
| Secure storage | `androidx.security:security-crypto` 1.1.0-alpha06 (`EncryptedSharedPreferences`) + Android Keystore HMAC key |
| Async | Kotlin Coroutines 1.8.1 |
| Build | Android Gradle Plugin 8.5.2, Gradle 8.7, JDK 17 |
| Tests | JUnit 5 (Jupiter 5.10.2), JVM-only, no device required |
| Transport | Tailscale (WireGuard) for encryption; the app itself speaks plain HTTP |

No third-party code is vendored into this repository. All dependencies resolve
from Google's Maven repository and Maven Central.

---

## Prerequisites

- **JDK 17** (`JAVA_HOME` set, or `java` on `PATH`).
- **Android SDK** with platform API 35 installed.
- **Tailscale** on both the laptop and the phone, joined to the **same tailnet**,
  with the tailnet up. Both devices need a working Tailscale address.
- **A modern browser** on the laptop.
- **Windows PowerShell** for the build commands below.

---

## Build

The Gradle launcher in this repository is named `antihide-gradlew.bat` rather
than the conventional `gradlew.bat`, because the authoring machine's tooling
blocked the standard filename. It is the standard Gradle wrapper script under a
different name and behaves identically. If your machine allows it, renaming it
to `gradlew.bat` is safe.

```powershell
cd <repo>

# 1. Point the build at your SDK (local.properties is machine-specific and is
#    not committed). Skip if Android Studio already created it.
echo "sdk.dir=C:\path\to\Android\Sdk" > local.properties

# 2. Build
.\antihide-gradlew.bat :app:assembleDebug

# 3. Install on a connected phone (USB debugging enabled)
.\antihide-gradlew.bat :app:installDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Build
output is excluded from version control and is regenerated by the commands above.

---

## Test

The suite is JVM-only. It needs no device, no emulator and no Tailscale
connection.

```powershell
.\antihide-gradlew.bat :app:testDebugUnitTest
```

An HTML report is written to `app/build/reports/tests/testDebugUnitTest/`.

**Current verified result: 103 tests, 0 failures, 0 errors, 0 skipped.**

That result was produced from a **completely clean workspace** — the whole
`app/build/` output directory and the `.gradle/` project cache were deleted
first, and the suite was re-run with `--rerun-tasks --no-build-cache` so that all
23 Gradle tasks genuinely re-executed instead of being served from the build
cache. The debug APK rebuilt from that same clean state came out
byte-identical to the previous artifact, so the build is reproducible.

See [`docs/TEST_RESULTS.md`](docs/TEST_RESULTS.md) for the per-suite breakdown
and exactly how to reproduce the run.

---

## Installation

`:app:installDebug` installs over ADB to a connected device. There is no
Play Store distribution and no signed release build configuration; see
[Known limitations](#known-limitations).

---

## Network setup and connection model

1. Install Tailscale on the phone and the laptop; join them to the same tailnet.
2. Confirm the phone has a Tailscale IPv4 address (`100.64.0.0/10`) and, where
   enabled, a Tailscale IPv6 ULA address (`fd7a:115c:a1e0::/48`).
3. Launch AntiHide V2 on the phone and grant the notification permission so the
   foreground service can run.
4. On the laptop, open `http://<phone-tailscale-address>:8765` in a browser. The
   default port is `8765`.
5. The dashboard reports `PAIRING_REQUIRED` until you pair (see below).

The server binds only to Tailscale interface addresses. It never binds to
`0.0.0.0` or `::`, so it will not answer on Wi-Fi, cellular or any other
interface. If the phone has no Tailscale address, it refuses to start the
listener rather than falling back to a wildcard bind.

You may reach the phone by its numeric Tailscale address or by its MagicDNS
name. Both are supported; see the authentication section for why that detail
matters.

---

## Authentication and security

### Layer 1 — network admission

Admission is decided **only** on the raw bytes returned by
`InetAddress.getAddress()`. There is deliberately **no reverse DNS lookup in
the admission path**, and an architecture test
(`architecture/BannedApisSourceTest`) fails the build if `remoteHost`,
`getHostName(`, `localStorage`, `sessionStorage` or `?token=` ever reappear in
shipped source — including the browser assets.

This is not incidental. The previous generation of this app (V1) called
`remoteHost` / `getHostName()`, which returns a numeric IP on a cold cache and a
MagicDNS hostname once Android's DNS cache is warm. Browser reloads then sent
hostname `Host` / `Origin` headers, numeric-IP admission filters rejected them,
and legitimate requests failed with `403 Forbidden` while every automated test
still passed. Removing DNS from the decision path eliminates that failure mode
entirely.

A wildcard local bind (`0.0.0.0` / `::`) is **rejected**, because under the Linux
Weak Host Model a wildcard listener would also answer packets that arrived over
unencrypted Wi-Fi. `scripts/verify-e12a-staticroute.ps1` is the adversarial test
for exactly that case.

### Layer 2 — authentication

Four stages, every comparison constant-time:

1. **Physical pairing window** — the phone owner opens a 120-second window and
   receives an 8-character code (40 bits, `SecureRandom`, Crockford Base32,
   displayed as `XXXX-XXXX`). Single use. Five failed attempts lock it out.
2. **Session issuance** — a successful pairing returns an `HttpOnly`,
   `SameSite=Strict` cookie bound to the exact peer IP bytes. 14-day TTL, at
   most 4 concurrent sessions, revocable.
3. **Reload bootstrap** — `GET /api/v1/session` restores the session from the
   cookie alone, which is the exact path that was unreliable in V1.
4. **Per-command CSRF** — mutating requests require an `X-AntiHide-CSRF` header
   matching an HMAC-SHA256 token derived from a per-device key held in the
   **Android Keystore**. The key never leaves the Keystore, so tokens cannot be
   recomputed off-device.

Sessions at rest live in `EncryptedSharedPreferences` (AES256-SIV keys,
AES256-GCM values).

### Response hardening

Every response, with no opt-out route:

- `Content-Security-Policy` — `script-src 'self'`, `frame-ancestors 'none'`,
  `base-uri 'none'`, `form-action 'none'`, no external origins.
- `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.

Request headers are capped at 16 KiB and bodies at 64 KiB. The request line is
never logged. The event log is scrubbed **at the sink**, so a future call site
that interpolates a token still cannot leak it.

### Transport

The app speaks plain HTTP; confidentiality and integrity come entirely from the
Tailscale/WireGuard layer, and Layer 1 refuses any peer that did not arrive over
the tunnel. This is a deliberate design choice for a single-user tailnet tool.
It has a direct consequence worth stating plainly: **the app must never be
reachable over a non-tunnel interface**, because there is no in-app TLS to fall
back on if that assumption is ever violated.

---

## Privacy

- **Nothing leaves your tailnet.** There is no cloud service, no analytics, no
  telemetry, no crash reporting and no third-party SDK. The only outbound
  connections are to the device running the app.
- **No location data.** The app does not request, read or transmit GPS or any
  other location. It is an acoustic alarm, not a tracker.
- **No Internet permission abuse.** The only permission touching connectivity is
  `INTERNET`, used to serve the local server.
- **The event log is redacted before it is displayed or served**, masking peer
  addresses and removing session values.
- **Verification logs are not committed.** Manual browser-automation output can
  capture your own tailnet hostnames and addresses, so it is excluded from the
  repository by `.gitignore`; the curated outcome is recorded in
  [`docs/VERIFICATION.md`](docs/VERIFICATION.md) instead.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Dashboard shows `PAIRING_REQUIRED` | Normal before pairing. Open a pairing window on the phone. |
| `401 PAIRING_WINDOW_CLOSED` | No window is open, or the 120 s window expired. Open a new one. |
| `401 PAIRING_LOCKED` | Five failed attempts. Open a new pairing window on the phone. |
| `403` on a command | Session cookie present but CSRF header missing or wrong. Reload the page. |
| `401` on a command | Cookie missing, expired, revoked, or issued to a different peer address. Re-pair. |
| Browser cannot connect at all | Tailscale is down on either device, the phone's foreground service is not running, or the phone has no Tailscale address. Check `Tailscale` status first. |
| Works by IP but not by MagicDNS name | Usually a MagicDNS propagation delay. Retry; the admission check does not use DNS. |
| Alarm silent but state is `RINGING` | The phone is muted, Do Not Disturb is on, or audio is routed to a paired Bluetooth device. |
| Service dies when the screen locks | Battery optimisation. The app requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; grant the exemption. On ColorOS, also allow background activity and auto-start. |

---

## Known limitations and explicitly unverified behavior

Stated precisely, because these are the boundaries of what has actually been
checked.

- **Automated coverage is JVM-only.** The 103 tests exercise admission logic,
  the auth lifecycle, the HTTP wire parser, the state machine, state sync,
  service lifecycle, response headers and log scrubbing. They do **not** run on
  a device, do not exercise real sockets, and do not cover Android framework
  behaviour.
- **No CI.** The suite runs only when a human runs it. There is no workflow that
  enforces it.
- **Real-device verification is manual.** The experiments `E-01` … `E-13`
  (including `E-12A`, the cross-interface static-route attack test, and `E-12B`,
  the auth negative matrix) live in `scripts/verify-slice1.ps1` and
  `scripts/verify-e12a-staticroute.ps1`. They require a phone, a paired browser
  and manual steps, and their results are recorded in
  [`docs/VERIFICATION.md`](docs/VERIFICATION.md).
- **`E-12A` self-elevates to Administrator** and installs a static route on the
  laptop. Read the script before running it.
- **Browser support.** The dashboard is dependency-free vanilla JavaScript with
  no build step, so it has no framework-specific behaviour. Per the project
  records, **Google Chrome on Windows 11 is the browser used for real-device
  verification. Microsoft Edge has not been tested** — do not read the Chrome
  result as covering Edge. No other browser has been tested.
- **No signed release build.** The release build type has no `signingConfig` and
  no ProGuard rules file. Distributing this app requires adding both.
- **No release APK is published.** Only a debug build is produced locally.
- **Single user, single tailnet by design.** There is no multi-user support and
  revocation is coarse ("revoke all sessions").
- **Session TTL is 14 days.** A stolen cookie stays usable for two weeks from
  any host that later receives the same Tailscale address. Bounded by the
  4-session cap and by revoke-all.
- **A loopback bypass flag exists in the admission API.**
  `RawSocketAdmissionVerifier.verify(..., allowLoopbackForLocalTests)` accepts
  loopback peers when enabled. It defaults to `false` and is documented as
  production-false; it exists so tests can bind a real socket without a tailnet.

---

## Contributing

This is a personal project maintained by one person. Contributions are welcome
but small and focused patches are far easier to review than large ones.

- **Open an issue before a large change.** A paragraph on the goal is enough.
- **Keep the security invariants intact.** Two are enforced by tests that will
  fail the build if broken: no reverse-DNS calls in the admission path, and no
  browser storage or URL-embedded tokens in shipped source. If a change seems to
  need either, it is worth a conversation first.
- **Add or update tests** for any behaviour change. `:app:testDebugUnitTest` must
  pass before you open a pull request.
- **Never commit secrets, real tailnet addresses, hostnames, machine paths or
  device identifiers.** Use the synthetic fixtures already in the suite.
- **Do not add CI, external services or new dependencies** without discussing
  them first.
- By contributing you agree that your contribution is provided under the same
  terms as the project (see **License**, below).

---

## License

**License decision required from repository owner.**

No license file is present in this repository yet, and none has been applied.
Until the owner selects one, default copyright applies: nobody may legally copy,
modify or redistribute this code. This repository is intended to be published
**private first** for exactly this reason.

If the owner later publishes it publicly, a license must be added before
publication. Nothing in this repository depends on a specific license choice.

---

## Repository layout

```
app/
  src/main/java/com/antihide/v2/
    auth/      session store, four-stage auth manager, Keystore HMAC key
    domain/    alarm + arm state machine, system state snapshot
    net/       HTTP wire parser, socket binder, admission verifier, server, SSE
    service/   foreground service, boot receiver, notifiers, device locks
    ui/        activities and the ColorOS readiness card
  src/main/assets/dashboard/   vanilla-JS dashboard served by the app
  src/main/res/raw/            alarm tone
  src/test/java/               103 JVM tests
scripts/       manual real-device verification harnesses (PowerShell)
docs/          curated verification and test-result records
ROADMAP.md     architecture evaluation and the design decisions behind this release
```
