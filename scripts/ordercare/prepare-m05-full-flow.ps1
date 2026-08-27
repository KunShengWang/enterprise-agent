param(
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$Database = 'floworder',
    [string]$DbUser = 'root',
    [string]$DbPassword = $env:FLOWORDER_MYSQL_PASSWORD,
    [string]$FlowOrderRepo = ''
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
    throw 'mysql client was not found. Install it or add it to PATH.'
}

if ([string]::IsNullOrWhiteSpace($FlowOrderRepo)) {
    $FlowOrderRepo = Join-Path $PSScriptRoot '..\..\..\floworder'
}

$flowOrderRoot = (Resolve-Path -LiteralPath $FlowOrderRepo).Path
$orderCareScriptRoot = Join-Path $flowOrderRoot 'scripts\ordercare'
$sqlRoot = Join-Path $orderCareScriptRoot 'sql'
$m3Script = Join-Path $orderCareScriptRoot 'm3-fault-recovery.ps1'
$baselineScript = Join-Path $orderCareScriptRoot 'm0.5-recovery-baseline.ps1'
$m2CleanupSql = Join-Path $sqlRoot 'm2-cleanup.sql'

foreach ($requiredPath in @($m3Script, $baselineScript, $m2CleanupSql)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "required FlowOrder file was not found: $requiredPath"
    }
}

function Invoke-MySqlFile([string]$SqlFile) {
    $resolvedSqlFile = (Resolve-Path -LiteralPath $SqlFile).Path.Replace('\', '/')
    $previousPassword = $env:MYSQL_PWD
    if (-not [string]::IsNullOrWhiteSpace($DbPassword)) {
        $env:MYSQL_PWD = $DbPassword
    }
    try {
        & mysql `
            "--host=$DbHost" `
            "--port=$DbPort" `
            "--user=$DbUser" `
            "--database=$Database" `
            '--default-character-set=utf8mb4' `
            "--execute=source $resolvedSqlFile"
        if ($LASTEXITCODE -ne 0) {
            throw "mysql failed while executing $resolvedSqlFile"
        }
    }
    finally {
        $env:MYSQL_PWD = $previousPassword
    }
}

Write-Host '[1/3] Applying OrderCare M2/M3 recovery migrations...'
& $m3Script `
    -Action Migrate `
    -DbHost $DbHost `
    -DbPort $DbPort `
    -Database $Database `
    -DbUser $DbUser `
    -DbPassword $DbPassword

Write-Host '[2/3] Removing previous ORDERCARE-M05 Proposal and Action records...'
Invoke-MySqlFile $m2CleanupSql

Write-Host '[3/3] Recreating the ORDERCARE-M05 timeout/dead-letter fixture...'
& $baselineScript `
    -Action Inject `
    -DbHost $DbHost `
    -DbPort $DbPort `
    -Database $Database `
    -DbUser $DbUser `
    -DbPassword $DbPassword

Write-Host ''
Write-Host 'OrderCare fixture is ready.' -ForegroundColor Green
Write-Host 'requestId    = ORDERCARE-M05-REQUEST'
Write-Host 'orderNo      = ORDERCARE-M05-ORDER'
Write-Host 'deductNo     = ORDERCARE-M05-DEDUCT'
Write-Host 'deadLetterId = 9000000000000505'
Write-Host ''
Write-Host 'Next: start FlowOrder order/resource services, RabbitMQ and enterprise-agent, then submit the full OrderCare prompt.'
