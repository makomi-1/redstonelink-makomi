<#
.SYNOPSIS
bench 模块：外部客户端实例自动启动、bench 配置写入与进程回收。
#>

$script:BenchClientWindowInteropInitialized = $false

function Assert-BenchClientIdentityCompatible {
	param(
		[string]$AsPlayer,
		[string]$BenchClientPlayerName
	)
	if ([string]::IsNullOrWhiteSpace($BenchClientPlayerName)) {
		throw "BenchClientPlayerName is required when AutoStartBenchClient is enabled."
	}
	$trimmedPlayerName = $BenchClientPlayerName.Trim()
	if ([string]::IsNullOrWhiteSpace($AsPlayer)) {
		return $trimmedPlayerName
	}
	$trimmedAsPlayer = $AsPlayer.Trim()
	if ($trimmedAsPlayer.StartsWith("@", [System.StringComparison]::Ordinal)) {
		throw "AutoStartBenchClient currently requires AsPlayer to be the same plain player name as BenchClientPlayerName. Selectors are not supported."
	}
	if (-not [string]::Equals($trimmedAsPlayer, $trimmedPlayerName, [System.StringComparison]::Ordinal)) {
		throw "AsPlayer must exactly match BenchClientPlayerName when AutoStartBenchClient is enabled. AsPlayer=$trimmedAsPlayer BenchClientPlayerName=$trimmedPlayerName"
	}
	return $trimmedPlayerName
}

function Get-BenchClientConfigFilePath {
	param(
		[string]$BenchClientInstanceRootPath
	)
	$configDirectoryPath = New-DirectoryIfMissing -Path (Join-Path $BenchClientInstanceRootPath "config")
	return (Join-Path $configDirectoryPath "redstonelink-bench-client.properties")
}

function Resolve-BenchClientInstanceRootPath {
	param(
		[string]$RepoRootPath
	)
	if ([string]::IsNullOrWhiteSpace($script:BenchClientInstanceRoot)) {
		throw "BenchClientInstanceRoot is required."
	}
	$instanceRootPath = Resolve-PathFromBase -BaseDirectory $RepoRootPath -CandidatePath $script:BenchClientInstanceRoot
	if (-not (Test-Path -LiteralPath $instanceRootPath -PathType Container)) {
		throw "Bench client instance root not found: $instanceRootPath"
	}
	return $instanceRootPath
}

function Resolve-BenchClientWorkingDirectoryPath {
	param(
		[string]$BenchClientInstanceRootPath
	)
	$workingDirectoryPath = if ([string]::IsNullOrWhiteSpace($script:BenchClientWorkingDirectory)) {
		$BenchClientInstanceRootPath
	} else {
		Resolve-PathFromBase -BaseDirectory $BenchClientInstanceRootPath -CandidatePath $script:BenchClientWorkingDirectory
	}
	if (-not (Test-Path -LiteralPath $workingDirectoryPath -PathType Container)) {
		throw "Bench client working directory not found: $workingDirectoryPath"
	}
	return $workingDirectoryPath
}

function Resolve-BenchClientModsDirectoryPath {
	param(
		[string]$BenchClientInstanceRootPath
	)
	$modsDirectoryPath = if ([string]::IsNullOrWhiteSpace($script:BenchClientModsDir)) {
		Join-Path $BenchClientInstanceRootPath "mods"
	} else {
		Resolve-PathFromBase -BaseDirectory $BenchClientInstanceRootPath -CandidatePath $script:BenchClientModsDir
	}
	return (New-DirectoryIfMissing -Path $modsDirectoryPath)
}

function Write-BenchClientAutomationConfigFile {
	param(
		[string]$ConfigFilePath,
		[string]$PlayerName,
		[string]$ServerHost,
		[int]$ServerPort,
		[int]$InitialConnectDelayMs,
		[int]$ReconnectIntervalMs,
		[bool]$OpenTickCharts,
		[int]$PostJoinActionDelayMs
	)
	$configContent = @(
		"enabled=true"
		"player.name=$PlayerName"
		"server.host=$ServerHost"
		"server.port=$ServerPort"
		"initial.connect.delay.ms=$InitialConnectDelayMs"
		"reconnect.interval.ms=$ReconnectIntervalMs"
		"open.tick.chart=$($OpenTickCharts.ToString().ToLowerInvariant())"
		"post.join.action.delay.ms=$PostJoinActionDelayMs"
		""
	) -join "`r`n"
	Write-Utf8NoBomFile -Path $ConfigFilePath -Content $configContent
}

