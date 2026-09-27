function Start-DocQueryFrontend([string]$ProjectRoot, [string]$ApiTarget) {
    $frontendRoot = Join-Path $ProjectRoot 'frontend'
    Push-Location $frontendRoot
    try {
        & pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }
    } finally { Pop-Location }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $frontendPort = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    $logPrefix = Join-Path ([IO.Path]::GetTempPath()) ('docquery-vue-e2e-' + [Guid]::NewGuid().ToString('N'))
    $previousTarget = $env:DOCQUERY_API_TARGET
    try {
        $env:DOCQUERY_API_TARGET = $ApiTarget
        $vite = Join-Path $frontendRoot 'scripts\dev.mjs'
        $process = Start-Process -FilePath 'node' -WorkingDirectory $frontendRoot `
            -ArgumentList @(('"' + $vite + '"'), '--host', '127.0.0.1', '--port', [string]$frontendPort, '--strictPort') `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput "$logPrefix.out.log" -RedirectStandardError "$logPrefix.err.log"
    } finally { $env:DOCQUERY_API_TARGET = $previousTarget }
    $frontendUrl = "http://127.0.0.1:$frontendPort"
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($process.HasExited) { throw "Vite exited; see $logPrefix.err.log" }
            try {
                if ((Invoke-WebRequest -UseBasicParsing -Uri "$frontendUrl/admin/" -TimeoutSec 2).StatusCode -eq 200) {
                    return @{ Process = $process; BaseUrl = $frontendUrl }
                }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        throw "Vite did not become ready; see $logPrefix.err.log"
    } catch {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
        throw
    }
}
function Stop-DocQueryFrontend($Frontend) {
    if ($Frontend -and -not $Frontend.Process.HasExited) {
        Stop-Process -Id $Frontend.Process.Id -Force -ErrorAction SilentlyContinue
        $Frontend.Process.WaitForExit(10000) | Out-Null
    }
}
