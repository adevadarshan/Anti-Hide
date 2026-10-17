# Automated Test Results

Curated record of the JVM test suite. Generated from Gradle's JUnit XML output
(`app/build/test-results/testDebugUnitTest/`) and checked in so the evidence
survives even though `app/build/` is excluded from version control.

## Latest verified run

| | |
| --- | --- |
| Date | 2026-10-17 (23:22 local) |
| Command | `.\antihide-gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-build-cache` |
| Gradle / AGP / Kotlin | 8.7 / 8.5.2 / 1.9.24 |
| JDK | Temurin 17.0.20.1 |
| Test framework | JUnit 5 (Jupiter 5.10.2), JVM only |
| **Total tests** | **103** |
| **Failures** | **0** |
| **Errors** | **0** |
| **Skipped** | **0** |
| Result | **BUILD SUCCESSFUL** — 23 of 23 tasks executed |

This run was performed against a **completely clean workspace**: the entire
`app/build/` output directory and the `.gradle/` project cache were deleted
first, so every task genuinely re-executed rather than being served from Gradle's
build cache. `--rerun-tasks --no-build-cache` was used to guarantee that.

The debug APK was then rebuilt from the same clean state and came out
**byte-identical** to the previously built artifact (9,086,821 bytes,
SHA-256 `0B943D84E3B6EA061DF1D35B9DB57C7235EA4E4253F481C7A8AB449B5BC7AE66`),
confirming the build is reproducible.

Reproduce it with:

```powershell
.\antihide-gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-build-cache
```

## Per-suite breakdown

| Suite | Tests | Failures |
| --- | ---: | ---: |
| `auth.IntegratedAuthFlowTest` | 31 | 0 |
| `net.RawSocketAdmissionVerifierTest` | 17 | 0 |
| `domain.AntiHideStateMachineTest` | 12 | 0 |
| `net.DiagnosticsAndSecurityHeadersTest` | 12 | 0 |
| `service.ServiceLifecycleTest` | 10 | 0 |
| `net.StateSyncReconciliationTest` | 9 | 0 |
| `net.HttpWireParsingTest` | 7 | 0 |
| `architecture.RingingSideEffectWiringTest` | 3 | 0 |
| `architecture.BannedApisSourceTest` | 2 | 0 |
| **Total** | **103** | **0** |

## What the suite covers

**`auth.IntegratedAuthFlowTest` (31)** — the full four-stage lifecycle end to
end: pairing against a closed window is rejected; an open window issues a
session; the cookie bootstraps a session on reload; a command without a CSRF
header is refused; a command with the wrong CSRF token is refused; a CSRF token
replayed against a foreign cookie is refused; sessions expire; the concurrent
session cap and oldest-first eviction hold; a session bound to a different peer
address cannot be used; revocation closes every open SSE stream.

**`net.RawSocketAdmissionVerifierTest` (17)** — admission decided on raw bytes
only. Covers the `100.64.0.0/10` boundaries (`100.63.255.255` rejected,
`100.64.0.0` accepted, `100.127.255.255` accepted, `100.128.0.0` rejected),
Tailscale ULA acceptance and rejection, loopback gating, wildcard rejection on
both local address families, and the address-sanitizing helper.

**`domain.AntiHideStateMachineTest` (12)** — alarm and arm transitions,
invariants (a fresh machine is always IDLE, side effects happen only in the
right state), and the watchdog timeout.

**`net.DiagnosticsAndSecurityHeadersTest` (12)** — response headers are present
on every response; the CSP forbids inline and external script; diagnostics
serialize `boundAddresses` and `recentEvents` as arrays; the event log scrubs a
session value, a raw IPv4 address and an IPv6 interface identifier at the sink;
bound addresses are masked; the app version is served without leaking internals.

**`service.ServiceLifecycleTest` (10)** — wake lock acquired exactly once on
ringing and never re-acquired; released on local stop and on the watchdog;
wifi lock held for the service lifetime independently of alarm state; lock
release is idempotent; the service always restarts IDLE and silent.

**`net.StateSyncReconciliationTest` (9)** — SSE first frame is a full snapshot;
heartbeats carry `stateVersion` and `serverBootId`; a rebind closes every open
stream instead of leaving a zombie socket; a restarted server reports a new
`serverBootId` and a reset `stateVersion`; status converges after an
interruption; `/api/v1/status` never carries the CSRF token; revocation
terminates streams and subsequent status answers `401`.

**`net.HttpWireParsingTest` (7)** — request-line, header and body parsing;
case-insensitive header lookup; cookie extraction; query strings and fragments
are stripped before routing; the 16 KiB header cap and 64 KiB body cap are
enforced; malformed input is rejected.

**`architecture.RingingSideEffectWiringTest` (3)** — the alarm's audio, vibration
and notification side effects are wired where intended, so a future refactor
cannot silently detach them.

**`architecture.BannedApisSourceTest` (2)** — a static architecture guard. Scans
shipped source (`kt`, `java`, `js`, `html`, `css`, `xml`, `json`, `pro`) for
`remoteHost`, `getHostName(`, `localStorage`, `sessionStorage` and `?token=`.
This is the regression test for the V1 failure mode described in the README: it
fails the build if DNS ever re-enters the admission path, or if browser storage
or a URL-embedded token is introduced. It also asserts the dashboard assets
actually exist, so the scan cannot pass vacuously.

## Scope of this evidence

This suite runs on the JVM. It does **not** run on a device or emulator, does
not create real sockets, and does not exercise Android framework behaviour.
Claims about real-device behaviour are recorded separately in
[`VERIFICATION.md`](VERIFICATION.md).

## Reproducing

```powershell
.\antihide-gradlew.bat :app:testDebugUnitTest
```

XML output: `app/build/test-results/testDebugUnitTest/`
HTML report: `app/build/reports/tests/testDebugUnitTest/`
