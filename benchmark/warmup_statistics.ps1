function Get-ReactorWarmupDecision {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [double[]] $Samples,
        [int] $WindowRounds = 3,
        [double] $MaximumRobustTrendPercent = 3.0,
        [double] $MaximumBorderlineRobustTrendPercent = 4.0,
        [double] $MaximumBorderlineMedianShiftPercent = 3.0,
        [double] $MaximumMedianAbsoluteDeviationPercent = 4.0
    )

    if ($WindowRounds -lt 2) { throw "WindowRounds must be at least 2." }
    if ($Samples.Count -lt (2 * $WindowRounds)) {
        throw "Warmup decision requires at least two complete windows."
    }

    $previous = @($Samples | Select-Object -Last (2 * $WindowRounds) | Select-Object -First $WindowRounds)
    $recent = @($Samples | Select-Object -Last $WindowRounds)
    $combined = @($Samples | Select-Object -Last (2 * $WindowRounds))
    $previousMedian = Get-ReactorMedian -Values $previous
    $recentMedian = Get-ReactorMedian -Values $recent
    $combinedMedian = Get-ReactorMedian -Values $combined
    $medianShift = if ($previousMedian -le 0) {
        [double]::PositiveInfinity
    } else {
        100.0 * [math]::Abs($recentMedian - $previousMedian) / $previousMedian
    }
    $slopes = [Collections.Generic.List[double]]::new()
    for ($left = 0; $left -lt ($combined.Count - 1); $left++) {
        for ($right = $left + 1; $right -lt $combined.Count; $right++) {
            $slopes.Add(($combined[$right] - $combined[$left]) / ($right - $left))
        }
    }
    $theilSenSlope = Get-ReactorMedian -Values @($slopes)
    $robustTrend = if ($combinedMedian -le 0) {
        [double]::PositiveInfinity
    } else {
        100.0 * [math]::Abs($theilSenSlope) * ($combined.Count - 1) / $combinedMedian
    }
    $deviations = @($combined | ForEach-Object { [math]::Abs($_ - $combinedMedian) })
    $medianAbsoluteDeviation = if ($combinedMedian -le 0) {
        [double]::PositiveInfinity
    } else {
        100.0 * (Get-ReactorMedian -Values $deviations) / $combinedMedian
    }
    $minimum = ($combined | Measure-Object -Minimum).Minimum
    $maximum = ($combined | Measure-Object -Maximum).Maximum
    $rangeSpread = if ($combinedMedian -le 0) {
        [double]::PositiveInfinity
    } else {
        100.0 * ($maximum - $minimum) / $combinedMedian
    }

    $trendGateMode = if ($robustTrend -le $MaximumRobustTrendPercent) {
        "primary"
    } elseif ($robustTrend -le $MaximumBorderlineRobustTrendPercent `
            -and $medianShift -le $MaximumBorderlineMedianShiftPercent) {
        "borderline_confirmed"
    } else {
        "failed"
    }

    return [pscustomobject]@{
        previous_median_rps = $previousMedian
        recent_median_rps = $recentMedian
        combined_median_rps = $combinedMedian
        median_shift_pct = $medianShift
        theil_sen_slope_rps_per_round = $theilSenSlope
        robust_trend_pct = $robustTrend
        median_absolute_deviation_pct = $medianAbsoluteDeviation
        range_spread_pct = $rangeSpread
        trend_gate_mode = $trendGateMode
        passed = $trendGateMode -ne "failed" `
                -and $medianAbsoluteDeviation -le $MaximumMedianAbsoluteDeviationPercent
    }
}

function Get-ReactorWarmupVariantOrder([int] $Round, [int] $EndpointIndex) {
    if ($Round -lt 1 -or $EndpointIndex -lt 0) {
        throw "Warmup round and endpoint index must be non-negative and one-based/zero-based respectively."
    }
    if (($Round + $EndpointIndex) % 2 -eq 0) {
        return @("baseline", "candidate")
    }
    return @("candidate", "baseline")
}
