[CmdletBinding()]
param(
    [int] $PairRepeats = 3,
    [string] $ConcurrencyLevels = "64,256",
    [string] $EndpointClasses = "small-json-direct,direct-json-writer,raw-json",
    [string] $Duration = "15s",
    [string] $Warmup = "8s",
    [double] $CpuLimit = 1.0,
    [string] $MemoryLimit = "128m",
    [string] $SlotACpuSet = "0",
    [string] $SlotBCpuSet = "2",
    [string] $RunnerCpuSet = "4-5",
    [string] $CollectorCpuSet = "6",
    [int] $InterPairCooldownSeconds = 3,
    [int] $PhaseCooldownSeconds = 15,
    [int] $StartupRepeats = 6,
    [double] $MinUsefulRpsDeltaPercent = -2.0,
    [double] $MaxP99RegressionPercent = 10.0,
    [double] $Max503DeltaPercentagePoints = 2.0,
    [double] $MaxMemoryRegressionMiB = 3.0,
    [double] $MaxRpsPairDeltaStandardDeviation = 10.0,
    [double] $MaxP99PairDeltaStandardDeviation = 15.0,
    [double] $MaxStartupRegressionPercent = 10.0,
    [double] $MaxStartupRegressedPairRatePercent = 25.0,
    [double] $MaxStartupCoefficientVariationPercent = 20.0,
    [int] $HttpSampleRate = 256,
    [double] $MaxHostCpuAveragePercent = 15.0,
    [double] $MaxHostCpuPeakPercent = 40.0,
    [double] $MinHostFreeVirtualMiB = 3072.0,
    [int] $HostStabilizationSeconds = 15,
    [switch] $SkipHostPreflight,
    [switch] $ProtocolOnly,
    [switch] $AutoSelectCpuRoles,
    [switch] $AllowRunnerCollectorSiblingSharing,
    [switch] $SkipBuild,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if ($PairRepeats -lt 3) {
    throw "PairRepeats must be at least 3 per CPU slot."
}
if ($StartupRepeats -lt 4 -or ($StartupRepeats % 2) -ne 0) {
    throw "StartupRepeats must be an even number of at least 4."
}
if ($MaxMemoryRegressionMiB -le 0 -or $MaxMemoryRegressionMiB -gt 3.0) {
    throw "MaxMemoryRegressionMiB must be greater than zero and cannot exceed the 3 MiB product boundary."
}
if ($HttpSampleRate -lt 1 -or $HttpSampleRate -gt 1024 `
        -or (($HttpSampleRate -band ($HttpSampleRate - 1)) -ne 0)) {
    throw "HttpSampleRate must be a power of two between 1 and 1024."
}
if ($HostStabilizationSeconds -lt 0 -or $HostStabilizationSeconds -gt 300) {
    throw "HostStabilizationSeconds must be between 0 and 300."
}

$ConcurrencyValues = @(
    $ConcurrencyLevels -split "[,\s]+" |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { [int] $_ }
)
if ($ConcurrencyValues.Count -eq 0) {
    throw "At least one concurrency value is required."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDir "benchmark_isolation.ps1")
$ProjectRoot = Split-Path -Parent $ScriptDir
$WorkspaceRoot = Split-Path -Parent $ProjectRoot
$FrameworkRoot = Join-Path $WorkspaceRoot "rust-java-rest"
$MockRoot = Join-Path $ScriptDir "mock-collector"
$Context = Join-Path $ScriptDir "context"
$ResultsDir = Join-Path $ScriptDir ("results\glowroot_gate_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$ResidentResults = Join-Path $ResultsDir "resident"
$StartupResults = Join-Path $ResultsDir "startup"
$ProtocolDir = Join-Path $ResultsDir "protocol"
$Network = "reactor-benchmark-net"
$AppImage = "java-rust-glowroot-agent:benchmark-app"
$CollectorImage = "java-rust-glowroot-agent:mock-collector"
$RunnerImage = "reactor-benchmark-runner:local"
$CollectorContainer = "glowroot-mock-collector"
$AppContainer = "glowroot-agent-smoke"
$AgentId = "java-rust-glowroot-agent::benchmark"
$FailOpenObservationTimeoutMs = 75000
$ExportIntervalContractMs = 60000

if (-not $ProtocolOnly -and -not $SkipHostPreflight) {
    Assert-ReactorHostBenchmarkReadiness `
            -MaxAverageCpuPercent $MaxHostCpuAveragePercent `
            -MaxPeakCpuPercent $MaxHostCpuPeakPercent `
            -MinFreeVirtualMiB $MinHostFreeVirtualMiB
}

function Invoke-Checked {
    param([string] $File, [string[]] $Arguments, [string] $WorkingDirectory)
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

function Remove-Container {
    param([string] $Name)
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name }) {
        & docker rm -f $Name *> $null
    }
}

