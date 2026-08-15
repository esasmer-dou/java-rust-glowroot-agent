[CmdletBinding()]
param(
    [ValidateSet("reactor-performance-native-linux", "reactor-wsl-smoke")]
    [string] $RunnerClass = "reactor-performance-native-linux",
    [string] $EvidencePath = "",
    [int] $MinLogicalCpu = 8,
    [double] $MinMemoryGiB = 12,
    [double] $MinFreeDiskGiB = 20,
    [switch] $AllowWslForSmoke
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if (-not $IsLinux) {
    throw "The performance runner preflight supports Linux only."
}
if ($RunnerClass -eq "reactor-performance-native-linux" -and $AllowWslForSmoke) {
    throw "A production runner cannot enable AllowWslForSmoke."
}

$checks = [Collections.Generic.List[object]]::new()
function Add-Check([string] $Name, [bool] $Passed, [string] $Observed, [string] $Required) {
    $checks.Add([ordered]@{
        name = $Name
        passed = $Passed
        observed = $Observed
        required = $Required
    })
}

$kernelRelease = (& uname -r 2>&1) -join " "
$kernelVersion = Get-Content -Raw -LiteralPath "/proc/version"
$isWsl = $kernelVersion -match "(?i)microsoft|wsl"
$wslAllowed = $AllowWslForSmoke -and $RunnerClass -eq "reactor-wsl-smoke"
Add-Check "native_linux_kernel" (-not $isWsl -or $wslAllowed) $kernelRelease `
        $(if ($wslAllowed) { "Linux or WSL smoke" } else { "Linux kernel without WSL" })

$containerType = "none"
if (Get-Command systemd-detect-virt -ErrorAction SilentlyContinue) {
    $detectedContainer = (& systemd-detect-virt --container 2>$null) -join " "
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($detectedContainer) `
            -and $detectedContainer.Trim() -ne "none") {
        $containerType = $detectedContainer.Trim()
    }
}
$isContainerized = $containerType -ne "none" -and $containerType -ne "wsl"
Add-Check "host_not_containerized" (-not $isContainerized) $containerType "none or WSL smoke classification"

$memInfo = Get-Content -LiteralPath "/proc/meminfo"
$memTotalLine = $memInfo | Where-Object { $_ -match '^MemTotal:\s+([0-9]+)\s+kB$' } |
        Select-Object -First 1
if ($null -eq $memTotalLine) { throw "Cannot read MemTotal from /proc/meminfo." }
[void] ($memTotalLine -match '^MemTotal:\s+([0-9]+)\s+kB$')
$hostMemoryGiB = [math]::Round(([double] $Matches[1] / 1024.0 / 1024.0), 2)
Add-Check "host_memory" ($hostMemoryGiB -ge $MinMemoryGiB) "$hostMemoryGiB GiB" ">= $MinMemoryGiB GiB"

$logicalCpu = [Environment]::ProcessorCount
Add-Check "host_logical_cpu" ($logicalCpu -ge $MinLogicalCpu) "$logicalCpu" ">= $MinLogicalCpu"

$physicalGroups = [Collections.Generic.HashSet[string]]::new()
foreach ($topology in Get-ChildItem -Path "/sys/devices/system/cpu/cpu*/topology/thread_siblings_list" `
        -File -ErrorAction SilentlyContinue) {
    [void] $physicalGroups.Add((Get-Content -Raw -LiteralPath $topology.FullName).Trim())
}
Add-Check "physical_cpu_groups" ($physicalGroups.Count -ge 4) "$($physicalGroups.Count)" ">= 4"

$governors = @(
    Get-ChildItem -Path "/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor" `
            -File -ErrorAction SilentlyContinue |
        ForEach-Object { (Get-Content -Raw -LiteralPath $_.FullName).Trim() } |
        Sort-Object -Unique
)
$governorObserved = if ($governors.Count -eq 0) { "not-exposed" } else { $governors -join "," }
$governorPassed = $governors.Count -eq 0 -or ($governors.Count -eq 1 -and $governors[0] -eq "performance")
Add-Check "cpu_frequency_policy" $governorPassed $governorObserved "performance or not exposed by a dedicated VM"

