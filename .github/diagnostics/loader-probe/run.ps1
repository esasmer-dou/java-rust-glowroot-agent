$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path "$PSScriptRoot/../../..").Path
$results = Join-Path $root 'benchmark/results/loader-probe'
New-Item -ItemType Directory -Force $results | Out-Null
$owned = [Collections.Generic.List[string]]::new()
$rows = [Collections.Generic.List[object]]::new()
$net = 'g07-loader-probe'
function Start-Owned([string]$name, [string[]]$options) {
    & docker run -d --name $name --network $net --label reactor.test=g07-loader @options | Out-Null
    if ($LASTEXITCODE) { throw "Cannot start $name" }
    $owned.Add($name)
}
function Request([string]$path, [string]$method='GET') {
    $port = (& docker port g07-loader-app 8080/tcp).Trim().Split(':')[-1]
    Invoke-RestMethod -Uri "http://127.0.0.1:$port$path" -Method $method -TimeoutSec 15
}
if (@(& docker ps --format '{{.Names}}').Count) { throw 'Diagnostic requires an idle runner' }
& docker network create --label reactor.test=g07-loader $net | Out-Null
try {
    Start-Owned g07-loader-collector @('--cpuset-cpus','1','--memory','256m','g07-loader-collector')
    Start-Owned g07-loader-wrk @('--cpuset-cpus','0','--entrypoint','sh','williamyeh/wrk:latest','-c','while :; do sleep 3600; done')
    foreach ($mode in @('off','normal','early','off-2','early-2','normal-2')) {
        $enabled = if ($mode -like 'off*') { 'false' } else { 'true' }
        $early = if ($mode -like 'early*') { 'true' } else { 'false' }
        Start-Owned g07-loader-app @('--cpuset-cpus','2,3','--cpus','1','--memory','256m','-p','127.0.0.1::8080','--mount',"type=bind,source=$PSScriptRoot/target,target=/app,readonly",'--mount',"type=bind,source=$results,target=/report",'-e','MALLOC_ARENA_MAX=2','-e','MALLOC_TRIM_THRESHOLD_=131072','ibm-semeru-runtimes:open-21-jre-jammy','java','-Xms16m','-Xmx96m','-Xss256k','-Xquickstart','-Xtune:virtualized','-Xshareclasses:none','-XX:ActiveProcessorCount=1','-XX:-TransparentHugePage','-Dfile.encoding=UTF-8',"-Xverbosegclog:/report/$mode-gc.xml",'-Djava.security.egd=file:/dev/./urandom',"-Dprobe.preload=$early","-Dreactor.glowroot.enabled=$enabled",'-Dreactor.glowroot.profile=micro','-Dreactor.glowroot.collector.address=http://g07-loader-collector:8181',"-Dreactor.glowroot.agent.id=loader-$mode",'-jar','/app/spring-loader-probe-1.0.0.jar')
        $ready = $false
        foreach ($i in 1..60) { try { Request /health | Out-Null; $ready=$true; break } catch { Start-Sleep 1 } }
        if (!$ready) { throw 'App did not start' }
        foreach ($cycle in 1..4) { foreach ($path in @('/api/small?id=42','/api/raw')) {
            & docker exec g07-loader-wrk wrk -t2 -c64 -d5s "http://g07-loader-app:8080$path" *> $null
            if ($LASTEXITCODE) { throw 'Warmup failed' }
        } }
        $cells = foreach ($c in @(64,256)) { foreach ($endpoint in @('small','raw')) {
            $path = if ($endpoint -eq 'small') { '/api/small?id=42' } else { '/api/raw' }
            $out = (& docker exec g07-loader-wrk wrk -t2 "-c$c" -d12s --latency "http://g07-loader-app:8080$path") -join "`n"
            $out | Set-Content "$results/$mode-$endpoint-c$c.txt"
            if ($LASTEXITCODE) { throw "Load failed: $out" }
            if ($out -notmatch 'Requests/sec:\s+([\d.]+)') { throw 'No RPS' }; $rps = [double]$Matches[1]
            [pscustomobject]@{endpoint=$endpoint;concurrency=$c;rps=$rps}
        } }
        Request /internal/benchmark/full-gc POST | Out-Null
        Start-Sleep 5
        foreach ($file in @('smaps','smaps_rollup','status','stat')) {
            & docker exec g07-loader-app cat "/proc/1/$file" > "$results/$mode-$file.txt"
        }
        $rollup = Get-Content "$results/$mode-smaps_rollup.txt" -Raw
        if ($rollup -notmatch '(?m)^Rss:\s+(\d+)') { throw 'No RSS' }; $rss = [long]$Matches[1]/1024
        $group = & docker exec g07-loader-app sh -c 'cat /sys/fs/cgroup/memory.current; cat /sys/fs/cgroup/memory.stat'
        $group > "$results/$mode-cgroup.txt"
        Request /internal/benchmark/memory | ConvertTo-Json -Depth 8 > "$results/$mode-jvm.json"
        $rows.Add([pscustomobject]@{mode=$mode;rss_mib=$rss;container_mib=[long]$group[0]/1MB;cells=@($cells)})
        $rows | ConvertTo-Json -Depth 8 > "$results/probe.json"
        & docker logs g07-loader-app *> "$results/$mode-app.log"
        & docker rm -f g07-loader-app *> $null
        [void]$owned.Remove('g07-loader-app')
        Write-Host "$mode RSS=$rss"
    }
} finally {
    foreach ($name in $owned) { & docker logs $name *> "$results/$name.log"; & docker rm -f $name *> $null }
    & docker network rm $net *> $null
}
