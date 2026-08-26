[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$verifier = Join-Path $PSScriptRoot "verify-production-gate.ps1"
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("glowroot-gate-test-" + [Guid]::NewGuid())
$commit = "a" * 40
$otherCommit = "b" * 40

function Write-JsonFixture([string] $Path, [object] $Value) {
    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Assert-VerifierFails([string] $RunsPath, [string] $ArtifactsPath, [string] $Description) {
    $shell = (Get-Process -Id $PID).Path
    & $shell -NoLogo -NoProfile -NonInteractive -File $verifier `
        -Commit $commit `
        -Repository "owner/repository" `
        -RunsFixturePath $RunsPath `
        -ArtifactsFixturePath $ArtifactsPath `
        -GitHubOutputPath "" *> $null
    $failureExitCode = $LASTEXITCODE
    $global:LASTEXITCODE = 0
    if ($failureExitCode -eq 0) {
        throw "Expected verifier failure: $Description"
    }
}

try {
    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
    $runsPath = Join-Path $tempRoot "runs.json"
    $artifactsPath = Join-Path $tempRoot "artifacts.json"
    $outputPath = Join-Path $tempRoot "github-output.txt"

    $passingRun = [ordered]@{
        workflow_runs = @(
            [ordered]@{
                id = 42
                head_sha = $commit
                event = "workflow_dispatch"
                status = "completed"
                conclusion = "success"
                created_at = "2026-08-26T12:00:00Z"
                html_url = "https://github.example/actions/runs/42"
            }
        )
    }
    $passingArtifacts = [ordered]@{
        artifacts = @(
            [ordered]@{ name = "spring-boot-production-gate"; expired = $false },
            [ordered]@{ name = "rust-java-rest-production-gate"; expired = $false }
        )
    }
    Write-JsonFixture $runsPath $passingRun
    Write-JsonFixture $artifactsPath $passingArtifacts

    & $verifier `
        -Commit $commit `
        -Repository "owner/repository" `
        -RunsFixturePath $runsPath `
        -ArtifactsFixturePath $artifactsPath `
        -GitHubOutputPath $outputPath
    $output = @(Get-Content -LiteralPath $outputPath)
    if ($output -notcontains "production_gate_run_id=42") {
        throw "Passing fixture did not publish the verified run id."
    }

    $mismatchedRun = $passingRun | ConvertTo-Json -Depth 8 | ConvertFrom-Json
    $mismatchedRun.workflow_runs[0].head_sha = $otherCommit
    Write-JsonFixture $runsPath $mismatchedRun
    Assert-VerifierFails $runsPath $artifactsPath "commit mismatch"

    Write-JsonFixture $runsPath $passingRun
    $expiredArtifacts = $passingArtifacts | ConvertTo-Json -Depth 8 | ConvertFrom-Json
    $expiredArtifacts.artifacts[1].expired = $true
    Write-JsonFixture $artifactsPath $expiredArtifacts
    Assert-VerifierFails $runsPath $artifactsPath "expired required artifact"

    $missingArtifacts = [ordered]@{
        artifacts = @([ordered]@{ name = "spring-boot-production-gate"; expired = $false })
    }
    Write-JsonFixture $artifactsPath $missingArtifacts
    Assert-VerifierFails $runsPath $artifactsPath "missing required artifact"

    Write-Host "Production Gate verifier tests passed."
    $global:LASTEXITCODE = 0
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
