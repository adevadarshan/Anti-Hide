# E-12A: Weak Host Model cross-interface static route attack.
# Must run as Administrator (it adds a static route). Self-elevates if needed.
<#
.SYNOPSIS
  Proves the app refuses to answer when a request arrives over unencrypted Wi-Fi
  instead of the Tailscale tunnel.

.DESCRIPTION
  Adds a /32 host route on the laptop that aims the phone's Tailscale address at
  the phone's Wi-Fi address on the local LAN, then attempts to reach the phone.
  Under the Linux Weak Host Model a 0.0.0.0 listener would answer on wlan0 too.
  A correct implementation refuses both paths. The route is removed at the end.

.PARAMETER PhoneTailscaleIp
  REQUIRED. The phone's Tailscale IPv4 address (inside 100.64.0.0/10).

.PARAMETER PhoneWifiIp
  REQUIRED. The phone's IPv4 address on the same Wi-Fi LAN as this laptop.

.EXAMPLE
  .\verify-e12a-staticroute.ps1 -PhoneTailscaleIp 100.101.102.103 -PhoneWifiIp 192.168.1.50

.EXAMPLE
  # Discover the addresses first:
  #   tailscale status          -> the phone's Tailscale address
  #   ipconfig                  -> the phone's address on the laptop's LAN
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$PhoneTailscaleIp,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$PhoneWifiIp,

    [int]$Port = 8765
)

$id = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $id.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'Requesting elevation...'
    Start-Process powershell -Verb RunAs -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"",
        '-PhoneTailscaleIp', $PhoneTailscaleIp, '-PhoneWifiIp', $PhoneWifiIp, '-Port', $Port
    )
    Write-Host 'A new elevated window will run the test. This one will close.'
    return
}

Write-Host ''
Write-Host '================ E-12A Weak Host Model attack test ================' -ForegroundColor Cyan
Write-Host "Phone Tailscale IP : $PhoneTailscaleIp"
Write-Host "Phone Wi-Fi IP     : $PhoneWifiIp  (same LAN as this laptop)"
Write-Host ''

Write-Host '--- 0) baseline: phone over Tailscale must work ---' -ForegroundColor Yellow
& curl.exe -s -o NUL -w "    http://$PhoneTailscaleIp`:$Port/api/v1/ping -> %{http_code} (expect 200)`n" --max-time 8 "http://$PhoneTailscaleIp`:$Port/api/v1/ping"

Write-Host ''
Write-Host '--- 1) install laptop Tailscale must stay ON but we add a LAN route to the phone Tailscale IP ---' -ForegroundColor Yellow
Write-Host '    STOP Tailscale on the LAPTOP first, then continue.' -ForegroundColor Red
$go = Read-Host '    Press Enter when laptop Tailscale is OFF'
& route.exe delete $PhoneTailscaleIp 2>$null | Out-Null
& route.exe add $PhoneTailscaleIp mask 255.255.255.255 $PhoneWifiIp
Write-Host ''
Write-Host '--- 2) routing table entry now in place ---' -ForegroundColor Yellow
& route.exe print $PhoneTailscaleIp

Write-Host ''
Write-Host '--- 3) attack: reach the phone Tailscale IP over unencrypted wlan0 ---' -ForegroundColor Yellow
$a = & curl.exe -s -o NUL -w "%{http_code}" --max-time 8 "http://$PhoneTailscaleIp`:$Port/api/v1/ping"
Write-Host "    curl http://$PhoneTailscaleIp`:$Port/api/v1/ping -> $a   (expect 000 = refused/timeout)"
$b = & curl.exe -s -o NUL -w "%{http_code}" --max-time 8 "http://$PhoneWifiIp`:$Port/api/v1/ping"
Write-Host "    curl http://$PhoneWifiIp`:$Port/api/v1/ping       -> $b   (expect 000 = refused)"

Write-Host ''
Write-Host '--- 4) cleanup ---' -ForegroundColor Yellow
& route.exe delete $PhoneTailscaleIp
Write-Host '    route deleted. Re-enable Tailscale on the laptop when done.'

Write-Host ''
if ($a -ne '200' -and $b -ne '200') {
    Write-Host 'RESULT: PASS - neither LAN path was served. tun0 scoping + Layer 1 raw-byte peer check held.' -ForegroundColor Green
} else {
    Write-Host 'RESULT: FAIL - a request was served over unencrypted wlan0. This is a Slice 1 STOP condition.' -ForegroundColor Red
}
Write-Host '=================================================================='