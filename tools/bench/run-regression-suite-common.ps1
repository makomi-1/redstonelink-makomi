<#
.SYNOPSIS
运行推荐回归 suite 的统一包装入口。
.DESCRIPTION
面向 smoke / regression-core / regression-lifecycle / regression-full。
默认不启动外部客户端，也不会自动把玩家传送到观察位；
尤其是包含 crosschunk / loading / restart 场景的 lifecycle 类入口，
故意避免自动 TP 对区块加载产生额外干扰。
#>
param(
	[ValidateSet("smoke", "core", "lifecycle", "full")][string]$Mode,
	[switch]$BuildSync,
	[string]$ServerRoot = "D:\OpenProjects\RedstoneLink\mcserver",
	[string]$ServerStartCommand = ".\start.bat",
	[ValidateSet("Normal", "Minimized", "Hidden")][string]$ServerWindowMode = "Hidden",
	[string]$ServerPriorityClass = "High",
	[string]$RconHost = "127.0.0.1",
	[int]$RconPort = 25575,
	[Alias("RconPassword")]
	$RconSecret = "redstonelink-bench",
	[string[]]$CaseIds,
	[string]$SparkActivityPath,
	[string]$AsPlayer,
	[string[]]$PlayerSetupCommands,
	[int]$PlayerReadyTimeoutMs = 180000,
	[int]$PlayerReadyPollIntervalMs = 250,
	[string]$PlayerReadyProbeCommand = "data get entity @s Pos",
	[string]$BuildTask = "remapJar",
	[string]$ModJarPath,
	[string]$ServerModsDir,
	[switch]$DeleteCaseWorldOnSuccess,
	[switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-RegressionSuitePath {
	param([string]$ModeName)
	switch ([string]$ModeName) {
		"smoke" { return (Join-Path $PSScriptRoot "suites\smoke.json") }
		"core" { return (Join-Path $PSScriptRoot "suites\regression-core.json") }
		"lifecycle" { return (Join-Path $PSScriptRoot "suites\regression-lifecycle.json") }
		"full" { return (Join-Path $PSScriptRoot "suites\regression-full.json") }
		default { throw "Unsupported regression suite mode: $ModeName" }
	}
}

$invokeArgs = @{
	ServerRoot = $ServerRoot
	TemplateWorldPath = (Join-Path $ServerRoot "rl-bench-template")
	ServerStartCommand = $ServerStartCommand
	ServerWindowMode = $ServerWindowMode
	ServerPriorityClass = $ServerPriorityClass
	SuitePath = (Get-RegressionSuitePath -ModeName $Mode)
	RconHost = $RconHost
	RconPort = $RconPort
	RconPassword = $RconSecret
}

if ($null -ne $CaseIds -and @($CaseIds).Count -gt 0) {
	$invokeArgs.CaseIds = @($CaseIds)
}
if (-not [string]::IsNullOrWhiteSpace($SparkActivityPath)) {
	$invokeArgs.SparkActivityPath = $SparkActivityPath
}
if (-not [string]::IsNullOrWhiteSpace($AsPlayer)) {
	$invokeArgs.AsPlayer = $AsPlayer
	$invokeArgs.PlayerReadyTimeoutMs = $PlayerReadyTimeoutMs
	$invokeArgs.PlayerReadyPollIntervalMs = $PlayerReadyPollIntervalMs
	$invokeArgs.PlayerReadyProbeCommand = $PlayerReadyProbeCommand
	if ($null -ne $PlayerSetupCommands -and @($PlayerSetupCommands).Count -gt 0) {
		$invokeArgs.PlayerSetupCommands = @($PlayerSetupCommands)
	}
}
if ($BuildSync) {
	$invokeArgs.BuildBeforeSyncLatestModJar = $true
	$invokeArgs.SyncLatestModJar = $true
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
