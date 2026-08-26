$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "gate_statistics.ps1")

function Assert-Equal([object] $Expected, [object] $Actual, [string] $Message) {
    if ($Expected -ne $Actual) { throw "$Message Expected=$Expected Actual=$Actual" }
}

function Sample([int] $Pair, [int64] $Requests, [int64] $Errors) {
    [pscustomobject]@{ pair = $Pair; requests = $Requests; non_2xx = $Errors }
}

# A noisy pair remains visible, while a better paired median and weighted total pass.
$baseline = @(Sample 1 100000 0; Sample 2 100000 200; Sample 3 100000 100)
$candidate = @(Sample 1 100000 20; Sample 2 100000 0; Sample 3 100000 0)
$decision = Get-ReactorNon2xxDecision -Baseline $baseline -Candidate $candidate
Assert-Equal $true $decision.passed "A lower aggregate and paired median must pass."
if ($decision.max_paired_delta_pp -le 0) { throw "The noisy paired maximum must remain observable." }

$aggregateRegression = Get-ReactorNon2xxDecision `
        -Baseline @(Sample 1 100000 0; Sample 2 100000 100; Sample 3 100000 100) `
        -Candidate @(Sample 1 100000 90; Sample 2 100000 90; Sample 3 100000 90)
Assert-Equal $false $aggregateRegression.passed "A weighted aggregate regression must fail."

$medianRegression = Get-ReactorNon2xxDecision `
        -Baseline @(Sample 1 100000 0; Sample 2 100000 0; Sample 3 100000 1000) `
        -Candidate @(Sample 1 100000 1; Sample 2 100000 1; Sample 3 100000 0)
Assert-Equal $false $medianRegression.passed "A paired-median regression must fail."

$peakRegression = Get-ReactorNon2xxDecision `
        -Baseline @(Sample 1 100000 100; Sample 2 100000 100; Sample 3 100000 100) `
        -Candidate @(Sample 1 100000 200; Sample 2 100000 0; Sample 3 100000 0)
Assert-Equal $false $peakRegression.passed "A new worst-case error envelope must fail."

$boundedSaturation = Get-ReactorNon2xxDecision `
        -Baseline @(Sample 1 100000 0; Sample 2 100000 0; Sample 3 100000 0) `
        -Candidate @(Sample 1 100000 0; Sample 2 100000 12; Sample 3 100000 0) `
        -MaximumDeltaPercentagePoints 0.02 `
        -MaximumAbsolutePercent 0.05
Assert-Equal $true $boundedSaturation.passed "A bounded saturated-cell reject must pass its explicit margin."

$absoluteRegression = Get-ReactorNon2xxDecision `
        -Baseline @(Sample 1 100000 60; Sample 2 100000 0; Sample 3 100000 0) `
        -Candidate @(Sample 1 100000 50; Sample 2 100000 0; Sample 3 100000 0) `
        -MaximumDeltaPercentagePoints 0.02 `
        -MaximumAbsolutePercent 0.05
Assert-Equal $false $absoluteRegression.passed "An unhealthy absolute error peak must fail even when candidate improves."

$mismatchFailed = $false
try {
    Get-ReactorNon2xxDecision `
            -Baseline @(Sample 1 1000 0) `
            -Candidate @(Sample 2 1000 0) | Out-Null
} catch {
    $mismatchFailed = $true
}
Assert-Equal $true $mismatchFailed "Mismatched pairs must fail closed."

Write-Host "Benchmark gate statistics: PASS"
