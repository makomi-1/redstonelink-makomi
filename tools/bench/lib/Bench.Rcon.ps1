<#
.SYNOPSIS
bench 模块：RCON 连接、命令收发与 tick 观测。
#>

$script:BenchClientCommandBridgeRequestCounter = 1L
$script:BenchPlayerCommandBridgeReady = $false

function Set-BenchPlayerCommandBridgeContext {
	param(
		[string]$AsPlayer,
		[string]$BenchClientPlayerName,
		[string]$RequestFilePath,
		[string]$ResponseFilePath,
		[string]$DispatchMode
	)
	$script:BenchAsPlayer = if ([string]::IsNullOrWhiteSpace($AsPlayer)) { "" } else { ([string]$AsPlayer).Trim() }
	$script:BenchClientPlayerName = if ([string]::IsNullOrWhiteSpace($BenchClientPlayerName)) {
		""
	} else {
		([string]$BenchClientPlayerName).Trim()
	}
	$script:BenchClientCommandBridgeRequestFilePath = if ([string]::IsNullOrWhiteSpace($RequestFilePath)) {
		""
	} else {
		([string]$RequestFilePath).Trim()
	}
	$script:BenchClientCommandBridgeResponseFilePath = if ([string]::IsNullOrWhiteSpace($ResponseFilePath)) {
		""
	} else {
		([string]$ResponseFilePath).Trim()
	}
	$script:BenchClientCommandBridgeDispatchMode = if ([string]::IsNullOrWhiteSpace($DispatchMode)) {
		"server_network"
	} else {
		([string]$DispatchMode).Trim().ToLowerInvariant()
	}
	$script:BenchPlayerCommandBridgeReady = $false
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

function Test-BenchResponseLooksLikeFailure {
	param([string]$ResponseText)
	$normalized = ([string]$ResponseText).Trim()
	if ([string]::IsNullOrWhiteSpace($normalized)) {
		return $false
	}
	$failurePatterns = @(
		"(?i)\bUnknown(?: or incomplete)? command\b",
		"(?i)\bCould not parse command\b",
		"(?i)\bIncorrect argument\b",
		"(?i)\bNo entity was found\b",
		"(?i)\bNo player was found\b",
		"(?i)\bToo many requests\b",
		"(?i)\binsufficient permission\b",
		"(?i)\bplayer[- ]only\b",
		"(?i)\bunallocated\b",
		"(?i)\bInvalid (?:serial|serials|source|sources|target|targets|role|type|scope|protected serial)\b",
		"(?i)\b(?:Source|Target) serial \d+ is retired\b",
		"(?i)\bRetired target serials\b",
		"\u6e90\u5e8f\u53f7\\s*\\d+\\s*\u5df2\u9000\u5f79",
		"\u76ee\u6807\u5e8f\u53f7\\s*\\d+\\s*\u5df2\u9000\u5f79",
		"\u4ee5\u4e0b\u76ee\u6807\u5e8f\u53f7\u5df2\u9000\u5f79",
		"(?i)\bOffline targets are blocked\b",
		"(?i)\bThese targets are offline or in unloaded chunks\b",
		"(?i)\bnot found\b",
		"(?i)\bunsupported input endpoint\b",
		"\u6ca1\u6709\u8db3\u591f\u6743\u9650",
		"\u64cd\u4f5c\u8fc7\u4e8e\u9891\u7e41",
		"\u914d\u7f6e\u5df2\u7981\u6b62\u79bb\u7ebf\u7ed1\u5b9a",
		"\u4ee5\u4e0b\u76ee\u6807\u5f53\u524d\u4e0d\u5728\u7ebf\u6216\u533a\u5757\u672a\u52a0\u8f7d"
	)
	foreach ($pattern in $failurePatterns) {
		if ($normalized -match $pattern) {
			return $true
		}
	}
	return $false
}

function Assert-BenchCommandResponse {
	param(
		[string]$Command,
		[string]$ResponseText,
		[string]$ExpectedPrefix = "",
		[string]$ExpectedRegex = ""
	)
	$normalized = ([string]$ResponseText).Trim()
	if ([string]::IsNullOrWhiteSpace($normalized)) {
		throw "Bench command returned empty response: $Command"
	}
	if (Test-BenchResponseLooksLikeFailure -ResponseText $normalized) {
		throw "Bench command returned failure response: $Command | response=$normalized"
	}
	if (-not [string]::IsNullOrWhiteSpace($ExpectedPrefix) -and -not $normalized.StartsWith($ExpectedPrefix, [System.StringComparison]::Ordinal)) {
		throw "Bench command response prefix mismatch: expectedPrefix=$ExpectedPrefix command=$Command | response=$normalized"
	}
	if (-not [string]::IsNullOrWhiteSpace($ExpectedRegex) -and -not [System.Text.RegularExpressions.Regex]::IsMatch($normalized, $ExpectedRegex)) {
		throw "Bench command response missing expected marker: command=$Command expectedRegex=$ExpectedRegex | response=$normalized"
	}
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
		throw "Timed out waiting for the first RCON auth response from ${ServerHost}:$Port. Verify enable-rcon/rcon.port/rcon.password and ensure the server is fully started. $($_.Exception.Message)"
	}

	# 某些服务端会先回一个空的 response value，再回真正的 auth response。
	$second = $null
	$firstLooksLikePrelude = $first.PacketType -eq 0 -and [string]::IsNullOrEmpty($first.Body)
	if ($firstLooksLikePrelude) {
		try {
			$second = Read-RconPacketIfAvailable -Client $client -Stream $stream -WaitTimeoutMs 500
		} catch {
			$client.Dispose()
			throw "Timed out waiting for the final RCON auth response from ${ServerHost}:$Port after receiving an auth prelude packet. $($_.Exception.Message)"
		}
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

function Assert-RunCasePlayerContext {
	if ($DryRun) {
		return
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		if ([bool]$script:BenchAutoTeleportPlayerToObservationPoint) {
			throw "AutoTeleportPlayerToObservationPoint requires AsPlayer."
		}
		Write-Host "[Bench] AsPlayer not provided; server.command.benchmarkMode.enabled must be true or player-only commands will fail."
		return
	}
	if ($script:BenchAsPlayer -match "\s") {
		throw "AsPlayer must be a single player name or selector without whitespace."
	}
}

function Wrap-WithPlayerContext {
	param(
		[string]$Command
	)
	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		return $Command
	}
	return "execute as $($script:BenchAsPlayer) at $($script:BenchAsPlayer) run $Command"
}

function Wrap-WithDimensionContext {
	param(
		[string]$Command,
		[string]$Dimension
	)
	if ([string]::IsNullOrWhiteSpace($Dimension)) {
		return $Command
	}
	return "execute in $Dimension run $Command"
}

function Wrap-WithBenchContexts {
	param(
		[string]$Command,
		[string]$Dimension = "",
		[switch]$SkipPlayerContext
	)
	$wrappedCommand = [string]$Command
	if (-not $SkipPlayerContext) {
		if ([string]::IsNullOrWhiteSpace($Dimension)) {
			return (Wrap-WithPlayerContext $wrappedCommand)
		}
		return (Wrap-WithPlayerContext (Wrap-WithDimensionContext -Command $wrappedCommand -Dimension $Dimension))
	}
	return (Wrap-WithDimensionContext -Command $wrappedCommand -Dimension $Dimension)
}

function Convert-BenchBridgeTextToBase64 {
	param([string]$Text)
	if ([string]::IsNullOrEmpty($Text)) {
		return ""
	}
	return [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Text))
}

