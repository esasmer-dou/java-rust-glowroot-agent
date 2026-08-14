function Get-ReactorMedian([double[]] $Values) {
    if ($Values.Count -eq 0) { throw "Median requires at least one value." }
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count % 2 -eq 1) {
        return [double]$sorted[[int][math]::Floor($sorted.Count / 2.0)]
    }
    $upper = [int]($sorted.Count / 2)
    return ([double]$sorted[$upper - 1] + [double]$sorted[$upper]) / 2.0
}

function Get-ReactorNon2xxDecision {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [object[]] $Baseline,
        [Parameter(Mandatory)]
        [object[]] $Candidate,
        [double] $MaximumDeltaPercentagePoints = 0.0,
        [double] $MaximumAbsolutePercent = 100.0
    )

    if ($Baseline.Count -eq 0 -or $Baseline.Count -ne $Candidate.Count) {
        throw "Non-2xx decision requires equal, non-empty baseline and candidate samples."
    }

    $baselineByPair = @{}
    $candidateByPair = @{}
    foreach ($sample in $Baseline) {
        if ($baselineByPair.ContainsKey($sample.pair)) { throw "Duplicate baseline pair: $($sample.pair)" }
        if ([int64]$sample.requests -le 0) { throw "Baseline pair $($sample.pair) has no requests." }
        $baselineByPair[$sample.pair] = $sample
    }
    foreach ($sample in $Candidate) {
        if ($candidateByPair.ContainsKey($sample.pair)) { throw "Duplicate candidate pair: $($sample.pair)" }
        if ([int64]$sample.requests -le 0) { throw "Candidate pair $($sample.pair) has no requests." }
        $candidateByPair[$sample.pair] = $sample
    }

    $pairedDeltas = [Collections.Generic.List[double]]::new()
    foreach ($pair in @($baselineByPair.Keys | Sort-Object)) {
        if (-not $candidateByPair.ContainsKey($pair)) { throw "Candidate pair $pair is missing." }
        $baselineSample = $baselineByPair[$pair]
        $candidateSample = $candidateByPair[$pair]
        $baselineRate = 100.0 * [double]$baselineSample.non_2xx / [double]$baselineSample.requests
        $candidateRate = 100.0 * [double]$candidateSample.non_2xx / [double]$candidateSample.requests
        $pairedDeltas.Add($candidateRate - $baselineRate)
    }
    if ($candidateByPair.Count -ne $baselineByPair.Count) {
        throw "Candidate contains a pair that is absent from baseline."
    }

    $baselineRequests = [double](($Baseline.requests | Measure-Object -Sum).Sum)
    $candidateRequests = [double](($Candidate.requests | Measure-Object -Sum).Sum)
    $baselineErrors = [double](($Baseline.non_2xx | Measure-Object -Sum).Sum)
    $candidateErrors = [double](($Candidate.non_2xx | Measure-Object -Sum).Sum)
    $baselineAggregateRate = 100.0 * $baselineErrors / $baselineRequests
    $candidateAggregateRate = 100.0 * $candidateErrors / $candidateRequests
    $baselinePeakRate = ($Baseline | ForEach-Object {
            100.0 * [double]$_.non_2xx / [double]$_.requests
        } | Measure-Object -Maximum).Maximum
    $candidatePeakRate = ($Candidate | ForEach-Object {
            100.0 * [double]$_.non_2xx / [double]$_.requests
        } | Measure-Object -Maximum).Maximum
    $medianDelta = Get-ReactorMedian -Values @($pairedDeltas)
    $aggregateDelta = $candidateAggregateRate - $baselineAggregateRate

    return [pscustomobject]@{
        baseline_aggregate_pct = $baselineAggregateRate
        candidate_aggregate_pct = $candidateAggregateRate
        baseline_peak_pct = $baselinePeakRate
        candidate_peak_pct = $candidatePeakRate
        median_paired_delta_pp = $medianDelta
        aggregate_delta_pp = $aggregateDelta
        max_paired_delta_pp = ($pairedDeltas | Measure-Object -Maximum).Maximum
        passed = $baselineAggregateRate -le $MaximumAbsolutePercent `
                -and $candidateAggregateRate -le $MaximumAbsolutePercent `
                -and $baselinePeakRate -le $MaximumAbsolutePercent `
                -and $candidatePeakRate -le $MaximumAbsolutePercent `
                -and $medianDelta -le $MaximumDeltaPercentagePoints `
                -and $aggregateDelta -le $MaximumDeltaPercentagePoints `
                -and ($candidatePeakRate - $baselinePeakRate) -le $MaximumDeltaPercentagePoints
    }
}
