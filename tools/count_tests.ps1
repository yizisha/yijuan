$files = Get-ChildItem -Recurse -Path 'R:\app\build\test-results','R:\core\build\test-results','R:\data\build\test-results','R:\domain\build\test-results','R:\feature\reader\build\test-results' -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue
$t = 0
$f = 0
foreach ($file in $files) {
    $x = [xml](Get-Content $file.FullName)
    $t += [int]$x.testsuite.tests
    $f += [int]$x.testsuite.failures
}
Write-Output "Total tests: $t, Failures: $f, Suites: $($files.Count)"
