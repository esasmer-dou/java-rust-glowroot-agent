$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "gate_statistics.ps1")
. (Join-Path $PSScriptRoot "warmup_statistics.ps1")

function Assert-Warmup([bool] $Expected, [double[]] $Samples, [string] $Message) {
    $decision = Get-ReactorWarmupDecision `
            -Samples $Samples `
            -WindowRounds 3 `
            -MaximumRobustTrendPercent 3 `
            -MaximumBorderlineRobustTrendPercent 5 `
            -MaximumBorderlineMedianShiftPercent 3 `
            -MaximumMedianAbsoluteDeviationPercent 4
    if ($decision.passed -ne $Expected) {
        throw ("$Message Expected=$Expected Actual=$($decision.passed) " +
                "Trend=$($decision.robust_trend_pct) MAD=$($decision.median_absolute_deviation_pct)")
    }
}

Assert-Warmup $true `
        @(29157.07, 29222.68, 29537.46, 31426.22, 28755.49, 29480.87) `
        "A single scheduler outlier without a sustained trend must pass."
Assert-Warmup $true `
        @(66517.44, 69807.87, 70093.68, 66308.17, 66527.76, 66045.18) `
        "Transient adjacent outliers without a sustained trend must pass."
Assert-Warmup $false `
        @(3496.0, 5187.0, 5354.0, 5223.0, 5634.0, 5826.0) `
        "A continuing OpenJ9/JIT ramp must fail."
Assert-Warmup $false `
        @(9751.40, 9754.20, 9994.31, 10071.69, 10039.93, 10147.95) `
        "A borderline trend with a material median-window shift must fail."
Assert-Warmup $true `
        @(6611.87, 6740.90, 6812.38, 6767.06, 7051.08, 6758.10) `
        "A borderline slope with matching window medians and low dispersion must pass."
Assert-Warmup $true `
        @(8439.49, 8533.92, 8687.37, 8761.17, 8781.21, 8729.19) `
        "A near-plateau trend below the hard boundary and with matching medians must pass."
Assert-Warmup $true `
        @(20100.0, 19950.0, 20050.0, 20020.0, 20080.0, 19980.0) `
        "A stable series must pass."
Assert-Warmup $false `
        @(18000.0, 20000.0, 22000.0, 22000.0, 20000.0, 18000.0) `
        "Repeated high dispersion must fail even when medians match."
Assert-Warmup $false `
        @(81421.66, 80864.75, 82219.98, 82966.04, 78501.34, 78451.49) `
        "A late execution-level shift must not pass without confirmation."
Assert-Warmup $true `
        @(78501.34, 78451.49, 78510.0, 78490.0, 78520.0, 78480.0) `
        "A confirmed stable execution level must pass without relaxing thresholds."
Assert-Warmup $false `
        @(18279.39, 19304.13, 21120.07, 21682.69, 21391.04, 21510.27) `
        "A late OpenJ9 ramp must remain blocked at the old confirmation boundary."
Assert-Warmup $true `
        @(21120.07, 21682.69, 21391.04, 21510.27, 21480.0, 21520.0) `
        "A bounded rolling window must accept the same process after a confirmed plateau."

$insufficientFailed = $false
try {
    Get-ReactorWarmupDecision -Samples @(1.0, 2.0, 3.0) -WindowRounds 3 | Out-Null
} catch {
    $insufficientFailed = $true
}
if (-not $insufficientFailed) { throw "An incomplete two-window sample must fail closed." }

foreach ($endpointIndex in 0..2) {
    $baselineFirst = 0
    foreach ($round in 1..16) {
        $order = @(Get-ReactorWarmupVariantOrder -Round $round -EndpointIndex $endpointIndex)
        if ($order.Count -ne 2 -or $order[0] -eq $order[1]) {
            throw "Warmup variant order must contain baseline and candidate exactly once."
        }
        if ($order[0] -eq "baseline") { $baselineFirst++ }
    }
    if ($baselineFirst -ne 8) {
        throw "Endpoint $endpointIndex must start with each variant exactly eight times."
    }
}

Write-Host "Warmup gate statistics: PASS"
