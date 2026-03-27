<#
.SYNOPSIS
运行轻量性能 suite。
.DESCRIPTION
默认启用外部客户端自动进服、自动传送观察位、聚焦窗口与 F3+2 tick 曲线。
#>
param(
	[string]$ServerRoot = "D:\OpenProjects\RedstoneLink\mcserver",
	[string]$ServerStartCommand = ".\start.bat",
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
	[string]$PrismLauncherPath = "D:\Prism Launcher\prismlauncher.exe",
	[string]$PrismRootDir = "C:\Users\15166\AppData\Roaming\PrismLauncher",
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
	[string]$ModJarPath,
	[string]$ServerModsDir,
	[switch]$DeleteCaseWorldOnSuccess,
	[switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$invokeArgs = @{
	Mode = "lite"
	ServerRoot = $ServerRoot
	ServerStartCommand = $ServerStartCommand
	RconHost = $RconHost
	RconPort = $RconPort
	RconPassword = $RconSecret
	BenchClientPlayerName = $BenchClientPlayerName
	BenchClientGameHost = $BenchClientGameHost
	BenchClientGamePort = $BenchClientGamePort
	PlayerSetupCommands = $PlayerSetupCommands
	PlayerReadyTimeoutMs = $PlayerReadyTimeoutMs
	PlayerReadyPollIntervalMs = $PlayerReadyPollIntervalMs
	PlayerReadyProbeCommand = $PlayerReadyProbeCommand
	BenchClientInitialConnectDelayMs = $BenchClientInitialConnectDelayMs
	BenchClientReconnectIntervalMs = $BenchClientReconnectIntervalMs
	BenchClientStopTimeoutMs = $BenchClientStopTimeoutMs
	BenchClientFocusTimeoutMs = $BenchClientFocusTimeoutMs
	BenchClientPostJoinActionDelayMs = $BenchClientPostJoinActionDelayMs
}

foreach ($optionalName in @(
	"CaseIds",
	"SparkActivityPath",
	"BenchClientProfile",
	"BenchClientLaunchTarget",
	"PrismLauncherPath",
	"PrismRootDir",
	"BenchClientInstanceRoot",
	"BenchClientWorkingDirectory",
	"BenchClientModsDir",
	"BenchClientStartCommand",
	"AsPlayer",
	"ModJarPath",
	"ServerModsDir"
)) {
	$value = Get-Variable -Name $optionalName -ValueOnly
	if ($value -is [System.Array]) {
		if (@($value).Count -gt 0) {
			$invokeArgs[$optionalName] = $value
		}
		continue
	}
	if (-not [string]::IsNullOrWhiteSpace([string]$value)) {
		$invokeArgs[$optionalName] = $value
	}
}

if ($DeleteCaseWorldOnSuccess) {
	$invokeArgs.DeleteCaseWorldOnSuccess = $true
}
if ($ContinueOnFailure) {
	$invokeArgs.ContinueOnFailure = $true
}

& (Join-Path $PSScriptRoot "run-performance-suite-common.ps1") @invokeArgs
