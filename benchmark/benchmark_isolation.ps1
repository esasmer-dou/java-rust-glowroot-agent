function ConvertFrom-ReactorCpuSet {
    param([string] $CpuSet)

    $cpus = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($token in ($CpuSet -split ",")) {
        $value = $token.Trim()
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        if ($value -match "^([0-9]+)-([0-9]+)$") {
            $start = [int] $Matches[1]
            $end = [int] $Matches[2]
            if ($end -lt $start) {
                throw "Invalid CPU set range: $value"
            }
            foreach ($cpu in $start..$end) {
                [void] $cpus.Add($cpu)
            }
            continue
        }
        if ($value -notmatch "^[0-9]+$") {
            throw "Unsupported CPU set token: $value"
        }
        [void] $cpus.Add([int] $value)
    }
    return @($cpus | Sort-Object)
}

function Get-ReactorLinuxCpuSnapshot {
    if (-not $IsLinux) {
        throw "Linux CPU counters are available only on Linux."
    }
    $snapshot = @{}
    foreach ($line in Get-Content -LiteralPath "/proc/stat") {
        $parts = @($line.Trim() -split "\s+")
        if ($parts.Count -lt 9 -or $parts[0] -notmatch "^cpu([0-9]+)$") {
            continue
        }
        $values = @($parts[1..($parts.Count - 1)] | ForEach-Object { [double] $_ })
        $total = ($values | Measure-Object -Sum).Sum
        $idle = $values[3] + $values[4]
        $snapshot[[int] $Matches[1]] = [pscustomobject]@{
            total = [double] $total
            busy = [double] ($total - $idle)
            steal = [double] $values[7]
        }
    }
    if ($snapshot.Count -eq 0) {
        throw "Cannot read per-CPU counters from /proc/stat."
    }
    return $snapshot
}

function Get-ReactorLinuxPhysicalCpuSet {
    param([string] $CpuSet)

    $cpus = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($cpu in @(ConvertFrom-ReactorCpuSet -CpuSet $CpuSet)) {
        $path = "/sys/devices/system/cpu/cpu$cpu/topology/thread_siblings_list"
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "CPU sibling topology is missing for CPU $cpu."
        }
        foreach ($sibling in @(ConvertFrom-ReactorCpuSet -CpuSet (Get-Content -Raw -LiteralPath $path))) {
            [void] $cpus.Add($sibling)
        }
    }
    return @($cpus | Sort-Object)
}

function Get-ReactorLinuxCpuWindow {
    param(
        [hashtable] $Before,
        [hashtable] $After,
        [int[]] $Cpus
    )

    [double] $total = 0
    [double] $busy = 0
    [double] $steal = 0
    foreach ($cpu in $Cpus) {
        if (-not $Before.ContainsKey($cpu) -or -not $After.ContainsKey($cpu)) {
            throw "CPU $cpu disappeared while sampling /proc/stat."
        }
        $total += $After[$cpu].total - $Before[$cpu].total
        $busy += $After[$cpu].busy - $Before[$cpu].busy
        $steal += $After[$cpu].steal - $Before[$cpu].steal
    }
    if ($total -le 0) {
        throw "Linux CPU counter window did not advance."
    }
    return [pscustomobject]@{
        busy_percent = 100.0 * $busy / $total
        steal_percent = 100.0 * $steal / $total
    }
}