function Remove-BenchClientAutomationConfigFile {
	param(
		[string]$ConfigFilePath
	)
	if ([string]::IsNullOrWhiteSpace($ConfigFilePath)) {
		return
	}
	if (Test-Path -LiteralPath $ConfigFilePath -PathType Leaf) {
		Remove-Item -LiteralPath $ConfigFilePath -Force
	}
}

# 统一路径/命令行比较口径，便于通过 gameDir 等参数识别真实客户端进程。
function Get-BenchClientComparableText {
	param(
		[string]$Text
	)
	if ([string]::IsNullOrWhiteSpace($Text)) {
		return ""
	}
	$directorySeparator = [System.IO.Path]::DirectorySeparatorChar
	$normalizedText = $Text.Replace("/", ([string]$directorySeparator)).Trim().ToLowerInvariant()
	while ($normalizedText.EndsWith(([string]$directorySeparator), [System.StringComparison]::Ordinal)) {
		$normalizedText = $normalizedText.Substring(0, $normalizedText.Length - 1)
	}
	return $normalizedText
}

# 生成当前 bench 客户端实例对应的匹配路径集合，用于识别真实 Java 客户端进程。
function Add-BenchClientMatchPath {
	param(
		[System.Collections.Generic.List[string]]$MatchPaths,
		[string]$PathValue
	)
	if ($null -eq $MatchPaths) {
		return
	}
	$normalizedPath = Get-BenchClientComparableText -Text $PathValue
	if ((-not [string]::IsNullOrWhiteSpace($normalizedPath)) -and (-not $MatchPaths.Contains($normalizedPath))) {
		$MatchPaths.Add($normalizedPath)
	}
}

function Get-BenchClientMatchPaths {
	param(
		[string]$InstanceRootPath,
		[string]$WorkingDirectoryPath
	)
	$matchPaths = New-Object System.Collections.Generic.List[string]
	foreach ($pathValue in @($InstanceRootPath, $WorkingDirectoryPath)) {
		Add-BenchClientMatchPath -MatchPaths $matchPaths -PathValue $pathValue
		$normalizedPath = Get-BenchClientComparableText -Text $pathValue
		if ([string]::IsNullOrWhiteSpace($normalizedPath)) {
			continue
		}
		$leafName = [System.IO.Path]::GetFileName($normalizedPath)
		if (
			[string]::Equals($leafName, "minecraft", [System.StringComparison]::OrdinalIgnoreCase) -or
			[string]::Equals($leafName, ".minecraft", [System.StringComparison]::OrdinalIgnoreCase)
		) {
			# Prism 一类启动器的 Java 命令行常写到实例目录的同级资源（如 natives），因此补充上一层实例目录。
			$parentDirectoryPath = [System.IO.Path]::GetDirectoryName($normalizedPath)
			Add-BenchClientMatchPath -MatchPaths $matchPaths -PathValue $parentDirectoryPath
		}
	}
	return @($matchPaths.ToArray())
}

# 解析 BenchClientStartCommand 的首个可执行项，优先走直接启动，避免 cmd.exe /c 吞掉带空格路径的启动器命令。
function Split-BenchClientStartCommand {
	param(
		[string]$Command
	)
	$trimmedCommand = ([string]$Command).Trim()
	if ([string]::IsNullOrWhiteSpace($trimmedCommand)) {
		return $null
	}

	if ($trimmedCommand.StartsWith('"', [System.StringComparison]::Ordinal)) {
		$closingQuoteIndex = $trimmedCommand.IndexOf('"', 1)
		if ($closingQuoteIndex -lt 1) {
			return $null
		}
		$filePath = $trimmedCommand.Substring(1, $closingQuoteIndex - 1)
		$argumentText = $trimmedCommand.Substring($closingQuoteIndex + 1).Trim()
	} else {
		$parts = $trimmedCommand -split '\s+', 2
		$filePath = [string]$parts[0]
		$argumentText = if ($parts.Count -gt 1) {
			([string]$parts[1]).Trim()
		} else {
			""
		}
	}

	$filePath = [System.Environment]::ExpandEnvironmentVariables(([string]$filePath).Trim())
	if ([string]::IsNullOrWhiteSpace($filePath)) {
		return $null
	}
	return [pscustomobject]@{
		filePath = $filePath
		argumentText = $argumentText
	}
}

