<#
.SYNOPSIS
bench 模块：RCON 连接、命令收发与 tick 观测。
#>

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
		"(?i)\binvalid\b",
		"(?i)\bunallocated\b",
		"(?i)\bretired\b",
		"(?i)\boffline\b",
		"(?i)\bnot found\b",
		"(?i)\bunsupported input endpoint\b"
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
