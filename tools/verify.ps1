# Runs the project gates under PowerShell 7 and prints a compact summary.
#
# Invoked as:  pwsh -NoProfile -File tools\verify.ps1 [gradle tasks...]
# Default tasks are the three that must stay green: style, unit tests, APK.
#
# PowerShell 7 is required: its Get-Content/Set-Content default to UTF-8, while the 5.1 host reads
# source files as ANSI and silently destroys every non-ASCII character (that is how
# ReaderSettingsSheet.kt was corrupted earlier in this project).

param(
    [string[]]$Tasks = @('verifyKotlinStyle', 'testDebugUnitTest', ':app:assembleDebug')
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$workDir = $root
$hasNonAscii = $root -match '[^\x00-\x7F]'
if ($hasNonAscii) {
    if (-not (Test-Path 'R:\')) {
        subst R: "$root" | Out-Null
    }
    $workDir = 'R:\'
}
Set-Location $workDir

Write-Output "PS $($PSVersionTable.PSVersion) | dir: $workDir | tasks: $($Tasks -join ', ')"

$output = & .\gradlew.bat @Tasks --console=plain 2>&1 | ForEach-Object { "$_" }
$exit = $LASTEXITCODE
Set-Location $root

$errors = $output | Where-Object { $_ -match '^e: ' }
$failures = $output | Where-Object { $_ -match 'FAILED|tests completed' }
$verdict = $output | Where-Object { $_ -match 'BUILD SUCCESSFUL|BUILD FAILED' }

if ($errors) {
    Write-Output '--- compile errors ---'
    $errors | Select-Object -First 15 | ForEach-Object { $_.Substring([Math]::Min(70, $_.Length)) }
}
if ($failures) {
    Write-Output '--- test failures ---'
    $failures | Select-Object -First 15 | ForEach-Object { $_.Trim() }
}
Write-Output '--- verdict ---'
$verdict | ForEach-Object { $_.Trim() }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $file = Get-Item $apk
    Write-Output ("apk: {0:N0} bytes, {1}" -f $file.Length, $file.LastWriteTime)
}

exit $exit
