[CmdletBinding()]
param(
    [switch]$SkipAgentBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
[xml]$rootPom = Get-Content -Raw -LiteralPath (Join-Path $projectRoot "pom.xml")
$agentVersion = "$($rootPom.project.version)".Trim()
if ([string]::IsNullOrWhiteSpace($agentVersion)) {
    throw "Cannot resolve the agent version from the root pom.xml."
}
$appPom = Join-Path $PSScriptRoot "non-web-app/pom.xml"
$appJar = Join-Path $PSScriptRoot "non-web-app/target/spring-glowroot-non-web-benchmark-1.0.0.jar"
$dependencyTree = Join-Path $PSScriptRoot "non-web-app/target/runtime-dependencies.txt"

Push-Location $projectRoot
try {
    if (-not $SkipAgentBuild) {
        & mvn -B -ntp install
        if ($LASTEXITCODE -ne 0) { throw "Agent build failed with exit code $LASTEXITCODE" }
    }

    & mvn -B -ntp -f $appPom clean package `
            dependency:tree `
            "-Dglowroot.agent.version=$agentVersion" `
            "-Dscope=runtime" `
            "-DoutputFile=$dependencyTree"
    if ($LASTEXITCODE -ne 0) { throw "Non-web application build failed with exit code $LASTEXITCODE" }

    $dependencies = Get-Content -Raw -LiteralPath $dependencyTree
    foreach ($forbidden in @("spring-webmvc", "spring-web:", "tomcat-embed", "jakarta.servlet-api")) {
        if ($dependencies.Contains($forbidden)) {
            throw "Non-web runtime dependency tree unexpectedly contains $forbidden"
        }
    }

    $output = (& java -jar $appJar 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "Non-web application failed:`n$output"
    }
    if (-not $output.Contains("NON_WEB_GLOWROOT_READY")) {
        throw "Non-web application did not report native telemetry readiness:`n$output"
    }
    Write-Host "Non-web Spring telemetry gate: PASS"
} finally {
    Pop-Location
}
