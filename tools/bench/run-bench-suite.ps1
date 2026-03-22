<#
.SYNOPSIS
RedstoneLink bench suite 编排脚本。
.DESCRIPTION
面向外部 dedicated server 的“每 case 一个新档”自动化流程：
1. 复制模板世界为新 case 世界
2. 最小修改 server.properties 的 level-name
3. 启动 dedicated server 并等待 RCON 就绪
4. 调用现有 run-bench.ps1 执行单 case
5. 收集 bench 结果并发送 stop
#>
param(
	[string]$ServerRoot,
	[string]$ServerPropertiesPath,
	[string]$ServerStartCommand,
	[string]$TemplateWorldPath,
	[string[]]$CaseIds,
	[string]$MatrixPath = (Join-Path $PSScriptRoot "matrix.json"),
	[string]$RconHost = "127.0.0.1",
	[int]$RconPort = 25575,
	[string]$RconPassword,
	[string]$AsPlayer,
	[string]$SparkActivityPath,
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

function New-Utf8NoBomEncoding {
	return (New-Object System.Text.UTF8Encoding($false))
}

function Write-Utf8NoBomFile {
	param(
		[string]$Path,
		[string]$Content
	)
	[System.IO.File]::WriteAllText($Path, $Content, (New-Utf8NoBomEncoding))
}

function Read-Utf8Text {
	param([string]$Path)
	return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Get-MatrixConfig {
	param([string]$Path)
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
		throw "Matrix file not found: $Path"
	}
	return (Get-Content -Path $Path -Encoding UTF8 -Raw | ConvertFrom-Json)
}

function Resolve-CaseIdList {
	param(
		$Matrix,
		[string[]]$RequestedCaseIds
	)
	if ($null -eq $RequestedCaseIds -or $RequestedCaseIds.Count -eq 0) {
		return @($Matrix.cases | ForEach-Object { [string]$_.id })
	}

	$knownIds = @{}
	foreach ($case in $Matrix.cases) {
		$knownIds[[string]$case.id] = $true
	}

	$resolved = New-Object System.Collections.Generic.List[string]
	foreach ($caseId in $RequestedCaseIds) {
		if (-not $knownIds.ContainsKey([string]$caseId)) {
			throw "Case not found in matrix: $caseId"
		}
		$resolved.Add([string]$caseId)
	}
	return @($resolved.ToArray())
}

function Ensure-Directory {
	param([string]$Path)
	if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
		New-Item -Path $Path -ItemType Directory -Force | Out-Null
	}
	return [System.IO.Path]::GetFullPath($Path)
}

function Get-SafeNamePart {
	param([string]$Value)
	$sanitized = [System.Text.RegularExpressions.Regex]::Replace($Value, "[^A-Za-z0-9_-]", "_")
	if ([string]::IsNullOrWhiteSpace($sanitized)) {
		return "case"
	}
	return $sanitized
}

function New-CaseWorldName {
	param(
		[string]$Prefix,
		[string]$CaseId,
		[int]$Index,
		[string]$SuiteTimestamp
	)
	$safeCaseId = Get-SafeNamePart -Value $CaseId
	return "{0}-{1}-{2:D2}-{3}" -f $Prefix, $safeCaseId, $Index, $SuiteTimestamp
}

function Get-CaseWorldsRootPath {
	param(
		[string]$ServerRootPath,
		[string]$DirectoryName
	)
	if (-not (Test-Path -LiteralPath $ServerRootPath -PathType Container)) {
		throw "Server root not found: $ServerRootPath"
	}
	return (Ensure-Directory -Path (Join-Path $ServerRootPath $DirectoryName))
}

function Get-CaseWorldLevelName {
	param(
		[string]$DirectoryName,
		[string]$WorldName
	)
	$normalizedDirectoryName = ""
	if (-not [string]::IsNullOrWhiteSpace($DirectoryName)) {
		$normalizedDirectoryName = $DirectoryName -replace "\\", "/" -replace "^/+", "" -replace "/+$", ""
	}
	if ([string]::IsNullOrWhiteSpace($normalizedDirectoryName)) {
		return $WorldName
	}
	return "$normalizedDirectoryName/$WorldName"
}

