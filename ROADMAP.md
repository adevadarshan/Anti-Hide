# AntiHide V2 — Independent Architecture Evaluation & Development Roadmap

**Document Filename**: `ROADMAP.md`  
**Target Environment**: OPPO A58 (Android / ColorOS) + Windows 11 Laptop over Private Network (Tailscale)  
**Document Status**: Implemented. This document is the architecture evaluation and design record that produced the current baseline release. The `tun0` binding, four-stage authentication, foreground service, SSE state synchronisation and production dashboard described here are implemented and verified — see [`README.md`](README.md) for the shipped feature set, [`docs/TEST_RESULTS.md`](docs/TEST_RESULTS.md) for the automated suite, and [`docs/VERIFICATION.md`](docs/VERIFICATION.md) for what was verified on real hardware. Sections 12, 13 and 19 are retained as the original sequencing rationale and acceptance criteria; they are a historical design record, not a work queue.

---

## 1. Executive Summary

AntiHide is a personal Android phone-finding system that enables a Windows 11 laptop browser to connect over a private network to an OPPO A58 smartphone, trigger a loud acoustic alarm (`RING`), and silence it (`STOP`) once found, without relying on any public cloud infrastructure.

In the previous implementation (V1: Kotlin, Jetpack Compose, Ktor HTTP/WebSocket, Tailscale), remote `RING` and `STOP` worked on the physical device, but browser reloads intermittently failed with `HTTP 403 Forbidden` despite passing all automated tests. Independent investigation into Android/JVM socket APIs (`java.net.InetSocketAddress`), Android's `netd` DNS stub resolver, Ktor's `RequestConnectionPoint` (`origin.remoteHost`), and Tailscale MagicDNS (`100.100.100.100`) shows why:
1. Calling `origin.remoteHost` or `InetSocketAddress.getHostName()` triggers a reverse DNS (`PTR`) lookup whose return value changes from a numeric IP string (`"100.x.y.z"`) to a MagicDNS hostname (`"laptop.tailnet.ts.net"`) as soon as Android's DNS cache populates during page reloads.
2. Browser requests loaded via a MagicDNS hostname send that hostname in `Host` and `Origin` headers during `POST` and WebSocket upgrade handshakes, causing numeric-IP admission filters to return `403 Forbidden`.
3. Ktor's in-memory unit test engine (`testApplication`) mocks connection strings (`"localhost"`) and never exercises real kernel sockets, `netd`, or browser reload behavior.

This roadmap defines a verification-first architecture and slice-by-slice engineering plan for **AntiHide V2**:
- **Mandatory Networking & Auth Proof-of-Concept Gate First (Slice 1)**: Validates `tun0` VPN network binding, raw byte-array peer verification (`InetAddress.getAddress()`), and the complete end-to-end Pairing → Session Issuance → Reload Bootstrap → Command Authorization flow on the physical OPPO A58 and Windows 11 laptop across 14 real-device experiments before any alarm or UI feature work begins.
- **Stateless Idempotent Commands + Resilient State Sync**: Replaces fragile WebSocket command channels with idempotent `POST /api/v1/command` requests paired with Server-Sent Events (`GET /api/v1/events`) and automatic 2-second HTTP status polling (`GET /api/v1/status`).
- **Core Reliability Before Optional Features**: Delivers and verifies the core `RING`/`STOP` system (Slice 2) and OPPO A58 ColorOS screen-off survival (Slice 3) before adding optional capabilities such as Arm/Disarm (Slice 6), non-destructive volume control (Slice 7), and optional BLE fallback (Slice 8).

---

## 2. Project Goals and Non-Goals

### 2.1 Primary Functional Goals
1. **Secure Laptop-to-Phone Communication**: Authenticate and authorize all traffic between the Windows 11 browser and the OPPO A58 over the private network.
2. **Reliable Remote `RING` and `STOP` Commands**: Guarantee idempotent command execution with deterministic client retries and clear confirmation.
3. **Browser Dashboard with Unambiguous States**: Display real-time connection state (`CONNECTED_LIVE`, `CONNECTED_POLLING`, `RECONNECTING`, `UNAUTHORIZED`) and alarm state (`IDLE`, `RINGING`).
4. **Integrated Pairing, Session Validation, and Authorization**: Provide a cohesive cryptographic pairing and session lifecycle that proves requests come from the paired laptop and never leaks secrets into URLs, logs, or browser persistence (`localStorage`/`sessionStorage`).
5. **Screen-Off & Background Alarm Operation**: Ensure the OPPO A58 remains reachable and plays the alarm when the screen is off and unplugged, within Android 13/14/15 and OPPO ColorOS limits.
6. **Network-Independent Local `STOP`**: Allow immediate alarm silencing on the OPPO A58 (via lock-screen UI, notification action, or in-app button) even when the laptop disconnects.
7. **Deterministic Reconnection**: Recover automatically after normal page reloads (`F5`), hard refreshes (`Ctrl+F5`), tab closes/reopens, Wi-Fi/cellular switches, Tailscale restarts, and Android service restarts.
8. **Optional Arm/Disarm Functionality**: Allow arming and disarming where arming **never** triggers the alarm and disarming prevents remote `RING` activation.
9. **Configurable Alarm Volume Without Global Side Effects**: Control alarm loudness without mutating global Android volume settings (`STREAM_MUSIC`, `STREAM_RING`, `STREAM_ALARM`).
10. **Optional Bluetooth Fallback**: Evaluate and optionally implement an authenticated BLE fallback if justified after the core system is proven.

### 2.2 Explicit Non-Goals
- **No Public Cloud or Push Relay**: No Firebase Cloud Messaging (FCM), external servers, or third-party accounts unless Slice 3 proves local foreground services cannot survive on the OPPO A58.
- **No GPS / Map Tracking**: AntiHide is an acoustic proximity finder, not a geographical tracking tool.
- **No Stealth Operation**: An explicit Android Foreground Service notification must be visible whenever AntiHide is active in the background.

---

## 3. Previous Implementation Lessons

### 3.1 Summary of the V1 Failure ([Experimentally Demonstrated Behavior])
- V1 used Kotlin, Jetpack Compose, Ktor HTTP/WebSocket, a browser dashboard, and Tailscale.
- `RING` and `STOP` functioned on the OPPO A58, but browser reloads intermittently returned `HTTP 403 Forbidden`.
- Incoming connections were sometimes represented as numeric IP addresses (`100.x.y.z`) and sometimes as a Tailscale MagicDNS hostname.
- The V1 admission filter accepted specific numeric IP ranges and rejected hostnames.
- Multiple address-normalization patches and unit tests passed in automated suites while the real-device `403` failure persisted.

### 3.2 Underlying Root Causes ([Verified Fact])
1. **`InetSocketAddress.getHostName()` Reverse DNS Caching**:
   - **[Verified Fact]**: In Android Libcore (`java.net.InetSocketAddress`), calling `getHostName()` (which Ktor's `call.request.origin.remoteHost` invokes) triggers a reverse DNS (`PTR`) lookup via Android's `netd` resolver if no hostname is cached on the `InetAddress` object.
   - **[Verified Fact]**: Tailscale runs a local MagicDNS responder at `100.100.100.100` that answers `PTR` queries for `100.64.0.0/10` peers with their MagicDNS name (`<device>.<tailnet>.ts.net`).
   - When the DNS cache is cold or times out, `getHostName()` returns `"100.x.y.z"`. Once `netd` caches the `PTR` record during initial page load, subsequent sockets opened during browser reloads return the MagicDNS hostname string, triggering a `403 Forbidden` in any filter expecting numeric digits.
2. **Browser `Origin` and `Host` Header Semantics**:
   - **[Verified Fact]**: When the user navigates to `http://<magicdns-hostname>:<port>`, the browser sends `Host: <magicdns-hostname>:<port>` on all requests and `Origin: http://<magicdns-hostname>:<port>` on `POST` and WebSocket upgrade requests. Any filter validating `Host` or `Origin` against numeric IP ranges fails on reload.
3. **Why `0.0.0.0` + `socket.localAddress` Is Also Insufficient**:
   - **[Verified Fact]**: Under the Linux kernel's **Weak Host Model**, binding a listening socket to `0.0.0.0` and inspecting `socket.localAddress` only tells the application the **destination IP header** of the incoming packet, not whether the packet physically entered via the encrypted `tun0` interface or an untrusted `wlan0` Wi-Fi interface.

---

## 4. Research Methodology and Sources

### 4.1 Classification of Claims
- **[Verified Fact]**: Grounded directly in Android SDK/AOSP documentation, Java Libcore source code, IETF RFCs, WHATWG specifications, or Tailscale official documentation.
- **[Experimentally Demonstrated Behavior]**: Observed directly on the user's OPPO A58 and Windows 11 laptop in V1.
- **[Reasoned Recommendation]**: Engineering design choice derived from verified constraints.
- **[Assumption]**: Environmental prerequisite assumed to hold unless disproven during hardware testing.
- **[Unresolved Question]**: Hardware- or firmware-specific behavior that requires empirical measurement on the OPPO A58 during Slice 1 or Slice 3.

### 4.2 Authoritative Sources
1. **Android / Java Networking (`java.net.InetAddress`, `java.net.InetSocketAddress`, `android.net.Network`)**:
   - *Android Developers Reference*: `InetAddress.getAddress()` returns the raw IP address in network byte order (`byte[4]` for IPv4, `byte[16]` for IPv6) with zero DNS lookups. `InetSocketAddress.getHostName()` performs a reverse name-service lookup.
   - *Android Developers Reference (`android.net.Network#bindSocket`)*: Binds a `Socket`, `DatagramSocket`, or `FileDescriptor` specifically to a given Android `Network` (such as a `TRANSPORT_VPN` network), enforcing at the kernel `fwmark`/`SO_BINDTODEVICE` level that traffic only flows over that network interface.
2. **Linux Kernel IPv4 Networking (`Documentation/networking/ip-sysctl.rst`)**:
   - Linux implements the Weak Host Model by default: packets addressed to any local IP are accepted on any interface unless bound to a specific device (`SO_BINDTODEVICE` / `fwmark`) or blocked by strict reverse-path filtering (`rp_filter = 1`) and `netd` VPN policy routing rules.
3. **Tailscale Official Documentation**:
   - *CGNAT IPv4 (`100.64.0.0/10`, RFC 6598) & IPv6 ULA (`fd7a:115c:a1e0::/48`)*: Every Tailscale device receives addresses from these ranges on its virtual `tun0` interface.
   - *MagicDNS (`100.100.100.100`)*: Answers forward `A`/`AAAA` and reverse `PTR` queries for tailnet nodes.
4. **Android Foreground Services & Audio (`API 33–35`)**:
   - *Android Developers — Foreground Service Types*: Requires explicit `foregroundServiceType` declaration (`connectedDevice` / `specialUse`) and runtime notification permissions.
   - *Android Developers — `AudioAttributes` & `MediaPlayer`*: `USAGE_ALARM` plays on the alarm stream independent of ringer silent mode; `MediaPlayer.setVolume(left, right)` scales player amplitude locally (`0.0f..1.0f`) without altering `AudioManager` global stream indices; `setPreferredDevice(AudioDeviceInfo)` routes audio to `TYPE_BUILTIN_SPEAKER`.
5. **WHATWG Fetch, Cookie (`RFC 6265bis`), and Server-Sent Events Specifications**:
   - Cookies are scoped per host origin (an IP origin `100.x.y.z` does not share cookies with a hostname origin `oppo-a58`). `HttpOnly` prevents `document.cookie` reads; `SameSite=Strict` prevents cross-site attachment.

---

## 5. Verified Technical Constraints

| Area | Constraint Classification & Technical Reality | Design Consequence for AntiHide V2 |
| :--- | :--- | :--- |
| **Socket Address Resolution** | **[Verified Fact]** `InetSocketAddress.getHostName()` and Ktor's `origin.remoteHost` invoke reverse DNS (`PTR`). `InetAddress.getAddress()` returns raw `byte[]` without DNS I/O. | Ban `getHostName()` and `origin.remoteHost` via static build checks. Inspect only `InetAddress.getAddress()` byte arrays. |
| **Linux Weak Host Model vs. `tun0` Isolation** | **[Verified Fact]** Binding to `0.0.0.0` and checking `socket.localAddress` only checks the packet's destination IP header, not whether it entered through `tun0` or `wlan0`. | Bind the listening server specifically to the `tun0` `InetAddress` (`100.x.y.z` / `fd7a:...`) AND/OR bind the socket descriptor to the Android `TRANSPORT_VPN` `Network`, and verify isolation empirically with a static-route LAN attack test in Slice 1. |
| **Browser Cookie Origin Isolation** | **[Verified Fact]** Browsers maintain separate cookie jars for `http://100.x.y.z:8765` and `http://<magicdns-hostname>:8765`. | The phone's session store must support multiple simultaneous `AuthorizedSession` entries for the paired laptop (e.g., up to 4 active sessions bound to the laptop's Tailscale IP bytes) so pairing on the MagicDNS URL does not log out the numeric IP URL, and vice versa. |
| **Security Invariant #5 (Secret Storage)** | **[Verified Fact]** Secrets must not appear in logs, URLs, diagnostic responses, or browser persistence (`localStorage`/`sessionStorage`/`IndexedDB`). | Use an `HttpOnly; SameSite=Strict` session cookie (`antihide_sid`) paired with an in-memory-only CSRF token (`csrfToken`) fetched via `GET /api/v1/session` on page load and sent in `X-AntiHide-CSRF` on all `POST` requests. |
| **OPPO A58 ColorOS Background Freezing** | **[Verified Fact / Unresolved Question]** ColorOS includes `OplusHansManager` which can freeze background sockets when the screen is off unless exempted in battery settings. | Combine Foreground Service + `WifiLock` + explicit ColorOS battery-exemption verification in Slice 3's 60-minute unplugged screen-off soak test. |

