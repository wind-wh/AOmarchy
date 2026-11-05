# Builds the APK locally inside WSL (no GitHub Actions), which is much faster for small iterations.
#
# One-time setup (already done on this machine, see docs/local-build.md):
#   - WSL2 Ubuntu with JDK 21, Android SDK (platform 36, build-tools 36.0.0), NDK 28.2.13676358, CMake 3.22.1
#   - the release keystore and signing.properties under /root/keys (never in the repository)
#   - /root/build-aomarchy.sh: rsync this checkout to /root/aomarchy, then run Gradle there
#
# Usage:
#   pwsh -File tools/build-local.ps1                 # signed release APK -> deploy-test/AOmarchy-local.apk
#   pwsh -File tools/build-local.ps1 -Install        # ... and adb install -r it on the connected tablet
#   pwsh -File tools/build-local.ps1 -Clean          # gradle clean first
#   pwsh -File tools/build-local.ps1 -Task assembleDebug
#
# The APK is signed with the release key, so it updates an installed release build in place.

param(
    [switch]$Install,
    [switch]$Clean,
    [string]$Task = 'assembleRelease',
    [string]$Adb = 'C:\Users\wind\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path (Split-Path -Parent $repo) 'deploy-test'
$apk = Join-Path $outDir 'AOmarchy-local.apk'

$gradleArgs = @()
if ($Clean) { $gradleArgs += 'clean' }
$gradleArgs += $Task
$cmd = "bash /root/build-aomarchy.sh " + ($gradleArgs -join ' ')

$start = Get-Date
Write-Host "==> WSL build: $($gradleArgs -join ' ')" -ForegroundColor Cyan
& wsl -u root -e bash -lc $cmd
if ($LASTEXITCODE -ne 0) { throw "build failed (exit $LASTEXITCODE)" }
if (-not (Test-Path $apk)) { throw "build finished but $apk is missing" }
$elapsed = (Get-Date) - $start
$size = (Get-Item $apk).Length
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
Write-Host ("==> built in {0:n0}s  {1:n0} bytes  sha256 {2}" -f $elapsed.TotalSeconds, $size, $sha) -ForegroundColor Green

if ($Install) {
    if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }
    Write-Host "==> adb install -r $apk" -ForegroundColor Cyan
    & $Adb install -r $apk | Select-Object -Last 2 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE)" }
    $ver = & $Adb shell dumpsys package app.aomarchy | Select-String -Pattern 'versionName' | Select-Object -First 1
    if ($ver) { Write-Host $ver.Line.Trim() }
}