function Copy-TemplateWorld {
	param(
		[string]$TemplatePath,
		[string]$CaseWorldsRootPath,
		[string]$WorldName
	)
	if (-not (Test-Path -LiteralPath $TemplatePath -PathType Container)) {
		throw "Template world not found: $TemplatePath"
	}
	if (-not (Test-Path -LiteralPath $CaseWorldsRootPath -PathType Container)) {
		throw "Case worlds root not found: $CaseWorldsRootPath"
	}

	$targetWorldPath = Join-Path $CaseWorldsRootPath $WorldName
	if (Test-Path -LiteralPath $targetWorldPath) {
		throw "Target case world already exists: $targetWorldPath"
	}

	Copy-Item -LiteralPath $TemplatePath -Destination $targetWorldPath -Recurse -Force
	return [System.IO.Path]::GetFullPath($targetWorldPath)
}

function Set-ServerPropertyValue {
	param(
		[string]$Path,
		[string]$Key,
		[string]$Value
	)
	$text = Read-Utf8Text -Path $Path
	$lines = New-Object System.Collections.Generic.List[string]
	$reader = New-Object System.IO.StringReader($text)
	try {
		$line = $reader.ReadLine()
		$updated = $false
		while ($null -ne $line) {
			if (-not $updated -and $line -match "^\s*$([System.Text.RegularExpressions.Regex]::Escape($Key))\s*=") {
				$lines.Add("$Key=$Value")
				$updated = $true
			} else {
				$lines.Add($line)
			}
			$line = $reader.ReadLine()
		}
		if (-not $updated) {
			$lines.Add("$Key=$Value")
		}
	} finally {
		$reader.Dispose()
	}

	$joined = [string]::Join([Environment]::NewLine, $lines)
	if ($text.EndsWith("`n") -or $text.EndsWith("`r")) {
		$joined += [Environment]::NewLine
	}
	Write-Utf8NoBomFile -Path $Path -Content $joined
}

function Get-ResultsDirectoryPath {
	param(
		[string]$RepoRootPath,
		$Matrix
	)
	return (Ensure-Directory -Path (Join-Path $RepoRootPath ([string]$Matrix.defaults.resultsDir)))
}

function Get-ResultFileSnapshot {
	param([string]$ResultsDirPath)
	$snapshot = @{}
	if (-not (Test-Path -LiteralPath $ResultsDirPath -PathType Container)) {
		return $snapshot
	}
	foreach ($file in (Get-ChildItem -LiteralPath $ResultsDirPath -Filter "*.json" -File)) {
		$snapshot[$file.FullName] = $file.LastWriteTimeUtc
	}
	return $snapshot
}

function Find-NewBenchResultFile {
	param(
		[string]$ResultsDirPath,
		[hashtable]$BeforeSnapshot,
		[string]$CaseId,
		[datetime]$StartedAtUtc
	)
	$caseSuffix = "_$CaseId.json"
	$candidates = @(Get-ChildItem -LiteralPath $ResultsDirPath -Filter "*.json" -File |
		Where-Object { $_.Name.EndsWith($caseSuffix, [System.StringComparison]::OrdinalIgnoreCase) } |
		Sort-Object LastWriteTimeUtc -Descending)

	$newFiles = @($candidates | Where-Object { -not $BeforeSnapshot.ContainsKey($_.FullName) })
	if ($newFiles.Count -gt 0) {
		return $newFiles[0].FullName
	}

	$timeBased = @($candidates | Where-Object { $_.LastWriteTimeUtc -ge $StartedAtUtc } | Sort-Object LastWriteTimeUtc -Descending)
	if ($timeBased.Count -gt 0) {
		return $timeBased[0].FullName
	}
	return $null
}

function Read-BenchResultSummary {
	param([string]$ResultPath)
	if ([string]::IsNullOrWhiteSpace($ResultPath) -or -not (Test-Path -LiteralPath $ResultPath -PathType Leaf)) {
		return $null
	}
	$parsed = Get-Content -Path $ResultPath -Encoding UTF8 -Raw | ConvertFrom-Json
	$spark = Get-OptionalPsObjectPropertyValue -Object $parsed -PropertyName "spark"
	$stop = Get-OptionalPsObjectPropertyValue -Object $spark -PropertyName "stop"
	$profilerUrl = Get-OptionalActivityResultPrimaryValue -ActivityResult (
		Get-OptionalPsObjectPropertyValue -Object $stop -PropertyName "stopCpuActivity"
	)
	$healthUrl = Get-OptionalActivityResultPrimaryValue -ActivityResult (
		Get-OptionalPsObjectPropertyValue -Object $stop -PropertyName "healthActivity"
	)
	return [ordered]@{
		caseId = [string]$parsed.caseId
		resultPath = $ResultPath
		profilerUrl = $profilerUrl
		healthUrl = $healthUrl
	}
}

