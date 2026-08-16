[CmdletBinding()]
param(
    [ValidateSet("spring-boot", "rust-java-rest", "all")]
    [string] $ApplicationKind = "rust-java-rest",
    [string] $RequiredRestVersion = "4.5.2",
    [int] $RequiredRestNativeAbi = 29
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$startedAt = [DateTime]::UtcNow
$applications = if ($ApplicationKind -eq "all") {
    @("spring-boot", "rust-java-rest")
} else {
    @($ApplicationKind)
}

& (Join-Path $ScriptDir "test_gate_statistics.ps1")
& (Join-Path $ScriptDir "test_warmup_statistics.ps1")

$results = [Collections.Generic.List[object]]::new()
foreach ($application in $applications) {
    $arguments = @{
        ApplicationKind = $application
        PairRepeats = 3
        ConcurrencyLevels = "64"
        EndpointClasses = "small-json,raw-json"
        Duration = "5s"
        Warmup = "2s"
        MinWarmupRounds = 3
        MaxWarmupRounds = 6
        MaxWarmupConfirmationRounds = 0
        MinUsefulRpsDeltaPercent = -10
        MaxP99RegressionPercent = 25
        SkipHostPreflight = $true
        DevelopmentQuickMode = $true
        FailOnGate = $true
    }
    if ($application -eq "rust-java-rest") {
        $arguments.RequiredRestVersion = $RequiredRestVersion
        $arguments.RequiredRestNativeAbi = $RequiredRestNativeAbi
        $arguments.MemoryLimit = "128m"
        $arguments.AllowedThreadDelta = 1
    }
    if ($IsLinux) {
        $arguments.AutoSelectCpuRoles = $true
        $arguments.AllowRunnerCollectorSiblingSharing = $true
    }

    Write-Host "Running development-only Docker quick gate for $application."
    & (Join-Path $ScriptDir "spring_boot_gate.ps1") @arguments
    $results.Add([ordered]@{ application = $application; passed = $true })
}

$evidence = [ordered]@{
    schema = 1
    classification = "development-only"
    release_evidence = $false
    applications = $results
    concurrency = @(64)
    endpoints = @("small-json", "raw-json")
    pair_repeats = 3
    started_at_utc = $startedAt.ToString("O")
    completed_at_utc = [DateTime]::UtcNow.ToString("O")
}
$evidencePath = Join-Path $ScriptDir "results/local_quick_gate.json"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidencePath) | Out-Null
$evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $evidencePath -Encoding utf8
Write-Host "Local Docker quick gate passed. This result is diagnostic and cannot satisfy the release gate."
