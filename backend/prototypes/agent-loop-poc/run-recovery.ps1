# THROWAWAY. Each java invocation is a NEW process. No API key or Docker required.
param([switch] $Batch)
$mainClass = if ($Batch) { 'prototype.BatchRecoveryPoc' } else { 'prototype.RecoveryPoc' }
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & .\mvnw.cmd -q -ntp compile org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath '-Dmdep.outputFile=target/classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Compilation/dependency resolution failed' }
    $classpath = (Join-Path $PSScriptRoot 'target/classes') + [IO.Path]::PathSeparator + (Get-Content 'target/classpath.txt' -Raw).Trim()
    $root = Join-Path $PSScriptRoot ('target/PROTOTYPE-wipe-me-' + [guid]::NewGuid().ToString('N'))
    function Run-Jvm([string[]] $Arguments) {
        & java -cp $classpath $mainClass @Arguments
        if ($LASTEXITCODE -ne 0) { throw ('JVM failed: ' + ($Arguments -join ' ')) }
    }
    foreach ($implementation in @('manual', 'agentic')) {
        foreach ($scenario in @('approve', 'reject', 'conflict')) {
            $scratch = Join-Path $root ($implementation + '-' + $scenario)
            Write-Output "`n=== $implementation / $scenario ==="
            Run-Jvm @($implementation, 'start', $scratch)
            if ($scenario -eq 'conflict') { Run-Jvm @($implementation, 'edit', $scratch) }
            $decision = if ($scenario -eq 'reject') { 'reject' } else { 'approve' }
            Run-Jvm @($implementation, 'resume', $scratch, $decision)
            # A further process repeats approval to expose accidental re-entry.
            Run-Jvm @($implementation, 'resume', $scratch, $decision)
        }
    }
    Write-Output "`nScratch state retained for inspection: $root"
} finally { Pop-Location }
