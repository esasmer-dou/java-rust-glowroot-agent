[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $Commit,

    [string] $Repository = $env:GITHUB_REPOSITORY,
    [string] $WorkflowFile = "production-gate.yml",
    [string[]] $RequiredArtifacts = @(
        "spring-boot-production-gate",
        "rust-java-rest-production-gate"
    ),
    [string] $RunsFixturePath = "",
    [string] $ArtifactsFixturePath = "",
    [string] $GitHubOutputPath = $env:GITHUB_OUTPUT
)

$ErrorActionPreference = "Stop"

if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') {
    throw "Repository must use the owner/name form."
}

function Invoke-GitHubJson([string] $Uri) {
    $token = "$env:PRODUCTION_GATE_TOKEN"
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "PRODUCTION_GATE_TOKEN is required when fixture files are not used."
    }
    $headers = @{
        Authorization = "Bearer $token"
        Accept = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
    Invoke-RestMethod -Uri $Uri -Headers $headers
}

$encodedWorkflow = [Uri]::EscapeDataString($WorkflowFile)
if ([string]::IsNullOrWhiteSpace($RunsFixturePath)) {
    $runsUri = "https://api.github.com/repos/$Repository/actions/workflows/$encodedWorkflow/runs" +
            "?event=workflow_dispatch&status=completed&head_sha=$Commit&per_page=100"
    $runsResponse = Invoke-GitHubJson $runsUri
} else {
    $runsResponse = Get-Content -Raw -LiteralPath $RunsFixturePath | ConvertFrom-Json
}

$successfulRuns = @(
    $runsResponse.workflow_runs |
        Where-Object {
            "$($_.head_sha)" -eq $Commit `
                -and "$($_.event)" -eq "workflow_dispatch" `
                -and "$($_.status)" -eq "completed" `
                -and "$($_.conclusion)" -eq "success"
        } |
        Sort-Object { [DateTimeOffset] "$($_.created_at)" } -Descending
)
if ($successfulRuns.Count -eq 0) {
    throw "No successful Production Gate workflow_dispatch run exists for exact commit $Commit."
}

$gateRun = $successfulRuns[0]
if ([string]::IsNullOrWhiteSpace($ArtifactsFixturePath)) {
    $artifactsUri = "https://api.github.com/repos/$Repository/actions/runs/$($gateRun.id)/artifacts?per_page=100"
    $artifactsResponse = Invoke-GitHubJson $artifactsUri
} else {
    $artifactsResponse = Get-Content -Raw -LiteralPath $ArtifactsFixturePath | ConvertFrom-Json
}

$artifacts = @($artifactsResponse.artifacts)
foreach ($requiredArtifact in $RequiredArtifacts) {
    $matching = @(
        $artifacts | Where-Object {
            "$($_.name)" -eq $requiredArtifact -and -not [bool] $_.expired
        }
    )
    if ($matching.Count -ne 1) {
        throw "Production Gate run $($gateRun.id) must expose one non-expired '$requiredArtifact' artifact; found $($matching.Count)."
    }
}

if (-not [string]::IsNullOrWhiteSpace($GitHubOutputPath)) {
    "production_gate_run_id=$($gateRun.id)" | Add-Content -LiteralPath $GitHubOutputPath -Encoding utf8
    "production_gate_url=$($gateRun.html_url)" | Add-Content -LiteralPath $GitHubOutputPath -Encoding utf8
}

Write-Host "Production Gate verified: commit=$Commit run=$($gateRun.id) artifacts=$($RequiredArtifacts -join ',')"
