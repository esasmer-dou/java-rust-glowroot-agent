$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gate = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "spring_boot_gate.ps1")
$workflow = Get-Content -Raw -LiteralPath `
        (Join-Path $projectRoot ".github/workflows/production-gate.yml")
$release = Get-Content -Raw -LiteralPath `
        (Join-Path $projectRoot ".github/workflows/release.yml")

function Assert-Contains([string] $Text, [string] $Pattern, [string] $Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
}

function Get-FunctionText([string] $Text, [string] $Name, [string] $NextName) {
    $start = $Text.IndexOf("function $Name", [StringComparison]::Ordinal)
    $end = $Text.IndexOf("function $NextName", $start + 1, [StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -le $start) { throw "Cannot locate function $Name." }
    return $Text.Substring($start, $end - $start)
}

$warmup = Get-FunctionText $gate "Invoke-Warmup" "Invoke-InterleavedPreWarm"
$measurement = Get-FunctionText $gate "Invoke-Wrk" "Median"

Assert-Contains $gate 'function Start-LoadRunner' "The gate must start one persistent load runner."
Assert-Contains $warmup '@\("exec", \$LoadRunner, "wrk"' `
        "Warmup must use docker exec, not docker run."
if ($warmup -match 'docker\s+run|@\("run"') {
    throw "Warmup must not create a container per sample."
}
Assert-Contains $measurement 'docker exec -d \$LoadRunner' `
        "Measured wrk must reuse the persistent runner."
if ($measurement -match 'spring-glowroot-wrk-|docker\s+logs') {
    throw "Measured load must not create or inspect one-shot wrk containers."
}
Assert-Contains $gate '@\(\$Baseline, \$Candidate, \$Collector, \$LoadRunner\)' `
        "Gate cleanup must remove the persistent load runner."

if (([regex]::Matches($workflow, '-MaxWarmupRounds 6')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-MaxWarmupConfirmationRounds 10')).Count -ne 2 -or
        ([regex]::Matches($workflow, '-PreWarmCycles 2')).Count -ne 2) {
    throw "Spring and Rust-Java jobs must use the same bounded adaptive warmup contract."
}
Assert-Contains $workflow 'REACTOR_GATE_PAIR_REPEATS' `
        "Workflow must expose release and extended gate depths."
if (([regex]::Matches($release, '\.pair_repeats >= 3')).Count -ne 2 -or
        ([regex]::Matches($release, '\.warmup_stability\.fixed_rounds == 6')).Count -ne 2 -or
        ([regex]::Matches(
            $release,
            '\.warmup_stability\.load_runner_lifecycle == "persistent_per_gate"'
        )).Count -ne 2) {
    throw "Release evidence checks must enforce the optimized production-gate contract."
}

Write-Host "Persistent load-runner contract tests passed."
