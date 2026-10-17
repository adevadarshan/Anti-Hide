<#
.SYNOPSIS
  AntiHide V2 - Slice 1 real-device verification (experiments E-01 .. E-13).

.DESCRIPTION
  Drives the HTTP, reload and negative-auth matrix against a phone running the Slice 1 PoC over
  Tailscale. Experiments that need a physical action (Wi-Fi toggles, Tailscale restarts, tapping
  Revoke on the phone) pause and print the exact step, then verify the result automatically.

.EXAMPLE
  .\verify-slice1.ps1 -Ip 100.101.102.103 -MagicDns oppo-a58 -Code K7M9-P4X2

.EXAMPLE
  .\verify-slice1.ps1 -Ip 100.101.102.103 -MagicDns oppo-a58 -Only E-12B -Code K7M9-P4X2

.NOTES
  -Only takes one exact experiment id: E-01, E-01D, E-02, E-04, E-05, E-06, E-07, E-08,
  E-09, E-10, E-11, E-12A, E-12B, E-13. Run E-01D separately to pair the MagicDNS origin,
  because pairing codes are single use.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [string]$MagicDns,
    [string]$Ipv6,
    [int]$Port = 8765,
    [string]$Code,
    [string]$Only,
    [int]$RevokeWaitSeconds = 90,
    [string]$Adb,
    [switch]$SkipManual
)

$ErrorActionPreference = 'Stop'
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
$script:Results = New-Object System.Collections.ArrayList
$script:CsrfIp = $null
$script:CsrfDns = $null
$script:CookieIp = $null
$script:CookieDns = $null

# ---------------------------------------------------------------- output helpers

function Write-Banner { param([string]$Text) Write-Host ''; Write-Host "=== $Text ===" -ForegroundColor Cyan }
function Write-Step { param([string]$Text) Write-Host "  -> $Text" -ForegroundColor Yellow }
function Write-Pass { param([string]$Id, [string]$Text) Add-Result $Id $true $Text; Write-Host "  [PASS] $Id $Text" -ForegroundColor Green }
function Write-Fail { param([string]$Id, [string]$Text) Add-Result $Id $false $Text; Write-Host "  [FAIL] $Id $Text" -ForegroundColor Red }
function Add-Result { param([string]$Id, [bool]$Ok, [string]$Text)
    [void]$script:Results.Add([pscustomobject]@{ Experiment = $Id; Passed = $Ok; Detail = $Text })
}
function Should-Run { param([string]$Id) if (-not $Only) { return $true }; return ($Id -eq $Only) }

# ---------------------------------------------------------------- HTTP helpers

function New-Jar { return New-Object System.Net.CookieContainer }

# Experiments are runnable standalone via -Only, so the paired session is persisted between runs.
# Stored as an array of entries: a PSCustomObject cannot hold URL-shaped property names.
$StatePath = Join-Path $env:TEMP 'antihide-slice1-state.json'

function Save-State { param($Entries) @($Entries) | ConvertTo-Json -Depth 4 | Set-Content -Path $StatePath -Encoding UTF8 }

function Load-State {
    if (-not (Test-Path $StatePath)) { return @() }
    try { return @(Get-Content $StatePath -Raw | ConvertFrom-Json) } catch { return @() }
}

function Get-Or-PairSession {
    # Returns @{Cookie; Csrf} for $Base, pairing once and caching the result.
    param([string]$Base, [string]$PairingCode, [switch]$Force)
    $entries = Load-State
    $existing = $entries | Where-Object { $_.Base -eq $Base } | Select-Object -First 1
    if (-not $Force -and $existing -and $existing.Cookie) { return $existing }

    if (-not $PairingCode) {
        Write-Step "A fresh pairing window is needed for $Base."
        $PairingCode = Read-Host '  pairing code'
    }
    $r = Invoke-Api -Base $Base -Path '/api/v1/pair' -Method POST -Body ('{"code":"' + $PairingCode + '"}')
    $cookie = Get-CookieValue $r.SetCookie
    if (-not $cookie) { throw "pairing failed for $Base (HTTP $($r.Status))" }
    $entry = [pscustomobject]@{ Base = $Base; Cookie = $cookie; Csrf = (Get-Csrf (Invoke-Session -Base $Base -Cookie $cookie).Body) }
    Save-State (@($entries | Where-Object { $_.Base -ne $Base }) + @($entry))
    return $entry
}