function Get-OptionalPsObjectPropertyValue {
	param(
		$Object,
		[string]$PropertyName
	)
	if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($PropertyName)) {
		return $null
	}
	$properties = $Object.PSObject.Properties
	if ($null -eq $properties -or -not ($properties.Name -contains $PropertyName)) {
		return $null
	}
	return $Object.$PropertyName
}

function Get-OptionalActivityResultPrimaryValue {
	param($ActivityResult)
	if ($null -eq $ActivityResult) {
		return $null
	}
	$url = Get-OptionalPsObjectPropertyValue -Object $ActivityResult -PropertyName "url"
	if (-not [string]::IsNullOrWhiteSpace([string]$url)) {
		return [string]$url
	}
	$fallbackResponse = Get-OptionalPsObjectPropertyValue -Object $ActivityResult -PropertyName "fallbackResponse"
	if (-not [string]::IsNullOrWhiteSpace([string]$fallbackResponse)) {
		return [string]$fallbackResponse
	}
	return $null
}

function New-RconPacketBytes {
	param(
		[int]$RequestId,
		[int]$PacketType,
		[string]$Body
	)
	$encoding = [System.Text.Encoding]::UTF8
	$bodyBytes = $encoding.GetBytes($Body)
	$packetLength = 4 + 4 + $bodyBytes.Length + 2
	$buffer = New-Object byte[] (4 + $packetLength)
	[System.BitConverter]::GetBytes($packetLength).CopyTo($buffer, 0)
	[System.BitConverter]::GetBytes($RequestId).CopyTo($buffer, 4)
	[System.BitConverter]::GetBytes($PacketType).CopyTo($buffer, 8)
	$bodyBytes.CopyTo($buffer, 12)
	$buffer[$buffer.Length - 2] = 0
	$buffer[$buffer.Length - 1] = 0
	return $buffer
}

function Read-ExactBytes {
	param(
		[System.IO.Stream]$Stream,
		[int]$Count
	)
	$buffer = New-Object byte[] $Count
	$offset = 0
	while ($offset -lt $Count) {
		$read = $Stream.Read($buffer, $offset, $Count - $offset)
		if ($read -le 0) {
			throw "Unexpected EOF while reading RCON packet."
		}
		$offset += $read
	}
	return $buffer
}

function Read-RconPacket {
	param([System.IO.Stream]$Stream)
	$lengthBytes = Read-ExactBytes -Stream $Stream -Count 4
	$length = [System.BitConverter]::ToInt32($lengthBytes, 0)
	$payload = Read-ExactBytes -Stream $Stream -Count $length
	$requestId = [System.BitConverter]::ToInt32($payload, 0)
	$packetType = [System.BitConverter]::ToInt32($payload, 4)
	$bodyLength = [Math]::Max(0, $length - 10)
	$body = if ($bodyLength -gt 0) {
		[System.Text.Encoding]::UTF8.GetString($payload, 8, $bodyLength)
	} else {
		""
	}
	return [pscustomobject]@{
		RequestId = $requestId
		PacketType = $packetType
		Body = $body
	}
}

