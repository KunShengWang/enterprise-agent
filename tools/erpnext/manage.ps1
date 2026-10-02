[CmdletBinding()]
param([ValidateSet('start','health','seed','reset','prepare','app','it','demo')][string]$Action='health')
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repo
function CheckExit { if($LASTEXITCODE -ne 0){throw "Command failed with exit $LASTEXITCODE"} }
function Assert-SiteReady {
    $siteContainer=& docker @compose ps -aq create-site; CheckExit
    if([string]::IsNullOrWhiteSpace($siteContainer)){throw 'Run start first: create-site container is absent'}
    $state=& docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' $siteContainer; CheckExit
    if($state.Trim() -ne 'exited 0'){throw "Site initialization is not complete: $state. Inspect create-site logs."}
}
function Import-RuntimeEnvironment {
    foreach($line in Get-Content 'target/erpnext/runtime.env') {
        if($line -match '^([A-Z_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
    }
    $env:ERPNEXT_WORKSPACE=$repo
}
try {
    New-Item -ItemType Directory -Force target/erpnext | Out-Null
    $compose=@('compose','--env-file','infra/erpnext/.env','-f','infra/erpnext/compose.yaml')
    switch($Action) {
        'start' {
            if(-not(Test-Path infra/erpnext/.env)) {
                $db=[guid]::NewGuid().ToString('N'); $admin=[guid]::NewGuid().ToString('N')
                [IO.File]::WriteAllText((Join-Path $repo 'infra/erpnext/.env'),"ERP_DB_PASSWORD=$db`nERP_ADMIN_PASSWORD=$admin`nERP_HTTP_PORT=8088`n")
            }
            & docker @compose up -d; CheckExit
            Write-Host 'Wait for create-site to exit 0; then run health and seed. Credentials remain in ignored local files.'
        }
        'health' {
            & docker @compose ps -a; CheckExit
            Assert-SiteReady
            $port=8088
            foreach($line in Get-Content infra/erpnext/.env){if($line -match '^ERP_HTTP_PORT=(\d+)$'){$port=[int]$Matches[1]}}
            $response=Invoke-RestMethod "http://localhost:$port/api/method/ping"
            if($response.message -ne 'pong'){throw 'ERPNext is not ready'}
            Write-Host 'ERPNext readiness: pong'
        }
        {$_ -in 'seed','reset'} {
            Assert-SiteReady
            $command=@('exec','-T','backend','/home/frappe/frappe-bench/env/bin/python','/workspace/tools/erpnext/seed.py')
            if($Action -eq 'reset'){$command+='reset'}
            & docker @compose @command; CheckExit
            # Seed writes the default URL; reflect the configured host port without exposing credentials.
            $port=8088
            foreach($line in Get-Content infra/erpnext/.env){if($line -match '^ERP_HTTP_PORT=(\d+)$'){$port=[int]$Matches[1]}}
            $runtime=Get-Content target/erpnext/runtime.env -Raw
            [IO.File]::WriteAllText((Join-Path $repo 'target/erpnext/runtime.env'),$runtime.Replace('http://localhost:8088',"http://localhost:$port"))
            Get-Content target/erpnext/seed-result.json
        }
        'prepare' {
            & mvn -B -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/erpnext/classpath.txt'; CheckExit
        }
        'app' {
            Import-RuntimeEnvironment
            & mvn spring-boot:run '-Dspring-boot.run.profiles=erpnext'; CheckExit
        }
        'it' {
            Import-RuntimeEnvironment
            $env:ERPNEXT_IT='true'
            & mvn -B '-Dtest=ErpNextProcurementIT' test; CheckExit
        }
        'demo' {
            Import-RuntimeEnvironment
            if([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)){throw 'Set DEEPSEEK_API_KEY before the real-model ERP demo'}
            $env:ERPNEXT_AGENT_DEMO='true'
            & mvn -B '-Dtest=ErpNextSingleAgentDemoIT' test; CheckExit
        }
    }
} finally { Pop-Location }
