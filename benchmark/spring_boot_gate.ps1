[CmdletBinding()]
param(
    [int] $PairRepeats = 3,
    [string] $ConcurrencyLevels = "64,256",
    [string] $EndpointClasses = "small-json,raw-json,heavy-json",
    [string] $Duration = "12s",
    [string] $Warmup = "5s",
    [double] $CpuLimit = 1.0,
    [string] $MemoryLimit = "256m",
    [string] $SlotACpuSet = "0",
    [string] $SlotBCpuSet = "2",
    [string] $RunnerCpuSet = "4-5",
    [string] $CollectorCpuSet = "6",
    [switch] $SequentialVariants,
    [switch] $UseJavaAgentBootstrap,
    [switch] $AllowRunnerCollectorSiblingSharing,
    [double] $MinUsefulRpsDeltaPercent = -2.0,
    [double] $MaxP99RegressionPercent = 10.0,
    [double] $MaxMemoryRegressionMiB = 3.0,
    [int] $AllowedThreadDelta = 1,
    [double] $Max503DeltaPercentagePoints = 0.0,
    [double] $MaxHostCpuAveragePercent = 15.0,
    [double] $MaxHostCpuPeakPercent = 40.0,
    [switch] $SkipHostPreflight,
    [switch] $SkipBuild,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
if ($PairRepeats -lt 3) { throw "PairRepeats must be at least 3." }
if ($MaxMemoryRegressionMiB -gt 3.0) { throw "The agent memory gate cannot exceed 3 MiB." }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDir "benchmark_isolation.ps1")
$ProjectRoot = Split-Path -Parent $ScriptDir
$SpringRoot = Join-Path $ScriptDir "spring-app"
$Context = Join-Path $ScriptDir "spring-context"
$Results = Join-Path $ScriptDir ("results\spring_gate_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$Network = "reactor-benchmark-net"
$AppImage = "java-rust-glowroot-agent:spring-benchmark"
$CollectorImage = "java-rust-glowroot-agent:mock-collector"
$RunnerImage = "curlimages/curl:8.12.1"
$WrkImage = "williamyeh/wrk:latest"
$Baseline = "spring-glowroot-baseline"
$Candidate = "spring-glowroot-candidate"
$Collector = "spring-glowroot-collector"

$EndpointMap = @{
    "small-json" = "/api/small?id=42"
    "raw-json" = "/api/raw"
    "heavy-json" = "/api/heavy?items=100"
}
$concurrency = @($ConcurrencyLevels -split "[,\s]+" | Where-Object { $_ } | ForEach-Object { [int] $_ })
$endpoints = @($EndpointClasses -split "[,\s]+" | Where-Object { $_ })
foreach ($endpoint in $endpoints) {
    if (-not $EndpointMap.ContainsKey($endpoint)) { throw "Unknown endpoint class: $endpoint" }
}
if (-not $SkipHostPreflight) {
    Assert-ReactorHostBenchmarkReadiness `
            -MaxAverageCpuPercent $MaxHostCpuAveragePercent `
            -MaxPeakCpuPercent $MaxHostCpuPeakPercent `
            -MinFreeVirtualMiB 3072
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
}

function Find-AgentJar {
    $jar = Get-ChildItem (Join-Path $ProjectRoot "agent-bootstrap\target") -Filter "java-rust-glowroot-agent-*.jar" -File |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if ($null -eq $jar) { throw "Agent JAR was not built." }
    return $jar.FullName
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
        Invoke-Checked mvn @("-B", "-ntp", "clean", "package") $SpringRoot | Out-Null
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
    Copy-Item (Join-Path $SpringRoot "target\spring-glowroot-benchmark-1.0.0.jar") `
            (Join-Path $Context "application.jar")
    Copy-Item (Find-AgentJar) (Join-Path $Context "agent.jar")
    Invoke-Checked docker @("build", "-f", "spring.Dockerfile", "-t", $AppImage, ".") $ScriptDir | Out-Null
    Invoke-Checked docker @("build", "-t", $CollectorImage, ".") (Join-Path $ScriptDir "mock-collector") | Out-Null
}

function Ensure-Network {
    if (-not (& docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $Network })) {
        Invoke-Checked docker @("network", "create", $Network) $ProjectRoot | Out-Null
    }
}

