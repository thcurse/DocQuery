param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$containerName = 'docquery-n43-e2e-' + ([Guid]::NewGuid().ToString('N').Substring(0, 12))
$mysqlPassword = 'n43-e2e-mysql-only'
$platformLogin = 'platform.e2e'
$platformPassword = 'Platform Browser Password 2026!'
$appProcess = $null
$jar = $null
$preexistingAppPids = @()
$appOut = Join-Path ([IO.Path]::GetTempPath()) ($containerName + '-app.out.log')
$appErr = Join-Path ([IO.Path]::GetTempPath()) ($containerName + '-app.err.log')

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Wait-Until([scriptblock]$Probe, [int]$Seconds, [string]$FailureMessage) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try { if (& $Probe) { return } } catch { }
        Start-Sleep -Milliseconds 750
    } while ([DateTime]::UtcNow -lt $deadline)
    throw $FailureMessage
}

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $projectRoot 'mvnw.cmd') -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'target') -Filter 'docquery-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw 'Packaged DocQuery JAR was not found under target' }
    if (-not $jar.FullName.StartsWith((Join-Path $projectRoot 'target'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Resolved JAR is outside the expected target directory'
    }

    $mysqlPort = Get-FreeTcpPort
    $appPort = Get-FreeTcpPort
    & docker run --detach --rm --name $containerName `
        --publish "127.0.0.1:${mysqlPort}:3306" `
        --env 'MYSQL_DATABASE=docquery' `
        --env 'MYSQL_USER=docquery' `
        --env "MYSQL_PASSWORD=$mysqlPassword" `
        --env "MYSQL_ROOT_PASSWORD=$mysqlPassword" `
        mysql:8.4.10 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Ephemeral MySQL container failed to start' }

    Wait-Until {
        $previousNativePreference = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
        try {
            $probeResult = & docker exec --env "MYSQL_PWD=$mysqlPassword" $containerName `
                mysql --protocol=TCP --host=127.0.0.1 --user=root `
                --batch --skip-column-names --execute 'SELECT 1' 2>$null
            return $LASTEXITCODE -eq 0 -and $probeResult -eq '1'
        } finally {
            $PSNativeCommandUseErrorActionPreference = $previousNativePreference
        }
    } 150 'Ephemeral MySQL did not become ready'

    $processEnvironment = @{
        'SERVER_PORT' = [string]$appPort
        'DOCQUERY_DB_URL' = "jdbc:mysql://127.0.0.1:${mysqlPort}/docquery?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
        'DOCQUERY_DB_USERNAME' = 'docquery'
        'DOCQUERY_DB_PASSWORD' = $mysqlPassword
        'DOCQUERY_OBJECT_STORAGE_ENABLED' = 'false'
        'DOCQUERY_RABBITMQ_INFRASTRUCTURE_ENABLED' = 'false'
        'DOCQUERY_OUTBOX_PUBLISHER_ENABLED' = 'false'
        'DOCQUERY_PROCESSING_LISTENER_ENABLED' = 'false'
        'DOCQUERY_SEARCH_ENABLED' = 'false'
        'DOCQUERY_RETRIEVAL_PROVIDER_ENABLED' = 'false'
        'DOCQUERY_QUERY_IDEMPOTENCY_ENABLED' = 'false'
    }
    $previousEnvironment = @{}
    foreach ($entry in $processEnvironment.GetEnumerator()) {
        $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    $preexistingAppPids = @(
        Get-CimInstance Win32_Process |
            Where-Object {
                $_.Name -eq 'java.exe' -and
                $_.CommandLine -like "*$($jar.FullName)*"
            } |
            Select-Object -ExpandProperty ProcessId
    )
    try {
        $appProcess = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName) `
            -PassThru -WindowStyle Hidden -RedirectStandardOutput $appOut -RedirectStandardError $appErr
    } finally {
        foreach ($entry in $previousEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }

    $baseUrl = "http://127.0.0.1:$appPort"
    Wait-Until {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/api/admin/v1/auth/csrf" -TimeoutSec 2
        return $response.StatusCode -eq 200
    } 120 'Spring Boot did not become ready'

    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        $passwordHash = & node -e "import bcrypt from 'bcryptjs'; process.stdout.write('{bcrypt}' + bcrypt.hashSync(process.argv[1], 10))" $platformPassword
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($passwordHash)) {
        throw 'Failed to create the test-only BCrypt hash'
    }
    $seedSql = "INSERT INTO admin_user (tenant_id, login_name, password_hash, role, status, created_at, updated_at) VALUES (NULL, '$platformLogin', '$passwordHash', '1', '1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));"
    & docker exec --env "MYSQL_PWD=$mysqlPassword" $containerName `
        mysql --user=docquery docquery "--execute=$seedSql"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to seed the platform administrator' }

    $env:DOCQUERY_E2E_BASE_URL = $baseUrl
    $env:DOCQUERY_E2E_PLATFORM_LOGIN = $platformLogin
    $env:DOCQUERY_E2E_PLATFORM_PASSWORD = $platformPassword
    $env:DOCQUERY_E2E_BROWSER_CHANNEL = 'msedge'
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        & npm.cmd run test:e2e
        if ($LASTEXITCODE -ne 0) { throw 'Playwright browser acceptance failed' }
    } finally {
        Pop-Location
        Remove-Item Env:DOCQUERY_E2E_BASE_URL -ErrorAction SilentlyContinue
        Remove-Item Env:DOCQUERY_E2E_PLATFORM_LOGIN -ErrorAction SilentlyContinue
        Remove-Item Env:DOCQUERY_E2E_PLATFORM_PASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:DOCQUERY_E2E_BROWSER_CHANNEL -ErrorAction SilentlyContinue
    }
} catch {
    $runningContainer = & docker ps --filter "name=^/${containerName}$" --format '{{.Names}}' 2>$null
    if ($runningContainer -eq $containerName) {
        Write-Host 'Ephemeral MySQL log tail:'
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & docker logs --tail 80 $containerName }
        finally { $ErrorActionPreference = $previousPreference }
    }
    if (Test-Path -LiteralPath $appErr) {
        Write-Host 'Spring Boot error log tail:'
        Get-Content -LiteralPath $appErr -Tail 80
    }
    if (Test-Path -LiteralPath $appOut) {
        Write-Host 'Spring Boot output log tail:'
        Get-Content -LiteralPath $appOut -Tail 120
    }
    throw
} finally {
    if ($appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
        $appProcess.WaitForExit(10000) | Out-Null
    }
    if ($jar) {
        $newAppProcesses = Get-CimInstance Win32_Process |
            Where-Object {
                $_.Name -eq 'java.exe' -and
                $_.CommandLine -like "*$($jar.FullName)*" -and
                $preexistingAppPids -notcontains $_.ProcessId
            }
        foreach ($process in $newAppProcesses) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    if ($containerName -match '^docquery-n43-e2e-[a-f0-9]{12}$') {
        $existingContainer = & docker ps --all --filter "name=^/${containerName}$" --format '{{.Names}}' 2>$null
        if ($existingContainer -eq $containerName) {
            & docker stop $containerName 2>$null | Out-Null
        }
    }
    Pop-Location
}