function Convert-BenchBridgeBase64ToText {
	param([string]$EncodedText)
	if ([string]::IsNullOrWhiteSpace($EncodedText)) {
		return ""
	}
	try {
		return [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($EncodedText.Trim()))
	} catch {
		return ""
	}
}

function Write-BenchBridgeFileAtomically {
	param(
		[string]$Path,
		[string]$Content
	)
	if ([string]::IsNullOrWhiteSpace($Path)) {
		throw "Bench bridge path is required."
	}
	$tempPath = "$Path.tmp"
	Write-Utf8NoBomFile -Path $tempPath -Content $Content
	Move-Item -LiteralPath $tempPath -Destination $Path -Force
}

function Read-BenchBridgePropertiesFile {
	param([string]$Path)
	if ([string]::IsNullOrWhiteSpace($Path)) {
		throw "Bench bridge path is required."
	}
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
		return $null
	}
	$result = @{}
	foreach ($rawLine in @([System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8))) {
		$currentLine = [string]$rawLine
		if ([string]::IsNullOrWhiteSpace($currentLine)) {
			continue
		}
		$separatorIndex = $currentLine.IndexOf("=")
		if ($separatorIndex -lt 0) {
			continue
		}
		$key = $currentLine.Substring(0, $separatorIndex).Trim()
		$value = $currentLine.Substring($separatorIndex + 1)
		if (-not [string]::IsNullOrWhiteSpace($key)) {
			$result[$key] = $value
		}
	}
	return $result
}