---

## 6. Architecture Alternatives and Trade-Off Analysis

### 6.1 Server Socket Binding & Transport Options

| Option | Description | Pros | Cons | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **Option A: Explicit `tun0` Interface Binding + Pure JDK / Ktor Raw-Socket HTTP/1.1 + SSE + Polling Fallback** | Enumerates Android's `tun0` VPN interface (`NetworkCapabilities.TRANSPORT_VPN`), binds the HTTP server specifically to the phone's Tailscale IPv4 (`100.x.y.z`) and IPv6 (`fd7a:...`) addresses on `tun0` (plus `127.0.0.1` for local tests), and rebinds automatically via `ConnectivityManager.NetworkCallback` if `tun0` changes. Uses stateless `POST /api/v1/command` and `GET /api/v1/events` (SSE) + `GET /api/v1/status` polling. | - Physically and logically restricts listening sockets to the Tailscale `tun0` interface.<br>- Direct access to `Socket.getInetAddress().getAddress()` raw bytes.<br>- Zero WebSocket upgrade fragility on browser reload. | - Requires a `NetworkCallback` listener to rebind cleanly within 1–2s if Tailscale restarts. | **RECOMMENDED** |
| **Option B: Wildcard `0.0.0.0` Binding Relying on `socket.localAddress`** | Binds once to `0.0.0.0:8765` and checks `socket.localAddress` in software. | - Never needs to rebind when interfaces flap. | - Vulnerable to Linux Weak Host Model cross-interface packets unless kernel `rp_filter` / `netd` rules block `wlan0` ingress.<br>- Ktor `local` API does not reliably report kernel `getsockname()`. | **REJECTED as Primary** (Only acceptable if supplemented by `SO_BINDTODEVICE` and verified against static-route LAN spoofing in Slice 1). |
| **Option C: WebSockets as Primary Command Channel** | Uses persistent WebSocket for `RING`/`STOP` and status. | - Bidirectional single socket when healthy. | - Half-open sockets after phone sleep or rapid `F5` reloads delay or drop `RING`/`STOP` commands. | **REJECTED** |

### 6.2 Client Identity & Authentication Options

| Option | How Pairing, Session, and Command Auth Work Together | Security & Usability Trade-Offs | Verdict |
| :--- | :--- | :--- | :--- |
| **Auth Option 1: Physical One-Time Pairing → Peer-IP-Bound Server Session + `HttpOnly` Cookie + Per-Session HMAC CSRF Header** | 1. **Pairing**: User opens 120s window on phone; enters 8-char CSPRNG code on laptop (`POST /api/v1/pair`).<br>2. **Session Creation**: Phone records laptop's raw Tailscale peer IP bytes (`boundPeerIpBytes`), creates 256-bit `sessionId` (set as `HttpOnly; SameSite=Strict` cookie) and 256-bit `csrfToken = HMAC-SHA256(masterKey, sessionId)` (returned in JSON body into JS memory).<br>3. **Reload Bootstrap**: On `F5`, browser sends `HttpOnly` cookie from `boundPeerIpBytes` to `GET /api/v1/session`, restoring `csrfToken` into JS memory.<br>4. **Command Auth**: `POST /api/v1/command` requires matching `boundPeerIpBytes` + valid `HttpOnly` cookie + matching `X-AntiHide-CSRF: <csrfToken>` header. | - Zero secrets in URLs, logs, or `localStorage`/`sessionStorage` (satisfies Invariant #5).<br>- Non-browser scripts or cross-origin tabs cannot forge `X-AntiHide-CSRF` because they cannot read `/api/v1/session`.<br>- Stolen cookie cannot be used from another IP because `boundPeerIpBytes` is enforced on every request.<br>- Supports up to 4 concurrent session slots for the same `boundPeerIpBytes` so numeric IP and MagicDNS tabs both stay logged in. | **RECOMMENDED** |
| **Auth Option 2: Cookie + Static `X-AntiHide-CSRF: 1` Header** | Checks `HttpOnly` cookie and a fixed string header `X-AntiHide-CSRF: 1`. | - Static header adds no cryptographic entropy; single-slot cookie breaks when switching between IP and MagicDNS URLs. | **REJECTED** (Replaced by Auth Option 1). |
| **Auth Option 3: Tailscale HTTPS (`tailscale cert`) + Mutual TLS (mTLS) Client Certificates** | Uses TLS client certificates installed in the Windows 11 certificate store. | - Strongest transport-layer security.<br>- High setup friction (requires Tailscale HTTPS admin configuration and manual client cert installation in Windows 11). | **OPTIONAL ENHANCEMENT** (Not required for core operation, as WireGuard already encrypts the Tailscale `tun0` transport). |

---

## 7. Recommended Architecture and Justification

### 7.1 Network Binding & Layer 1 Admission (`TailscaleInterfaceServer` + `RawSocketAdmissionVerifier`)
1. **Interface-Scoped Socket Binding**:
   - The server queries `ConnectivityManager` and `NetworkInterface.getNetworkInterfaces()` to locate the active Tailscale VPN interface (`tun0`) and its assigned Tailscale IPv4 (`100.64.0.0/10`) and IPv6 (`fd7a:115c:a1e0::/48`) `InetAddress` instances.
   - Instead of trusting `0.0.0.0` blindly, the server binds dedicated listening sockets directly to the phone's active Tailscale `InetAddress` (and binds the underlying socket descriptor to Android's active VPN `Network` via `vpnNetwork.bindSocket(socket)` where supported by the engine, plus `127.0.0.1` only in debug instrumentation test builds).
   - **Why Prefer a Thin `java.net.ServerSocket` / `Socket` HTTP/1.1 + SSE Engine (or Raw-Socket Ktor CIO Hook)**: Using `java.net.ServerSocket` directly (~300 lines of clean, dependency-free Kotlin) allows calling `vpnNetwork.bindSocket(serverSocket)` before `bind()`, and gives immediate access to `acceptedSocket.inetAddress.address` (`ByteArray`) and `acceptedSocket.localAddress.address` (`ByteArray`) on the very first line of `accept()` before parsing a single HTTP byte. (Slice 1 compares this directly on the OPPO A58).
2. **Raw-Byte Peer Verification**:
   - Every accepted connection extracts `remoteBytes = socket.inetAddress.address` (a pure memory read returning 4 bytes for IPv4 or 16 bytes for IPv6, with **zero** DNS lookups).
   - Verifies bitwise membership in `100.64.0.0/10` (`(remoteBytes[0].toInt() and 0xFF) == 100 && (remoteBytes[1].toInt() and 0xC0) == 0x40`) or `fd7a:115c:a1e0::/48`.
   - `getHostName()` and `origin.remoteHost` are completely absent from the codebase.

### 7.2 Layer 2 Integrated Authentication & Authorization (`SessionAuthManager`)
Pairing, session generation, cookie validation, reload bootstrap, and command authorization operate as a single state-verified protocol:

