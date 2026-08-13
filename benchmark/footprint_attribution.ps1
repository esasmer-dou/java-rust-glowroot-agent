[CmdletBinding()]
param(
    [int] $RepeatCount = 3,
    [int] $Concurrency = 256,
    [string] $Duration = "5s",
    [int] $RequestsPerEndpoint = 4096,
    [string] $ResultsDir = "",
    [string] $DisabledImage = "java-rust-glowroot-agent:benchmark-app",
    [string] $NativeImage = "java-rust-glowroot-agent:benchmark-app",
    [string] $JavaAgentImage = "java-rust-glowroot-agent:benchmark-app",
    [string] $SlotACpuSet = "0",
    [string] $SlotBCpuSet = "2",
    [string] $SlotCCpuSet = "4",
    [string] $RunnerCpuSet = "7",
    [string] $CollectorCpuSet = "6",
    [int] $MinimumProcessAgeSeconds = 75,
    [double] $MaxProcessAgeSpreadSeconds = 5.0,
    [double] $MaxMemoryRegressionMiB = 3.0,
    [int] $MaxThreadDelta = 0,
    [switch] $FailOnGate,
    [switch] $KeepContainers
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
[System.Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
[System.Threading.Thread]::CurrentThread.CurrentUICulture = [Globalization.CultureInfo]::InvariantCulture

if ($RepeatCount -lt 3 -or ($RepeatCount % 3) -ne 0) {
    throw "RepeatCount must be a multiple of 3 and at least 3 so every variant uses every CPU slot equally."
}
if ($MaxMemoryRegressionMiB -le 0 -or $MaxMemoryRegressionMiB -gt 3.0) {
    throw "MaxMemoryRegressionMiB must be greater than zero and cannot exceed the 3 MiB product boundary."
}
if ($RequestsPerEndpoint -lt 1) {
    throw "RequestsPerEndpoint must be positive."
}
if ($MinimumProcessAgeSeconds -lt 65) {
    throw "MinimumProcessAgeSeconds must be at least 65 so every enabled variant can complete its first 60-second export interval."
}
if ($MaxProcessAgeSpreadSeconds -le 0) {
    throw "MaxProcessAgeSpreadSeconds must be positive."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDir "benchmark_isolation.ps1")
$ProjectRoot = Split-Path -Parent $ScriptDir
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\footprint_attribution_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$CollectorImage = "java-rust-glowroot-agent:mock-collector"
$RunnerImage = "reactor-benchmark-runner:local"
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$network = "glowroot-attribution-$suffix"
$collector = "glowroot-attribution-collector-$suffix"
$runner = "glowroot-attribution-runner-$suffix"
$variants = @("disabled", "native-properties", "javaagent")
$slots = @($SlotACpuSet, $SlotBCpuSet, $SlotCCpuSet)
$rows = [System.Collections.Generic.List[object]]::new()
$collectorAddress = ""

$baseJavaOpts = @(
    "-Xms8m", "-Xmx40m", "-Xss256k", "-Xquickstart", "-Xtune:virtualized",
    "-Xshareclasses:none", "-XX:ActiveProcessorCount=1", "-Xgc:threads=1",
    "-XX:-TransparentHugePage", "-Dreactor.runtime.profile=micro-rest",
    "-Dreactor.rust.log.level=error", "-Dreactor.rust.java.log.level=warn",
    "-Dfile.encoding=UTF-8", "-Djava.security.egd=file:/dev/./urandom"
) -join " "

function Invoke-DockerChecked {
    param([string[]] $Arguments)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Remove-Container {
    param([string] $Name)
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name }) {
        & docker rm -f $Name *> $null
    }
}

function Get-ContainerName {
    param([string] $Variant, [int] $Phase)
    return "glowroot-attribution-$suffix-$Phase-$Variant"
}

function Get-VariantOptions {
    param([string] $Variant, [int] $Phase)
    $agentId = "java-rust-glowroot-agent::attribution::$Variant-$Phase"
    $common = @(
        "-Dreactor.glowroot.collector.address=$collectorAddress",
        "-Dreactor.glowroot.agent.id=$agentId",
        "-Dreactor.glowroot.application.name=glowroot-attribution",
        "-Dreactor.glowroot.http.sample-rate=256",
        "-Dreactor.glowroot.trace.capacity=0",
        "-Dreactor.glowroot.max-routes=64",
        "-Dreactor.glowroot.max-export-bytes=65536"
    ) -join " "
    switch ($Variant) {
        "disabled" { return "-Dreactor.glowroot.enabled=false" }
        "native-properties" {
            return "-Dreactor.glowroot.enabled=true $common"
        }
        "javaagent" {
            return "-javaagent:/app/agent.jar=collector=$collectorAddress,agent-id=$agentId," +
                "application=glowroot-attribution,http-sample-rate=256,trace-capacity=0," +
                "max-routes=64,max-export-bytes=65536"
        }
        default { throw "Unknown variant: $Variant" }
    }
}

function Get-VariantImage {
    param([string] $Variant)
    switch ($Variant) {
        "disabled" { return $DisabledImage }
        "native-properties" { return $NativeImage }
        "javaagent" { return $JavaAgentImage }
        default { throw "Unknown variant: $Variant" }
    }
}

function Wait-HttpReady {
    param([string] $Container)
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        & docker exec $runner curl -fsS "http://${Container}:8080/health" *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    $logs = & docker logs $Container 2>&1
    throw "$Container did not become ready:`n$($logs -join "`n")"
}

function Invoke-Load {
    param([string] $Container, [string] $Path)
    $output = & docker exec $runner load-probe `
        --url "http://${Container}:8080$Path" `
        --method GET `
        --concurrency $Concurrency `
        --duration $Duration `
        --requests $RequestsPerEndpoint `
        --timeout-ms 10000 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "load-probe failed for ${Container}${Path}:`n$($output -join "`n")"
    }
}