function Resolve-BenchClientStartFilePath {
	param(
		[string]$WorkingDirectory,
		[string]$CandidateFilePath
	)
	$trimmedCandidateFilePath = ([string]$CandidateFilePath).Trim()
	if ([string]::IsNullOrWhiteSpace($trimmedCandidateFilePath)) {
		return $null
	}

	if ([System.IO.Path]::IsPathRooted($trimmedCandidateFilePath)) {
		if (Test-Path -LiteralPath $trimmedCandidateFilePath -PathType Leaf) {
			return [System.IO.Path]::GetFullPath($trimmedCandidateFilePath)
		}
		return $null
	}

	$relativeCandidatePath = Join-Path $WorkingDirectory $trimmedCandidateFilePath
	if (Test-Path -LiteralPath $relativeCandidatePath -PathType Leaf) {
		return [System.IO.Path]::GetFullPath($relativeCandidatePath)
	}

	$commandInfo = Get-Command $trimmedCandidateFilePath -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
	if ($null -eq $commandInfo) {
		return $null
	}

	foreach ($propertyName in @("Path", "Definition", "Source")) {
		$property = $commandInfo.PSObject.Properties[$propertyName]
		if (($null -ne $property) -and (-not [string]::IsNullOrWhiteSpace([string]$property.Value))) {
			return [string]$property.Value
		}
	}
	return $null
}

function Get-BenchClientStartCommandSpec {
	param(
		[string]$WorkingDirectory,
		[string]$Command
	)
	$parsedCommand = Split-BenchClientStartCommand -Command $Command
	if ($null -eq $parsedCommand) {
		return [pscustomobject]@{
			filePath = "cmd.exe"
			argumentList = @("/c", ([string]$Command))
			launchMode = "cmdShell"
			resolvedStartFilePath = ""
		}
	}

	$resolvedStartFilePath = Resolve-BenchClientStartFilePath -WorkingDirectory $WorkingDirectory -CandidateFilePath $parsedCommand.filePath
	if ([string]::IsNullOrWhiteSpace($resolvedStartFilePath)) {
		return [pscustomobject]@{
			filePath = "cmd.exe"
			argumentList = @("/c", ([string]$Command))
			launchMode = "cmdShell"
			resolvedStartFilePath = ""
		}
	}

	$fileExtension = [System.IO.Path]::GetExtension($resolvedStartFilePath)
	if (
		[string]::Equals($fileExtension, ".bat", [System.StringComparison]::OrdinalIgnoreCase) -or
		[string]::Equals($fileExtension, ".cmd", [System.StringComparison]::OrdinalIgnoreCase)
	) {
		$wrappedCommand = '"' + $resolvedStartFilePath + '"'
		if (-not [string]::IsNullOrWhiteSpace($parsedCommand.argumentText)) {
			$wrappedCommand = "$wrappedCommand $($parsedCommand.argumentText)"
		}
		return [pscustomobject]@{
			filePath = "cmd.exe"
			argumentList = @("/c", $wrappedCommand)
			launchMode = "cmdShell"
			resolvedStartFilePath = $resolvedStartFilePath
		}
	}

	return [pscustomobject]@{
		filePath = $resolvedStartFilePath
		argumentList = $parsedCommand.argumentText
		launchMode = "directProcess"
		resolvedStartFilePath = $resolvedStartFilePath
	}
}

# 读取当前所有 Java/JavaW 进程的最小摘要，供启动前后做增量匹配。
function Get-BenchClientJavaProcessInfos {
	$processInfos = New-Object System.Collections.Generic.List[object]
	$javaProcesses = Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^(java|javaw)\.exe$' }
	foreach ($processInfo in $javaProcesses) {
		$processInfos.Add([pscustomobject]@{
			processId = ([int]$processInfo.ProcessId)
			parentProcessId = ([int]$processInfo.ParentProcessId)
			name = ([string]$processInfo.Name)
			commandLine = ([string]$processInfo.CommandLine)
			executablePath = ([string]$processInfo.ExecutablePath)
			creationDate = ([string]$processInfo.CreationDate)
		})
	}
	return @($processInfos.ToArray())
}

# 将进程摘要转成 PID 查找表，便于筛选“启动后新出现”的 Java 进程。
function New-BenchClientProcessIdLookup {
	param(
		$ProcessInfos
	)
	$lookup = @{}
	foreach ($processInfo in @($ProcessInfos)) {
		$processId = [int]$processInfo.processId
		$lookup[$processId] = $true
	}
	return $lookup
}

# 判断某个 Java 进程命令行是否命中了当前 bench 客户端实例目录。
function Test-BenchClientJavaProcessMatchesPaths {
	param(
		$ProcessInfo,
		[string[]]$CandidatePaths,
		[int]$ParentProcessId
	)
	if ($null -eq $ProcessInfo) {
		return $false
	}
	if (($ParentProcessId -gt 0) -and ([int]$ProcessInfo.parentProcessId -eq $ParentProcessId)) {
		return $true
	}
	$normalizedCommandLine = Get-BenchClientComparableText -Text ([string]$ProcessInfo.commandLine)
	if ([string]::IsNullOrWhiteSpace($normalizedCommandLine)) {
		return $false
	}
	foreach ($candidatePath in @($CandidatePaths)) {
		if ([string]::IsNullOrWhiteSpace($candidatePath)) {
			continue
		}
		if ($normalizedCommandLine.Contains($candidatePath)) {
			return $true
		}
	}
	return $false
}