function Invoke-Curl([string] $Url) {
    $args = @("run", "--rm", "--network", $Network, "--entrypoint", "curl")
    if ($RunnerCpuSet) { $args += @("--cpuset-cpus", $RunnerCpuSet) }
    $output = & docker @args $RunnerImage "-fsS" $Url 2>&1
    if ($LASTEXITCODE -ne 0) { throw "HTTP probe failed for ${Url}: $($output -join "`n")" }
    return $output -join "`n"
}

function Invoke-Warmup([string] $Target, [string] $Path) {
    $args = @("run", "--rm", "--network", $Network)
    if ($RunnerCpuSet) { $args += @("--cpuset-cpus", $RunnerCpuSet) }
    $args += @($WrkImage, "-t1", "-c32", "-d$Warmup", "http://${Target}:8080$Path")
    $output = & docker @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Warmup failed for ${Target}${Path}:`n$($output -join "`n")"
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
    $telemetry = if ($Enabled) {
        if ($UseJavaAgentBootstrap) {
            "-javaagent:/app/agent.jar=collector=${Collector}:8181,agent-id=spring-benchmark::pair-${Pair}," +
            "application=spring-glowroot-benchmark,http-sample-rate=256,trace-capacity=0," +
            "max-routes=64,max-export-bytes=65536,spring-enabled=true"
        } else {
            "-Dreactor.glowroot.enabled=true " +
            "-Dreactor.glowroot.collector.address=http://${Collector}:8181 " +
            "-Dreactor.glowroot.agent.id=spring-benchmark::pair-${Pair} " +
            "-Dreactor.glowroot.application.name=spring-glowroot-benchmark " +
            "-Dreactor.glowroot.http.sample-rate=256 " +
            "-Dreactor.glowroot.trace.capacity=0 " +
            "-Dreactor.glowroot.max-routes=64 " +
            "-Dreactor.glowroot.max-export-bytes=65536 " +
            "-Dreactor.glowroot.spring.enabled=true"
        }
    } else { "" }
    $args = @(
        "run", "-d", "--name", $Name, "--network", $Network,
        "--cpus", "$CpuLimit", "--memory", $MemoryLimit,
        "-e", "TELEMETRY_OPTS=$telemetry"
    )
    if ($CpuSet) { $args += @("--cpuset-cpus", $CpuSet) }
    $args += $AppImage
    $started = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Checked docker $args $ProjectRoot | Out-Null
    Wait-Http $Name
    $started.Stop()
    return $started.Elapsed.TotalMilliseconds
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

function Invoke-Wrk([string] $Target, [string] $Path, [int] $Concurrency, [int] $Sequence) {
    $runner = "spring-glowroot-wrk-$Sequence"
    Remove-Container $runner
    $args = @("run", "-d", "--name", $runner, "--network", $Network)
    if ($RunnerCpuSet) { $args += @("--cpuset-cpus", $RunnerCpuSet) }
    $args += @($WrkImage, "-t2", "-c$Concurrency", "-d$Duration", "--latency", "http://${Target}:8080$Path")
    Invoke-Checked docker $args $ProjectRoot | Out-Null
    $maxRss = 0.0
    $maxContainer = 0.0
    $maxThreads = 0
    while ((& docker inspect -f "{{.State.Running}}" $runner 2>$null) -eq "true") {
        $sample = Get-ContainerMetrics $Target
        $maxRss = [math]::Max($maxRss, $sample.process_rss_mib)
        $maxContainer = [math]::Max($maxContainer, $sample.container_mib)
        $maxThreads = [math]::Max($maxThreads, $sample.threads)
        Start-Sleep -Milliseconds 250
    }
    $output = (& docker logs $runner 2>&1) -join "`n"
    Remove-Container $runner
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
        non_2xx = $non2xx
        non_2xx_pct = [math]::Round($non2xxRate, 6)
        process_rss_mib = $maxRss
        container_mib = $maxContainer
        threads = $maxThreads
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

Prepare-Build
Ensure-Network
New-Item -ItemType Directory -Force -Path $Results | Out-Null
Assert-ReactorBenchmarkCpuIsolation -RunnerImage $RunnerImage `
        -SlotACpuSet $SlotACpuSet `
        -SlotBCpuSet $(if ($SequentialVariants) { $SlotACpuSet } else { $SlotBCpuSet }) `
        -RunnerCpuSet $RunnerCpuSet `
        -CollectorCpuSet $CollectorCpuSet `
        -AllowSharedApplicationSlots:$SequentialVariants `
        -AllowRunnerCollectorSiblingSharing:$AllowRunnerCollectorSiblingSharing
Start-Collector
$records = [Collections.Generic.List[object]]::new()
$startups = [Collections.Generic.List[object]]::new()
$sequence = 0
try {
    for ($pair = 1; $pair -le $PairRepeats; $pair++) {
        $cells = foreach ($endpoint in $endpoints) {
            foreach ($value in $concurrency) { [pscustomobject]@{ endpoint = $endpoint; concurrency = $value } }
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
                    & docker exec $name sh -c "grep -q rust_glowroot_agent /proc/1/maps"
                    if ($LASTEXITCODE -ne 0) {
                        throw "Spring auto-configuration did not load the standalone native agent."
                    }
                }
                Invoke-Curl "http://${name}:8080/api/small?id=1" | Out-Null
                Invoke-Curl "http://${name}:8080/api/raw" | Out-Null
                Invoke-Curl "http://${name}:8080/api/heavy?items=10" | Out-Null
                foreach ($endpoint in $endpoints) {
                    Invoke-Warmup $name $EndpointMap[$endpoint]
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
                        non_2xx = $metric.non_2xx
                        non_2xx_pct = $metric.non_2xx_pct
                        process_rss_mib = $metric.process_rss_mib
                        container_mib = $metric.container_mib
                        threads = $metric.threads
                    })
                    Start-Sleep -Seconds 2
                }
                Remove-Container $name
                Start-Sleep -Seconds 5
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
        & docker exec $Candidate sh -c "grep -q rust_glowroot_agent /proc/1/maps"
        if ($LASTEXITCODE -ne 0) { throw "Spring auto-configuration did not load the standalone native agent." }
        foreach ($name in @($Baseline, $Candidate)) {
            Invoke-Curl "http://${name}:8080/api/small?id=1" | Out-Null
            Invoke-Curl "http://${name}:8080/api/raw" | Out-Null
            Invoke-Curl "http://${name}:8080/api/heavy?items=10" | Out-Null
            foreach ($endpoint in $endpoints) {
                Invoke-Warmup $name $EndpointMap[$endpoint]
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
                    non_2xx = $metric.non_2xx
                    non_2xx_pct = $metric.non_2xx_pct
                    process_rss_mib = $metric.process_rss_mib
                    container_mib = $metric.container_mib
                    threads = $metric.threads
                })
                Start-Sleep -Seconds 2
            }
        }
        Remove-Container $Baseline
        Remove-Container $Candidate
        Start-Sleep -Seconds 5
    }
} finally {
    foreach ($name in @($Baseline, $Candidate, $Collector)) { Remove-Container $name }
}