function Ensure-Network {
    if (-not (& docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $Network })) {
        Invoke-Checked docker @("network", "create", $Network) $ProjectRoot | Out-Null
    }
}

function Wait-ContainerLog {
    param([string] $Container, [string] $Text, [int] $TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $logs = (& docker logs $Container 2>&1) -join "`n"
        if ($logs.Contains($Text)) {
            return
        }
        if (-not (& docker ps --format "{{.Names}}" | Where-Object { $_ -eq $Container })) {
            throw "$Container exited before becoming ready:`n$logs"
        }
        Start-Sleep -Milliseconds 250
    }
    throw "$Container did not report readiness within $TimeoutSeconds seconds."
}

function Get-RunnerDockerPrefix {
    param([string] $EntryPoint)
    $args = @("run", "--rm", "--network", $Network, "--entrypoint", $EntryPoint)
    if (-not [string]::IsNullOrWhiteSpace($RunnerCpuSet)) {
        $args += @("--cpuset-cpus", $RunnerCpuSet)
    }
    return $args + @($RunnerImage)
}

function Invoke-Runner {
    param([string[]] $Arguments)
    $dockerArgs = @(Get-RunnerDockerPrefix "load-probe") + $Arguments
    $output = & docker @dockerArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "load-probe failed:`n$($output -join "`n")"
    }
    return $output
}

function Invoke-RunnerCurl {
    param([string] $Url)
    $dockerArgs = @(Get-RunnerDockerPrefix "curl") + @("-fsS", $Url)
    $output = & docker @dockerArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "curl failed for ${Url}:`n$($output -join "`n")"
    }
    return ($output -join "`n")
}

function Wait-Http {
    param([string] $Url, [int] $TimeoutSeconds = 45)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-RunnerCurl $Url | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw "Application did not become ready: $Url"
}

function Read-JsonFile {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Find-SingleArtifact {
    param([string] $Directory, [string] $Filter)
    $matches = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
            Sort-Object LastWriteTime -Descending)
    if ($matches.Count -eq 0) {
        throw "Artifact not found: $Directory/$Filter"
    }
    return $matches[0].FullName
}

