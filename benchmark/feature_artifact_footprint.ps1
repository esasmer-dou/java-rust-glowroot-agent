[CmdletBinding()]
param(
    [int] $RepeatCount = 6,
    [int] $Concurrency = 256,
    [string] $Duration = "120s",
    [int] $RequestsPerEndpoint = 4096,
    [double] $MaxMemoryRegressionMiB = 3.0,
    [int] $MaxThreadDelta = 0,
    [double] $MaxHostCpuAveragePercent = 15.0,
    [double] $MaxHostCpuPeakPercent = 40.0,
    [double] $MinHostFreeVirtualMiB = 3072.0,
    [int] $HostStabilizationSeconds = 15,
    [switch] $SkipHostPreflight,
    [switch] $FailOnGate,
    [switch] $SkipNativeBuild
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
[System.Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
[System.Threading.Thread]::CurrentThread.CurrentUICulture = [Globalization.CultureInfo]::InvariantCulture

if ($MaxMemoryRegressionMiB -le 0 -or $MaxMemoryRegressionMiB -gt 3.0) {
    throw "MaxMemoryRegressionMiB must be greater than zero and cannot exceed the 3 MiB product boundary."
}

if ($RepeatCount -lt 3 -or ($RepeatCount % 3) -ne 0) {
    throw "RepeatCount must be a multiple of 3 and at least 3."
}
if ($HostStabilizationSeconds -lt 0 -or $HostStabilizationSeconds -gt 300) {
    throw "HostStabilizationSeconds must be between 0 and 300."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDir "benchmark_isolation.ps1")
$ProjectRoot = Split-Path -Parent $ScriptDir
$WorkspaceRoot = Split-Path -Parent $ProjectRoot
$FrameworkRoot = Join-Path $WorkspaceRoot "rust-java-rest"
$NativeRoot = Join-Path $WorkspaceRoot "rust-spring"
$MockRoot = Join-Path $ScriptDir "mock-collector"
$Context = Join-Path $ScriptDir "context"
$ResultsDir = Join-Path $ScriptDir ("results\feature_artifact_footprint_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$BaselineImage = "java-rust-glowroot-agent:feature-off"
$CandidateImage = "java-rust-glowroot-agent:feature-on"
$CollectorImage = "java-rust-glowroot-agent:mock-collector"
$RunnerImage = "reactor-benchmark-runner:local"
$NoGlowrootBinary = Join-Path $NativeRoot "target-linux-no-glowroot\release\librust_hyper.so"
$NoGlowrootFingerprint = Join-Path $NativeRoot "target-linux-no-glowroot\source-fingerprint.sha256"

if (-not $SkipHostPreflight) {
    Assert-ReactorHostBenchmarkReadiness `
            -MaxAverageCpuPercent $MaxHostCpuAveragePercent `
            -MaxPeakCpuPercent $MaxHostCpuPeakPercent `
            -MinFreeVirtualMiB $MinHostFreeVirtualMiB
}

function Invoke-Checked {
    param([string] $File, [string[]] $Arguments, [string] $WorkingDirectory)
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

function Find-SingleArtifact {
    param([string] $Directory, [string] $Filter)
    $matches = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
            Sort-Object LastWriteTime -Descending)
    if ($matches.Count -eq 0) {
        throw "Artifact not found: $Directory/$Filter"
    }
    return $matches[0].FullName
}

function Get-NativeSourceFingerprint {
    $sourceFiles = [System.Collections.Generic.List[string]]::new()
    foreach ($relativePath in @("Cargo.toml", "Cargo.lock", "build.rs", "rust-toolchain.toml")) {
        $path = Join-Path $NativeRoot $relativePath
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $sourceFiles.Add($path)
        }
    }
    Get-ChildItem -LiteralPath (Join-Path $NativeRoot "src") -Recurse -File |
        ForEach-Object { $sourceFiles.Add($_.FullName) }

    $entries = foreach ($path in $sourceFiles) {
        $relative = [IO.Path]::GetRelativePath($NativeRoot, $path).Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative=$hash"
    }
    $content = (@($entries | Sort-Object) -join "`n")
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($content)
        return ([Convert]::ToHexString($sha.ComputeHash($bytes))).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Assert-FeatureOffSourceFingerprint {
    param([string] $Expected)
    if (-not (Test-Path -LiteralPath $NoGlowrootFingerprint -PathType Leaf)) {
        throw "Feature-off source fingerprint is missing: $NoGlowrootFingerprint. Re-run without -SkipNativeBuild."
    }
    $actual = (Get-Content -Raw -LiteralPath $NoGlowrootFingerprint).Trim().ToLowerInvariant()
    if ($actual -ne $Expected) {
        throw "Feature-off native binary was built from different source. Expected $Expected but found $actual. Re-run without -SkipNativeBuild."
    }
}

function Reset-Context {
    $fullContext = [IO.Path]::GetFullPath($Context)
    $fullScriptDir = [IO.Path]::GetFullPath($ScriptDir) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullContext.StartsWith($fullScriptDir, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to recreate context outside benchmark directory: $fullContext"
    }
    if (Test-Path -LiteralPath $fullContext) {
        Remove-Item -LiteralPath $fullContext -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $fullContext | Out-Null
    return $fullContext
}

function Prepare-Context {
    param([switch] $FeatureOff)
    $fullContext = Reset-Context
    $frameworkJar = Find-SingleArtifact (Join-Path $FrameworkRoot "target") "*-core-runtime.jar"
    $codegenJar = Find-SingleArtifact (Join-Path $FrameworkRoot "target") "*-codegen.jar"
    $agentJar = Find-SingleArtifact (Join-Path $ProjectRoot "target") "java-rust-glowroot-agent-*.jar"
    Copy-Item -LiteralPath $frameworkJar -Destination (Join-Path $fullContext "framework.jar")
    Copy-Item -LiteralPath $codegenJar -Destination (Join-Path $fullContext "codegen.jar")
    Copy-Item -LiteralPath $agentJar -Destination (Join-Path $fullContext "agent.jar")
    Copy-Item -LiteralPath (Join-Path $FrameworkRoot "benchmark\minimal-production\src") `
            -Destination (Join-Path $fullContext "src") -Recurse

    if ($FeatureOff) {
        if (-not (Test-Path -LiteralPath $NoGlowrootBinary -PathType Leaf)) {
            throw "Feature-off Linux native binary is missing: $NoGlowrootBinary"
        }
        $replacement = Join-Path $fullContext "replacement"
        $nativeDirectory = Join-Path $replacement "native\linux-x64"
        New-Item -ItemType Directory -Force -Path $nativeDirectory | Out-Null
        Copy-Item -LiteralPath $NoGlowrootBinary `
                -Destination (Join-Path $nativeDirectory "librust_hyper.so")

        $currentManifest = Join-Path $FrameworkRoot "src\main\resources\native\native-provenance.properties"
        $manifestDirectory = Join-Path $replacement "native"
        $manifest = Get-Content -Raw -LiteralPath $currentManifest
        $hash = (Get-FileHash -LiteralPath $NoGlowrootBinary -Algorithm SHA256).Hash.ToLowerInvariant()
        $manifest = [regex]::Replace($manifest, '(?m)^linux-x64\.sha256=.*$', "linux-x64.sha256=$hash")
        [IO.File]::WriteAllText(
            (Join-Path $manifestDirectory "native-provenance.properties"),
            $manifest,
            [Text.UTF8Encoding]::new($false))
        Invoke-Checked jar @(
            "uf", (Join-Path $fullContext "framework.jar"),
            "-C", $replacement, "native") $ProjectRoot | Out-Null
        Remove-Item -LiteralPath $replacement -Recurse -Force
    }
}

$nativeSourceFingerprint = Get-NativeSourceFingerprint
if (-not $SkipNativeBuild) {
    $mount = "{0}:/work" -f ([IO.Path]::GetFullPath($NativeRoot))
    Invoke-Checked docker @(
        "run", "--rm", "-v", $mount, "-w", "/work",
        "-e", "CARGO_TARGET_DIR=/work/target-linux-no-glowroot",
        "rust:1.91-bookworm", "cargo", "build", "--release", "--no-default-features",
        "--features", "websocket,dubbo,redis") $NativeRoot | Out-Null
    [IO.File]::WriteAllText(
        $NoGlowrootFingerprint,
        "$nativeSourceFingerprint`n",
        [Text.UTF8Encoding]::new($false))
}
Assert-FeatureOffSourceFingerprint $nativeSourceFingerprint

Invoke-Checked mvn @("-q", "-DskipTests", "package") $FrameworkRoot | Out-Null
Invoke-Checked mvn @("-q", "package") $ProjectRoot | Out-Null
Invoke-Checked mvn @("-q", "-DskipTests", "package") $MockRoot | Out-Null

Prepare-Context -FeatureOff
Invoke-Checked docker @("build", "-t", $BaselineImage, "-f", "app.Dockerfile", ".") $ScriptDir | Out-Null
Prepare-Context
Invoke-Checked docker @("build", "-t", $CandidateImage, "-f", "app.Dockerfile", ".") $ScriptDir | Out-Null
Invoke-Checked docker @("build", "-t", $CollectorImage, ".") $MockRoot | Out-Null
Invoke-Checked docker @("build", "-t", $RunnerImage, "-f", "Dockerfile.benchmark", ".") `
        (Join-Path $FrameworkRoot "benchmark") | Out-Null

if (-not $SkipHostPreflight) {
    if ($HostStabilizationSeconds -gt 0) {
        Start-Sleep -Seconds $HostStabilizationSeconds
    }
    Assert-ReactorHostBenchmarkReadiness `
            -MaxAverageCpuPercent $MaxHostCpuAveragePercent `
            -MaxPeakCpuPercent $MaxHostCpuPeakPercent `
            -MinFreeVirtualMiB $MinHostFreeVirtualMiB
}

& (Join-Path $ScriptDir "footprint_attribution.ps1") `
        -RepeatCount $RepeatCount `
        -Concurrency $Concurrency `
        -Duration $Duration `
        -RequestsPerEndpoint $RequestsPerEndpoint `
        -ResultsDir $ResultsDir `
        -DisabledImage $BaselineImage `
        -NativeImage $CandidateImage `
        -JavaAgentImage $CandidateImage `
        -MaxMemoryRegressionMiB $MaxMemoryRegressionMiB `
        -MaxThreadDelta $MaxThreadDelta `
        -FailOnGate:$FailOnGate
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Feature artifact footprint report: $(Join-Path $ResultsDir 'REPORT.md')"