function Read-RconPacketIfAvailable {
	param(
		[System.Net.Sockets.TcpClient]$Client,
		[System.IO.Stream]$Stream,
		[int]$WaitTimeoutMs = 500
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($stopwatch.ElapsedMilliseconds -lt $WaitTimeoutMs) {
		if ($Client.Available -gt 0) {
			return (Read-RconPacket -Stream $Stream)
		}
		Start-Sleep -Milliseconds 20
	}
	return $null
}

function Open-RconConnection {
	param(
		[string]$ServerHost,
		[int]$Port,
		[string]$Password
	)
	if ([string]::IsNullOrWhiteSpace($Password)) {
		throw "RconPassword is required."
	}

	$client = New-Object System.Net.Sockets.TcpClient
	$client.ReceiveTimeout = 3000
	$client.SendTimeout = 3000
	$client.Connect($ServerHost, $Port)
	$stream = $client.GetStream()

	$authPacket = New-RconPacketBytes -RequestId 1 -PacketType 3 -Body $Password
	$stream.Write($authPacket, 0, $authPacket.Length)
	$stream.Flush()

	try {
		$first = Read-RconPacket -Stream $stream
	} catch {
		$client.Dispose()
		throw "Timed out waiting for the first RCON auth response from ${ServerHost}:$Port. $($_.Exception.Message)"
	}

	$second = $null
	$firstLooksLikePrelude = $first.PacketType -eq 0 -and [string]::IsNullOrEmpty($first.Body)
	if ($firstLooksLikePrelude) {
		$second = Read-RconPacketIfAvailable -Client $client -Stream $stream -WaitTimeoutMs 500
	} else {
		$second = Read-RconPacketIfAvailable -Client $client -Stream $stream -WaitTimeoutMs 120
	}

	$authResponse = if ($null -ne $second -and ($second.RequestId -eq 1 -or $second.RequestId -eq -1)) {
		$second
	} elseif ($first.RequestId -eq 1 -or $first.RequestId -eq -1) {
		$first
	} elseif ($null -ne $second) {
		$second
	} else {
		$first
	}
	if ($authResponse.RequestId -eq -1) {
		$client.Dispose()
		throw "RCON auth failed."
	}
	if ($authResponse.RequestId -ne 1) {
		$client.Dispose()
		throw "Unexpected RCON auth response. requestId=$($authResponse.RequestId), packetType=$($authResponse.PacketType)"
	}

	return [pscustomobject]@{
		Client = $client
		Stream = $stream
		NextRequestId = 10
	}
}

function Close-RconConnection {
	param($Connection)
	if ($null -ne $Connection) {
		if ($null -ne $Connection.Stream) {
			$Connection.Stream.Dispose()
		}
		if ($null -ne $Connection.Client) {
			$Connection.Client.Dispose()
		}
	}
}

function Invoke-RconCommand {
	param(
		$Connection,
		[string]$Command,
		[int]$ReceiveTimeoutMs = 3000
	)
	if ($null -eq $Connection) {
		throw "RCON connection is not open."
	}

	$requestId = [int]$Connection.NextRequestId
	$Connection.NextRequestId = $requestId + 1
	$packet = New-RconPacketBytes -RequestId $requestId -PacketType 2 -Body $Command
	$Connection.Stream.Write($packet, 0, $packet.Length)
	$Connection.Stream.Flush()

	$previousReceiveTimeout = $Connection.Client.ReceiveTimeout
	$Connection.Client.ReceiveTimeout = $ReceiveTimeoutMs
	$responseParts = New-Object System.Collections.Generic.List[string]
	$receivedPackets = New-Object System.Collections.Generic.List[object]
	try {
		$firstPacket = Read-RconPacket -Stream $Connection.Stream
		$receivedPackets.Add($firstPacket)
		while ($true) {
			$nextPacket = Read-RconPacketIfAvailable -Client $Connection.Client -Stream $Connection.Stream -WaitTimeoutMs 120
			if ($null -eq $nextPacket) {
				break
			}
			$receivedPackets.Add($nextPacket)
		}
		foreach ($packetResponse in $receivedPackets) {
			if ($packetResponse.RequestId -eq $requestId -and -not [string]::IsNullOrWhiteSpace($packetResponse.Body)) {
				$responseParts.Add($packetResponse.Body)
			}
		}
	} finally {
		$Connection.Client.ReceiveTimeout = $previousReceiveTimeout
	}

	return (($responseParts -join "`n").Trim())
}

function Test-RconAlreadyReachable {
	param(
		[string]$ServerHost,
		[int]$Port,
		[string]$Password
	)
	try {
		$connection = Open-RconConnection -ServerHost $ServerHost -Port $Port -Password $Password
		Close-RconConnection -Connection $connection
		return $true
	} catch {
		return $false
	}
}

function Wait-RconReady {
	param(
		[string]$ServerHost,
		[int]$Port,
		[string]$Password,
		[int]$TimeoutMs,
		[int]$PollIntervalMs
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	$lastError = $null
	while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMs) {
		try {
			$connection = Open-RconConnection -ServerHost $ServerHost -Port $Port -Password $Password
			Close-RconConnection -Connection $connection
			return
		} catch {
			$lastError = $_.Exception.Message
			if ($lastError -match "RCON auth failed|RconPassword is required") {
				throw
			}
		}
		Start-Sleep -Milliseconds $PollIntervalMs
	}
	throw "Timed out waiting for RCON to become ready. lastError=$lastError"
}

function Stop-ServerByRcon {
	param(
		[string]$ServerHost,
		[int]$Port,
		[string]$Password
	)
	$connection = $null
	try {
		$connection = Open-RconConnection -ServerHost $ServerHost -Port $Port -Password $Password
		Invoke-RconCommand -Connection $connection -Command "stop" -ReceiveTimeoutMs 8000 | Out-Null
	} finally {
		Close-RconConnection -Connection $connection
	}
}

function Wait-ProcessExit {
	param(
		[System.Diagnostics.Process]$Process,
		[int]$TimeoutMs,
		[int]$PollIntervalMs
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMs) {
		if ($Process.HasExited) {
			return
		}
		Start-Sleep -Milliseconds $PollIntervalMs
		$Process.Refresh()
	}
	throw "Server process did not exit within timeout. pid=$($Process.Id)"
}

function Start-DedicatedServerProcess {
	param(
		[string]$WorkingDirectory,
		[string]$Command
	)
	if ([string]::IsNullOrWhiteSpace($Command)) {
		throw "ServerStartCommand is required."
	}

	return (Start-Process -FilePath "cmd.exe" -ArgumentList @("/c", $Command) -WorkingDirectory $WorkingDirectory -PassThru)
}

function Write-SuiteSummaryJson {
	param(
		[string]$OutputPath,
		$SummaryObject
	)
	$jsonText = $SummaryObject | ConvertTo-Json -Depth 12
	Write-Utf8NoBomFile -Path $OutputPath -Content $jsonText
	return $OutputPath
}

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
	throw "ServerRoot is required."
}
if ([string]::IsNullOrWhiteSpace($TemplateWorldPath)) {
	throw "TemplateWorldPath is required."
}
if ([string]::IsNullOrWhiteSpace($RconPassword)) {
	throw "RconPassword is required."
}
if ([string]::IsNullOrWhiteSpace($ServerPropertiesPath)) {
	throw "ServerPropertiesPath is required."
}

