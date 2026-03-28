<#
.SYNOPSIS
运行 smoke suite（纯运行版）。
#>
param(
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
	[switch]$DeleteCaseWorldOnSuccess,
	[switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$invokeArgs = @{
	Mode = "smoke"
	ServerRoot = $ServerRoot
	ServerStartCommand = $ServerStartCommand
	ServerWindowMode = $ServerWindowMode
	ServerPriorityClass = $ServerPriorityClass
	RconHost = $RconHost
	RconPort = $RconPort
	RconPassword = $RconSecret
	PlayerReadyTimeoutMs = $PlayerReadyTimeoutMs
	PlayerReadyPollIntervalMs = $PlayerReadyPollIntervalMs
	PlayerReadyProbeCommand = $PlayerReadyProbeCommand
}

foreach ($optionalName in @(
	"CaseIds",
	"SparkActivityPath",
	"AsPlayer"
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

if ($null -ne $PlayerSetupCommands -and @($PlayerSetupCommands).Count -gt 0) {
	$invokeArgs.PlayerSetupCommands = @($PlayerSetupCommands)
}
if ($DeleteCaseWorldOnSuccess) {
	$invokeArgs.DeleteCaseWorldOnSuccess = $true
}
if ($ContinueOnFailure) {
	$invokeArgs.ContinueOnFailure = $true
}

& (Join-Path $PSScriptRoot "run-regression-suite-common.ps1") @invokeArgs
