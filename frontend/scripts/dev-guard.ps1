param(
    [Parameter(Mandatory = $true)][string]$NodePath,
    [int]$ParentId = 0,
    [string]$EntryPoint = '',
    [ValidateRange(96, 1536)][int]$ProcessLimitMiB = 1536,
    [ValidateRange(32, 768)][int]$HeapMiB = 768
)
$ErrorActionPreference = 'Stop'
try {
    if (-not $EntryPoint) { $EntryPoint = Join-Path $PSScriptRoot 'dev-worker.mjs' }
    Add-Type -Path (Join-Path $PSScriptRoot 'DevMemoryGuard.cs')
    $frontendRoot = Split-Path $PSScriptRoot -Parent
    $viteArgs = @()
    if ($env:DOCQUERY_DEV_ARGS) { $viteArgs = [string[]](ConvertFrom-Json -InputObject $env:DOCQUERY_DEV_ARGS) }
    $result = [DocQuery.DevMemoryGuard]::Run(
        $NodePath, $EntryPoint, [string[]]$viteArgs, $frontendRoot,
        $ProcessLimitMiB, $HeapMiB, $ParentId
    )
    exit $result
} catch {
    [Console]::Error.WriteLine('[DocQuery dev] Memory protection could not start: ' + $_.Exception.Message)
    exit 1
}
