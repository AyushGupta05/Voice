# P85 driver (agent G8, 2026-10-17): launches one GUI-mode (non-headless) Fiji process that runs one
# Groovy probe script, records the launch time, samples the process footprint when the script signals
# "ready", and enforces a hard timeout. Only the PID started here (and its child processes, if any)
# is ever killed. Nothing is written into the Fiji directory; probe output goes to -OutDir.
#
# Usage (PowerShell 5.1):
#   powershell -NoProfile -File P85-gui-driver.ps1 -Script <abs .groovy> -OutDir <abs dir> [-TimeoutSec 240]
param(
  [Parameter(Mandatory=$true)][string]$Script,
  [Parameter(Mandatory=$true)][string]$OutDir,
  [int]$TimeoutSec = 240,
  [int]$GraceSec = 15
)
$ErrorActionPreference = 'Stop'
$fiji = 'C:\Users\arnav\Documents\dev\Fiji'
$jdk  = Join-Path $fiji 'java\win64\zulu21.42.19-ca-jdk21.0.7-win_x64'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$name = [IO.Path]::GetFileNameWithoutExtension($Script)
$log  = Join-Path $OutDir ($name + '.driver.txt')
foreach ($f in @('ready.flag','measured.flag','done.flag')) { $p = Join-Path $OutDir $f; if (Test-Path $p) { Remove-Item $p -Force } }
function Say([string]$s) { $line = (Get-Date).ToString('HH:mm:ss.fff') + ' ' + $s; Add-Content -Path $log -Value $line -Encoding utf8; Write-Output $line }

$fargs = @("-Dimagej.updater.disableAutocheck=true", "-Dg8.out.dir=$OutDir",
          "--java-home=$jdk", "--allow-multiple", "--run", "`"$Script`"")
$launchMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$p = Start-Process -FilePath (Join-Path $fiji 'fiji-windows-x64.exe') -ArgumentList $fargs `
       -WorkingDirectory $fiji -PassThru `
       -RedirectStandardOutput (Join-Path $OutDir ($name + '.stdout.txt')) `
       -RedirectStandardError  (Join-Path $OutDir ($name + '.stderr.txt'))
Say "launched pid=$($p.Id) launchEpochMs=$launchMs args=$($fargs -join ' ')"
Set-Content -Path (Join-Path $OutDir 'launch-epoch-ms.txt') -Value $launchMs -Encoding ascii

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$sampled = $false
$doneSeen = $false
$doneAt = $null
while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
  if (-not $sampled -and (Test-Path (Join-Path $OutDir 'ready.flag'))) {
    $readyMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Start-Sleep -Milliseconds 200
    $gp = Get-Process -Id $p.Id
    $kids = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" | Select-Object ProcessId, Name, WorkingSetSize
    Say ("ready.flag seen at epochMs=$readyMs; pid=$($p.Id) name=$($gp.ProcessName) WorkingSet64=$($gp.WorkingSet64) " +
         "PrivateMemorySize64=$($gp.PrivateMemorySize64) PeakWorkingSet64=$($gp.PeakWorkingSet64) Threads=$($gp.Threads.Count) " +
         "Handles=$($gp.HandleCount) children=" + (($kids | ForEach-Object { "$($_.ProcessId):$($_.Name):$($_.WorkingSetSize)" }) -join ','))
    Set-Content -Path (Join-Path $OutDir 'measured.flag') -Value 'ok' -Encoding ascii
    $sampled = $true
  }
  if ((Test-Path (Join-Path $OutDir 'done.flag')) -and -not $doneSeen) { $doneSeen = $true; $doneAt = Get-Date; Say "done.flag seen" }
  if ($doneSeen -and -not $p.HasExited -and ((Get-Date) - $doneAt).TotalSeconds -gt $GraceSec) {
    Say "process still alive $GraceSec s after done.flag (System.exit did not terminate it)"
    break
  }
  Start-Sleep -Milliseconds 100
}
if (-not $p.HasExited) {
  $js = Join-Path $jdk 'bin\jstack.exe'
  & $js -l $p.Id > (Join-Path $OutDir ($name + '.jstack.txt')) 2>&1
  Start-Sleep -Seconds 3
  & $js -l $p.Id > (Join-Path $OutDir ($name + '.jstack2.txt')) 2>&1
  Say "jstack written to $name.jstack.txt and (3 s later) $name.jstack2.txt"
  Say "TIMEOUT after $TimeoutSec s: killing pid=$($p.Id) and its children"
  Get-CimInstance Win32_Process -Filter "ParentProcessId=$($p.Id)" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Stop-Process -Id $p.Id -Force
  Start-Sleep -Milliseconds 500
}
$p.WaitForExit()
Say "exited pid=$($p.Id) exitCode=$($p.ExitCode) elapsedMs=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $launchMs)"
