<#
.SYNOPSIS
运行首批必跑 suite（纯运行版）。
.DESCRIPTION
使用现有服务器模组目录直接运行首批必跑，不执行 remapJar，也不复制本地 jar 到服务器。
#>
param(
	[string]$ServerRoot = "D:\OpenProjects\RedstoneLink\mcserver",
	[string]$ServerStartCommand = ".\start.bat",
	[ValidateSet("Normal", "Minimized", "Hidden")][string]$ServerWindowMode = "Hidden",
	[string]$ServerPriorityClass = "High",
	[string]$RconHost = "127.0.0.1",
	[int]$RconPort = 25575,
	[Alias("RconPassword")]
	$RconSecret,
	[string]$AsPlayer,
	[string]$SparkActivityPath,
	[switch]$DeleteCaseWorldOnSuccess,
	[switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$invokeArgs = @{
	ServerRoot = $ServerRoot
	TemplateWorldPath = (Join-Path $ServerRoot "rl-bench-template")
	ServerStartCommand = $ServerStartCommand
	ServerWindowMode = $ServerWindowMode
	ServerPriorityClass = $ServerPriorityClass
	SuitePath = (Join-Path $PSScriptRoot "suites\first-mandatory.json")
	RconHost = $RconHost
	RconPort = $RconPort
	RconPassword = $RconSecret
}

if (-not [string]::IsNullOrWhiteSpace($AsPlayer)) {
	$invokeArgs.AsPlayer = $AsPlayer
}
if (-not [string]::IsNullOrWhiteSpace($SparkActivityPath)) {
	$invokeArgs.SparkActivityPath = $SparkActivityPath
}
if ($DeleteCaseWorldOnSuccess) {
	$invokeArgs.DeleteCaseWorldOnSuccess = $true
}
if ($ContinueOnFailure) {
	$invokeArgs.ContinueOnFailure = $true
}

& (Join-Path $PSScriptRoot "run-bench-suite.ps1") @invokeArgs
