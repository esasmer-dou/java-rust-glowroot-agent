[CmdletBinding()]
param(
    [string] $BaselineTag = "v4.3.0",
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
    [double] $MaxHostCpuAveragePercent = 15.0,
    [double] $MaxHostCpuPeakPercent = 40.0,
    [double] $MinHostFreeVirtualMiB = 3072.0,
    [int] $HostStabilizationSeconds = 15,
    [switch] $SkipHostPreflight,
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
$ResultsDir = Join-Path $ScriptDir ("results\artifact_upgrade_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$ResidentResults = Join-Path $ResultsDir "resident"
$StartupResults = Join-Path $ResultsDir "startup"
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$safeTag = $BaselineTag -replace "[^A-Za-z0-9_.-]", "-"
$BaselineImage = "java-rust-glowroot-agent:artifact-$safeTag"
$CandidateImage = "java-rust-glowroot-agent:artifact-candidate"
$CollectorImage = "java-rust-glowroot-agent:mock-collector"
$RunnerImage = "reactor-benchmark-runner:local"
$Network = "glowroot-artifact-$suffix"
$CollectorContainer = "glowroot-artifact-collector-$suffix"
$BaselineWorktree = Join-Path ([IO.Path]::GetTempPath()) "glowroot-artifact-$suffix"
$AgentId = "java-rust-glowroot-agent::artifact-gate"
$worktreeCreated = $false
$networkCreated = $false

if (-not $SkipHostPreflight) {
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

function Find-SingleArtifact {
    param([string] $Directory, [string] $Filter)
    $matches = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
            Sort-Object LastWriteTime -Descending)
    if ($matches.Count -eq 0) {
        throw "Artifact not found: $Directory/$Filter"
    }
    return $matches[0].FullName
}

function Reset-Context {
    $fullContext = [IO.Path]::GetFullPath($Context)
    $fullScriptDir = [IO.Path]::GetFullPath($ScriptDir) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullContext.StartsWith($fullScriptDir, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to recreate context outside benchmark directory: $fullContext"
    }
    if (Test-Path -LiteralPath $fullContext) {
        Remove-Item -LiteralPath $fullContext -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $fullContext | Out-Null
    return $fullContext
}

function Prepare-Context {
    param([string] $FrameworkBuildRoot)
    $fullContext = Reset-Context
    Copy-Item -LiteralPath (Find-SingleArtifact (Join-Path $FrameworkBuildRoot "target") "*-core-runtime.jar") `
            -Destination (Join-Path $fullContext "framework.jar")
    Copy-Item -LiteralPath (Find-SingleArtifact (Join-Path $FrameworkBuildRoot "target") "*-codegen.jar") `
            -Destination (Join-Path $fullContext "codegen.jar")
    Copy-Item -LiteralPath (Find-SingleArtifact (Join-Path $ProjectRoot "agent-bootstrap\target") "java-rust-glowroot-agent-*.jar") `
            -Destination (Join-Path $fullContext "agent.jar")
    # Both images compile the current minimal application source. Only the framework artifacts differ.
    Copy-Item -LiteralPath (Join-Path $FrameworkRoot "benchmark\minimal-production\src") `
            -Destination (Join-Path $fullContext "src") -Recurse
}

function Remove-Container {
    param([string] $Name)
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name }) {
        & docker rm -f $Name *> $null
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

function Get-NativePropertyOptions {
    param([string] $CollectorAddress)

    return @(
        "-Dreactor.glowroot.enabled=true",
        "-Dreactor.glowroot.collector.address=$CollectorAddress",
        "-Dreactor.glowroot.agent.id=$AgentId",
        "-Dreactor.glowroot.application.name=glowroot-artifact-gate",
        "-Dreactor.glowroot.http.sample-rate=256",
        "-Dreactor.glowroot.trace.capacity=0",
        "-Dreactor.glowroot.max-routes=64",
        "-Dreactor.glowroot.max-export-bytes=65536"
    ) -join " "
}

function Write-Report {
    $rows = @(
        Get-ChildItem -LiteralPath $ResidentResults -Filter "crossover_comparison.csv" -Recurse -File |
            ForEach-Object { Import-Csv -LiteralPath $_.FullName }
    )
    if ($rows.Count -eq 0) {
        throw "Artifact resident gate produced no comparison rows."
    }
    $startupCsv = Join-Path $StartupResults "comparison\startup_comparison.csv"
    $startup = @(Import-Csv -LiteralPath $startupCsv | Select-Object -First 1)[0]
    $residentPass = @($rows | Where-Object gate -ne "PASS").Count -eq 0
    $startupPass = $startup.gate -eq "PASS"
    $passed = $residentPass -and $startupPass

    $summary = [ordered]@{
        passed = $passed
        baseline_tag = $BaselineTag
        baseline_image = $BaselineImage
        candidate_image = $CandidateImage
        resident_crossover_gate = if ($residentPass) { "PASS" } else { "BLOCKED" }
        startup_gate = $startup.gate
        endpoint_cells = $rows
        thresholds = [ordered]@{
            max_process_rss_regression_mib = $MaxMemoryRegressionMiB
            max_container_memory_regression_mib = $MaxMemoryRegressionMiB
            min_useful_rps_delta_percent = $MinUsefulRpsDeltaPercent
            max_p99_regression_percent = $MaxP99RegressionPercent
            max_503_delta_percentage_points = $Max503DeltaPercentagePoints
        }
    }
    $summary | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath (Join-Path $ResultsDir "gate-summary.json") -Encoding utf8

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("# Published Artifact Upgrade Gate")
    $lines.Add("")
    $lines.Add("The same application source and JVM/container limits were used. The baseline image contains $BaselineTag native artifacts; the candidate contains the new native runtime and enabled micro agent.")
    $lines.Add("CPU slots were crossed in AB/BA phases.")
    $lines.Add("")
    $lines.Add("- Resident crossover gate: $(if ($residentPass) { 'PASS' } else { 'BLOCKED' })")
    $lines.Add("- Startup gate: $($startup.gate)")
    $lines.Add("")
    $lines.Add("| Endpoint class | C | Pairs | Process RSS old/new MiB | RSS delta | Container delta | Useful 200 RPS delta | p99 delta | 503 delta | Gate |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    foreach ($row in $rows | Sort-Object endpoint_class, {[int]$_.concurrency}) {
        $lines.Add("| $($row.endpoint_class) | $($row.concurrency) | $($row.pairs) | $($row.baseline_median_process_rss_mib)/$($row.candidate_median_process_rss_mib) | $($row.process_rss_delta_mib) | $($row.container_memory_delta_mib) | $($row.useful_200_rps_delta_pct)% | $($row.p99_delta_pct)% | $($row.crossover_503_delta_pp) pp | $($row.gate) |")
    }
    $lines.Add("")
    $lines.Add("| Startup metric | Old median | New + agent median | Paired delta | Gate |")
    $lines.Add("|---|---:|---:|---:|---|")
    $lines.Add("| Internal ready | $($startup.baseline_median_ready_ms) ms | $($startup.candidate_median_ready_ms) ms | $($startup.median_paired_ready_delta_pct)% | $($startup.gate) |")
    $lines.Add("| HTTP reachable | $($startup.baseline_median_reachable_ms) ms | $($startup.candidate_median_reachable_ms) ms | $($startup.median_paired_reachable_delta_pct)% | $($startup.gate) |")
    $lines.Add("")
    $lines.Add("Overall artifact gate: **$(if ($passed) { 'PASS' } else { 'BLOCKED' })**")
    $lines | Set-Content -LiteralPath (Join-Path $ResultsDir "REPORT.md") -Encoding utf8
    return $passed
}

$gatePassed = $false
try {
    if (-not $SkipBuild) {
        Invoke-Checked git @("worktree", "add", "--detach", $BaselineWorktree, $BaselineTag) $FrameworkRoot | Out-Null
        $worktreeCreated = $true
        Invoke-Checked mvn @("-q", "-DskipTests", "package") $BaselineWorktree | Out-Null
        Invoke-Checked mvn @("-q", "-DskipTests", "package") $FrameworkRoot | Out-Null
        Invoke-Checked mvn @("-q", "package") $ProjectRoot | Out-Null
        Invoke-Checked mvn @("-q", "-DskipTests", "package") $MockRoot | Out-Null

        Prepare-Context $BaselineWorktree
        Invoke-Checked docker @("build", "-t", $BaselineImage, "-f", "app.Dockerfile", ".") $ScriptDir | Out-Null
        Prepare-Context $FrameworkRoot
        Invoke-Checked docker @("build", "-t", $CandidateImage, "-f", "app.Dockerfile", ".") $ScriptDir | Out-Null
        Invoke-Checked docker @("build", "-t", $CollectorImage, ".") $MockRoot | Out-Null
        Invoke-Checked docker @("build", "-t", $RunnerImage, "-f", "Dockerfile.benchmark", ".") `
                (Join-Path $FrameworkRoot "benchmark") | Out-Null
    } else {
        foreach ($image in $BaselineImage, $CandidateImage, $CollectorImage, $RunnerImage) {
            Invoke-Checked docker @("image", "inspect", $image) $ProjectRoot | Out-Null
        }
    }

    Assert-ReactorBenchmarkCpuIsolation `
            -RunnerImage $RunnerImage `
            -SlotACpuSet $SlotACpuSet `
            -SlotBCpuSet $SlotBCpuSet `
            -RunnerCpuSet $RunnerCpuSet `
            -CollectorCpuSet $CollectorCpuSet

    if (-not $SkipHostPreflight) {
        if ($HostStabilizationSeconds -gt 0) {
            Start-Sleep -Seconds $HostStabilizationSeconds
        }
        Assert-ReactorHostBenchmarkReadiness `
                -MaxAverageCpuPercent $MaxHostCpuAveragePercent `
                -MaxPeakCpuPercent $MaxHostCpuPeakPercent `
                -MinFreeVirtualMiB $MinHostFreeVirtualMiB
    }

    New-Item -ItemType Directory -Force -Path $ResultsDir, $ResidentResults | Out-Null

    Invoke-Checked docker @("network", "create", $Network) $ProjectRoot | Out-Null
    $networkCreated = $true
    $collectorReport = Join-Path $ResultsDir "mock-collector.json"
    $mount = "{0}:/reports" -f ([IO.Path]::GetFullPath($ResultsDir))
    $collectorArgs = @(
        "run", "-d", "--name", $CollectorContainer, "--network", $Network,
        "--cpus", "0.5", "--memory", "128m", "-v", $mount,
        "-e", "MOCK_COLLECTOR_REPORT=/reports/mock-collector.json"
    )
    if (-not [string]::IsNullOrWhiteSpace($CollectorCpuSet)) {
        $collectorArgs += @("--cpuset-cpus", $CollectorCpuSet)
    }
    $collectorArgs += $CollectorImage
    Invoke-Checked docker $collectorArgs $ProjectRoot | Out-Null
    Wait-ContainerLog $CollectorContainer "Glowroot mock collector ready"

    $baselineOptions = "-Dreactor.glowroot.enabled=false"
    $collectorAddress = "$(Get-ReactorContainerNetworkIp `
            -Container $CollectorContainer -Network $Network):8181"
    $candidateOptions = Get-NativePropertyOptions -CollectorAddress $collectorAddress
    foreach ($concurrency in $ConcurrencyValues) {
        & (Join-Path $FrameworkRoot "benchmark\resident_crossover_gate.ps1") `
                -BaselineImage $BaselineImage `
                -CandidateImage $CandidateImage `
                -ResultsDir (Join-Path $ResidentResults "c$concurrency") `
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
            throw "Artifact resident crossover failed at concurrency $concurrency."
        }
    }

    & (Join-Path $FrameworkRoot "benchmark\image_startup_gate.ps1") `
            -BaselineImage $BaselineImage `
            -CandidateImage $CandidateImage `
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
        throw "Artifact startup gate execution failed."
    }

    $gatePassed = Write-Report
    if (Test-Path -LiteralPath $collectorReport) {
        Copy-Item -LiteralPath $collectorReport -Destination (Join-Path $ResultsDir "collector-final.json")
    }
} finally {
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $CollectorContainer }) {
        & docker logs $CollectorContainer *> (Join-Path $ResultsDir "mock-collector.log")
    }
    Remove-Container $CollectorContainer
    if ($networkCreated) {
        & docker network rm $Network *> $null
    }
    if ($worktreeCreated) {
        & git -C $FrameworkRoot worktree remove --force $BaselineWorktree *> $null
    }
}

Write-Host "Artifact upgrade report: $(Join-Path $ResultsDir 'REPORT.md')"
if ($FailOnGate -and -not $gatePassed) {
    exit 1
}
