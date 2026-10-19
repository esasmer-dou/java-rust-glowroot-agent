$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/native_runtime_contract.ps1"
$revision = '0123456789012345678901234567890123456789'
$manifest = "schema=2`nrest.abi=29`nsource.revision=$revision"
if ((Assert-ReactorNativeRuntimeContract $manifest 29 $revision) -ne $revision) {
    throw 'Matching native source was not preserved.'
}
foreach ($invalid in @(
    $manifest.Replace('schema=2','schema=1'),
    $manifest.Replace('rest.abi=29','rest.abi=28'),
    $manifest.Replace($revision,"$revision-dirty"),
    $manifest.Replace($revision,'1123456789012345678901234567890123456789'),
    "$manifest`nsource.revision=$revision"
)) {
    $failed = $false
    try { Assert-ReactorNativeRuntimeContract $invalid 29 $revision | Out-Null }
    catch { $failed = $true }
    if (!$failed) { throw "Unsafe native provenance accepted: $invalid" }
}
Write-Host 'Native runtime source contract: PASS'
