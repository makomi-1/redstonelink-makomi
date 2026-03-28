<#
.SYNOPSIS
运行首批必跑 suite（remapJar + 同步服务器模组版）。
.DESCRIPTION
在运行首批必跑前先执行 remapJar，并把最新 RedstoneLink 运行 jar 复制到 dedicated server 的 mods 目录。
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
	[string]$BuildTask = "remapJar",
	[string]$ModJarPath,
	[string]$ServerModsDir,
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
	BuildBeforeSyncLatestModJar = $true
	SyncLatestModJar = $true
	BuildTask = $BuildTask
}

if (-not [string]::IsNullOrWhiteSpace($AsPlayer)) {
	$invokeArgs.AsPlayer = $AsPlayer
}
if (-not [string]::IsNullOrWhiteSpace($SparkActivityPath)) {
	$invokeArgs.SparkActivityPath = $SparkActivityPath
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
