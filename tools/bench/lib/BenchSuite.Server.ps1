<#
.SYNOPSIS
bench suite 模块：dedicated server 与 RCON 生命周期控制。
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

function Open-RconConnection {
	param(
		[string]$ServerHost,
		[int]$Port,
		[System.Security.SecureString]$Password
	)
	$plainTextPassword = Convert-SecureStringToPlainText -Password $Password
	if ([string]::IsNullOrWhiteSpace($plainTextPassword)) {
		throw "RconPassword is required."
	}

	$client = New-Object System.Net.Sockets.TcpClient
	$client.ReceiveTimeout = 3000
	$client.SendTimeout = 3000
	$client.Connect($ServerHost, $Port)
	$stream = $client.GetStream()

	$authPacket = New-RconPacketBytes -RequestId 1 -PacketType 3 -Body $plainTextPassword
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
		[System.Security.SecureString]$Password
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
		[System.Security.SecureString]$Password,
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
		[System.Security.SecureString]$Password
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
