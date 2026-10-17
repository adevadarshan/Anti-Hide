# Real-Device Verification Record

This document records **what was verified on real hardware and what was not**.
It deliberately contains no tailnet addresses, hostnames, device identifiers or
machine paths; those are the operator's private network metadata and are
excluded from this repository.

> **Privacy note.** The browser-automation console output captured during manual
> dashboard verification is **not** committed. Those files are scratch tooling
> output and they record the machine's own tailnet hostnames and addresses. The
> directory is excluded by `.gitignore` so it cannot return. This file is the
> curated, sanitized summary of that session.

---

## 1. Manual verification harnesses

These are PowerShell scripts that drive a phone running the app from a laptop
browser over Tailscale. Experiments that need a physical action pause, print the
exact step, and then verify the result automatically.

| Script | Purpose |
| --- | --- |
| `scripts/verify-slice1.ps1` | The full experiment matrix `E-01` … `E-13`, including reload torture, MagicDNS vs numeric-IP parity, cookie-only bootstrap, SSE behaviour, revocation, service restart, and the `E-12B` auth negative matrix. |
| `scripts/verify-e12a-staticroute.ps1` | The `E-12A` adversarial test: the Weak Host Model cross-interface static-route attack. |
| `scripts/generate-alarm-wav.ps1` | Regenerates the alarm tone asset. |

All scripts take their target address, MagicDNS name and pairing code as
**parameters**. No address, hostname or code is hardcoded as an active default.

### `E-12A` — read before running

`scripts/verify-e12a-staticroute.ps1` **self-elevates to Administrator** and
installs a host route on the laptop that aims the phone's Tailscale address at
the phone's Wi-Fi address on the local LAN. Its purpose is to prove the app
refuses to answer when a request arrives over unencrypted Wi-Fi instead of the
tunnel. It cleans up after itself. It also asks you to stop Tailscale on the
laptop for the duration of the test.

This is the direct adversarial test of the Layer 1 wildcard-bind rejection
described in the README, and it is the single most security-relevant experiment
in the project.

Both target addresses are **required parameters** — this repository contains no
real network address of any kind, so you must supply your own:

```powershell
# Find them first:
#   tailscale status      -> the phone's Tailscale address (100.64.0.0/10)
#   ipconfig              -> the phone's address on the laptop's LAN
.\scripts\verify-e12a-staticroute.ps1 -PhoneTailscaleIp <phone-tailscale-ip> -PhoneWifiIp <phone-lan-ip>
```

Example shape only — these are documentation-range placeholders, not real hosts:

```powershell
.\scripts\verify-e12a-staticroute.ps1 -PhoneTailscaleIp 100.101.102.103 -PhoneWifiIp 192.168.1.50
```

---

## 2. Verification status

### Verified on a real device

| Area | Status |
| --- | --- |
| Dashboard reachable from a laptop browser over Tailscale | **Verified.** Served over the numeric Tailscale IPv4 address, over the MagicDNS hostname, and over the Tailscale IPv6 ULA address. |
| Unauthenticated requests correctly refused | **Verified.** `/api/v1/session` returns `401` with a `PAIRING_REQUIRED` status until a browser is paired. |
| Dashboard rendering and live status | **Verified.** The dashboard renders, polls status, reports connection state and latency. |
| Browser used | **Google Chrome on Windows 11.** |

The archived browser console output for this session confirms the server was
reached over all three address forms and returned the expected `401` for an
unpaired client.

### Not verified

| Area | Status |
| --- | --- |
| **Microsoft Edge** | **Not tested.** The project records Edge as a target alongside Chrome. Targeting is not testing. No Edge result exists. |
| Any browser other than Chrome | **Not tested.** |
| The completed authenticated flow on device | **Not evidenced in this repository.** The archived console output shows the connection dropping partway through (timeout and connection-refused errors) before an authenticated command was issued. The dashboard's own connection panel was still showing `BOOTING`/`PAIRING_REQUIRED` at the end of the capture. |
| `E-01` … `E-13` experiment results | **Not recorded here.** The harnesses exist and are committed; no pass/fail result set for a full run is stored in this repository. |
| `E-12A` static-route attack result | **Not recorded here.** No result is stored. |
| `E-12B` auth negative matrix on device | **Not recorded here.** The negative cases are covered by `IntegratedAuthFlowTest` on the JVM; the on-device run is not recorded. |
| Screen-off / ColorOS soak, 60 minutes unplugged | **Not recorded here.** |
| Battery impact over time | **Not measured.** No figures are claimed anywhere in this project. |

---

## 3. How to read this honestly

The automated suite (103 tests, see [`TEST_RESULTS.md`](TEST_RESULTS.md)) proves
the *logic* of admission, authentication, state sync and redaction. It runs on
the JVM and never opens a real socket.

The manual harnesses are the only thing that can prove the logic holds against a
real kernel, a real Android VPN `Network`, a real Tailscale tunnel and a real
browser reload cycle — which is precisely the layer where V1 failed and where a
JVM test cannot reach.

A green suite plus an unrecorded manual run is the current state. That is a
defensible position for a personal project, but it should be stated rather than
implied, and nothing in this repository should be read as claiming a hardware
result that is not written down here.

---

## 4. Adding results

If a manual run is completed, record it here as a table row: experiment id, date,
device model, result, and anything that failed. Keep addresses, hostnames and
identifiers out of the document — refer to them by role ("the phone", "the
laptop") rather than by value. The harnesses already accept their targets as
parameters precisely so that no operator-specific value has to live in the
repository.