function Test-BenchPlayerCommandBridgeEnabled {
	if ($DryRun) {
		return $false
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		return $false
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchClientPlayerName)) {
		return $false
	}
	if (-not [string]::Equals($script:BenchAsPlayer, $script:BenchClientPlayerName, [System.StringComparison]::Ordinal)) {
		return $false
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchClientCommandBridgeRequestFilePath)) {
		return $false
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchClientCommandBridgeResponseFilePath)) {
		return $false
	}
	return $true
}

function Get-BenchPlayerCommandBridgeDispatchMode {
	$rawMode = [string]$script:BenchClientCommandBridgeDispatchMode
	if ([string]::IsNullOrWhiteSpace($rawMode)) {
		return "server_network"
	}
	$normalizedMode = $rawMode.Trim().ToLowerInvariant()
	switch ($normalizedMode) {
		"server_network" {
			return $normalizedMode
		}
		"client_direct_command" {
			return $normalizedMode
		}
		default {
			Write-Host "[Bench] Unknown bench player command bridge dispatch mode '$rawMode'; fallback to server_network."
			return "server_network"
		}
	}
}

function Resolve-BenchPlayerBridgeInnerCommand {
	param([string]$Command)
	if (-not (Test-BenchPlayerCommandBridgeEnabled)) {
		return $null
	}
	if (-not [bool]$script:BenchPlayerCommandBridgeReady) {
		return $null
	}
	$playerContextPrefix = "execute as $($script:BenchAsPlayer) at $($script:BenchAsPlayer) run "
	$normalizedCommand = [string]$Command
	if (-not $normalizedCommand.StartsWith($playerContextPrefix, [System.StringComparison]::Ordinal)) {
		return $null
	}
	return $normalizedCommand.Substring($playerContextPrefix.Length)
}

