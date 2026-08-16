[CmdletBinding()]
param(
    [ValidateSet("spring-boot", "rust-java-rest")]
    [string] $ApplicationKind = "spring-boot",
    [string] $RequiredRestVersion = "4.5.4",
    [int] $RequiredRestNativeAbi = 29,
    [int] $PairRepeats = 3,
    [int] $MinimumPairRepeats = 3,
    [switch] $RequireAllPairs,
    [string] $ConcurrencyLevels = "64,256",
    [string] $HeavyConcurrencyLevels = "",
    [string] $EndpointClasses = "small-json,raw-json,heavy-json",
    [string] $Duration = "12s",
    [string] $Warmup = "5s",
    [int] $PreWarmCycles = 2,
    [int] $MinWarmupRounds = 3,
    [int] $MaxWarmupRounds = 16,
    [int] $MaxWarmupConfirmationRounds = 4,
    [double] $MaxWarmupRobustTrendPercent = 3.0,
    [double] $MaxWarmupBorderlineRobustTrendPercent = 5.0,
    [double] $MaxWarmupBorderlineMedianShiftPercent = 3.0,
    [double] $MaxWarmupMedianAbsoluteDeviationPercent = 4.0,
    [double] $CpuLimit = 1.0,
    [bool] $PinSingleCpuQuotaToOneLogicalCpu = $false,
    [string] $MemoryLimit = "256m",
    [string] $SlotACpuSet = "0",
    [string] $SlotBCpuSet = "2",
    [string] $RunnerCpuSet = "4-5",
    [string] $CollectorCpuSet = "6",
    [string] $OrchestratorCpuSet = "7",
    [switch] $SequentialVariants,
    [switch] $AutoSelectCpuRoles,
    [switch] $UseJavaAgentBootstrap,
    [switch] $AllowRunnerCollectorSiblingSharing,
    [double] $MinUsefulRpsDeltaPercent = -2.0,
    [double] $MaxP99RegressionPercent = 10.0,
    [double] $MaxMemoryRegressionMiB = 3.0,
    [int] $AllowedThreadDelta = 1,
    [Alias("Max503DeltaPercentagePoints")]
    [double] $MaxNon2xxDeltaPercentagePoints = 0.0,
    [double] $MaxSaturatedNon2xxDeltaPercentagePoints = 0.02,
    [double] $MaxAbsoluteNon2xxPercent = 0.05,
    [double] $MaxHostCpuAveragePercent = 15.0,
    [double] $MaxHostCpuPeakPercent = 40.0,
    [double] $MaxHostSiblingCpuPercent = 10.0,
    [double] $MaxHostStealCpuPercent = 1.0,
    [int] $HostStabilizationSeconds = 10,
    [switch] $SkipHostPreflight,
    [switch] $SkipBuild,
    [switch] $DevelopmentQuickMode,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
if ($MinimumPairRepeats -lt 3) { throw "MinimumPairRepeats must be at least 3." }
if ($PairRepeats -lt $MinimumPairRepeats) {
    throw "PairRepeats must be greater than or equal to MinimumPairRepeats."
}
if ($MaxMemoryRegressionMiB -gt 3.0) { throw "The agent memory gate cannot exceed 3 MiB." }
if ($PreWarmCycles -lt 1 -or $PreWarmCycles -gt 8) {
    throw "PreWarmCycles must be between 1 and 8."
}
if ($MinWarmupRounds -lt 2) { throw "MinWarmupRounds must be at least 2." }
if ($MaxWarmupRounds -lt (2 * $MinWarmupRounds)) {
    throw "Warmup must contain at least two complete stability windows."
}
if ($MaxWarmupConfirmationRounds -lt 0 -or $MaxWarmupConfirmationRounds -gt 16) {
    throw "MaxWarmupConfirmationRounds must be between 0 and 16."
}
if ($MaxWarmupRobustTrendPercent -le 0 -or $MaxWarmupRobustTrendPercent -gt 3.0) {
    throw "MaxWarmupRobustTrendPercent must be between 0 and 3 percent."
}
if ($MaxWarmupBorderlineRobustTrendPercent -lt $MaxWarmupRobustTrendPercent `
        -or $MaxWarmupBorderlineRobustTrendPercent -gt 5.0) {
    throw "MaxWarmupBorderlineRobustTrendPercent must be between the primary threshold and 5 percent."
}
if ($MaxWarmupBorderlineMedianShiftPercent -le 0 `
        -or $MaxWarmupBorderlineMedianShiftPercent -gt 3.0) {
    throw "MaxWarmupBorderlineMedianShiftPercent must be between 0 and 3 percent."
}
if ($MaxWarmupMedianAbsoluteDeviationPercent -le 0 `
        -or $MaxWarmupMedianAbsoluteDeviationPercent -gt 4.0) {
    throw "MaxWarmupMedianAbsoluteDeviationPercent must be between 0 and 4 percent."
}
if ($MaxNon2xxDeltaPercentagePoints -lt 0.0) {
    throw "MaxNon2xxDeltaPercentagePoints cannot be negative."
}
if ($MaxSaturatedNon2xxDeltaPercentagePoints -lt $MaxNon2xxDeltaPercentagePoints `
        -or $MaxSaturatedNon2xxDeltaPercentagePoints -gt 0.02) {
    throw "The saturated non-2xx delta must be between the general threshold and 0.02 percentage points."
}
if ($MaxAbsoluteNon2xxPercent -le 0.0 -or $MaxAbsoluteNon2xxPercent -gt 0.05) {
    throw "The absolute non-2xx ceiling must be between 0 and 0.05 percent."
}
if ($DevelopmentQuickMode -and -not $SkipHostPreflight) {
    throw "DevelopmentQuickMode requires SkipHostPreflight and cannot produce release evidence."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDir "benchmark_isolation.ps1")
. (Join-Path $ScriptDir "gate_statistics.ps1")
. (Join-Path $ScriptDir "warmup_statistics.ps1")
$ProjectRoot = Split-Path -Parent $ScriptDir
$IsRustJavaRest = $ApplicationKind -eq "rust-java-rest"
$SpringRoot = Join-Path $ScriptDir "spring-app"
$FrameworkRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot "..\rust-java-rest"))
$ContextName = if ($IsRustJavaRest) { "context" } else { "spring-context" }
$ResultPattern = if ($IsRustJavaRest) { "results\rest_gate_{0}" } else { "results\spring_gate_{0}" }
$Context = Join-Path $ScriptDir $ContextName
$Results = Join-Path $ScriptDir ($ResultPattern -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$Network = "reactor-benchmark-net"
$AppImage = if ($IsRustJavaRest) {
    "java-rust-glowroot-agent:benchmark-app"
} else {
    "java-rust-glowroot-agent:spring-benchmark"
}
$CollectorImage = "java-rust-glowroot-agent:mock-collector"
$RunnerImage = "curlimages/curl:8.12.1"
$WrkImage = "williamyeh/wrk:latest"
$Baseline = if ($IsRustJavaRest) { "rest-glowroot-baseline" } else { "spring-glowroot-baseline" }
$Candidate = if ($IsRustJavaRest) { "rest-glowroot-candidate" } else { "spring-glowroot-candidate" }
$Collector = if ($IsRustJavaRest) { "rest-glowroot-collector" } else { "spring-glowroot-collector" }
$LoadRunner = if ($IsRustJavaRest) { "rest-glowroot-load-runner" } else { "spring-glowroot-load-runner" }
$ContainerCpuSets = @{}

$EndpointMap = if ($IsRustJavaRest) {
    @{
        "small-json" = "/api/v1/candidates/direct"
        "raw-json" = "/api/v1/heavy/raw"
        "heavy-json" = "/api/v1/heavy?items=100"
    }
} else {
    @{
        "small-json" = "/api/small?id=42"
        "raw-json" = "/api/raw"
        "heavy-json" = "/api/heavy?items=100"
    }
}
$concurrency = @($ConcurrencyLevels -split "[,\s]+" | Where-Object { $_ } | ForEach-Object { [int] $_ })
$heavyConcurrency = if ([string]::IsNullOrWhiteSpace($HeavyConcurrencyLevels)) {
    $concurrency
} else {
    @($HeavyConcurrencyLevels -split "[,\s]+" | Where-Object { $_ } | ForEach-Object { [int] $_ })
}
$endpoints = @($EndpointClasses -split "[,\s]+" | Where-Object { $_ })
foreach ($endpoint in $endpoints) {
    if (-not $EndpointMap.ContainsKey($endpoint)) { throw "Unknown endpoint class: $endpoint" }
}

function Invoke-Checked([string] $File, [string[]] $Arguments, [string] $WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        $output = & $File @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed: $File $($Arguments -join ' ')`n$($output -join "`n")"
        }
        return $output
    } finally {
        Pop-Location
    }
}

function Remove-Container([string] $Name) {
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name }) {
        & docker rm -f $Name *> $null
    }
    [void] $ContainerCpuSets.Remove($Name)
}

function Get-EndpointConcurrency([string] $Endpoint) {
    if ($Endpoint -eq "heavy-json") { return @($heavyConcurrency) }
    return @($concurrency)
}

function Find-AgentJar {
    $jar = Get-ChildItem (Join-Path $ProjectRoot "agent-bootstrap\target") -Filter "java-rust-glowroot-agent-*.jar" -File |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if ($null -eq $jar) { throw "Agent JAR was not built." }
    return $jar.FullName
}

