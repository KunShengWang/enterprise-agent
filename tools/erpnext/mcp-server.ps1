$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo
$dependencies = (Get-Content -LiteralPath 'target/erpnext/classpath.txt' -Raw).Trim()
$classpath = (Join-Path $repo 'target/classes') + [IO.Path]::PathSeparator + $dependencies
& java '-Dfile.encoding=UTF-8' -cp $classpath com.agent.platform.procurement.erp.ProcurementMcpServer
exit $LASTEXITCODE
