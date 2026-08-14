function Get-ReactorWarmupDecision {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [double[]] $Samples,
        [int] $WindowRounds = 3,
        [double] $MaximumMedianShiftPercent = 3.0,
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

    return [pscustomobject]@{
        previous_median_rps = $previousMedian
        recent_median_rps = $recentMedian
        combined_median_rps = $combinedMedian
        median_shift_pct = $medianShift
        median_absolute_deviation_pct = $medianAbsoluteDeviation
        range_spread_pct = $rangeSpread
        passed = $medianShift -le $MaximumMedianShiftPercent `
                -and $medianAbsoluteDeviation -le $MaximumMedianAbsoluteDeviationPercent
    }
}