function Get-HttpBody {
    param([string] $Container, [string] $Path)
    $body = & docker exec $runner curl -fsS "http://${Container}:8080$Path" 2>$null
    if ($LASTEXITCODE -ne 0) {
        return ""
    }
    return ($body -join "`n")
}

function Wait-FirstExport {
    param([string] $Container)
    $deadline = (Get-Date).AddSeconds(75)
    while ((Get-Date) -lt $deadline) {
        $body = Get-HttpBody $Container "/diagnostics/glowroot"
        if (-not [string]::IsNullOrWhiteSpace($body)) {
            $diagnostics = $body | ConvertFrom-Json
            if ($diagnostics.export_success -ge 1) {
                return
            }
        }
        Start-Sleep -Seconds 1
    }
    throw "$Container did not complete a Glowroot export within 75 seconds."
}

function Wait-MinimumProcessAge {
    param([datetime] $StartedAtUtc)
    while ((([datetime]::UtcNow - $StartedAtUtc).TotalSeconds) -lt $MinimumProcessAgeSeconds) {
        Start-Sleep -Milliseconds 250
    }
}

function Read-Kb {
    param([string] $Text, [string] $Name)
    $match = [regex]::Match($Text, "(?m)^$([regex]::Escape($Name)):\s+([0-9]+)\s+kB")
    if ($match.Success) { return [int64] $match.Groups[1].Value }
    return 0L
}

function Read-CgroupBytes {
    param([string] $Text, [string] $Name)
    $match = [regex]::Match($Text, "(?m)^$([regex]::Escape($Name))\s+([0-9]+)")
    if ($match.Success) { return [int64] $match.Groups[1].Value }
    return 0L
}

function To-MiB {
    param([double] $Bytes)
    return [math]::Round($Bytes / 1048576.0, 3)
}

function Get-PathCategory {
    param([string] $Path)
    if ($Path -match "libinstrument") { return "java-instrumentation" }
    if ($Path -match "librust_hyper|rust_hyper") { return "rust-native-lib" }
    if ($Path -match "libj9|libjvm|libomr|j9jit|j9gc|j9vm|openj9|compressedrefs") {
        return "openj9-native-lib"
    }
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path -match "^\[anon") { return "anonymous" }
    if ($Path -eq "[heap]") { return "process-heap" }
    if ($Path.StartsWith("[stack")) { return "thread-stack" }
    if ($Path -match "^/usr/lib|^/lib|ld-linux|libc\.so|libpthread|libstdc\+\+|libgcc") {
        return "system-native-lib"
    }
    return "file-mapped-other"
}