$summary = [Collections.Generic.List[object]]::new()
foreach ($endpoint in $endpoints) {
    foreach ($value in $concurrency) {
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
        $pairedErrorDeltas = [Collections.Generic.List[double]]::new()
        foreach ($pair in 1..$PairRepeats) {
            $pairedBase = $base | Where-Object pair -eq $pair | Select-Object -First 1
            $pairedAgent = $agent | Where-Object pair -eq $pair | Select-Object -First 1
            $pairedRpsDeltas.Add(100.0 * ($pairedAgent.useful_rps - $pairedBase.useful_rps) / $pairedBase.useful_rps)
            $pairedP99Deltas.Add(100.0 * ($pairedAgent.p99_ms - $pairedBase.p99_ms) / $pairedBase.p99_ms)
            $pairedRssDeltas.Add($pairedAgent.process_rss_mib - $pairedBase.process_rss_mib)
            $pairedContainerDeltas.Add($pairedAgent.container_mib - $pairedBase.container_mib)
            $pairedThreadDeltas.Add($pairedAgent.threads - $pairedBase.threads)
            $pairedErrorDeltas.Add($pairedAgent.non_2xx_pct - $pairedBase.non_2xx_pct)
        }
        $rpsDelta = Median -Values @($pairedRpsDeltas)
        $p99Delta = Median -Values @($pairedP99Deltas)
        $rssDelta = Median -Values @($pairedRssDeltas)
        $containerDelta = Median -Values @($pairedContainerDeltas)
        $threadDelta = Median -Values @($pairedThreadDeltas)
        $errorDelta = Median -Values @($pairedErrorDeltas)
        $maxRssDelta = ($pairedRssDeltas | Measure-Object -Maximum).Maximum
        $maxContainerDelta = ($pairedContainerDeltas | Measure-Object -Maximum).Maximum
        $observedMaxThreadDelta = ($pairedThreadDeltas | Measure-Object -Maximum).Maximum
        $maxErrorDelta = ($pairedErrorDeltas | Measure-Object -Maximum).Maximum
        $passed = $rpsDelta -ge $MinUsefulRpsDeltaPercent -and $p99Delta -le $MaxP99RegressionPercent `
                -and $rssDelta -le $MaxMemoryRegressionMiB `
                -and $containerDelta -le $MaxMemoryRegressionMiB `
                -and $observedMaxThreadDelta -le $AllowedThreadDelta `
                -and $maxErrorDelta -le $Max503DeltaPercentagePoints
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
            non_2xx_delta_pp = [math]::Round($errorDelta, 6)
            max_paired_non_2xx_delta_pp = [math]::Round($maxErrorDelta, 6)
            gate = if ($passed) { "PASS" } else { "FAIL" }
        })
    }
}
$startupBase = Median -Values @(($startups | Where-Object variant -eq "baseline").ms)
$startupAgent = Median -Values @(($startups | Where-Object variant -eq "candidate").ms)
$pairedStartupDeltas = [Collections.Generic.List[double]]::new()
foreach ($pair in 1..$PairRepeats) {
    $pairedBase = $startups | Where-Object { $_.pair -eq $pair -and $_.variant -eq "baseline" } | Select-Object -First 1
    $pairedAgent = $startups | Where-Object { $_.pair -eq $pair -and $_.variant -eq "candidate" } | Select-Object -First 1
    $pairedStartupDeltas.Add(100.0 * ($pairedAgent.ms - $pairedBase.ms) / $pairedBase.ms)
}
$startupDelta = Median -Values @($pairedStartupDeltas)
$passedAll = -not ($summary.gate -contains "FAIL") -and $startupDelta -le 10.0