function Invoke-BenchPlayerCommandBridge {
	param(
		[string]$Command,
		[int]$WaitTimeoutMs = 5000
	)
	if (-not (Test-BenchPlayerCommandBridgeEnabled)) {
		throw "Bench player command bridge is not available."
	}
	$normalizedCommand = if ($null -eq $Command) { "" } else { [string]$Command }
	$requestFilePath = [string]$script:BenchClientCommandBridgeRequestFilePath
	$responseFilePath = [string]$script:BenchClientCommandBridgeResponseFilePath
	$requestId = [long]$script:BenchClientCommandBridgeRequestCounter
	$script:BenchClientCommandBridgeRequestCounter++
	$normalizedWaitTimeoutMs = [Math]::Max(1000, [int]$WaitTimeoutMs)

	Clear-BenchClientCommandBridgeFiles -RequestFilePath $requestFilePath -ResponseFilePath $responseFilePath
	$requestContent = @(
		"request.id=$requestId"
		"dispatch.mode=$(Get-BenchPlayerCommandBridgeDispatchMode)"
		"capture.tick.window=false"
		"command.base64=$(Convert-BenchBridgeTextToBase64 -Text $normalizedCommand)"
		""
	) -join "`r`n"
	Write-BenchBridgeFileAtomically -Path $requestFilePath -Content $requestContent

	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($stopwatch.ElapsedMilliseconds -lt $normalizedWaitTimeoutMs) {
		if (Test-Path -LiteralPath $responseFilePath -PathType Leaf) {
			$responseProperties = Read-BenchBridgePropertiesFile -Path $responseFilePath
			if (($null -ne $responseProperties) -and ($responseProperties.ContainsKey("request.id"))) {
				$responseRequestId = [long]$responseProperties["request.id"]
				if ($responseRequestId -eq $requestId) {
					return [pscustomobject]@{
						requestId = $responseRequestId
						callbackSuccess = [bool]::Parse([string]($responseProperties["callback.success"]))
						resultCode = [int]$responseProperties["result.code"]
						hasTickWindow = [bool]::Parse([string]($responseProperties["has.tick.window"]))
						tickWindowStart = [long]$responseProperties["tick.window.start"]
						tickWindowEnd = [long]$responseProperties["tick.window.end"]
						output = Convert-BenchBridgeBase64ToText -EncodedText ([string]$responseProperties["output.base64"])
						errorDetail = Convert-BenchBridgeBase64ToText -EncodedText ([string]$responseProperties["error.detail.base64"])
					}
				}
			}
		}
		Start-Sleep -Milliseconds 20
	}

	Remove-BenchClientCommandBridgeFile -Path $requestFilePath
	throw "Timed out waiting for bench player command bridge response within ${normalizedWaitTimeoutMs}ms."
}

# 统一返回玩家上下文就绪探针，默认探测当前玩家实体坐标。
function Get-PlayerReadyProbeCommand {
	param()

	$probeCommand = [string]$script:BenchPlayerReadyProbeCommand
	if ([string]::IsNullOrWhiteSpace($probeCommand)) {
		return "data get entity @s Pos"
	}

	return $probeCommand.Trim()
}

# 执行一次玩家上下文探针，判断选择器是否已可用。
function Test-PlayerContextReady {
	param(
		$Connection
	)

	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		return [pscustomobject]@{
			ready = $true
			command = $null
			response = ""
		}
	}

	$probeCommand = Get-PlayerReadyProbeCommand
	$wrappedCommand = Wrap-WithPlayerContext $probeCommand
	$response = Invoke-RconCommand -Connection $Connection -Command $wrappedCommand -Silent
	$normalized = ([string]$response).Trim()
	$ready = $false

	if ((-not [string]::IsNullOrWhiteSpace($normalized)) -and (-not (Test-BenchResponseLooksLikeFailure -ResponseText $normalized))) {
		$ready = $true
	}

	return [pscustomobject]@{
		ready = $ready
		command = $wrappedCommand
		response = $normalized
	}
}

# 轮询等待玩家上下文就绪，超时则直接失败，避免后续 place/link/input 命令误报。
function Wait-PlayerContextReady {
	param(
		$Connection
	)

	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		return $null
	}

	$timeoutMs = [Math]::Max(0, [int]$script:BenchPlayerReadyTimeoutMs)
	$pollIntervalMs = [Math]::Max(50, [int]$script:BenchPlayerReadyPollIntervalMs)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	$lastProbe = $null

	do {
		$lastProbe = Test-PlayerContextReady -Connection $Connection
		if ([bool]$lastProbe.ready) {
			$script:BenchPlayerCommandBridgeReady = $true
			Write-Host "[Bench] Player context ready: $($script:BenchAsPlayer) ($($stopwatch.ElapsedMilliseconds)ms)"
			return [pscustomobject]@{
				selector = $script:BenchAsPlayer
				waitMs = $stopwatch.ElapsedMilliseconds
				probeCommand = (Get-PlayerReadyProbeCommand)
				wrappedProbeCommand = $lastProbe.command
				probeResponse = $lastProbe.response
			}
		}

		if ($stopwatch.ElapsedMilliseconds -ge $timeoutMs) {
			break
		}

		Start-Sleep -Milliseconds $pollIntervalMs
	} while ($true)

	$lastResponse = ""
	if ($null -ne $lastProbe) {
		$lastResponse = [string]$lastProbe.response
	}

	throw "Timed out waiting for player context '$($script:BenchAsPlayer)' within ${timeoutMs}ms. probe=$(Get-PlayerReadyProbeCommand) lastResponse=$lastResponse"
}