function Get-SmapsCategories {
    param([string] $Text)
    $items = [System.Collections.Generic.List[object]]::new()
    $current = $null
    foreach ($line in ($Text -split "`n")) {
        $line = $line.TrimEnd("`r")
        if ($line -match "^[0-9a-fA-F]+-[0-9a-fA-F]+\s+") {
            if ($null -ne $current) { $items.Add([PSCustomObject] $current) }
            $parts = $line -split "\s+", 6
            $path = if ($parts.Count -ge 6) { $parts[5] } else { "" }
            $current = [ordered]@{ category = Get-PathCategory $path; rss_kb = 0L; pss_kb = 0L; private_dirty_kb = 0L; anonymous_kb = 0L }
            continue
        }
        if ($null -eq $current) { continue }
        if ($line -match "^(Rss|Pss|Private_Dirty|Anonymous):\s+([0-9]+)\s+kB") {
            $value = [int64] $Matches[2]
            switch ($Matches[1]) {
                "Rss" { $current.rss_kb = $value }
                "Pss" { $current.pss_kb = $value }
                "Private_Dirty" { $current.private_dirty_kb = $value }
                "Anonymous" { $current.anonymous_kb = $value }
            }
        }
    }
    if ($null -ne $current) { $items.Add([PSCustomObject] $current) }
    return @($items | Group-Object category | ForEach-Object {
        [PSCustomObject]@{
            category = $_.Name
            rss_mib = To-MiB ((($_.Group | Measure-Object rss_kb -Sum).Sum) * 1024.0)
            pss_mib = To-MiB ((($_.Group | Measure-Object pss_kb -Sum).Sum) * 1024.0)
            private_dirty_mib = To-MiB ((($_.Group | Measure-Object private_dirty_kb -Sum).Sum) * 1024.0)
            anonymous_mib = To-MiB ((($_.Group | Measure-Object anonymous_kb -Sum).Sum) * 1024.0)
        }
    })
}

function Get-CategoryValue {
    param([object[]] $Categories, [string] $Category, [string] $Property)
    $row = @($Categories | Where-Object category -eq $Category | Select-Object -First 1)
    if ($row.Count -eq 0) { return 0.0 }
    return [double] $row[0].$Property
}

function Collect-Evidence {
    param(
        [string] $Variant,
        [string] $Container,
        [int] $Phase,
        [string] $CpuSlot,
        [datetime] $StartedAtUtc
    )
    $dir = Join-Path $ResultsDir "phase-$Phase\$Variant"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $rollup = (& docker exec $Container sh -c "cat /proc/1/smaps_rollup") -join "`n"
    $smaps = (& docker exec $Container sh -c "cat /proc/1/smaps") -join "`n"
    $status = (& docker exec $Container sh -c "cat /proc/1/status") -join "`n"
    $cgroupCurrent = ((& docker exec $Container sh -c "cat /sys/fs/cgroup/memory.current") -join "`n").Trim()
    $cgroupStat = (& docker exec $Container sh -c "cat /sys/fs/cgroup/memory.stat") -join "`n"
    $diagnostics = Get-HttpBody $Container "/diagnostics/glowroot"
    $rollup | Set-Content (Join-Path $dir "smaps_rollup.txt") -Encoding utf8
    $smaps | Set-Content (Join-Path $dir "smaps.txt") -Encoding utf8
    $status | Set-Content (Join-Path $dir "status.txt") -Encoding utf8
    $cgroupStat | Set-Content (Join-Path $dir "cgroup_memory.stat") -Encoding utf8
    $diagnostics | Set-Content (Join-Path $dir "glowroot_diagnostics.json") -Encoding utf8
    $categories = Get-SmapsCategories $smaps
    $categories | Export-Csv (Join-Path $dir "smaps_categories.csv") -NoTypeInformation -Encoding utf8
    $threadMatch = [regex]::Match($status, "(?m)^Threads:\s+([0-9]+)")
    $rows.Add([PSCustomObject]@{
        phase = $Phase
        variant = $Variant
        cpu_slot = $CpuSlot
        process_age_seconds = [math]::Round((([datetime]::UtcNow - $StartedAtUtc).TotalSeconds), 3)
        vmrss_mib = To-MiB ((Read-Kb $status "VmRSS") * 1024.0)
        smaps_rss_mib = To-MiB ((Read-Kb $rollup "Rss") * 1024.0)
        smaps_pss_mib = To-MiB ((Read-Kb $rollup "Pss") * 1024.0)
        private_dirty_mib = To-MiB ((Read-Kb $rollup "Private_Dirty") * 1024.0)
        anonymous_mib = To-MiB ((Read-Kb $rollup "Anonymous") * 1024.0)
        cgroup_current_mib = if ($cgroupCurrent -match "^[0-9]+$") { To-MiB ([double] $cgroupCurrent) } else { 0.0 }
        cgroup_anon_mib = To-MiB (Read-CgroupBytes $cgroupStat "anon")
        cgroup_file_mib = To-MiB (Read-CgroupBytes $cgroupStat "file")
        cgroup_sock_mib = To-MiB (Read-CgroupBytes $cgroupStat "sock")
        threads = if ($threadMatch.Success) { [int] $threadMatch.Groups[1].Value } else { 0 }
        instrumentation_rss_mib = Get-CategoryValue $categories "java-instrumentation" "rss_mib"
        rust_native_rss_mib = Get-CategoryValue $categories "rust-native-lib" "rss_mib"
        openj9_rss_mib = Get-CategoryValue $categories "openj9-native-lib" "rss_mib"
    })
}