1. **Pairing State Machine (`POST /api/v1/pair`)**:
   - Default state on phone: `PairingWindow.Closed`.
   - When the user unlocks the OPPO A58 and taps **"Pair Laptop"**, the phone generates a 40-bit CSPRNG pairing code (8 Base32 characters, e.g., `K7M9-P4X2`) valid for 120 seconds and at most 5 failed attempts.
   - The laptop submits `POST /api/v1/pair` with JSON `{ "code": "K7M9-P4X2" }`.
   - If the connection passed Layer 1, the window is open, and `MessageDigest.isEqual` matches the code:
     - The pairing window immediately transitions to `Closed` (single-use).
     - The server creates an `AuthorizedSession` entry:
       - `sessionId`: 32 random bytes (`SecureRandom`), encoded as 43-char Base64URL.
       - `csrfToken`: 32 bytes computed via `HMAC-SHA256(keystoreSecretKey, sessionId)`, encoded as 43-char Base64URL.
       - `boundPeerIp`: The exact 4-byte or 16-byte `remoteBytes` of the laptop's Tailscale IP.
       - `createdAtEpochMs`, `expiresAtEpochMs` (14 days sliding TTL).
     - Stored in a bounded 4-slot session table in `EncryptedSharedPreferences` keyed by `(sessionId)` so the same laptop (`boundPeerIp`) can hold active sessions on **both** `http://<tailscale-ip>:8765` and `http://<magicdns-hostname>:8765` simultaneously.
     - Response sets `Set-Cookie: antihide_sid=<sessionId>; HttpOnly; SameSite=Strict; Path=/` and returns HTTP `200 OK` with JSON `{ "authenticated": true, "csrfToken": "<csrfToken>", "state": { ... } }`.

2. **Session Bootstrap Across Browser Reloads (`GET /api/v1/session`)**:
   - The browser holds `csrfToken` strictly in a JavaScript module closure variable (`let activeCsrfToken = null`). Nothing is ever written to `localStorage`, `sessionStorage`, `IndexedDB`, or URL hashes/queries.
   - When the user refreshes the page (`F5` or `Ctrl+F5`) or opens a new tab on a paired origin, the browser automatically attaches the `HttpOnly` cookie `antihide_sid` to `GET /api/v1/session`.
   - The server verifies:
     1. Layer 1 raw-byte Tailscale admission passed.
     2. `antihide_sid` exists in `SessionAuthManager` and is not expired.
     3. The current socket's `remoteBytes` exactly match `session.boundPeerIp` (constant-time byte comparison).
   - If all three hold, the server returns `200 OK` with `{ "authenticated": true, "csrfToken": "<csrfToken>", "state": { ... } }`. If any check fails, it returns `401 Unauthorized` with `{ "authenticated": false }`, prompting the Pairing UI cleanly.

3. **Per-Command Authorization (`POST /api/v1/command`)**:
   - Every command (`RING`, `STOP`, `ARM`, `DISARM`, `SET_VOLUME`) must pass:
     1. Layer 1 Tailscale `tun0` + raw peer byte check (`403` on failure).
     2. Valid `antihide_sid` cookie + matching `session.boundPeerIp` (`401` on failure).
     3. Valid `X-AntiHide-CSRF` header matching `session.csrfToken` via `MessageDigest.isEqual` (`403` with `{"error":"INVALID_CSRF_TOKEN"}` on failure).
     4. Valid `AntiHideStateMachine` precondition (e.g., `RING` requires `armState == ARMED`, returning `409 Conflict` if `DISARMED`).

---

## 8. Networking Proof-of-Concept Design (Slice 1 Validation Gate)

Before building alarm playback, lock-screen UI, or optional features, **Slice 1** builds and tests the minimal **Networking & Auth Proof of Concept (PoC)** on the OPPO A58 and Windows 11 laptop.

### 8.1 What the Slice 1 PoC Implements
1. **Interface-Bound Server (`TailscaleHttpServer`)**:
   - Binds to the OPPO A58's active Tailscale `tun0` `Network` (`vpnNetwork.bindSocket`) and Tailscale IP addresses (`100.x.y.z` / `fd7a:...`) on port `8765`, with a `ConnectivityManager.NetworkCallback` that automatically rebinds if `tun0` restarts.
2. **Layer 1 & Layer 2 Security Pipeline**:
   - `RawSocketAdmissionVerifier` + `SessionAuthManager` (implementing the full 4-stage Pairing → Session Issuance → Reload Bootstrap → CSRF Command Verification lifecycle described in Section 7.2).
3. **PoC Endpoints**:
   - `GET /`, `GET /app.js`, `GET /styles.css` (Diagnostic PoC Dashboard).
   - `GET /api/v1/ping` (Unauthenticated reachability check returning `{"status":"ok"}`).
   - `POST /api/v1/pair` (Exchanges 8-char one-time code for `HttpOnly` cookie + `csrfToken`).
   - `GET /api/v1/session` (Validates `HttpOnly` cookie + `boundPeerIp`, returns `csrfToken` and current state on reload).
   - `GET /api/v1/events` (Authenticated SSE stream emitting state updates + 15s `: heartbeat`).
   - `POST /api/v1/echo-command` (Authenticated + CSRF-verified command echo test with `commandId` idempotency check).

### 8.2 The 14 Mandatory Hardware Verification Experiments (OPPO A58 + Windows 11)

| Exp ID | Experiment | Exact Procedure on OPPO A58 + Windows 11 | Pass Criterion |
| :--- | :--- | :--- | :--- |
| **E-01** | **Initial Connection & Pairing (IP + MagicDNS)** | Open `http://<tailscale-ip>:8765/` in Tab A, pair with code #1. Open `http://<magicdns-host>:8765/` in Tab B, pair with code #2. | Both origins issue `HttpOnly` cookies, receive `csrfToken`, and display `CONNECTED` simultaneously (`200 OK`). |
| **E-02** | **Repeated Normal Page Loads (`F5`)** | Press `F5` 50 consecutive times on the numeric IP tab and 50 consecutive times on the MagicDNS tab. | 100 / 100 reloads succeed (`200 OK` on `/`, `/app.js`, `/api/v1/session`, `/api/v1/events`); **0** `403` or `401` errors. |
| **E-03** | **Hard Refreshes (`Ctrl+F5`)** | Press `Ctrl+F5` 30 consecutive times on the MagicDNS tab and 30 times on the numeric IP tab. | 60 / 60 hard reloads return `200 OK` with `Cache-Control: no-store`; `GET /api/v1/session` restores `csrfToken` into JS memory every time. |
| **E-04** | **Fresh Browser Tabs** | Open 5 fresh tabs to `http://<magicdns-host>:8765/` and dispatch `POST /api/v1/echo-command` from each tab. | All 5 tabs bootstrap automatically via the `HttpOnly` cookie and execute `echo-command` with `200 OK`. |
| **E-05** | **Browser Closure & Reopening** | Close Windows 11 browser completely (`Alt+F4`), wait 15 seconds, reopen browser to both IP and MagicDNS URLs. | Both origins automatically restore session via `GET /api/v1/session` (`200 OK`) and execute `echo-command` without re-pairing. |
| **E-06** | **HTTP Resource Loading & Status Codes** | Inspect DevTools Network tab across all routes (`GET /`, `GET /api/v1/session`, `POST /api/v1/echo-command`, `GET /nonexistent`). | Valid routes return `200 OK`; unknown route returns `404`; zero unexpected `403` or `500` responses. |
| **E-07** | **SSE Stream Establishment, Auth & Revocation** | Keep `GET /api/v1/events` open for 5 minutes; then tap "Revoke Paired Sessions" on the OPPO A58. | SSE receives `: heartbeat` every 15s for 5 minutes; upon revocation, stream closes within 1s and next `/api/v1/session` returns `401 Unauthorized`. |
| **E-08** | **Repeated Connect / Disconnect Cycles** | Run 25 rapid SSE disconnect/reconnect cycles + 25 `echo-command` dispatches from the PoC dashboard harness. | 25 / 25 cycles recover cleanly with zero socket leaks on the phone (`ss -tan` / `netstat` check). |
| **E-09** | **Temporary Wi-Fi & Tailscale Interruptions** | Toggle OPPO A58 Wi-Fi OFF for 15s then ON. Toggle Windows 11 Tailscale OFF for 15s then ON. | Server rebinds to `tun0` automatically; browser transitions `CONNECTED → RECONNECTING → CONNECTED` within `< 10s` without pressing `F5`. |
| **E-10** | **Android Service Restart** | Force-restart `AntiHideForegroundService` on the OPPO A58 while browser tab stays open. | Service restarts, loads persisted session table from `EncryptedSharedPreferences`, rebinds `tun0:8765`, and browser reconnects within `< 5s` while staying authenticated. |
| **E-11** | **Client-Address Representation Changes** | Populate Android `netd` reverse DNS cache (`ping -a` / `nslookup` via `100.100.100.100`), then send requests via IPv4, IPv6 (`http://[fd7a:...]:8765`), short MagicDNS name, and FQDN. | 100% of requests pass Layer 1 admission; server logs confirm `InetAddress.getAddress()` returns raw 4-byte or 16-byte arrays regardless of reverse DNS state. |
| **E-12A** | **Weak-Host-Model Cross-Interface Static Route Attack Test** | Connect Windows 11 and OPPO A58 to the same local Wi-Fi (`192.168.1.x`). Stop Tailscale on Windows 11. On Windows 11 Administrator PowerShell, add a static route: `route add <phone-tailscale-ip> mask 255.255.255.255 <phone-wifi-ip>`. Attempt `curl http://<phone-tailscale-ip>:8765/api/v1/ping` and `curl http://<phone-wifi-ip>:8765/api/v1/ping` (then delete static route). | Both connection attempts over unencrypted `wlan0` **fail** (Connection Refused / Timeout / `403`), proving that `tun0` binding + raw peer verification prevents Weak-Host-Model cross-interface bypass. |
| **E-12B** | **Integrated Auth & CSRF Negative Matrix** | Over Tailscale, test: (1) `POST /api/v1/echo-command` without cookie (`401`), (2) with valid cookie but missing `X-AntiHide-CSRF` (`403`), (3) with valid cookie but wrong `X-AntiHide-CSRF` (`403`), (4) with valid cookie + CSRF replayed from a different Tailscale device IP (`401`), (5) 6 wrong pairing codes (`429`). | 100% of negative test cases are rejected with the exact expected HTTP status code. |
| **E-13** | **Recovery After Extended Offline Period** | Disable Tailscale on OPPO A58 for 3 minutes while browser remains open; re-enable Tailscale. | Browser automatically recovers to `CONNECTED` within `< 10s` of Tailscale returning and successfully executes `echo-command`. |

---

## 9. Networking Validation Criteria and Fallback Decisions

### 9.1 Go / No-Go Criteria for Slice 1
Slice 1 passes **only** if:
1. **0 False Rejections**: `0` `HTTP 403` or unexpected `HTTP 401` responses across all 160 browser reloads (E-02, E-03) and all address representations (E-11).
2. **100% Cross-Interface & Auth Isolation**: Both the Weak-Host-Model static-route attack (E-12A) and all 5 authentication/CSRF negative tests (E-12B) are blocked 100% of the time.
3. **Zero Secret Leakage**: Inspection of `window.localStorage`, `window.sessionStorage`, `document.cookie`, URL strings, and Logcat shows zero session IDs or CSRF secrets.