function Invoke-Api {
    param(
        [string]$Base, [string]$Path, [string]$Method = 'GET', [string]$Body,
        [hashtable]$Headers = @{}, $Jar, [int]$TimeoutMs = 8000
    )
    $req = [System.Net.HttpWebRequest]::Create("$Base$Path")
    $req.Method = $Method
    $req.Timeout = $TimeoutMs
    $req.ReadWriteTimeout = $TimeoutMs
    $req.AllowAutoRedirect = $false
    $req.UserAgent = 'antihide-slice1-verify/1.0'
    $req.CachePolicy = New-Object System.Net.Cache.RequestCachePolicy([System.Net.Cache.RequestCacheLevel]::NoCacheNoStore)
    if ($Jar) { $req.CookieContainer = $Jar }
    foreach ($key in $Headers.Keys) { $req.Headers[$key] = $Headers[$key] }
    if ($Body) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $req.ContentType = 'application/json'
        $req.ContentLength = $bytes.Length
        $stream = $req.GetRequestStream(); $stream.Write($bytes, 0, $bytes.Length); $stream.Close()
    }
    $response = $null
    try { $response = $req.GetResponse() } catch [System.Net.WebException] { $response = $_.Exception.Response }
    if ($null -eq $response) { return [pscustomobject]@{ Status = 0; Body = ''; Headers = @{}; SetCookie = $null } }
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
    $text = $reader.ReadToEnd(); $reader.Close()
    $headerMap = @{}
    foreach ($key in $response.Headers.AllKeys) { $headerMap[$key.ToLower()] = $response.Headers[$key] }
    $setCookie = $response.Headers['Set-Cookie']
    $status = [int]$response.StatusCode
    $response.Close()
    return [pscustomobject]@{ Status = $status; Body = $text; Headers = $headerMap; SetCookie = $setCookie }
}

function Get-CookieValue { param([string]$SetCookie)
    if (-not $SetCookie) { return $null }
    return ($SetCookie -split ';')[0].Split('=')[1]
}