$matrix = Get-MatrixConfig -Path $MatrixPath
$resolvedCaseIds = @(Resolve-CaseIdList -Matrix $matrix -RequestedCaseIds $CaseIds)
$suiteTimestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$suiteOutputDirectory = Ensure-Directory -Path (Join-Path $SuiteResultsDir $suiteTimestamp)
$resultsDirPath = Get-ResultsDirectoryPath -RepoRootPath $repoRoot -Matrix $matrix
$benchScriptPath = Join-Path $PSScriptRoot "run-bench.ps1"
$serverRootFullPath = [System.IO.Path]::GetFullPath($ServerRoot)
$templateWorldFullPath = [System.IO.Path]::GetFullPath($TemplateWorldPath)
$serverPropertiesFullPath = [System.IO.Path]::GetFullPath($ServerPropertiesPath)
$caseWorldsRootPath = Get-CaseWorldsRootPath -ServerRootPath $serverRootFullPath -DirectoryName $caseWorldsDirectoryName
$originalServerPropertiesText = Read-Utf8Text -Path $serverPropertiesFullPath

if (Test-RconAlreadyReachable -ServerHost $RconHost -Port $RconPort -Password $RconPassword) {
	throw "RCON is already reachable before suite start. Stop the dedicated server first to ensure each case loads its own fresh world."
}

$suiteSummary = [ordered]@{
	suiteTimestamp = $suiteTimestamp
	serverRoot = $serverRootFullPath
	serverPropertiesPath = $serverPropertiesFullPath
	templateWorldPath = $templateWorldFullPath
	caseWorldsRootPath = $caseWorldsRootPath
	caseIds = $resolvedCaseIds
	startedAt = (Get-Date).ToString("s")
	results = @()
	restoredServerProperties = $false
}

Write-Host "[BenchSuite] Suite: $suiteTimestamp"
Write-Host "[BenchSuite] Server root: $serverRootFullPath"
Write-Host "[BenchSuite] Case worlds root: $caseWorldsRootPath"
Write-Host "[BenchSuite] Template world: $templateWorldFullPath"
Write-Host "[BenchSuite] Cases: $($resolvedCaseIds -join ', ')"