### 9.2 Architectural Fallbacks If Slice 1 Uncovers an Engine Issue

| Potential Slice 1 Finding | Diagnostic Signal | Pre-Planned Fallback Architecture |
| :--- | :--- | :--- |
| **Ktor CIO does not allow calling `vpnNetwork.bindSocket()` on the underlying `ServerSocket` or obscures raw `InetAddress` bytes** | `E-12A` static route test reaches Ktor, or socket extraction requires reflective access. | **Fallback A (Direct `java.net.ServerSocket` Transport)**: Use a 300-line Kotlin coroutine wrapper around standard `javax.net.SocketFactory` / `java.net.ServerSocket` created via `vpnNetwork.getSocketFactory().createServerSocket(8765, 50, tailscaleInetAddress)`. This guarantees kernel-level `SO_BINDTODEVICE` (`fwmark`) binding to `tun0` and direct `socket.inetAddress.address` byte array inspection. |
| **SSE (`EventSource`) stalls across `tun0` rebinds without firing browser `onerror`** | Browser stays in `CONNECTED_LIVE` for `> 20s` after `E-09` Wi-Fi toggle. | **Fallback B (Client Heartbeat Watchdog + Polling Primary)**: Client enforces a strict 20s timer on SSE `: heartbeat` frames; on timeout, it calls `eventSource.close()` and uses 2-second `GET /api/v1/status` polling as the primary transport. |

---

## 10. System Architecture and Component Responsibilities

    +-------------------------------------------------------------------------+
    |                     WINDOWS 11 LAPTOP (Browser SPA)                     |
    |                                                                         |
    |  +-------------------+   +----------------------+   +----------------+  |
    |  | Dashboard UI View |<--| State & Auth Manager |<--| SSE + 2s Poll  |  |
    |  | (Ring/Stop/Vol)   |   | (In-RAM csrfToken)   |   | Watchdog       |  |
    |  +---------+---------+   +-----------+----------+   +--------+-------+  |
    |            |                         |                       ^          |
    |            | POST /api/v1/command    | GET /api/v1/session   |          |
    |            | Cookie: antihide_sid    | (On F5 Reload)        |          |
    |            | X-AntiHide-CSRF: <hmac> |                       |          |
    +------------+-------------------------+-----------------------+----------+
                 |                         |                       |
                 |        Tailscale WireGuard Tunnel (`tun0`)      |
                 |     (100.64.0.0/10  or  fd7a:115c:a1e0::/48)    |
                 v                         v                       |
    +-------------------------------------------------------------------------+
    |                  OPPO A58 ANDROID PHONE (`tun0` Network)                |
    |                                                                         |
    |  +-------------------------------------------------------------------+  |
    |  |            AntiHideForegroundService (Single Process)             |  |
    |  |                                                                   |  |
    |  |  1. TailscaleInterfaceServer & RawSocketAdmissionVerifier         |  |
    |  |     - Bound specifically to `tun0` VPN Network & Tailscale IPs    |  |
    |  |     - Verifies raw 4/16-byte `socket.inetAddress.address`         |  |
    |  |                                                                   |  |
    |  |  2. SessionAuthManager (4-Stage Integrated Auth)                  |  |
    |  |     - 120s Physical Pairing Window (8-char CSPRNG code)           |  |
    |  |     - 4-Slot Session Table (supports IP + MagicDNS simultaneously)|  |
    |  |     - Enforces HttpOnly Cookie + boundPeerIp + HMAC CSRF Header   |  |
    |  |                                                                   |  |
    |  |  3. AntiHideStateMachine (Mutex-Guarded Single Source of Truth)   |  |
    |  |     - States: IDLE | RINGING ; Readiness: ARMED | DISARMED        |  |
    |  |     - 64-entry CommandId LRU Deduplicator + 180s Auto-Stop Timer  |  |
    |  |                |                                   |              |  |
    |  |                v                                   v              |  |
    |  |  4. AlarmAudioController              5. Local Stop Surfaces      |  |
    |  |     - USAGE_ALARM + Built-in Speaker     - Lock-Screen Activity   |  |
    |  |     - Logarithmic Player Gain            - Notification Action    |  |
    |  |     - Zero Global Volume Mutation        - In-App Stop Button     |  |
    |  +-------------------------------------------------------------------+  |
    +-------------------------------------------------------------------------+

### Component Responsibilities
1. **`TailscaleInterfaceServer`**: Monitors Android `ConnectivityManager` for the active `TRANSPORT_VPN` network (`tun0`), binds the listening socket directly to that `Network` and its Tailscale `InetAddress` endpoints, and cleanly rebinds when `tun0` changes.
2. **`RawSocketAdmissionVerifier`**: Inspects only `socket.inetAddress.address` (`ByteArray`) and `socket.localAddress.address` (`ByteArray`). Rejects any peer not in `100.64.0.0/10` or `fd7a:115c:a1e0::/48` (or `127.0.0.1` in local instrumentation tests). Never calls `getHostName()`.
3. **`SessionAuthManager`**: Owns the pairing window state, constant-time code verification, Android Keystore-backed `EncryptedSharedPreferences` 4-slot session store, `HttpOnly; SameSite=Strict` cookie issuance, `boundPeerIp` byte matching, and per-session `HMAC-SHA256` `X-AntiHide-CSRF` verification.
4. **`AntiHideStateMachine`**: Coroutine `Mutex`-protected state reducer holding `SystemState(alarmState, armState, volumePercent, stateVersion, ringStartedAtEpochMs)`. Deduplicates `commandId` UUIDs (64-entry ring buffer) and runs the 180-second safety auto-stop watchdog.
5. **`AlarmAudioController`**: Observes `AntiHideStateMachine`. Plays looping alarm audio over `USAGE_ALARM`, routes output to `AudioDeviceInfo.TYPE_BUILTIN_SPEAKER`, scales player-local gain without touching global Android volume sliders, and triggers `VibrationAttributes.USAGE_ALARM`.
6. **`AntiHideForegroundService`**: Manages component lifecycles, `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`), ringing `WakeLock` (`PARTIAL_WAKE_LOCK`), ongoing notification, and lock-screen `AlarmRingActivity`.

---

## 11. Security Threat Model and Security Invariants

### 11.1 Assets, Trust Boundaries, and Attackers
- **Protected Assets**: (1) Alarm acoustic control (`RING`/`STOP`), (2) System availability when the phone is lost, (3) Session & pairing credentials, (4) User privacy on untrusted networks.
- **Trust Boundaries**:
  1. **Untrusted Local Wi-Fi (`wlan0`) vs. Encrypted Tailscale Tunnel (`tun0`)**: Mitigated by binding the listening socket directly to the `tun0` VPN `Network` and Tailscale `InetAddress` rather than `0.0.0.0`, verified empirically by the `E-12A` static-route attack test.
  2. **Unpaired Tailnet Peers vs. Paired Windows 11 Laptop**: Mitigated by requiring both a valid `antihide_sid` `HttpOnly` cookie and a byte-for-byte match between the socket's remote Tailscale IP (`remoteBytes`) and the session's `boundPeerIp`.
  3. **Malicious Third-Party Websites Open in Other Laptop Browser Tabs vs. AntiHide Dashboard Origin**: Mitigated by `SameSite=Strict` on the cookie, zero `Access-Control-Allow-Origin` headers, and requiring `X-AntiHide-CSRF: <256-bit-hmac>` on all `POST` requests.
  4. **Locked Physical OPPO A58 Screen vs. Unlocked Admin UI**: A person holding the locked phone can tap `STOP` to silence an active alarm, but cannot view pairing codes, revoke sessions, or disarm the service without unlocking the phone.

### 11.2 Verification of All 10 Security Invariants

| # | Security Invariant | How It Is Preserved and Tested |
| :--- | :--- | :--- |
| **1** | The alarm must never start without an explicit, authenticated `RING` command. | Only `Command.Ring` dispatched after passing Layer 1 (`tun0` + peer IP) and Layer 2 (`antihide_sid` cookie + `boundPeerIp` + `X-AntiHide-CSRF` HMAC) while `armState == ARMED` can transition `alarmState` to `RINGING`. |
| **2** | Arming, disarming, startup, refreshes, reconnects, heartbeats, and recovery must never activate the alarm. | `Command.Arm`, `Command.Disarm`, `GET /`, `GET /api/v1/session`, `GET /api/v1/events`, and `Service.onCreate/onStartCommand` have no code path that sets `alarmState = RINGING`. Service startup initializes `alarmState = IDLE`. |
| **3** | Local `STOP` must remain available independently of network status. | Lock-screen `AlarmRingActivity`, notification action `ACTION_LOCAL_STOP`, and in-app `STOP` button invoke `AntiHideStateMachine.process(Command.Stop(LOCAL))` directly in-process. |
| **4** | Unauthorized clients must not be able to control the alarm. | Enforced by the 4-stage pipeline (`tun0` socket binding → raw peer IP check → `HttpOnly` cookie + `boundPeerIp` check → per-session HMAC `X-AntiHide-CSRF` check). |
| **5** | Secrets must not appear in logs, URLs, diagnostic responses, browser persistence, or source control. | `antihide_sid` is in an `HttpOnly` cookie; `csrfToken` lives strictly in a JS RAM variable (`let activeCsrfToken`) re-fetched on reload via `GET /api/v1/session`. Static tests ban `localStorage`, `sessionStorage`, and URL token parameters. |
| **6** | Duplicate and replayed commands must not cause unintended behavior. | `AntiHideStateMachine` records the last 64 `commandId` UUIDs; duplicate `RING` or `STOP` requests return the current state (`200 OK`) without re-triggering audio. |
| **7** | Network failures must never silently weaken authentication or authorization. | All admission and auth checks are fail-closed (`try/catch` defaults to `403`/`401` denial). Rebinding after a `tun0` flap preserves all auth checks. |
| **8** | Diagnostic mechanisms must not expose secrets or create an unnecessary information leak. | Unauthenticated `/api/v1/ping` returns only `{"status":"ok"}`. Authenticated `/api/v1/diagnostics` redacts all session IDs, CSRF tokens, and raw IP octets (`100.x.x.x`). |
| **9** | Alarm state must remain consistent after disconnection, reconnection, and service restart. | Upon every reconnect or reload, the browser fetches `GET /api/v1/session` (or `/api/v1/status`) containing the authoritative `stateVersion` and `alarmState`, immediately overwriting stale client UI state. |
| **10** | Security-sensitive functionality must include negative tests proving that unauthorized or malformed operations are rejected. | Every slice includes automated JUnit negative tests and real-device negative tests (`E-12A`, `E-12B`, `RDV-06`). |