function Prepare-BuildContext {
    $fullContext = [IO.Path]::GetFullPath($Context)
    $fullScriptDir = [IO.Path]::GetFullPath($ScriptDir) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullContext.StartsWith($fullScriptDir, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to recreate context outside benchmark directory: $fullContext"
    }
    if (Test-Path -LiteralPath $fullContext) {
        Remove-Item -LiteralPath $fullContext -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $fullContext | Out-Null
    $frameworkJar = Find-SingleArtifact (Join-Path $FrameworkRoot "target") "*-core-runtime.jar"
    $codegenJar = Find-SingleArtifact (Join-Path $FrameworkRoot "target") "*-codegen.jar"
    $agentJar = Find-SingleArtifact (Join-Path $ProjectRoot "agent-bootstrap\target") `
            "java-rust-glowroot-agent-*.jar"
    Copy-Item -LiteralPath $frameworkJar -Destination (Join-Path $fullContext "framework.jar")
    Copy-Item -LiteralPath $codegenJar -Destination (Join-Path $fullContext "codegen.jar")
    Copy-Item -LiteralPath $agentJar -Destination (Join-Path $fullContext "agent.jar")
    Copy-Item -LiteralPath (Join-Path $FrameworkRoot "benchmark\minimal-production\src") `
            -Destination (Join-Path $fullContext "src") -Recurse
}

function Start-Collector {
    Remove-Container $CollectorContainer
    $mount = "{0}:/reports" -f ([IO.Path]::GetFullPath($ProtocolDir))
    $args = @(
        "run", "-d", "--name", $CollectorContainer, "--network", $Network,
        "--cpus", "0.5", "--memory", "128m", "-v", $mount,
        "-e", "MOCK_COLLECTOR_REPORT=/reports/mock-collector.json"
    )
    if (-not [string]::IsNullOrWhiteSpace($CollectorCpuSet)) {
        $args += @("--cpuset-cpus", $CollectorCpuSet)
    }
    $args += $CollectorImage
    Invoke-Checked docker $args $ProjectRoot | Out-Null
    Wait-ContainerLog $CollectorContainer "Glowroot mock collector ready"
}

function Get-NativePropertyOptions {
    param(
        [string] $CollectorAddress = "${CollectorContainer}:8181",
        [string] $AgentIdValue = $AgentId,
        [int] $SlowThresholdMs = 500,
        [int] $TraceCapacity = 0,
        [int] $SampleRate = 256
    )
    return @(
        "-Dreactor.glowroot.enabled=true",
        "-Dreactor.glowroot.collector.address=$CollectorAddress",
        "-Dreactor.glowroot.agent.id=$AgentIdValue",
        "-Dreactor.glowroot.application.name=glowroot-benchmark",
        "-Dreactor.glowroot.trace.slow-threshold-ms=$SlowThresholdMs",
        "-Dreactor.glowroot.http.sample-rate=$SampleRate",
        "-Dreactor.glowroot.trace.capacity=$TraceCapacity",
        "-Dreactor.glowroot.max-routes=64",
        "-Dreactor.glowroot.max-export-bytes=65536"
    ) -join " "
}

function Start-AgentApp {
    param(
        [string] $CollectorAddress,
        [ValidateSet("native-properties", "javaagent")]
        [string] $Mode = "native-properties",
        [string] $AgentIdValue = $AgentId,
        [int] $SlowThresholdMs = 500
    )
    Remove-Container $AppContainer
    $launchOptions = if ($Mode -eq "javaagent") {
        "-javaagent:/app/agent.jar=collector=$CollectorAddress,agent-id=$AgentIdValue," +
            "application=glowroot-benchmark,slow-threshold-ms=$SlowThresholdMs," +
            "http-sample-rate=256,trace-capacity=8,max-routes=64,max-export-bytes=65536"
    } else {
        Get-NativePropertyOptions `
                -CollectorAddress $CollectorAddress `
                -AgentIdValue $AgentIdValue `
                -SlowThresholdMs $SlowThresholdMs `
                -TraceCapacity 8 `
                -SampleRate 256
    }
    $args = @(
        "run", "-d", "--name", $AppContainer, "--network", $Network,
        "--cpus", "$CpuLimit", "--memory", $MemoryLimit,
        # app.Dockerfile evaluates this slot as JVM launch arguments. In native-properties mode it
        # deliberately contains only -D options and does not load the convenience Java agent.
        "-e", "JAVA_AGENT_OPTS=$launchOptions"
    )
    if (-not [string]::IsNullOrWhiteSpace($SlotACpuSet)) {
        $args += @("--cpuset-cpus", $SlotACpuSet)
    }
    $args += $AppImage
    Invoke-Checked docker $args $ProjectRoot | Out-Null
    Wait-Http "http://${AppContainer}:8080/health"
}

function Invoke-ProtocolGate {
    Start-AgentApp -CollectorAddress "${CollectorContainer}:8181" `
            -Mode "native-properties" -SlowThresholdMs 1
    try {
        $output = Invoke-Runner @(
            "--url", "http://${AppContainer}:8080/api/v1/heavy?items=1000",
            "--method", "GET", "--concurrency", "64", "--duration", "10s", "--timeout-ms", "10000"
        )
        $output | Set-Content -LiteralPath (Join-Path $ProtocolDir "load.txt") -Encoding utf8

        $reportPath = Join-Path $ProtocolDir "mock-collector.json"
        $deadline = (Get-Date).AddSeconds(75)
        $report = $null
        while ((Get-Date) -lt $deadline) {
            $report = Read-JsonFile $reportPath
            if ($null -ne $report -and $report.healthy -and $report.init_messages -ge 1 `
                    -and $report.aggregate_messages -ge 1 -and $report.gauge_messages -ge 1 `
                    -and $report.trace_messages -ge 1) {
                break
            }
            Start-Sleep -Seconds 1
        }
        if ($null -eq $report -or -not $report.healthy -or $report.aggregate_messages -lt 1 `
                -or $report.gauge_messages -lt 1 -or $report.trace_messages -lt 1 `
                -or $report.validation_errors -ne 0 -or $report.agent_id -ne $AgentId) {
            throw "Glowroot protocol validation failed: $($report | ConvertTo-Json -Compress)"
        }
        $report | ConvertTo-Json -Depth 8 |
                Set-Content -LiteralPath (Join-Path $ProtocolDir "protocol-gate-report.json") -Encoding utf8
        Invoke-RunnerCurl "http://${AppContainer}:8080/diagnostics/glowroot" |
                Set-Content -LiteralPath (Join-Path $ProtocolDir "agent-diagnostics.json") -Encoding utf8
    } finally {
        if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $AppContainer }) {
            & docker logs $AppContainer *> (Join-Path $ProtocolDir "app.log")
        }
        Remove-Container $AppContainer
    }
}

