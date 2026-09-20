param(
    [ValidateSet("DeterministicEval", "RoutingModel", "FaultRecovery", "BusinessE2E", "Full", "All")]
    [string]$Action = "DeterministicEval",
    [string]$DbUrl = "",
    [string]$DbUsername = "",
    [string]$DbPassword = "",
    [switch]$IsolatedTestDatabase,
    [switch]$EnableLiveModelEval
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$evidenceEnvironmentNames = @('WORKBENCH_ROUTING_EVAL', 'WORKBENCH_POSTGRES_IT',
    'AGENT_STORAGE_POSTGRES_URL', 'AGENT_STORAGE_POSTGRES_USERNAME', 'AGENT_STORAGE_POSTGRES_PASSWORD',
    'MEMORY_POSTGRES_URL', 'MEMORY_POSTGRES_USERNAME', 'MEMORY_POSTGRES_PASSWORD',
    'RAG_POSTGRES_URL', 'RAG_POSTGRES_USERNAME', 'RAG_POSTGRES_PASSWORD')
$evidenceEnvironmentBefore = @{}
foreach ($name in $evidenceEnvironmentNames) { $evidenceEnvironmentBefore[$name] = [Environment]::GetEnvironmentVariable($name) }
Push-Location $projectRoot

function Invoke-MavenTests {
    param([string]$Selector)
    & mvn.cmd -o -q "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" "-Dtest=$Selector" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven evidence gate failed: $Selector"
    }
}

function Invoke-DeterministicEval {
    Invoke-MavenTests "WorkbenchRoutingEvalSuiteTests,WorkbenchM3DPolicyEvalTests,WorkbenchRoutingSafetyGateTests,WorkbenchBudgetEvalSuiteTests"
}

function Invoke-RoutingModelEval {
    if (!$EnableLiveModelEval) { throw "RoutingModel requires explicit -EnableLiveModelEval; it is not an offline check" }
    $env:WORKBENCH_ROUTING_EVAL = "true"
    Invoke-MavenTests "WorkbenchRoutingRealModelEvalIT"
}

function Invoke-FaultRecovery {
    Set-PostgresEnvironment
    $env:WORKBENCH_POSTGRES_IT = "true"
    Invoke-MavenTests "WorkCommandHandlerPostgresIT,JdbcRoutingStorePostgresIT,JdbcDispatchStorePostgresIT,UnifiedWorkEventProjectorPostgresIT"
}

function Invoke-BusinessE2E {
    Invoke-MavenTests "ProcurementAgentRuntimeE2ETests,ProcurementRfqHitlTests"
}

function Set-PostgresEnvironment {
    if (!$IsolatedTestDatabase -or [string]::IsNullOrWhiteSpace($DbUrl)) {
        throw "FaultRecovery requires an explicitly isolated test database: -IsolatedTestDatabase -DbUrl"
    }
    if ($DbUrl -notmatch '^jdbc:postgresql://[^/]+/(?<database>[^?/#;]+)(?:\?.*)?$' -or
        $Matches.database -in @('enterprise_agent', 'postgres', 'template0', 'template1')) {
        throw "Provide a dedicated isolated PostgreSQL test database URL, never a default database"
    }
    if ([string]::IsNullOrWhiteSpace($DbUsername) -or [string]::IsNullOrWhiteSpace($DbPassword)) {
        throw "Explicit test database username and password are required; no environment/default fallback"
    }
    $env:AGENT_STORAGE_POSTGRES_URL = $DbUrl
    $env:AGENT_STORAGE_POSTGRES_USERNAME = $DbUsername
    $env:AGENT_STORAGE_POSTGRES_PASSWORD = $DbPassword
    $env:MEMORY_POSTGRES_URL = $DbUrl
    $env:MEMORY_POSTGRES_USERNAME = $DbUsername
    $env:MEMORY_POSTGRES_PASSWORD = $DbPassword
    $env:RAG_POSTGRES_URL = $DbUrl
    $env:RAG_POSTGRES_USERNAME = $DbUsername
    $env:RAG_POSTGRES_PASSWORD = $DbPassword
}

function Invoke-FullRegression {
    & mvn.cmd -o -q clean "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" test
    if ($LASTEXITCODE -ne 0) { throw "Full backend regression failed" }
    Push-Location (Join-Path $projectRoot "frontend")
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "Frontend production build failed" }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw "Frontend smoke failed" }
    }
    finally {
        Pop-Location
    }
}

try {
    switch ($Action) {
        "DeterministicEval" { Invoke-DeterministicEval }
        "RoutingModel" { Invoke-RoutingModelEval }
        "FaultRecovery" { Invoke-FaultRecovery }
        "BusinessE2E" { Invoke-BusinessE2E }
        "Full" { Invoke-FullRegression }
        "All" {
            Invoke-DeterministicEval
            Invoke-BusinessE2E
            Invoke-FullRegression
        }
    }
}
finally {
    foreach ($name in $evidenceEnvironmentNames) { [Environment]::SetEnvironmentVariable($name, $evidenceEnvironmentBefore[$name]) }
    Pop-Location
}
