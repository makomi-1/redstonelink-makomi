<#
.SYNOPSIS
RedstoneLink bench 自动化脚本入口壳。
#>
param(
	[ValidateSet("List", "PrintCase", "InstallDatapack", "RunCase", "RunFunctionalCase")][string]$Action = "List",
	[string]$CaseId,
	$CaseParameters = $null,
	[string]$MatrixPath = (Join-Path $PSScriptRoot "matrix.json"),
	[string]$SavePath = (Join-Path $PSScriptRoot "..\..\run\saves\rl-bench"),
	[string]$RconHost = "127.0.0.1",
	[int]$RconPort = 25575,
	[string]$RconPassword,
	[string]$AsPlayer,
	[switch]$AutoStartBenchClient,
	[string]$BenchClientPlayerName,
	[string]$BenchClientInstanceRoot,
	[string]$BenchClientWorkingDirectory,
	[string]$BenchClientStartCommand,
	[string]$BenchClientGameHost = "127.0.0.1",
	[int]$BenchClientGamePort = 25565,
	[int]$BenchClientInitialConnectDelayMs = 0,
	[int]$BenchClientReconnectIntervalMs = 1000,
	[int]$BenchClientStopTimeoutMs = 10000,
	[switch]$BenchClientFocusWindow,
	[int]$BenchClientFocusTimeoutMs = 15000,
	[switch]$BenchClientOpenTickCharts,
	[int]$BenchClientPostJoinActionDelayMs = 1000,
	[switch]$SyncLatestClientModJar,
	[switch]$BuildBeforeSyncLatestModJar,
	[string]$BuildTask = "remapJar",
	[string]$GradleWrapperPath = (Join-Path $PSScriptRoot "..\..\gradlew.bat"),
	[string]$ModJarPath,
	[string]$BenchClientModsDir,
	[int]$PlayerReadyTimeoutMs = 120000,
	[int]$PlayerReadyPollIntervalMs = 250,
	[string]$PlayerReadyProbeCommand = "data get entity @s Pos",
	[string[]]$PlayerSetupCommands = @(),
	[switch]$AutoTeleportPlayerToObservationPoint,
	[string]$SparkActivityPath,
	[int]$SparkActivityTimeoutMs = 30000,
	[int]$SparkActivityPollIntervalMs = 250,
	[switch]$SkipSpark,
	[switch]$DryRun
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$datapackSource = Join-Path $PSScriptRoot "datapack\rl_bench"
$script:DryRun = [bool]$DryRun
$script:DryRunSerialCounter = 1L
$script:DryRunGameTime = 0L
$script:DryRunInputJobCounter = 1L
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Client.ps1"))
if ($AutoStartBenchClient) {
	$AsPlayer = Assert-BenchClientIdentityCompatible -AsPlayer $AsPlayer -BenchClientPlayerName $BenchClientPlayerName
}
$script:BenchAsPlayer = $AsPlayer
$script:BenchAutoStartClient = [bool]$AutoStartBenchClient
$script:BenchClientPlayerName = if ([string]::IsNullOrWhiteSpace($BenchClientPlayerName)) { "" } else { $BenchClientPlayerName.Trim() }
$script:BenchClientInstanceRoot = if ([string]::IsNullOrWhiteSpace($BenchClientInstanceRoot)) { "" } else { $BenchClientInstanceRoot.Trim() }
$script:BenchClientWorkingDirectory = if ([string]::IsNullOrWhiteSpace($BenchClientWorkingDirectory)) { "" } else { $BenchClientWorkingDirectory.Trim() }
$script:BenchClientStartCommand = if ([string]::IsNullOrWhiteSpace($BenchClientStartCommand)) { "" } else { $BenchClientStartCommand.Trim() }
$script:BenchClientGameHost = if ([string]::IsNullOrWhiteSpace($BenchClientGameHost)) { "127.0.0.1" } else { $BenchClientGameHost.Trim() }
$script:BenchClientGamePort = [Math]::Max(1, [int]$BenchClientGamePort)
$script:BenchClientInitialConnectDelayMs = [Math]::Max(0, [int]$BenchClientInitialConnectDelayMs)
$script:BenchClientReconnectIntervalMs = [Math]::Max(250, [int]$BenchClientReconnectIntervalMs)
$script:BenchClientStopTimeoutMs = [Math]::Max(1000, [int]$BenchClientStopTimeoutMs)
$script:BenchClientFocusWindow = [bool]$BenchClientFocusWindow
$script:BenchClientFocusTimeoutMs = [Math]::Max(1000, [int]$BenchClientFocusTimeoutMs)
$script:BenchClientOpenTickCharts = [bool]$BenchClientOpenTickCharts
$script:BenchClientPostJoinActionDelayMs = [Math]::Max(0, [int]$BenchClientPostJoinActionDelayMs)
$script:BenchSyncLatestClientModJar = [bool]$SyncLatestClientModJar
$script:BenchBuildBeforeSyncLatestModJar = [bool]$BuildBeforeSyncLatestModJar
$script:BenchBuildTask = if ([string]::IsNullOrWhiteSpace($BuildTask)) { "remapJar" } else { $BuildTask.Trim() }
$script:BenchGradleWrapperPath = [string]$GradleWrapperPath
$script:BenchModJarPath = [string]$ModJarPath
$script:BenchClientModsDir = [string]$BenchClientModsDir
$script:BenchPlayerReadyTimeoutMs = [Math]::Max(0, [int]$PlayerReadyTimeoutMs)
$script:BenchPlayerReadyPollIntervalMs = [Math]::Max(50, [int]$PlayerReadyPollIntervalMs)
$script:BenchPlayerReadyProbeCommand = if ([string]::IsNullOrWhiteSpace($PlayerReadyProbeCommand)) {
	"data get entity @s Pos"
} else {
	([string]$PlayerReadyProbeCommand).Trim()
}
$script:BenchPlayerSetupCommands = @(
	@($PlayerSetupCommands) |
		Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
		ForEach-Object { ([string]$_).Trim() }
)
$script:BenchAutoTeleportPlayerToObservationPoint = [bool]$AutoTeleportPlayerToObservationPoint
# 按职责拆分 bench 执行脚本，入口文件只保留参数、全局状态与动作分发。
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Matrix.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.Config.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.World.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\BenchSuite.ModSync.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.World.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.PerfSpark.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Rcon.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.PlaceAndLink.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.FunctionalTrace.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Results.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.EntryPoint.ps1"))