function Invoke-JavaAgentBootstrapGate {
    $bootstrapAgentId = "${AgentId}::javaagent-bootstrap"
    Start-AgentApp -CollectorAddress "${CollectorContainer}:8181" `
            -Mode "javaagent" -AgentIdValue $bootstrapAgentId
    try {
        $diagnostics = Invoke-RunnerCurl "http://${AppContainer}:8080/diagnostics/glowroot"
        $diagnostics | Set-Content `
                -LiteralPath (Join-Path $ProtocolDir "javaagent-bootstrap-diagnostics.json") `
                -Encoding utf8
        $parsed = $diagnostics | ConvertFrom-Json
        if (-not $parsed.enabled -or $parsed.agent_id -ne $bootstrapAgentId `
                -or $parsed.max_routes -ne 64 -or $parsed.trace_capacity -ne 8) {
            throw "Java agent bootstrap did not apply the expected native configuration."
        }
    } finally {
        if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $AppContainer }) {
            & docker logs $AppContainer *> (Join-Path $ProtocolDir "javaagent-bootstrap-app.log")
        }
        Remove-Container $AppContainer
    }
}

function Invoke-FailOpenGate {
    # The hard-memory profile resolves DNS once at startup. Exercise a resolved but unavailable
    # collector here; an unresolvable name is an invalid startup configuration, not a fail-open
    # transport outage.
    Start-AgentApp -CollectorAddress "127.0.0.1:65534" -Mode "native-properties"
    try {
        $output = (Invoke-Runner @(
            "--url", "http://${AppContainer}:8080/api/v1/candidates/direct",
            "--method", "GET", "--concurrency", "64", "--duration", "5s", "--timeout-ms", "10000"
        )) -join "`n"
        $output | Set-Content -LiteralPath (Join-Path $ProtocolDir "fail-open-load.txt") -Encoding utf8
        if ($output -notmatch '(?m)^Status 200: [1-9][0-9]*$' -or $output -notmatch 'errors total: 0') {
            throw "Collector-down fail-open gate produced request errors."
        }
        # The production interval contract is 60 seconds. Wait for the first real transport attempt
        # instead of treating the pre-export disconnected state as a failed assertion.
        $deadline = (Get-Date).AddMilliseconds($FailOpenObservationTimeoutMs)
        $attempts = 0
        $parsed = $null
        $diagnostics = $null
        $observation = [Diagnostics.Stopwatch]::StartNew()
        do {
            $attempts++
            $diagnostics = Invoke-RunnerCurl "http://${AppContainer}:8080/diagnostics/glowroot"
            $parsed = $diagnostics | ConvertFrom-Json
            if (-not $parsed.connected -and $parsed.export_failure -ge 1) { break }
            Start-Sleep -Seconds 5
        } while ((Get-Date) -lt $deadline)
        $observation.Stop()
        $diagnostics | Set-Content `
                -LiteralPath (Join-Path $ProtocolDir "fail-open-diagnostics.json") `
                -Encoding utf8
        [ordered]@{
            attempts = $attempts
            elapsed_ms = [math]::Round($observation.Elapsed.TotalMilliseconds, 2)
            deadline_ms = $FailOpenObservationTimeoutMs
            export_interval_contract_ms = $ExportIntervalContractMs
            connected = [bool] $parsed.connected
            export_failure = [int64] $parsed.export_failure
        } | ConvertTo-Json -Depth 3 | Set-Content `
                -LiteralPath (Join-Path $ProtocolDir "fail-open-observation.json") `
                -Encoding utf8
        if ($parsed.connected -or $parsed.export_failure -lt 1) {
            throw "Collector-down diagnostics did not expose a failed, disconnected exporter."
        }
    } finally {
        if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $AppContainer }) {
            & docker logs $AppContainer *> (Join-Path $ProtocolDir "fail-open-app.log")
        }
        Remove-Container $AppContainer
    }
}