---

## 12. Dependency Graph and Slice Sequence

    [Slice 1: Networking, `tun0` Binding & Integrated Auth PoC (VALIDATION GATE)]
                                          |
                                          v  (Gate 1: 14/14 Real-Device Network & Auth Tests Pass)
    [Slice 2: Minimal End-to-End Core — State Machine, Alarm Audio & Remote/Local Stop]
                                          |
                                          v  (Gate 2: Remote & Disconnected Local Ring/Stop Verified)
    [Slice 3: Android Foreground Service, Screen-Off Operation & ColorOS Hardening]
                                          |
                                          v  (Gate 3: 60-Min Unplugged Screen-Off Ring Verified)
    [Slice 4: Real-Time State Synchronization (SSE + Polling Fallback) & Reconnection]
                                          |
                                          v  (Gate 4: Automatic Recovery Across Wi-Fi/Tailscale Drops)
    [Slice 5: Production Browser Dashboard & Sanitized Diagnostics]
                                          |
                                          v  (Gate 5: Core System Complete — Goals 1–7 Met)
              +---------------------------+---------------------------+
              |                           |                           |
              v                           v                           v
    [Slice 6: Optional —        [Slice 7: Optional —        [Slice 8: Optional —
     Safe Arm/Disarm Control]    Configurable Volume &       BLE Fallback Evaluation &
                                 Built-in Speaker Routing]   Implementation (If Justified)]

---

## 13. Detailed Slice-by-Slice Implementation Roadmap

---

### Slice 1: Networking, `tun0` Binding & Integrated Auth Proof of Concept (Mandatory Validation Gate)

- **Objective**: Prove on the actual OPPO A58 and Windows 11 laptop that interface-scoped `tun0` socket binding, raw byte-array peer verification, and the 4-stage Pairing + Session + Reload Bootstrap + CSRF Authorization flow work reliably across 160 browser reloads and block all unauthorized/cross-interface attacks before building application features.
- **Scope**:
  - `TailscaleInterfaceServer` binding specifically to the OPPO A58's active `TRANSPORT_VPN` (`tun0`) `Network` and Tailscale IPv4/IPv6 addresses on port `8765`, with `ConnectivityManager.NetworkCallback` auto-rebinding.
  - `RawSocketAdmissionVerifier` checking `socket.inetAddress.address` (`ByteArray`) against `100.64.0.0/10` and `fd7a:115c:a1e0::/48` (plus `127.0.0.1` only in local test mode).
  - `SessionAuthManager` implementing:
    1. 120-second physical opt-in pairing window with 8-char CSPRNG code and 5-attempt lockout (`POST /api/v1/pair`).
    2. 4-slot session table in `EncryptedSharedPreferences` storing `(sessionId, csrfToken, boundPeerIpBytes, expiresAt)` so numeric IP and MagicDNS origins can stay authenticated simultaneously.
    3. Reload bootstrap endpoint (`GET /api/v1/session`) verifying `HttpOnly` cookie + `boundPeerIpBytes` and returning `csrfToken` into JS memory.
    4. Authenticated + CSRF-verified test command endpoint (`POST /api/v1/echo-command`) and SSE heartbeat stream (`GET /api/v1/events`).
  - Minimal PoC HTML/JS test page (`assets/dashboard/index.html`, `app.js`) and minimal phone UI screen to open the pairing window and view connection logs.
- **Explicit Non-Goals**: No alarm sound, no `MediaPlayer`, no lock-screen activity, no Arm/Disarm, no volume control.
- **Prerequisites & Dependencies**: Existing Android project workspace inspected and preserved; Tailscale active on OPPO A58 and Windows 11 laptop.
- **Architecture & Implementation Approach**:
  - Use a clean, inspectable `ServerSocket` loop created via `vpnNetwork.getSocketFactory().createServerSocket(8765, 50, tailscaleInetAddress)` (or verify Ktor CIO raw socket hook against `E-12A`; if Ktor CIO cannot bind to `vpnNetwork` or obscures raw socket bytes, use the `ServerSocket` adapter immediately).
  - Serve all responses with `Cache-Control: no-store, no-cache, must-revalidate, max-age=0`.
- **Expected Files / Modules**:
  - `net/TailscaleInterfaceServer.kt`
  - `net/RawSocketAdmissionVerifier.kt`
  - `auth/SessionAuthManager.kt`
  - `assets/dashboard/index.html`, `assets/dashboard/app.js`
  - `test/net/RawSocketAdmissionVerifierTest.kt`
  - `test/auth/IntegratedAuthFlowTest.kt`
  - `test/architecture/BannedApisSourceTest.kt`
- **Automated Tests**:
  - **Integrated Auth Flow Test (`IntegratedAuthFlowTest`)**: Tests the entire chain together—unopened pairing window rejected (`403`/`401`) → open window → wrong code rejected → 6th wrong code locks window (`429`) → reopen window → valid code issues `antihide_sid` cookie + `csrfToken` bound to `100.101.102.103` → reload bootstrap `GET /api/v1/session` with cookie from `100.101.102.103` returns `csrfToken` (`200`) → `GET /api/v1/session` with same cookie from different peer IP `100.101.102.99` rejected (`401`) → `POST /api/v1/echo-command` with cookie + valid `X-AntiHide-CSRF` succeeds (`200`) → `POST` with missing or wrong `X-AntiHide-CSRF` rejected (`403`) → second pairing from `100.101.102.103` (simulating MagicDNS tab) creates slot #2 without invalidating slot #1 → session revocation clears all slots (`401`).
  - **CIDR Byte Boundary Tests**: Tests raw byte arrays for `100.63.255.255` (reject), `100.64.0.0` (accept), `100.127.255.255` (accept), `100.128.0.0` (reject), `fd7a:115c:a1e0::1` (accept), `192.168.1.50` (reject).
  - **Static Source Guard (`BannedApisSourceTest`)**: Fails build if `remoteHost`, `getHostName(`, `localStorage`, or `sessionStorage` appears in `src/main`.
- **Real-Device Verification Procedures**:
  - Execute all 14 experiments (**E-01 through E-13**, including **E-12A** static-route LAN bypass and **E-12B** negative auth matrix) on the OPPO A58 and Windows 11 laptop.
- **Measurable Acceptance Criteria**:
  - **160 / 160 (`100%`)** reloads (50 `F5` + 30 `Ctrl+F5` on IP URL, and 50 `F5` + 30 `Ctrl+F5` on MagicDNS URL) return `200 OK` with **0** `403` or `401` errors.
  -Both IP and MagicDNS tabs remain simultaneously authenticated and can send `POST /api/v1/echo-command`.
  - **100%** of cross-interface static-route attempts (`E-12A`) and negative auth/CSRF attempts (`E-12B`) are rejected.
- **Failure Scenarios & Recovery Behavior**:
  - If `tun0` is not active when the app starts, server enters `WAITING_FOR_VPN` state and binds automatically within 1 second of `ConnectivityManager` reporting `TRANSPORT_VPN` available.
- **Security Implications & Required Negative Tests**:
  - `E-12A` (LAN static-route spoofing) and `E-12B` (missing cookie, missing CSRF, wrong CSRF, different peer IP, brute-force PIN) must all pass before Slice 1 can be marked complete.
- **Diagnostic Evidence to Collect**:
  - Windows 11 browser HAR log across 160 reloads; PowerShell output of `E-12A` and `E-12B`; sanitized `adb logcat -s AntiHideNet` output.
- **Definition of Done**: All unit/integration tests and all 14 real-device experiments pass with collected evidence.
- **Explicit Go/No-Go Decision**: **GO** to Slice 2 only if all 14 experiments pass with zero `403` errors on legitimate traffic and 100% rejection on negative tests.
- **Conditions Requiring Work to Stop**: Any `403` on legitimate reloads, or if `E-12A` allows packets over `wlan0`.
- **Conditions Before Next Slice Begins**: User review and approval of Slice 1 hardware verification results.

---

### Slice 2: Minimal End-to-End Core — State Machine, Alarm Playback & Remote/Local Stop

