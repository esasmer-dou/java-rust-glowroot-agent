[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $WindowsBinary,

    [Parameter(Mandatory = $true)]
    [string] $WindowsMetadata,

    [Parameter(Mandatory = $true)]
    [string] $WindowsChecksum,

    [Parameter(Mandatory = $true)]
    [string] $LinuxBinary,

    [Parameter(Mandatory = $true)]
    [string] $LinuxMetadata,

    [Parameter(Mandatory = $true)]
    [string] $LinuxChecksum,

    [string] $NativeSourceDirectory = (Join-Path $PSScriptRoot "..\..\rust-spring"),

    [int] $GlowrootAbi = 3
)

$ErrorActionPreference = "Stop"

function Resolve-RequiredFile([string] $PathValue) {
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction Stop
    if (-not (Test-Path -LiteralPath $resolved.Path -PathType Leaf)) {
        throw "Native artifact is not a file: $PathValue"
    }
    return $resolved.Path
}

function Read-BuildMetadata([string] $PathValue, [string] $ExpectedArtifactName) {
    $values = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $PathValue)
    if ($values.schema -ne "1") {
        throw "Native build metadata schema must be 1: $PathValue"
    }
    if ($values.'artifact.name' -ne $ExpectedArtifactName) {
        throw "Unexpected native artifact metadata: $($values.'artifact.name')"
    }
    if ([string]::IsNullOrWhiteSpace($values.'source.revision')) {
        throw "Native build metadata has no source revision: $PathValue"
    }
    return $values
}

function Assert-ArtifactChecksum([string] $Binary, [string] $ChecksumFile) {
    $expected = ((Get-Content -Raw -LiteralPath $ChecksumFile).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $Binary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) {
        throw "Native CI checksum mismatch for ${Binary}: expected $expected but found $actual"
    }
    return $actual
}

$windowsSource = Resolve-RequiredFile $WindowsBinary
$linuxSource = Resolve-RequiredFile $LinuxBinary
$windowsMetaPath = Resolve-RequiredFile $WindowsMetadata
$linuxMetaPath = Resolve-RequiredFile $LinuxMetadata
$windowsChecksumPath = Resolve-RequiredFile $WindowsChecksum
$linuxChecksumPath = Resolve-RequiredFile $LinuxChecksum
$sourceRepository = (Resolve-Path -LiteralPath $NativeSourceDirectory).Path

$dirty = & git -C $sourceRepository status --porcelain
if ($LASTEXITCODE -ne 0 -or $dirty) {
    throw "Native source worktree must be clean before packaging: $sourceRepository"
}
$sourceRevision = (& git -C $sourceRepository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($sourceRevision)) {
    throw "Cannot read the native source revision"
}

$windowsMeta = Read-BuildMetadata $windowsMetaPath "windows-x64-rust-glowroot-agent"
$linuxMeta = Read-BuildMetadata $linuxMetaPath "linux-x64-rust-glowroot-agent"
if ($windowsMeta.'source.revision' -ne $sourceRevision -or $linuxMeta.'source.revision' -ne $sourceRevision) {
    throw "Native artifacts were not built from the clean checked-out revision $sourceRevision"
}

$windowsHash = Assert-ArtifactChecksum $windowsSource $windowsChecksumPath
$linuxHash = Assert-ArtifactChecksum $linuxSource $linuxChecksumPath
$resources = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\spring-runtime-core\src\main\resources\native"))
$windowsDirectory = Join-Path $resources "windows-x64"
$linuxDirectory = Join-Path $resources "linux-x64"
New-Item -ItemType Directory -Force -Path $resources, $windowsDirectory, $linuxDirectory | Out-Null
Copy-Item -LiteralPath $windowsSource -Destination (Join-Path $windowsDirectory "rust_glowroot_agent.dll") -Force
Copy-Item -LiteralPath $linuxSource -Destination (Join-Path $linuxDirectory "librust_glowroot_agent.so") -Force

$manifest = @"
schema=1
glowroot.abi=$GlowrootAbi
crate.version=0.1.0
source.revision=$sourceRevision
windows-x64.sha256=$windowsHash
linux-x64.sha256=$linuxHash
"@
[IO.File]::WriteAllText(
        (Join-Path $resources "native-provenance.properties"),
        ($manifest.TrimEnd() -replace "`r`n", "`n") + "`n",
        [Text.UTF8Encoding]::new($false))

Write-Host "Standalone Glowroot native artifacts synchronized from $sourceRevision."
Write-Host "  Windows SHA-256: $windowsHash"
Write-Host "  Linux SHA-256:   $linuxHash"