function Write-ProtocolGateReport {
    $protocol = Get-Content -Raw -LiteralPath (Join-Path $ProtocolDir "protocol-gate-report.json") |
            ConvertFrom-Json
    $failOpen = Get-Content -Raw -LiteralPath (Join-Path $ProtocolDir "fail-open-diagnostics.json") |
            ConvertFrom-Json
    $failOpenObservation = Get-Content -Raw `
            -LiteralPath (Join-Path $ProtocolDir "fail-open-observation.json") |
            ConvertFrom-Json
    $javaAgent = Get-Content -Raw `
            -LiteralPath (Join-Path $ProtocolDir "javaagent-bootstrap-diagnostics.json") |
            ConvertFrom-Json
    $protocolPass = $protocol.healthy -and $protocol.validation_errors -eq 0
    $failOpenPass = -not $failOpen.connected -and $failOpen.export_failure -ge 1 `
            -and $failOpenObservation.attempts -ge 1 `
            -and $failOpenObservation.export_failure -ge 1 `
            -and $failOpenObservation.deadline_ms -eq $FailOpenObservationTimeoutMs `
            -and $failOpenObservation.export_interval_contract_ms -eq $ExportIntervalContractMs `
            -and $failOpenObservation.elapsed_ms -le $FailOpenObservationTimeoutMs
    $javaAgentPass = $javaAgent.enabled `
            -and $javaAgent.agent_id -eq "${AgentId}::javaagent-bootstrap" `
            -and $javaAgent.max_routes -eq 64 -and $javaAgent.trace_capacity -eq 8
    $passed = $protocolPass -and $failOpenPass -and $javaAgentPass

    [ordered]@{
        passed = $passed
        mode = "protocol-only"
        protocol_gate = if ($protocolPass) { "PASS" } else { "FAIL" }
        fail_open_gate = if ($failOpenPass) { "PASS" } else { "FAIL" }
        javaagent_bootstrap_gate = if ($javaAgentPass) { "PASS" } else { "FAIL" }
        protocol = $protocol
        fail_open = $failOpen
        fail_open_observation = $failOpenObservation
    } | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath (Join-Path $ResultsDir "gate-summary.json") -Encoding utf8

    @(
        "# Glowroot Protocol And Fail-Open Gate"
        ""
        "- Protocol gate: $(if ($protocolPass) { 'PASS' } else { 'FAIL' })"
        "- Collector-down fail-open gate: $(if ($failOpenPass) { 'PASS' } else { 'FAIL' })"
        "- Fail-open transport observation: $($failOpenObservation.attempts) polls, " +
                "$($failOpenObservation.elapsed_ms) ms, $($failOpenObservation.export_failure) export failures"
        "- Optional Java agent bootstrap gate: $(if ($javaAgentPass) { 'PASS' } else { 'FAIL' })"
        ""
        "Overall gate: **$(if ($passed) { 'PASS' } else { 'BLOCKED' })**"
        ""
        "This mode does not make an RPS, p99, startup, or resident-memory claim."
    ) | Set-Content -LiteralPath (Join-Path $ResultsDir "REPORT.md") -Encoding utf8
    return $passed
}

function Write-GateReport {
    $comparisonRows = @(
        Get-ChildItem -LiteralPath $ResidentResults -Filter "crossover_comparison.csv" -Recurse -File |
            ForEach-Object { Import-Csv -LiteralPath $_.FullName }
    )
    if ($comparisonRows.Count -eq 0) {
        throw "Resident crossover gate produced no comparison rows."
    }
    $startupCsv = Join-Path $StartupResults "comparison\startup_comparison.csv"
    if (-not (Test-Path -LiteralPath $startupCsv)) {
        throw "Startup gate result is missing: $startupCsv"
    }
    $startup = @(Import-Csv -LiteralPath $startupCsv | Select-Object -First 1)[0]
    $protocol = Get-Content -Raw -LiteralPath (Join-Path $ProtocolDir "protocol-gate-report.json") |
            ConvertFrom-Json
    $failOpen = Get-Content -Raw -LiteralPath (Join-Path $ProtocolDir "fail-open-diagnostics.json") |
            ConvertFrom-Json
    $failOpenObservation = Get-Content -Raw `
            -LiteralPath (Join-Path $ProtocolDir "fail-open-observation.json") |
            ConvertFrom-Json
    $javaAgent = Get-Content -Raw `
            -LiteralPath (Join-Path $ProtocolDir "javaagent-bootstrap-diagnostics.json") |
            ConvertFrom-Json
    $residentPass = @($comparisonRows | Where-Object gate -ne "PASS").Count -eq 0
    $startupPass = $startup.gate -eq "PASS"
    $protocolPass = $protocol.healthy -and $protocol.validation_errors -eq 0
    $failOpenPass = -not $failOpen.connected -and $failOpen.export_failure -ge 1 `
            -and $failOpenObservation.attempts -ge 1 `
            -and $failOpenObservation.export_failure -ge 1 `
            -and $failOpenObservation.deadline_ms -eq $FailOpenObservationTimeoutMs `
            -and $failOpenObservation.export_interval_contract_ms -eq $ExportIntervalContractMs `
            -and $failOpenObservation.elapsed_ms -le $FailOpenObservationTimeoutMs
    $javaAgentPass = $javaAgent.enabled `
            -and $javaAgent.agent_id -eq "${AgentId}::javaagent-bootstrap" `
            -and $javaAgent.max_routes -eq 64 -and $javaAgent.trace_capacity -eq 8
    $passed = $residentPass -and $startupPass -and $protocolPass -and $failOpenPass `
            -and $javaAgentPass

    $summary = [ordered]@{
        passed = $passed
        protocol_gate = if ($protocolPass) { "PASS" } else { "FAIL" }
        fail_open_gate = if ($failOpenPass) { "PASS" } else { "FAIL" }
        javaagent_bootstrap_gate = if ($javaAgentPass) { "PASS" } else { "FAIL" }
        fail_open_observation = $failOpenObservation
        resident_crossover_gate = if ($residentPass) { "PASS" } else { "BLOCKED" }
        startup_gate = $startup.gate
        endpoint_cells = $comparisonRows
        thresholds = [ordered]@{
            http_sample_rate = $HttpSampleRate
            max_process_rss_regression_mib = $MaxMemoryRegressionMiB
            max_container_memory_regression_mib = $MaxMemoryRegressionMiB
            min_useful_rps_delta_percent = $MinUsefulRpsDeltaPercent
            max_p99_regression_percent = $MaxP99RegressionPercent
            max_503_delta_percentage_points = $Max503DeltaPercentagePoints
            max_rps_pair_delta_sd_pp = $MaxRpsPairDeltaStandardDeviation
            max_p99_pair_delta_sd_pp = $MaxP99PairDeltaStandardDeviation
        }
    }
    $summary | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath (Join-Path $ResultsDir "gate-summary.json") -Encoding utf8

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# Glowroot Footprint And Performance Gate")
    $lines.Add("")
    $lines.Add("The same application image was kept resident with telemetry disabled and enabled.")
    $lines.Add("CPU slots were crossed in a second phase so a favourable CPU could not decide the result.")
    $lines.Add("")
    $lines.Add("- Protocol gate: $(if ($protocolPass) { 'PASS' } else { 'FAIL' })")
    $lines.Add("- Collector-down fail-open gate: $(if ($failOpenPass) { 'PASS' } else { 'FAIL' })")
    $lines.Add("- Fail-open transport observation: $($failOpenObservation.attempts) polls, " +
            "$($failOpenObservation.elapsed_ms) ms, $($failOpenObservation.export_failure) export failures")
    $lines.Add("- Optional Java agent bootstrap gate: $(if ($javaAgentPass) { 'PASS' } else { 'FAIL' })")
    $lines.Add("- Resident crossover gate: $(if ($residentPass) { 'PASS' } else { 'BLOCKED' })")
    $lines.Add("- Startup gate: $($startup.gate)")
    $lines.Add("")
    $lines.Add("| Endpoint class | C | Pairs | Process RSS disabled/enabled MiB | RSS delta | Container delta | Useful 200 RPS delta | p99 delta | 503 delta | RPS/P99 within-phase variation | Gate |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    foreach ($row in $comparisonRows | Sort-Object endpoint_class, {[int]$_.concurrency}) {
        $lines.Add("| $($row.endpoint_class) | $($row.concurrency) | $($row.pairs) | $($row.baseline_median_process_rss_mib)/$($row.candidate_median_process_rss_mib) | $($row.process_rss_delta_mib) | $($row.container_memory_delta_mib) | $($row.useful_200_rps_delta_pct)% | $($row.p99_delta_pct)% | $($row.crossover_503_delta_pp) pp | $($row.within_phase_rps_variation_pct)/$($row.within_phase_p99_variation_pct)% | $($row.gate) |")
    }
    $lines.Add("")
    $lines.Add("| Startup metric | Disabled median | Enabled median | Paired delta | Gate |")
    $lines.Add("|---|---:|---:|---:|---|")
    $lines.Add("| Internal ready | $($startup.baseline_median_ready_ms) ms | $($startup.candidate_median_ready_ms) ms | $($startup.median_paired_ready_delta_pct)% | $($startup.gate) |")
    $lines.Add("| HTTP reachable | $($startup.baseline_median_reachable_ms) ms | $($startup.candidate_median_reachable_ms) ms | $($startup.median_paired_reachable_delta_pct)% | $($startup.gate) |")
    $lines.Add("")
    $lines.Add("Overall gate: **$(if ($passed) { 'PASS' } else { 'BLOCKED' })**")
    $lines.Add("")
    $lines.Add("A favourable median never overrides an unstable pair-delta gate.")
    $lines.Add("This same-image gate measures active telemetry state. A release also needs a previous-version versus new-version artifact gate to include native code-page growth.")
    $lines | Set-Content -LiteralPath (Join-Path $ResultsDir "REPORT.md") -Encoding utf8
    return $passed
}

if (-not $SkipBuild) {
    Invoke-Checked mvn @("-q", "clean", "package") $ProjectRoot | Out-Null
    Invoke-Checked mvn @("-q", "-DskipTests", "package") $FrameworkRoot | Out-Null
    Invoke-Checked mvn @("-q", "clean", "package") $MockRoot | Out-Null
    Prepare-BuildContext
    Invoke-Checked docker @("build", "-t", $AppImage, "-f", "app.Dockerfile", ".") $ScriptDir | Out-Null
    Invoke-Checked docker @("build", "-t", $CollectorImage, ".") $MockRoot | Out-Null
    Invoke-Checked docker @("build", "-t", $RunnerImage, "-f", "Dockerfile.benchmark", ".") `
            (Join-Path $FrameworkRoot "benchmark") | Out-Null
} else {
    foreach ($image in $AppImage, $CollectorImage, $RunnerImage) {
        Invoke-Checked docker @("image", "inspect", $image) $ProjectRoot | Out-Null
    }
}