function Select-ReactorBenchmarkCpuRoles {
    param([int] $SampleSeconds = 5)

    if (-not $IsLinux) {
        throw "Automatic benchmark CPU selection requires Linux."
    }
    if ($SampleSeconds -lt 2) {
        throw "CPU role selection requires at least two seconds."
    }

    $groups = @{}
    foreach ($path in Get-ChildItem -Path "/sys/devices/system/cpu" -Directory) {
        if ($path.Name -notmatch "^cpu([0-9]+)$") { continue }
        $cpu = [int] $Matches[1]
        $topology = Join-Path $path.FullName "topology/thread_siblings_list"
        if (-not (Test-Path -LiteralPath $topology -PathType Leaf)) { continue }
        $group = (Get-Content -Raw -LiteralPath $topology).Trim()
        if (-not $groups.ContainsKey($group)) {
            $groups[$group] = [System.Collections.Generic.List[int]]::new()
        }
        $groups[$group].Add($cpu)
    }
    if ($groups.Count -lt 2) {
        throw "At least two physical CPU groups are required for the production benchmark."
    }

    $before = Get-ReactorLinuxCpuSnapshot
    Start-Sleep -Seconds $SampleSeconds
    $after = Get-ReactorLinuxCpuSnapshot
    $ranked = foreach ($entry in $groups.GetEnumerator()) {
        $cpus = @($entry.Value | Sort-Object)
        $window = Get-ReactorLinuxCpuWindow -Before $before -After $after -Cpus $cpus
        $logical = foreach ($cpu in $cpus) {
            $cpuWindow = Get-ReactorLinuxCpuWindow -Before $before -After $after -Cpus @($cpu)
            [pscustomobject]@{ cpu = $cpu; busy_percent = $cpuWindow.busy_percent }
        }
        [pscustomobject]@{
            group = $entry.Key
            cpus = $cpus
            busy_percent = $window.busy_percent
            steal_percent = $window.steal_percent
            logical = @($logical | Sort-Object busy_percent, cpu)
        }
    }
    $ranked = @($ranked | Sort-Object busy_percent, steal_percent, group)
    $application = $ranked[0]
    $load = $ranked[1]
    # Reserve the complete SMT sibling group for the application. Pinning only one logical CPU
    # leaves its sibling available to unrelated host work, which can steal execution resources from
    # the same physical core and create false A/B regressions. --cpus still enforces the requested
    # aggregate CPU quota inside this bounded cpuset.
    $applicationCpuSet = $application.cpus -join ","
    $runnerCpu = $load.logical[0].cpu
    $collectorCpu = if ($load.logical.Count -gt 1) { $load.logical[1].cpu } else { $runnerCpu }

    Write-Host (("CPU roles selected after build: app={0} group={1} busy={2:N2}% steal={3:N2}%; " +
            "runner={4} collector={5} group={6}") -f `
            $applicationCpuSet, $application.group, $application.busy_percent, $application.steal_percent, `
            $runnerCpu, $collectorCpu, $load.group)
    return [pscustomobject]@{
        application = $applicationCpuSet
        runner = "$runnerCpu"
        collector = "$collectorCpu"
        application_group = "$($application.group)"
        observed_busy_percent = [math]::Round($application.busy_percent, 3)
        observed_steal_percent = [math]::Round($application.steal_percent, 3)
    }
}

function Set-ReactorCurrentProcessCpuAffinity {
    param([string] $CpuSet)

    if (-not $IsLinux) { return }
    $output = & taskset -pc $CpuSet $PID 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot pin benchmark orchestrator to CPU set ${CpuSet}: $($output -join ' ')"
    }
    Write-Host "Benchmark orchestrator pinned to CPU set $CpuSet."
}

function Get-ReactorCpuSiblingGroups {
    param([string] $RunnerImage)

    $probe = 'for path in /sys/devices/system/cpu/cpu[0-9]*; do n=${path##*cpu}; printf "cpu%s " "$n"; cat "$path/topology/thread_siblings_list"; done'
    $output = & docker run --rm --entrypoint sh $RunnerImage -c $probe 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot inspect Docker CPU topology:`n$($output -join "`n")"
    }
    $groups = @{}
    foreach ($line in $output) {
        if ("$line" -match "^cpu([0-9]+)\s+([^\s]+)$") {
            $groups[[int] $Matches[1]] = $Matches[2]
        }
    }
    if ($groups.Count -eq 0) {
        throw "Docker did not expose CPU sibling topology. Run the gate on a Linux node with /sys topology available."
    }
    return $groups
}