try {
	for ($index = 0; $index -lt $resolvedCaseIds.Count; $index++) {
		$caseId = [string]$resolvedCaseIds[$index]
		$worldName = New-CaseWorldName -Prefix $CaseWorldPrefix -CaseId $caseId -Index ($index + 1) -SuiteTimestamp $suiteTimestamp
		$worldLevelName = Get-CaseWorldLevelName -DirectoryName $caseWorldsDirectoryName -WorldName $worldName
		$caseRecord = [ordered]@{
			caseId = $caseId
			worldName = $worldName
			worldLevelName = $worldLevelName
			status = "pending"
			startedAt = (Get-Date).ToString("s")
		}
		$serverProcess = $null
		$worldPath = $null
		try {
			$worldPath = Copy-TemplateWorld -TemplatePath $templateWorldFullPath -CaseWorldsRootPath $caseWorldsRootPath -WorldName $worldName
			$caseRecord.worldPath = $worldPath
			Write-Host "[BenchSuite] Case $caseId -> world $worldName"

			Set-ServerPropertyValue -Path $serverPropertiesFullPath -Key "level-name" -Value $worldLevelName
			$serverProcess = Start-DedicatedServerProcess -WorkingDirectory $serverRootFullPath -Command $ServerStartCommand
			$caseRecord.serverPid = $serverProcess.Id
			Wait-RconReady -ServerHost $RconHost -Port $RconPort -Password $RconPassword -TimeoutMs $StartupTimeoutMs -PollIntervalMs $StartupPollIntervalMs

			$beforeSnapshot = Get-ResultFileSnapshot -ResultsDirPath $resultsDirPath
			$startedAtUtc = [datetime]::UtcNow
			$benchArgs = @{
				Action = "RunCase"
				CaseId = $caseId
				MatrixPath = $MatrixPath
				SavePath = $worldPath
				RconHost = $RconHost
				RconPort = $RconPort
				RconPassword = $RconPassword
			}
			if (-not [string]::IsNullOrWhiteSpace($AsPlayer)) {
				$benchArgs.AsPlayer = $AsPlayer
			}
			if (-not [string]::IsNullOrWhiteSpace($SparkActivityPath)) {
				$benchArgs.SparkActivityPath = $SparkActivityPath
			}

			& $benchScriptPath @benchArgs

			$resultPath = Find-NewBenchResultFile -ResultsDirPath $resultsDirPath -BeforeSnapshot $beforeSnapshot -CaseId $caseId -StartedAtUtc $startedAtUtc
			if ($null -eq $resultPath) {
				throw "Bench result JSON not found for case: $caseId"
			}
			$benchSummary = Read-BenchResultSummary -ResultPath $resultPath
			$caseRecord.bench = $benchSummary
			$caseRecord.status = "success"
		} catch {
			$caseRecord.status = "failed"
			$caseRecord.error = $_.Exception.Message
			Write-Host "[BenchSuite] Case failed: $caseId"
			Write-Host "[BenchSuite] Error: $($caseRecord.error)"
			if (-not $ContinueOnFailure) {
				throw
			}
		} finally {
			if ($null -ne $serverProcess) {
				try {
					if (-not $serverProcess.HasExited) {
						Stop-ServerByRcon -ServerHost $RconHost -Port $RconPort -Password $RconPassword
						Wait-ProcessExit -Process $serverProcess -TimeoutMs $ShutdownTimeoutMs -PollIntervalMs $ShutdownPollIntervalMs
					}
				} catch {
					$caseRecord.stopError = $_.Exception.Message
					Write-Host "[BenchSuite] Stop server failed for case ${caseId}: $($caseRecord.stopError)"
					if (-not $ContinueOnFailure -and $caseRecord.status -eq "success") {
						throw
					}
				}
			}

			if ($DeleteCaseWorldOnSuccess -and $caseRecord.status -eq "success" -and -not [string]::IsNullOrWhiteSpace([string]$worldPath) -and (Test-Path -LiteralPath $worldPath)) {
				Remove-Item -LiteralPath $worldPath -Recurse -Force
				$caseRecord.worldDeleted = $true
			}

			$caseRecord.completedAt = (Get-Date).ToString("s")
			$suiteSummary.results += $caseRecord
		}
	}
} finally {
	Write-Utf8NoBomFile -Path $serverPropertiesFullPath -Content $originalServerPropertiesText
	$suiteSummary.restoredServerProperties = $true
	$suiteSummary.completedAt = (Get-Date).ToString("s")
	$suiteSummary.summaryPath = (Join-Path $suiteOutputDirectory "summary.json")
	Write-SuiteSummaryJson -OutputPath $suiteSummary.summaryPath -SummaryObject $suiteSummary | Out-Null
	Write-Host "[BenchSuite] Summary -> $($suiteSummary.summaryPath)"
}