$swapTotalLine = $memInfo | Where-Object { $_ -match '^SwapTotal:\s+([0-9]+)\s+kB$' } | Select-Object -First 1
$swapFreeLine = $memInfo | Where-Object { $_ -match '^SwapFree:\s+([0-9]+)\s+kB$' } | Select-Object -First 1
[void] ($swapTotalLine -match '^SwapTotal:\s+([0-9]+)\s+kB$')
$swapTotalKiB = [int64] $Matches[1]
[void] ($swapFreeLine -match '^SwapFree:\s+([0-9]+)\s+kB$')
$swapFreeKiB = [int64] $Matches[1]
$swapUsedKiB = $swapTotalKiB - $swapFreeKiB
Add-Check "host_swap_unused" ($swapUsedKiB -eq 0 -or $wslAllowed) "$swapUsedKiB KiB used" `
        $(if ($wslAllowed) { "diagnostic for WSL smoke" } else { "0 KiB used" })

$rootDrive = [IO.DriveInfo]::new("/")
$freeDiskGiB = [math]::Round(($rootDrive.AvailableFreeSpace / 1GB), 2)
Add-Check "root_free_disk" ($freeDiskGiB -ge $MinFreeDiskGiB) "$freeDiskGiB GiB" ">= $MinFreeDiskGiB GiB"

$dockerJson = (& docker info --format '{{json .}}' 2>&1) -join ""
if ($LASTEXITCODE -ne 0) { throw "Docker is unavailable: $dockerJson" }
$dockerInfo = $dockerJson | ConvertFrom-Json
$dockerCpu = [int] $dockerInfo.NCPU
$dockerMemoryGiB = [math]::Round(([double] $dockerInfo.MemTotal / 1GB), 2)
Add-Check "docker_linux_engine" ($dockerInfo.OSType -eq "linux") "$($dockerInfo.OSType)" "linux"
Add-Check "docker_cpu" ($dockerCpu -ge $MinLogicalCpu) "$dockerCpu" ">= $MinLogicalCpu"
Add-Check "docker_memory" ($dockerMemoryGiB -ge $MinMemoryGiB) "$dockerMemoryGiB GiB" ">= $MinMemoryGiB GiB"

$javaVersion = (& java -version 2>&1) -join " "
$javaPassed = $javaVersion -match '(?i)openj9' -and $javaVersion -match 'version "21[\.]'
Add-Check "java_runtime" $javaPassed $javaVersion "Semeru OpenJ9 Java 21"

$mavenVersion = (& mvn -version 2>&1) -join " "
Add-Check "maven" ($LASTEXITCODE -eq 0) $mavenVersion "available"

$listenerCount = @(Get-Process -Name "Runner.Listener" -ErrorAction SilentlyContinue).Count
$singleListenerRequired = $RunnerClass -eq "reactor-performance-native-linux"
Add-Check "single_runner_listener" (-not $singleListenerRequired -or $listenerCount -eq 1) `
        "$listenerCount" $(if ($singleListenerRequired) { "exactly 1" } else { "diagnostic only" })

$failedChecks = @($checks | Where-Object { -not $_.passed })
$result = [ordered]@{
    schema = 1
    passed = $failedChecks.Count -eq 0
    runner_class = $RunnerClass
    runner_name = "$env:RUNNER_NAME"
    runner_group = "$env:RUNNER_GROUP"
    repository = "$env:GITHUB_REPOSITORY"
    commit = "$env:GITHUB_SHA"
    run_id = "$env:GITHUB_RUN_ID"
    job = "$env:GITHUB_JOB"
    observed_at_utc = [DateTime]::UtcNow.ToString("O")
    kernel_release = $kernelRelease
    wsl = $isWsl
    containerized = $isContainerized
    virtualization = if (Get-Command systemd-detect-virt -ErrorAction SilentlyContinue) {
        $value = (& systemd-detect-virt 2>$null) -join " "
        if ([string]::IsNullOrWhiteSpace($value)) { "none" } else { $value.Trim() }
    } else {
        "unknown"
    }
    logical_cpu = $logicalCpu
    physical_cpu_groups = $physicalGroups.Count
    host_memory_gib = $hostMemoryGiB
    docker_cpu = $dockerCpu
    docker_memory_gib = $dockerMemoryGiB
    free_disk_gib = $freeDiskGiB
    cpu_governors = $governorObserved
    runner_listener_count = $listenerCount
    checks = $checks
}

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot "results/runner-preflight/$RunnerClass.json"
}
$evidenceDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($EvidencePath))
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $EvidencePath -Encoding utf8

Write-Host "Runner preflight: class=$RunnerClass passed=$($result.passed) CPUs=$logicalCpu memory=$hostMemoryGiB GiB Docker=$dockerCpu/$dockerMemoryGiB GiB WSL=$isWsl container=$containerType"
if (-not $result.passed) {
    $details = $failedChecks | ForEach-Object { "$($_.name): observed=$($_.observed), required=$($_.required)" }
    throw "Performance runner preflight failed:`n$($details -join "`n")"
}