# 轮询查找启动后新出现、且命中当前实例目录的真实 Minecraft Java 进程。
function Find-BenchClientJavaProcess {
	param(
		[hashtable]$KnownProcessIds,
		[string[]]$CandidatePaths,
		[int]$ParentProcessId,
		[int]$TimeoutMs,
		[int]$PollIntervalMs
	)
	if ($null -eq $KnownProcessIds) {
		$KnownProcessIds = @{}
	}
	$TimeoutMs = [Math]::Max(0, $TimeoutMs)
	$PollIntervalMs = [Math]::Max(50, $PollIntervalMs)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

	do {
		$matchingProcesses = @(
			Get-BenchClientJavaProcessInfos |
				Where-Object {
					$processId = [int]$_.processId
					(-not $KnownProcessIds.ContainsKey($processId)) -and (Test-BenchClientJavaProcessMatchesPaths -ProcessInfo $_ -CandidatePaths $CandidatePaths -ParentProcessId $ParentProcessId)
				} |
				Sort-Object processId -Descending
		)
		if ($matchingProcesses.Count -gt 0) {
			return $matchingProcesses[0]
		}
		if ($stopwatch.ElapsedMilliseconds -ge $TimeoutMs) {
			return $null
		}
		Start-Sleep -Milliseconds $PollIntervalMs
	} while ($true)
}

# 查找当前仍在运行、且命中 bench 客户端实例目录的 Java 进程。
function Get-BenchClientRunningGameProcessInfo {
	param(
		[string[]]$CandidatePaths,
		[int]$LauncherProcessId,
		[int]$PreferredProcessId
	)
	$matchingProcesses = @(
		Get-BenchClientJavaProcessInfos |
			Where-Object { Test-BenchClientJavaProcessMatchesPaths -ProcessInfo $_ -CandidatePaths $CandidatePaths -ParentProcessId $LauncherProcessId } |
			Sort-Object processId -Descending
	)
	if (($PreferredProcessId -gt 0) -and $matchingProcesses.Count -gt 0) {
		$preferredProcess = $matchingProcesses | Where-Object { ([int]$_.processId) -eq $PreferredProcessId } | Select-Object -First 1
		if ($null -ne $preferredProcess) {
			return $preferredProcess
		}
	}
	if ($matchingProcesses.Count -gt 0) {
		return $matchingProcesses[0]
	}
	return $null
}

# 通过 PID 重新获取托管进程对象，避免只握着已退出的旧句柄。
function Get-BenchClientManagedProcess {
	param(
		[int]$ProcessId,
		[int]$RetryCount,
		[int]$RetryDelayMs
	)
	if ($ProcessId -le 0) {
		return $null
	}
	$RetryCount = [Math]::Max(1, $RetryCount)
	$RetryDelayMs = [Math]::Max(0, $RetryDelayMs)
	for ($attempt = 0; $attempt -lt $RetryCount; $attempt++) {
		try {
			return (Get-Process -Id $ProcessId -ErrorAction Stop)
		} catch {
			if ($attempt -ge ($RetryCount - 1)) {
				return $null
			}
			Start-Sleep -Milliseconds $RetryDelayMs
		}
	}
	return $null
}