$records | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $Results "raw.json") -Encoding utf8
[ordered]@{
    passed = $passedAll
    pair_repeats = $PairRepeats
    decision_statistic = "median_of_paired_deltas"
    activation_mode = if ($UseJavaAgentBootstrap) { "javaagent_plus_starter" } else { "starter_properties" }
    startup_baseline_median_ms = [math]::Round($startupBase, 2)
    startup_candidate_median_ms = [math]::Round($startupAgent, 2)
    startup_delta_pct = [math]::Round($startupDelta, 2)
    rows = $summary
} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $Results "summary.json") -Encoding utf8

$lines = [Collections.Generic.List[string]]::new()
$lines.Add("# Spring Boot Agent Gate")
$lines.Add("")
$lines.Add("Activation: **$(if ($UseJavaAgentBootstrap) { 'optional -javaagent bootstrap + starter' } else { 'recommended starter + properties' })**.")
$lines.Add("Paired runs: $PairRepeats. Mode: $(if ($SequentialVariants) { 'same-core sequential' } else { 'dual-slot isolated' }). Startup off/on medians: $([math]::Round($startupBase,2)) / $([math]::Round($startupAgent,2)) ms; paired delta median: $([math]::Round($startupDelta,2))%.")
$lines.Add("RPS, p99, RSS, cgroup, and startup gates use the median of paired deltas. RSS/cgroup maxima remain visible diagnostics; deterministic agent-owned and exact-source resident maxima are enforced by the separate footprint gates.")
$lines.Add("")
$lines.Add("| Endpoint | C | RPS off/on med | Paired RPS delta | p99 off/on med ms | Paired p99 delta | RSS paired med/max MiB | cgroup paired med/max MiB | Thread paired med/max | non-2xx paired med/max pp | Gate |")
$lines.Add("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
foreach ($row in $summary) {
    $lines.Add("| $($row.endpoint) | $($row.concurrency) | $($row.baseline_rps)/$($row.candidate_rps) | $($row.rps_delta_pct)% | $($row.baseline_p99_ms)/$($row.candidate_p99_ms) | $($row.p99_delta_pct)% | $($row.process_rss_delta_mib)/$($row.max_paired_process_rss_delta_mib) | $($row.container_delta_mib)/$($row.max_paired_container_delta_mib) | $($row.thread_delta)/$($row.max_paired_thread_delta) | $($row.non_2xx_delta_pp)/$($row.max_paired_non_2xx_delta_pp) | $($row.gate) |")
}
$lines.Add("")
$lines.Add("Overall gate: **$(if ($passedAll) { 'PASS' } else { 'BLOCKED' })**")
$lines | Set-Content (Join-Path $Results "REPORT.md") -Encoding utf8
Write-Host "Spring Boot gate report: $(Join-Path $Results 'REPORT.md')"
if ($FailOnGate -and -not $passedAll) { throw "Spring Boot production gate failed." }