$selectedRoles = $null
if ($AutoSelectCpuRoles) {
    if (-not $ProtocolOnly) {
        throw "Automatic two-slot selection is not supported by the resident crossover gate. Use the sequential production matrix."
    }
    $selectedRoles = Select-ReactorBenchmarkCpuRoles
    $SlotACpuSet = $selectedRoles.application
    $SlotBCpuSet = $selectedRoles.application
    $RunnerCpuSet = $selectedRoles.runner
    $CollectorCpuSet = $selectedRoles.collector
}
Set-ReactorCurrentProcessCpuAffinity -CpuSet $RunnerCpuSet
Assert-ReactorBenchmarkCpuIsolation `
        -RunnerImage $RunnerImage `
        -SlotACpuSet $SlotACpuSet `
        -SlotBCpuSet $SlotBCpuSet `
        -RunnerCpuSet $RunnerCpuSet `
        -CollectorCpuSet $CollectorCpuSet `
        -AllowSharedApplicationSlots:($ProtocolOnly -and $AutoSelectCpuRoles) `
        -AllowRunnerCollectorSiblingSharing:$AllowRunnerCollectorSiblingSharing

if (-not $ProtocolOnly -and -not $SkipHostPreflight) {
    if ($HostStabilizationSeconds -gt 0) {
        Start-Sleep -Seconds $HostStabilizationSeconds
    }
    Assert-ReactorHostBenchmarkReadiness `
            -MaxAverageCpuPercent $MaxHostCpuAveragePercent `
            -MaxPeakCpuPercent $MaxHostCpuPeakPercent `
            -MinFreeVirtualMiB $MinHostFreeVirtualMiB
}