# 在玩家上下文下执行初始化命令，适合做 tag/tp/gamemode 等测试前准备。
function Invoke-PlayerContextSetupCommands {
	param(
		$Connection
	)

	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		return @()
	}

	$setupCommands = @($script:BenchPlayerSetupCommands)
	if ($setupCommands.Count -le 0) {
		return @()
	}

	$operations = New-Object System.Collections.Generic.List[object]
	foreach ($commandText in $setupCommands) {
		$wrappedCommand = Wrap-WithPlayerContext ([string]$commandText)
		$response = Invoke-RconCommand -Connection $Connection -Command $wrappedCommand -Silent
		if (Test-BenchResponseLooksLikeFailure -ResponseText $response) {
			throw "Player setup command failed: $wrappedCommand | response=$response"
		}

		$operations.Add([pscustomobject]@{
			command = $wrappedCommand
			response = $response
		})
	}

	return @($operations.ToArray())
}

# 对外统一入口：先等玩家就绪，再执行玩家初始化命令，并返回本轮摘要。
function Ensure-PlayerContextReadyAndSetup {
	param(
		$Connection
	)

	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		return $null
	}

	$readySummary = Wait-PlayerContextReady -Connection $Connection
	$setupOperations = Invoke-PlayerContextSetupCommands -Connection $Connection

	return [pscustomobject]@{
		selector = $script:BenchAsPlayer
		waitTimeoutMs = [Math]::Max(0, [int]$script:BenchPlayerReadyTimeoutMs)
		pollIntervalMs = [Math]::Max(50, [int]$script:BenchPlayerReadyPollIntervalMs)
		probeCommand = $readySummary.probeCommand
		wrappedProbeCommand = $readySummary.wrappedProbeCommand
		waitMs = $readySummary.waitMs
		probeResponse = $readySummary.probeResponse
		setupCommands = $setupOperations
	}
}