function Get-Median {
    param([double[]] $Values)
    $sorted = @($Values | Sort-Object)
    if (($sorted.Count % 2) -eq 1) {
        return $sorted[[int][math]::Floor($sorted.Count / 2.0)]
    }
    $upper = [int]($sorted.Count / 2)
    return ($sorted[$upper - 1] + $sorted[$upper]) / 2.0
}

function Get-SummaryRow {
    param([string] $Variant)
    $selected = @($rows | Where-Object variant -eq $Variant)
    $result = [ordered]@{ variant = $Variant; repeats = $selected.Count }
    foreach ($property in @(
        "process_age_seconds",
        "vmrss_mib", "smaps_rss_mib", "smaps_pss_mib", "private_dirty_mib", "anonymous_mib",
        "cgroup_current_mib", "cgroup_anon_mib", "cgroup_file_mib", "cgroup_sock_mib", "threads",
        "instrumentation_rss_mib", "rust_native_rss_mib", "openj9_rss_mib")) {
        $result[$property] = [math]::Round((Get-Median @($selected | ForEach-Object { [double] $_.$property })), 3)
    }
    return [PSCustomObject] $result
}

function Get-PairedMedianDelta {
    param([string] $FromVariant, [string] $ToVariant, [string] $Property)
    $deltas = [System.Collections.Generic.List[double]]::new()
    foreach ($phase in 1..$RepeatCount) {
        $from = $rows | Where-Object { $_.phase -eq $phase -and $_.variant -eq $FromVariant } |
            Select-Object -First 1
        $to = $rows | Where-Object { $_.phase -eq $phase -and $_.variant -eq $ToVariant } |
            Select-Object -First 1
        if ($null -eq $from -or $null -eq $to) {
            throw "Missing paired attribution row for phase ${phase}: $FromVariant -> $ToVariant"
        }
        $deltas.Add(([double] $to.$Property) - ([double] $from.$Property))
    }
    return [math]::Round((Get-Median $deltas), 3)
}

function Get-PairedDeltaStats {
    param([string] $FromVariant, [string] $ToVariant, [string] $Property)
    $deltas = [System.Collections.Generic.List[double]]::new()
    foreach ($phase in 1..$RepeatCount) {
        $from = $rows | Where-Object { $_.phase -eq $phase -and $_.variant -eq $FromVariant } |
            Select-Object -First 1
        $to = $rows | Where-Object { $_.phase -eq $phase -and $_.variant -eq $ToVariant } |
            Select-Object -First 1
        if ($null -eq $from -or $null -eq $to) {
            throw "Missing paired attribution row for phase ${phase}: $FromVariant -> $ToVariant"
        }
        $deltas.Add(([double] $to.$Property) - ([double] $from.$Property))
    }
    return [PSCustomObject]@{
        median = [math]::Round((Get-Median $deltas), 3)
        min = [math]::Round(($deltas | Measure-Object -Minimum).Minimum, 3)
        max = [math]::Round(($deltas | Measure-Object -Maximum).Maximum, 3)
        spread = [math]::Round(
            ($deltas | Measure-Object -Maximum).Maximum - ($deltas | Measure-Object -Minimum).Minimum,
            3)
    }
}

function Get-MaxDiagnosticsValue {
    param([string] $Property)
    $values = [System.Collections.Generic.List[double]]::new()
    foreach ($phase in 1..$RepeatCount) {
        foreach ($variant in @("native-properties", "javaagent")) {
            $path = Join-Path $ResultsDir "phase-$phase\$variant\glowroot_diagnostics.json"
            if (-not (Test-Path -LiteralPath $path)) {
                throw "Missing Glowroot diagnostics evidence: $path"
            }
            $diagnostics = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
            $value = $diagnostics.$Property
            if ($null -eq $value) {
                throw "Glowroot diagnostics does not expose '$Property': $path"
            }
            $values.Add([double] $value)
        }
    }
    return ($values | Measure-Object -Maximum).Maximum
}

function Get-MaxPositiveMappedDelta {
    param([string] $FromVariant, [string] $ToVariant, [string] $Property)
    $maximum = 0.0
    foreach ($phase in 1..$RepeatCount) {
        $from = $rows | Where-Object { $_.phase -eq $phase -and $_.variant -eq $FromVariant } |
            Select-Object -First 1
        $to = $rows | Where-Object { $_.phase -eq $phase -and $_.variant -eq $ToVariant } |
            Select-Object -First 1
        if ($null -eq $from -or $null -eq $to) {
            throw "Missing mapped-page attribution row for phase ${phase}: $FromVariant -> $ToVariant"
        }
        $maximum = [math]::Max($maximum, ([double] $to.$Property) - ([double] $from.$Property))
    }
    return [math]::Round($maximum, 3)
}

