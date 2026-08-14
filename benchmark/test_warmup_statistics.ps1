$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "gate_statistics.ps1")
. (Join-Path $PSScriptRoot "warmup_statistics.ps1")

function Assert-Warmup([bool] $Expected, [double[]] $Samples, [string] $Message) {
    $decision = Get-ReactorWarmupDecision `
            -Samples $Samples `
            -WindowRounds 3 `
            -MaximumMedianShiftPercent 3 `
            -MaximumMedianAbsoluteDeviationPercent 4
    if ($decision.passed -ne $Expected) {
        throw ("$Message Expected=$Expected Actual=$($decision.passed) " +
                "Shift=$($decision.median_shift_pct) MAD=$($decision.median_absolute_deviation_pct)")
    }
}

Assert-Warmup $true `
        @(29157.07, 29222.68, 29537.46, 31426.22, 28755.49, 29480.87) `
        "A single scheduler outlier without a sustained trend must pass."
Assert-Warmup $false `
        @(3496.0, 5187.0, 5354.0, 5223.0, 5634.0, 5826.0) `
        "A continuing OpenJ9/JIT ramp must fail."
Assert-Warmup $true `
        @(20100.0, 19950.0, 20050.0, 20020.0, 20080.0, 19980.0) `
        "A stable series must pass."
Assert-Warmup $false `
        @(18000.0, 20000.0, 22000.0, 22000.0, 20000.0, 18000.0) `
        "Repeated high dispersion must fail even when medians match."

$insufficientFailed = $false
try {
    Get-ReactorWarmupDecision -Samples @(1.0, 2.0, 3.0) -WindowRounds 3 | Out-Null
} catch {
    $insufficientFailed = $true
}
if (-not $insufficientFailed) { throw "An incomplete two-window sample must fail closed." }

Write-Host "Warmup gate statistics: PASS"
