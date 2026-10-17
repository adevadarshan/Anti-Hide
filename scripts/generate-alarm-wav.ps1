# Generates app/src/main/assets/raw/antihide_alarm.wav:
# a dual-tone pulsed siren (880 Hz / 1760 Hz, 250 ms each) in 16-bit PCM mono at 44.1 kHz.
# MediaPlayer decodes this reliably, and the sharp square-ish envelope is loud and obvious.
param(
    [string]$Out = "$PSScriptRoot\..\app\src\main\res\raw\antihide_alarm.wav",
    [int]$SampleRate = 44100,
    [double]$DurationSeconds = 2.0,
    [double]$ToneSeconds = 0.25
)
$ErrorActionPreference = 'Stop'

$totalSamples = [int]($SampleRate * $DurationSeconds)
$toneSamples = [int]($SampleRate * $ToneSeconds)
$pcm = New-Object 'System.Byte[]' ($totalSamples * 2)

for ($i = 0; $i -lt $totalSamples; $i++) {
    $segment = [int]($i / $toneSamples)
    $freq = if ($segment % 2 -eq 0) { 880.0 } else { 1760.0 }
    $t = $i / [double]$SampleRate
    # Sine carrier plus a third harmonic keeps it piercing on a phone speaker.
    $sample = 0.62 * [Math]::Sin(2 * [Math]::PI * $freq * $t) +
              0.22 * [Math]::Sin(2 * [Math]::PI * $freq * 3 * $t)
    # Short attack/release per segment avoids clicks that would mute the alarm on some firmware.
    $posInSegment = ($i % $toneSamples) / [double]$toneSamples
    $envelope = [Math]::Min(1.0, [Math]::Min($posInSegment / 0.02, (1.0 - $posInSegment) / 0.05))
    $value = [Math]::Max(-1.0, [Math]::Min(1.0, $sample * $envelope))
    $p = [int]($value * 28000)
    if ($p -gt 32767) { $p = 32767 }
    if ($p -lt -32768) { $p = -32768 }
    $bytes = [System.BitConverter]::GetBytes([int16]$p)
    $pcm[$i * 2] = $bytes[0]
    $pcm[$i * 2 + 1] = $bytes[1]
}

$stream = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter($stream)
$writer.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
$writer.Write([int](36 + $pcm.Length))
$writer.Write([System.Text.Encoding]::ASCII.GetBytes('WAVE'))
$writer.Write([System.Text.Encoding]::ASCII.GetBytes('fmt '))
$writer.Write([int]16)                      # PCM chunk size
$writer.Write([int16]1)                     # format = PCM
$writer.Write([int16]1)                     # channels = mono
$writer.Write([int]$SampleRate)
$writer.Write([int]($SampleRate * 2))       # byte rate
$writer.Write([int16]2)                     # block align
$writer.Write([int16]16)                    # bits per sample
$writer.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
$writer.Write([int]$pcm.Length)
$writer.Write($pcm)
$writer.Flush()

$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
[System.IO.File]::WriteAllBytes($Out, $stream.ToArray())
$writer.Close()
Write-Host ("wrote {0} ({1:N0} bytes, {2:N2}s, {3} Hz)" -f $Out, (Get-Item $Out).Length, $DurationSeconds, $SampleRate)