function Assert-ReactorBenchmarkCpuIsolation {
    param(
        [string] $RunnerImage,
        [string] $SlotACpuSet,
        [string] $SlotBCpuSet,
        [string] $SlotCCpuSet = "",
        [string] $RunnerCpuSet,
        [string] $CollectorCpuSet,
        [switch] $AllowRunnerCollectorSiblingSharing,
        [switch] $AllowSharedApplicationSlots
    )

    $topology = Get-ReactorCpuSiblingGroups -RunnerImage $RunnerImage
    $roles = [ordered]@{
        "application-slot-a" = $SlotACpuSet
    }
    if (-not ($AllowSharedApplicationSlots -and $SlotACpuSet -eq $SlotBCpuSet)) {
        $roles["application-slot-b"] = $SlotBCpuSet
    }
    if (-not [string]::IsNullOrWhiteSpace($SlotCCpuSet)) {
        $roles["application-slot-c"] = $SlotCCpuSet
    }
    $roles["load-runner"] = $RunnerCpuSet
    $roles["collector"] = $CollectorCpuSet
    $physicalCoreOwners = @{}
    $logicalCpuOwners = @{}
    foreach ($entry in $roles.GetEnumerator()) {
        $roleCpus = @(ConvertFrom-ReactorCpuSet -CpuSet $entry.Value)
        if ($roleCpus.Count -eq 0) {
            throw "CPU isolation requires an explicit non-empty CPU set for $($entry.Key)."
        }
        foreach ($cpu in $roleCpus) {
            if (-not $topology.ContainsKey($cpu)) {
                throw "CPU $cpu from $($entry.Key) is not available to Docker."
            }
            if ($logicalCpuOwners.ContainsKey($cpu) -and $logicalCpuOwners[$cpu] -ne $entry.Key) {
                $existingRole = $logicalCpuOwners[$cpu]
                $runnerCollectorPair = @($entry.Key, $existingRole) -contains "load-runner" `
                        -and @($entry.Key, $existingRole) -contains "collector"
                if (-not ($AllowRunnerCollectorSiblingSharing -and $runnerCollectorPair)) {
                    throw "CPU isolation is invalid: $($entry.Key) and $existingRole use logical CPU $cpu."
                }
            }
            $logicalCpuOwners[$cpu] = $entry.Key
            $physicalCore = "$($topology[$cpu])"
            if ($physicalCoreOwners.ContainsKey($physicalCore) `
                    -and $physicalCoreOwners[$physicalCore] -ne $entry.Key) {
                $existingRole = $physicalCoreOwners[$physicalCore]
                $runnerCollectorPair = @($entry.Key, $existingRole) -contains "load-runner" `
                    -and @($entry.Key, $existingRole) -contains "collector"
                if (-not ($AllowRunnerCollectorSiblingSharing -and $runnerCollectorPair)) {
                    throw "CPU isolation is invalid: $($entry.Key) and $existingRole share physical-core sibling group $physicalCore."
                }
            } else {
                $physicalCoreOwners[$physicalCore] = $entry.Key
            }
        }
    }
    $slotCMessage = if ([string]::IsNullOrWhiteSpace($SlotCCpuSet)) { "" } else { " C=$SlotCCpuSet" }
    $sharedMessage = if ($AllowRunnerCollectorSiblingSharing) { " runner/collector-SMT-sharing=allowed" } else { "" }
    $applicationMessage = if ($AllowSharedApplicationSlots -and $SlotACpuSet -eq $SlotBCpuSet) {
        " sequential-application-slot=$SlotACpuSet"
    } else {
        " A=$SlotACpuSet B=$SlotBCpuSet"
    }
    Write-Host "CPU isolation verified:$applicationMessage$slotCMessage runner=$RunnerCpuSet collector=$CollectorCpuSet$sharedMessage"
}

function Get-ReactorContainerNetworkIp {
    param(
        [string] $Container,
        [string] $Network
    )

    $inspection = @((& docker inspect $Container 2>&1) | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0 -or $null -eq $inspection) {
        throw "Cannot inspect container network address: $Container"
    }
    $networkProperty = $inspection.NetworkSettings.Networks.PSObject.Properties[$Network]
    if ($null -eq $networkProperty -or [string]::IsNullOrWhiteSpace($networkProperty.Value.IPAddress)) {
        throw "Container $Container has no IPv4 address on network $Network."
    }
    return "$($networkProperty.Value.IPAddress)"
}

function Assert-ReactorHostBenchmarkReadiness {
    param(
        [int] $SampleCount = 10,
        [double] $MaxAverageCpuPercent = 15.0,
        [double] $MaxPeakCpuPercent = 40.0,
        [double] $MaxStealCpuPercent = 1.0,
        [double] $MinFreeVirtualMiB = 3072.0,
        [string] $CpuSet = ""
    )

    if ($SampleCount -lt 3) {
        throw "Host benchmark preflight requires at least three CPU samples."
    }

    [double] $stealCpu = 0
    if ($IsLinux) {
        $available = Get-ReactorLinuxCpuSnapshot
        $cpus = if ([string]::IsNullOrWhiteSpace($CpuSet)) {
            @($available.Keys | Sort-Object)
        } else {
            @(Get-ReactorLinuxPhysicalCpuSet -CpuSet $CpuSet)
        }
        $cpu = [System.Collections.Generic.List[double]]::new()
        $steal = [System.Collections.Generic.List[double]]::new()
        $before = $available
        foreach ($sample in 1..$SampleCount) {
            Start-Sleep -Seconds 1
            $after = Get-ReactorLinuxCpuSnapshot
            $window = Get-ReactorLinuxCpuWindow -Before $before -After $after -Cpus $cpus
            $cpu.Add($window.busy_percent)
            $steal.Add($window.steal_percent)
            $before = $after
        }
        $averageCpu = [math]::Round((($cpu | Measure-Object -Average).Average), 2)
        $peakCpu = [math]::Round((($cpu | Measure-Object -Maximum).Maximum), 2)
        $stealCpu = [math]::Round((($steal | Measure-Object -Maximum).Maximum), 2)
        $memInfo = Get-Content -LiteralPath "/proc/meminfo"
        $availableLine = $memInfo | Where-Object { $_ -match '^MemAvailable:\s+([0-9]+)\s+kB$' } |
                Select-Object -First 1
        if ($null -eq $availableLine) { throw "Cannot read MemAvailable from /proc/meminfo." }
        [void] ($availableLine -match '^MemAvailable:\s+([0-9]+)\s+kB$')
        $freeVirtualMiB = [math]::Round(([double] $Matches[1] / 1024.0), 2)
    } elseif ($IsWindows) {
        $counter = Get-Counter '\Processor(_Total)\% Processor Time' `
                -SampleInterval 1 -MaxSamples $SampleCount
        $cpu = @($counter.CounterSamples | ForEach-Object { [double] $_.CookedValue })
        $averageCpu = [math]::Round((($cpu | Measure-Object -Average).Average), 2)
        $peakCpu = [math]::Round((($cpu | Measure-Object -Maximum).Maximum), 2)
        $os = Get-CimInstance Win32_OperatingSystem
        $freeVirtualMiB = [math]::Round(([double] $os.FreeVirtualMemory / 1024.0), 2)
    } else {
        throw "Host benchmark preflight supports Windows and Linux only."
    }

    Write-Host "Host benchmark preflight: cpus=$CpuSet avg-cpu=$averageCpu% peak-cpu=$peakCpu% max-steal=$stealCpu% free-memory=$freeVirtualMiB MiB"
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($averageCpu -gt $MaxAverageCpuPercent) {
        $failures.Add("average CPU $averageCpu% exceeds $MaxAverageCpuPercent%")
    }
    if ($peakCpu -gt $MaxPeakCpuPercent) {
        $failures.Add("peak CPU $peakCpu% exceeds $MaxPeakCpuPercent%")
    }
    if ($stealCpu -gt $MaxStealCpuPercent) {
        $failures.Add("steal CPU $stealCpu% exceeds $MaxStealCpuPercent%")
    }
    if ($freeVirtualMiB -lt $MinFreeVirtualMiB) {
        $failures.Add("free virtual memory $freeVirtualMiB MiB is below $MinFreeVirtualMiB MiB")
    }
    if ($failures.Count -gt 0) {
        throw "Host is not quiet enough for a release-grade benchmark: $($failures -join '; '). Close or isolate host workloads, then rerun."
    }
    return [pscustomobject]@{
        cpu_set = $CpuSet
        average_cpu_percent = $averageCpu
        peak_cpu_percent = $peakCpu
        max_steal_percent = $stealCpu
        free_memory_mib = $freeVirtualMiB
    }
}
