$ErrorActionPreference = 'Stop'
node (Join-Path $PSScriptRoot 'generate-proto.mjs')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
