[CmdletBinding()]
param(
    [switch] $SkipAgentBuild,
    [int] $Concurrency = 32,
    [int] $DurationSeconds = 5,
    [double] $MinimumRequestsPerSecond = 100
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$scriptRoot = $PSScriptRoot
$projectRoot = Split-Path -Parent $scriptRoot
$springApp = Join-Path $scriptRoot "spring-app"
$webFluxApp = Join-Path $scriptRoot "webflux-app"
$network = "glowroot-spring-runtime-compat"
$curlImage = "curlimages/curl:8.12.1"
$wrkImage = "williamyeh/wrk:latest"
$resultsDirectory = Join-Path $scriptRoot "results/runtime-compatibility"
$resultsPath = Join-Path $resultsDirectory "spring-runtimes.json"

if ($Concurrency -lt 1 -or $Concurrency -gt 512) {
    throw "Concurrency must be between 1 and 512."
}
if ($DurationSeconds -lt 2 -or $DurationSeconds -gt 60) {
    throw "DurationSeconds must be between 2 and 60."
}

function Invoke-Checked([string] $Command, [string[]] $Arguments, [string] $WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Command failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

function Remove-Container([string] $Name) {
    & docker rm -f $Name 2>$null | Out-Null
}

function Invoke-HttpStatus([string] $Container, [string] $Path) {
    $output = & docker run --rm --network $network $curlImage `
            -sS -o /dev/null -w "%{http_code}" "http://${Container}:8080$Path" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "HTTP status probe failed for $Container$Path`: $($output -join "`n")"
    }
    return (($output -join "").Trim())
}

function Invoke-HttpJson([string] $Container, [string] $Path) {
    $output = & docker run --rm --network $network $curlImage `
            -fsS "http://${Container}:8080$Path" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "HTTP JSON probe failed for $Container$Path`: $($output -join "`n")"
    }
    return ($output -join "`n")
}

function Wait-Ready([string] $Container) {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-HttpStatus $Container "/health") -eq "200") { return }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw "$Container did not become ready.`n$((& docker logs $Container 2>&1) -join "`n")"
}

function Parse-Wrk([string] $Output) {
    $rpsMatch = [regex]::Match($Output, 'Requests/sec:\s+([0-9.]+)')
    $p99Match = [regex]::Match($Output, '(?m)^\s*99%\s+([0-9.]+)(us|ms|s)')
    $non2xxMatch = [regex]::Match($Output, 'Non-2xx or 3xx responses:\s+(\d+)')
    if (-not $rpsMatch.Success -or -not $p99Match.Success) {
        throw "Cannot parse wrk output:`n$Output"
    }
    $p99 = [double]$p99Match.Groups[1].Value
    switch ($p99Match.Groups[2].Value) {
        "us" { $p99 /= 1000.0 }
        "s" { $p99 *= 1000.0 }
    }
    return [pscustomobject]@{
        requests_per_second = [math]::Round([double]$rpsMatch.Groups[1].Value, 2)
        p99_ms = [math]::Round($p99, 3)
        non_2xx = if ($non2xxMatch.Success) { [int]$non2xxMatch.Groups[1].Value } else { 0 }
    }
}

