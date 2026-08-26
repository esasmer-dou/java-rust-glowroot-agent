[CmdletBinding()]
param(
    [switch]$SkipAgentBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$starterPom = Join-Path $projectRoot "spring-boot-starter/pom.xml"
$runtimePom = Join-Path $projectRoot "spring-runtime-core/pom.xml"
$mvcPom = Join-Path $projectRoot "spring-mvc-adapter/pom.xml"
$appPom = Join-Path $PSScriptRoot "spring-app/pom.xml"
$appTarget = Join-Path $PSScriptRoot "spring-app/target"
$webFluxPom = Join-Path $PSScriptRoot "webflux-app/pom.xml"
$webFluxTarget = Join-Path $PSScriptRoot "webflux-app/target"
$evidenceDirectory = Join-Path $PSScriptRoot "results/dependency-contract"
$evidencePath = Join-Path $evidenceDirectory "embedded-server-dependencies.json"

$servers = @(
    [pscustomobject]@{
        Name = "tomcat"
        Starter = "spring-boot-starter-tomcat"
        Expected = "org.apache.tomcat.embed:tomcat-embed-core"
        AdapterPom = Join-Path $projectRoot "spring-tomcat-adapter/pom.xml"
        AdapterDependency = @("org.apache.tomcat.embed", "tomcat-embed-core")
        HttpAdapter = "tomcat-valve"
        Forbidden = @("org.eclipse.jetty:jetty-server", "io.undertow:undertow-core")
        JarExpected = "tomcat-embed-core-"
        JarForbidden = @("jetty-server-", "undertow-core-")
    },
    [pscustomobject]@{
        Name = "jetty"
        Starter = "spring-boot-starter-jetty"
        Expected = "org.eclipse.jetty:jetty-server"
        AdapterPom = Join-Path $projectRoot "spring-jetty-adapter/pom.xml"
        AdapterDependency = @("org.eclipse.jetty", "jetty-server")
        HttpAdapter = "jetty-request-log"
        Forbidden = @("org.apache.tomcat.embed:tomcat-embed-core", "io.undertow:undertow-core")
        JarExpected = "jetty-server-"
        JarForbidden = @("tomcat-embed-core-", "undertow-core-")
    },
    [pscustomobject]@{
        Name = "undertow"
        Starter = "spring-boot-starter-undertow"
        Expected = "io.undertow:undertow-core"
        AdapterPom = Join-Path $projectRoot "spring-undertow-adapter/pom.xml"
        AdapterDependency = @("io.undertow", "undertow-servlet")
        HttpAdapter = "undertow-completion-listener"
        Forbidden = @("org.apache.tomcat.embed:tomcat-embed-core", "org.eclipse.jetty:jetty-server")
        JarExpected = "undertow-core-"
        JarForbidden = @("tomcat-embed-core-", "jetty-server-")
    }
)

function Find-Dependency([xml] $Pom, [string] $GroupId, [string] $ArtifactId) {
    $namespace = [Xml.XmlNamespaceManager]::new($Pom.NameTable)
    $namespace.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")
    return $Pom.SelectSingleNode(
            "/m:project/m:dependencies/m:dependency[m:groupId='$GroupId' and m:artifactId='$ArtifactId']",
            $namespace)
}

function Assert-ProvidedOptionalDependency(
        [xml] $Pom,
        [string] $GroupId,
        [string] $ArtifactId) {
    $dependency = Find-Dependency $Pom $GroupId $ArtifactId
    if ($null -eq $dependency) {
        throw "Starter contract is missing $GroupId`:$ArtifactId."
    }
    if ($dependency.scope -ne "provided" -or $dependency.optional -ne "true") {
        throw "$GroupId`:$ArtifactId must remain provided and optional; otherwise it can leak into applications."
    }
}

[xml]$pom = Get-Content -Raw -LiteralPath $starterPom
foreach ($dependency in @(
        @("jakarta.servlet", "jakarta.servlet-api"),
        @("org.apache.tomcat.embed", "tomcat-embed-core"),
        @("org.eclipse.jetty", "jetty-server"),
        @("io.undertow", "undertow-servlet"))) {
    if ($null -ne (Find-Dependency $pom $dependency[0] $dependency[1])) {
        throw "The common starter must not depend on $($dependency[0]):$($dependency[1])."
    }
}
[xml]$runtime = Get-Content -Raw -LiteralPath $runtimePom
Assert-ProvidedOptionalDependency $runtime "org.springframework.boot" "spring-boot-autoconfigure"
[xml]$mvc = Get-Content -Raw -LiteralPath $mvcPom
Assert-ProvidedOptionalDependency $mvc "jakarta.servlet" "jakarta.servlet-api"
Assert-ProvidedOptionalDependency $mvc "org.springframework" "spring-webmvc"
foreach ($server in $servers) {
    [xml]$adapterPom = Get-Content -Raw -LiteralPath $server.AdapterPom
    Assert-ProvidedOptionalDependency $adapterPom $server.AdapterDependency[0] $server.AdapterDependency[1]
}

Push-Location $projectRoot
try {
    if (-not $SkipAgentBuild) {
        & mvn -B -ntp -DskipTests install
        if ($LASTEXITCODE -ne 0) { throw "Agent build failed with exit code $LASTEXITCODE." }
    }

    $results = [Collections.Generic.List[object]]::new()
    foreach ($server in $servers) {
        $dependencyTree = Join-Path $appTarget "dependency-tree-$($server.Name).txt"
        & mvn -B -ntp -f $appPom clean package `
                "-Dembedded.server.artifact=$($server.Starter)"
        if ($LASTEXITCODE -ne 0) {
            throw "$($server.Name) application build failed with exit code $LASTEXITCODE."
        }
        & mvn -B -ntp -f $appPom dependency:tree `
                "-Dembedded.server.artifact=$($server.Starter)" `
                "-Dscope=runtime" `
                "-DoutputFile=$dependencyTree"
        if ($LASTEXITCODE -ne 0) {
            throw "$($server.Name) dependency tree failed with exit code $LASTEXITCODE."
        }

        $tree = Get-Content -Raw -LiteralPath $dependencyTree
        if (-not $tree.Contains("com.reactor:java-rust-glowroot-spring-boot-starter")) {
            throw "$($server.Name) runtime tree does not contain the Glowroot starter."
        }
        foreach ($adapterArtifact in @(
                "java-rust-glowroot-spring-tomcat-adapter",
                "java-rust-glowroot-spring-jetty-adapter",
                "java-rust-glowroot-spring-undertow-adapter")) {
            if (-not $tree.Contains("com.reactor:$adapterArtifact")) {
                throw "$($server.Name) runtime tree does not contain internal adapter $adapterArtifact."
            }
        }
        if (-not $tree.Contains($server.Expected)) {
            throw "$($server.Name) runtime tree does not contain $($server.Expected)."
        }
        foreach ($forbidden in $server.Forbidden) {
            if ($tree.Contains($forbidden)) {
                throw "$($server.Name) runtime tree unexpectedly contains $forbidden."
            }
        }

        $appJar = Get-ChildItem -LiteralPath $appTarget -Filter "spring-glowroot-benchmark-*.jar" |
                Where-Object { $_.Name -notmatch '\.original$' } |
                Select-Object -First 1
        if ($null -eq $appJar) { throw "$($server.Name) executable JAR was not produced." }

        $jarEntries = (& jar tf $appJar.FullName 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $($appJar.FullName)." }
        if (-not $jarEntries.Contains($server.JarExpected)) {
            throw "$($server.Name) executable JAR does not contain $($server.JarExpected)."
        }
        foreach ($forbidden in $server.JarForbidden) {
            if ($jarEntries.Contains($forbidden)) {
                throw "$($server.Name) executable JAR unexpectedly contains $forbidden."
            }
        }

        $results.Add([pscustomobject]@{
            server = $server.Name
            selected_starter = $server.Starter
            selected_engine = $server.Expected
            forbidden_engines_absent = $true
            executable_jar_verified = $true
            http_adapter = $server.HttpAdapter
        })
    }

    $webFluxTree = Join-Path $webFluxTarget "dependency-tree-reactor-netty.txt"
    & mvn -B -ntp -f $webFluxPom clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Reactor Netty WebFlux application build failed with exit code $LASTEXITCODE."
    }
    & mvn -B -ntp -f $webFluxPom dependency:tree `
            "-Dscope=runtime" `
            "-DoutputFile=$webFluxTree"
    if ($LASTEXITCODE -ne 0) {
        throw "Reactor Netty WebFlux dependency tree failed with exit code $LASTEXITCODE."
    }
    $reactiveDependencies = Get-Content -Raw -LiteralPath $webFluxTree
    foreach ($required in @(
            "com.reactor:java-rust-glowroot-spring-webflux-adapter",
            "com.reactor:java-rust-glowroot-spring-runtime",
            "org.springframework.boot:spring-boot-starter-webflux",
            "io.projectreactor.netty:reactor-netty-http")) {
        if (-not $reactiveDependencies.Contains($required)) {
            throw "WebFlux runtime tree does not contain $required."
        }
    }
    foreach ($forbidden in @(
            "com.reactor:java-rust-glowroot-spring-mvc-adapter",
            "com.reactor:java-rust-glowroot-spring-tomcat-adapter",
            "com.reactor:java-rust-glowroot-spring-jetty-adapter",
            "com.reactor:java-rust-glowroot-spring-undertow-adapter",
            "org.springframework:spring-webmvc",
            "jakarta.servlet:jakarta.servlet-api",
            "org.apache.tomcat.embed:tomcat-embed-core",
            "org.eclipse.jetty:jetty-server",
            "io.undertow:undertow-core")) {
        if ($reactiveDependencies.Contains($forbidden)) {
            throw "WebFlux runtime tree unexpectedly contains $forbidden."
        }
    }

    $webFluxJar = Get-ChildItem -LiteralPath $webFluxTarget -Filter "spring-webflux-glowroot-benchmark-*.jar" |
            Where-Object { $_.Name -notmatch '\.original$' } |
            Select-Object -First 1
    if ($null -eq $webFluxJar) { throw "Reactor Netty executable JAR was not produced." }
    $webFluxEntries = (& jar tf $webFluxJar.FullName 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $($webFluxJar.FullName)." }
    foreach ($required in @(
            "java-rust-glowroot-spring-webflux-adapter-",
            "reactor-netty-http-")) {
        if (-not $webFluxEntries.Contains($required)) {
            throw "Reactor Netty executable JAR does not contain $required."
        }
    }
    foreach ($forbidden in @(
            "java-rust-glowroot-spring-mvc-adapter-",
            "java-rust-glowroot-spring-tomcat-adapter-",
            "java-rust-glowroot-spring-jetty-adapter-",
            "java-rust-glowroot-spring-undertow-adapter-",
            "spring-webmvc-",
            "jakarta.servlet-api-",
            "tomcat-embed-core-",
            "jetty-server-",
            "undertow-core-")) {
        if ($webFluxEntries.Contains($forbidden)) {
            throw "Reactor Netty executable JAR unexpectedly contains $forbidden."
        }
    }
    $results.Add([pscustomobject]@{
        server = "reactor-netty"
        selected_starter = "spring-boot-starter-webflux"
        selected_engine = "io.projectreactor.netty:reactor-netty-http"
        forbidden_engines_absent = $true
        executable_jar_verified = $true
        http_adapter = "webflux-filter"
    })

    New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
    [pscustomobject]@{
        passed = $true
        starter_server_dependencies = "internal-adapter-jars-only"
        server_api_dependencies = "provided_optional_inside_each_adapter"
        application_selects_server = $true
        results = $results
        note = "Each Servlet engine activates its direct completion adapter. Engine APIs remain isolated as provided and optional dependencies. WebFlux remains a separate adapter. tomcat-embed-el is an EL implementation, not the Tomcat server engine."
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $evidencePath -Encoding utf8

    Write-Host "Embedded Servlet server dependency gate: PASS"
    Write-Host "Evidence: $evidencePath"
} finally {
    Pop-Location
}