# 将启动后的外层进程解析为“真正需要托管的客户端进程”。
function Resolve-BenchClientTrackedProcess {
	param(
		[System.Diagnostics.Process]$LauncherProcess,
		[hashtable]$KnownJavaProcessIds,
		[string]$InstanceRootPath,
		[string]$WorkingDirectoryPath
	)
	$matchPaths = Get-BenchClientMatchPaths -InstanceRootPath $InstanceRootPath -WorkingDirectoryPath $WorkingDirectoryPath
	$launcherProcessId = if ($null -eq $LauncherProcess) { 0 } else { ([int]$LauncherProcess.Id) }
	$launcherExitProbeDelayMs = 1200
	$quickChildDiscoveryTimeoutMs = 2500
	$launcherChildDiscoveryTimeoutMs = 30000
	$pollIntervalMs = 250

	Start-Sleep -Milliseconds $launcherExitProbeDelayMs

	# 先做一轮短探测，兼容启动器很快分叉出真实 Java 进程的场景。
	$gameProcessInfo = Find-BenchClientJavaProcess `
		-KnownProcessIds $KnownJavaProcessIds `
		-CandidatePaths $matchPaths `
		-ParentProcessId $launcherProcessId `
		-TimeoutMs $quickChildDiscoveryTimeoutMs `
		-PollIntervalMs $pollIntervalMs
	if ($null -ne $gameProcessInfo) {
		$gameProcessId = ([int]$gameProcessInfo.processId)
		$gameProcess = Get-BenchClientManagedProcess -ProcessId $gameProcessId -RetryCount 5 -RetryDelayMs 100
		if ($null -eq $gameProcess) {
			throw "Bench client game process was detected but could not be attached. pid=$gameProcessId"
		}
		return [pscustomobject]@{
			launcherProcess = $LauncherProcess
			launcherProcessId = $launcherProcessId
			gameProcess = $gameProcess
			gameProcessId = $gameProcessId
			trackedProcess = $gameProcess
			trackedProcessId = $gameProcessId
			trackedProcessKind = "gameProcess"
			matchPaths = $matchPaths
		}
	}

	if (($null -ne $LauncherProcess) -and (-not $LauncherProcess.HasExited) -and ($LauncherProcess.ProcessName -match '^(java|javaw)$')) {
		return [pscustomobject]@{
			launcherProcess = $LauncherProcess
			launcherProcessId = $launcherProcessId
			gameProcess = $null
			gameProcessId = 0
			trackedProcess = $LauncherProcess
			trackedProcessId = $launcherProcessId
			trackedProcessKind = "launcherProcess"
			matchPaths = $matchPaths
		}
	}

	# 对 Prism 这类“启动器先存活一段时间、稍后才分叉出真实 javaw”的流程，继续观察直到真实客户端出现或超时。
	$launcherDiscoveryStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($launcherDiscoveryStopwatch.ElapsedMilliseconds -lt $launcherChildDiscoveryTimeoutMs) {
		$gameProcessInfo = Find-BenchClientJavaProcess `
			-KnownProcessIds $KnownJavaProcessIds `
			-CandidatePaths $matchPaths `
			-ParentProcessId $launcherProcessId `
			-TimeoutMs 0 `
			-PollIntervalMs $pollIntervalMs
		if ($null -ne $gameProcessInfo) {
			$gameProcessId = ([int]$gameProcessInfo.processId)
			$gameProcess = Get-BenchClientManagedProcess -ProcessId $gameProcessId -RetryCount 5 -RetryDelayMs 100
			if ($null -eq $gameProcess) {
				throw "Bench client game process was detected after delayed launcher handoff but could not be attached. pid=$gameProcessId"
			}
			return [pscustomobject]@{
				launcherProcess = $LauncherProcess
				launcherProcessId = $launcherProcessId
				gameProcess = $gameProcess
				gameProcessId = $gameProcessId
				trackedProcess = $gameProcess
				trackedProcessId = $gameProcessId
				trackedProcessKind = "gameProcess"
				matchPaths = $matchPaths
			}
		}
		Start-Sleep -Milliseconds $pollIntervalMs
	}

	if (($null -ne $LauncherProcess) -and (-not $LauncherProcess.HasExited)) {
		return [pscustomobject]@{
			launcherProcess = $LauncherProcess
			launcherProcessId = $launcherProcessId
			gameProcess = $null
			gameProcessId = 0
			trackedProcess = $LauncherProcess
			trackedProcessId = $launcherProcessId
			trackedProcessKind = "launcherProcess"
			matchPaths = $matchPaths
		}
	}

	$matchPathsSummary = if ($matchPaths.Count -gt 0) { ($matchPaths -join ";") } else { "<none>" }
	throw "Bench client launcher exited and no tracked process could be resolved. launcherPid=$launcherProcessId instanceRoot=$InstanceRootPath matchPaths=$matchPathsSummary"
}

function Start-BenchClientProcess {
	param(
		[string]$WorkingDirectory,
		[string]$Command
	)
	if ([string]::IsNullOrWhiteSpace($WorkingDirectory)) {
		throw "Bench client WorkingDirectory is required."
	}
	if ([string]::IsNullOrWhiteSpace($Command)) {
		throw "Bench client start command is required."
	}
	$startCommandSpec = Get-BenchClientStartCommandSpec -WorkingDirectory $WorkingDirectory -Command $Command
	$startProcessArguments = @{
		FilePath = [string]$startCommandSpec.filePath
		WorkingDirectory = $WorkingDirectory
		PassThru = $true
	}
	if ($startCommandSpec.argumentList -is [System.Array]) {
		$startProcessArguments["ArgumentList"] = $startCommandSpec.argumentList
	} elseif (-not [string]::IsNullOrWhiteSpace([string]$startCommandSpec.argumentList)) {
		$startProcessArguments["ArgumentList"] = [string]$startCommandSpec.argumentList
	}
	return (Start-Process @startProcessArguments)
}

