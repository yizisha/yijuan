# Prints the tail of the last gradle run for a single task, for diagnosing a gate failure.
# Run as: pwsh -NoProfile -File tools\gate-log.ps1 verifyKotlinStyle

param([string]$Task = 'verifyKotlinStyle')

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$output = & .\gradlew.bat $Task --console=plain 2>&1 | ForEach-Object { "$_" }
Write-Output "exit: $LASTEXITCODE"
$output |
    Where-Object { $_ -match '\.kt:|\.kts:|Error|error:|warning:|violat|FAILED|What went wrong' } |
    Select-Object -First 40 |
    ForEach-Object { $_ }