# Move the player to the computed observation point after place/link.
function Invoke-PlayerObservationTeleport {
	param(
		$Connection,
		$ObservationPoint,
		[string]$Dimension = ""
	)

	if (-not [bool]$script:BenchAutoTeleportPlayerToObservationPoint) {
		return $null
	}
	if ([string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
		throw "AutoTeleportPlayerToObservationPoint requires AsPlayer."
	}
	if ($null -eq $ObservationPoint) {
		throw "Observation point is required when AutoTeleportPlayerToObservationPoint is enabled."
	}

	$positionText = Format-PreciseVec3 -Vec $ObservationPoint.Position
	$facingText = Format-PreciseVec3 -Vec $ObservationPoint.Facing
	$resolvedDimension = if ([string]::IsNullOrWhiteSpace($Dimension)) {
		[string](Get-OptionalProperty -Object $ObservationPoint -Name "Dimension" -DefaultValue "")
	} else {
		$Dimension
	}
	$wrappedCommand = Wrap-WithBenchContexts `
		-Command ("tp @s {0} facing {1}" -f $positionText, $facingText) `
		-Dimension $resolvedDimension
	$response = Invoke-RconCommand -Connection $Connection -Command $wrappedCommand -Silent
	if (Test-BenchResponseLooksLikeFailure -ResponseText $response) {
		throw "Player observation teleport failed: $wrappedCommand | response=$response"
	}

	return [pscustomobject]@{
		command = $wrappedCommand
		response = $response
		dimension = if ([string]::IsNullOrWhiteSpace($resolvedDimension)) { $null } else { $resolvedDimension }
		position = $ObservationPoint.Position
		facing = $ObservationPoint.Facing
		bounds = $ObservationPoint.Bounds
		spanX = $ObservationPoint.SpanX
		spanY = $ObservationPoint.SpanY
		spanZ = $ObservationPoint.SpanZ
		verticalOffset = $ObservationPoint.VerticalOffset
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
		[switch]$Silent,
		[int]$ReceiveTimeoutMs = 3000,
		[switch]$AllowReadTimeout
	)
	if ($DryRun) {
		if (-not $Silent) {
			Write-Host "[Bench/DryRun] $Command"
		}
		return ""
	}
	if ($null -eq $Connection) {
		throw "RCON connection is not open."
	}
	$bridgeCommand = Resolve-BenchPlayerBridgeInnerCommand -Command $Command
	if ($null -ne $bridgeCommand) {
		try {
			$bridgeResult = Invoke-BenchPlayerCommandBridge -Command $bridgeCommand -WaitTimeoutMs ([Math]::Max(5000, [int]$ReceiveTimeoutMs))
		} catch {
			if ($AllowReadTimeout) {
				if (-not $Silent) {
					Write-Host "[Bench/PLAYER] $Command"
					Write-Host "[Bench/RESP] <bridge timeout tolerated>"
				}
				return ""
			}
			throw
		}
		$responseText = [string]$bridgeResult.output
		if ([string]::IsNullOrWhiteSpace($responseText) -and -not [string]::IsNullOrWhiteSpace([string]$bridgeResult.errorDetail)) {
			throw "Bench player command bridge returned empty response: $Command | detail=$([string]$bridgeResult.errorDetail)"
		}
		if (-not $Silent) {
			Write-Host "[Bench/PLAYER] $Command"
			if ($responseText) {
				Write-Host "[Bench/RESP] $responseText"
			}
		}
		return $responseText
	}
	$requestId = [int]$Connection.NextRequestId
	$Connection.NextRequestId = $requestId + 1

	$packet = New-RconPacketBytes -RequestId $requestId -PacketType 2 -Body $Command
	$Connection.Stream.Write($packet, 0, $packet.Length)
	$Connection.Stream.Flush()

	$previousReceiveTimeout = $Connection.Client.ReceiveTimeout
	$Connection.Client.ReceiveTimeout = $ReceiveTimeoutMs
	$responseParts = New-Object System.Collections.Generic.List[string]
	$matchedPacketReceived = $false
	$receiveStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	try {
		while ($true) {
			$waitTimeoutMs = if ($matchedPacketReceived) {
				120
			} else {
				[Math]::Max(1, $ReceiveTimeoutMs - [int]$receiveStopwatch.ElapsedMilliseconds)
			}
			if (-not $matchedPacketReceived -and $waitTimeoutMs -le 0) {
				if ($AllowReadTimeout) {
					if (-not $Silent) {
						Write-Host "[Bench/RCON] $Command"
						Write-Host "[Bench/RESP] <read timeout tolerated>"
					}
					return ""
				}
				throw "Timed out waiting for matching RCON response."
			}
			$packetResponse = Read-RconPacketIfAvailable -Client $Connection.Client -Stream $Connection.Stream -WaitTimeoutMs $waitTimeoutMs
			if ($null -eq $packetResponse) {
				if ($matchedPacketReceived) {
					break
				}
				continue
			}
			if ($packetResponse.RequestId -ne $requestId) {
				continue
			}
			$matchedPacketReceived = $true
			if ($null -ne $packetResponse.Body) {
				$responseParts.Add($packetResponse.Body)
			}
		}
	} finally {
		$Connection.Client.ReceiveTimeout = $previousReceiveTimeout
	}

	$responseText = ($responseParts -join "`n").Trim()
	if (-not $Silent) {
		Write-Host "[Bench/RCON] $Command"
		if ($responseText) {
			Write-Host "[Bench/RESP] $responseText"
		}
	}
	return $responseText
}

function Invoke-ReloadAndReconnect {
	param(
		$Connection,
		[string]$ServerHost,
		[int]$Port,
		[string]$Password
	)
	Invoke-RconCommand -Connection $Connection -Command "reload" -ReceiveTimeoutMs 8000 -AllowReadTimeout | Out-Null
	Start-Sleep -Milliseconds 1200
	Close-RconConnection -Connection $Connection
	return (Open-RconConnection -ServerHost $ServerHost -Port $Port -Password $Password)
}

function Get-ServerGameTime {
	param(
		$Connection,
		[int]$MaxAttempts = 4,
		[int]$RetryDelayMs = 40
	)
	if ($DryRun) {
		return [long]$script:DryRunGameTime
	}
	$normalizedAttempts = [Math]::Max(1, [int]$MaxAttempts)
	$normalizedDelayMs = [Math]::Max(10, [int]$RetryDelayMs)
	$lastResponse = $null
	for ($attempt = 1; $attempt -le $normalizedAttempts; $attempt++) {
		$lastResponse = Invoke-RconCommand -Connection $Connection -Command "time query gametime" -Silent
		$matches = [System.Text.RegularExpressions.Regex]::Matches([string]$lastResponse, "-?\d+")
		if ($matches.Count -gt 0) {
			return [long]$matches[$matches.Count - 1].Value
		}
		if ($attempt -lt $normalizedAttempts) {
			Start-Sleep -Milliseconds $normalizedDelayMs
		}
	}
	throw "Failed to parse game time from response: $lastResponse"
}

function Wait-ServerTicks {
	param(
		$Connection,
		[int]$Ticks,
		[int]$PollIntervalMs = 25,
		[int]$MaxWaitMs = 60000
	)
	$normalizedTicks = [Math]::Max(0, [int]$Ticks)
	$normalizedPollIntervalMs = [Math]::Max(10, [int]$PollIntervalMs)
	if ($DryRun) {
		$startTick = [long]$script:DryRunGameTime
		$script:DryRunGameTime += $normalizedTicks
		return [ordered]@{
			requestedTicks = $normalizedTicks
			startTick = $startTick
			targetTick = $startTick + $normalizedTicks
			endTick = [long]$script:DryRunGameTime
			polls = 0
			dryRun = $true
		}
	}

	$startTick = Get-ServerGameTime -Connection $Connection
	$targetTick = $startTick + $normalizedTicks
	$endTick = $startTick
	$polls = 0
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($endTick -lt $targetTick) {
		if ($stopwatch.ElapsedMilliseconds -gt $MaxWaitMs) {
			throw "Timed out waiting for server ticks. start=$startTick target=$targetTick current=$endTick"
		}
		Start-Sleep -Milliseconds $normalizedPollIntervalMs
		$endTick = Get-ServerGameTime -Connection $Connection
		$polls++
	}
	return [ordered]@{
		requestedTicks = $normalizedTicks
		startTick = $startTick
		targetTick = $targetTick
		endTick = $endTick
		polls = $polls
		dryRun = $false
	}
}

function Invoke-RconCommandWithTickWindow {
	param(
		$Connection,
		[string]$Command,
		[switch]$Silent,
		[int]$ReceiveTimeoutMs = 3000,
		[switch]$AllowReadTimeout
	)
	$startTick = Get-ServerGameTime -Connection $Connection
	$response = Invoke-RconCommand `
		-Connection $Connection `
		-Command $Command `
		-Silent:$Silent `
		-ReceiveTimeoutMs $ReceiveTimeoutMs `
		-AllowReadTimeout:$AllowReadTimeout
	$endTick = Get-ServerGameTime -Connection $Connection
	return [ordered]@{
		command = $Command
		response = $response
		tickWindow = [ordered]@{
			startTick = [long]$startTick
			endTick = [long]$endTick
		}
	}
}
