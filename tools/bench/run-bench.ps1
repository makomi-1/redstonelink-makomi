<#
.SYNOPSIS
RedstoneLink bench 自动化脚本入口壳。
#>
param([ValidateSet("List", "PrintCase", "InstallDatapack", "RunCase", "RunFunctionalCase")][string]$Action = "List",[string]$CaseId,[string]$MatrixPath = (Join-Path $PSScriptRoot "matrix.json"),[string]$SavePath = (Join-Path $PSScriptRoot "..\..\run\saves\rl-bench"),[string]$RconHost = "127.0.0.1",[int]$RconPort = 25575,[string]$RconPassword,[string]$AsPlayer,[string]$SparkActivityPath,[int]$SparkActivityTimeoutMs = 30000,[int]$SparkActivityPollIntervalMs = 250,[switch]$SkipSpark,[switch]$DryRun)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$datapackSource = Join-Path $PSScriptRoot "datapack\rl_bench"
$script:DryRunSerialCounter = 1L
$script:DryRunGameTime = 0L
$script:BenchAsPlayer = $AsPlayer
# 按职责拆分 bench 执行脚本，入口文件只保留参数、全局状态与动作分发。
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Matrix.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.World.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.PerfSpark.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Rcon.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.PlaceAndLink.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.FunctionalTrace.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.Results.ps1"))
. (Resolve-Path (Join-Path $PSScriptRoot "lib\Bench.EntryPoint.ps1"))
