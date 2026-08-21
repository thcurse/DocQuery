param(
    [switch]$SkipBuild,
    [switch]$ManualBrowser
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$namePrefix = "docquery-n44-e2e-$runId"
$containerNames = @(
    "$namePrefix-mysql",
    "$namePrefix-seaweedfs",
    "$namePrefix-rabbitmq",
    "$namePrefix-elasticsearch",
    "$namePrefix-redis"
)
$mysqlPassword = 'n44-e2e-mysql-only'
$rabbitPassword = 'n44-e2e-rabbit-only'
$redisPassword = 'n44-e2e-redis-only'
$storageAccessKey = 'n44-e2e-storage'
$storageSecretKey = 'n44-e2e-storage-secret'
$platformLogin = 'platform.e2e'
$platformPassword = 'Platform Browser Password 2026!'
$appProcess = $null
$fakeProcess = $null
$appClasspath = $null
$preexistingAppPids = @()
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) $namePrefix
$appOut = "$tempRoot-app.out.log"
$appErr = "$tempRoot-app.err.log"
$fakeOut = "$tempRoot-fake.out.log"
$fakeErr = "$tempRoot-fake.err.log"

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

function Test-ContainerCommand([string]$Name, [string[]]$Arguments) {
    $previousNativePreference = $PSNativeCommandUseErrorActionPreference
    $PSNativeCommandUseErrorActionPreference = $false
    try {
        & docker exec $Name @Arguments 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } finally {
        $PSNativeCommandUseErrorActionPreference = $previousNativePreference
    }
}

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $projectRoot 'mvnw.cmd') -DskipTests prepare-package
        if ($LASTEXITCODE -ne 0) { throw 'Maven prepare-package failed' }
    }
    $classesDirectory = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'target\classes')).Path
    $classpathFile = Join-Path $projectRoot 'target\n44-runtime-classpath.txt'
    & (Join-Path $projectRoot 'mvnw.cmd') dependency:build-classpath `
        "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=runtime'
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile)) {
        throw 'Maven runtime classpath resolution failed'
    }
    $dependencyClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($dependencyClasspath)) {
        throw 'Resolved runtime classpath is empty'
    }
    $appClasspath = "$classesDirectory;$dependencyClasspath"

    $mysqlPort = Get-FreeTcpPort
    $s3Port = Get-FreeTcpPort
    $rabbitPort = Get-FreeTcpPort
    $elasticsearchPort = Get-FreeTcpPort
    $redisPort = Get-FreeTcpPort
    $fakePort = Get-FreeTcpPort
    $appPort = Get-FreeTcpPort

    & docker run --detach --rm --name $containerNames[0] `
        --publish "127.0.0.1:${mysqlPort}:3306" `
        --env 'MYSQL_DATABASE=docquery' `
        --env 'MYSQL_USER=docquery' `
        --env "MYSQL_PASSWORD=$mysqlPassword" `
        --env "MYSQL_ROOT_PASSWORD=$mysqlPassword" `
        mysql:8.4.10 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Ephemeral MySQL container failed to start' }

    & docker run --detach --rm --name $containerNames[1] `
        --publish "127.0.0.1:${s3Port}:8333" `
        --env "AWS_ACCESS_KEY_ID=$storageAccessKey" `
        --env "AWS_SECRET_ACCESS_KEY=$storageSecretKey" `
        --env 'S3_BUCKET=docquery-source' `
        chrislusf/seaweedfs:4.40 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Ephemeral SeaweedFS container failed to start' }

    & docker run --detach --rm --name $containerNames[2] `
        --publish "127.0.0.1:${rabbitPort}:5672" `
        --env 'RABBITMQ_DEFAULT_USER=docquery' `
        --env "RABBITMQ_DEFAULT_PASS=$rabbitPassword" `
        rabbitmq:4.3.4-management | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Ephemeral RabbitMQ container failed to start' }

    & docker run --detach --rm --name $containerNames[3] `
        --publish "127.0.0.1:${elasticsearchPort}:9200" `
        --env 'discovery.type=single-node' `
        --env 'xpack.security.enabled=false' `
        --env 'ES_JAVA_OPTS=-Xms512m -Xmx512m' `
        docker.elastic.co/elasticsearch/elasticsearch:9.4.4 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Ephemeral Elasticsearch container failed to start' }

    & docker run --detach --rm --name $containerNames[4] `
        --publish "127.0.0.1:${redisPort}:6379" `
        redis:8.8.1 redis-server --appendonly no --requirepass $redisPassword | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Ephemeral Redis container failed to start' }

    Wait-Until {
        $previousNativePreference = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
        try {
            $probeResult = & docker exec --env "MYSQL_PWD=$mysqlPassword" $containerNames[0] `
                mysql --protocol=TCP --host=127.0.0.1 --user=root `
                --batch --skip-column-names --execute 'SELECT 1' 2>$null
            return $LASTEXITCODE -eq 0 -and $probeResult -eq '1'
        } finally {
            $PSNativeCommandUseErrorActionPreference = $previousNativePreference
        }
    } 150 'Ephemeral MySQL did not become ready'
    Wait-Until { Test-ContainerCommand $containerNames[1] @('wget', '-q', '-O', '-', 'http://127.0.0.1:9333/cluster/status') } 150 'Ephemeral SeaweedFS did not become ready'
    Wait-Until { Test-ContainerCommand $containerNames[2] @('rabbitmq-diagnostics', '-q', 'ping') } 180 'Ephemeral RabbitMQ did not become ready'
    Wait-Until { Test-ContainerCommand $containerNames[3] @('curl', '-fsS', 'http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow') } 240 'Ephemeral Elasticsearch did not become ready'
    Wait-Until {
        $previousNativePreference = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
        try {
            $probeResult = & docker exec --env "REDISCLI_AUTH=$redisPassword" $containerNames[4] `
                redis-cli ping 2>$null
            return $LASTEXITCODE -eq 0 -and $probeResult -eq 'PONG'
        } finally {
            $PSNativeCommandUseErrorActionPreference = $previousNativePreference
        }
    } 120 'Ephemeral Redis did not become ready'

    $previousFakePort = [Environment]::GetEnvironmentVariable('DOCQUERY_FAKE_PROVIDER_PORT', 'Process')
    [Environment]::SetEnvironmentVariable('DOCQUERY_FAKE_PROVIDER_PORT', [string]$fakePort, 'Process')
    try {
        $fakeProcess = Start-Process -FilePath 'node' `
            -ArgumentList @((Join-Path $projectRoot 'tools\n4.4\fake-provider.mjs')) `
            -PassThru -WindowStyle Hidden -WorkingDirectory $projectRoot `
            -RedirectStandardOutput $fakeOut -RedirectStandardError $fakeErr
    } finally {
        [Environment]::SetEnvironmentVariable('DOCQUERY_FAKE_PROVIDER_PORT', $previousFakePort, 'Process')
    }
    Wait-Until {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$fakePort/health" -TimeoutSec 2
        return $response.StatusCode -eq 200
    } 30 'Fake provider did not become ready'

    $processEnvironment = @{
        'SERVER_PORT' = [string]$appPort
        'DOCQUERY_DB_URL' = "jdbc:mysql://127.0.0.1:${mysqlPort}/docquery?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
        'DOCQUERY_DB_USERNAME' = 'docquery'
        'DOCQUERY_DB_PASSWORD' = $mysqlPassword
        'DOCQUERY_OBJECT_STORAGE_ENDPOINT' = "http://127.0.0.1:$s3Port"
        'DOCQUERY_OBJECT_STORAGE_ACCESS_KEY' = $storageAccessKey
        'DOCQUERY_OBJECT_STORAGE_SECRET_KEY' = $storageSecretKey
        'DOCQUERY_OBJECT_STORAGE_BUCKET' = 'docquery-source'
        'DOCQUERY_RABBITMQ_HOST' = '127.0.0.1'
        'DOCQUERY_RABBITMQ_PORT' = [string]$rabbitPort
        'DOCQUERY_RABBITMQ_USERNAME' = 'docquery'
        'DOCQUERY_RABBITMQ_PASSWORD' = $rabbitPassword
        'DOCQUERY_PROCESSING_LISTENER_ENABLED' = 'true'
        'DOCQUERY_DOCUMENT_DELETION_LISTENER_ENABLED' = 'true'
        'DOCQUERY_SEARCH_ENABLED' = 'true'
        'DOCQUERY_ELASTICSEARCH_ENDPOINT' = "http://127.0.0.1:$elasticsearchPort"
        'DOCQUERY_RETRIEVAL_PROVIDER_ENABLED' = 'true'
        'DOCQUERY_DEEPSEEK_BASE_URL' = "http://127.0.0.1:$fakePort/v1"
        'DEEPSEEK_API_KEY' = 'n44-fake-chat-key'
        'DOCQUERY_ALIBABA_EMBEDDING_BASE_URL' = "http://127.0.0.1:$fakePort/v1"
        'DASHSCOPE_API_KEY' = 'n44-fake-embedding-key'
        'DOCQUERY_REDIS_HOST' = '127.0.0.1'
        'DOCQUERY_REDIS_PORT' = [string]$redisPort
        'DOCQUERY_REDIS_PASSWORD' = $redisPassword
        'DOCQUERY_QUERY_IDEMPOTENCY_ENABLED' = 'true'
    }
    $previousEnvironment = @{}
    foreach ($entry in $processEnvironment.GetEnumerator()) {
        $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    $preexistingAppPids = @(
        Get-CimInstance Win32_Process |
            Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*com.doc.docquery.DocQueryApplication*' } |
            Select-Object -ExpandProperty ProcessId
    )
    try {
        $appProcess = Start-Process -FilePath 'java' -ArgumentList @('-cp', $appClasspath, 'com.doc.docquery.DocQueryApplication') `
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
    } 180 'Spring Boot did not become ready'

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
    & docker exec --env "MYSQL_PWD=$mysqlPassword" $containerNames[0] `
        mysql --user=docquery docquery "--execute=$seedSql"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to seed the platform administrator' }

    if ($ManualBrowser) {
        Write-Host "N4.4 browser environment ready: $baseUrl/admin/"
        Write-Host "Platform test login: $platformLogin"
        Write-Host 'Press Enter to stop the isolated browser environment.'
        [void](Read-Host)
    } else {
        $env:DOCQUERY_E2E_BASE_URL = $baseUrl
        $env:DOCQUERY_E2E_PLATFORM_LOGIN = $platformLogin
        $env:DOCQUERY_E2E_PLATFORM_PASSWORD = $platformPassword
        $env:DOCQUERY_E2E_BROWSER_CHANNEL = 'msedge'
        Push-Location (Join-Path $projectRoot 'frontend')
        try {
            & npx.cmd playwright test e2e/n44-showcase-flow.spec.ts
            if ($LASTEXITCODE -ne 0) { throw 'N4.4 Playwright browser acceptance failed' }
        } finally {
            Pop-Location
            Remove-Item Env:DOCQUERY_E2E_BASE_URL -ErrorAction SilentlyContinue
            Remove-Item Env:DOCQUERY_E2E_PLATFORM_LOGIN -ErrorAction SilentlyContinue
            Remove-Item Env:DOCQUERY_E2E_PLATFORM_PASSWORD -ErrorAction SilentlyContinue
            Remove-Item Env:DOCQUERY_E2E_BROWSER_CHANNEL -ErrorAction SilentlyContinue
        }
    }
} catch {
    foreach ($containerName in $containerNames) {
        $runningContainer = & docker ps --filter "name=^/${containerName}$" --format '{{.Names}}' 2>$null
        if ($runningContainer -eq $containerName) {
            Write-Host "Container log tail: $containerName"
            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { & docker logs --tail 60 $containerName }
            finally { $ErrorActionPreference = $previousPreference }
        }
    }
    foreach ($log in @($fakeErr, $fakeOut, $appErr, $appOut)) {
        if (Test-Path -LiteralPath $log) {
            Write-Host "Log tail: $log"
            Get-Content -LiteralPath $log -Tail 120
        }
    }
    throw
} finally {
    if ($appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
        $appProcess.WaitForExit(10000) | Out-Null
    }
    if ($appClasspath) {
        $newAppProcesses = Get-CimInstance Win32_Process |
            Where-Object {
                $_.Name -eq 'java.exe' -and
                $_.CommandLine -like '*com.doc.docquery.DocQueryApplication*' -and
                $preexistingAppPids -notcontains $_.ProcessId
            }
        foreach ($process in $newAppProcesses) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    if ($fakeProcess -and -not $fakeProcess.HasExited) {
        Stop-Process -Id $fakeProcess.Id -Force -ErrorAction SilentlyContinue
        $fakeProcess.WaitForExit(10000) | Out-Null
    }
    if ($namePrefix -match '^docquery-n44-e2e-[a-f0-9]{12}$') {
        foreach ($containerName in $containerNames) {
            $existingContainer = & docker ps --all --filter "name=^/${containerName}$" --format '{{.Names}}' 2>$null
            if ($existingContainer -eq $containerName) {
                & docker stop $containerName 2>$null | Out-Null
            }
        }
    }
    Pop-Location
}