function Get-ProcessMetrics([string] $Container) {
    $status = (& docker exec $Container cat /proc/1/status 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Cannot read process metrics from $Container." }
    $rssMatch = [regex]::Match($status, '(?m)^VmRSS:\s+(\d+)\s+kB\s*$')
    $threadsMatch = [regex]::Match($status, '(?m)^Threads:\s+(\d+)\s*$')
    if (-not $rssMatch.Success -or -not $threadsMatch.Success) {
        throw "Cannot parse RSS/thread metrics from $Container`: $status"
    }
    $rssKiB = [long]$rssMatch.Groups[1].Value
    $threads = [int]$threadsMatch.Groups[1].Value
    if ($rssKiB -le 0 -or $threads -le 0) {
        throw "Invalid RSS/thread metrics from $Container`: rss_kib=$rssKiB threads=$threads"
    }
    return [pscustomobject]@{
        rss_mib = [math]::Round(([double]$rssKiB / 1024.0), 3)
        threads = $threads
    }
}

function Find-AgentJar {
    $jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot "agent-bootstrap/target") `
            -Filter "java-rust-glowroot-agent-*.jar" |
            Where-Object { $_.Name -notmatch '(-sources|-javadoc)\.jar$' } |
            Select-Object -First 1
    if ($null -eq $jar) { throw "Agent bootstrap JAR was not produced." }
    return $jar.FullName
}

$runtimes = @(
    [pscustomobject]@{ Name = "tomcat"; Kind = "servlet"; Starter = "spring-boot-starter-tomcat"; Adapter = "tomcat-valve" },
    [pscustomobject]@{ Name = "jetty"; Kind = "servlet"; Starter = "spring-boot-starter-jetty"; Adapter = "jetty-request-log" },
    [pscustomobject]@{ Name = "undertow"; Kind = "servlet"; Starter = "spring-boot-starter-undertow"; Adapter = "undertow-completion-listener" },
    [pscustomobject]@{ Name = "reactor-netty"; Kind = "webflux"; Starter = "spring-boot-starter-webflux"; Adapter = "webflux-filter" }
)

& docker version | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker is required for the runtime compatibility gate." }

$windowsSemeru = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
if ($IsWindows -and (Test-Path -LiteralPath $windowsSemeru -PathType Container)) {
    $env:JAVA_HOME = $windowsSemeru
    $env:Path = "$(Join-Path $env:JAVA_HOME 'bin')$([IO.Path]::PathSeparator)$env:Path"
}

if (-not $SkipAgentBuild) {
    Invoke-Checked "mvn" @("-B", "-ntp", "clean", "install") $projectRoot
}

if (-not (& docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $network })) {
    Invoke-Checked "docker" @("network", "create", $network) $projectRoot
}

$rows = [Collections.Generic.List[object]]::new()
try {
    foreach ($runtime in $runtimes) {
        $container = "glowroot-compat-$($runtime.Name)"
        $image = "java-rust-glowroot-agent:compat-$($runtime.Name)"
        $contextName = "compat-context-$($runtime.Name)"
        $context = Join-Path $scriptRoot $contextName
        Remove-Container $container
        if (Test-Path -LiteralPath $context) {
            Remove-Item -LiteralPath $context -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path $context | Out-Null

        if ($runtime.Kind -eq "servlet") {
            Invoke-Checked "mvn" @(
                "-B", "-ntp", "-DskipTests", "clean", "package",
                "-Dembedded.server.artifact=$($runtime.Starter)"
            ) $springApp
            $applicationJar = Join-Path $springApp "target/spring-glowroot-benchmark-1.0.0.jar"
        } else {
            Invoke-Checked "mvn" @("-B", "-ntp", "-DskipTests", "clean", "package") $webFluxApp
            $applicationJar = Join-Path $webFluxApp "target/spring-webflux-glowroot-benchmark-1.0.0.jar"
        }
        $expectedAdapter = $runtime.Adapter

        Copy-Item -LiteralPath $applicationJar -Destination (Join-Path $context "application.jar")
        Copy-Item -LiteralPath (Find-AgentJar) -Destination (Join-Path $context "agent.jar")
        Invoke-Checked "docker" @(
            "build", "-f", "spring.Dockerfile",
            "--build-arg", "APP_CONTEXT=$contextName",
            "-t", $image, "."
        ) $scriptRoot

        $telemetryOptions = @(
            "-Dreactor.glowroot.enabled=true",
            "-Dreactor.glowroot.spring.enabled=true",
            "-Dreactor.glowroot.collector.address=http://127.0.0.1:1",
            "-Dreactor.glowroot.agent.id=compat::$($runtime.Name)",
            "-Dreactor.glowroot.application.name=compat-$($runtime.Name)",
            "-Dreactor.glowroot.http.sample-rate=1",
            "-Dreactor.glowroot.trace.capacity=0",
            "-Dreactor.glowroot.max-routes=32"
        ) -join " "
        Invoke-Checked "docker" @(
            "run", "-d", "--name", $container, "--network", $network,
            "--cpus", "1", "--memory", "256m",
            "-e", "TELEMETRY_OPTS=$telemetryOptions",
            $image
        ) $projectRoot

        Wait-Ready $container
        $adapter = Invoke-HttpJson $container "/internal/benchmark/telemetry-adapter" | ConvertFrom-Json
        if ($adapter.enabled -ne $true -or $adapter.httpAdapter -ne $expectedAdapter `
                -or $adapter.fullLifecycle -ne $true) {
            throw "$($runtime.Name) did not activate $expectedAdapter`: $($adapter | ConvertTo-Json -Compress)"
        }

        foreach ($expectation in @(
                @{ Path = "/api/small?id=42"; Status = "200" },
                @{ Path = "/internal/benchmark/async"; Status = "200" },
                @{ Path = "/internal/benchmark/error"; Status = "500" },
                @{ Path = "/internal/benchmark/not-found"; Status = "404" })) {
            $actualStatus = Invoke-HttpStatus $container $expectation.Path
            if ($actualStatus -ne $expectation.Status) {
                throw "$($runtime.Name) $($expectation.Path) returned $actualStatus, expected $($expectation.Status)."
            }
        }

        $diagnostics = Invoke-HttpJson $container "/internal/benchmark/telemetry-diagnostics"
        $routeMatch = [regex]::Match($diagnostics, '"registered_routes":(\d+)')
        if (-not $routeMatch.Success -or [int]$routeMatch.Groups[1].Value -lt 4) {
            throw "$($runtime.Name) did not register the shared lifecycle routes: $diagnostics"
        }

        & docker run --rm --network $network $wrkImage `
                -t2 -c ([math]::Min($Concurrency, 32)) -d2s `
                "http://${container}:8080/api/small?id=42" | Out-Null
        $wrkOutput = (& docker run --rm --network $network $wrkImage `
                -t2 -c $Concurrency "-d${DurationSeconds}s" --latency `
                "http://${container}:8080/api/small?id=42" 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "$($runtime.Name) wrk failed:`n$wrkOutput" }
        $load = Parse-Wrk $wrkOutput
        if ($load.non_2xx -ne 0) {
            throw "$($runtime.Name) produced $($load.non_2xx) non-2xx responses under compatibility load."
        }
        if ($load.requests_per_second -lt $MinimumRequestsPerSecond) {
            throw "$($runtime.Name) RPS $($load.requests_per_second) is below $MinimumRequestsPerSecond."
        }
        $process = Get-ProcessMetrics $container
        $rows.Add([pscustomobject]@{
            runtime = $runtime.Name
            application_model = $runtime.Kind
            http_adapter = $expectedAdapter
            sync_status = 200
            async_status = 200
            error_status = 500
            not_found_status = 404
            registered_routes = [int]$routeMatch.Groups[1].Value
            requests_per_second = $load.requests_per_second
            p99_ms = $load.p99_ms
            non_2xx = $load.non_2xx
            process_rss_mib = $process.rss_mib
            threads = $process.threads
        })

        Remove-Container $container
        Remove-Item -LiteralPath $context -Recurse -Force
    }
} finally {
    foreach ($runtime in $runtimes) { Remove-Container "glowroot-compat-$($runtime.Name)" }
    foreach ($runtime in $runtimes) {
        $context = Join-Path $scriptRoot "compat-context-$($runtime.Name)"
        if (Test-Path -LiteralPath $context) { Remove-Item -LiteralPath $context -Recurse -Force }
    }
    & docker network rm $network 2>$null | Out-Null
}

New-Item -ItemType Directory -Force -Path $resultsDirectory | Out-Null
[pscustomobject]@{
    schema = 1
    passed = $true
    classification = "compatibility-load-smoke"
    servlet_adapters = "tomcat-valve,jetty-request-log,undertow-completion-listener"
    webflux_adapter = "webflux-filter"
    concurrency = $Concurrency
    duration_seconds = $DurationSeconds
    rows = $rows
    completed_at_utc = [DateTime]::UtcNow.ToString("O")
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resultsPath -Encoding utf8

$rows | Format-Table -AutoSize
Write-Host "Spring runtime compatibility gate: PASS"
Write-Host "Evidence: $resultsPath"
