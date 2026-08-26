$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "benchmark_isolation.ps1")
$isolationSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "benchmark_isolation.ps1")

if ($isolationSource -notmatch 'automatic-idle-sample-shared-infrastructure' -or
        $isolationSource -notmatch 'infrastructure-physical-group-sharing=allowed') {
    throw "Managed two-core runners must keep the application isolated from one bounded shared infrastructure group."
}

function Assert-Equal([object] $Expected, [object] $Actual, [string] $Message) {
    if ($Expected -ne $Actual) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-Throws([scriptblock] $Action, [string] $MessagePattern, [string] $Message) {
    $caught = $null
    try {
        & $Action
    } catch {
        $caught = $_
    }
    if ($null -eq $caught) {
        throw "$Message Expected an exception."
    }
    if ("$($caught.Exception.Message)" -notmatch $MessagePattern) {
        throw "$Message Unexpected exception: $($caught.Exception.Message)"
    }
}

$environmentNames = @(
    "REACTOR_BENCHMARK_APPLICATION_CPU_SET",
    "REACTOR_BENCHMARK_RUNNER_CPU_SET",
    "REACTOR_BENCHMARK_COLLECTOR_CPU_SET",
    "REACTOR_BENCHMARK_ORCHESTRATOR_CPU_SET"
)
$original = @{}
foreach ($name in $environmentNames) {
    $original[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $null, "Process")
    }
    Assert-Equal $null (Get-ReactorConfiguredBenchmarkCpuRoles) `
        "No CPU role environment must preserve automatic selection."

    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_APPLICATION_CPU_SET", "6,7", "Process")
    Assert-Throws { Get-ReactorConfiguredBenchmarkCpuRoles } "configuration is partial" `
        "Partial CPU role configuration must fail closed."

    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_RUNNER_CPU_SET", "4,5", "Process")
    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_COLLECTOR_CPU_SET", "0", "Process")
    Assert-Throws { Get-ReactorConfiguredBenchmarkCpuRoles } "configuration is partial" `
        "The orchestrator CPU role must be explicit."

    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_ORCHESTRATOR_CPU_SET", "2,3", "Process")
    $configured = Get-ReactorConfiguredBenchmarkCpuRoles
    Assert-Equal "6,7" $configured.application "Application CPU set must remain normalized."
    Assert-Equal "4,5" $configured.runner "Runner CPU set must remain normalized."
    Assert-Equal "0" $configured.collector "Collector CPU set must remain normalized."
    Assert-Equal "2,3" $configured.orchestrator "Orchestrator CPU set must remain normalized."
    Assert-Equal "environment" $configured.source "Configured CPU role source must be observable."

    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_APPLICATION_CPU_SET", "7,6", "Process")
    $configured = Get-ReactorConfiguredBenchmarkCpuRoles
    Assert-Equal "6,7" $configured.application "Application CPU set must be sorted and deduplicated."

    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_RUNNER_CPU_SET", "5,4", "Process")
    $configured = Get-ReactorConfiguredBenchmarkCpuRoles
    Assert-Equal "4,5" $configured.runner "Runner CPU set must be sorted and deduplicated."

    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_RUNNER_CPU_SET", "4,5", "Process")
    [Environment]::SetEnvironmentVariable("REACTOR_BENCHMARK_COLLECTOR_CPU_SET", "1-2", "Process")
    Assert-Throws { Get-ReactorConfiguredBenchmarkCpuRoles } "COLLECTOR_CPU_SET must contain exactly one" `
        "Collector role must use one logical CPU."

    Assert-Equal "1,2,3,5" (ConvertTo-ReactorNormalizedCpuSet -CpuSet "5,1-3,2") `
        "CPU set normalization must support ranges and duplicates."
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $original[$name], "Process")
    }
}

Write-Host "Benchmark isolation tests passed."