function Wait-BenchClientProcessExit {
	param(
		[System.Diagnostics.Process]$Process,
		[int]$TimeoutMs,
		[int]$PollIntervalMs
	)
	if ($null -eq $Process) {
		return
	}
	$TimeoutMs = [Math]::Max(1000, $TimeoutMs)
	$PollIntervalMs = [Math]::Max(50, $PollIntervalMs)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMs) {
		if ($Process.HasExited) {
			return
		}
		Start-Sleep -Milliseconds $PollIntervalMs
	}
	throw "Timed out waiting for bench client process to exit. pid=$($Process.Id)"
}

function Stop-BenchClientProcess {
	param(
		[System.Diagnostics.Process]$Process,
		[int]$TimeoutMs
	)
	if ($null -eq $Process) {
		return
	}
	if ($Process.HasExited) {
		return
	}
	$TimeoutMs = [Math]::Max(1000, $TimeoutMs)
	Stop-Process -Id $Process.Id -Force
	Wait-BenchClientProcessExit -Process $Process -TimeoutMs $TimeoutMs -PollIntervalMs 250
}

# 按 PID 尝试关闭进程；若进程已不存在则直接返回。
function Stop-BenchClientProcessById {
	param(
		[int]$ProcessId,
		[int]$TimeoutMs
	)
	$process = Get-BenchClientManagedProcess -ProcessId $ProcessId -RetryCount 1 -RetryDelayMs 0
	if ($null -eq $process) {
		return
	}
	Stop-BenchClientProcess -Process $process -TimeoutMs $TimeoutMs
}

function Initialize-BenchClientWindowInterop {
	if ($script:BenchClientWindowInteropInitialized) {
		return
	}
	if ("BenchClientWindowInterop" -as [type]) {
		$script:BenchClientWindowInteropInitialized = $true
		return
	}
	Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class BenchClientWindowInterop {
	[DllImport("user32.dll")]
	public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

	[DllImport("user32.dll")]
	public static extern bool SetForegroundWindow(IntPtr hWnd);

	[DllImport("user32.dll")]
	public static extern bool IsIconic(IntPtr hWnd);
}
"@
	$script:BenchClientWindowInteropInitialized = $true
}

function Focus-BenchClientWindow {
	param(
		[int]$ProcessId,
		[int]$TimeoutMs
	)
	if (-not [bool]$script:BenchClientFocusWindow) {
		return $null
	}
	if ($ProcessId -le 0) {
		return [ordered]@{
			enabled = $true
			focused = $false
			processId = $ProcessId
			reason = "invalid_process_id"
		}
	}
	if ($DryRun) {
		return [ordered]@{
			enabled = $true
			focused = $false
			processId = $ProcessId
			dryRun = $true
		}
	}

	Initialize-BenchClientWindowInterop
	$normalizedTimeoutMs = [Math]::Max(250, [int]$TimeoutMs)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($stopwatch.ElapsedMilliseconds -lt $normalizedTimeoutMs) {
		$process = Get-BenchClientManagedProcess -ProcessId $ProcessId -RetryCount 1 -RetryDelayMs 0
		if ($null -eq $process -or $process.HasExited) {
			return [ordered]@{
				enabled = $true
				focused = $false
				processId = $ProcessId
				reason = "process_exited"
			}
		}
		$process.Refresh()
		$windowHandle = $process.MainWindowHandle
		if (($null -ne $windowHandle) -and ($windowHandle -ne [IntPtr]::Zero)) {
			$showState = if ([BenchClientWindowInterop]::IsIconic($windowHandle)) { 9 } else { 5 }
			[BenchClientWindowInterop]::ShowWindowAsync($windowHandle, $showState) | Out-Null
			$focused = [BenchClientWindowInterop]::SetForegroundWindow($windowHandle)
			return [ordered]@{
				enabled = $true
				focused = [bool]$focused
				processId = $ProcessId
				windowHandle = $windowHandle.ToInt64()
				reason = if ($focused) { "success" } else { "set_foreground_rejected" }
			}
		}
		Start-Sleep -Milliseconds 200
	}

	return [ordered]@{
		enabled = $true
		focused = $false
		processId = $ProcessId
		reason = "window_handle_timeout"
		timeoutMs = $normalizedTimeoutMs
	}
}

