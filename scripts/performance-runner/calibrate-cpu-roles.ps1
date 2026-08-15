[CmdletBinding()]
param(
    [ValidateRange(8, 30)]
    [int] $Rounds = 18,
    [ValidateRange(1, 5)]
    [int] $SecondsPerSample = 1,
    [string] $OutputPath = ""
)

$ErrorActionPreference = "Stop"
if (-not $IsLinux) {
    throw "CPU role calibration must run on the native Linux performance host."
}
foreach ($command in @("openssl", "taskset")) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is missing: $command"
    }
}

$benchmarkRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
. (Join-Path $benchmarkRoot "benchmark/gate_statistics.ps1")
. (Join-Path $benchmarkRoot "benchmark/warmup_statistics.ps1")
. (Join-Path $benchmarkRoot "benchmark/benchmark_isolation.ps1")

$groupMap = @{}
foreach ($path in Get-ChildItem -Path "/sys/devices/system/cpu" -Directory) {
    if ($path.Name -notmatch "^cpu([0-9]+)$") { continue }
    $topology = Join-Path $path.FullName "topology/thread_siblings_list"
    if (-not (Test-Path -LiteralPath $topology -PathType Leaf)) { continue }
    $normalized = ConvertTo-ReactorNormalizedCpuSet -CpuSet (Get-Content -Raw -LiteralPath $topology)
    $groupMap[$normalized] = @(ConvertFrom-ReactorCpuSet -CpuSet $normalized)
}
$groups = @($groupMap.GetEnumerator() | ForEach-Object {
        [pscustomobject]@{
            cpu_set = $_.Key
            cpus = @($_.Value)
            first_cpu = [int] (@($_.Value | Sort-Object)[0])
            samples = [Collections.Generic.List[double]]::new()
        }
    } | Sort-Object first_cpu)
if ($groups.Count -lt 2) {
    throw "At least two physical CPU groups are required."
}

Write-Host "Calibrating $($groups.Count) physical CPU groups with $Rounds interleaved rounds."
for ($round = 0; $round -lt $Rounds; $round++) {
    $offset = $round % $groups.Count
    $order = @($groups[$offset..($groups.Count - 1)])
    if ($offset -gt 0) {
        $order += @($groups[0..($offset - 1)])
    }
    foreach ($group in $order) {
        $output = & taskset -c $group.cpu_set openssl speed `
            -seconds $SecondsPerSample -bytes 16384 sha256 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "OpenSSL calibration failed for CPU group $($group.cpu_set): $($output -join ' ')"
        }
        $throughput = $null
        foreach ($line in $output) {
            if ("$line" -match '^sha256\s+([0-9]+(?:\.[0-9]+)?)k\s*$') {
                $throughput = [double] $Matches[1]
            }
        }
        if ($null -eq $throughput) {
            throw "Cannot parse OpenSSL SHA-256 throughput for CPU group $($group.cpu_set)."
        }
        $group.samples.Add($throughput)
    }
}

$results = foreach ($group in $groups) {
    $decision = Get-ReactorWarmupDecision `
        -Samples @($group.samples) `
        -WindowRounds 3 `
        -MaximumRobustTrendPercent 3 `
        -MaximumBorderlineRobustTrendPercent 5 `
        -MaximumBorderlineMedianShiftPercent 3 `
        -MaximumMedianAbsoluteDeviationPercent 4
    [pscustomobject]@{
        cpu_set = $group.cpu_set
        logical_cpu_count = $group.cpus.Count
        median_kib_per_second = [math]::Round((Get-ReactorMedian -Values @($group.samples)), 3)
        robust_trend_percent = [math]::Round($decision.robust_trend_pct, 3)
        median_absolute_deviation_percent = [math]::Round($decision.median_absolute_deviation_pct, 3)
        passed = [bool] $decision.passed
        samples_kib_per_second = @($group.samples)
    }
}
$passing = @($results | Where-Object { $_.passed } |
        Sort-Object `
            @{ Expression = "median_absolute_deviation_percent"; Descending = $false }, `
            @{ Expression = "robust_trend_percent"; Descending = $false }, `
            @{ Expression = "median_kib_per_second"; Descending = $true })
$application = $passing | Select-Object -First 1
$runnerGroup = $passing | Where-Object { $_.cpu_set -ne $application.cpu_set } |
    Select-Object -First 1
$collectorGroup = $passing | Where-Object {
        $_.cpu_set -ne $application.cpu_set -and $_.cpu_set -ne $runnerGroup.cpu_set
    } | Select-Object -First 1
if ($null -eq $application -or $null -eq $runnerGroup -or $null -eq $collectorGroup) {
    $results | Format-Table cpu_set, median_kib_per_second, robust_trend_percent, median_absolute_deviation_percent, passed
    throw "Calibration needs three stable physical CPU groups for application, load runner, and collector isolation."
}
$runnerCpus = @(ConvertFrom-ReactorCpuSet -CpuSet $runnerGroup.cpu_set)
$collectorCpus = @(ConvertFrom-ReactorCpuSet -CpuSet $collectorGroup.cpu_set)
$recommendation = [pscustomobject]@{
    application_cpu_set = $application.cpu_set
    runner_cpu_set = "$($runnerCpus[0])"
    collector_cpu_set = "$($collectorCpus[0])"
}
$evidence = [pscustomobject]@{
    generated_at_utc = [DateTime]::UtcNow.ToString("o")
    rounds = $Rounds
    seconds_per_sample = $SecondsPerSample
    results = @($results)
    recommendation = $recommendation
}

$results | Format-Table cpu_set, median_kib_per_second, robust_trend_percent, median_absolute_deviation_percent, passed
Write-Host ""
Write-Host "Recommended calibrated roles:"
Write-Host "  --application-cpus $($recommendation.application_cpu_set) --runner-cpu $($recommendation.runner_cpu_set) --collector-cpu $($recommendation.collector_cpu_set)"
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Write-Host "Calibration evidence: $OutputPath"
}
