<#
.SYNOPSIS
运行性能 suite 的统一包装入口。
.DESCRIPTION
封装 dedicated server 与 Prism 外部客户端的常用性能 bench 参数。
默认开启：
1. 外部客户端自动启动并自动进服
2. 玩家自动传送到观察位
3. 客户端窗口聚焦
4. 自动打开 F3+2 tick 曲线
#>
param(
	[ValidateSet("lite", "heavy")][string]$Mode,
	[switch]$BuildSync,
	[string]$ServerRoot,
	[string]$TemplateWorldPath,
	[string]$ServerStartCommand = ".\start.bat",
	[ValidateSet("Normal", "Minimized", "Hidden")][string]$ServerWindowMode = "Hidden",
	[string]$ServerPriorityClass = "High",
	[string]$RconHost = "127.0.0.1",
	[int]$RconPort = 25575,
	[Alias("RconPassword")]
	$RconSecret = "redstonelink-bench",
	[string[]]$CaseIds,
	[string]$SparkActivityPath,
	[string]$BenchClientPlayerName = "op",
	[string]$BenchClientProfile,
	[string]$BenchClientLaunchTarget = "1.21.1",
	[string]$BenchClientGameHost = "127.0.0.1",
	[int]$BenchClientGamePort = 25565,
	[string]$PrismLauncherPath,
	[string]$PrismRootDir,
	[string]$BenchClientInstanceRoot,
	[string]$BenchClientWorkingDirectory,
	[string]$BenchClientModsDir,
	[string]$BenchClientStartCommand,
	[string]$AsPlayer,
	[string[]]$PlayerSetupCommands = @("gamemode spectator @s"),
	[int]$PlayerReadyTimeoutMs = 180000,
	[int]$PlayerReadyPollIntervalMs = 250,
	[string]$PlayerReadyProbeCommand = "data get entity @s Pos",
	[int]$BenchClientInitialConnectDelayMs = 0,
	[int]$BenchClientReconnectIntervalMs = 1000,
	[int]$BenchClientStopTimeoutMs = 10000,
	[int]$BenchClientFocusTimeoutMs = 15000,
	[int]$BenchClientPostJoinActionDelayMs = 1000,
	[string]$BuildTask = "remapJar",
	[string]$ModJarPath,
	[string]$ServerModsDir,
	[switch]$DeleteCaseWorldOnSuccess,
	[switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Get-Command Get-BenchConfiguredServerRootPath -ErrorAction SilentlyContinue)) {
	. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.PathConfig.ps1"))
}

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
	$ServerRoot = Get-BenchConfiguredServerRootPath
}
if ([string]::IsNullOrWhiteSpace($TemplateWorldPath)) {
	$TemplateWorldPath = Get-BenchConfiguredTemplateWorldPath -ServerRootPath $ServerRoot
}
if ([string]::IsNullOrWhiteSpace($PrismLauncherPath)) {
	$PrismLauncherPath = Get-BenchConfiguredPrismLauncherPath
}
if ([string]::IsNullOrWhiteSpace($PrismRootDir)) {
	$PrismRootDir = Get-BenchConfiguredPrismRootDirPath
}

function Format-PerformanceSuiteArgument {
	param(
		[string]$Value
	)
	if ($null -eq $Value) {
		return '""'
	}
	return '"' + ([string]$Value).Replace('"', '\"') + '"'
}

function New-PrismBenchClientStartCommand {
	param(
		[string]$LauncherPath,
		[string]$LauncherRootDirectory,
		[string]$LaunchTarget,
		[string]$ProfileName,
		[string]$GameHost,
		[int]$GamePort
	)
	$serverAddress = "{0}:{1}" -f $GameHost, $GamePort
	return (
		"{0} --dir {1} --launch {2} --profile {3} --server {4}" -f
		(Format-PerformanceSuiteArgument -Value $LauncherPath),
		(Format-PerformanceSuiteArgument -Value $LauncherRootDirectory),
		(Format-PerformanceSuiteArgument -Value $LaunchTarget),
		(Format-PerformanceSuiteArgument -Value $ProfileName),
		(Format-PerformanceSuiteArgument -Value $serverAddress)
	)
}

function Get-PerformanceSuitePath {
	param(
		[string]$ModeName
	)
	switch ([string]$ModeName) {
		"lite" { return (Join-Path $PSScriptRoot "suites\performance-lite.json") }
		"heavy" { return (Join-Path $PSScriptRoot "suites\performance-heavy.json") }
		default { throw "Unsupported performance suite mode: $ModeName" }
	}
}

