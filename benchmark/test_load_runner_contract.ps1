$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gate = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "spring_boot_gate.ps1")
$workflow = Get-Content -Raw -LiteralPath `
        (Join-Path $projectRoot ".github/workflows/production-gate.yml")
$release = Get-Content -Raw -LiteralPath `
        (Join-Path $projectRoot ".github/workflows/release.yml")

function Assert-Contains([string] $Text, [string] $Pattern, [string] $Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
}

function Get-FunctionText([string] $Text, [string] $Name, [string] $NextName) {
    $start = $Text.IndexOf("function $Name", [StringComparison]::Ordinal)
    $end = $Text.IndexOf("function $NextName", $start + 1, [StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -le $start) { throw "Cannot locate function $Name." }
    return $Text.Substring($start, $end - $start)
}

function Get-FunctionTextUntilMarker([string] $Text, [string] $Name, [string] $Marker) {
    $start = $Text.IndexOf("function $Name", [StringComparison]::Ordinal)
    $end = $Text.IndexOf($Marker, $start + 1, [StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -le $start) { throw "Cannot locate function $Name." }
    return $Text.Substring($start, $end - $start)
}

$warmup = Get-FunctionText $gate "Invoke-Warmup" "Invoke-InterleavedPreWarm"
$measurement = Get-FunctionText $gate "Invoke-Wrk" "Median"
$earlyDecision = Get-FunctionTextUntilMarker `
        $gate `
        "Test-EarlyReleaseDecision" `
        "`nPrepare-Build"

Assert-Contains $gate 'function Start-LoadRunner' "The gate must start one persistent load runner."
Assert-Contains $gate 'Set-ReactorCurrentProcessCpuAffinity -CpuSet \$OrchestratorCpuSet' `
        "Benchmark orchestration must not share the wrk CPU set."
Assert-Contains $warmup '@\("exec", \$LoadRunner, "wrk"' `
        "Warmup must use docker exec, not docker run."
if ($warmup -match 'docker\s+run|@\("run"') {
    throw "Warmup must not create a container per sample."
}
Assert-Contains $measurement 'docker exec -d \$LoadRunner' `
        "Measured wrk must reuse the persistent runner."
if ($measurement -match 'spring-glowroot-wrk-|docker\s+logs') {
    throw "Measured load must not create or inspect one-shot wrk containers."
}
Assert-Contains $gate '@\(\$Baseline, \$Candidate, \$Collector, \$LoadRunner\)' `
        "Gate cleanup must remove the persistent load runner."

if (([regex]::Matches($workflow, '-MaxWarmupRounds 6')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-MaxWarmupConfirmationRounds 10')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-PreWarmCycles 4')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-PairRepeats 6')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-MinimumPairRepeats 3')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-HeavyConcurrencyLevels "64,128"')).Count -ne 2) {
    throw "Spring and Rust-Java jobs must use the same bounded adaptive warmup contract."
}
Assert-Contains $workflow 'REACTOR_GATE_QUALIFICATION_DEPTH' `
        "Workflow must expose release and extended gate depths."
Assert-Contains $workflow 'spring-boot-agent:\s+needs: \[gate-statistics, rust-java-rest-agent\]' `
        "The production workflow must stop before Spring when the Rust-Java gate fails."
if (([regex]::Matches($release, '\.pair_repeats >= 3')).Count -ne 2 -or
        ([regex]::Matches($release, '\.minimum_pair_repeats == 3')).Count -ne 2 -or
        ([regex]::Matches($release, '\.maximum_pair_repeats == 6')).Count -ne 2 -or
        ([regex]::Matches($release, '\.pair_decision == "strict_early_pass"')).Count -ne 2 -or
        ([regex]::Matches($release, '\.pair_decision == "maximum_pairs"')).Count -ne 2 -or
        ([regex]::Matches($release, '\.cpu_roles\.orchestrator')).Count -ne 3 -or
        ([regex]::Matches($release, '\.warmup_stability\.fixed_rounds == 6')).Count -ne 2 -or
        ([regex]::Matches(
            $release,
            '\.warmup_stability\.load_runner_lifecycle == "persistent_per_gate"'
        )).Count -ne 2) {
    throw "Release evidence checks must enforce the optimized production-gate contract."
}

. (Join-Path $PSScriptRoot "gate_statistics.ps1")
function Median([double[]] $Values) { return Get-ReactorMedian -Values $Values }
function Get-EndpointConcurrency([string] $Endpoint) { return @(64) }
Invoke-Expression $earlyDecision

$RequireAllPairs = $false
$MinimumPairRepeats = 3
$PairRepeats = 6
$MinUsefulRpsDeltaPercent = -2.0
$MaxP99RegressionPercent = 10.0
$AllowedThreadDelta = 1
$MaxHostSiblingCpuPercent = 10.0
$MaxHostStealCpuPercent = 1.0
$MaxNon2xxDeltaPercentagePoints = 0.0
$MaxSaturatedNon2xxDeltaPercentagePoints = 0.02
$MaxAbsoluteNon2xxPercent = 0.05
$MaxMemoryRegressionMiB = 3.0
$IsRustJavaRest = $false
$endpoints = @("small-json")

function New-Record([int] $Pair, [string] $Variant, [double] $Rps) {
    return [pscustomobject]@{
        pair = $Pair
        endpoint = "small-json"
        concurrency = 64
        variant = $Variant
        useful_rps = $Rps
        p99_ms = 10.0
        threads = if ($Variant -eq "candidate") { 11 } else { 10 }
        host_sibling_busy_percent = 0.0
        host_steal_percent = 0.0
        requests = 10000
        non_2xx = 0
    }
}

$records = @()
$startups = @()
$steadyMemory = @()
foreach ($pair in 1..3) {
    $records += New-Record $pair "baseline" 1000.0
    $records += New-Record $pair "candidate" 1000.0
    $startups += [pscustomobject]@{ pair = $pair; variant = "baseline"; ms = 1000.0 }
    $startups += [pscustomobject]@{ pair = $pair; variant = "candidate"; ms = 1020.0 }
    $steadyMemory += [pscustomobject]@{
        pair = $pair; variant = "baseline"; process_rss_mib = 50.0; container_mib = 55.0; threads = 10
    }
    $steadyMemory += [pscustomobject]@{
        pair = $pair; variant = "candidate"; process_rss_mib = 51.0; container_mib = 56.0; threads = 11
    }
}
if (-not (Test-EarlyReleaseDecision -CompletedPairs 3)) {
    throw "A clean three-pair matrix must satisfy the strict early-pass envelope."
}
$records | Where-Object { $_.variant -eq "candidate" } | ForEach-Object { $_.useful_rps = 970.0 }
if (Test-EarlyReleaseDecision -CompletedPairs 3) {
    throw "A three-percent RPS regression must continue to the maximum pair count."
}
$RequireAllPairs = $true
$records | Where-Object { $_.variant -eq "candidate" } | ForEach-Object { $_.useful_rps = 1000.0 }
if (Test-EarlyReleaseDecision -CompletedPairs 3) {
    throw "Extended qualification must never stop at the minimum pair count."
}

Write-Host "Persistent load-runner contract tests passed."