function Find-SingleArtifact([string] $Directory, [string] $Pattern) {
    $artifacts = @(Get-ChildItem -LiteralPath $Directory -Filter $Pattern -File)
    if ($artifacts.Count -ne 1) {
        throw "Expected exactly one artifact matching $Pattern under $Directory; found $($artifacts.Count)."
    }
    return $artifacts[0].FullName
}

function Prepare-Build {
    if (-not $SkipBuild) {
        $windowsSemeru = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
        if ($IsWindows -and (Test-Path -LiteralPath $windowsSemeru -PathType Container)) {
            $env:JAVA_HOME = $windowsSemeru
        }
        if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path -LiteralPath $env:JAVA_HOME -PathType Container)) {
            throw "JAVA_HOME must point to an existing Java 21 installation."
        }
        $javaBin = Join-Path $env:JAVA_HOME "bin"
        $env:Path = "$javaBin$([IO.Path]::PathSeparator)$env:Path"
        Invoke-Checked mvn @("-B", "-ntp", "clean", "install") $ProjectRoot | Out-Null
        if ($IsRustJavaRest) {
            if (-not (Test-Path -LiteralPath (Join-Path $FrameworkRoot "pom.xml") -PathType Leaf)) {
                throw "Rust-Java REST checkout is missing: $FrameworkRoot"
            }
            [xml] $frameworkPom = Get-Content -Raw -LiteralPath (Join-Path $FrameworkRoot "pom.xml")
            $frameworkVersion = "$($frameworkPom.project.version)"
            if ($frameworkVersion -ne $RequiredRestVersion) {
                throw "Rust-Java REST version mismatch: expected $RequiredRestVersion, found $frameworkVersion."
            }
            $bridgeSource = Get-Content -Raw -LiteralPath `
                    (Join-Path $FrameworkRoot "src\main\java\com\reactor\rust\bridge\NativeBridge.java")
            if ($bridgeSource -notmatch "EXPECTED_NATIVE_ABI_VERSION\s*=\s*$RequiredRestNativeAbi\s*;") {
                throw "Rust-Java REST does not require native ABI $RequiredRestNativeAbi."
            }
            $frameworkProvenance = Get-Content -Raw -LiteralPath `
                    (Join-Path $FrameworkRoot "src\main\resources\native\native-provenance.properties")
            if ($frameworkProvenance -notmatch "(?m)^rest\.abi=$RequiredRestNativeAbi\r?$") {
                throw "Rust-Java REST packaged native provenance is not ABI $RequiredRestNativeAbi."
            }
            Invoke-Checked mvn @("-B", "-ntp", "-DskipTests", "clean", "package") $FrameworkRoot | Out-Null
        } else {
            Invoke-Checked mvn @("-B", "-ntp", "clean", "package") $SpringRoot | Out-Null
        }
        $glowrootSource = [IO.Path]::GetFullPath((Join-Path $ProjectRoot "..\glowroot"))
        $workflowGlowrootSource = Join-Path $ProjectRoot "glowroot-upstream"
        if (-not (Test-Path -LiteralPath (Join-Path $glowrootSource "wire-api\src\main\protobuf") -PathType Container)) {
            $glowrootSource = $workflowGlowrootSource
        }
        if (-not (Test-Path -LiteralPath (Join-Path $glowrootSource "wire-api\src\main\protobuf") -PathType Container)) {
            throw "Glowroot wire-api source is missing. Checkout revision 622dc6f800228cccc6fa37b0ed9e779446d7c41e."
        }
        Invoke-Checked mvn @(
            "-B", "-ntp", "clean", "package",
            "-Dglowroot.source.dir=$glowrootSource"
        ) (Join-Path $ScriptDir "mock-collector") | Out-Null
    }
    if (Test-Path $Context) { Remove-Item -LiteralPath $Context -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $Context | Out-Null
    if ($IsRustJavaRest) {
        Copy-Item (Find-SingleArtifact (Join-Path $FrameworkRoot "target") "*-core-runtime.jar") `
                (Join-Path $Context "framework.jar")
        Copy-Item (Find-SingleArtifact (Join-Path $FrameworkRoot "target") "*-codegen.jar") `
                (Join-Path $Context "codegen.jar")
        Copy-Item (Find-AgentJar) (Join-Path $Context "agent.jar")
        Copy-Item (Join-Path $FrameworkRoot "benchmark\minimal-production\src") `
                (Join-Path $Context "src") -Recurse
        Invoke-Checked docker @("build", "-f", "app.Dockerfile", "-t", $AppImage, ".") `
                $ScriptDir | Out-Null
        Invoke-Checked docker @("build", "-f", "Dockerfile.benchmark", "-t", `
                "reactor-benchmark-runner:local", ".") (Join-Path $FrameworkRoot "benchmark") | Out-Null
    } else {
        Copy-Item (Join-Path $SpringRoot "target\spring-glowroot-benchmark-1.0.0.jar") `
                (Join-Path $Context "application.jar")
        Copy-Item (Find-AgentJar) (Join-Path $Context "agent.jar")
        Invoke-Checked docker @("build", "-f", "spring.Dockerfile", "-t", $AppImage, ".") `
                $ScriptDir | Out-Null
    }
    Invoke-Checked docker @("build", "-t", $CollectorImage, ".") (Join-Path $ScriptDir "mock-collector") | Out-Null
}

function Ensure-Network {
    if (-not (& docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $Network })) {
        Invoke-Checked docker @("network", "create", $Network) $ProjectRoot | Out-Null
    }
}

function Start-LoadRunner {
    Remove-Container $LoadRunner
    $args = @(
        "run", "-d", "--name", $LoadRunner, "--network", $Network,
        "--entrypoint", "sh"
    )
    if ($RunnerCpuSet) { $args += @("--cpuset-cpus", $RunnerCpuSet) }
    $args += @($WrkImage, "-c", "while :; do sleep 3600; done")
    Invoke-Checked docker $args $ProjectRoot | Out-Null
    $running = (& docker inspect -f "{{.State.Running}}" $LoadRunner 2>$null) -join ""
    if ($LASTEXITCODE -ne 0 -or $running -ne "true") {
        throw "Persistent wrk load runner did not start."
    }
}

function Get-ApplicationExecutionCpuSet([string] $ReservedCpuSet) {
    if (-not $IsLinux -or -not $PinSingleCpuQuotaToOneLogicalCpu -or $CpuLimit -gt 1.0) {
        return $ReservedCpuSet
    }
    $reservedCpus = @(ConvertFrom-ReactorCpuSet -CpuSet $ReservedCpuSet)
    if ($reservedCpus.Count -eq 0) {
        throw "Application CPU reservation is empty."
    }
    # Reserve the full SMT group, but do not let a one-CPU cgroup burst across both siblings.
    return "$($reservedCpus[0])"
}

function Invoke-Curl([string] $Url, [string] $Method = "GET") {
    $args = @("run", "--rm", "--network", $Network, "--entrypoint", "curl")
    if ($RunnerCpuSet) { $args += @("--cpuset-cpus", $RunnerCpuSet) }
    $output = & docker @args $RunnerImage "-fsS" "-X" $Method $Url 2>&1
    if ($LASTEXITCODE -ne 0) { throw "HTTP probe failed for ${Url}: $($output -join "`n")" }
    return $output -join "`n"
}

function Invoke-Warmup([string] $Target, [string] $Path) {
    $args = @("exec", $LoadRunner, "wrk", "-t2", "-c32", "-d$Warmup", "http://${Target}:8080$Path")
    $output = $null
    foreach ($attempt in 1..3) {
        $output = & docker @args 2>&1
        if ($LASTEXITCODE -eq 0) { break }
        if ($attempt -eq 3) {
            throw "Warmup failed for ${Target}${Path}:`n$($output -join "`n")"
        }
        Start-Sleep -Seconds 1
    }
    $text = $output -join "`n"
    if ($text -notmatch 'Requests/sec:\s+([0-9.]+)') {
        throw "Cannot parse warmup RPS for ${Target}${Path}:`n$text"
    }
    return [double] $Matches[1]
}

function Invoke-InterleavedPreWarm([string] $Target) {
    foreach ($cycle in 1..$PreWarmCycles) {
        foreach ($endpoint in $endpoints) {
            [void](Invoke-Warmup $Target $EndpointMap[$endpoint])
        }
    }
}

function Invoke-PairedInterleavedPreWarm {
    foreach ($cycle in 1..$PreWarmCycles) {
        $endpointIndex = 0
        foreach ($endpoint in $endpoints) {
            $variants = @(Get-ReactorWarmupVariantOrder `
                    -Round $cycle `
                    -EndpointIndex $endpointIndex)
            foreach ($variant in $variants) {
                $target = if ($variant -eq "baseline") { $Baseline } else { $Candidate }
                [void](Invoke-Warmup $target $EndpointMap[$endpoint])
            }
            $endpointIndex++
        }
    }
}

function Invoke-InterleavedWarmup([string] $Target) {
    $samplesByEndpoint = @{}
    foreach ($endpoint in $endpoints) {
        $samplesByEndpoint[$endpoint] = [Collections.Generic.List[double]]::new()
    }
    foreach ($round in 1..$MaxWarmupRounds) {
        Add-InterleavedWarmupRound `
                -Target $Target `
                -SamplesByEndpoint $samplesByEndpoint
    }
    return $samplesByEndpoint
}

function Add-InterleavedWarmupRound(
        [string] $Target,
        [hashtable] $SamplesByEndpoint) {
    foreach ($endpoint in $endpoints) {
        $SamplesByEndpoint[$endpoint].Add((Invoke-Warmup $Target $EndpointMap[$endpoint]))
    }
}

function Test-WarmupStability([hashtable] $SamplesByEndpoint) {
    foreach ($endpoint in $endpoints) {
        $decision = Get-ReactorWarmupDecision `
                -Samples @($SamplesByEndpoint[$endpoint]) `
                -WindowRounds $MinWarmupRounds `
                -MaximumRobustTrendPercent $MaxWarmupRobustTrendPercent `
                -MaximumBorderlineRobustTrendPercent $MaxWarmupBorderlineRobustTrendPercent `
                -MaximumBorderlineMedianShiftPercent $MaxWarmupBorderlineMedianShiftPercent `
                -MaximumMedianAbsoluteDeviationPercent $MaxWarmupMedianAbsoluteDeviationPercent
        if (-not $decision.passed) { return $false }
    }
    return $true
}

function Invoke-PairedInterleavedWarmup {
    $samplesByVariant = @{}
    foreach ($variant in @("baseline", "candidate")) {
        $samplesByVariant[$variant] = @{}
        foreach ($endpoint in $endpoints) {
            $samplesByVariant[$variant][$endpoint] = [Collections.Generic.List[double]]::new()
        }
    }
    foreach ($round in 1..$MaxWarmupRounds) {
        Add-PairedInterleavedWarmupRound -SamplesByVariant $samplesByVariant -Round $round
    }
    return $samplesByVariant
}

function Add-PairedInterleavedWarmupRound(
        [hashtable] $SamplesByVariant,
        [int] $Round) {
    $endpointIndex = 0
    foreach ($endpoint in $endpoints) {
        $variants = @(Get-ReactorWarmupVariantOrder `
                -Round $Round `
                -EndpointIndex $endpointIndex)
        foreach ($variant in $variants) {
            $target = if ($variant -eq "baseline") { $Baseline } else { $Candidate }
            $SamplesByVariant[$variant][$endpoint].Add(
                (Invoke-Warmup $target $EndpointMap[$endpoint]))
        }
        $endpointIndex++
    }
}

function Test-PairedWarmupStability([hashtable] $SamplesByVariant) {
    foreach ($variant in @("baseline", "candidate")) {
        foreach ($endpoint in $endpoints) {
            $decision = Get-ReactorWarmupDecision `
                    -Samples @($SamplesByVariant[$variant][$endpoint]) `
                    -WindowRounds $MinWarmupRounds `
                    -MaximumRobustTrendPercent $MaxWarmupRobustTrendPercent `
                    -MaximumBorderlineRobustTrendPercent $MaxWarmupBorderlineRobustTrendPercent `
                    -MaximumBorderlineMedianShiftPercent $MaxWarmupBorderlineMedianShiftPercent `
                    -MaximumMedianAbsoluteDeviationPercent $MaxWarmupMedianAbsoluteDeviationPercent
            if (-not $decision.passed) { return $false }
        }
    }
    return $true
}

function Assert-StabilizedWarmup(
        [int] $Pair,
        [string] $Variant,
        [string] $Endpoint,
        [int] $ConfirmationRoundsUsed,
        [double[]] $Samples) {
    $expectedRounds = $MaxWarmupRounds + $ConfirmationRoundsUsed
    if ($Samples.Count -ne $expectedRounds) {
        throw "Endpoint $Endpoint has $($Samples.Count) warmup samples; expected $expectedRounds."
    }
    $firstStableRound = $null
    for ($round = 2 * $MinWarmupRounds; $round -le $expectedRounds; $round++) {
        $decision = Get-ReactorWarmupDecision `
                -Samples @($Samples | Select-Object -First $round) `
                -WindowRounds $MinWarmupRounds `
                -MaximumRobustTrendPercent $MaxWarmupRobustTrendPercent `
                -MaximumBorderlineRobustTrendPercent $MaxWarmupBorderlineRobustTrendPercent `
                -MaximumBorderlineMedianShiftPercent $MaxWarmupBorderlineMedianShiftPercent `
                -MaximumMedianAbsoluteDeviationPercent $MaxWarmupMedianAbsoluteDeviationPercent
        if ($null -eq $firstStableRound -and $decision.passed) {
            $firstStableRound = $round
        }
    }

    $finalDecision = Get-ReactorWarmupDecision `
            -Samples $Samples `
            -WindowRounds $MinWarmupRounds `
            -MaximumRobustTrendPercent $MaxWarmupRobustTrendPercent `
            -MaximumBorderlineRobustTrendPercent $MaxWarmupBorderlineRobustTrendPercent `
            -MaximumBorderlineMedianShiftPercent $MaxWarmupBorderlineMedianShiftPercent `
            -MaximumMedianAbsoluteDeviationPercent $MaxWarmupMedianAbsoluteDeviationPercent
    $warmupPassed = $finalDecision.passed
    $script:warmups.Add([pscustomobject]@{
        pair = $Pair
        variant = $Variant
        endpoint = $Endpoint
        rounds = $expectedRounds
        fixed_rounds = $MaxWarmupRounds
        confirmation_rounds = $ConfirmationRoundsUsed
        interleaved_rounds = $expectedRounds
        first_stable_round = $firstStableRound
        previous_median_rps = [math]::Round($finalDecision.previous_median_rps, 2)
        recent_median_rps = [math]::Round($finalDecision.recent_median_rps, 2)
        median_shift_pct = [math]::Round($finalDecision.median_shift_pct, 3)
        theil_sen_slope_rps_per_round = [math]::Round($finalDecision.theil_sen_slope_rps_per_round, 3)
        robust_trend_pct = [math]::Round($finalDecision.robust_trend_pct, 3)
        trend_gate_mode = $finalDecision.trend_gate_mode
        median_absolute_deviation_pct = [math]::Round($finalDecision.median_absolute_deviation_pct, 3)
        range_spread_pct = [math]::Round($finalDecision.range_spread_pct, 3)
        rps_samples = @($Samples)
        gate = if ($warmupPassed) { "PASS" } else { "FAIL" }
    })
    if (-not $warmupPassed -and -not $DevelopmentQuickMode) {
        $sampleText = @($Samples | ForEach-Object {
            $_.ToString("F2", [Globalization.CultureInfo]::InvariantCulture)
        }) -join ", "
        throw ("Warmup did not stabilize for pair=$Pair variant=$Variant endpoint=$Endpoint " +
                "after $MaxWarmupRounds fixed rounds and $ConfirmationRoundsUsed bounded " +
                "confirmation rounds. Robust trend=" +
                "$([math]::Round($finalDecision.robust_trend_pct,3))%; MAD=" +
                "$([math]::Round($finalDecision.median_absolute_deviation_pct,3))%. " +
                "RPS samples: $sampleText.")
    }
}

function Wait-Http([string] $Container) {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-Curl "http://${Container}:8080/health" | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw "$Container did not become ready.`n$((& docker logs $Container 2>&1) -join "`n")"
}

function Start-Collector {
    Remove-Container $Collector
    $args = @("run", "-d", "--name", $Collector, "--network", $Network, "--cpus", "0.5", "--memory", "128m")
    if ($CollectorCpuSet) { $args += @("--cpuset-cpus", $CollectorCpuSet) }
    $args += $CollectorImage
    Invoke-Checked docker $args $ProjectRoot | Out-Null
}

function Start-App([string] $Name, [string] $CpuSet, [bool] $Enabled, [int] $Pair) {
    Remove-Container $Name
    $agentIdPrefix = if ($IsRustJavaRest) { "rust-java-rest-benchmark" } else { "spring-benchmark" }
    $applicationName = if ($IsRustJavaRest) { "rust-java-rest-benchmark" } else { "spring-glowroot-benchmark" }
    $collectorAddress = if ($IsRustJavaRest) { "${Collector}:8181" } else { "http://${Collector}:8181" }
    $telemetry = if ($Enabled) {
        if ($UseJavaAgentBootstrap) {
            $springArgument = if ($IsRustJavaRest) { "" } else { ",spring-enabled=true" }
            "-javaagent:/app/agent.jar=collector=${Collector}:8181,agent-id=${agentIdPrefix}::pair-${Pair}," +
            "application=${applicationName},http-sample-rate=256,trace-capacity=0," +
            "max-routes=64,max-export-bytes=65536${springArgument}"
        } else {
            $springProperty = if ($IsRustJavaRest) { "" } else { " -Dreactor.glowroot.spring.enabled=true" }
            "-Dreactor.glowroot.enabled=true " +
            "-Dreactor.glowroot.collector.address=${collectorAddress} " +
            "-Dreactor.glowroot.agent.id=${agentIdPrefix}::pair-${Pair} " +
            "-Dreactor.glowroot.application.name=${applicationName} " +
            "-Dreactor.glowroot.http.sample-rate=256 " +
            "-Dreactor.glowroot.trace.capacity=0 " +
            "-Dreactor.glowroot.max-routes=64 " +
            "-Dreactor.glowroot.max-export-bytes=65536" + $springProperty
        }
    } else { "" }
    $telemetryEnvironment = if ($IsRustJavaRest) { "JAVA_AGENT_OPTS" } else { "TELEMETRY_OPTS" }
    $args = @(
        "run", "-d", "--name", $Name, "--network", $Network,
        "--cpus", "$CpuLimit", "--memory", $MemoryLimit,
        "-e", "${telemetryEnvironment}=$telemetry"
    )
    $executionCpuSet = Get-ApplicationExecutionCpuSet -ReservedCpuSet $CpuSet
    if ($executionCpuSet) { $args += @("--cpuset-cpus", $executionCpuSet) }
    $args += $AppImage
    $started = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Checked docker $args $ProjectRoot | Out-Null
    $ContainerCpuSets[$Name] = $executionCpuSet
    Write-Host "Application $Name reserved CPU group $CpuSet and executes on CPU set $executionCpuSet."
    Wait-Http $Name
    $started.Stop()
    return $started.Elapsed.TotalMilliseconds
}

function Assert-NativeTelemetryLoaded([string] $Name) {
    $libraryPattern = if ($IsRustJavaRest) { "rust_hyper" } else { "rust_glowroot_agent" }
    & docker exec $Name sh -c "grep -q $libraryPattern /proc/1/maps"
    if ($LASTEXITCODE -ne 0) {
        throw "$ApplicationKind did not load the expected native telemetry library."
    }
}

function Invoke-RouteSmoke([string] $Name) {
    foreach ($path in @($EndpointMap.Values | Sort-Object -Unique)) {
        Invoke-Curl "http://${Name}:8080$path" | Out-Null
    }
}

function Convert-LatencyToMs([double] $Value, [string] $Unit) {
    switch ($Unit) {
        "us" { return $Value / 1000.0 }
        "ms" { return $Value }
        "s" { return $Value * 1000.0 }
        default { throw "Unknown wrk latency unit: $Unit" }
    }
}

function Get-ContainerMetrics([string] $Name) {
    $raw = (& docker exec $Name sh -c 'awk "/VmRSS:/{rss=\$2}/Threads:/{threads=\$2} END{print rss,threads}" /proc/1/status; cat /sys/fs/cgroup/memory.current' 2>&1)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -lt 2) { throw "Cannot read metrics from $Name" }
    $process = "$($raw[0])" -split "\s+"
    return [pscustomobject]@{
        process_rss_mib = [math]::Round(([double]$process[0] / 1024.0), 3)
        threads = [int]$process[1]
        container_mib = [math]::Round(([double]$raw[1] / 1MB), 3)
    }
}

function Get-StableContainerMetrics([string] $Name, [int] $SampleCount = 5) {
    if ($SampleCount -lt 3) { throw "Stable memory measurement requires at least three samples." }
    $samples = foreach ($sample in 1..$SampleCount) {
        Get-ContainerMetrics $Name
        if ($sample -lt $SampleCount) { Start-Sleep -Seconds 1 }
    }
    return [pscustomobject]@{
        process_rss_mib = Median -Values @($samples.process_rss_mib)
        container_mib = Median -Values @($samples.container_mib)
        threads = [int](($samples.threads | Measure-Object -Maximum).Maximum)
    }
}

function Prepare-SteadyMemoryMeasurement([string] $Name) {
    if (-not $IsRustJavaRest) {
        Invoke-Curl "http://${Name}:8080/internal/benchmark/full-gc" "POST" | Out-Null
    }
    Start-Sleep -Seconds 5
}

function Invoke-Wrk([string] $Target, [string] $Path, [int] $Concurrency, [int] $Sequence) {
    $resultFile = "/tmp/reactor-wrk-$Sequence.out"
    $exitFile = "/tmp/reactor-wrk-$Sequence.exit"
    $targetUrl = "http://${Target}:8080$Path"
    & docker exec $LoadRunner sh -c "rm -f '$resultFile' '$exitFile'" *> $null
    if ($LASTEXITCODE -ne 0) { throw "Cannot prepare persistent wrk load runner." }
    $hostBefore = if ($IsLinux) { Get-ReactorLinuxCpuSnapshot } else { $null }
    $targetCpuSet = if ($ContainerCpuSets.ContainsKey($Target)) {
        "$($ContainerCpuSets[$Target])"
    } else {
        $SlotACpuSet
    }
    $applicationCpus = if ($IsLinux) { @(ConvertFrom-ReactorCpuSet -CpuSet $targetCpuSet) } else { @() }
    $physicalCpus = if ($IsLinux) { @(Get-ReactorLinuxPhysicalCpuSet -CpuSet $targetCpuSet) } else { @() }
    $siblingCpus = if ($IsLinux) {
        @($physicalCpus | Where-Object { $applicationCpus -notcontains $_ })
    } else { @() }
    $wrkCommand = "wrk -t2 -c$Concurrency -d$Duration --latency '$targetUrl' " +
            "> '$resultFile' 2>&1; printf '%s' `$? > '$exitFile'"
    & docker exec -d $LoadRunner sh -c $wrkCommand
    if ($LASTEXITCODE -ne 0) { throw "Cannot start wrk measurement for ${Target}${Path}." }
    $maxRss = 0.0
    $maxContainer = 0.0
    $maxThreads = 0
    $deadline = (Get-Date).AddMinutes(2)
    while ($true) {
        $sample = Get-ContainerMetrics $Target
        $maxRss = [math]::Max($maxRss, $sample.process_rss_mib)
        $maxContainer = [math]::Max($maxContainer, $sample.container_mib)
        $maxThreads = [math]::Max($maxThreads, $sample.threads)
        & docker exec $LoadRunner sh -c "test -f '$exitFile'" *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ((Get-Date) -ge $deadline) {
            throw "wrk measurement timed out for ${Target}${Path}."
        }
        Start-Sleep -Milliseconds 250
    }
    $hostAfter = if ($IsLinux) { Get-ReactorLinuxCpuSnapshot } else { $null }
    $hostStealPercent = if ($IsLinux) {
        (Get-ReactorLinuxCpuWindow -Before $hostBefore -After $hostAfter -Cpus $applicationCpus).steal_percent
    } else { 0.0 }
    $hostSiblingBusyPercent = if ($siblingCpus.Count -gt 0) {
        (Get-ReactorLinuxCpuWindow -Before $hostBefore -After $hostAfter -Cpus $siblingCpus).busy_percent
    } else { 0.0 }
    $exitCode = ((& docker exec $LoadRunner cat $exitFile 2>&1) -join "").Trim()
    $output = (& docker exec $LoadRunner cat $resultFile 2>&1) -join "`n"
    & docker exec $LoadRunner sh -c "rm -f '$resultFile' '$exitFile'" *> $null
    if ($exitCode -ne "0") { throw "wrk failed with exit code $exitCode for ${Target}${Path}:`n$output" }
    if ($output -notmatch 'Requests/sec:\s+([0-9.]+)') { throw "Cannot parse wrk RPS:`n$output" }
    $rps = [double]$Matches[1]
    if ($output -notmatch '(?m)^\s*([0-9]+) requests in ') { throw "Cannot parse wrk request count:`n$output" }
    $requests = [int64]$Matches[1]
    if ($output -notmatch '(?m)^\s*99%\s+([0-9.]+)(us|ms|s)') { throw "Cannot parse wrk p99:`n$output" }
    $p99 = Convert-LatencyToMs ([double]$Matches[1]) $Matches[2]
    $non2xx = if ($output -match 'Non-2xx or 3xx responses:\s+([0-9]+)') { [int64]$Matches[1] } else { 0 }
    $non2xxRate = if ($requests -eq 0) { 100.0 } else { 100.0 * $non2xx / $requests }
    if ($maxRss -eq 0) {
        $sample = Get-ContainerMetrics $Target
        $maxRss = $sample.process_rss_mib
        $maxContainer = $sample.container_mib
        $maxThreads = $sample.threads
    }
    return [pscustomobject]@{
        rps = [math]::Round($rps, 2)
        useful_rps = [math]::Round($rps * (1.0 - ($non2xxRate / 100.0)), 2)
        p99_ms = [math]::Round($p99, 3)
        requests = $requests
        non_2xx = $non2xx
        non_2xx_pct = [math]::Round($non2xxRate, 6)
        process_rss_mib = $maxRss
        container_mib = $maxContainer
        threads = $maxThreads
        host_sibling_busy_percent = [math]::Round($hostSiblingBusyPercent, 3)
        host_steal_percent = [math]::Round($hostStealPercent, 3)
    }
}

function Median([double[]] $Values) {
    if ($Values.Count -eq 0) { throw "Median requires at least one value." }
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count % 2 -eq 1) {
        return [double]$sorted[[int][math]::Floor($sorted.Count / 2.0)]
    }
    $right = [int]($sorted.Count / 2)
    return ([double]$sorted[$right - 1] + [double]$sorted[$right]) / 2.0
}

if ((Median -Values @(3.0, 1.0, 2.0)) -ne 2.0 -or
        (Median -Values @(4.0, 1.0, 3.0, 2.0)) -ne 2.5) {
    throw "Median self-check failed."
}

function Test-EarlyReleaseDecision([int] $CompletedPairs) {
    if ($RequireAllPairs -or $CompletedPairs -lt $MinimumPairRepeats `
            -or $CompletedPairs -ge $PairRepeats) {
        return $false
    }

    $earlyRpsThreshold = $MinUsefulRpsDeltaPercent + 0.5
    $earlyP99Threshold = $MaxP99RegressionPercent - 2.0
    foreach ($endpoint in $endpoints) {
        foreach ($value in @(Get-EndpointConcurrency $endpoint)) {
            $base = @($records | Where-Object {
                    $_.endpoint -eq $endpoint -and $_.concurrency -eq $value `
                    -and $_.variant -eq "baseline"
                })
            $agent = @($records | Where-Object {
                    $_.endpoint -eq $endpoint -and $_.concurrency -eq $value `
                    -and $_.variant -eq "candidate"
                })
            if ($base.Count -ne $CompletedPairs -or $agent.Count -ne $CompletedPairs) {
                return $false
            }
            $pairedRpsDeltas = [Collections.Generic.List[double]]::new()
            $pairedP99Deltas = [Collections.Generic.List[double]]::new()
            $pairedThreadDeltas = [Collections.Generic.List[int]]::new()
            $pairedHostSiblingMaxima = [Collections.Generic.List[double]]::new()
            $maximumHostSteal = 0.0
            foreach ($pair in 1..$CompletedPairs) {
                $pairedBase = $base | Where-Object pair -eq $pair | Select-Object -First 1
                $pairedAgent = $agent | Where-Object pair -eq $pair | Select-Object -First 1
                $pairedRpsDeltas.Add(
                    100.0 * ($pairedAgent.useful_rps - $pairedBase.useful_rps) / $pairedBase.useful_rps)
                $pairedP99Deltas.Add(
                    100.0 * ($pairedAgent.p99_ms - $pairedBase.p99_ms) / $pairedBase.p99_ms)
                $pairedThreadDeltas.Add($pairedAgent.threads - $pairedBase.threads)
                $pairedHostSiblingMaxima.Add([math]::Max(
                        $pairedBase.host_sibling_busy_percent,
                        $pairedAgent.host_sibling_busy_percent
                ))
                $maximumHostSteal = [math]::Max(
                        $maximumHostSteal,
                        [math]::Max($pairedBase.host_steal_percent, $pairedAgent.host_steal_percent)
                )
            }
            if ((Median -Values @($pairedRpsDeltas)) -lt $earlyRpsThreshold `
                    -or (Median -Values @($pairedP99Deltas)) -gt $earlyP99Threshold `
                    -or ($pairedThreadDeltas | Measure-Object -Maximum).Maximum -gt $AllowedThreadDelta `
                    -or (Median -Values @($pairedHostSiblingMaxima)) -gt $MaxHostSiblingCpuPercent `
                    -or $maximumHostSteal -gt $MaxHostStealCpuPercent) {
                return $false
            }
            $non2xxDeltaThreshold = if ($IsRustJavaRest -and $endpoint -eq "heavy-json" `
                    -and $value -ge 256) {
                $MaxSaturatedNon2xxDeltaPercentagePoints
            } else {
                $MaxNon2xxDeltaPercentagePoints
            }
            $errorDecision = Get-ReactorNon2xxDecision `
                    -Baseline $base `
                    -Candidate $agent `
                    -MaximumDeltaPercentagePoints $non2xxDeltaThreshold `
                    -MaximumAbsolutePercent $MaxAbsoluteNon2xxPercent
            if (-not $errorDecision.passed) { return $false }
        }
    }

    $startupDeltas = [Collections.Generic.List[double]]::new()
    $rssDeltas = [Collections.Generic.List[double]]::new()
    $containerDeltas = [Collections.Generic.List[double]]::new()
    $steadyThreadDeltas = [Collections.Generic.List[int]]::new()
    foreach ($pair in 1..$CompletedPairs) {
        $baseStartup = $startups | Where-Object { $_.pair -eq $pair -and $_.variant -eq "baseline" } |
                Select-Object -First 1
        $agentStartup = $startups | Where-Object { $_.pair -eq $pair -and $_.variant -eq "candidate" } |
                Select-Object -First 1
        $startupDeltas.Add(100.0 * ($agentStartup.ms - $baseStartup.ms) / $baseStartup.ms)
        $baseMemory = $steadyMemory | Where-Object {
                $_.pair -eq $pair -and $_.variant -eq "baseline"
            } | Select-Object -First 1
        $agentMemory = $steadyMemory | Where-Object {
                $_.pair -eq $pair -and $_.variant -eq "candidate"
            } | Select-Object -First 1
        $rssDeltas.Add($agentMemory.process_rss_mib - $baseMemory.process_rss_mib)
        $containerDeltas.Add($agentMemory.container_mib - $baseMemory.container_mib)
        $steadyThreadDeltas.Add($agentMemory.threads - $baseMemory.threads)
    }
    if ((Median -Values @($startupDeltas)) -gt 8.0 `
            -or (Median -Values @($rssDeltas)) -gt ($MaxMemoryRegressionMiB - 0.5) `
            -or (Median -Values @($containerDeltas)) -gt ($MaxMemoryRegressionMiB - 0.5) `
            -or ($steadyThreadDeltas | Measure-Object -Maximum).Maximum -gt $AllowedThreadDelta) {
        return $false
    }

    Write-Host "Strict early-pass envelope satisfied after $CompletedPairs independent pairs."
    return $true
}

Prepare-Build
Ensure-Network
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$selectedRoles = $null
if ($AutoSelectCpuRoles) {
    $selectedRoles = Select-ReactorBenchmarkCpuRoles
    $SlotACpuSet = $selectedRoles.application
    $SlotBCpuSet = $selectedRoles.application
    $RunnerCpuSet = $selectedRoles.runner
    $CollectorCpuSet = $selectedRoles.collector
    $OrchestratorCpuSet = $selectedRoles.orchestrator
    $SequentialVariants = $true
}
Set-ReactorCurrentProcessCpuAffinity -CpuSet $OrchestratorCpuSet
if (-not $SkipHostPreflight) {
    if ($HostStabilizationSeconds -gt 0) {
        Write-Host "Waiting $HostStabilizationSeconds seconds after build before host preflight."
        Start-Sleep -Seconds $HostStabilizationSeconds
    }
    $hostReadiness = Assert-ReactorHostBenchmarkReadiness `
            -MaxAverageCpuPercent $MaxHostCpuAveragePercent `
            -MaxPeakCpuPercent $MaxHostCpuPeakPercent `
            -MaxStealCpuPercent $MaxHostStealCpuPercent `
            -MinFreeVirtualMiB 3072 `
            -CpuSet $SlotACpuSet
} else {
    $hostReadiness = $null
}
Assert-ReactorBenchmarkCpuIsolation -RunnerImage $RunnerImage `
        -SlotACpuSet $SlotACpuSet `
        -SlotBCpuSet $(if ($SequentialVariants) { $SlotACpuSet } else { $SlotBCpuSet }) `
        -RunnerCpuSet $RunnerCpuSet `
        -CollectorCpuSet $CollectorCpuSet `
        -OrchestratorCpuSet $OrchestratorCpuSet `
        -AllowSharedApplicationSlots:$SequentialVariants `
        -AllowRunnerCollectorSiblingSharing:$AllowRunnerCollectorSiblingSharing
Start-Collector
Start-LoadRunner
$records = [Collections.Generic.List[object]]::new()
$startups = [Collections.Generic.List[object]]::new()
$steadyMemory = [Collections.Generic.List[object]]::new()
$warmups = [Collections.Generic.List[object]]::new()
$sequence = 0
$completedPairs = 0
$stoppedAfterStrictEarlyPass = $false
try {
    for ($pair = 1; $pair -le $PairRepeats; $pair++) {
        $cells = foreach ($endpoint in $endpoints) {
            foreach ($value in @(Get-EndpointConcurrency $endpoint)) {
                [pscustomobject]@{ endpoint = $endpoint; concurrency = $value }
            }
        }
        $cells = @($cells | Sort-Object { Get-Random })

        if ($SequentialVariants) {
            $variants = if ($pair % 2 -eq 1) { @("baseline", "candidate") } else { @("candidate", "baseline") }
            foreach ($variant in $variants) {
                $enabled = $variant -eq "candidate"
                $name = if ($enabled) { $Candidate } else { $Baseline }
                $startups.Add([pscustomobject]@{
                    pair = $pair
                    variant = $variant
                    ms = (Start-App $name $SlotACpuSet $enabled $pair)
                })
                if ($enabled) {
                    Assert-NativeTelemetryLoaded $name
                }
                Invoke-RouteSmoke $name
                Invoke-InterleavedPreWarm $name
                $warmupSamples = Invoke-InterleavedWarmup $name
                $confirmationRoundsUsed = 0
                while (-not (Test-WarmupStability -SamplesByEndpoint $warmupSamples) `
                        -and $confirmationRoundsUsed -lt $MaxWarmupConfirmationRounds) {
                    $confirmationRoundsUsed++
                    Add-InterleavedWarmupRound `
                            -Target $name `
                            -SamplesByEndpoint $warmupSamples
                }
                foreach ($endpoint in $endpoints) {
                    Assert-StabilizedWarmup `
                            -Pair $pair `
                            -Variant $variant `
                            -Endpoint $endpoint `
                            -ConfirmationRoundsUsed $confirmationRoundsUsed `
                            -Samples @($warmupSamples[$endpoint])
                }
                foreach ($cell in $cells) {
                    $sequence++
                    $metric = Invoke-Wrk $name $EndpointMap[$cell.endpoint] $cell.concurrency $sequence
                    $records.Add([pscustomobject]@{
                        pair = $pair
                        endpoint = $cell.endpoint
                        concurrency = $cell.concurrency
                        variant = $variant
                        rps = $metric.rps
                        useful_rps = $metric.useful_rps
                        p99_ms = $metric.p99_ms
                        requests = $metric.requests
                        non_2xx = $metric.non_2xx
                        non_2xx_pct = $metric.non_2xx_pct
                        process_rss_mib = $metric.process_rss_mib
                        container_mib = $metric.container_mib
                        threads = $metric.threads
                        host_sibling_busy_percent = $metric.host_sibling_busy_percent
                        host_steal_percent = $metric.host_steal_percent
                    })
                    Start-Sleep -Seconds 2
                }
                Prepare-SteadyMemoryMeasurement $name
                $stable = Get-StableContainerMetrics $name
                $steadyMemory.Add([pscustomobject]@{
                    pair = $pair
                    variant = $variant
                    process_rss_mib = $stable.process_rss_mib
                    container_mib = $stable.container_mib
                    threads = $stable.threads
                })
                Remove-Container $name
                Start-Sleep -Seconds 5
            }
            $completedPairs = $pair
            if (Test-EarlyReleaseDecision -CompletedPairs $completedPairs) {
                $stoppedAfterStrictEarlyPass = $true
                break
            }
            continue
        }

        $baselineCpu = if ($pair % 2 -eq 1) { $SlotACpuSet } else { $SlotBCpuSet }
        $candidateCpu = if ($pair % 2 -eq 1) { $SlotBCpuSet } else { $SlotACpuSet }
        if ($pair % 2 -eq 1) {
            $startups.Add([pscustomobject]@{ pair = $pair; variant = "baseline"; ms = (Start-App $Baseline $baselineCpu $false $pair) })
            $startups.Add([pscustomobject]@{ pair = $pair; variant = "candidate"; ms = (Start-App $Candidate $candidateCpu $true $pair) })
        } else {
            $startups.Add([pscustomobject]@{ pair = $pair; variant = "candidate"; ms = (Start-App $Candidate $candidateCpu $true $pair) })
            $startups.Add([pscustomobject]@{ pair = $pair; variant = "baseline"; ms = (Start-App $Baseline $baselineCpu $false $pair) })
        }
        Assert-NativeTelemetryLoaded $Candidate
        foreach ($variant in @("baseline", "candidate")) {
            $name = if ($variant -eq "baseline") { $Baseline } else { $Candidate }
            Invoke-RouteSmoke $name
        }
        Invoke-PairedInterleavedPreWarm
        $warmupSamples = Invoke-PairedInterleavedWarmup
        $confirmationRoundsUsed = 0
        while (-not (Test-PairedWarmupStability -SamplesByVariant $warmupSamples) `
                -and $confirmationRoundsUsed -lt $MaxWarmupConfirmationRounds) {
            $confirmationRoundsUsed++
            Add-PairedInterleavedWarmupRound `
                    -SamplesByVariant $warmupSamples `
                    -Round ($MaxWarmupRounds + $confirmationRoundsUsed)
        }
        foreach ($variant in @("baseline", "candidate")) {
            foreach ($endpoint in $endpoints) {
                Assert-StabilizedWarmup `
                        -Pair $pair `
                        -Variant $variant `
                        -Endpoint $endpoint `
                        -ConfirmationRoundsUsed $confirmationRoundsUsed `
                        -Samples @($warmupSamples[$variant][$endpoint])
            }
        }

        foreach ($cell in $cells) {
            $variants = if ((Get-Random -Minimum 0 -Maximum 2) -eq 0) {
                @("baseline", "candidate")
            } else { @("candidate", "baseline") }
            foreach ($variant in $variants) {
                $sequence++
                $target = if ($variant -eq "baseline") { $Baseline } else { $Candidate }
                $metric = Invoke-Wrk $target $EndpointMap[$cell.endpoint] $cell.concurrency $sequence
                $records.Add([pscustomobject]@{
                    pair = $pair
                    endpoint = $cell.endpoint
                    concurrency = $cell.concurrency
                    variant = $variant
                    rps = $metric.rps
                    useful_rps = $metric.useful_rps
                    p99_ms = $metric.p99_ms
                    requests = $metric.requests
                    non_2xx = $metric.non_2xx
                    non_2xx_pct = $metric.non_2xx_pct
                    process_rss_mib = $metric.process_rss_mib
                    container_mib = $metric.container_mib
                    threads = $metric.threads
                    host_sibling_busy_percent = $metric.host_sibling_busy_percent
                    host_steal_percent = $metric.host_steal_percent
                })
                Start-Sleep -Seconds 2
            }
        }
        if (-not $IsRustJavaRest) {
            Invoke-Curl "http://${Baseline}:8080/internal/benchmark/full-gc" "POST" | Out-Null
            Invoke-Curl "http://${Candidate}:8080/internal/benchmark/full-gc" "POST" | Out-Null
        }
        Start-Sleep -Seconds 5
        $memoryVariants = if ($pair % 2 -eq 1) {
            @("baseline", "candidate")
        } else {
            @("candidate", "baseline")
        }
        foreach ($variant in $memoryVariants) {
            $target = if ($variant -eq "baseline") { $Baseline } else { $Candidate }
            $stable = Get-StableContainerMetrics $target
            $steadyMemory.Add([pscustomobject]@{
                pair = $pair
                variant = $variant
                process_rss_mib = $stable.process_rss_mib
                container_mib = $stable.container_mib
                threads = $stable.threads
            })
        }
        Remove-Container $Baseline
        Remove-Container $Candidate
        Start-Sleep -Seconds 5
        $completedPairs = $pair
        if (Test-EarlyReleaseDecision -CompletedPairs $completedPairs) {
            $stoppedAfterStrictEarlyPass = $true
            break
        }
    }
} catch {
    ConvertTo-Json -InputObject @($records) -Depth 6 | Set-Content `
            (Join-Path $Results "raw-partial.json") -Encoding utf8
    ConvertTo-Json -InputObject @($steadyMemory) -Depth 6 | Set-Content `
            (Join-Path $Results "steady-memory-partial.json") -Encoding utf8
    ConvertTo-Json -InputObject @($warmups) -Depth 6 | Set-Content `
            (Join-Path $Results "warmup.json") -Encoding utf8
    [ordered]@{
        passed = $false
        application_kind = $ApplicationKind
        stage = "matrix"
        error = $_.Exception.Message
    } | ConvertTo-Json -Depth 4 | Set-Content `
            (Join-Path $Results "failure.json") -Encoding utf8
    throw
} finally {
    foreach ($name in @($Baseline, $Candidate, $Collector, $LoadRunner)) { Remove-Container $name }
}

$summary = [Collections.Generic.List[object]]::new()
foreach ($endpoint in $endpoints) {
    foreach ($value in @(Get-EndpointConcurrency $endpoint)) {
        $base = @($records | Where-Object { $_.endpoint -eq $endpoint -and $_.concurrency -eq $value -and $_.variant -eq "baseline" })
        $agent = @($records | Where-Object { $_.endpoint -eq $endpoint -and $_.concurrency -eq $value -and $_.variant -eq "candidate" })
        $baseRps = Median -Values @($base.useful_rps)
        $agentRps = Median -Values @($agent.useful_rps)
        $baseP99 = Median -Values @($base.p99_ms)
        $agentP99 = Median -Values @($agent.p99_ms)
        $baseRss = Median -Values @($base.process_rss_mib)
        $agentRss = Median -Values @($agent.process_rss_mib)
        $baseContainer = Median -Values @($base.container_mib)
        $agentContainer = Median -Values @($agent.container_mib)
        $pairedRpsDeltas = [Collections.Generic.List[double]]::new()
        $pairedP99Deltas = [Collections.Generic.List[double]]::new()
        $pairedRssDeltas = [Collections.Generic.List[double]]::new()
        $pairedContainerDeltas = [Collections.Generic.List[double]]::new()
        $pairedThreadDeltas = [Collections.Generic.List[int]]::new()
        foreach ($pair in 1..$completedPairs) {
            $pairedBase = $base | Where-Object pair -eq $pair | Select-Object -First 1
            $pairedAgent = $agent | Where-Object pair -eq $pair | Select-Object -First 1
            $pairedRpsDeltas.Add(100.0 * ($pairedAgent.useful_rps - $pairedBase.useful_rps) / $pairedBase.useful_rps)
            $pairedP99Deltas.Add(100.0 * ($pairedAgent.p99_ms - $pairedBase.p99_ms) / $pairedBase.p99_ms)
            $pairedRssDeltas.Add($pairedAgent.process_rss_mib - $pairedBase.process_rss_mib)
            $pairedContainerDeltas.Add($pairedAgent.container_mib - $pairedBase.container_mib)
            $pairedThreadDeltas.Add($pairedAgent.threads - $pairedBase.threads)
        }
        $rpsDelta = Median -Values @($pairedRpsDeltas)
        $p99Delta = Median -Values @($pairedP99Deltas)
        $rssDelta = Median -Values @($pairedRssDeltas)
        $containerDelta = Median -Values @($pairedContainerDeltas)
        $threadDelta = Median -Values @($pairedThreadDeltas)
        $maxRssDelta = ($pairedRssDeltas | Measure-Object -Maximum).Maximum
        $maxContainerDelta = ($pairedContainerDeltas | Measure-Object -Maximum).Maximum
        $observedMaxThreadDelta = ($pairedThreadDeltas | Measure-Object -Maximum).Maximum
        $non2xxDeltaThreshold = if ($IsRustJavaRest -and $endpoint -eq "heavy-json" -and $value -ge 256) {
            $MaxSaturatedNon2xxDeltaPercentagePoints
        } else {
            $MaxNon2xxDeltaPercentagePoints
        }
        $errorDecision = Get-ReactorNon2xxDecision `
                -Baseline $base `
                -Candidate $agent `
                -MaximumDeltaPercentagePoints $non2xxDeltaThreshold `
                -MaximumAbsolutePercent $MaxAbsoluteNon2xxPercent
        $pairedHostSiblingMaxima = [Collections.Generic.List[double]]::new()
        foreach ($pair in 1..$completedPairs) {
            $pairedBase = $base | Where-Object pair -eq $pair | Select-Object -First 1
            $pairedAgent = $agent | Where-Object pair -eq $pair | Select-Object -First 1
            $pairedHostSiblingMaxima.Add([math]::Max(
                    $pairedBase.host_sibling_busy_percent,
                    $pairedAgent.host_sibling_busy_percent
            ))
        }
        $hostSiblingBusy = Median -Values @($pairedHostSiblingMaxima)
        $maxHostSiblingBusy = ($pairedHostSiblingMaxima | Measure-Object -Maximum).Maximum
        $maxHostSteal = (@($base.host_steal_percent) + @($agent.host_steal_percent) |
                Measure-Object -Maximum).Maximum
        $passed = $rpsDelta -ge $MinUsefulRpsDeltaPercent -and $p99Delta -le $MaxP99RegressionPercent `
                -and $observedMaxThreadDelta -le $AllowedThreadDelta `
                -and $errorDecision.passed `
                -and $hostSiblingBusy -le $MaxHostSiblingCpuPercent `
                -and $maxHostSteal -le $MaxHostStealCpuPercent
        $summary.Add([pscustomobject]@{
            endpoint = $endpoint; concurrency = $value
            baseline_rps = [math]::Round($baseRps, 2); candidate_rps = [math]::Round($agentRps, 2)
            rps_delta_pct = [math]::Round($rpsDelta, 2)
            baseline_p99_ms = [math]::Round($baseP99, 3); candidate_p99_ms = [math]::Round($agentP99, 3)
            p99_delta_pct = [math]::Round($p99Delta, 2)
            process_rss_delta_mib = [math]::Round($rssDelta, 3)
            max_paired_process_rss_delta_mib = [math]::Round($maxRssDelta, 3)
            container_delta_mib = [math]::Round($containerDelta, 3)
            max_paired_container_delta_mib = [math]::Round($maxContainerDelta, 3)
            thread_delta = [int]$threadDelta
            max_paired_thread_delta = [int]$observedMaxThreadDelta
            baseline_non_2xx_pct = [math]::Round($errorDecision.baseline_aggregate_pct, 6)
            candidate_non_2xx_pct = [math]::Round($errorDecision.candidate_aggregate_pct, 6)
            baseline_peak_non_2xx_pct = [math]::Round($errorDecision.baseline_peak_pct, 6)
            candidate_peak_non_2xx_pct = [math]::Round($errorDecision.candidate_peak_pct, 6)
            non_2xx_delta_pp = [math]::Round($errorDecision.median_paired_delta_pp, 6)
            aggregate_non_2xx_delta_pp = [math]::Round($errorDecision.aggregate_delta_pp, 6)
            max_paired_non_2xx_delta_pp = [math]::Round($errorDecision.max_paired_delta_pp, 6)
            non_2xx_delta_threshold_pp = $non2xxDeltaThreshold
            absolute_non_2xx_ceiling_pct = $MaxAbsoluteNon2xxPercent
            paired_host_sibling_busy_percent = [math]::Round($hostSiblingBusy, 3)
            max_host_sibling_busy_percent = [math]::Round($maxHostSiblingBusy, 3)
            max_host_steal_percent = [math]::Round($maxHostSteal, 3)
            gate = if ($passed) { "PASS" } else { "FAIL" }
        })
    }
}
$startupBase = Median -Values @(($startups | Where-Object variant -eq "baseline").ms)
$startupAgent = Median -Values @(($startups | Where-Object variant -eq "candidate").ms)
$pairedStartupDeltas = [Collections.Generic.List[double]]::new()
foreach ($pair in 1..$completedPairs) {
    $pairedBase = $startups | Where-Object { $_.pair -eq $pair -and $_.variant -eq "baseline" } | Select-Object -First 1
    $pairedAgent = $startups | Where-Object { $_.pair -eq $pair -and $_.variant -eq "candidate" } | Select-Object -First 1
    $pairedStartupDeltas.Add(100.0 * ($pairedAgent.ms - $pairedBase.ms) / $pairedBase.ms)
}
$startupDelta = Median -Values @($pairedStartupDeltas)
$pairedSteadyRssDeltas = [Collections.Generic.List[double]]::new()
$pairedSteadyContainerDeltas = [Collections.Generic.List[double]]::new()
$pairedSteadyThreadDeltas = [Collections.Generic.List[int]]::new()
foreach ($pair in 1..$completedPairs) {
    $pairedBase = $steadyMemory | Where-Object { $_.pair -eq $pair -and $_.variant -eq "baseline" } |
            Select-Object -First 1
    $pairedAgent = $steadyMemory | Where-Object { $_.pair -eq $pair -and $_.variant -eq "candidate" } |
            Select-Object -First 1
    $pairedSteadyRssDeltas.Add($pairedAgent.process_rss_mib - $pairedBase.process_rss_mib)
    $pairedSteadyContainerDeltas.Add($pairedAgent.container_mib - $pairedBase.container_mib)
    $pairedSteadyThreadDeltas.Add($pairedAgent.threads - $pairedBase.threads)
}
$steadyRssDelta = Median -Values @($pairedSteadyRssDeltas)
$steadyContainerDelta = Median -Values @($pairedSteadyContainerDeltas)
$steadyThreadDelta = Median -Values @($pairedSteadyThreadDeltas)
$steadyMemoryPassed = $steadyRssDelta -le $MaxMemoryRegressionMiB `
        -and $steadyContainerDelta -le $MaxMemoryRegressionMiB `
        -and $steadyThreadDelta -le $AllowedThreadDelta
$observedMaxWarmupRobustTrend = ($warmups.robust_trend_pct | Measure-Object -Maximum).Maximum
$observedMaxWarmupMedianShift = ($warmups.median_shift_pct | Measure-Object -Maximum).Maximum
$observedMaxWarmupMad = ($warmups.median_absolute_deviation_pct | Measure-Object -Maximum).Maximum
$observedMaxWarmupRangeSpread = ($warmups.range_spread_pct | Measure-Object -Maximum).Maximum
$observedMaxFirstStableRound = ($warmups.first_stable_round | Measure-Object -Maximum).Maximum
$observedMaxWarmupConfirmationRounds = ($warmups.confirmation_rounds | Measure-Object -Maximum).Maximum
$observedMaxWarmupTotalRounds = ($warmups.rounds | Measure-Object -Maximum).Maximum
$warmupGatePassed = -not ($warmups.gate -contains "FAIL")
$passedAll = -not ($summary.gate -contains "FAIL") -and $startupDelta -le 10.0 -and $steadyMemoryPassed

ConvertTo-Json -InputObject @($records) -Depth 5 |
        Set-Content (Join-Path $Results "raw.json") -Encoding utf8
ConvertTo-Json -InputObject @($steadyMemory) -Depth 5 |
        Set-Content (Join-Path $Results "steady-memory.json") -Encoding utf8
ConvertTo-Json -InputObject @($warmups) -Depth 5 |
        Set-Content (Join-Path $Results "warmup.json") -Encoding utf8
[ordered]@{
    passed = $passedAll
    benchmark_classification = if ($DevelopmentQuickMode) { "development-only" } else { "production" }
    release_evidence = -not $DevelopmentQuickMode
    application_kind = $ApplicationKind
    measured_endpoint_classes = @($endpoints)
    smoked_endpoint_classes = @($EndpointMap.Keys | Sort-Object)
    required_rest_version = if ($IsRustJavaRest) { $RequiredRestVersion } else { $null }
    required_rest_native_abi = if ($IsRustJavaRest) { $RequiredRestNativeAbi } else { $null }
    pair_repeats = $completedPairs
    minimum_pair_repeats = $MinimumPairRepeats
    maximum_pair_repeats = $PairRepeats
    pair_decision = if ($stoppedAfterStrictEarlyPass) { "strict_early_pass" } else { "maximum_pairs" }
    decision_statistic = "median_of_paired_deltas"
    non_2xx_decision = "endpoint_noninferiority_median_weighted_aggregate_and_peak_envelope"
    non_2xx_threshold_percentage_points = $MaxNon2xxDeltaPercentagePoints
    saturated_non_2xx_threshold_percentage_points = $MaxSaturatedNon2xxDeltaPercentagePoints
    absolute_non_2xx_ceiling_percent = $MaxAbsoluteNon2xxPercent
    activation_mode = if ($IsRustJavaRest) {
        if ($UseJavaAgentBootstrap) { "javaagent_bootstrap" } else { "embedded_native_properties" }
    } else {
        if ($UseJavaAgentBootstrap) { "javaagent_plus_starter" } else { "starter_properties" }
    }
    cpu_roles = [ordered]@{
        application = $SlotACpuSet
        application_execution = Get-ApplicationExecutionCpuSet -ReservedCpuSet $SlotACpuSet
        single_cpu_logical_pin = $PinSingleCpuQuotaToOneLogicalCpu
        runner = $RunnerCpuSet
        collector = $CollectorCpuSet
        orchestrator = $OrchestratorCpuSet
        auto_selected = [bool] $AutoSelectCpuRoles
        selection_source = if ($null -ne $selectedRoles) { $selectedRoles.source } else { "parameters" }
        application_group = if ($null -ne $selectedRoles) { $selectedRoles.application_group } else { "" }
    }
    host_preflight = $hostReadiness
    host_noise_limits = [ordered]@{
        sibling_busy_percent = $MaxHostSiblingCpuPercent
        steal_percent = $MaxHostStealCpuPercent
    }
    startup_baseline_median_ms = [math]::Round($startupBase, 2)
    startup_candidate_median_ms = [math]::Round($startupAgent, 2)
    startup_delta_pct = [math]::Round($startupDelta, 2)
    warmup_stability = [ordered]@{
        prewarm_cycles = $PreWarmCycles
        prewarm_duration_per_endpoint = $Warmup
        load_runner_lifecycle = "persistent_per_gate"
        fixed_rounds = $MaxWarmupRounds
        interleaved_rounds = $MaxWarmupRounds
        maximum_confirmation_rounds = $MaxWarmupConfirmationRounds
        confirmation_scope = if ($SequentialVariants) {
            "all_endpoints_interleaved_per_variant"
        } else {
            "all_endpoints_and_variants_interleaved"
        }
        observed_maximum_confirmation_rounds = [int] $observedMaxWarmupConfirmationRounds
        observed_maximum_total_rounds = [int] $observedMaxWarmupTotalRounds
        variant_interleaved = -not $SequentialVariants
        stability_window_rounds = $MinWarmupRounds
        maximum_allowed_robust_trend_pct = $MaxWarmupRobustTrendPercent
        maximum_allowed_borderline_robust_trend_pct = $MaxWarmupBorderlineRobustTrendPercent
        maximum_allowed_borderline_median_shift_pct = $MaxWarmupBorderlineMedianShiftPercent
        maximum_allowed_median_absolute_deviation_pct = $MaxWarmupMedianAbsoluteDeviationPercent
        observed_maximum_robust_trend_pct = [math]::Round($observedMaxWarmupRobustTrend, 3)
        observed_maximum_median_shift_pct = [math]::Round($observedMaxWarmupMedianShift, 3)
        observed_maximum_median_absolute_deviation_pct = [math]::Round($observedMaxWarmupMad, 3)
        observed_maximum_range_spread_pct = [math]::Round($observedMaxWarmupRangeSpread, 3)
        observed_maximum_first_stable_round = [int] $observedMaxFirstStableRound
        gate = if ($warmupGatePassed) { "PASS" } elseif ($DevelopmentQuickMode) { "WARN" } else { "FAIL" }
    }
    steady_memory = [ordered]@{
        normalization = if ($IsRustJavaRest) { "idle_window" } else { "explicit_full_gc_then_idle" }
        process_rss_delta_mib = [math]::Round($steadyRssDelta, 3)
        container_delta_mib = [math]::Round($steadyContainerDelta, 3)
        thread_delta = [int]$steadyThreadDelta
        threshold_mib = $MaxMemoryRegressionMiB
        gate = if ($steadyMemoryPassed) { "PASS" } else { "FAIL" }
    }
    rows = $summary
} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $Results "summary.json") -Encoding utf8

$lines = [Collections.Generic.List[string]]::new()
$reportTitle = if ($IsRustJavaRest) { "Rust-Java REST Agent Gate" } else { "Spring Boot Agent Gate" }
$compatibilityDescription = if ($IsRustJavaRest) {
    "Compatibility: rust-java-rest $RequiredRestVersion, native ABI $RequiredRestNativeAbi."
} else {
    "Compatibility: Spring Boot Servlet MVC."
}
$lines.Add("# $reportTitle")
$lines.Add("")
$activationDescription = if ($IsRustJavaRest) {
    if ($UseJavaAgentBootstrap) { "optional -javaagent bootstrap" } else { "embedded Rust runtime + properties" }
} else {
    if ($UseJavaAgentBootstrap) { "optional -javaagent bootstrap + starter" } else { "recommended starter + properties" }
}
$lines.Add("Application: **$ApplicationKind**. Activation: **$activationDescription**.")
$lines.Add("Benchmark classification: **$(if ($DevelopmentQuickMode) { 'development-only; not release evidence' } else { 'production release evidence' })**.")
$lines.Add($compatibilityDescription)
$lines.Add("Measured endpoint classes: $($endpoints -join ', '). Functional route smoke: $(@($EndpointMap.Keys | Sort-Object) -join ', ').")
$lines.Add("CPU roles: application=$SlotACpuSet, runner=$RunnerCpuSet, collector=$CollectorCpuSet, orchestrator=$OrchestratorCpuSet; auto-selected=$([bool]$AutoSelectCpuRoles).")
$lines.Add("Paired runs: $completedPairs (minimum=$MinimumPairRepeats, maximum=$PairRepeats, decision=$(if ($stoppedAfterStrictEarlyPass) { 'strict early pass' } else { 'maximum pairs' })). Mode: $(if ($SequentialVariants) { 'same-core sequential' } else { 'dual-slot isolated' }). Startup off/on medians: $([math]::Round($startupBase,2)) / $([math]::Round($startupAgent,2)) ms; paired delta median: $([math]::Round($startupDelta,2))%.")
$lines.Add("Warmup stability: $PreWarmCycles equal pre-warm cycles plus $MaxWarmupRounds measured rounds per endpoint/process, fully interleaved across endpoint classes$(if (-not $SequentialVariants) { ' and baseline/candidate variants' } else { '' }). One persistent wrk container is reused for the whole gate. A failing measured window receives at most $MaxWarmupConfirmationRounds additional full-matrix interleaved confirmation rounds without relaxing any threshold; this run used at most $observedMaxWarmupConfirmationRounds. The final $($MinWarmupRounds * 2)-round window had at most $([math]::Round($observedMaxWarmupRobustTrend,3))% normalized Theil-Sen trend against a $MaxWarmupRobustTrendPercent% primary gate. A trend up to $MaxWarmupBorderlineRobustTrendPercent% is accepted only when previous/recent window medians differ by at most $MaxWarmupBorderlineMedianShiftPercent%. Median absolute deviation must remain within $MaxWarmupMedianAbsoluteDeviationPercent%. Range spread remains diagnostic at $([math]::Round($observedMaxWarmupRangeSpread,3))%; latest first-stable round $observedMaxFirstStableRound.")
$lines.Add("RPS, p99, and startup gates use the median of paired deltas. Non-2xx uses a zero-delta gate for normal cells. The $MaxSaturatedNon2xxDeltaPercentagePoints percentage-point non-inferiority margin applies only when embedded REST heavy-json is explicitly tested at c256+. Baseline and candidate aggregate/peak error rates must also stay at or below $MaxAbsoluteNon2xxPercent%. The worst paired delta remains diagnostic. Steady memory is sampled after both variants complete the same full workload at equal process age. Per-cell RSS/cgroup maxima remain diagnostics; deterministic agent-owned and exact-source resident maxima are enforced by the separate footprint gates.")
$lines.Add("Steady memory normalization: $(if ($IsRustJavaRest) { 'equal idle window' } else { 'benchmark-only explicit full GC followed by an equal idle window' }). This removes unrelated GC-phase noise without changing any RPS or p99 measurement. Paired delta: process RSS=$([math]::Round($steadyRssDelta,3)) MiB; cgroup=$([math]::Round($steadyContainerDelta,3)) MiB; threads=$([int]$steadyThreadDelta); gate=$(if ($steadyMemoryPassed) { 'PASS' } else { 'FAIL' }).")
$lines.Add("")
$lines.Add("| Endpoint | C | RPS off/on med | Paired RPS delta | p99 off/on med ms | Paired p99 delta | RSS paired med/max MiB | cgroup paired med/max MiB | Thread paired med/max | non-2xx off/on agg % | non-2xx off/on peak % | non-2xx delta med/agg/max pp | Host sibling med/max; steal max | Gate |")
$lines.Add("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
foreach ($row in $summary) {
    $lines.Add("| $($row.endpoint) | $($row.concurrency) | $($row.baseline_rps)/$($row.candidate_rps) | $($row.rps_delta_pct)% | $($row.baseline_p99_ms)/$($row.candidate_p99_ms) | $($row.p99_delta_pct)% | $($row.process_rss_delta_mib)/$($row.max_paired_process_rss_delta_mib) | $($row.container_delta_mib)/$($row.max_paired_container_delta_mib) | $($row.thread_delta)/$($row.max_paired_thread_delta) | $($row.baseline_non_2xx_pct)/$($row.candidate_non_2xx_pct) | $($row.baseline_peak_non_2xx_pct)/$($row.candidate_peak_non_2xx_pct) | $($row.non_2xx_delta_pp)/$($row.aggregate_non_2xx_delta_pp)/$($row.max_paired_non_2xx_delta_pp) | $($row.paired_host_sibling_busy_percent)%/$($row.max_host_sibling_busy_percent)%/$($row.max_host_steal_percent)% | $($row.gate) |")
}
$lines.Add("")
$lines.Add("Overall gate: **$(if ($passedAll) { 'PASS' } else { 'BLOCKED' })**")
$lines | Set-Content (Join-Path $Results "REPORT.md") -Encoding utf8
Write-Host "$ApplicationKind gate report: $(Join-Path $Results 'REPORT.md')"
if ($FailOnGate -and -not $passedAll) {
    $gateName = if ($DevelopmentQuickMode) { "development quick gate" } else { "production gate" }
    throw "$ApplicationKind $gateName failed."
}
