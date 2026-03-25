<#
.SYNOPSIS
RedstoneLink bench suite 编排脚本入口壳。
.DESCRIPTION
面向外部 dedicated server 的“每 case 一个新档”自动化流程：
1. 复制模板世界为新 case 世界
2. 最小修改 server.properties 的 level-name
3. 启动 dedicated server 并等待 RCON 就绪
4. 调用现有 run-bench.ps1 执行单 case
5. 收集 bench 结果并发送 stop
#>
param(
    [string]$ServerRoot = "D:\OpenProjects\RedstoneLink\mcserver",
    [string]$ServerPropertiesPath,
    [string]$ServerStartCommand,
    [string]$TemplateWorldPath = "D:\OpenProjects\RedstoneLink\mcserver\rl-bench-template",
    [string[]]$CaseIds,
    [string]$SuitePath,
    [ValidateSet("RunCase", "RunFunctionalCase")][string]$BenchAction = "RunCase",
    [string]$MatrixPath = (Join-Path $PSScriptRoot "matrix.json"),
    [string]$RconHost = "127.0.0.1",
    [int]$RconPort = 25575,
    [Alias("RconPassword")]
    $RconSecret,
    [string]$AsPlayer,
    [string]$SparkActivityPath,
    [switch]$SyncLatestModJar,
    [switch]$BuildBeforeSyncLatestModJar,
    [string]$BuildTask = "remapJar",
    [string]$GradleWrapperPath = (Join-Path $PSScriptRoot "..\..\gradlew.bat"),
    [string]$ModJarPath,
    [string]$ServerModsDir,
    [int]$StartupTimeoutMs = 180000,
    [int]$StartupPollIntervalMs = 1000,
    [int]$ShutdownTimeoutMs = 60000,
    [int]$ShutdownPollIntervalMs = 1000,
    [string]$CaseWorldPrefix = "rl-case",
    [string]$SuiteResultsDir = (Join-Path $PSScriptRoot "..\..\run\profiles\bench-suite-results"),
    [switch]$DeleteCaseWorldOnSuccess,
    [switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$caseWorldsDirectoryName = "rl-cases"
if ([string]::IsNullOrWhiteSpace($ServerPropertiesPath) -and -not [string]::IsNullOrWhiteSpace($ServerRoot)) {
    $ServerPropertiesPath = Join-Path $ServerRoot "server.properties"
}

# suite 建立在单 case bench 引擎之上，直接复用 matrix/case 读取能力。
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Matrix.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.Config.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.World.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.ModSync.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.Results.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.Server.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.EntryPoint.ps1"))