- **Objective**: Build the core phone-finding loop on top of the validated Slice 1 networking foundation: authenticated remote `RING` starts the alarm on the OPPO A58, and both remote `STOP` (laptop) and local `STOP` (phone) reliably silence it.
- **Scope**:
  - `AntiHideStateMachine`: `Mutex`-guarded state machine (`IDLE`, `RINGING`) with a 64-entry `commandId` deduplication ring buffer and 180-second auto-stop watchdog.
  - `POST /api/v1/command` handling `RING` and `STOP` actions (enforcing Slice 1's full cookie + `boundPeerIp` + `X-AntiHide-CSRF` verification).
  - `AlarmAudioController`: Loops high-visibility alarm audio on `AudioAttributes.USAGE_ALARM` (`CONTENT_TYPE_SONIFICATION`) + hardware vibration (`VibrationAttributes.USAGE_ALARM`).
  - Minimal browser `RING` and `STOP` buttons + phone UI `STOP ALARM` button.
- **Explicit Non-Goals**: No Arm/Disarm toggle yet, no custom volume slider yet, no BLE fallback.
- **Prerequisites & Dependencies**: Slice 1 passed.
- **Architecture & Implementation Approach**:
  - `AntiHideStateMachine` is the single source of truth; `AlarmAudioController` observes state transitions and guarantees `MediaPlayer.stop()`, `release()`, and `abandonAudioFocusRequest()` inside a `finally` block whenever state becomes `IDLE`.
- **Expected Files / Modules**:
  - `domain/AntiHideStateMachine.kt`, `domain/SystemState.kt`
  - `audio/AlarmAudioController.kt`
  - `ui/MainActivity.kt`
  - `res/raw/antihide_alarm.ogg`
  - `test/domain/AntiHideStateMachineTest.kt`
- **Automated Tests**:
  - State machine transition tests (`IDLE -> RINGING -> IDLE`), duplicate `commandId` idempotency (10 identical `RING` requests start audio once), concurrent `RING`/`STOP` coroutine races, 180s virtual-time watchdog auto-stop.
  - Security invariant tests verifying that server startup, `/api/v1/session`, and heartbeats never trigger `RINGING`.
- **Real-Device Verification Procedures**:
  - Set OPPO A58 ringer to Silent/Vibrate. Trigger 20 remote `RING` and `STOP` cycles from Windows 11 (10 via numeric IP tab, 10 via MagicDNS tab).
  - Trigger `RING` from laptop, disconnect laptop Wi-Fi, and tap `STOP ALARM` on the OPPO A58 screen 10 times.
- **Measurable Acceptance Criteria**:
  - Remote `RING` starts audible alarm within `< 1,500 ms` across 20/20 runs.
  - Remote `STOP` and local `STOP` silence audio and vibration within `< 250 ms` across 30/30 runs.
- **Failure Scenarios & Recovery Behavior**:
  - If bundled raw audio resource fails to decode, automatically fall back to `RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)` + continuous vibration.
- **Security Implications & Required Negative Tests**:
  - Verify `POST /api/v1/command` (`{"action":"RING"}`) without valid `antihide_sid` cookie or without valid `X-AntiHide-CSRF` token returns `401`/`403` and produces zero sound.
- **Diagnostic Evidence to Collect**:
  - Sanitized command audit log (`stateVersion`, action, source `REMOTE|LOCAL`, execution time ms).
- **Definition of Done**: Automated tests and 30 real-device `RING`/`STOP` cycles pass on OPPO A58.
- **Explicit Go/No-Go Decision**: **GO** to Slice 3 only when remote and local `RING`/`STOP` succeed 100% of the time.
- **Conditions Requiring Work to Stop**: Any failure to stop audio immediately, overlapping `MediaPlayer` instances on duplicate commands, or unauthorized ring activation.
- **Conditions Before Next Slice Begins**: Slice 2 hardware verification complete.

---

### Slice 3: Android Foreground Service Lifecycle, Screen-Off Operation & ColorOS Hardening

- **Objective**: Ensure AntiHide remains reachable and plays the alarm when the OPPO A58 screen is off and unplugged in Deep Doze, and provide single-tap lock-screen `STOP` without unlocking the phone.
- **Scope**:
  - `AntiHideForegroundService` (`foregroundServiceType="connectedDevice|specialUse"`), `START_STICKY`, and `BootCompletedReceiver`.
  - Scoped `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) while active, plus scoped `PowerManager.WakeLock` (`PARTIAL_WAKE_LOCK`, 185s max timeout) acquired strictly during `RINGING`.
  - Lock-screen `STOP` surfaces:
    1. Ongoing high-priority notification with `STOP ALARM` action button (`PendingIntent` direct to service) visible on the lock screen.
    2. Full-screen lock activity `AlarmRingActivity` (`setShowWhenLocked(true)`, `setTurnScreenOn(true)`) displaying a giant `STOP ALARM` button and intercepting Volume Down/Up hardware keys to stop the alarm.
  - In-app **OPPO ColorOS Readiness Checklist** verifying `PowerManager.isIgnoringBatteryOptimizations()`, `POST_NOTIFICATIONS`, and `NotificationManager.canUseFullScreenIntent()`, with direct buttons to open system settings and step-by-step instructions for ColorOS *"Allow background activity"*.
- **Explicit Non-Goals**: No optional Arm/Disarm or volume features yet.
- **Prerequisites & Dependencies**: Slice 2 passed.
- **Expected Files / Modules**:
  - `service/AntiHideForegroundService.kt`
  - `ui/AlarmRingActivity.kt`
  - `ui/ColorOsReadinessCard.kt`
  - `AndroidManifest.xml`
- **Automated Tests**:
  - Instrumentation tests verifying service start, notification creation, `ACTION_LOCAL_STOP` intent handling, and that service restart initializes `alarmState = IDLE` (Security Invariant #2).
- **Real-Device Verification Procedures**:
  - Force Doze via Wireless ADB (`adb shell dumpsys deviceidle force-idle`) with screen off and test `RING` + lock-screen `STOP`.
  - **60-Minute Unplugged Screen-Off Soak Test (`RDV-03`)**: Unplug OPPO A58, turn screen off, leave untouched for 15 minutes, 30 minutes, and 60 minutes. At each interval, press `RING` on Windows 11 laptop, verify phone rings and wakes lock screen, and test stopping from both the lock screen and laptop.
- **Measurable Acceptance Criteria**:
  - OPPO A58 rings within `< 2,500 ms` at the 15m, 30m, and 60m unplugged screen-off checkpoints.
  - Lock-screen `STOP` (both via `AlarmRingActivity` and notification action) silences the alarm in `< 250 ms` without requiring PIN or fingerprint unlock.
- **Failure Scenarios & Recovery Behavior**:
  - If the user denies Android 14+ `USE_FULL_SCREEN_INTENT`, the lock-screen notification `STOP ALARM` action button still functions independently.
- **Security Implications & Required Negative Tests**:
  - Verify that while the OPPO A58 is locked, `AlarmRingActivity` only exposes `STOP ALARM` and cannot open the pairing window or revoke sessions (`KeyguardManager.isDeviceLocked`).
- **Diagnostic Evidence to Collect**:
  - `adb shell dumpsys deviceidle` logs, `dumpsys power` wake-lock verification (confirming zero wake locks held after `STOP`), and response latencies at 15m, 30m, and 60m.
- **Definition of Done**: 60-minute unplugged screen-off test and lock-screen `STOP` verified on OPPO A58.
- **Explicit Go/No-Go Decision**: **GO** to Slice 4 only after passing the 60-minute unplugged screen-off test.
- **Conditions Requiring Work to Stop**: Phone becomes unreachable during screen-off idle, or lock-screen `STOP` requires unlocking the phone.
- **Conditions Before Next Slice Begins**: Slice 3 real-device verification complete.

---

### Slice 4: Real-Time State Synchronization (SSE + Polling Fallback) & Reconnection Resilience

- **Objective**: Ensure the browser dashboard and phone stay state-consistent across page reloads, Wi-Fi/cellular switches, Tailscale restarts, and temporary network drops.
- **Scope**:
  - `SseStateBroadcaster` streaming JSON `SystemState` (`stateVersion`, `alarmState`, `armState`, `volumePercent`) on every state change plus `: heartbeat\n\n` every 15 seconds.
  - Client `ConnectionManager` in `app.js`:
    - Primary `EventSource('/api/v1/events')` with a 20-second heartbeat watchdog.
    - Automatic fallback to 2-second `GET /api/v1/status` polling whenever SSE is reconnecting.
    - Immediate state reconciliation (`GET /api/v1/session`) on `window.online` and `document.visibilitychange`.
    - Automatic retry on `POST /api/v1/command` (up to 3 attempts at 0ms, 600ms, 1500ms using the identical `commandId`).
- **Explicit Non-Goals**: No new phone audio or optional features.
- **Prerequisites & Dependencies**: Slice 3 passed.
- **Expected Files / Modules**:
  - `server/SseStateBroadcaster.kt`
  - `assets/dashboard/connection.js`
  - `test/server/StateSyncReconciliationTest.kt`
- **Automated Tests**:
  - State version reconciliation tests: client rejects stale responses (`stateVersion < currentVersion`); server cleans up closed SSE sockets immediately upon write failure.
- **Real-Device Verification Procedures**:
  - Start `RING` from laptop → stop locally on locked OPPO A58 → verify laptop dashboard updates to `IDLE` within `< 1,000 ms`.
  - Start `RING` from laptop → disable laptop Wi-Fi → stop locally on OPPO A58 → re-enable laptop Wi-Fi → verify dashboard reconciles to `IDLE` within `< 5s`.
  - Switch OPPO A58 from Wi-Fi to Mobile Data while browser is open → verify automatic transition `CONNECTED -> RECONNECTING -> CONNECTED` within `< 10s`.
- **Measurable Acceptance Criteria**:
  - 100% state convergence within `< 1,000 ms` when connected and within `< 5,000 ms` after network restoration without manual browser reload.
- **Failure Scenarios & Recovery Behavior**:
  - If SSE connections are dropped silently by an intermediate network change, the 20s heartbeat watchdog closes the zombie `EventSource` and switches to 2s polling automatically.
- **Security Implications & Required Negative Tests**:
  - Verify that revoking sessions on the phone immediately terminates active SSE connections and returns `401` on subsequent status polls.
- **Diagnostic Evidence to Collect**:
  - Browser console state-transition timestamps across Wi-Fi/Cellular switches and local-stop reconciliation.
- **Definition of Done**: Automated reconciliation tests and real-device interruption tests pass.
- **Explicit Go/No-Go Decision**: **GO** to Slice 5 only when reconnection and state convergence are 100% reliable.
- **Conditions Requiring Work to Stop**: Dashboard displays stale `RINGING` or `IDLE` state for `> 5s` after network restoration.
- **Conditions Before Next Slice Begins**: Slice 4 hardware verification complete.

---

### Slice 5: Production Browser Dashboard & Sanitized Diagnostics

- **Objective**: Complete the production Windows 11 browser dashboard with clear visual states, elapsed ring timer, pairing screen, and sanitized diagnostic drawer.
- **Scope**:
  - Self-contained HTML/CSS/Vanilla JS SPA (`assets/dashboard/`):
    - **Connection Badge**: `CONNECTED (Live SSE)`, `CONNECTED (Polling)`, `RECONNECTING`, `PAIRING REQUIRED`.
    - **Alarm Control Card**: High-contrast `RING PHONE` and `STOP ALARM` buttons, elapsed ring timer, and optimistic state indicators.
    - **Sanitized Diagnostics Drawer**: Displays `stateVersion`, round-trip latency (ms), transport mode, and last 20 sanitized events (zero cookies, tokens, or raw IPs).
  - Security headers on all responses: `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`.
- **Explicit Non-Goals**: No external CDNs, fonts, or third-party JS dependencies.
- **Prerequisites & Dependencies**: Slice 4 passed.
- **Expected Files / Modules**:
  - `assets/dashboard/index.html`, `assets/dashboard/styles.css`, `assets/dashboard/app.js`
  - `server/SecurityHeaders.kt`
- **Automated Tests**:
  - Header verification tests (CSP, `Cache-Control: no-store`, `X-Content-Type-Options`) and diagnostic payload sanitization tests.
- **Real-Device Verification Procedures**:
  - Verify full workflow in both **Microsoft Edge** and **Google Chrome** on Windows 11 across numeric IP and MagicDNS URLs.
- **Measurable Acceptance Criteria**:
  - Dashboard loads in `< 500 ms` over Tailscale; passes all Core Functional Goals (1–7) and Security Invariants (1–10).
- **Failure Scenarios & Recovery Behavior**:
  - Failed/timed-out command displays an explicit retry error banner rather than leaving the button in an indeterminate spinning state.
- **Security Implications & Required Negative Tests**:
  - Verify `GET /api/v1/diagnostics` returns `401` when unauthenticated and contains zero secret substrings when authenticated.
- **Diagnostic Evidence to Collect**:
  - Browser DevTools Network + Security header capture in Edge and Chrome.
- **Definition of Done**: Core AntiHide V2 system is complete and verified on hardware.
- **Explicit Go/No-Go Decision**: **GO** to Optional Slices (6, 7, 8) only after Core System sign-off.
- **Conditions Requiring Work to Stop**: Any UI ambiguity or security header regression.
- **Conditions Before Next Slice Begins**: Core system sign-off.

---

### Slice 6: Optional Feature — Safe Arm/Disarm Readiness Control

- **Objective**: Add optional `ARMED` / `DISARMED` readiness control on the phone and laptop dashboard, ensuring arming or disarming **never** triggers the alarm.
- **Scope**:
  - Add `armState` (`ARMED`, `DISARMED`) to `SystemState`, persisted in `EncryptedSharedPreferences`.
  - Add `ARM` and `DISARM` actions to `POST /api/v1/command` and phone UI.
  - Enforce invariants:
    - `ARM` transitions `DISARMED -> ARMED` with `alarmState` strictly remaining `IDLE`.
    - `DISARM` transitions `ARMED -> DISARMED` and, if `alarmState == RINGING`, immediately stops the alarm (`RINGING -> IDLE`).
    - `RING` while `DISARMED` is rejected with `HTTP 409 Conflict` (`{"error":"PHONE_DISARMED"}`).
- **Explicit Non-Goals**: No automatic scheduling or location-based arming.
- **Prerequisites & Dependencies**: Slice 5 passed.
- **Automated Tests**:
  - Exhaustive state-machine permutation tests proving `ARM` and `DISARM` never transition `alarmState` to `RINGING`, and `RING` while `DISARMED` never invokes `AlarmAudioController.start()`.
- **Real-Device Verification Procedures**:
  - Toggle `ARM` and `DISARM` 20 times from phone and laptop → confirm 0 sound.
  - Set `DISARMED` → click `RING` on laptop → confirm `409` message and 0 sound.
  - Trigger `RING` while `ARMED` → toggle `DISARM` → confirm immediate silence.
- **Measurable Acceptance Criteria**:
  - 0 acoustic emissions across 20 Arm/Disarm toggles; 100% `409` rejection of `RING` when disarmed.
- **Failure Scenarios & Recovery Behavior**:
  - Missing persisted preference defaults to `ARMED` + `IDLE`.
- **Security Implications & Required Negative Tests**:
  - Unauthenticated or missing-CSRF `ARM`/`DISARM` requests are rejected with `401`/`403`.
- **Diagnostic Evidence to Collect**:
  - State machine transition log across all `ARM`, `DISARM`, and rejected `RING` tests.
- **Definition of Done**: Automated and hardware tests pass.
- **Explicit Go/No-Go Decision**: **GO** to Slice 7 if all invariants hold.
- **Conditions Requiring Work to Stop**: Any scenario where arming activates the alarm.
- **Conditions Before Next Slice Begins**: Slice 6 verification complete.

---

### Slice 7: Optional Feature — Configurable Alarm Volume & Built-in Speaker Routing

- **Objective**: Allow configuring alarm volume (`10%–100%`) without mutating global Android volume settings, and force alarm playback through the OPPO A58 built-in speaker even when Bluetooth audio devices are connected.
- **Scope**:
  - Add `volumePercent` (`10..100`) and `SET_VOLUME` command to `AntiHideStateMachine` and dashboard.
  - Apply logarithmic player-local scaling `gain = (volumePercent / 100.0f).pow(2.0f)` via `MediaPlayer.setVolume(gain, gain)` on `USAGE_ALARM` without calling `AudioManager.setStreamVolume()`.
  - **Scoped Mute Safety Floor**: Only if `audioManager.getStreamVolume(STREAM_ALARM) == 0` (system alarm stream muted at OS level), temporarily raise `STREAM_ALARM` to 50% during `RINGING` and restore it to `0` inside the guaranteed `stop()` `finally` block.
  - Route alarm output to `AudioDeviceInfo.TYPE_BUILTIN_SPEAKER` via `mediaPlayer.setPreferredDevice()`.
- **Explicit Non-Goals**: No permanent modification of global system volume streams.
- **Prerequisites & Dependencies**: Slice 6 passed.
- **Automated Tests**:
  - Unit tests for gain math, clamping (`10..100`), and mock `AudioManager` verification proving `setStreamVolume()` is never called when `STREAM_ALARM > 0`, and that `SET_VOLUME` never transitions `alarmState` to `RINGING`.
- **Real-Device Verification Procedures**:
  - Record OPPO A58 `STREAM_MUSIC`, `STREAM_RING`, `STREAM_ALARM`, and `STREAM_NOTIFICATION` indices.
  - Test `RING` + `STOP` at `20%`, `60%`, and `100%` AntiHide volume → verify audible loudness scaling and confirm via `adb shell cmd media_session volume --get` / Settings UI that all global volume sliders are unchanged after `STOP`.
  - Connect Bluetooth earbuds to OPPO A58 → trigger `RING` → confirm sound plays from the phone's built-in speaker.
- **Measurable Acceptance Criteria**:
  - 0 post-stop changes to global Android volume indices across 10 test runs; built-in speaker outputs sound even with Bluetooth headphones paired.
- **Failure Scenarios & Recovery Behavior**:
  - If `setPreferredDevice()` returns `false`, log warning and continue playback on default alarm path.
- **Security Implications & Required Negative Tests**:
  - Out-of-bounds volume values (`-10`, `500`) are rejected (`400`) or clamped safely without starting the alarm.
- **Diagnostic Evidence to Collect**:
  - Before/during/after `AudioManager` volume stream dump from OPPO A58.
- **Definition of Done**: Non-destructive volume control and speaker routing verified on OPPO A58.
- **Explicit Go/No-Go Decision**: **GO** to Project Completion (or optional Slice 8).
- **Conditions Requiring Work to Stop**: Global device volume remains altered after `STOP`, or `SET_VOLUME` starts the alarm.
- **Conditions Before Next Slice Begins**: Slice 7 hardware verification complete.

---

### Slice 8: Optional Feature — Bluetooth Low Energy (BLE) Fallback Evaluation & Implementation

- **Objective**: Provide an authenticated local BLE GATT fallback so the Windows 11 laptop can ring the OPPO A58 via Web Bluetooth when all IP networks (Wi-Fi, cellular, and Tailscale) are offline.
- **Technical Feasibility & Justification Gate**:
  - **[Verified Fact]**: Windows 11 Edge/Chrome supports Web Bluetooth (`navigator.bluetooth`), and the OPPO A58 supports BLE Peripheral Mode (`BluetoothGattServer`).
  - **[Verified Constraint]**: When Wi-Fi/Tailscale is completely down, the browser cannot fetch `http://100.x.y.z:8765` over IP, and Web Bluetooth requires a **Browser Secure Context** (`https://` or `http://localhost`). Thus, BLE fallback requires saving a standalone offline HTML file (`antihide-ble-fallback.html`) on the Windows 11 laptop served from `localhost` (or an installed PWA).
  - **Decision Gate**: Execute Slice 8 only if you explicitly want Airplane-Mode (Bluetooth-only) fallback after Slices 1–7 are complete.
- **Scope (If Elected)**:
  - `BleFallbackServer` advertising a custom 128-bit UUID in `ADVERTISE_MODE_LOW_POWER` while `ARMED`.
  - **HMAC-SHA256 Challenge-Response GATT Protocol**:
    1. Laptop reads a 16-byte single-use random `nonce` (15s TTL) from `ChallengeCharacteristic`.
    2. Laptop computes `HMAC-SHA256(pairedBleKey, nonce || actionByte || commandId)` and writes to `CommandCharacteristic`.
    3. Phone verifies HMAC in constant time, invalidates `nonce`, and dispatches `RING` or `STOP` to `AntiHideStateMachine`.
- **Automated Tests**:
  - Unit tests for BLE nonce expiration, replay rejection, and HMAC verification.
- **Real-Device Verification Procedures**:
  - Put OPPO A58 in Airplane Mode with Bluetooth ON. From Windows 11 Chrome/Edge, connect via Web Bluetooth, trigger `RING`, and trigger `STOP`.
- **Measurable Acceptance Criteria**:
  - BLE `RING` and `STOP` complete within `< 5s`; replayed GATT payloads are 100% rejected; idle BLE advertising adds `< 2%/hr` battery drain.
- **Definition of Done**: Authenticated BLE fallback verified on OPPO A58 + Windows 11.
- **Explicit Go/No-Go Decision**: Optional milestone sign-off.
- **Conditions Requiring Work to Stop**: Unacceptable ColorOS BLE battery drain or Web Bluetooth adapter instability on Windows 11.

---

## 14. Automated Testing Strategy

1. **Domain & State Machine Unit Suite (`JUnit 5` + `kotlinx-coroutines-test`)**:
   - Tests all state transitions, 64-entry `commandId` idempotency cache, 180s auto-stop watchdog (using virtual time), and Arm/Disarm safety invariants.
2. **Integrated Authentication & Authorization Suite (`IntegratedAuthFlowTest`)**:
   - Tests the complete 4-stage pipeline together: pairing window lifecycle, rate-limiting (`429`), multi-slot session creation (IP + MagicDNS coexistence), `HttpOnly` cookie + `boundPeerIp` verification on reload (`GET /api/v1/session`), per-session `HMAC-SHA256` `X-AntiHide-CSRF` verification on `POST /api/v1/command`, peer-IP mismatch rejection (`401`), and session revocation.
3. **Raw-Byte Network Admission Suite (`RawSocketAdmissionVerifierTest`)**:
   - Tests bitwise CIDR matching on 4-byte (`100.64.0.0/10`) and 16-byte (`fd7a:115c:a1e0::/48`) byte arrays across all boundary values.
4. **Static Security & Architecture Guard (`BannedApisSourceTest`)**:
   - Scans `src/main` and `assets/dashboard` to fail the build if `remoteHost`, `getHostName(`, `localStorage`, `sessionStorage`, or URL token query strings appear anywhere.
5. **On-Device Instrumentation Suite (`androidTest` on OPPO A58)**:
   - Tests `AntiHideForegroundService` startup, `EncryptedSharedPreferences` persistence, `AudioManager` volume non-mutation, and `ACTION_LOCAL_STOP` handling.

---

## 15. Real-Device Verification Plan (OPPO A58 + Windows 11)

No capability is accepted until verified on the physical **OPPO A58** and **Windows 11 laptop**:

| Test ID | Verification Focus | Multi-Run Procedure on OPPO A58 + Windows 11 | Quantitative Pass Threshold | Failure Signal | Diagnostic Evidence to Collect |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **RDV-01** | **Browser Compatibility & 160-Reload Torture** | In Windows 11 **Edge** and **Chrome**: pair both `http://<tailscale-ip>:8765` and `http://<magicdns-host>:8765`. Run 50 normal reloads (`F5`) + 30 hard reloads (`Ctrl+F5`) on each URL. | **160 / 160 (`100%`)** return `200 OK`; **0** `403` or `401` errors; both tabs stay authenticated simultaneously. | Any `403`, unexpected `401`, or broken page load. | Browser HAR export + `adb logcat -s AntiHideNet`. |
| **RDV-02** | **Weak-Host-Model Cross-Interface Attack (`E-12A`)** | Disable laptop Tailscale; add Windows static route `route add <phone-tailscale-ip> mask 255.255.255.255 <phone-wifi-ip>` over local Wi-Fi; attempt HTTP requests to `<phone-tailscale-ip>:8765` and `<phone-wifi-ip>:8765`. | **100%** of requests over `wlan0` fail (refused/timeout/`403`). | Any HTTP response served over unencrypted Wi-Fi when `tun0` binding is active. | PowerShell `curl -v` output + Android socket logs. |
| **RDV-03** | **Integrated Auth & CSRF Negative Suite (`E-12B`)** | Over Tailscale, run automated script testing: (1) unauthenticated `POST /api/v1/command`, (2) valid cookie + missing `X-AntiHide-CSRF`, (3) valid cookie + wrong `X-AntiHide-CSRF`, (4) valid cookie replayed from a 2nd Tailscale peer IP, (5) 6 wrong pairing codes. | **100%** rejected (`401`, `403`, `403`, `401`, `429`); phone remains silent. | Any `200 OK` or alarm activation. | Script output log + server security audit log. |
| **RDV-04** | **60-Minute Unplugged Screen-Off Doze Soak** | Unplug OPPO A58, lock screen, leave idle for **15m**, **30m**, and **60m** (plus `dumpsys deviceidle force-idle`). Trigger `RING` from laptop at each checkpoint. | **3 / 3 (`100%`)** ring audibly within `< 2,500 ms` on first click. | Connection timeout or delayed audio (`> 2.5s`). | `dumpsys deviceidle`, `dumpsys power`, and client latency logs. |
| **RDV-05** | **Disconnected Local Stop & Remote Stop** | Trigger `RING` → disable laptop Wi-Fi → tap `STOP` on locked OPPO A58 screen (10 runs via full-screen activity, 10 runs via notification action). Plus 20 remote `STOP` runs. | **40 / 40 (`100%`)** stop audio within `< 250 ms` without unlocking the phone. | Audio continues or phone requires PIN unlock before stopping. | Audio stop timestamp delta in Logcat. |
| **RDV-06** | **Network & Service Interruption Recovery** | With dashboard open: toggle OPPO A58 Wi-Fi OFF/ON (5x), toggle Tailscale OFF/ON (5x), restart `AntiHideForegroundService` (5x). | **15 / 15 (`100%`)** recover to `CONNECTED` automatically in `< 10s` without pressing `F5`. | Dashboard stuck in `RECONNECTING` or loses pairing session on service restart. | Browser console connection timeline + Android `NetworkCallback` log. |
| **RDV-07** | **Duplicate Command & Audio Non-Mutation** | Send 20 duplicate `RING` commands with same `commandId`; test volume at `20%` and `100%` with Bluetooth earbuds connected. | Single audio instance plays on **built-in speaker**; global Android volume streams are **100% unchanged** after `STOP`. | Overlapping audio, sound routed only to earbuds, or global volume slider altered. | `adb shell cmd media_session volume --get` before and after. |

---

## 16. Failure Diagnosis and Recovery Strategy

| Failure Mode | Diagnostic Evidence to Check | Recovery & Remediation |
| :--- | :--- | :--- |
| **`HTTP 403` During Request** | Check JSON response code: `NON_TAILSCALE_PEER` (Layer 1 byte check failed) vs. `INVALID_CSRF_TOKEN` (Layer 2 `X-AntiHide-CSRF` header mismatch). | If `INVALID_CSRF_TOKEN` after a server restart, client automatically calls `GET /api/v1/session` to refresh its in-memory `csrfToken` and retries once. |
| **`HTTP 401` on New Hostname** | User switched from `http://100.x.y.z:8765` to `http://oppo-a58:8765` before pairing the second hostname origin. | Dashboard cleanly shows the **Pair This Origin** card; once paired, the 4-slot session table keeps both the IP origin and MagicDNS origin authenticated simultaneously. |
| **Unreachable After `tun0` Restart** | Check `adb logcat -s AntiHideNet` for `ConnectivityManager.NetworkCallback` `onAvailable` / `onLinkPropertiesChanged` events. | `TailscaleInterfaceServer` automatically closes the orphaned `ServerSocket` and rebinds to the new `tun0` `Network` handle within 1 second. |
| **Phone Unreachable After 30+ Min Screen Off** | Compare `ping <tailscale-ip>` vs. `curl http://<tailscale-ip>:8765/api/v1/ping`. | If ICMP works but port `8765` stalls, ColorOS App Freezer suspended the process -> verify ColorOS *"Allow background activity"* and lock app in Recents using the Slice 3 Readiness Checklist. |

---

## 17. Risks, Assumptions, and Unresolved Questions

1. **[Assumption — Tailscale Peer Connectivity]**: Both the OPPO A58 and Windows 11 laptop belong to the same tailnet with ACLs permitting TCP port `8765`.
2. **[Unresolved Question — OPPO A58 ColorOS Firmware Specifics]**: Whether ColorOS on the user's specific OPPO A58 build honors standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `WifiLock` alone for `> 60` minutes unplugged, or also requires the proprietary ColorOS *"Allow background activity"* toggle. This will be empirically resolved in **Slice 3 (`RDV-04`)**.
3. **[Unresolved Question — Engine `vpnNetwork.bindSocket` Compatibility]**: Whether Ktor CIO exposes the underlying `ServerSocket` descriptor before binding so `vpnNetwork.bindSocket()` can be applied, or whether our thin `java.net.ServerSocket` HTTP/1.1 + SSE transport (`Fallback A`) is cleaner. This will be empirically resolved in **Slice 1 (`E-12A`)**.

---

## 18. Definition of Done for the Complete Project

The AntiHide V2 project is complete when:
1. All automated unit, integrated auth, static source guard, and on-device instrumentation tests pass.
2. Static verification confirms **zero** occurrences of `remoteHost`, `getHostName(`, `localStorage`, or `sessionStorage`.
3. All 14 Slice 1 PoC experiments (**E-01 through E-13**, including `E-12A` static-route LAN isolation and `E-12B` integrated auth/CSRF negative tests) pass on the OPPO A58 and Windows 11 laptop.
4. All 7 Real-Device Verification suites (**RDV-01 through RDV-07**) pass on the physical hardware with captured logs.
5. Both numeric Tailscale IP (`100.x.y.z`) and MagicDNS hostnames work interchangeably and simultaneously across 160 browser reloads with **zero** `HTTP 403` errors.
6. Remote `RING`, remote `STOP`, and disconnected lock-screen local `STOP` work reliably, including after 60 minutes of unplugged screen-off idle time.
7. Optional features (Arm/Disarm and Configurable Volume with built-in speaker routing) preserve all 10 Security Invariants and leave global Android audio settings untouched.

---

## 19. Final Implementation Order and Go/No-Go Gates

| Order | Slice | Mandatory Go / No-Go Gate Before Next Slice |
| :--- | :--- | :--- |
| **1** | **Slice 1**: Networking, `tun0` Binding & Integrated Auth PoC | **Gate 1**: 14/14 PoC experiments (including `E-12A` static-route LAN test, `E-12B` auth negative matrix, and 160 IP + MagicDNS reloads) pass on OPPO A58 + Windows 11. |
| **2** | **Slice 2**: Minimal End-to-End Core (`RING` / `STOP` + State Machine) | **Gate 2**: 20 remote `RING`/`STOP` and 10 disconnected local `STOP` tests pass on OPPO A58; idempotency and 180s watchdog verified. |
| **3** | **Slice 3**: Foreground Service, Screen-Off & ColorOS Hardening | **Gate 3**: 60-minute unplugged screen-off `RING` soak test (`RDV-04`) and single-tap lock-screen `STOP` (`RDV-05`) pass on OPPO A58. |
| **4** | **Slice 4**: SSE + Polling Fallback & Reconnection Resilience | **Gate 4**: Automatic state convergence across Wi-Fi/Cellular switches, Tailscale restarts, and service restarts (`RDV-06`) in `< 10s`. |
| **5** | **Slice 5**: Production Browser Dashboard & Sanitized Diagnostics | **Gate 5 (Core System Complete)**: Full Core Definition of Done (Goals 1–7) verified in Edge and Chrome on Windows 11. |
| **6** | **Slice 6**: Optional — Safe Arm/Disarm Readiness Control | **Gate 6**: 20 Arm/Disarm toggles produce 0 sound; `RING` while disarmed returns `409 Conflict`. |
| **7** | **Slice 7**: Optional — Configurable Volume & Speaker Routing | **Gate 7**: Volume scales `10%–100%` on OPPO A58 built-in speaker with 0 mutation of global Android volume streams after `STOP`. |
| **8** | **Slice 8**: Optional — BLE Fallback (Conditional) | **Gate 8**: Executed only if Airplane-Mode Bluetooth finding is desired; must pass HMAC challenge-response and `< 2%/hr` battery test. |

---

## 20. Baseline Implementation Record

The architecture specified in this roadmap is implemented and shipped. The components named here are present in the source tree and covered by the automated suite:

1. `TailscaleInterfaceServer` binds specifically to the `TRANSPORT_VPN` `tun0` `Network` and Tailscale `InetAddress`. `RawSocketAdmissionVerifier` performs raw `InetAddress.getAddress()` byte checks, enforced by a static test that bans `getHostName()` and `remoteHost` from shipped source.
2. `SessionAuthManager` implements the 120-second physical pairing window, the 4-slot peer-IP-bound `HttpOnly; SameSite=Strict` session cookie, the `GET /api/v1/session` reload bootstrap, and per-session `HMAC-SHA256` `X-AntiHide-CSRF` verification, covered by `IntegratedAuthFlowTest`.
3. The alarm core, `AntiHideStateMachine`, foreground service, SSE state synchronisation with polling fallback, and the sanitised diagnostics dashboard are implemented as described in Sections 13 and 14.

The 14 Real-Device Verification Experiments (`E-01` through `E-13`, including `E-12A` and `E-12B`) remain available as committed harnesses in `scripts/`. Their design rationale is preserved above; the results recorded for this baseline are in `docs/VERIFICATION.md`.

Automated test evidence for the baseline is in `docs/TEST_RESULTS.md`.