function Start-BenchClientAutomationSession {
	param(
		[string]$RepoRootPath,
		[switch]$SkipModSync
	)
	if (-not $script:BenchAutoStartClient) {
		return $null
	}
	if ([string]::IsNullOrWhiteSpace($RepoRootPath)) {
		throw "RepoRootPath is required."
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchClientStartCommand)) {
		throw "BenchClientStartCommand is required when AutoStartBenchClient is enabled."
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchClientInstanceRoot)) {
		throw "BenchClientInstanceRoot is required when AutoStartBenchClient is enabled."
	}

	$instanceRootPath = Resolve-BenchClientInstanceRootPath -RepoRootPath $RepoRootPath
	$workingDirectoryPath = Resolve-BenchClientWorkingDirectoryPath -BenchClientInstanceRootPath $instanceRootPath
	$modsDirectoryPath = Resolve-BenchClientModsDirectoryPath -BenchClientInstanceRootPath $instanceRootPath
	$configFilePath = Get-BenchClientConfigFilePath -BenchClientInstanceRootPath $instanceRootPath
	$matchPaths = Get-BenchClientMatchPaths -InstanceRootPath $instanceRootPath -WorkingDirectoryPath $workingDirectoryPath
	$modSyncSummary = $null
	$gradleWrapperFilePath = if ([string]::IsNullOrWhiteSpace($script:BenchGradleWrapperPath)) {
		""
	} else {
		Resolve-PathFromBase -BaseDirectory $RepoRootPath -CandidatePath $script:BenchGradleWrapperPath
	}

	if ((-not $SkipModSync) -and $script:BenchBuildBeforeSyncLatestModJar -and -not $script:BenchSyncLatestClientModJar) {
		throw "BuildBeforeSyncLatestModJar requires SyncLatestClientModJar for single case external client sync."
	}
	if ((-not $SkipModSync) -and $script:BenchSyncLatestClientModJar) {
		if ($script:BenchBuildBeforeSyncLatestModJar) {
			Invoke-GradleBuildTask -RepoRootPath $RepoRootPath -GradleWrapperFilePath $gradleWrapperFilePath -TaskName $script:BenchBuildTask | Out-Host
		}
		$localModJarPath = Resolve-LocalRuntimeModJarPath -RepoRootPath $RepoRootPath -ExplicitModJarPath $script:BenchModJarPath
		$modSyncSummary = Sync-ClientModJar -SourceJarPath $localModJarPath -TargetModsDirectoryPath $modsDirectoryPath
		Write-Host "[Bench] Synced client mod jar -> $($modSyncSummary.copiedJarPath)"
	}

	if ($DryRun) {
		Write-Host "[Bench/DryRun] bench client player=$($script:BenchClientPlayerName) server=$($script:BenchClientGameHost):$($script:BenchClientGamePort)"
		return [pscustomobject]@{
			process = $null
			processId = $null
			launcherProcess = $null
			launcherProcessId = 0
			gameProcess = $null
			gameProcessId = 0
			trackedProcess = $null
			trackedProcessId = 0
			trackedProcessKind = $null
			matchPaths = $matchPaths
			instanceRootPath = $instanceRootPath
			workingDirectoryPath = $workingDirectoryPath
			modsDirectoryPath = $modsDirectoryPath
			configFilePath = $configFilePath
			playerName = $script:BenchClientPlayerName
			serverHost = $script:BenchClientGameHost
			serverPort = $script:BenchClientGamePort
			initialConnectDelayMs = $script:BenchClientInitialConnectDelayMs
			reconnectIntervalMs = $script:BenchClientReconnectIntervalMs
			openTickCharts = [bool]$script:BenchClientOpenTickCharts
			postJoinActionDelayMs = [int]$script:BenchClientPostJoinActionDelayMs
			focusWindow = [bool]$script:BenchClientFocusWindow
			focusResult = $null
			modSync = $modSyncSummary
			dryRun = $true
		}
	}

	Write-BenchClientAutomationConfigFile `
		-ConfigFilePath $configFilePath `
		-PlayerName $script:BenchClientPlayerName `
		-ServerHost $script:BenchClientGameHost `
		-ServerPort $script:BenchClientGamePort `
		-InitialConnectDelayMs $script:BenchClientInitialConnectDelayMs `
		-ReconnectIntervalMs $script:BenchClientReconnectIntervalMs `
		-OpenTickCharts ([bool]$script:BenchClientOpenTickCharts) `
		-PostJoinActionDelayMs ([int]$script:BenchClientPostJoinActionDelayMs)

	$launcherProcess = $null
	try {
		$knownJavaProcessIds = New-BenchClientProcessIdLookup -ProcessInfos (Get-BenchClientJavaProcessInfos)
		$launcherProcess = Start-BenchClientProcess -WorkingDirectory $workingDirectoryPath -Command $script:BenchClientStartCommand
		$trackedSession = Resolve-BenchClientTrackedProcess `
			-LauncherProcess $launcherProcess `
			-KnownJavaProcessIds $knownJavaProcessIds `
			-InstanceRootPath $instanceRootPath `
			-WorkingDirectoryPath $workingDirectoryPath
		$focusResult = Focus-BenchClientWindow -ProcessId ([int]$trackedSession.trackedProcessId) -TimeoutMs ([int]$script:BenchClientFocusTimeoutMs)

		Write-Host "[Bench] Started bench client trackedPid=$($trackedSession.trackedProcessId) kind=$($trackedSession.trackedProcessKind) player=$($script:BenchClientPlayerName)"
		if ($null -ne $focusResult) {
			Write-Host "[Bench] Bench client focus result: focused=$($focusResult.focused) reason=$($focusResult.reason) pid=$($focusResult.processId)"
		}
		return [pscustomobject]@{
			process = $trackedSession.trackedProcess
			processId = $trackedSession.trackedProcessId
			launcherProcess = $trackedSession.launcherProcess
			launcherProcessId = $trackedSession.launcherProcessId
			gameProcess = $trackedSession.gameProcess
			gameProcessId = $trackedSession.gameProcessId
			trackedProcess = $trackedSession.trackedProcess
			trackedProcessId = $trackedSession.trackedProcessId
			trackedProcessKind = $trackedSession.trackedProcessKind
			matchPaths = $trackedSession.matchPaths
			instanceRootPath = $instanceRootPath
			workingDirectoryPath = $workingDirectoryPath
			modsDirectoryPath = $modsDirectoryPath
			configFilePath = $configFilePath
			playerName = $script:BenchClientPlayerName
			serverHost = $script:BenchClientGameHost
			serverPort = $script:BenchClientGamePort
			initialConnectDelayMs = $script:BenchClientInitialConnectDelayMs
			reconnectIntervalMs = $script:BenchClientReconnectIntervalMs
			openTickCharts = [bool]$script:BenchClientOpenTickCharts
			postJoinActionDelayMs = [int]$script:BenchClientPostJoinActionDelayMs
			focusWindow = [bool]$script:BenchClientFocusWindow
			focusResult = $focusResult
			modSync = $modSyncSummary
		}
	} catch {
		Remove-BenchClientAutomationConfigFile -ConfigFilePath $configFilePath
		throw
	}
}

