$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gate = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "spring_boot_gate.ps1")
$protocolGate = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "glowroot_gate.ps1")
$embeddedServerGate = Get-Content -Raw -LiteralPath `
        (Join-Path $PSScriptRoot "embedded_server_dependency_gate.ps1")
$runtimeCompatibilityGate = Get-Content -Raw -LiteralPath `
        (Join-Path $PSScriptRoot "spring_runtime_compatibility_gate.ps1")
$nonWebGate = Get-Content -Raw -LiteralPath `
        (Join-Path $PSScriptRoot "non_web_gate.ps1")
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

[xml]$rootPom = Get-Content -Raw -LiteralPath (Join-Path $projectRoot "pom.xml")
$agentVersion = "$($rootPom.project.version)".Trim()
foreach ($fixture in @(
        [pscustomobject]@{
            Path = "spring-app/pom.xml"
            Artifact = "java-rust-glowroot-spring-boot-starter"
        },
        [pscustomobject]@{
            Path = "webflux-app/pom.xml"
            Artifact = "java-rust-glowroot-spring-webflux-adapter"
        },
        [pscustomobject]@{
            Path = "non-web-app/pom.xml"
            Artifact = "java-rust-glowroot-spring-boot-starter"
        })) {
    [xml]$fixturePom = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot $fixture.Path)
    $fixtureVersion = "$($fixturePom.project.properties.'glowroot.agent.version')".Trim()
    if ($fixtureVersion -ne $agentVersion) {
        throw "$($fixture.Path) must default glowroot.agent.version to root version $agentVersion; found $fixtureVersion."
    }
    $agentDependency = @($fixturePom.project.dependencies.dependency) |
            Where-Object { $_.artifactId -eq $fixture.Artifact } |
            Select-Object -First 1
    if ($null -eq $agentDependency -or "$($agentDependency.version)" -ne '${glowroot.agent.version}') {
        throw "$($fixture.Path) must reference $($fixture.Artifact) through glowroot.agent.version."
    }
}
foreach ($fixtureGate in @($gate, $embeddedServerGate, $runtimeCompatibilityGate, $nonWebGate)) {
    Assert-Contains $fixtureGate ([regex]::Escape('"-Dglowroot.agent.version=$agentVersion"')) `
            "Every fixture build gate must pass the root agent version explicitly."
}

$warmup = Get-FunctionText $gate "Invoke-Warmup" "Invoke-InterleavedPreWarm"
$measurement = Get-FunctionText $gate "Invoke-Wrk" "Median"
$earlyDecision = Get-FunctionTextUntilMarker `
        $gate `
        "Test-EarlyReleaseDecision" `
        "`nPrepare-Build"
$invalidPairHelpers = Get-FunctionTextUntilMarker `
        $gate `
        "Reset-ReactorListToCount" `
        "`nfunction Test-EarlyReleaseDecision"

Assert-Contains $gate 'function Start-LoadRunner' "The gate must start one persistent load runner."
Assert-Contains $gate 'function Get-ApplicationExecutionCpuSet' `
        "The gate must separate the reserved SMT group from the one-CPU execution set."
Assert-Contains $gate '\$ContainerCpuSets\[\$Name\] = \$executionCpuSet' `
        "Runtime noise accounting must use the application's actual execution CPU set."
Assert-Contains $gate 'application_execution = Get-ApplicationExecutionCpuSet' `
        "Release evidence must record the application's actual execution CPU set."
Assert-Contains $gate '\[bool\] \$PinSingleCpuQuotaToOneLogicalCpu = \$false' `
        "Single-logical-CPU execution must remain an explicit diagnostic, not the release default."
Assert-Contains $gate 'single_cpu_logical_pin = \$PinSingleCpuQuotaToOneLogicalCpu' `
        "Release evidence must record whether the diagnostic logical-CPU pin was enabled."
Assert-Contains $gate '\[int\] \$MaxInvalidPairAttempts = 2' `
        "The release gate must bound invalid process-pair replacement."
Assert-Contains $gate 'invalid_pair_policy = "discard_entire_pair_and_restart_both_variants"' `
        "Release evidence must describe the symmetric invalid-pair policy."
Assert-Contains $gate 'Reset-ReactorListToCount -List \$records' `
        "An invalid process pair must not leak workload samples into release evidence."