if ([string]::IsNullOrWhiteSpace($BenchClientProfile)) {
	$BenchClientProfile = $BenchClientPlayerName
}
if ([string]::IsNullOrWhiteSpace($BenchClientInstanceRoot)) {
	$BenchClientInstanceRoot = Join-Path $PrismRootDir ("instances\{0}\minecraft" -f $BenchClientLaunchTarget)
}
if ([string]::IsNullOrWhiteSpace($BenchClientStartCommand)) {
	$BenchClientStartCommand = New-PrismBenchClientStartCommand `
		-LauncherPath $PrismLauncherPath `
		-LauncherRootDirectory $PrismRootDir `
		-LaunchTarget $BenchClientLaunchTarget `
		-ProfileName $BenchClientProfile `
		-GameHost $BenchClientGameHost `
		-GamePort $BenchClientGamePort
}
if ([string]::IsNullOrWhiteSpace($AsPlayer)) {
	$AsPlayer = $BenchClientPlayerName
}

$invokeArgs = @{
	ServerRoot = $ServerRoot
	TemplateWorldPath = $TemplateWorldPath
	ServerStartCommand = $ServerStartCommand
	ServerWindowMode = $ServerWindowMode
	ServerPriorityClass = $ServerPriorityClass
	SuitePath = (Get-PerformanceSuitePath -ModeName $Mode)
	RconHost = $RconHost
	RconPort = $RconPort
	RconPassword = $RconSecret
	AsPlayer = $AsPlayer
	AutoStartBenchClient = $true
	BenchClientPlayerName = $BenchClientPlayerName
	BenchClientInstanceRoot = $BenchClientInstanceRoot
	BenchClientStartCommand = $BenchClientStartCommand
	BenchClientGameHost = $BenchClientGameHost
	BenchClientGamePort = $BenchClientGamePort
	BenchClientInitialConnectDelayMs = $BenchClientInitialConnectDelayMs
	BenchClientReconnectIntervalMs = $BenchClientReconnectIntervalMs
	BenchClientStopTimeoutMs = $BenchClientStopTimeoutMs
	BenchClientFocusWindow = $true
	BenchClientFocusTimeoutMs = $BenchClientFocusTimeoutMs
	BenchClientOpenTickCharts = $true
	BenchClientPostJoinActionDelayMs = $BenchClientPostJoinActionDelayMs
	PlayerReadyTimeoutMs = $PlayerReadyTimeoutMs
	PlayerReadyPollIntervalMs = $PlayerReadyPollIntervalMs
	PlayerReadyProbeCommand = $PlayerReadyProbeCommand
	PlayerSetupCommands = $PlayerSetupCommands
	AutoTeleportPlayerToObservationPoint = $true
}

if (-not [string]::IsNullOrWhiteSpace($SparkActivityPath)) {
	$invokeArgs.SparkActivityPath = $SparkActivityPath
}
if ($null -ne $CaseIds -and @($CaseIds).Count -gt 0) {
	$invokeArgs.CaseIds = @($CaseIds)
}
if (-not [string]::IsNullOrWhiteSpace($BenchClientWorkingDirectory)) {
	$invokeArgs.BenchClientWorkingDirectory = $BenchClientWorkingDirectory
}
if (-not [string]::IsNullOrWhiteSpace($BenchClientModsDir)) {
	$invokeArgs.BenchClientModsDir = $BenchClientModsDir
}
if ($BuildSync) {
	$invokeArgs.BuildBeforeSyncLatestModJar = $true
	$invokeArgs.SyncLatestModJar = $true
	$invokeArgs.SyncLatestClientModJar = $true
	$invokeArgs.BuildTask = $BuildTask
}
if (-not [string]::IsNullOrWhiteSpace($ModJarPath)) {
	$invokeArgs.ModJarPath = $ModJarPath
}
if (-not [string]::IsNullOrWhiteSpace($ServerModsDir)) {
	$invokeArgs.ServerModsDir = $ServerModsDir
}
if ($DeleteCaseWorldOnSuccess) {
	$invokeArgs.DeleteCaseWorldOnSuccess = $true
}
if ($ContinueOnFailure) {
	$invokeArgs.ContinueOnFailure = $true
}

& (Join-Path $PSScriptRoot "run-bench-suite.ps1") @invokeArgs