function Get-MaxPhaseProcessAgeSpread {
    $maximum = 0.0
    foreach ($phase in 1..$RepeatCount) {
        $ages = @($rows | Where-Object phase -eq $phase | ForEach-Object {
                [double] $_.process_age_seconds
            })
        if ($ages.Count -ne $variants.Count) {
            throw "Missing process-age evidence for phase $phase."
        }
        $spread = ($ages | Measure-Object -Maximum).Maximum - ($ages | Measure-Object -Minimum).Minimum
        $maximum = [math]::Max($maximum, $spread)
    }
    return [math]::Round($maximum, 3)
}

try {
    foreach ($image in @($DisabledImage, $NativeImage, $JavaAgentImage) | Select-Object -Unique) {
        Invoke-DockerChecked @("image", "inspect", $image) | Out-Null
    }
    Invoke-DockerChecked @("image", "inspect", $CollectorImage) | Out-Null
    Invoke-DockerChecked @("image", "inspect", $RunnerImage) | Out-Null
    Assert-ReactorBenchmarkCpuIsolation `
        -RunnerImage $RunnerImage `
        -SlotACpuSet $SlotACpuSet `
        -SlotBCpuSet $SlotBCpuSet `
        -SlotCCpuSet $SlotCCpuSet `
        -RunnerCpuSet $RunnerCpuSet `
        -CollectorCpuSet $CollectorCpuSet `
        -AllowRunnerCollectorSiblingSharing
    Invoke-DockerChecked @("network", "create", $network) | Out-Null
    Invoke-DockerChecked @(
        "run", "-d", "--name", $collector, "--network", $network,
        "--cpuset-cpus", $CollectorCpuSet, "--cpus", "0.5", "--memory", "128m", $CollectorImage) | Out-Null
    $collectorAddress = "$(Get-ReactorContainerNetworkIp -Container $collector -Network $network):8181"
    Invoke-DockerChecked @(
        "run", "-d", "--name", $runner, "--network", $network,
        "--cpuset-cpus", $RunnerCpuSet, "--cpus", "1", "--entrypoint", "sh",
        $RunnerImage, "-c", "sleep 86400") | Out-Null

    for ($phase = 1; $phase -le $RepeatCount; $phase++) {
        $slot = $slots[($phase - 1) % $slots.Count]
        $phaseOrder = [System.Collections.Generic.List[string]]::new()
        for ($index = 0; $index -lt $variants.Count; $index++) {
            $phaseOrder.Add($variants[($index + $phase - 1) % $variants.Count])
        }
        foreach ($variant in $phaseOrder) {
            $container = Get-ContainerName $variant $phase
            $options = "$baseJavaOpts $(Get-VariantOptions $variant $phase)"
            $image = Get-VariantImage $variant
            $startedAtUtc = [datetime]::UtcNow
            try {
                Invoke-DockerChecked @(
                    "run", "-d", "--name", $container, "--network", $network,
                    "--cpuset-cpus", $slot, "--cpus", "1", "--memory", "128m",
                    "-e", "JAVA_TOOL_OPTIONS=", "-e", "JAVA_AGENT_OPTS=",
                    "-e", "JAVA_OPTS=$options", $image) | Out-Null
                Wait-HttpReady $container
                foreach ($path in @(
                        "/api/v1/candidates/direct",
                        "/api/v1/heavy?items=100",
                        "/api/v1/heavy/raw")) {
                    Invoke-Load $container $path
                }
                if ($variant -ne "disabled") {
                    Wait-FirstExport $container
                }
                Wait-MinimumProcessAge $startedAtUtc
                Collect-Evidence $variant $container $phase $slot $startedAtUtc
            } finally {
                Remove-Container $container
            }
            Start-Sleep -Seconds 3
        }
    }

    $rows | Export-Csv (Join-Path $ResultsDir "runs.csv") -NoTypeInformation -Encoding utf8
    $summary = @($variants | ForEach-Object { Get-SummaryRow $_ })
    $summary | Export-Csv (Join-Path $ResultsDir "summary.csv") -NoTypeInformation -Encoding utf8
    $attributions = @(
        [PSCustomObject]@{ label = "Native telemetry state"; from = "disabled"; to = "native-properties" },
        [PSCustomObject]@{ label = "Java agent bootstrap over native mode"; from = "native-properties"; to = "javaagent" },
        [PSCustomObject]@{ label = "Full javaagent versus disabled"; from = "disabled"; to = "javaagent" }
    )
    $attributionRows = @($attributions | ForEach-Object {
        [PSCustomObject]@{
            label = $_.label
            vmrss = Get-PairedMedianDelta $_.from $_.to "vmrss_mib"
            smaps_rss = Get-PairedMedianDelta $_.from $_.to "smaps_rss_mib"
            pss = Get-PairedMedianDelta $_.from $_.to "smaps_pss_mib"
            private_dirty = Get-PairedMedianDelta $_.from $_.to "private_dirty_mib"
            anonymous = Get-PairedMedianDelta $_.from $_.to "anonymous_mib"
            cgroup_current = Get-PairedMedianDelta $_.from $_.to "cgroup_current_mib"
            cgroup_sock = Get-PairedMedianDelta $_.from $_.to "cgroup_sock_mib"
            threads = Get-PairedMedianDelta $_.from $_.to "threads"
        }
    })
    $hardAttribution = $attributionRows | Where-Object label -eq "Native telemetry state" |
        Select-Object -First 1
    $fullAttribution = $attributionRows | Where-Object label -eq "Full javaagent versus disabled" |
        Select-Object -First 1
    $pairedVmrss = Get-PairedDeltaStats "disabled" "native-properties" "vmrss_mib"
    $pairedSmapsRss = Get-PairedDeltaStats "disabled" "native-properties" "smaps_rss_mib"
    $pairedCgroup = Get-PairedDeltaStats "disabled" "native-properties" "cgroup_current_mib"
    $pairedCgroupSock = Get-PairedDeltaStats "disabled" "native-properties" "cgroup_sock_mib"
    $pairedJavaAgentVmrss = Get-PairedDeltaStats "disabled" "javaagent" "vmrss_mib"
    $pairedJavaAgentSmapsRss = Get-PairedDeltaStats "disabled" "javaagent" "smaps_rss_mib"
    $pairedJavaAgentCgroup = Get-PairedDeltaStats "disabled" "javaagent" "cgroup_current_mib"
    $configuredDynamicCeilingBytes = Get-MaxDiagnosticsValue "configured_dynamic_memory_ceiling_bytes"
    $hardAgentBudgetBytes = Get-MaxDiagnosticsValue "hard_agent_attributed_budget_bytes"
    $residentReleaseGateThresholdBytes = Get-MaxDiagnosticsValue `
            "resident_release_gate_threshold_bytes"
    $kernelSocketCeilingBytes = Get-MaxDiagnosticsValue "collector_kernel_socket_ceiling_bytes"
    $nativeFeatureMappedDeltaMiB = Get-MaxPositiveMappedDelta `
        "disabled" "native-properties" "rust_native_rss_mib"
    $javaInstrumentationMappedMiB = @($rows | Where-Object variant -eq "javaagent" |
            ForEach-Object { [double] $_.instrumentation_rss_mib } | Measure-Object -Maximum).Maximum
    $maxPhaseProcessAgeSpreadSeconds = Get-MaxPhaseProcessAgeSpread
    $agentAttributedHardCeilingBytes = [int64] $configuredDynamicCeilingBytes +
        [int64] [math]::Ceiling($nativeFeatureMappedDeltaMiB * 1048576.0)
    $agentAttributedHardCeilingMiB = [math]::Round($agentAttributedHardCeilingBytes / 1048576.0, 3)
    $javaAgentAttributedCeilingBytes = $agentAttributedHardCeilingBytes +
        [int64] [math]::Ceiling($javaInstrumentationMappedMiB * 1048576.0)
    $javaAgentAttributedCeilingMiB = [math]::Round($javaAgentAttributedCeilingBytes / 1048576.0, 3)
    $kernelSocketCeilingMiB = [math]::Round($kernelSocketCeilingBytes / 1048576.0, 3)
    $gateChecks = [ordered]@{
        attributed_hard_ceiling = $agentAttributedHardCeilingBytes -le $hardAgentBudgetBytes
        vmrss_max = [double] $pairedVmrss.max -le $MaxMemoryRegressionMiB
        smaps_rss_max = [double] $pairedSmapsRss.max -le $MaxMemoryRegressionMiB
        cgroup_current_max = [double] $pairedCgroup.max -le $MaxMemoryRegressionMiB
        cgroup_sock_max = [double] $pairedCgroupSock.max -le $kernelSocketCeilingMiB
        threads = [double] $hardAttribution.threads -le $MaxThreadDelta
        process_age_spread = $maxPhaseProcessAgeSpreadSeconds -le $MaxProcessAgeSpreadSeconds
    }
    $gatePassed = -not ($gateChecks.Values -contains $false)
    $gateSummary = [PSCustomObject]@{
        status = if ($gatePassed) { "PASS" } else { "FAIL" }
        repeats = $RepeatCount
        execution_mode = "sequential-same-slot-paired-crossover"
        cpu_slot_rotation = "physical-core-balanced"
        application_cpu_slots = $slots -join ","
        runner_cpu_set = $RunnerCpuSet
        collector_cpu_set = $CollectorCpuSet
        runner_collector_smt_sharing = $true
        collector_address_mode = "fixed-ip"
        minimum_process_age_seconds = $MinimumProcessAgeSeconds
        max_process_age_spread_seconds = $MaxProcessAgeSpreadSeconds
        observed_max_process_age_spread_seconds = $maxPhaseProcessAgeSpreadSeconds
        max_memory_regression_mib = $MaxMemoryRegressionMiB
        max_thread_delta = $MaxThreadDelta
        configured_dynamic_memory_ceiling_bytes = [int64] $configuredDynamicCeilingBytes
        native_feature_mapped_rss_delta_mib = $nativeFeatureMappedDeltaMiB
        java_instrumentation_mapped_rss_mib = [math]::Round($javaInstrumentationMappedMiB, 3)
        javaagent_convenience_attributed_ceiling_bytes = $javaAgentAttributedCeilingBytes
        javaagent_convenience_attributed_ceiling_mib = $javaAgentAttributedCeilingMiB
        agent_attributed_hard_ceiling_bytes = $agentAttributedHardCeilingBytes
        agent_attributed_hard_ceiling_mib = $agentAttributedHardCeilingMiB
        hard_agent_attributed_budget_bytes = [int64] $hardAgentBudgetBytes
        resident_release_gate_threshold_bytes = [int64] $residentReleaseGateThresholdBytes
        collector_kernel_socket_ceiling_bytes = [int64] $kernelSocketCeilingBytes
        hard_mode = "native-properties"
        hard_mode_vmrss_delta_mib = $hardAttribution.vmrss
        hard_mode_smaps_rss_delta_mib = $hardAttribution.smaps_rss
        hard_mode_cgroup_current_delta_mib = $hardAttribution.cgroup_current
        hard_mode_thread_delta = $hardAttribution.threads
        javaagent_convenience_vmrss_delta_mib = $fullAttribution.vmrss
        javaagent_convenience_smaps_rss_delta_mib = $fullAttribution.smaps_rss
        javaagent_convenience_cgroup_current_delta_mib = $fullAttribution.cgroup_current
        javaagent_convenience_thread_delta = $fullAttribution.threads
        observed_vmrss_delta_min_mib = $pairedVmrss.min
        observed_vmrss_delta_max_mib = $pairedVmrss.max
        observed_vmrss_delta_spread_mib = $pairedVmrss.spread
        observed_smaps_rss_delta_min_mib = $pairedSmapsRss.min
        observed_smaps_rss_delta_max_mib = $pairedSmapsRss.max
        observed_smaps_rss_delta_spread_mib = $pairedSmapsRss.spread
        observed_cgroup_current_delta_min_mib = $pairedCgroup.min
        observed_cgroup_current_delta_max_mib = $pairedCgroup.max
        observed_cgroup_current_delta_spread_mib = $pairedCgroup.spread
        observed_cgroup_sock_delta_min_mib = $pairedCgroupSock.min
        observed_cgroup_sock_delta_max_mib = $pairedCgroupSock.max
        observed_cgroup_sock_delta_spread_mib = $pairedCgroupSock.spread
        javaagent_observed_vmrss_delta_max_mib = $pairedJavaAgentVmrss.max
        javaagent_observed_smaps_rss_delta_max_mib = $pairedJavaAgentSmapsRss.max
        javaagent_observed_cgroup_current_delta_max_mib = $pairedJavaAgentCgroup.max
        supporting_pss_delta_mib = $fullAttribution.pss
        supporting_private_dirty_delta_mib = $fullAttribution.private_dirty
        supporting_anonymous_delta_mib = $fullAttribution.anonymous
        checks = [PSCustomObject] $gateChecks
    }
    $gateSummary | ConvertTo-Json -Depth 3 |
        Set-Content (Join-Path $ResultsDir "gate-summary.json") -Encoding utf8
    $report = @(
        "# Glowroot Footprint Attribution",
        "",
        "All variants use the same workload and JVM profile. Each phase runs all variants sequentially on the same physical-core CPU slot.",
        "Variant order and the physical-core slot rotate across phases, removing simultaneous-JVM and fixed-slot bias.",
        "Every process is measured at age >= $MinimumProcessAgeSeconds seconds; the maximum allowed within-phase age spread is $MaxProcessAgeSpreadSeconds seconds.",
        "Application slots are $($slots -join ','). The sequential load runner ($RunnerCpuSet) and collector ($CollectorCpuSet) use separate SMT siblings on the fourth physical core.",
        "The resident gate uses the collector's fixed container IP; the protocol gate separately validates Kubernetes-style DNS.",
        "Disabled image: $DisabledImage. Native and javaagent images: $NativeImage / $JavaAgentImage.",
        "",
        "| Variant | VmRSS MiB | smaps RSS | PSS | Private dirty | Anonymous | cgroup current | cgroup anon | cgroup sock | Threads | Instrumentation RSS |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
        ($summary | ForEach-Object { "| $($_.variant) | $($_.vmrss_mib) | $($_.smaps_rss_mib) | $($_.smaps_pss_mib) | $($_.private_dirty_mib) | $($_.anonymous_mib) | $($_.cgroup_current_mib) | $($_.cgroup_anon_mib) | $($_.cgroup_sock_mib) | $($_.threads) | $($_.instrumentation_rss_mib) |" }),
        "",
        "| Attribution | VmRSS delta | smaps RSS delta | PSS delta | Private dirty delta | Anonymous delta | cgroup current delta | cgroup sock delta | Thread delta |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
        ($attributionRows | ForEach-Object { "| $($_.label) | $($_.vmrss) | $($_.smaps_rss) | $($_.pss) | $($_.private_dirty) | $($_.anonymous) | $($_.cgroup_current) | $($_.cgroup_sock) | $($_.threads) |" }),
        "",
        "## Gate: $($gateSummary.status)",
        "",
        "The embedded-native source-enforced attributed ceiling is $agentAttributedHardCeilingMiB MiB: $([int64] $configuredDynamicCeilingBytes) bytes of bounded state/reserves/export work plus $nativeFeatureMappedDeltaMiB MiB of native feature pages. The hard agent-attributed budget is $([math]::Round($hardAgentBudgetBytes / 1048576.0, 3)) MiB.",
        "The optional -javaagent convenience path is reported separately at $javaAgentAttributedCeilingMiB MiB after adding $([math]::Round($javaInstrumentationMappedMiB, 3)) MiB of Java instrumentation pages. It is not the hard-budget production mode.",
        "",
        "The artifact attribution gate requires every observed native-properties versus feature-disabled paired delta to stay at or below +$MaxMemoryRegressionMiB MiB for VmRSS, smaps RSS, and cgroup current. The maximum cgroup socket delta must stay within the $kernelSocketCeilingMiB MiB kernel reserve, with at most $MaxThreadDelta additional threads.",
        "Private dirty, anonymous memory, and PSS remain supporting attribution evidence; they do not replace the total resident-memory gate.",
        "Observed maximum within-phase process-age spread: $maxPhaseProcessAgeSpreadSeconds seconds.",
        "",
        "| Observed paired delta | Minimum MiB | Median MiB | Maximum MiB | Spread MiB |",
        "|---|---:|---:|---:|---:|",
        "| VmRSS | $($pairedVmrss.min) | $($pairedVmrss.median) | $($pairedVmrss.max) | $($pairedVmrss.spread) |",
        "| smaps RSS | $($pairedSmapsRss.min) | $($pairedSmapsRss.median) | $($pairedSmapsRss.max) | $($pairedSmapsRss.spread) |",
        "| cgroup current | $($pairedCgroup.min) | $($pairedCgroup.median) | $($pairedCgroup.max) | $($pairedCgroup.spread) |",
        "| cgroup sock | $($pairedCgroupSock.min) | $($pairedCgroupSock.median) | $($pairedCgroupSock.max) | $($pairedCgroupSock.spread) |",
        "",
        "Optional -javaagent observed maxima: VmRSS $($pairedJavaAgentVmrss.max) MiB, smaps RSS $($pairedJavaAgentSmapsRss.max) MiB, cgroup current $($pairedJavaAgentCgroup.max) MiB. These values are evidence only and do not inherit the embedded-native certification.",
        "",
        "Attribution summaries are derived from phase-matched independent JVM processes. Repeat count is a multiple of three, so each variant occupies each physical-core slot equally.",
        "OpenJ9 JIT, GC, allocator, and page residency differ between processes, but the strict release gate intentionally rejects even a noisy observed maximum above the product boundary. The Rust startup budget remains the agent-owned allocation contract; this test is the conservative artifact and resident-memory evidence gate."
    )
    $report | Set-Content (Join-Path $ResultsDir "REPORT.md") -Encoding utf8
    Write-Output "Footprint attribution report: $(Join-Path $ResultsDir 'REPORT.md')"
    if ($FailOnGate -and -not $gatePassed) {
        throw "Glowroot footprint gate failed. See $(Join-Path $ResultsDir 'gate-summary.json')."
    }
} finally {
    if (-not $KeepContainers) {
        foreach ($phase in 1..$RepeatCount) {
            foreach ($variant in $variants) { Remove-Container (Get-ContainerName $variant $phase) }
        }
        Remove-Container $runner
        Remove-Container $collector
        & docker network rm $network *> $null
    }
}