New-Item -ItemType Directory -Force -Path $ResultsDir, $ResidentResults, $ProtocolDir | Out-Null

Ensure-Network
Start-Collector
$performanceCollectorAddress = "$(Get-ReactorContainerNetworkIp `
        -Container $CollectorContainer -Network $Network):8181"
$gatePassed = $false
try {
    Invoke-ProtocolGate
    Invoke-JavaAgentBootstrapGate
    Invoke-FailOpenGate
    if ($ProtocolOnly) {
        $gatePassed = Write-ProtocolGateReport
    } else {
        $baselineOptions = "-Dreactor.glowroot.enabled=false"
        # The hard-memory production path is native properties. The optional -javaagent bootstrap
        # is validated separately and must not contaminate the resident/startup release gate.
        $candidateOptions = Get-NativePropertyOptions `
                -CollectorAddress $performanceCollectorAddress `
                -SampleRate $HttpSampleRate
        foreach ($concurrency in $ConcurrencyValues) {
            $cellDir = Join-Path $ResidentResults "c$concurrency"
            & (Join-Path $FrameworkRoot "benchmark\resident_crossover_gate.ps1") `
                    -BaselineImage $AppImage `
                    -CandidateImage $AppImage `
                    -ResultsDir $cellDir `
                    -RepeatCountPerSlot $PairRepeats `
                    -Concurrency $concurrency `
                    -Duration $Duration `
                    -PreWarmDuration $Warmup `
                    -EndpointClasses $EndpointClasses `
                    -InterPairCooldownSeconds $InterPairCooldownSeconds `
                    -PhaseCooldownSeconds $PhaseCooldownSeconds `
                    -CpuLimit $CpuLimit `
                    -MemoryLimit $MemoryLimit `
                    -SlotACpuSet $SlotACpuSet `
                    -SlotBCpuSet $SlotBCpuSet `
                    -RunnerCpuSet $RunnerCpuSet `
                    -BaselineJavaOptsAppend $baselineOptions `
                    -CandidateJavaOptsAppend $candidateOptions `
                    -AdditionalNetwork $Network `
                    -MinUsefulRpsDeltaPercent $MinUsefulRpsDeltaPercent `
                    -MaxP99RegressionPercent $MaxP99RegressionPercent `
                    -Max503DeltaPercentagePoints $Max503DeltaPercentagePoints `
                    -MaxProcessRssRegressionMiB $MaxMemoryRegressionMiB `
                    -MaxContainerMemoryRegressionMiB $MaxMemoryRegressionMiB `
                    -MaxRpsPairDeltaStandardDeviation $MaxRpsPairDeltaStandardDeviation `
                    -MaxP99PairDeltaStandardDeviation $MaxP99PairDeltaStandardDeviation
            if ($LASTEXITCODE -ne 0) {
                throw "Resident Glowroot crossover failed at concurrency $concurrency."
            }
        }

        & (Join-Path $FrameworkRoot "benchmark\image_startup_gate.ps1") `
                -BaselineImage $AppImage `
                -CandidateImage $AppImage `
                -ResultsDir $StartupResults `
                -RepeatCount $StartupRepeats `
                -CpuLimit $CpuLimit `
                -CpuSet $SlotACpuSet `
                -MemoryLimit $MemoryLimit `
                -BaselineJavaOptsAppend $baselineOptions `
                -CandidateJavaOptsAppend $candidateOptions `
                -Network $Network `
                -MaxRegressionPercent $MaxStartupRegressionPercent `
                -MaxRegressedPairRatePercent $MaxStartupRegressedPairRatePercent `
                -MaxCoefficientVariationPercent $MaxStartupCoefficientVariationPercent
        if ($LASTEXITCODE -ne 0) {
            throw "Glowroot startup gate execution failed."
        }
        $gatePassed = Write-GateReport
    }
} finally {
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $CollectorContainer }) {
        & docker logs $CollectorContainer *> (Join-Path $ProtocolDir "mock-collector.log")
    }
    Remove-Container $AppContainer
    Remove-Container $CollectorContainer
}

Write-Host "Glowroot gate report: $(Join-Path $ResultsDir 'REPORT.md')"
if ($FailOnGate -and -not $gatePassed) {
    exit 1
}