Assert-Contains $gate 'Reset-ReactorListToCount -List \$startups' `
        "An invalid process pair must not leak startup samples into release evidence."
Assert-Contains $gate 'Reset-ReactorListToCount -List \$steadyMemory' `
        "An invalid process pair must not leak memory samples into release evidence."
Assert-Contains $gate 'Reset-ReactorListToCount -List \$warmups' `
        "An invalid process pair must not leak warmup samples into release evidence."
Assert-Contains $gate 'function Assert-SpringTelemetryAdapter' `
        "The Spring release gate must verify the selected HTTP adapter."
Assert-Contains $gate '\[ValidateSet\("tomcat", "jetty", "undertow", "reactor-netty"\)\]' `
        "The Spring gate must expose every supported Servlet and reactive runtime."
Assert-Contains $gate '"spring-webflux"' `
        "The gate must expose the separately packaged WebFlux application."
foreach ($adapterId in @("tomcat-valve", "jetty-request-log", "undertow-completion-listener", "webflux-filter")) {
    Assert-Contains $gate ([regex]::Escape($adapterId)) `
            "The gate must require the complete adapter $adapterId."
}
Assert-Contains $gate '\$adapter\.httpAdapter -ne \$expectedAdapter' `
        "The Spring candidate must prove the selected framework-level HTTP adapter."
Assert-Contains $gate '\$adapter\.fullLifecycle -ne \$true' `
        "Every supported server must expose the complete HTTP lifecycle contract."
Assert-Contains $gate 'function Assert-SpringLifecycleSmoke' `
        "Every Spring runtime must pass the shared sync, async, error, and 404 lifecycle smoke."
Assert-Contains $gate 'collectorEvidencePassed = \$collectorEvidence\.healthy' `
        "The Spring gate must require collector-side aggregate evidence."
Assert-Contains $gate '-and \$steadyMemoryPassed -and \$warmupGatePassed -and \$collectorEvidencePassed' `
        "Warmup and collector evidence must participate in the final gate decision."
Assert-Contains $gate 'Set-ReactorCurrentProcessCpuAffinity -CpuSet \$OrchestratorCpuSet' `
        "Benchmark orchestration must not share the wrk CPU set."
Assert-Contains $protocolGate '\$OrchestratorCpuSet = \$selectedRoles\.orchestrator' `
        "The protocol gate must retain the auto-selected orchestrator CPU set."
Assert-Contains $protocolGate 'Set-ReactorCurrentProcessCpuAffinity -CpuSet \$OrchestratorCpuSet' `
        "Protocol orchestration must not run on the wrk CPU set."
Assert-Contains $protocolGate '-OrchestratorCpuSet \$OrchestratorCpuSet' `
        "The protocol isolation check must receive the orchestrator CPU set."
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
        ([regex]::Matches($workflow, '-MaxWarmupConfirmationRounds 14')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-PreWarmCycles 4')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-PairRepeats 6')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-MinimumPairRepeats 3')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-MaxInvalidPairAttempts 2')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-HeavyConcurrencyLevels "64,128"')).Count -ne 2) {
    throw "Spring and Rust-Java jobs must use the same bounded adaptive warmup contract."
}
Assert-Contains $workflow 'REACTOR_GATE_QUALIFICATION_DEPTH' `
        "Workflow must expose release and extended gate depths."
Assert-Contains $workflow '-ServletContainer tomcat' `
        "The full Spring release matrix must state its embedded server explicitly."
Assert-Contains $workflow 'embedded_server_dependency_gate\.ps1 -SkipAgentBuild' `
        "CI must prove that the agent does not select or leak an embedded server."
Assert-Contains $workflow 'spring_runtime_compatibility_gate\.ps1' `
        "CI must start every supported Spring HTTP runtime and run the shared lifecycle/load gate."
Assert-Contains $embeddedServerGate 'starter_server_dependencies = "internal-adapter-jars-only"' `
        "The dependency gate must prove that the main starter contains adapters but no server engine."
Assert-Contains $embeddedServerGate 'The common starter must not depend on' `
        "The dependency gate must reject direct Tomcat, Jetty, and Undertow dependencies."
Assert-Contains $embeddedServerGate 'tomcat-embed-core-' `
        "The dependency gate must distinguish the Tomcat engine from an EL implementation."
Assert-Contains $embeddedServerGate 'forbidden_engines_absent = \$true' `
        "The dependency gate must record server isolation in its evidence."
