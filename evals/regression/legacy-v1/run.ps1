[CmdletBinding()]
param(
    [switch]$FullSuite,
    [switch]$Live,
    [switch]$Postgres
)
$ErrorActionPreference = 'Stop'
# PowerShell 7 native exit codes are collected below, not converted to exceptions.
$PSNativeCommandUseErrorActionPreference = $false
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Push-Location $repo
$oldLive = $env:PROCUREMENT_LIVE_EVAL
$oldPostgres = $env:PROCUREMENT_POSTGRES_IT
try {
    $manifest = Get-Content (Join-Path $PSScriptRoot 'fixtures.json') -Raw | ConvertFrom-Json
    foreach ($entry in $manifest.files) {
        $actual = (Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $entry.sha256) {
            throw "Frozen resource differs: $($entry.path). Check LF checkout; never update the hash to hide a change."
        }
    }
    if ($Live -and [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
        throw 'Live requires DEEPSEEK_API_KEY in the environment.'
    }
    if ($Postgres -and [string]::IsNullOrWhiteSpace($env:AGENT_STORAGE_POSTGRES_URL)) {
        throw 'Postgres requires AGENT_STORAGE_POSTGRES_URL pointing to a disposable test database.'
    }
    $env:PROCUREMENT_LIVE_EVAL = if ($Live) { 'true' } else { 'false' }
    $env:PROCUREMENT_POSTGRES_IT = if ($Postgres) { 'true' } else { 'false' }
    $runId = 'v0-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $output = Join-Path $repo "target/procurement-v0/$runId"
    New-Item -ItemType Directory -Path $output | Out-Null
    $head = (git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git revision.' }
    $initialStatus = @(git status --short)
    $environment = [ordered]@{
        startedAt = [DateTimeOffset]::Now.ToString('o'); commit = $head
        branch = (git branch --show-current); workingTreeClean = ($initialStatus.Count -eq 0)
        gitStatus = $initialStatus; java = @(& java -version 2>&1 | ForEach-Object { "$_" })
        maven = @(& mvn -version 2>&1 | ForEach-Object { "$_" })
        os = [Environment]::OSVersion.VersionString; powershell = "$($PSVersionTable.PSVersion)"
        live = [bool]$Live; postgres = [bool]$Postgres; fullSuite = [bool]$FullSuite
        fixtureManifest = $manifest
    }
    $environment | ConvertTo-Json -Depth 10 | Set-Content "$output/environment.json" -Encoding UTF8
    # Existing tests have fixed target paths. Preserve previous evidence before invoking them.
    foreach ($folder in @('procurement-evaluation', 'procurement-live-eval')) {
        if (Test-Path "target/$folder") {
            Copy-Item -LiteralPath "target/$folder" -Destination "$output/previous-$folder" -Recurse
        }
    }
    $results = [Collections.Generic.List[object]]::new()
    function Invoke-V0Maven([string]$phase, [string[]]$mavenArgs, [bool]$hasTests = $true) {
        $phaseStart = [DateTimeOffset]::Now
        $suffix = "$runId-$phase"
        $arguments = @('-B', "-Devaluation.codeRevision=$head", "-Dsurefire.reportNameSuffix=$suffix") + $mavenArgs
        $command = 'mvn ' + (($arguments | ForEach-Object { "'$_'" }) -join ' ')
        $command | Set-Content "$output/$phase-command.txt" -Encoding UTF8
        Write-Host "Running $phase (log: $output/$phase.log)"
        & mvn @arguments *> "$output/$phase.log"
        $exitCode = $LASTEXITCODE
        $rows = @()
        if ($hasTests) {
            $reports = @(Get-ChildItem "target/surefire-reports/TEST-*-$suffix.xml" -ErrorAction SilentlyContinue)
            New-Item -ItemType Directory -Path "$output/$phase-surefire" | Out-Null
            foreach ($report in $reports) {
                Copy-Item -LiteralPath $report.FullName -Destination "$output/$phase-surefire"
                [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
                $suite = $xml.testsuite
                $rows += [pscustomobject]@{
                    name = [string]$suite.name; tests = [int]$suite.tests
                    failures = [int]$suite.failures; errors = [int]$suite.errors; skipped = [int]$suite.skipped
                }
            }
            if ($reports.Count -eq 0 -and $exitCode -eq 0) { $exitCode = 1 }
        }
        $total = [int](($rows | Measure-Object tests -Sum).Sum)
        $failures = [int](($rows | Measure-Object failures -Sum).Sum)
        $errors = [int](($rows | Measure-Object errors -Sum).Sum)
        $skipped = [int](($rows | Measure-Object skipped -Sum).Sum)
        $result = [pscustomobject]@{
            phase = $phase; command = $command; exitCode = $exitCode
            startedAt = $phaseStart.ToString('o'); finishedAt = [DateTimeOffset]::Now.ToString('o')
            tests = $total; pass = $total-$failures-$errors-$skipped
            failures = $failures; errors = $errors; skip = $skipped; suites = $rows
        }
        $results.Add($result)
        $results.ToArray() | ConvertTo-Json -Depth 8 | Set-Content "$output/results.json" -Encoding UTF8
        # Full-suite tests regenerate fixed artifacts. Keep each phase's original evidence binding.
        if ($phase -in @('procurement', 'replay', 'full') -and (Test-Path 'target/procurement-evaluation')) {
            Copy-Item -LiteralPath 'target/procurement-evaluation' -Destination "$output/$phase-evaluation" -Recurse
        }
        Write-Host "$phase : exit=$exitCode total=$total pass=$($result.pass) failures=$failures errors=$errors skip=$skipped"
    }
    Invoke-V0Maven 'procurement' @('-Dtest=*Procurement*Tests,JdbcProcurementCaseStorePostgresIT', 'test')
    # Replay only the artifact produced by this invocation, never an older successful run.
    $artifact = 'target/procurement-evaluation/runtime-artifact.json'
    $producer = $results[0].suites | Where-Object { $_.name -like '*ProcurementAgentRuntimeE2ETests*' }
    if ($producer -and $producer.tests -gt $producer.skipped -and $producer.failures -eq 0 -and
        $producer.errors -eq 0 -and (Test-Path $artifact) -and
        (Get-Item $artifact).LastWriteTimeUtc -ge ([DateTimeOffset]::Parse($results[0].startedAt)).UtcDateTime) {
        Invoke-V0Maven 'replay' @(
            '-Dtest=ProcurementArtifactReplayTests,ProcurementAnswerReplayTests,ProcurementAnswerPolicyTests,ProcurementAnswerRelationTests,ProcurementAnswerCompletenessTests,ProcurementAnswerAggregationTests,ProcurementAnswerV3ReplayTests,ProcurementComparisonMetricsTests,ProcurementComparisonReportsTests',
            "-Devaluation.artifact=$artifact", "-Danswer.artifact=$artifact", "-Danswer.v2.artifact=$artifact",
            "-Drelation.artifact=$artifact", "-Dcompleteness.artifact=$artifact", "-Danswer.v3.artifact=$artifact", 'test'
        )
    } else {
        throw "Current Runtime artifact producer failed; replay blocked. See $output/results.json"
    }
    if ($Live) { Invoke-V0Maven 'live' @('-Dtest=ProcurementLiveModelEvalIT', 'test') }
    if ($FullSuite) { Invoke-V0Maven 'full' @('test') }
    Invoke-V0Maven 'build' @('-DskipTests', 'package') $false
    foreach ($folder in @('procurement-evaluation', 'procurement-live-eval')) {
        if (Test-Path "target/$folder") {
            # Do not label an old Live report as current evidence when Live was not requested.
            if ($folder -eq 'procurement-live-eval' -and -not $Live) { continue }
            if ($folder -eq 'procurement-live-eval') {
                $livePhase = $results | Where-Object { $_.phase -eq 'live' }
                if (-not (Test-Path "target/$folder/report.json") -or
                    (Get-Item "target/$folder/report.json").LastWriteTimeUtc -lt
                    ([DateTimeOffset]::Parse($livePhase.startedAt)).UtcDateTime) { continue }
            }
            Copy-Item -LiteralPath "target/$folder" -Destination "$output/$folder" -Recurse
        }
    }
    foreach ($entry in $manifest.files) {
        if ((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
            throw "Frozen resource changed during regression: $($entry.path)"
        }
    }
    git status --short | Set-Content "$output/git-status-after.txt" -Encoding UTF8
    Write-Host "Evidence: $output"
    if (@($results | Where-Object { $_.exitCode -ne 0 }).Count -gt 0) { exit 1 }
} finally {
    $env:PROCUREMENT_LIVE_EVAL = $oldLive
    $env:PROCUREMENT_POSTGRES_IT = $oldPostgres
    Pop-Location
}