function Get-Csrf { param([string]$Body)
    $m = [regex]::Match($Body, '"csrfToken"\s*:\s*"([^"]+)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Send-EchoWithCookie {
    # HttpWebRequest only attaches cookies from a container, so the cookie header is set explicitly.
    param([string]$Base, [string]$Cookie, [string]$Csrf, [string]$CommandId)
    $headers = @{ 'X-AntiHide-CSRF' = $Csrf; 'Cookie' = "antihide_sid=$Cookie" }
    return Invoke-Api -Base $Base -Path '/api/v1/echo-command' -Method POST -Headers $headers `
        -Body ('{"commandId":"' + $CommandId + '","payload":"PING_TEST"}')
}

function Invoke-Session { param([string]$Base, [string]$Cookie)
    $headers = @{}
    if ($Cookie) { $headers['Cookie'] = "antihide_sid=$Cookie" }
    return Invoke-Api -Base $Base -Path '/api/v1/session' -Headers $headers
}

# ---------------------------------------------------------------- experiments

function E01-InitialPairing {
    Write-Banner 'E-01 Initial connection and pairing (IP + MagicDNS)'
    $ipBase = "http://$Ip`:$Port"
    $ping = Invoke-Api -Base $ipBase -Path '/api/v1/ping'
    if ($ping.Status -ne 200) { Write-Fail 'E-01' "phone unreachable at $ipBase (HTTP $($ping.Status))"; return }
    Write-Pass 'E-01a' "unauthenticated ping HTTP 200 at $ipBase"

    $session = Get-Or-PairSession -Base $ipBase -PairingCode $Code
    $script:CookieIp = $session.Cookie
    $script:CsrfIp = $session.Csrf
    Write-Pass 'E-01b' 'numeric IP origin paired'

    if ($MagicDns) {
        $dnsBase = "http://${MagicDns}:$Port"
        Write-Step 'A second pairing window is required for the MagicDNS origin (single-use codes).'
        Write-Step "On the phone: open a new pairing window, then run:"
        Write-Step "  .\verify-slice1.ps1 -Ip $Ip -MagicDns $MagicDns -Only E-01D"
    }
}

function E01D-PairDns {
    Write-Banner 'E-01D Pair the MagicDNS origin'
    if (-not $MagicDns) { Write-Fail 'E-01D' 'no -MagicDns supplied'; return }
    $dnsBase = "http://${MagicDns}:$Port"
    $session = Get-Or-PairSession -Base $dnsBase -PairingCode $Code
    $script:CookieDns = $session.Cookie
    $script:CsrfDns = $session.Csrf
    Write-Pass 'E-01D' 'MagicDNS origin paired'
}

function Invoke-ReloadTorture {
    param([string]$Label, [string]$Base, [string]$Cookie, [int]$Count)
    $bad = 0; $codes = @{}
    for ($i = 1; $i -le $Count; $i++) {
        foreach ($path in @('/', '/app.js', '/api/v1/session')) {
            $headers = @{}
            if ($path -eq '/api/v1/session' -and $Cookie) { $headers['Cookie'] = "antihide_sid=$Cookie" }
            $r = Invoke-Api -Base $Base -Path $path -Headers $headers
            if (-not $codes.ContainsKey($r.Status)) { $codes[$r.Status] = 0 }
            $codes[$r.Status]++
            if ($r.Status -ne 200) { $bad++ }
        }
        if ($i % 10 -eq 0) { Write-Host ("      {0}: {1}/{2} reload cycles" -f $Label, $i, $Count) }
    }
    $summary = ($codes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '
    if ($bad -eq 0) { Write-Pass $Label "$Count reload cycles, all 200 OK ($summary)" }
    else { Write-Fail $Label "$bad non-200 responses across $Count cycles ($summary)" }
}

function Ensure-Session {
    # Cookie-dependent experiments run standalone, so make sure a session exists first.
    $ipBase = "http://$Ip`:$Port"
    if (-not $script:CookieIp) {
        try {
            $s = Get-Or-PairSession -Base $ipBase -PairingCode $Code
            $script:CookieIp = $s.Cookie; $script:CsrfIp = $s.Csrf
        } catch { Write-Fail 'SETUP' "numeric IP session unavailable: $($_.Exception.Message)" }
    }
    if ($MagicDns -and -not $script:CookieDns) {
        try {
            $s = Get-Or-PairSession -Base "http://${MagicDns}:$Port" -PairingCode $Code
            $script:CookieDns = $s.Cookie; $script:CsrfDns = $s.Csrf
        } catch { Write-Step "MagicDNS session unavailable: $($_.Exception.Message)" }
    }
}

function E02-Reloads {
    Write-Banner 'E-02 / E-03 Repeated page loads (F5 and Ctrl+F5 equivalents)'
    $ipBase = "http://$Ip`:$Port"
    Invoke-ReloadTorture 'E-02' $ipBase $script:CookieIp 50
    if ($MagicDns) { Invoke-ReloadTorture 'E-02D' "http://${MagicDns}:$Port" $script:CookieDns 50 }
    Invoke-ReloadTorture 'E-03' $ipBase $script:CookieIp 30
    if ($MagicDns) { Invoke-ReloadTorture 'E-03D' "http://${MagicDns}:$Port" $script:CookieDns 30 }
    Write-Step 'HTTP-level reload proof. The browser-side F5/Ctrl+F5 torture (RDV-01) still needs a human:'
    Write-Step '  50x F5 + 30x Ctrl+F5 on each of the two URLs in Edge and Chrome, then export a HAR.'
}

function E04-FreshTabs {
    Write-Banner 'E-04 Five fresh tabs bootstrap from the cookie alone'
    $ipBase = "http://$Ip`:$Port"
    $ok = 0
    for ($i = 1; $i -le 5; $i++) {
        $s = Invoke-Session -Base $ipBase -Cookie $script:CookieIp
        if ($s.Status -eq 200) {
            $e = Send-EchoWithCookie -Base $ipBase -Cookie $script:CookieIp -Csrf $script:CsrfIp -CommandId ("tab-$i-" + [guid]::NewGuid())
            if ($e.Status -eq 200) { $ok++ }
        }
    }
    if ($ok -eq 5) { Write-Pass 'E-04' '5/5 fresh sessions bootstrapped and issued echo commands' }
    else { Write-Fail 'E-04' "only $ok/5 fresh tabs succeeded" }
}

function E05-Reopen {
    Write-Banner 'E-05 Browser closure and reopening'
    $ipBase = "http://$Ip`:$Port"
    $r = Invoke-Session -Base $ipBase -Cookie $script:CookieIp
    if ($r.Status -eq 200) { Write-Pass 'E-05a' 'session survived with the original cookie after a cold client' }
    else { Write-Fail 'E-05a' "HTTP $($r.Status) after client restart" }
    Write-Step 'Human step: close the browser completely (Alt+F4), wait 15s, reopen both URLs.'
}

function E06-StatusCodes {
    Write-Banner 'E-06 HTTP resource loading and status codes'
    $ipBase = "http://$Ip`:$Port"
    $expect = @{ '/' = 200; '/app.js' = 200; '/styles.css' = 200; '/api/v1/ping' = 200; '/nonexistent' = 404; '/api/v1/nope' = 404 }
    $bad = @()
    foreach ($path in $expect.Keys) {
        $r = Invoke-Api -Base $ipBase -Path $path
        if ($r.Status -ne $expect[$path]) { $bad += "$path expected $($expect[$path]) got $($r.Status)" }
    }
    if ($bad.Count -eq 0) { Write-Pass 'E-06' 'all routes returned the expected status; no 500s and no 403s' }
    else { Write-Fail 'E-06' ($bad -join '; ') }
    $noStore = (Invoke-Api -Base $ipBase -Path '/').Headers['cache-control']
    if ($noStore -like 'no-store*') { Write-Pass 'E-06b' "Cache-Control: $noStore" }
    else { Write-Fail 'E-06b' "Cache-Control was '$noStore'" }
}

function Open-SseStream {
    param([string]$Base, [string]$Cookie, [int]$Seconds, [int]$StopAfterHeartbeats = 0)
    $uri = [uri]$Base
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect($uri.Host, $uri.Port)
    $stream = $client.GetStream()
    # Must exceed the 15s server heartbeat interval, otherwise the reader times out between frames.
    $stream.ReadTimeout = 25000
    $request = "GET /api/v1/events HTTP/1.1`r`nHost: $($uri.Host):$($uri.Port)`r`nCookie: antihide_sid=$Cookie`r`nAccept: text/event-stream`r`nConnection: keep-alive`r`n`r`n"
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($request)
    $stream.Write($bytes, 0, $bytes.Length); $stream.Flush()

    $reader = New-Object System.IO.StreamReader($stream)
    $statusLine = $reader.ReadLine()
    $heartbeats = 0
    $sawData = $false
    $closed = $false
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        # Bound each read so the loop always re-checks its own deadline.
        try { $line = $reader.ReadLine() } catch { continue }
        if ($null -eq $line) { $closed = $true; break }
        if ($line.StartsWith('event: heartbeat')) { $heartbeats++; Write-Host "      heartbeat #$heartbeats at $(Get-Date -Format HH:mm:ss)" }
        if ($line.StartsWith('data:')) { $sawData = $true }
        if ($StopAfterHeartbeats -gt 0 -and $heartbeats -ge $StopAfterHeartbeats) { break }
    }
    $client.Close()
    return [pscustomobject]@{ StatusLine = $statusLine; Heartbeats = $heartbeats; SawData = $sawData; Closed = $closed }
}

function E07-SseRevocation {
    Write-Banner 'E-07 SSE establishment, heartbeat and revocation'
    $ipBase = "http://$Ip`:$Port"
    $probe = Open-SseStream -Base $ipBase -Cookie $script:CookieIp -Seconds 3
    if ($probe.StatusLine -like 'HTTP/1.1 200*') { Write-Pass 'E-07a' 'authenticated SSE stream opened and delivered a state snapshot' }
    else { Write-Fail 'E-07a' "SSE handshake returned: $($probe.StatusLine)" }

    if ($SkipManual) { Write-Step 'E-07b skipped (-SkipManual)'; return }
    Write-Step 'Keep this script running. NOW tap "Revoke All Sessions" on the OPPO A58.'
    Write-Step "Waiting up to $RevokeWaitSeconds seconds..."
    $watch = Open-SseStream -Base $ipBase -Cookie $script:CookieIp -Seconds $RevokeWaitSeconds
    $r = Invoke-Session -Base $ipBase -Cookie $script:CookieIp
    if ($r.Status -eq 401 -and $watch.Closed) {
        Write-Pass 'E-07b' "stream closed after $($watch.Heartbeats) heartbeat(s) and /api/v1/session now returns 401"
    } else {
        Write-Fail 'E-07b' "heartbeats=$($watch.Heartbeats) closed=$($watch.Closed) sessionStatus=$($r.Status)"
    }
}

function E08-ReconnectHarness {
    Write-Banner 'E-08 25 connect/disconnect cycles plus 25 echo commands'
    $ipBase = "http://$Ip`:$Port"
    $cycles = 0; $commands = 0; $frames = 0
    for ($i = 1; $i -le 25; $i++) {
        $s = Open-SseStream -Base $ipBase -Cookie $script:CookieIp -Seconds 2
        if ($s.StatusLine -like 'HTTP/1.1 200*') { $cycles++ }
        if ($s.SawData) { $frames++ }
        $e = Send-EchoWithCookie -Base $ipBase -Cookie $script:CookieIp -Csrf $script:CsrfIp -CommandId ("harness-$i-" + [guid]::NewGuid())
        if ($e.Status -eq 200) { $commands++ }
    }
    if ($cycles -eq 25 -and $commands -eq 25) { Write-Pass 'E-08' "25/25 reconnects and 25/25 echo commands ($frames initial snapshots)" }
    else { Write-Fail 'E-08' "reconnects=$cycles/25 commands=$commands/25" }
    Write-Step 'On the phone run: adb shell ss -tan | grep 8765   (expect no growing pile of sockets)'
}

function E09-NetworkInterruptions {
    Write-Banner 'E-09 Wi-Fi and Tailscale interruptions'
    if ($SkipManual) { Write-Step 'E-09 skipped (-SkipManual)'; return }
    $ipBase = "http://$Ip`:$Port"
    foreach ($round in 1..2) {
        Write-Step "Round ${round}: turn the OPPO A58 Wi-Fi OFF, wait 15s, then ON."
        Read-Host '  press Enter once Wi-Fi is back ON' | Out-Null
        Write-Step 'Round ' + $round + ': turn Windows Tailscale OFF for 15s, then ON.'
        Read-Host '  press Enter once Tailscale is back ON' | Out-Null
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $ok = $false
        while ($sw.Elapsed.TotalSeconds -lt 30) {
            if ((Invoke-Api -Base $ipBase -Path '/api/v1/ping' -TimeoutMs 2000).Status -eq 200) { $ok = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if ($ok) { Write-Pass "E-09r$round" "server reachable again after $([int]$sw.Elapsed.TotalSeconds)s" }
        else { Write-Fail "E-09r$round" 'server did not become reachable within 30s' }
    }
}

function E10-ServiceRestart {
    Write-Banner 'E-10 Android foreground service restart'
    if (-not $Adb) { Write-Step 'E-10 needs -Adb <path to adb.exe>. Skipping.'; return }
    $ipBase = "http://$Ip`:$Port"
    Write-Step 'Force-restarting AntiHideForegroundService'
    & $Adb shell am force-stop com.antihide.v2 | Out-Null
    Start-Sleep -Seconds 2
    & $Adb shell monkey -p com.antihide.v2 -c android.intent.category.LAUNCHER 1 | Out-Null
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $ok = $false
    while ($sw.Elapsed.TotalSeconds -lt 30) {
        if ((Invoke-Api -Base $ipBase -Path '/api/v1/ping' -TimeoutMs 2000).Status -eq 200) { $ok = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if ($ok -and $script:CookieIp) {
        $r = Invoke-Session -Base $ipBase -Cookie $script:CookieIp
        if ($r.Status -eq 200) { Write-Pass 'E-10' "service restarted in $([int]$sw.Elapsed.TotalSeconds)s and the session table was restored" }
        else { Write-Fail 'E-10' "service is back but the session returned HTTP $($r.Status)" }
    } elseif ($ok) { Write-Pass 'E-10' 'service restarted and rebound; no cookie to verify' }
    else { Write-Fail 'E-10' 'server did not come back within 30s' }
}

function E11-AddressRepresentations {
    Write-Banner 'E-11 Client address representation changes'
    $targets = @("http://$Ip`:$Port")
    if ($Ipv6) { $targets += "http://[$Ipv6]:$Port" } else { Write-Step 'No -Ipv6 supplied; skipping the IPv6 ULA target. Read it from the phone UI ("Bound tun0 addresses").' }
    if ($MagicDns) { $targets += "http://${MagicDns}:$Port"; $targets += "http://${MagicDns}.:$Port" }
    $ok = 0; $bad = @()
    foreach ($t in $targets) {
        $r = Invoke-Api -Base $t -Path '/api/v1/ping' -TimeoutMs 6000
        if ($r.Status -eq 200) { $ok++ } else { $bad += "$t -> HTTP $($r.Status)" }
    }
    if ($bad.Count -eq 0) { Write-Pass 'E-11' "$ok/$($targets.Count) address representations passed Layer 1: $($targets -join ', ')" }
    else { Write-Fail 'E-11' ($bad -join '; ') }
    Write-Step 'Warm the reverse DNS cache first, then re-run this experiment:'
    Write-Step "  nslookup $Ip 100.100.100.100"
    if ($MagicDns) { Write-Step "  ping -a $MagicDns" }
    Write-Step "  adb shell ping -a $Ip"
}

function E12A-WeakHostModel {
    Write-Banner 'E-12A Weak Host Model cross-interface static route attack'
    $id = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $id.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Fail 'E-12A' 'must run from an Administrator PowerShell to add a static route'
        return
    }
    $wifiIp = Read-Host '  OPPO A58 Wi-Fi IPv4 address on the same LAN (e.g. 192.168.1.50)'
    Write-Step "run: route add $Ip mask 255.255.255.255 $wifiIp"
    Write-Step "run: curl.exe -v --max-time 8 http://$Ip`:$Port/api/v1/ping   (expect connection refused / timeout)"
    Write-Step "run: curl.exe -v --max-time 8 http://${wifiIp}:$Port/api/v1/ping (expect connection refused)"
    Write-Step "run: route delete $Ip mask 255.255.255.255 $wifiIp"
    Write-Host ''
    $tun = Invoke-Api -Base "http://$Ip`:$Port" -Path '/api/v1/ping' -TimeoutMs 6000
    $wifi = Invoke-Api -Base "http://${wifiIp}:$Port" -Path '/api/v1/ping' -TimeoutMs 6000
    if ($tun.Status -eq 200 -and $wifi.Status -ne 200) {
        Write-Pass 'E-12A' "tun0 reachable ($($tun.Status)) while the LAN address was refused ($($wifi.Status))"
    } else {
        Write-Fail 'E-12A' "tun0=$($tun.Status) lan=$($wifi.Status). Any 200 on the LAN path is a Slice 1 blocker."
    }
}

function E12B-NegativeMatrix {
    Write-Banner 'E-12B Integrated auth and CSRF negative matrix'
    $ipBase = "http://$Ip`:$Port"
    if (-not $Code) { $Code = Read-Host '  pairing code (for the brute-force case)' }
    $checks = @()
    $checks += , @('no cookie', (Invoke-Api -Base $ipBase -Path '/api/v1/echo-command' -Method POST -Body '{"commandId":"n1","payload":"X"}'), 401)
    $checks += , @('cookie but no CSRF', (Invoke-Api -Base $ipBase -Path '/api/v1/echo-command' -Method POST -Headers @{ 'Cookie' = "antihide_sid=$script:CookieIp" } -Body '{"commandId":"n2","payload":"X"}'), 403)
    $checks += , @('cookie but wrong CSRF', (Invoke-Api -Base $ipBase -Path '/api/v1/echo-command' -Method POST -Headers @{ 'Cookie' = "antihide_sid=$script:CookieIp"; 'X-AntiHide-CSRF' = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' } -Body '{"commandId":"n3","payload":"X"}'), 403)
    $checks += , @('CSRF replayed from a foreign cookie', (Send-EchoWithCookie -Base $ipBase -Cookie 'forged-session-id' -Csrf $script:CsrfIp -CommandId 'n4'), 401)
    $codes = @('AAAA-1111', 'BBBB-2222', 'CCCC-3333', 'DDDD-4444', 'EEEE-5555', 'FFFF-6666')
    $last = 0
    foreach ($c in $codes) { $last = (Invoke-Api -Base $ipBase -Path '/api/v1/pair' -Method POST -Body ('{"code":"' + $c + '"}')).Status }
    $checks += , @('sixth wrong pairing code', [pscustomobject]@{ Status = $last }, 429)
    $bad = @()
    foreach ($c in $checks) {
        if ($c[1].Status -ne $c[2]) { $bad += "$($c[0]): expected $($c[2]) got $($c[1].Status)" }
    }
    if ($bad.Count -eq 0) { Write-Pass 'E-12B' 'all five negative cases rejected with the exact expected status code' }
    else { Write-Fail 'E-12B' ($bad -join '; ') }
}

function E13-ExtendedOffline {
    Write-Banner 'E-13 Recovery after an extended offline period'
    if ($SkipManual) { Write-Step 'E-13 skipped (-SkipManual)'; return }
    $ipBase = "http://$Ip`:$Port"
    Write-Step 'Turn Tailscale OFF on the OPPO A58 for 3 minutes, leave this script running.'
    Read-Host '  press Enter once Tailscale is ON again on the phone' | Out-Null
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $ok = $false
    while ($sw.Elapsed.TotalSeconds -lt 60) {
        if ((Invoke-Api -Base $ipBase -Path '/api/v1/ping' -TimeoutMs 2000).Status -eq 200) { $ok = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ok) { Write-Fail 'E-13' 'phone did not come back within 60s'; return }
    $s = Invoke-Session -Base $ipBase -Cookie $script:CookieIp
    if ($s.Status -eq 200) { Write-Pass 'E-13' "recovered in $([int]$sw.Elapsed.TotalSeconds)s and still authenticated" }
    else { Write-Fail 'E-13' "recovered but session returned HTTP $($s.Status)" }
}

# ---------------------------------------------------------------- runner

Write-Host "AntiHide V2 - Slice 1 verification against $Ip" -ForegroundColor Green
Write-Host "Port $Port. MagicDNS: $(if ($MagicDns) { $MagicDns } else { '(not supplied)' })"

if (Should-Run 'E-01') { E01-InitialPairing }
if (Should-Run 'E-01D') { E01D-PairDns }
Ensure-Session
if (Should-Run 'E-02') { E02-Reloads }
if (Should-Run 'E-04') { E04-FreshTabs }
if (Should-Run 'E-05') { E05-Reopen }
if (Should-Run 'E-06') { E06-StatusCodes }
if (Should-Run 'E-07') { E07-SseRevocation }
if (Should-Run 'E-08') { E08-ReconnectHarness }
if (Should-Run 'E-09') { E09-NetworkInterruptions }
if (Should-Run 'E-10') { E10-ServiceRestart }
if (Should-Run 'E-11') { E11-AddressRepresentations }
if (Should-Run 'E-12A') { E12A-WeakHostModel }
if (Should-Run 'E-12B') { E12B-NegativeMatrix }
if (Should-Run 'E-13') { E13-ExtendedOffline }

Write-Banner 'Summary'
$script:Results | Format-Table -AutoSize
$failed = @($script:Results | Where-Object { -not $_.Passed })
Write-Host ""
Write-Host ("Total checks: {0}   Passed: {1}   Failed: {2}" -f $script:Results.Count, ($script:Results.Count - $failed.Count), $failed.Count)
if ($failed.Count -gt 0) {
    Write-Host "Slice 1 Go/No-Go: NO-GO. Resolve the failures above before starting Slice 2." -ForegroundColor Red
    exit 1
}
Write-Host "All automated checks passed. Complete the human-only steps listed above before declaring GO." -ForegroundColor Green