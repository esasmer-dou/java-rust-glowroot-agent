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
        [double] $MinFreeVirtualMiB = 3072.0
    )

    if (-not $IsWindows) {
        Write-Host "Host benchmark preflight: Windows host counters not required on this runner."
        return
    }
    if ($SampleCount -lt 3) {
        throw "Host benchmark preflight requires at least three CPU samples."
    }

    $counter = Get-Counter '\Processor(_Total)\% Processor Time' `
            -SampleInterval 1 -MaxSamples $SampleCount
    $cpu = @($counter.CounterSamples | ForEach-Object { [double] $_.CookedValue })
    $averageCpu = [math]::Round((($cpu | Measure-Object -Average).Average), 2)
    $peakCpu = [math]::Round((($cpu | Measure-Object -Maximum).Maximum), 2)
    $os = Get-CimInstance Win32_OperatingSystem
    $freeVirtualMiB = [math]::Round(([double] $os.FreeVirtualMemory / 1024.0), 2)

    Write-Host "Host benchmark preflight: avg-cpu=$averageCpu% peak-cpu=$peakCpu% free-virtual=$freeVirtualMiB MiB"
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($averageCpu -gt $MaxAverageCpuPercent) {
        $failures.Add("average CPU $averageCpu% exceeds $MaxAverageCpuPercent%")
    }
    if ($peakCpu -gt $MaxPeakCpuPercent) {
        $failures.Add("peak CPU $peakCpu% exceeds $MaxPeakCpuPercent%")
    }
    if ($freeVirtualMiB -lt $MinFreeVirtualMiB) {
        $failures.Add("free virtual memory $freeVirtualMiB MiB is below $MinFreeVirtualMiB MiB")
    }
    if ($failures.Count -gt 0) {
        throw "Host is not quiet enough for a release-grade benchmark: $($failures -join '; '). Close or isolate host workloads, then rerun."
    }
}