Assert-Contains $embeddedServerGate 'spring-webflux-glowroot-benchmark-' `
        "The dependency gate must verify the separate WebFlux application artifact."
Assert-Contains $workflow 'rest_evidence_run_id:' `
        "Workflow must support content-addressed reuse of a passing REST matrix."
Assert-Contains $workflow 'runtime_evidence_identity\.ps1' `
        "Reused REST evidence must be verified against the current runtime tree."
Assert-Contains $workflow 'ref: 869f69aa94eeac6346ca9dc535c54b70505a19c5' `
        "The production workflow must pin the coordinated Rust-Java REST 4.6.2 commit."
Assert-Contains $workflow '-RequiredRestVersion "4\.6\.2"' `
        "The production workflow must reject a different Rust-Java REST release."
Assert-Contains $release 'verify-production-gate\.ps1' `
        "Release publication must require an exact-commit successful Production Gate."
Assert-Contains $release 'runtime-evidence-identity\.json' `
        "Release publication must require the runtime evidence identity manifest."
Assert-Contains $release '\(\.runtime_objects \| length\) == 4' `
        "Runtime evidence must include the agent-owned REST benchmark fixture."
if (([regex]::Matches($workflow, '-EndpointClasses \$endpointClasses')).Count -ne 2 -or
        ([regex]::Matches($workflow, '"small-json,raw-json,heavy-json"')).Count -ne 2 -or
        ([regex]::Matches($workflow, '"small-json,raw-json"')).Count -ne 2) {
    throw "Release depth must measure stable small/raw paths while extended depth retains heavy JSON."
}
Assert-Contains $workflow 'spring-boot-agent:\s+needs: \[gate-statistics, rust-java-rest-agent\]' `
        "The production workflow must stop before Spring when the Rust-Java gate fails."
if (([regex]::Matches($release, '\.pair_repeats >= 3')).Count -ne 2 -or
        ([regex]::Matches($release, '\.minimum_pair_repeats == 3')).Count -ne 2 -or
        ([regex]::Matches($release, '\.maximum_pair_repeats == 6')).Count -ne 2 -or
        ([regex]::Matches($release, '\.pair_decision == "strict_early_pass"')).Count -ne 2 -or
        ([regex]::Matches($release, '\.pair_decision == "maximum_pairs"')).Count -ne 2 -or
        ([regex]::Matches($release, '\.cpu_roles\.orchestrator')).Count -ne 3 -or
        ([regex]::Matches($release, '\.measured_endpoint_classes == \["small-json", "raw-json"\]')).Count -ne 2 -or
        ([regex]::Matches($release, '\.smoked_endpoint_classes \| sort')).Count -ne 2 -or
        ([regex]::Matches($release, '\(\.rows \| length\) == 4')).Count -ne 2 -or
        ([regex]::Matches($release, '\.warmup_stability\.fixed_rounds == 6')).Count -ne 2 -or
        ([regex]::Matches(
            $release,
            '\.warmup_stability\.load_runner_lifecycle == "persistent_per_gate"'
        )).Count -ne 2) {
    throw "Release evidence checks must enforce the optimized production-gate contract."
}
Assert-Contains $release '\.pair_attempts == \(\.pair_repeats \+ \.invalid_pair_attempts\)' `
        "Release publication must verify valid and rejected process-pair accounting."
Assert-Contains $release '\.invalid_pair_attempts <= 2 and \.maximum_invalid_pair_attempts == 2' `
        "Release publication must enforce the invalid process-pair budget."

Invoke-Expression $invalidPairHelpers
$rollbackProbe = [Collections.Generic.List[object]]::new()
$rollbackProbe.Add("kept")
$rollbackProbe.Add("discarded-1")
$rollbackProbe.Add("discarded-2")
Reset-ReactorListToCount -List $rollbackProbe -Count 1
if ($rollbackProbe.Count -ne 1 -or $rollbackProbe[0] -ne "kept") {
    throw "Invalid process-pair rollback did not preserve only pre-attempt evidence."
}
try {
    throw "Warmup did not stabilize for pair=1 variant=baseline endpoint=small-json"
} catch {
    if (-not (Test-IsWarmupStabilityFailure -ErrorRecord $_)) {
        throw "Warmup stability failures must be eligible for bounded pair replacement."
    }
}
try {
    throw "Warmup failed for baseline/small-json"
} catch {
    if (Test-IsWarmupStabilityFailure -ErrorRecord $_) {
        throw "Transport or correctness failures must not be hidden as invalid process pairs."
    }
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
