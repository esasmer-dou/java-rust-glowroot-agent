function Assert-ReactorNativeRuntimeContract {
    param([string]$Manifest, [int]$Abi, [string]$SourceRevision)
    $values = ConvertFrom-StringData $Manifest
    if ($values.schema -ne '2' -or $values.'rest.abi' -ne "$Abi") {
        throw "Packaged REST native provenance must be schema 2 and ABI $Abi."
    }
    if ($values.'source.revision' -notmatch '^[0-9a-f]{40}$') {
        throw 'Packaged REST native source must be a clean, full commit id.'
    }
    if ($SourceRevision -and $values.'source.revision' -ne $SourceRevision) {
        throw "REST native source mismatch: expected $SourceRevision, found $($values.'source.revision')."
    }
    return $values.'source.revision'
}