function Stop-BenchClientAutomationSession {
	param(
		$Session
	)
	if ($null -eq $Session) {
		return
	}
	$stoppedProcessIds = @{}
	try {
		$matchPaths = @()
		if ($Session.PSObject.Properties.Name -contains "matchPaths") {
			$matchPaths = @($Session.matchPaths)
		}
		$preferredGameProcessId = 0
		if ($Session.PSObject.Properties.Name -contains "gameProcessId") {
			$preferredGameProcessId = ([int]$Session.gameProcessId)
		}
		$launcherProcessId = 0
		if ($Session.PSObject.Properties.Name -contains "launcherProcessId") {
			$launcherProcessId = ([int]$Session.launcherProcessId)
		}

		# 停止时优先回收真实游戏进程；若启动阶段没抓到，也尝试按实例目录重新发现。
		if ($matchPaths.Count -gt 0) {
			$runningGameProcess = Get-BenchClientRunningGameProcessInfo -CandidatePaths $matchPaths -LauncherProcessId $launcherProcessId -PreferredProcessId $preferredGameProcessId
			if ($null -ne $runningGameProcess) {
				$runningGameProcessId = ([int]$runningGameProcess.processId)
				Stop-BenchClientProcessById -ProcessId $runningGameProcessId -TimeoutMs $script:BenchClientStopTimeoutMs
				$stoppedProcessIds[$runningGameProcessId] = $true
			}
		}

		foreach ($processId in @(
			([int]$Session.processId),
			([int]$Session.trackedProcessId),
			([int]$Session.launcherProcessId)
		)) {
			if (($processId -le 0) -or $stoppedProcessIds.ContainsKey($processId)) {
				continue
			}
			Stop-BenchClientProcessById -ProcessId $processId -TimeoutMs $script:BenchClientStopTimeoutMs
			$stoppedProcessIds[$processId] = $true
		}
	} finally {
		$configFilePath = ""
		if (($null -ne $Session) -and ($Session.PSObject.Properties.Name -contains "configFilePath")) {
			$configFilePath = [string]$Session.configFilePath
		}
		Remove-BenchClientAutomationConfigFile -ConfigFilePath $configFilePath
	}
}
