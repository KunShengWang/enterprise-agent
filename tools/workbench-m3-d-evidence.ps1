param(
    [ValidateSet("DeterministicEval", "RoutingModel", "FaultRecovery", "BusinessE2E", "Full", "All")]
    [string]$Action = "DeterministicEval",
    [string]$DbUrl = $env:AGENT_STORAGE_POSTGRES_URL,
    [string]$DbUsername = $env:AGENT_STORAGE_POSTGRES_USERNAME,
    [string]$DbPassword = $env:AGENT_STORAGE_POSTGRES_PASSWORD
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot

function Invoke-MavenTests {
    param([string]$Selector)
    & mvn.cmd -q "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" "-Dtest=$Selector" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven evidence gate failed: $Selector"
    }
}

function Invoke-DeterministicEval {
    Invoke-MavenTests "WorkbenchRoutingEvalSuiteTests,WorkbenchM3DPolicyEvalTests,WorkbenchRoutingSafetyGateTests,WorkbenchBudgetEvalSuiteTests,IncidentCommandCoreEvalTests,IncidentRecoveryPlannerEvalTests,OrderCareM3EvalSuiteTests"
}

function Invoke-RoutingModelEval {
    $env:WORKBENCH_ROUTING_EVAL = "true"
    Invoke-MavenTests "WorkbenchRoutingRealModelEvalIT"
}

function Invoke-FaultRecovery {
    Set-PostgresEnvironment
    $env:WORKBENCH_POSTGRES_IT = "true"
    $env:INCIDENT_POSTGRES_IT = "true"
    Invoke-MavenTests "WorkCommandHandlerPostgresIT,JdbcRoutingStorePostgresIT,JdbcDispatchStorePostgresIT,UnifiedWorkEventProjectorPostgresIT,JdbcIncidentStorePostgresIT,JdbcIncidentRecoveryPlanStorePostgresIT"
}

function Invoke-BusinessE2E {
    Set-PostgresEnvironment
    $env:ORDERCARE_MODEL_EVAL = "true"
    $env:INCIDENT_COMMAND_E2E = "true"
    Invoke-MavenTests "OrderCareM3ModelEvalE2ETests,IncidentCommandRuntimeE2ETests"
}

function Set-PostgresEnvironment {
    if ([string]::IsNullOrWhiteSpace($DbUrl)) {
        $script:DbUrl = if ([string]::IsNullOrWhiteSpace($env:RAG_POSTGRES_URL)) {
            "jdbc:postgresql://localhost:5432/enterprise_agent"
        } else { $env:RAG_POSTGRES_URL }
    }
    if ([string]::IsNullOrWhiteSpace($DbUsername)) {
        $script:DbUsername = if ([string]::IsNullOrWhiteSpace($env:RAG_POSTGRES_USERNAME)) {
            "postgres"
        } else { $env:RAG_POSTGRES_USERNAME }
    }
    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        $script:DbPassword = $env:RAG_POSTGRES_PASSWORD
    }
    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        throw "PostgreSQL password is required through -DbPassword, AGENT_STORAGE_POSTGRES_PASSWORD or RAG_POSTGRES_PASSWORD"
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
    & mvn.cmd -q clean "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" test
    if ($LASTEXITCODE -ne 0) { throw "Full backend regression failed" }
    Push-Location (Join-Path $projectRoot "frontend")
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "Frontend production build failed" }
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
            Invoke-RoutingModelEval
            Invoke-FaultRecovery
            Invoke-BusinessE2E
            Invoke-FullRegression
        }
    }
}
finally {
    Pop-Location
}
