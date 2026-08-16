[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $CurrentCommit,
    [Parameter(Mandatory)]
    [string] $EvidenceCommit,
    [Parameter(Mandatory)]
    [string] $EvidenceRunId,
    [Parameter(Mandatory)]
    [string] $EvidenceRoot,
    [Parameter(Mandatory)]
    [string] $OutputPath,
    [string] $RequiredRestVersion = "4.5.4",
    [int] $RequiredRestNativeAbi = 29
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePaths = @(
    "agent-bootstrap/src/main",
    "spring-boot-starter/src/main/resources/native",
    "benchmark/app.Dockerfile"
)

function Resolve-GitObject([string] $Commit, [string] $Path) {
    $object = (& git -C $projectRoot rev-parse "${Commit}:$Path" 2>&1) -join ""
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($object)) {
        throw "Cannot resolve runtime object ${Commit}:$Path."
    }
    return $object.Trim()
}

$summaries = @(Get-ChildItem -LiteralPath $EvidenceRoot -Recurse -File -Filter "summary.json" |
        Where-Object { $_.Directory.Name -like "rest_gate_*" })
if ($summaries.Count -ne 1) {
    throw "Expected exactly one REST production summary under $EvidenceRoot; found $($summaries.Count)."
}
$summary = Get-Content -Raw -LiteralPath $summaries[0].FullName | ConvertFrom-Json
if ($summary.passed -ne $true -or $summary.release_evidence -ne $true `
        -or $summary.benchmark_classification -ne "production" `
        -or $summary.application_kind -ne "rust-java-rest" `
        -or $summary.required_rest_version -ne $RequiredRestVersion `
        -or [int] $summary.required_rest_native_abi -ne $RequiredRestNativeAbi `
        -or $summary.warmup_stability.gate -ne "PASS" `
        -or @($summary.rows).Count -ne 4 `
        -or @($summary.rows | Where-Object gate -ne "PASS").Count -ne 0) {
    throw "The selected REST evidence is not a passing production matrix for REST $RequiredRestVersion ABI $RequiredRestNativeAbi."
}

$objects = [Collections.Generic.List[object]]::new()
foreach ($path in $runtimePaths) {
    $currentObject = Resolve-GitObject -Commit $CurrentCommit -Path $path
    $evidenceObject = Resolve-GitObject -Commit $EvidenceCommit -Path $path
    $matches = $currentObject -eq $evidenceObject
    $objects.Add([ordered]@{
        path = $path
        current_object = $currentObject
        evidence_object = $evidenceObject
        matches = $matches
    })
    if (-not $matches) {
        throw "Runtime evidence mismatch for $path. Current=$currentObject evidence=$evidenceObject."
    }
}

$outputDirectory = Split-Path -Parent $OutputPath
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
[ordered]@{
    schema = 1
    passed = $true
    current_commit = $CurrentCommit
    evidence_commit = $EvidenceCommit
    evidence_run_id = $EvidenceRunId
    evidence_reused = $CurrentCommit -ne $EvidenceCommit
    required_rest_version = $RequiredRestVersion
    required_rest_native_abi = $RequiredRestNativeAbi
    summary_path = [IO.Path]::GetRelativePath(
        [IO.Path]::GetFullPath($EvidenceRoot),
        $summaries[0].FullName)
    runtime_objects = @($objects)
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8

Write-Host "Runtime evidence identity verified: current=$CurrentCommit evidence=$EvidenceCommit reused=$($CurrentCommit -ne $EvidenceCommit)."
