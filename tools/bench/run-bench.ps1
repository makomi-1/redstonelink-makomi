<#
.SYNOPSIS
RedstoneLink bench 自动化脚本。
.DESCRIPTION
支持：
1. 读取场景矩阵
2. 安装 bench datapack
3. 通过 RCON 自动放置节点、读取 Serial、批量建链
4. 执行最小驱动步骤并调用 spark 命令采集
#>
param(
	[ValidateSet("List", "PrintCase", "InstallDatapack", "RunCase", "RunFunctionalCase")]
	[string]$Action = "List",
	[string]$CaseId,
	[string]$MatrixPath = (Join-Path $PSScriptRoot "matrix.json"),
	[string]$SavePath = (Join-Path $PSScriptRoot "..\..\run\saves\rl-bench"),
	[string]$RconHost = "127.0.0.1",
	[int]$RconPort = 25575,
	[string]$RconPassword,
	[string]$AsPlayer,
	[string]$SparkActivityPath,
	[int]$SparkActivityTimeoutMs = 30000,
	[int]$SparkActivityPollIntervalMs = 250,
	[switch]$SkipSpark,
	[switch]$DryRun
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$datapackSource = Join-Path $PSScriptRoot "datapack\rl_bench"
$script:DryRunSerialCounter = 1L
$script:DryRunGameTime = 0L
$script:BenchAsPlayer = $AsPlayer

function Get-OptionalProperty {
	param(
		$Object,
		[string]$Name,
		$DefaultValue = $null
	)
	if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) {
		return $DefaultValue
	}
	if ($Object -is [System.Collections.IDictionary]) {
		if ($Object.Contains($Name)) {
			return $Object[$Name]
		}
		return $DefaultValue
	}
	$property = $Object.PSObject.Properties[$Name]
	if ($null -eq $property) {
		return $DefaultValue
	}
	return $property.Value
}

function Get-MatrixConfig {
	param([string]$Path)
	if (-not (Test-Path $Path)) {
		throw "Matrix file not found: $Path"
	}
	return (Get-Content -Path $Path -Encoding UTF8 -Raw | ConvertFrom-Json)
}

function Get-CaseConfig {
	param(
		$Matrix,
		[string]$Id
	)
	if ([string]::IsNullOrWhiteSpace($Id)) {
		throw "CaseId is required for action $Action."
	}
	foreach ($case in $Matrix.cases) {
		if ($case.id -eq $Id) {
			return $case
		}
	}
	throw "Case not found: $Id"
}

function New-Vec3 {
	param(
		[int]$X,
		[int]$Y,
		[int]$Z
	)
	return [pscustomobject]@{
		X = $X
		Y = $Y
		Z = $Z
	}
}

function ConvertTo-Vec3 {
	param($ArrayValue)
	return (New-Vec3 -X ([int]$ArrayValue[0]) -Y ([int]$ArrayValue[1]) -Z ([int]$ArrayValue[2]))
}

function Format-Vec3 {
	param($Vec)
	return "$($Vec.X) $($Vec.Y) $($Vec.Z)"
}

function Get-BlockIdByKind {
	param([string]$Kind)
	switch ($Kind) {
		"sync_emitter" { return "redstonelink:link_sync_emitter" }
		"toggle_emitter" { return "redstonelink:link_toggle_emitter" }
		"pulse_emitter" { return "redstonelink:link_pulse_emitter" }
		"core_block" { return "redstonelink:link_redstone_core" }
		"core_dust" { return "redstonelink:link_redstone_dust_core" }
		default { throw "Unsupported node kind: $Kind" }
	}
}

function Expand-CuboidPositions {
	param($Layout)
	if ($Layout.shape -ne "cuboid") {
		throw "Unsupported layout shape: $($Layout.shape)"
	}
	$from = ConvertTo-Vec3 $Layout.from
	$to = ConvertTo-Vec3 $Layout.to
	$minX = [Math]::Min($from.X, $to.X)
	$maxX = [Math]::Max($from.X, $to.X)
	$minY = [Math]::Min($from.Y, $to.Y)
	$maxY = [Math]::Max($from.Y, $to.Y)
	$minZ = [Math]::Min($from.Z, $to.Z)
	$maxZ = [Math]::Max($from.Z, $to.Z)
	$positions = New-Object System.Collections.Generic.List[object]
	for ($y = $minY; $y -le $maxY; $y++) {
		for ($z = $minZ; $z -le $maxZ; $z++) {
			for ($x = $minX; $x -le $maxX; $x++) {
				$positions.Add((New-Vec3 -X $x -Y $y -Z $z))
			}
		}
	}
	return @($positions.ToArray())
}

function Get-BoundsFromPositions {
	param($Positions)
	$xs = $Positions | ForEach-Object { $_.X }
	$ys = $Positions | ForEach-Object { $_.Y }
	$zs = $Positions | ForEach-Object { $_.Z }
	return [pscustomobject]@{
		From = New-Vec3 -X (($xs | Measure-Object -Minimum).Minimum) -Y (($ys | Measure-Object -Minimum).Minimum) -Z (($zs | Measure-Object -Minimum).Minimum)
		To = New-Vec3 -X (($xs | Measure-Object -Maximum).Maximum) -Y (($ys | Measure-Object -Maximum).Maximum) -Z (($zs | Measure-Object -Maximum).Maximum)
	}
}

function Get-ControlPositions {
	param(
		$Positions,
		[string]$Mode
	)
	$sourcePositions = @($Positions)
	if ($sourcePositions.Count -eq 0) {
		return @()
	}
	switch ($Mode) {
		"north_strip" {
			$bounds = Get-BoundsFromPositions $sourcePositions
			$controlPositions = New-Object System.Collections.Generic.List[object]
			for ($y = $bounds.From.Y; $y -le $bounds.To.Y; $y++) {
				for ($x = $bounds.From.X; $x -le $bounds.To.X; $x++) {
					$controlPositions.Add((New-Vec3 -X $x -Y $y -Z ($bounds.From.Z - 1)))
				}
			}
			return @($controlPositions.ToArray())
		}
		default {
			throw "Unsupported control mode: $Mode"
		}
	}
}

function Install-BenchDatapack {
	param(
		[string]$SourcePath,
		[string]$WorldPath
	)
	$resolvedSourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
	$resolvedWorldPath = [System.IO.Path]::GetFullPath($WorldPath)
	if (-not (Test-Path -LiteralPath $resolvedSourcePath)) {
		throw "Datapack source not found: $SourcePath"
	}
	$datapacksDir = Join-Path $resolvedWorldPath "datapacks"
	$targetPath = Join-Path $datapacksDir "rl_bench"
	if (-not (Test-Path -LiteralPath $datapacksDir -PathType Container)) {
		New-Item -Path $datapacksDir -ItemType Directory -Force | Out-Null
	}
	if (Test-Path -LiteralPath $targetPath) {
		Remove-Item -LiteralPath $targetPath -Recurse -Force -ErrorAction SilentlyContinue
	}
	Copy-Item -LiteralPath $resolvedSourcePath -Destination $targetPath -Recurse -Force
	Write-Host "[Bench] Datapack installed -> $targetPath"
}

function Resolve-SparkActivityPath {
	param(
		[string]$WorldPath,
		[string]$ExplicitPath
	)
	if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
		return [System.IO.Path]::GetFullPath($ExplicitPath)
	}

	$resolvedWorldPath = [System.IO.Path]::GetFullPath($WorldPath)
	$worldParent = Split-Path -Path $resolvedWorldPath -Parent
	$serverRoot = $worldParent
	$worldContainerLeaf = Split-Path -Path $worldParent -Leaf
	if ($worldContainerLeaf -ieq "saves" -or $worldContainerLeaf -ieq "rl-cases") {
		$serverRoot = Split-Path -Path $worldParent -Parent
	}

	$candidates = New-Object System.Collections.Generic.List[string]
	$candidates.Add((Join-Path $serverRoot "spark\activity.json"))
	$candidates.Add((Join-Path $serverRoot "config\spark\activity.json"))
	$candidates.Add((Join-Path $serverRoot "plugins\spark\activity.json"))
	foreach ($candidate in $candidates) {
		if (Test-Path -LiteralPath $candidate -PathType Leaf) {
			return [System.IO.Path]::GetFullPath($candidate)
		}
	}
	return [System.IO.Path]::GetFullPath($candidates[0])
}

function Read-SparkActivityEntries {
	param(
		[string]$ActivityPath,
		[switch]$AllowMissing
	)
	if (-not (Test-Path -LiteralPath $ActivityPath -PathType Leaf)) {
		if ($AllowMissing) {
			return @()
		}
		throw "Spark activity file not found: $ActivityPath"
	}

	$rawText = Get-Content -Path $ActivityPath -Encoding UTF8 -Raw
	if ([string]::IsNullOrWhiteSpace($rawText)) {
		return @()
	}
	$parsed = ConvertFrom-Json -InputObject $rawText
	if ($null -eq $parsed) {
		return @()
	}
	return @($parsed)
}

function Get-SparkActivityEntrySignature {
	param($Entry)
	return ($Entry | ConvertTo-Json -Compress -Depth 12)
}

function New-SparkActivitySignatureSet {
	param($Entries)
	$set = @{}
	foreach ($entry in @($Entries)) {
		$set[(Get-SparkActivityEntrySignature -Entry $entry)] = $true
	}
	return $set
}

function Get-SparkActivitySnapshot {
	param([string]$ActivityPath)
	$entries = @(Read-SparkActivityEntries -ActivityPath $ActivityPath -AllowMissing)
	return [pscustomobject]@{
		Path = $ActivityPath
		Signatures = (New-SparkActivitySignatureSet -Entries $entries)
		Count = $entries.Count
	}
}

function Test-SparkActivityEntryKind {
	param(
		$Entry,
		[string]$Kind
	)
	$typeValue = ""
	if ($Entry.PSObject.Properties.Name -contains "type") {
		$typeValue = [string]$Entry.type
	}
	$entryJson = Get-SparkActivityEntrySignature -Entry $Entry
	switch ($Kind) {
		"profiler" {
			if (-not [string]::IsNullOrWhiteSpace($typeValue)) {
				return $typeValue -match "(?i)^profiler$"
			}
			return $entryJson -match "(?i)profiler"
		}
		"health" {
			if (-not [string]::IsNullOrWhiteSpace($typeValue)) {
				return $typeValue -match "(?i)health"
			}
			return $entryJson -match "(?i)health"
		}
		default { return $true }
	}
}

function Convert-SparkActivityEntryToResult {
	param(
		$Entry,
		[string]$Kind,
		[string]$ActivityPath,
		[string]$FallbackResponse
	)
	$entryJson = Get-SparkActivityEntrySignature -Entry $Entry
	$urlValue = $null
	if (
		$Entry.PSObject.Properties.Name -contains "data" -and
		$null -ne $Entry.data -and
		$Entry.data.PSObject.Properties.Name -contains "value"
	) {
		$urlValue = [string]$Entry.data.value
	}
	if ([string]::IsNullOrWhiteSpace($urlValue)) {
		$urlMatch = [System.Text.RegularExpressions.Regex]::Match($entryJson, 'https?://[^\s"\\]+')
		if ($urlMatch.Success) {
			$urlValue = $urlMatch.Value
		}
	}
	$timeValue = $null
	foreach ($propertyName in @("time", "timestamp", "createdAt", "date")) {
		if ($Entry.PSObject.Properties.Name -contains $propertyName) {
			$timeValue = [string]$Entry.$propertyName
			break
		}
	}

	return [ordered]@{
		matched = $true
		kind = $Kind
		path = $ActivityPath
		time = $timeValue
		url = $urlValue
		fallbackResponse = $FallbackResponse
		entry = $Entry
	}
}

function Wait-SparkActivityResult {
	param(
		[string]$ActivityPath,
		[hashtable]$BaselineSignatures,
		[string]$Kind,
		[int]$TimeoutMs,
		[int]$PollIntervalMs,
		[string]$FallbackResponse
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	$lastReadError = $null
	while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMs) {
		try {
			$entries = @(Read-SparkActivityEntries -ActivityPath $ActivityPath -AllowMissing)
			foreach ($entry in $entries) {
				$signature = Get-SparkActivityEntrySignature -Entry $entry
				if ($BaselineSignatures.ContainsKey($signature)) {
					continue
				}
				if (Test-SparkActivityEntryKind -Entry $entry -Kind $Kind) {
					return (Convert-SparkActivityEntryToResult -Entry $entry -Kind $Kind -ActivityPath $ActivityPath -FallbackResponse $FallbackResponse)
				}
			}
			$lastReadError = $null
		} catch {
			# spark 可能正处于写文件过程中，短暂 JSON 不完整时继续重试即可。
			$lastReadError = $_.Exception.Message
		}
		Start-Sleep -Milliseconds $PollIntervalMs
	}

	return [ordered]@{
		matched = $false
		kind = $Kind
		path = $ActivityPath
		timeoutMs = $TimeoutMs
		pollIntervalMs = $PollIntervalMs
		fallbackResponse = $FallbackResponse
		lastReadError = $lastReadError
	}
}

function Get-SparkActivityPrimaryValue {
	param($ActivityResult)
	if ($null -eq $ActivityResult) {
		return ""
	}
	if ($ActivityResult.matched -and -not [string]::IsNullOrWhiteSpace([string]$ActivityResult.url)) {
		return [string]$ActivityResult.url
	}
	if (-not [string]::IsNullOrWhiteSpace([string]$ActivityResult.fallbackResponse)) {
		return [string]$ActivityResult.fallbackResponse
	}
	return ""
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

function Convert-PositionsToSerialMap {
	param(
		$Connection,
		$Positions
	)
	$result = @{}
	foreach ($pos in $Positions) {
		if ($DryRun) {
			$key = (Format-Vec3 $pos)
			$result[$key] = $script:DryRunSerialCounter
			$script:DryRunSerialCounter++
			continue
		}
		$response = Invoke-RconCommand `
			-Connection $Connection `
			-Command (Wrap-WithPlayerContext ("data get block {0} Serial" -f (Format-Vec3 $pos))) `
			-Silent
		$matches = [System.Text.RegularExpressions.Regex]::Matches($response, "-?\d+")
		if ($matches.Count -eq 0) {
			throw "Failed to parse Serial from response: $response"
		}
		$key = (Format-Vec3 $pos)
		$result[$key] = [long]$matches[$matches.Count - 1].Value
	}
	return $result
}

function Get-SerialListFromMap {
	param([hashtable]$Map)
	return @($Map.GetEnumerator() | Sort-Object Name | ForEach-Object { [long]$_.Value })
}

function Get-SortedUniqueSerials {
	param([long[]]$Serials)
	if ($null -eq $Serials) {
		return @()
	}
	return @(
		$Serials |
			Where-Object { $null -ne $_ -and [long]$_ -gt 0L } |
			ForEach-Object { [long]$_ } |
			Sort-Object -Unique
	)
}

function Format-RangeSerialSegments {
	param([long[]]$Serials)
	$normalized = @(Get-SortedUniqueSerials $Serials)
	if ($normalized.Count -eq 0) {
		return @()
	}
	$segments = New-Object System.Collections.Generic.List[string]
	$runStart = $normalized[0]
	$runEnd = $normalized[0]
	for ($index = 1; $index -lt $normalized.Count; $index++) {
		$current = [long]$normalized[$index]
		if ($current -eq ($runEnd + 1L)) {
			$runEnd = $current
			continue
		}
		if ($runStart -eq $runEnd) {
			$segments.Add([string]$runStart)
		} else {
			$segments.Add(("{0}:{1}" -f $runStart, $runEnd))
		}
		$runStart = $current
		$runEnd = $current
	}
	if ($runStart -eq $runEnd) {
		$segments.Add([string]$runStart)
	} else {
		$segments.Add(("{0}:{1}" -f $runStart, $runEnd))
	}
	return @($segments.ToArray())
}

function Format-MixedSerialSegments {
	param([long[]]$Serials)
	$normalized = @(Get-SortedUniqueSerials $Serials)
	if ($normalized.Count -eq 0) {
		return @()
	}
	$segments = New-Object System.Collections.Generic.List[string]
	$index = 0
	while ($index -lt $normalized.Count) {
		$current = [long]$normalized[$index]
		if ($index + 1 -lt $normalized.Count) {
			$next = [long]$normalized[$index + 1]
			if ($next -eq ($current + 1L)) {
				$segments.Add(("{0}:{1}" -f $current, $next))
				$index += 2
				continue
			}
		}
		$segments.Add([string]$current)
		$index++
	}
	return @($segments.ToArray())
}

function Format-SerialInputText {
	param(
		[long[]]$Serials,
		[string]$Style = "slash_list"
	)
	$normalized = @(Get-SortedUniqueSerials $Serials)
	switch ([string]$Style) {
		"slash_list" {
			return ($normalized -join "/")
		}
		"range" {
			return ((Format-RangeSerialSegments $normalized) -join "/")
		}
		"mixed" {
			return ((Format-MixedSerialSegments $normalized) -join "/")
		}
		"duplicate_mixed" {
			if ($normalized.Count -eq 0) {
				return ""
			}
			$base = (Format-MixedSerialSegments $normalized) -join "/"
			return "$base/$($normalized[0])"
		}
		default {
			throw "Unsupported serial format style: $Style"
		}
	}
}

function Select-SerialsByIndex {
	param(
		[long[]]$Serials,
		$IndexValues
	)
	$normalizedSerials = @(Get-SortedUniqueSerials $Serials)
	if ($null -eq $IndexValues) {
		return $normalizedSerials
	}
	$rawIndexes = @($IndexValues)
	if ($rawIndexes.Count -eq 0) {
		return $normalizedSerials
	}
	$selected = New-Object System.Collections.Generic.List[long]
	foreach ($rawIndex in $rawIndexes) {
		$index = [int]$rawIndex
		if ($index -lt 0 -or $index -ge $normalizedSerials.Count) {
			throw "Serial index out of range: $index (count=$($normalizedSerials.Count))"
		}
		$selected.Add([long]$normalizedSerials[$index])
	}
	return @(Get-SortedUniqueSerials $selected.ToArray())
}

function Get-CaseExecutionBounds {
	param($CaseConfig)
	$arena = Get-OptionalProperty -Object $CaseConfig -Name "arena"
	if ($null -ne $arena) {
		return [pscustomobject]@{
			From = ConvertTo-Vec3 $arena.clearFrom
			To = ConvertTo-Vec3 $arena.clearTo
		}
	}

	$allPositions = New-Object System.Collections.Generic.List[object]
	if ($null -ne $CaseConfig.targets) {
		foreach ($pos in @(Expand-CuboidPositions $CaseConfig.targets.layout)) {
			$allPositions.Add($pos)
		}
	}
	foreach ($group in @($CaseConfig.sources)) {
		foreach ($pos in @(Expand-CuboidPositions $group.layout)) {
			$allPositions.Add($pos)
		}
	}
	if ($allPositions.Count -le 0) {
		return $null
	}
	return Get-BoundsFromPositions -Positions @($allPositions.ToArray())
}

function Ensure-CaseChunksLoaded {
	param(
		$Connection,
		$CaseConfig
	)
	if ($DryRun) {
		return
	}
	$bounds = Get-CaseExecutionBounds -CaseConfig $CaseConfig
	if ($null -eq $bounds) {
		return
	}
	$minX = [Math]::Min([int]$bounds.From.X, [int]$bounds.To.X)
	$maxX = [Math]::Max([int]$bounds.From.X, [int]$bounds.To.X)
	$minZ = [Math]::Min([int]$bounds.From.Z, [int]$bounds.To.Z)
	$maxZ = [Math]::Max([int]$bounds.From.Z, [int]$bounds.To.Z)
	$command = Wrap-WithPlayerContext ("forceload add {0} {1} {2} {3}" -f $minX, $minZ, $maxX, $maxZ)
	Invoke-RconCommand -Connection $Connection -Command $command | Out-Null
}

function Clear-Arena {
	param(
		$Connection,
		$Arena
	)
	if ($null -eq $Arena) {
		return
	}
	$from = ConvertTo-Vec3 $Arena.clearFrom
	$to = ConvertTo-Vec3 $Arena.clearTo
	Invoke-RconCommand `
		-Connection $Connection `
		-Command (Wrap-WithPlayerContext ("fill {0} {1} minecraft:air replace" -f (Format-Vec3 $from), (Format-Vec3 $to))) | Out-Null
}

function Place-NodeGroup {
	param(
		$Connection,
		$Group
	)
	$positions = @(Expand-CuboidPositions $Group.layout)
	$reuseExisting = [bool](Get-OptionalProperty -Object $Group -Name "reuseExisting" -DefaultValue $false)
	if ($reuseExisting) {
		return $positions
	}
	$bounds = Get-BoundsFromPositions $positions
	$blockId = Get-BlockIdByKind $Group.kind
	if ($positions.Count -gt 1) {
		Invoke-RconCommand `
			-Connection $Connection `
			-Command (Wrap-WithPlayerContext ("redstonelink place fill {0} {1} {2} force" -f (Format-Vec3 $bounds.From), (Format-Vec3 $bounds.To), $blockId)) | Out-Null
	} else {
		Invoke-RconCommand `
			-Connection $Connection `
			-Command (Wrap-WithPlayerContext ("redstonelink place setblock {0} {1} force" -f (Format-Vec3 $positions[0]), $blockId)) | Out-Null
	}
	return $positions
}

function Build-LinkCommands {
	param(
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[long[]]$TargetSerials
	)
	$linkCommands = New-Object System.Collections.Generic.List[string]
	$linkRules = @(Get-OptionalProperty -Object $CaseConfig -Name "links" -DefaultValue @())
	foreach ($rule in $linkRules) {
		$groupName = [string]$rule.sourceGroup
		$sourceSerials = @(Get-SerialListFromMap $SourceSerialMaps[$groupName])
		if ($sourceSerials.Count -eq 0) {
			continue
		}
		for ($index = 0; $index -lt $sourceSerials.Count; $index++) {
			$sourceSerial = $sourceSerials[$index]
			$mappedTargets = @(switch ([string]$rule.mapping) {
				"broadcast_all" { $TargetSerials }
				"fan_in_first" { @($TargetSerials[0]) }
				"round_robin" { @($TargetSerials[$index % $TargetSerials.Count]) }
				"zip" {
					if ($index -lt $TargetSerials.Count) { @($TargetSerials[$index]) } else { @() }
				}
				default { throw "Unsupported mapping mode: $($rule.mapping)" }
			})
			if ($mappedTargets.Count -eq 0) {
				continue
			}
			$command = "redstonelink link set triggerSource $sourceSerial $(Format-SerialInputText $mappedTargets)"
			if ($mappedTargets.Count -gt 1) {
				$command += " confirm"
			}
			$linkCommands.Add((Wrap-WithPlayerContext $command))
		}
	}
	return $linkCommands
}

function Invoke-BenchSetupCommand {
	param(
		$Connection,
		[string]$Command,
		[string]$ExpectedPrefix = "",
		[string]$ExpectedRegex = ""
	)
	$response = Invoke-RconCommand -Connection $Connection -Command $Command -Silent
	Assert-BenchCommandResponse `
		-Command $Command `
		-ResponseText $response `
		-ExpectedPrefix $ExpectedPrefix `
		-ExpectedRegex $ExpectedRegex
	return [ordered]@{
		command = $Command
		response = $response
	}
}

function Invoke-PrepareFunctions {
	param(
		$Connection,
		$Matrix
	)
	foreach ($fn in $Matrix.defaults.prepareFunctions) {
		Invoke-RconCommand -Connection $Connection -Command (Wrap-WithPlayerContext ("function {0}" -f $fn)) | Out-Null
	}
}

function Start-SparkCapture {
	param(
		$Connection,
		$SparkDefaults,
		[string]$CaseName,
		[string]$WorldPath
	)
	$result = [ordered]@{}
	if ($SkipSpark) {
		return $result
	}
	$activityPath = Resolve-SparkActivityPath -WorldPath $WorldPath -ExplicitPath $SparkActivityPath
	$result.startCpu = Invoke-RconCommand -Connection $Connection -Command $SparkDefaults.startCpu
	$result.activityPath = $activityPath
	$result.activityPathExistsAtStart = (Test-Path -LiteralPath $activityPath -PathType Leaf)
	return $result
}

function Stop-SparkCapture {
	param(
		$Connection,
		$SparkDefaults,
		[string]$CaseName,
		[string]$ActivityPath
	)
	$result = [ordered]@{}
	if ($SkipSpark) {
		return $result
	}
	if ($DryRun) {
		$result.stopCpu = ""
		$result.stopCpuActivity = [ordered]@{
			matched = $false
			kind = "profiler"
			path = $ActivityPath
			dryRun = $true
		}
		$result.health = ""
		$result.healthActivity = [ordered]@{
			matched = $false
			kind = "health"
			path = $ActivityPath
			dryRun = $true
		}
		return $result
	}

	$stopCommand = ([string]$SparkDefaults.stopCpu).Replace("{case_id}", $CaseName)
	$profilerSnapshot = Get-SparkActivitySnapshot -ActivityPath $ActivityPath
	$stopResponse = Invoke-RconCommand -Connection $Connection -Command $stopCommand
	$stopActivity = Wait-SparkActivityResult `
		-ActivityPath $ActivityPath `
		-BaselineSignatures $profilerSnapshot.Signatures `
		-Kind "profiler" `
		-TimeoutMs $SparkActivityTimeoutMs `
		-PollIntervalMs $SparkActivityPollIntervalMs `
		-FallbackResponse $stopResponse
	$result.stopCpu = Get-SparkActivityPrimaryValue -ActivityResult $stopActivity
	$result.stopCpuActivity = $stopActivity

	$healthSnapshot = Get-SparkActivitySnapshot -ActivityPath $ActivityPath
	$healthResponse = Invoke-RconCommand -Connection $Connection -Command ([string]$SparkDefaults.health)
	$healthActivity = Wait-SparkActivityResult `
		-ActivityPath $ActivityPath `
		-BaselineSignatures $healthSnapshot.Signatures `
		-Kind "health" `
		-TimeoutMs $SparkActivityTimeoutMs `
		-PollIntervalMs $SparkActivityPollIntervalMs `
		-FallbackResponse $healthResponse
	$result.health = Get-SparkActivityPrimaryValue -ActivityResult $healthActivity
	$result.healthActivity = $healthActivity
	return $result
}

function Invoke-DriveSchedule {
	param(
		$Connection,
		$CaseConfig,
		[hashtable]$SourcePositionGroups,
		[hashtable]$SourceSerialMaps,
		[int]$TickMillis
	)
	$totalTicks = [int]$CaseConfig.drive.totalTicks
	$stepStates = @{}
	for ($tick = 0; $tick -lt $totalTicks; $tick++) {
		foreach ($step in $CaseConfig.drive.steps) {
			$stepKey = "{0}:{1}" -f $step.kind, $step.sourceGroup
			switch ([string]$step.kind) {
				"activate_batch" {
					$everyTicks = [int]$step.everyTicks
					if ($everyTicks -gt 0 -and ($tick % $everyTicks) -eq 0) {
						$serials = @(Get-SerialListFromMap $SourceSerialMaps[[string]$step.sourceGroup])
						if ($serials.Count -gt 0) {
							$mode = [string]$step.mode
							$command = Wrap-WithPlayerContext "redstonelink node activate triggerSource $(Format-SerialInputText $serials) $mode"
							Invoke-RconCommand -Connection $Connection -Command $command | Out-Null
						}
					}
				}
				"sync_square_wave" {
					$periodTicks = [Math]::Max(2, [int]$step.periodTicks)
					$halfPeriod = [Math]::Max(1, [int]($periodTicks / 2))
					$shouldOn = (($tick % $periodTicks) -lt $halfPeriod)
					$previous = if ($stepStates.ContainsKey($stepKey)) { [bool]$stepStates[$stepKey] } else { $false }
					if ($tick -eq 0 -or $shouldOn -ne $previous) {
						$groupConfig = $CaseConfig.sources | Where-Object { $_.id -eq $step.sourceGroup } | Select-Object -First 1
						$control = $groupConfig.control
						$controlMode = if ([string]::IsNullOrWhiteSpace([string]$control.mode)) { "north_strip" } else { [string]$control.mode }
						$controlPositions = @(Get-ControlPositions -Positions $SourcePositionGroups[[string]$step.sourceGroup] -Mode $controlMode)
						$controlBounds = Get-BoundsFromPositions $controlPositions
						if ($shouldOn) {
							Invoke-RconCommand -Connection $Connection -Command (
								Wrap-WithPlayerContext ("fill {0} {1} {2} replace" -f
								(Format-Vec3 $controlBounds.From),
								(Format-Vec3 $controlBounds.To),
								([string]$control.onBlock)
								)
							) | Out-Null
						} else {
							Invoke-RconCommand -Connection $Connection -Command (
								Wrap-WithPlayerContext ("fill {0} {1} {2} replace" -f
								(Format-Vec3 $controlBounds.From),
								(Format-Vec3 $controlBounds.To),
								([string]$control.offBlock)
								)
							) | Out-Null
						}
						$stepStates[$stepKey] = $shouldOn
					}
				}
				default {
					throw "Unsupported drive kind: $($step.kind)"
				}
			}
		}
		if ($TickMillis -gt 0) {
			Start-Sleep -Milliseconds $TickMillis
		}
	}
}

function Resolve-PhaseSerials {
	param(
		$Phase,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$resolvedSerials = @()
	$explicitSerials = Get-OptionalProperty -Object $Phase -Name "serials"
	if ($null -ne $explicitSerials) {
		$resolvedSerials = @(
			Get-SortedUniqueSerials (
				@($explicitSerials | ForEach-Object { [long]$_ })
			)
		)
	} else {
		$serialRef = [string](Get-OptionalProperty -Object $Phase -Name "serialRef" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($serialRef)) {
			$serialRef = [string](Get-OptionalProperty -Object $Phase -Name "sourceGroup" -DefaultValue "")
		}
		if ([string]::IsNullOrWhiteSpace($serialRef)) {
			throw "Phase kind '$($Phase.kind)' requires serialRef/sourceGroup or explicit serials."
		}
		if ($serialRef -eq "targets") {
			$resolvedSerials = @(Get-SerialListFromMap $TargetSerialMap)
		} elseif ($SourceSerialMaps.ContainsKey($serialRef)) {
			$resolvedSerials = @(Get-SerialListFromMap $SourceSerialMaps[$serialRef])
		} else {
			throw "Unknown serialRef/sourceGroup: $serialRef"
		}
	}

	$requestedIndexes = New-Object System.Collections.Generic.List[int]
	$singleIndex = Get-OptionalProperty -Object $Phase -Name "serialIndex"
	if ($null -ne $singleIndex) {
		$requestedIndexes.Add([int]$singleIndex)
	}
	$multipleIndexes = Get-OptionalProperty -Object $Phase -Name "serialIndexes"
	if ($null -ne $multipleIndexes) {
		foreach ($rawIndex in @($multipleIndexes)) {
			$requestedIndexes.Add([int]$rawIndex)
		}
	}
	if ($requestedIndexes.Count -gt 0) {
		return @(Select-SerialsByIndex -Serials $resolvedSerials -IndexValues $requestedIndexes.ToArray())
	}
	return $resolvedSerials
}

function Resolve-InputEndpointCommandPath {
	param([string]$Endpoint)
	switch ([string]$Endpoint) {
		"triggerSource" { return "triggerSource" }
		"core_sync" { return "core sync" }
		default { throw "Unsupported functional input endpoint: $Endpoint" }
	}
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

function Normalize-NodeTraceFieldName {
	param([string]$RawName)
	$normalized = ([string]$RawName).Trim()
	switch ($normalized) {
		"类型" { return "type" }
		"type" { return "type" }
		"序号" { return "serial" }
		"serial" { return "serial" }
		"traceKind" { return "traceKind" }
		"tick" { return "tick" }
		"slot" { return "slot" }
		"online" { return "online" }
		"active" { return "active" }
		"input" { return "input" }
		"output" { return "output" }
		"configuredMode" { return "configuredMode" }
		"effectiveMode" { return "effectiveMode" }
		"resolvedStrength" { return "resolvedStrength" }
		"lastObservedInput" { return "lastObservedInput" }
		"lastDispatched" { return "lastDispatched" }
		"maxSources" { return "maxSources" }
		"dimension" { return "dimension" }
		"pos" { return "pos" }
		default { return $null }
	}
}

function Convert-NodeTraceFieldValue {
	param(
		[string]$FieldName,
		[string]$RawValue
	)
	$trimmed = ([string]$RawValue).Trim().TrimEnd('.')
	switch ($FieldName) {
		"online" {
			if ($trimmed -match '^(?i:true|false)$') {
				return ($trimmed.ToLowerInvariant() -eq "true")
			}
			return $trimmed
		}
		"active" {
			if ($trimmed -match '^(?i:true|false)$') {
				return ($trimmed.ToLowerInvariant() -eq "true")
			}
			return $trimmed
		}
		"serial" { return [long]$trimmed }
		"tick" { return [long]$trimmed }
		"slot" { return [int]$trimmed }
		"input" { return [int]$trimmed }
		"output" { return [int]$trimmed }
		"resolvedStrength" { return [int]$trimmed }
		"lastObservedInput" { return [int]$trimmed }
		"lastDispatched" { return [int]$trimmed }
		default { return $trimmed }
	}
}

function Split-NodeTraceEntries {
	param([string]$ResponseText)
	if ([string]::IsNullOrWhiteSpace($ResponseText)) {
		return @()
	}
	$normalizedText = $ResponseText.Replace([string][char]0xFF0C, ",")
	$normalizedText = $normalizedText.Replace([string][char]0x3002, ".")
	$normalizedText = $normalizedText.Replace([string][char]0xFF1A, ":")
	$normalizedText = $normalizedText.Replace("`r", "")
	$normalizedText = $normalizedText.Replace("`n", "")
	$rawEntries = [System.Text.RegularExpressions.Regex]::Split($normalizedText, "(?=\[RedstoneLink/NodeTrace\])")
	$entries = New-Object System.Collections.Generic.List[string]
	foreach ($rawEntry in $rawEntries) {
		$trimmedEntry = ([string]$rawEntry).Trim()
		if (-not [string]::IsNullOrWhiteSpace($trimmedEntry)) {
			$entries.Add($trimmedEntry)
		}
	}
	return @($entries.ToArray())
}

function Parse-NodeTraceSamples {
	param([string]$ResponseText)
	if ([string]::IsNullOrWhiteSpace($ResponseText)) {
		return @()
	}
	$samples = New-Object System.Collections.Generic.List[object]
	$entries = @(Split-NodeTraceEntries -ResponseText $ResponseText)
	foreach ($entry in $entries) {
		if ([string]::IsNullOrWhiteSpace($entry) -or $entry.IndexOf("[RedstoneLink/NodeTrace]") -lt 0) {
			continue
		}
		$normalizedLine = $entry.Replace([string][char]0xFF0C, ",")
		$normalizedLine = $normalizedLine.Replace([string][char]0x3002, ".")
		$normalizedLine = $normalizedLine.Replace([string][char]0xFF1A, ":")
		$normalizedLine = $normalizedLine.Replace("`r", "")
		$normalizedLine = $normalizedLine.Replace("`n", "")
		if ($normalizedLine -notmatch "traceKind=" -or $normalizedLine -notmatch "tick=") {
			continue
		}
		$content = $normalizedLine -replace "^\[RedstoneLink/NodeTrace\]\s*", ""
		$sample = [ordered]@{}
		$fieldMatches = [System.Text.RegularExpressions.Regex]::Matches(
			$content,
			"(?<key>[^=,]+?)\s*=\s*(?<value>.*?)(?=(?:,\s*[^=,]+\s*=)|$)"
		)
		foreach ($fieldMatch in $fieldMatches) {
			$fieldName = Normalize-NodeTraceFieldName -RawName $fieldMatch.Groups["key"].Value
			if ([string]::IsNullOrWhiteSpace($fieldName)) {
				continue
			}
			$sample[$fieldName] = Convert-NodeTraceFieldValue -FieldName $fieldName -RawValue $fieldMatch.Groups["value"].Value
		}
		if ($sample.Contains("serial") -and $sample.Contains("traceKind") -and $sample.Contains("tick")) {
			$samples.Add([pscustomobject]$sample)
		}
	}
	return @($samples.ToArray())
}

function Convert-ToComparableTraceValue {
	param($Value)
	if ($null -eq $Value) {
		return "<null>"
	}
	if ($Value -is [bool]) {
		return $Value.ToString().ToLowerInvariant()
	}
	if ($Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64]) {
		return ([string]$Value)
	}
	return ([string]$Value).Trim().ToLowerInvariant()
}

function Compare-TraceSampleAgainstExpectation {
	param(
		$Sample,
		$Expected
	)
	$actualSubset = [ordered]@{}
	$mismatches = New-Object System.Collections.Generic.List[object]
	$expectedProperties = if ($Expected -is [System.Collections.IDictionary]) {
		@(
			foreach ($entry in $Expected.GetEnumerator()) {
				[pscustomobject]@{
					Name = [string]$entry.Key
					Value = $entry.Value
				}
			}
		)
	} else {
		@(
			$Expected.PSObject.Properties |
				Where-Object {
					$_.MemberType -eq [System.Management.Automation.PSMemberTypes]::NoteProperty `
						-or $_.MemberType -eq [System.Management.Automation.PSMemberTypes]::Property
				}
		)
	}
	foreach ($property in $expectedProperties) {
		$fieldName = [string]$property.Name
		$expectedValue = $property.Value
		$sampleProperty = $Sample.PSObject.Properties[$fieldName]
		$actualValue = if ($null -eq $sampleProperty) { $null } else { $sampleProperty.Value }
		$actualSubset[$fieldName] = $actualValue
		if ((Convert-ToComparableTraceValue $actualValue) -ne (Convert-ToComparableTraceValue $expectedValue)) {
			$mismatches.Add([ordered]@{
				field = $fieldName
				expected = $expectedValue
				actual = $actualValue
			})
		}
	}
	return [ordered]@{
		actual = [pscustomobject]$actualSubset
		mismatches = @($mismatches.ToArray())
	}
}

function Invoke-NodeTraceRead {
	param(
		$Connection,
		[string]$Type,
		[long]$Serial,
		[int]$Limit
	)
	$normalizedLimit = [Math]::Max(1, [int]$Limit)
	$command = Wrap-WithPlayerContext "redstonelink node trace read $Type $Serial $normalizedLimit"
	$response = Invoke-RconCommand -Connection $Connection -Command $command -Silent
	return [ordered]@{
		type = $Type
		serial = $Serial
		limit = $normalizedLimit
		command = $command
		response = $response
		samples = @(Parse-NodeTraceSamples -ResponseText $response)
	}
}

function Find-TraceCycleMatch {
	param(
		$Samples,
		$ExpectedCycle
	)
	$expectedItems = @($ExpectedCycle)
	$sampleItems = @($Samples)
	if ($expectedItems.Count -le 0) {
		return [ordered]@{
			matched = $true
			rotation = 0
			searchWindowCount = 0
			actualWindow = @()
			failures = @()
		}
	}
	if ($sampleItems.Count -lt $expectedItems.Count) {
		return [ordered]@{
			matched = $false
			rotation = $null
			searchWindowCount = 0
			actualWindow = @($sampleItems)
			failures = @(
				[ordered]@{
					reason = "insufficient_samples"
					expectedCount = $expectedItems.Count
					actualCount = $sampleItems.Count
				}
			)
		}
	}

	$chronologicalSamples = @($sampleItems)
	[array]::Reverse($chronologicalSamples)
	$windowLength = $expectedItems.Count
	$searchWindowCount = 0
	$bestFailure = $null
	for ($startIndex = 0; $startIndex -le ($chronologicalSamples.Count - $windowLength); $startIndex++) {
		$window = @($chronologicalSamples[$startIndex..($startIndex + $windowLength - 1)])
		for ($rotation = 0; $rotation -lt $windowLength; $rotation++) {
			$searchWindowCount++
			$mismatches = New-Object System.Collections.Generic.List[object]
			for ($offset = 0; $offset -lt $windowLength; $offset++) {
				$expectedSample = $expectedItems[($rotation + $offset) % $windowLength]
				$actualSample = $window[$offset]
				$comparison = Compare-TraceSampleAgainstExpectation -Sample $actualSample -Expected $expectedSample
				if ($comparison.mismatches.Count -gt 0) {
					$mismatches.Add([ordered]@{
						sampleOffset = $offset
						expected = $expectedSample
						actual = $comparison.actual
						tick = $actualSample.tick
						mismatches = $comparison.mismatches
					})
				}
			}
			if ($mismatches.Count -eq 0) {
				return [ordered]@{
					matched = $true
					rotation = $rotation
					searchWindowCount = $searchWindowCount
					actualWindow = @($window)
					failures = @()
				}
			}
			if ($null -eq $bestFailure -or $mismatches.Count -lt $bestFailure.failures.Count) {
				$bestFailure = [ordered]@{
					matched = $false
					rotation = $rotation
					searchWindowCount = $searchWindowCount
					actualWindow = @($window)
					failures = @($mismatches.ToArray())
				}
			}
		}
	}
	if ($null -ne $bestFailure) {
		return $bestFailure
	}
	return [ordered]@{
		matched = $false
		rotation = $null
		searchWindowCount = $searchWindowCount
		actualWindow = @()
		failures = @(
			[ordered]@{
				reason = "no_window_checked"
			}
		)
	}
}

function Convert-ToChronologicalTraceSamples {
	param($Samples)
	$items = @($Samples)
	if ($items.Count -le 1) {
		return $items
	}
	$chronological = @($items)
	[array]::Reverse($chronological)
	return @($chronological)
}

function Get-TraceEarliestTick {
	param($Samples)
	$chronologicalSamples = @(Convert-ToChronologicalTraceSamples -Samples $Samples)
	if ($chronologicalSamples.Count -le 0) {
		return $null
	}
	$tickProperty = $chronologicalSamples[0].PSObject.Properties["tick"]
	if ($null -eq $tickProperty) {
		return $null
	}
	return [long]$tickProperty.Value
}

function Get-ExpandedTraceReadLimit {
	param(
		[int]$CurrentLimit,
		[int]$Capacity,
		[long]$StartTickMin,
		$Samples
	)
	if ($Capacity -le $CurrentLimit) {
		return $CurrentLimit
	}
	$earliestTick = Get-TraceEarliestTick -Samples $Samples
	if ($null -eq $earliestTick -or $earliestTick -le $StartTickMin) {
		return $CurrentLimit
	}
	$requiredExtra = [Math]::Max(0, [int]($earliestTick - $StartTickMin))
	$stepExtra = [Math]::Max(4, [Math]::Min(24, $requiredExtra + 2))
	$expandedLimit = $CurrentLimit + $stepExtra
	return [Math]::Min($Capacity, $expandedLimit)
}

function Test-NodeTraceReadRateLimited {
	param([string]$ResponseText)
	return (-not [string]::IsNullOrWhiteSpace($ResponseText)) -and ($ResponseText -match "Too many requests")
}

function Add-FunctionalPhaseResult {
	param(
		$PhaseResults,
		[hashtable]$PhaseContext,
		[string]$PhaseName,
		$PhaseResult
	)
	if ($null -eq $PhaseResult) {
		return
	}
	if (-not [string]::IsNullOrWhiteSpace($PhaseName)) {
		if ($PhaseContext.ContainsKey($PhaseName)) {
			throw "Duplicate functional phase name: $PhaseName"
		}
		$PhaseContext[$PhaseName] = $PhaseResult
	}
	$PhaseResults.Add($PhaseResult)
}

function Resolve-FunctionalPhaseResult {
	param(
		[hashtable]$PhaseContext,
		[string]$PhaseName
	)
	if ([string]::IsNullOrWhiteSpace($PhaseName)) {
		return $null
	}
	if ($null -eq $PhaseContext -or -not $PhaseContext.ContainsKey($PhaseName)) {
		throw "Unknown functional phase ref: $PhaseName"
	}
	return $PhaseContext[$PhaseName]
}

function Resolve-TraceMountTicksBySerial {
	param([string]$ResponseText)
	$mountTicks = @{}
	foreach ($sample in @(Parse-NodeTraceSamples -ResponseText $ResponseText)) {
		$serialProperty = $sample.PSObject.Properties["serial"]
		$tickProperty = $sample.PSObject.Properties["tick"]
		if ($null -eq $serialProperty -or $null -eq $tickProperty) {
			continue
		}
		$mountTicks[[string][long]$serialProperty.Value] = [long]$tickProperty.Value
	}
	return $mountTicks
}

function Get-TraceMountTickForSerial {
	param(
		$MountPhaseResult,
		[long]$Serial
	)
	if ($null -eq $MountPhaseResult) {
		return $null
	}
	$mountTicksBySerial = Get-OptionalProperty -Object $MountPhaseResult -Name "mountTicksBySerial"
	$serialKey = [string][long]$Serial
	if ($mountTicksBySerial -is [System.Collections.IDictionary] -and $mountTicksBySerial.Contains($serialKey)) {
		return [long]$mountTicksBySerial[$serialKey]
	}
	return $null
}

function Parse-SignalSequenceText {
	param([string]$SequenceText)
	$normalized = if ($null -eq $SequenceText) { "" } else { ([string]$SequenceText).Trim() }
	if ([string]::IsNullOrWhiteSpace($normalized)) {
		throw "Signal sequence must not be empty."
	}
	if ($normalized -match '^(?:1[0-5]|[0-9])$') {
		return @([int]$normalized)
	}
	if ($normalized.IndexOfAny(@(',', '/', ':', '|')) -ge 0) {
		$tokens = [System.Text.RegularExpressions.Regex]::Split($normalized, "[,/:|]")
		$sequence = New-Object System.Collections.Generic.List[int]
		foreach ($token in $tokens) {
			$item = if ($null -eq $token) { "" } else { $token.Trim() }
			if ([string]::IsNullOrWhiteSpace($item)) {
				continue
			}
			$value = [int]$item
			if ($value -lt 0 -or $value -gt 15) {
				throw "Signal sequence token out of range: $item"
			}
			$sequence.Add($value)
		}
		if ($sequence.Count -le 0) {
			throw "Signal sequence must not be empty."
		}
		return @($sequence.ToArray())
	}
	$sequence = New-Object System.Collections.Generic.List[int]
	foreach ($ch in $normalized.ToCharArray()) {
		if ([char]::IsWhiteSpace($ch)) {
			continue
		}
		$value = [System.Convert]::ToInt32([string]$ch, 16)
		if ($value -lt 0 -or $value -gt 15) {
			throw "Invalid signal sequence char: $ch"
		}
		$sequence.Add($value)
	}
	if ($sequence.Count -le 0) {
		throw "Signal sequence must not be empty."
	}
	return @($sequence.ToArray())
}

function New-TraceExpectationSample {
	param(
		[string]$Type,
		[string]$Mode,
		[int]$Power
	)
	$normalizedPower = [Math]::Max(0, [Math]::Min(15, [int]$Power))
	$active = ($normalizedPower -gt 0)
	$sample = [ordered]@{
		active = $active
		input = $normalizedPower
		output = $normalizedPower
	}
	if ([string]$Type -eq "core") {
		$sample.resolvedStrength = $normalizedPower
		if ($active -and -not [string]::IsNullOrWhiteSpace($Mode)) {
			$sample.effectiveMode = [string]$Mode
		}
	}
	return [pscustomobject]$sample
}

function Build-SyncSquareTraceExpectations {
	param(
		$TemplatePhaseResult,
		[string]$Type,
		[int]$TickCount
	)
	$waveform = Get-OptionalProperty -Object $TemplatePhaseResult -Name "waveform"
	if ($null -eq $waveform) {
		throw "sync_square template requires waveform metadata."
	}
	$periodTicks = [int](Get-OptionalProperty -Object $waveform -Name "periodTicks" -DefaultValue 0)
	$highTicks = [int](Get-OptionalProperty -Object $waveform -Name "highTicks" -DefaultValue 0)
	$highPower = [int](Get-OptionalProperty -Object $waveform -Name "highPower" -DefaultValue 15)
	$lowPower = [int](Get-OptionalProperty -Object $waveform -Name "lowPower" -DefaultValue 0)
	$phaseTicks = [int](Get-OptionalProperty -Object $waveform -Name "phaseTicks" -DefaultValue 0)
	if ($periodTicks -le 0) {
		throw "sync_square template requires periodTicks > 0."
	}
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { $periodTicks }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$cycleTick = [int](($offset + $phaseTicks) % $periodTicks)
		$power = if ($cycleTick -lt $highTicks) { $highPower } else { $lowPower }
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "sync" -Power $power))
	}
	return @($expectations.ToArray())
}

function Build-SyncCustomTraceExpectations {
	param(
		$TemplatePhaseResult,
		[string]$Type,
		[int]$TickCount
	)
	$sequenceText = [string](Get-OptionalProperty -Object $TemplatePhaseResult -Name "sequence" -DefaultValue "")
	$phaseTicks = [int](Get-OptionalProperty -Object $TemplatePhaseResult -Name "phaseTicks" -DefaultValue 0)
	$sequence = @(Parse-SignalSequenceText -SequenceText $sequenceText)
	if ($sequence.Count -le 0) {
		throw "sync_custom template requires a non-empty sequence."
	}
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { $sequence.Count }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$index = [int](($offset + $phaseTicks) % $sequence.Count)
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "sync" -Power ([int]$sequence[$index])))
	}
	return @($expectations.ToArray())
}

function Build-PulseTraceExpectations {
	param(
		$Template,
		[string]$Type,
		[int]$TickCount
	)
	$activeTicks = [int](Get-OptionalProperty -Object $Template -Name "activeTicks" -DefaultValue 4)
	$activePower = [int](Get-OptionalProperty -Object $Template -Name "activePower" -DefaultValue 15)
	if ($activeTicks -le 0) {
		throw "pulse template requires activeTicks > 0."
	}
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { ($activeTicks + 2) }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$power = if ($offset -lt $activeTicks) { $activePower } else { 0 }
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "pulse" -Power $power))
	}
	return @($expectations.ToArray())
}

function Build-ToggleTraceExpectations {
	param(
		$Template,
		[string]$Type,
		[int]$TickCount
	)
	$expectedActive = Get-OptionalProperty -Object $Template -Name "expectedActive"
	if ($null -eq $expectedActive) {
		throw "toggle_hold template requires expectedActive."
	}
	$activePower = [int](Get-OptionalProperty -Object $Template -Name "activePower" -DefaultValue 15)
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { 3 }
	$power = if ([bool]$expectedActive) { $activePower } else { 0 }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "toggle" -Power $power))
	}
	return @($expectations.ToArray())
}

function Resolve-TraceTickExpectations {
	param(
		$Phase,
		[string]$Type,
		[hashtable]$PhaseContext
	)
	$expectedTicksRaw = Get-OptionalProperty -Object $Phase -Name "expectTicks"
	if ($null -ne $expectedTicksRaw) {
		return @($expectedTicksRaw)
	}
	$template = Get-OptionalProperty -Object $Phase -Name "template"
	if ($null -eq $template) {
		throw "trace_read_tick_assert phase requires expectTicks or template."
	}
	$templateKind = [string](Get-OptionalProperty -Object $template -Name "kind" -DefaultValue "")
	$tickCount = [int](Get-OptionalProperty -Object $Phase -Name "tickCount" -DefaultValue 0)
	$templatePhaseRef = [string](Get-OptionalProperty -Object $template -Name "phaseRef" -DefaultValue "")
	if ([string]::IsNullOrWhiteSpace($templatePhaseRef)) {
		$templatePhaseRef = [string](Get-OptionalProperty -Object $Phase -Name "anchorRef" -DefaultValue "")
	}
	$templatePhaseResult = if ([string]::IsNullOrWhiteSpace($templatePhaseRef)) {
		$null
	} else {
		Resolve-FunctionalPhaseResult -PhaseContext $PhaseContext -PhaseName $templatePhaseRef
	}
	switch ($templateKind) {
		"sync_square" { return @(Build-SyncSquareTraceExpectations -TemplatePhaseResult $templatePhaseResult -Type $Type -TickCount $tickCount) }
		"sync_custom" { return @(Build-SyncCustomTraceExpectations -TemplatePhaseResult $templatePhaseResult -Type $Type -TickCount $tickCount) }
		"pulse" { return @(Build-PulseTraceExpectations -Template $template -Type $Type -TickCount $tickCount) }
		"toggle_hold" { return @(Build-ToggleTraceExpectations -Template $template -Type $Type -TickCount $tickCount) }
		default { throw "Unsupported trace tick template kind: $templateKind" }
	}
}

function Find-TraceTickWindowMatch {
	param(
		$Samples,
		$ExpectedTicks,
		[long]$StartTickMin,
		[long]$SearchTickMax
	)
	$expectedItems = @($ExpectedTicks)
	if ($expectedItems.Count -le 0) {
		return [ordered]@{
			matched = $true
			startTick = $StartTickMin
			alignmentOffset = 0
			candidateCount = 0
			actualWindow = @()
			failures = @()
		}
	}
	if ($SearchTickMax -lt $StartTickMin) {
		return [ordered]@{
			matched = $false
			startTick = $null
			alignmentOffset = $null
			candidateCount = 0
			actualWindow = @()
			failures = @(
				[ordered]@{
					reason = "invalid_anchor_window"
					startTickMin = $StartTickMin
					searchTickMax = $SearchTickMax
				}
			)
		}
	}
	$samplesByTick = @{}
	foreach ($sample in @(Convert-ToChronologicalTraceSamples -Samples $Samples)) {
		$tickProperty = $sample.PSObject.Properties["tick"]
		if ($null -eq $tickProperty) {
			continue
		}
		$tick = [long]$tickProperty.Value
		if (-not $samplesByTick.ContainsKey($tick)) {
			$samplesByTick[$tick] = $sample
		}
	}
	$candidateCount = 0
	$bestFailure = $null
	for ($candidateStartTick = [long]$StartTickMin; $candidateStartTick -le [long]$SearchTickMax; $candidateStartTick++) {
		$candidateCount++
		$actualWindow = New-Object System.Collections.Generic.List[object]
		$failures = New-Object System.Collections.Generic.List[object]
		for ($offset = 0; $offset -lt $expectedItems.Count; $offset++) {
			$expectedTick = $candidateStartTick + $offset
			if (-not $samplesByTick.ContainsKey($expectedTick)) {
				$failures.Add([ordered]@{
					reason = "missing_tick"
					sampleOffset = $offset
					tick = $expectedTick
					expected = $expectedItems[$offset]
				})
				continue
			}
			$actualSample = $samplesByTick[$expectedTick]
			$actualWindow.Add($actualSample)
			$comparison = Compare-TraceSampleAgainstExpectation -Sample $actualSample -Expected $expectedItems[$offset]
			if ($comparison.mismatches.Count -gt 0) {
				$failures.Add([ordered]@{
					reason = "field_mismatch"
					sampleOffset = $offset
					tick = $expectedTick
					expected = $expectedItems[$offset]
					actual = $comparison.actual
					mismatches = $comparison.mismatches
				})
			}
		}
		if ($failures.Count -eq 0) {
			return [ordered]@{
				matched = $true
				startTick = $candidateStartTick
				alignmentOffset = ($candidateStartTick - $StartTickMin)
				candidateCount = $candidateCount
				actualWindow = @($actualWindow.ToArray())
				failures = @()
			}
		}
		if ($null -eq $bestFailure -or $failures.Count -lt $bestFailure.failures.Count) {
			$bestFailure = [ordered]@{
				matched = $false
				startTick = $candidateStartTick
				alignmentOffset = ($candidateStartTick - $StartTickMin)
				candidateCount = $candidateCount
				actualWindow = @($actualWindow.ToArray())
				failures = @($failures.ToArray())
			}
		}
	}
	if ($null -ne $bestFailure) {
		$bestFailure.candidateCount = $candidateCount
		return $bestFailure
	}
	return [ordered]@{
		matched = $false
		startTick = $null
		alignmentOffset = $null
		candidateCount = $candidateCount
		actualWindow = @()
		failures = @(
			[ordered]@{
				reason = "no_candidate_checked"
			}
		)
	}
}

function Invoke-FunctionalPhases {
	param(
		$Connection,
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$phaseResults = New-Object System.Collections.Generic.List[object]
	$checks = New-Object System.Collections.Generic.List[object]
	$failedChecks = New-Object System.Collections.Generic.List[object]
	$phaseContext = @{}
	foreach ($phase in $CaseConfig.phases) {
		$kind = [string](Get-OptionalProperty -Object $phase -Name "kind" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($kind)) {
			throw "Functional phase kind is required."
		}
		$phaseName = [string](Get-OptionalProperty -Object $phase -Name "name" -DefaultValue $kind)
		switch ($kind) {
			"trace_mount" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$every = [int](Get-OptionalProperty -Object $phase -Name "every" -DefaultValue 1)
				$capacity = [int](Get-OptionalProperty -Object $phase -Name "capacity" -DefaultValue 128)
				$command = Wrap-WithPlayerContext "redstonelink node trace mount $type $serialText $every $capacity"
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				$response = [string]$commandResult.response
				$samples = @(Parse-NodeTraceSamples -ResponseText $response)
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					every = $every
					capacity = $capacity
					command = $command
					response = $response
					tickWindow = $commandResult.tickWindow
					samples = $samples
					mountTicksBySerial = (Resolve-TraceMountTicksBySerial -ResponseText $response)
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_unmount" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$command = Wrap-WithPlayerContext "redstonelink node trace unmount $type $serialText"
				$response = Invoke-RconCommand -Connection $Connection -Command $command -Silent
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					command = $command
					response = $response
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"input_start_square" {
				$endpoint = [string](Get-OptionalProperty -Object $phase -Name "endpoint" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$periodTicks = [int](Get-OptionalProperty -Object $phase -Name "periodTicks" -DefaultValue 0)
				if ($periodTicks -le 0) {
					throw "input_start_square phase requires periodTicks > 0."
				}
				$highTicks = [int](Get-OptionalProperty -Object $phase -Name "highTicks" -DefaultValue ([Math]::Max(1, [int]($periodTicks / 2))))
				$highPower = [int](Get-OptionalProperty -Object $phase -Name "highPower" -DefaultValue 15)
				$lowPower = [int](Get-OptionalProperty -Object $phase -Name "lowPower" -DefaultValue 0)
				$phaseTicks = [int](Get-OptionalProperty -Object $phase -Name "phaseTicks" -DefaultValue 0)
				$totalTicks = [int](Get-OptionalProperty -Object $phase -Name "totalTicks" -DefaultValue 0)
				$endpointPath = Resolve-InputEndpointCommandPath -Endpoint $endpoint
				$command = Wrap-WithPlayerContext (
					"redstonelink input start {0} square {1} {2} {3} {4} {5} {6} {7}" -f
					$endpointPath,
					$serialText,
					$periodTicks,
					$highTicks,
					$highPower,
					$lowPower,
					$phaseTicks,
					$totalTicks
				)
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText ([string]$commandResult.response) `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Started job="
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					endpoint = $endpoint
					serials = $serials
					serialText = $serialText
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
					waveform = [ordered]@{
						periodTicks = $periodTicks
						highTicks = $highTicks
						highPower = $highPower
						lowPower = $lowPower
						phaseTicks = $phaseTicks
						totalTicks = $totalTicks
					}
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"input_start_custom" {
				$endpoint = [string](Get-OptionalProperty -Object $phase -Name "endpoint" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$sequence = [string](Get-OptionalProperty -Object $phase -Name "sequence" -DefaultValue "")
				if ([string]::IsNullOrWhiteSpace($sequence)) {
					throw "input_start_custom phase requires sequence."
				}
				$phaseTicks = [int](Get-OptionalProperty -Object $phase -Name "phaseTicks" -DefaultValue 0)
				$totalTicks = [int](Get-OptionalProperty -Object $phase -Name "totalTicks" -DefaultValue 0)
				$endpointPath = Resolve-InputEndpointCommandPath -Endpoint $endpoint
				$command = Wrap-WithPlayerContext (
					"redstonelink input start {0} custom {1} {2} {3} {4}" -f
					$endpointPath,
					$serialText,
					$sequence,
					$phaseTicks,
					$totalTicks
				)
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText ([string]$commandResult.response) `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Started job="
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					endpoint = $endpoint
					serials = $serials
					serialText = $serialText
					sequence = $sequence
					phaseTicks = $phaseTicks
					totalTicks = $totalTicks
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"input_clear" {
				$command = Wrap-WithPlayerContext "redstonelink input clear"
				$response = Invoke-RconCommand -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText $response `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Cleared input jobs:"
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					command = $command
					response = $response
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"link_command" {
				$action = [string](Get-OptionalProperty -Object $phase -Name "action" -DefaultValue "")
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "triggerSource")
				$explicitSourceSerial = Get-OptionalProperty -Object $phase -Name "sourceSerial"
				if ($null -ne $explicitSourceSerial) {
					$sourceSerial = [long]$explicitSourceSerial
				} else {
					$sourceRef = [string](Get-OptionalProperty -Object $phase -Name "sourceRef" -DefaultValue "")
					if ([string]::IsNullOrWhiteSpace($sourceRef)) {
						throw "link_command phase requires sourceRef or sourceSerial."
					}
					$sourceIndex = [int](Get-OptionalProperty -Object $phase -Name "sourceIndex" -DefaultValue 0)
					$sourceCandidates = @(Resolve-PhaseSerials -Phase @{
						kind = $kind
						serialRef = $sourceRef
						serialIndex = $sourceIndex
					} -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
					if ($sourceCandidates.Count -ne 1) {
						throw "link_command phase must resolve exactly one source serial."
					}
					$sourceSerial = [long]$sourceCandidates[0]
				}

				$targetSerials = @()
				if ($action -eq "add" -or $action -eq "remove" -or $action -eq "set") {
					$targetRef = [string](Get-OptionalProperty -Object $phase -Name "targetRef" -DefaultValue "")
					$targetIndexes = Get-OptionalProperty -Object $phase -Name "targetIndexes"
					$singleTargetIndex = Get-OptionalProperty -Object $phase -Name "targetIndex"
					$explicitTargetSerials = Get-OptionalProperty -Object $phase -Name "targetSerials"
					$targetPhase = @{
						kind = $kind
						serialRef = $targetRef
					}
					if ($null -ne $explicitTargetSerials) {
						$targetPhase.serials = @($explicitTargetSerials)
					}
					if ($null -ne $singleTargetIndex) {
						$targetPhase.serialIndex = [int]$singleTargetIndex
					}
					if ($null -ne $targetIndexes) {
						$targetPhase.serialIndexes = @($targetIndexes)
					}
					$targetSerials = @(Resolve-PhaseSerials -Phase $targetPhase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				}

				$targetSerialFormat = [string](Get-OptionalProperty -Object $phase -Name "targetSerialFormat" -DefaultValue "slash_list")
				$forceConfirm = [bool](Get-OptionalProperty -Object $phase -Name "forceConfirm" -DefaultValue $false)
				switch ($action) {
					"add" {
						if ($targetSerials.Count -ne 1) {
							throw "link_command add requires exactly one target serial."
						}
						$commandText = "redstonelink link add $type $sourceSerial $($targetSerials[0])"
					}
					"remove" {
						if ($targetSerials.Count -ne 1) {
							throw "link_command remove requires exactly one target serial."
						}
						$commandText = "redstonelink link remove $type $sourceSerial $($targetSerials[0])"
					}
					"set" {
						$commandText = "redstonelink link set $type $sourceSerial $(Format-SerialInputText -Serials $targetSerials -Style $targetSerialFormat)"
						if ($targetSerials.Count -gt 1 -or $forceConfirm) {
							$commandText += " confirm"
						}
					}
					"clear" {
						$commandText = "redstonelink link set $type $sourceSerial"
					}
					"get" {
						$commandText = "redstonelink link get $type $sourceSerial"
					}
					default {
						throw "Unsupported link_command action: $action"
					}
				}
				$command = Wrap-WithPlayerContext $commandText
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					action = $action
					type = $type
					sourceSerial = $sourceSerial
					targetSerials = $targetSerials
					targetSerialText = if ($targetSerials.Count -gt 0) { Format-SerialInputText -Serials $targetSerials -Style $targetSerialFormat } else { "" }
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"activate_batch" {
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$mode = [string](Get-OptionalProperty -Object $phase -Name "mode" -DefaultValue "toggle")
				$command = Wrap-WithPlayerContext "redstonelink node activate triggerSource $serialText $mode"
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					mode = $mode
					serials = $serials
					serialText = $serialText
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"wait_ticks" {
				$ticks = [int](Get-OptionalProperty -Object $phase -Name "ticks" -DefaultValue 0)
				$waitInfo = Wait-ServerTicks -Connection $Connection -Ticks $ticks
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					wait = $waitInfo
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_latest_assert" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$expected = Get-OptionalProperty -Object $phase -Name "expect"
				if ($null -eq $expected) {
					throw "trace_latest_assert phase requires expect."
				}
				$expectedCount = [int](Get-OptionalProperty -Object $phase -Name "expectedCount" -DefaultValue $serials.Count)
				$command = Wrap-WithPlayerContext "redstonelink node trace latest $type $serialText"
				$response = Invoke-RconCommand -Connection $Connection -Command $command -Silent
				if ($DryRun) {
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						passed = $true
						skipped = $true
						reason = "dry_run"
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						type = $type
						serials = $serials
						serialText = $serialText
						command = $command
						response = $response
						samples = @()
						expected = $expected
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$samples = @(Parse-NodeTraceSamples -ResponseText $response)
				$countCheck = [ordered]@{
					phase = $phaseName
					kind = $kind
					scope = "sample_count"
					passed = ($samples.Count -eq $expectedCount)
					expected = $expectedCount
					actual = $samples.Count
				}
				$checks.Add($countCheck)
				if (-not $countCheck.passed) {
					$failedChecks.Add($countCheck)
				}
				foreach ($sample in $samples) {
					$comparison = Compare-TraceSampleAgainstExpectation -Sample $sample -Expected $expected
					$sampleCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "sample"
						type = $type
						serial = $sample.serial
						passed = ($comparison.mismatches.Count -eq 0)
						expected = $expected
						actual = $comparison.actual
						mismatches = $comparison.mismatches
					}
					$checks.Add($sampleCheck)
					if (-not $sampleCheck.passed) {
						$failedChecks.Add($sampleCheck)
					}
				}
				$phasePassed = ($countCheck.passed -and (@($samples | Where-Object {
					$comparison = Compare-TraceSampleAgainstExpectation -Sample $_ -Expected $expected
					$comparison.mismatches.Count -eq 0
				}).Count -eq $samples.Count))
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					command = $command
					response = $response
					samples = $samples
					expected = $expected
					passed = $phasePassed
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_read_cycle_assert" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$expectedCycleRaw = Get-OptionalProperty -Object $phase -Name "expectCycle"
				$expectedCycle = if ($null -eq $expectedCycleRaw) { @() } else { @($expectedCycleRaw) }
				if ($expectedCycle.Count -le 0) {
					throw "trace_read_cycle_assert phase requires expectCycle."
				}
				$limit = [int](Get-OptionalProperty -Object $phase -Name "limit" -DefaultValue ([Math]::Max($expectedCycle.Count, 8)))
				$minimumCount = [int](Get-OptionalProperty -Object $phase -Name "minimumCount" -DefaultValue $expectedCycle.Count)
				if ($DryRun) {
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						passed = $true
						skipped = $true
						reason = "dry_run"
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						type = $type
						serials = $serials
						serialText = $serialText
						limit = $limit
						minimumCount = $minimumCount
						expectedCycle = $expectedCycle
						reads = @()
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$phasePassed = $true
				$readResults = New-Object System.Collections.Generic.List[object]
				foreach ($serial in $serials) {
					$readResult = Invoke-NodeTraceRead -Connection $Connection -Type $type -Serial $serial -Limit $limit
					$samples = @($readResult.samples)
					$countCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "sample_count"
						type = $type
						serial = $serial
						passed = ($samples.Count -ge $minimumCount)
						expectedMinimum = $minimumCount
						actual = $samples.Count
					}
					$checks.Add($countCheck)
					if (-not $countCheck.passed) {
						$failedChecks.Add($countCheck)
						$phasePassed = $false
					}
					$matchResult = if ($countCheck.passed) {
						Find-TraceCycleMatch -Samples $samples -ExpectedCycle $expectedCycle
					} else {
						[ordered]@{
							matched = $false
							rotation = $null
							searchWindowCount = 0
							actualWindow = @($samples)
							failures = @(
								[ordered]@{
									reason = "count_check_failed"
								}
							)
						}
					}
					$cycleCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "cycle"
						type = $type
						serial = $serial
						passed = [bool]$matchResult.matched
						expectedCycle = $expectedCycle
						actualWindow = $matchResult.actualWindow
						rotation = $matchResult.rotation
						searchWindowCount = $matchResult.searchWindowCount
						failures = $matchResult.failures
					}
					$checks.Add($cycleCheck)
					if (-not $cycleCheck.passed) {
						$failedChecks.Add($cycleCheck)
						$phasePassed = $false
					}
					$readResults.Add([ordered]@{
						serial = $serial
						command = $readResult.command
						response = $readResult.response
						sampleCount = $samples.Count
						samples = $samples
						matched = [bool]$matchResult.matched
						rotation = $matchResult.rotation
						searchWindowCount = $matchResult.searchWindowCount
						actualWindow = $matchResult.actualWindow
						failures = $matchResult.failures
					})
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					limit = $limit
					minimumCount = $minimumCount
					expectedCycle = $expectedCycle
					reads = @($readResults.ToArray())
					passed = $phasePassed
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_read_tick_assert" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$mountRef = [string](Get-OptionalProperty -Object $phase -Name "mountRef" -DefaultValue "")
				$anchorRef = [string](Get-OptionalProperty -Object $phase -Name "anchorRef" -DefaultValue "")
				if ([string]::IsNullOrWhiteSpace($mountRef)) {
					throw "trace_read_tick_assert phase requires mountRef."
				}
				if ([string]::IsNullOrWhiteSpace($anchorRef)) {
					throw "trace_read_tick_assert phase requires anchorRef."
				}
				$mountPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $mountRef
				$anchorPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $anchorRef
				$mountEvery = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "every" -DefaultValue 0)
				if ($mountEvery -ne 1) {
					throw "trace_read_tick_assert requires mountRef every=1."
				}
				$anchorTickWindow = Get-OptionalProperty -Object $anchorPhaseResult -Name "tickWindow"
				if ($null -eq $anchorTickWindow) {
					throw "trace_read_tick_assert anchorRef must point to a command phase with tickWindow."
				}
				$commandStartTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "startTick" -DefaultValue -1L)
				$commandEndTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "endTick" -DefaultValue -1L)
				if ($commandStartTick -lt 0L -or $commandEndTick -lt 0L) {
					throw "trace_read_tick_assert anchorRef tickWindow is invalid."
				}
				$expectedTicks = @(Resolve-TraceTickExpectations -Phase $phase -Type $type -PhaseContext $phaseContext)
				if ($expectedTicks.Count -le 0) {
					throw "trace_read_tick_assert phase resolved no expected ticks."
				}
				$alignmentSlackTicks = [int](Get-OptionalProperty -Object $phase -Name "alignmentSlackTicks" -DefaultValue 1)
				$limit = [int](Get-OptionalProperty -Object $phase -Name "limit" -DefaultValue ([Math]::Max($expectedTicks.Count + $alignmentSlackTicks + 4, 8)))
				if ($DryRun) {
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						passed = $true
						skipped = $true
						reason = "dry_run"
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						type = $type
						serials = $serials
						serialText = $serialText
						mountRef = $mountRef
						anchorRef = $anchorRef
						limit = $limit
						alignmentSlackTicks = $alignmentSlackTicks
						expectedTicks = $expectedTicks
						reads = @()
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$phasePassed = $true
				$readResults = New-Object System.Collections.Generic.List[object]
				foreach ($serial in $serials) {
					$mountTick = Get-TraceMountTickForSerial -MountPhaseResult $mountPhaseResult -Serial $serial
					$mountCapacity = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "capacity" -DefaultValue $limit)
					$searchTickMax = 0L
					$startTickMin = $commandStartTick
					if ($null -ne $mountTick) {
						$startTickMin = [Math]::Max([long]$startTickMin, ([long]$mountTick + 1L))
					}
					$startTickMax = [long]$commandEndTick + [Math]::Max(0, $alignmentSlackTicks)
					$searchTickMax = [long]$startTickMax
					$currentLimit = [Math]::Min([Math]::Max(1, $limit), [Math]::Max(1, $mountCapacity))
					$readResult = Invoke-NodeTraceRead -Connection $Connection -Type $type -Serial $serial -Limit $currentLimit
					$samples = @($readResult.samples)
					$chronologicalSamples = @(Convert-ToChronologicalTraceSamples -Samples $samples)
					while ($currentLimit -lt $mountCapacity) {
						$expandedLimit = Get-ExpandedTraceReadLimit `
							-CurrentLimit $currentLimit `
							-Capacity $mountCapacity `
							-StartTickMin $startTickMin `
							-Samples $chronologicalSamples
						if ($expandedLimit -le $currentLimit) {
							break
						}
						$previousLimit = $currentLimit
						$previousReadResult = $readResult
						$previousSamples = @($samples)
						$previousChronologicalSamples = @($chronologicalSamples)
						$currentLimit = $expandedLimit
						$expandedReadResult = Invoke-NodeTraceRead -Connection $Connection -Type $type -Serial $serial -Limit $currentLimit
						if (Test-NodeTraceReadRateLimited -ResponseText ([string]$expandedReadResult.response)) {
							$currentLimit = $previousLimit
							$readResult = $previousReadResult
							$samples = @($previousSamples)
							$chronologicalSamples = @($previousChronologicalSamples)
							break
						}
						$readResult = $expandedReadResult
						$samples = @($readResult.samples)
						$chronologicalSamples = @(Convert-ToChronologicalTraceSamples -Samples $samples)
					}
					$eligibleSamples = @(
						$chronologicalSamples |
							Where-Object {
								$tickProperty = $_.PSObject.Properties["tick"]
								$null -ne $tickProperty -and [long]$tickProperty.Value -ge $startTickMin
							}
					)
					if ($eligibleSamples.Count -gt 0) {
						$eligibleLastTick = [long](@($eligibleSamples | Select-Object -Last 1)[0].tick)
						$latestCompleteWindowStart = $eligibleLastTick - [long]($expectedTicks.Count - 1)
						if ($latestCompleteWindowStart -gt $searchTickMax) {
							$searchTickMax = $latestCompleteWindowStart
						}
					}
					$countCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "eligible_sample_count"
						type = $type
						serial = $serial
						passed = ($eligibleSamples.Count -ge $expectedTicks.Count)
						expectedMinimum = $expectedTicks.Count
						actual = $eligibleSamples.Count
						rawSampleCount = $samples.Count
						startTickMin = $startTickMin
						startTickMax = $startTickMax
						searchTickMax = $searchTickMax
						mountTick = $mountTick
					}
					$checks.Add($countCheck)
					if (-not $countCheck.passed) {
						$failedChecks.Add($countCheck)
						$phasePassed = $false
					}
					if ($countCheck.passed) {
						$matchResult = Find-TraceTickWindowMatch `
							-Samples $eligibleSamples `
							-ExpectedTicks $expectedTicks `
							-StartTickMin $startTickMin `
							-SearchTickMax $searchTickMax
					} else {
						$matchResult = [ordered]@{
							matched = $false
							startTick = $null
							alignmentOffset = $null
							candidateCount = 0
							actualWindow = @($eligibleSamples)
							failures = @(
								[ordered]@{
									reason = "count_check_failed"
								}
							)
						}
					}
					$tickCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "tick_window"
						type = $type
						serial = $serial
						passed = [bool]$matchResult.matched
						expectedTicks = $expectedTicks
						actualWindow = $matchResult.actualWindow
						startTick = $matchResult.startTick
						alignmentOffset = $matchResult.alignmentOffset
						candidateCount = $matchResult.candidateCount
						failures = $matchResult.failures
					}
					$checks.Add($tickCheck)
					if (-not $tickCheck.passed) {
						$failedChecks.Add($tickCheck)
						$phasePassed = $false
					}
					$readResults.Add([ordered]@{
						serial = $serial
						command = $readResult.command
						response = $readResult.response
						requestedLimit = $limit
						readLimit = $currentLimit
						rawSampleCount = $samples.Count
						samples = $samples
						eligibleSamples = $eligibleSamples
						mountTick = $mountTick
						startTickMin = $startTickMin
						startTickMax = $startTickMax
						searchTickMax = $searchTickMax
						matched = [bool]$matchResult.matched
						startTick = $matchResult.startTick
						alignmentOffset = $matchResult.alignmentOffset
						candidateCount = $matchResult.candidateCount
						actualWindow = $matchResult.actualWindow
						failures = $matchResult.failures
					})
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					mountRef = $mountRef
					anchorRef = $anchorRef
					limit = $limit
					alignmentSlackTicks = $alignmentSlackTicks
					expectedTicks = $expectedTicks
					reads = @($readResults.ToArray())
					passed = $phasePassed
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			default {
				throw "Unsupported functional phase kind: $kind"
			}
		}
	}
	return [ordered]@{
		phases = @($phaseResults.ToArray())
		checks = @($checks.ToArray())
		failedChecks = @($failedChecks.ToArray())
		passed = ($failedChecks.Count -eq 0)
	}
}

function Ensure-ResultsDirectory {
	param(
		[string]$RepoRoot,
		[string]$RelativePath
	)
	$fullPath = Join-Path $RepoRoot $RelativePath
	if (-not (Test-Path $fullPath)) {
		New-Item -Path $fullPath -ItemType Directory -Force | Out-Null
	}
	return $fullPath
}

function Write-ResultJson {
	param(
		[string]$RepoRoot,
		[string]$RelativeResultsDir,
		[string]$CaseId,
		$ResultObject
	)
	$resultsDir = Ensure-ResultsDirectory -RepoRoot $RepoRoot -RelativePath $RelativeResultsDir
	$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
	$resultPath = Join-Path $resultsDir "${timestamp}_${CaseId}.json"
	$jsonText = $ResultObject | ConvertTo-Json -Depth 10
	$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
	[System.IO.File]::WriteAllText($resultPath, $jsonText, $utf8NoBom)
	Write-Host "[Bench] Result -> $resultPath"
	return $resultPath
}

function Show-CaseSummary {
	param($CaseConfig)
	Write-Host "[Bench] Case: $($CaseConfig.id)"
	Write-Host "[Bench] Desc: $($CaseConfig.description)"
	$layer = [string](Get-OptionalProperty -Object $CaseConfig -Name "layer" -DefaultValue "")
	$scenarioId = [string](Get-OptionalProperty -Object $CaseConfig -Name "scenarioId" -DefaultValue "")
	if (-not [string]::IsNullOrWhiteSpace($layer) -or -not [string]::IsNullOrWhiteSpace($scenarioId)) {
		Write-Host "[Bench] Scenario: layer=$layer id=$scenarioId"
	}
	Write-Host "[Bench] Target kind: $($CaseConfig.targets.kind)"
	Write-Host "[Bench] Target count: $(@(Expand-CuboidPositions $CaseConfig.targets.layout).Count)"
	foreach ($group in $CaseConfig.sources) {
		Write-Host "[Bench] Source group: $($group.id) kind=$($group.kind) count=$(@(Expand-CuboidPositions $group.layout).Count)"
	}
}

$matrix = Get-MatrixConfig -Path $MatrixPath

switch ($Action) {
	"List" {
		foreach ($case in $matrix.cases) {
			Write-Host ("{0} :: {1}" -f $case.id, $case.description)
		}
		break
	}
	"PrintCase" {
		$caseConfig = Get-CaseConfig -Matrix $matrix -Id $CaseId
		Show-CaseSummary -CaseConfig $caseConfig
		$targetPositions = @(Expand-CuboidPositions $caseConfig.targets.layout)
		$targetBounds = Get-BoundsFromPositions $targetPositions
		Write-Host "[Bench] Target bounds: $(Format-Vec3 $targetBounds.From) -> $(Format-Vec3 $targetBounds.To)"
		foreach ($group in $caseConfig.sources) {
				$positions = @(Expand-CuboidPositions $group.layout)
			$bounds = Get-BoundsFromPositions $positions
			Write-Host "[Bench] Source bounds [$($group.id)]: $(Format-Vec3 $bounds.From) -> $(Format-Vec3 $bounds.To)"
		}
		break
	}
	"InstallDatapack" {
		Install-BenchDatapack -SourcePath $datapackSource -WorldPath $SavePath
		break
	}
	"RunCase" {
		$caseConfig = Get-CaseConfig -Matrix $matrix -Id $CaseId
		Install-BenchDatapack -SourcePath $datapackSource -WorldPath $SavePath
		Show-CaseSummary -CaseConfig $caseConfig
		Assert-RunCasePlayerContext

			$connection = $null
		try {
			if (-not $DryRun) {
				$connection = Open-RconConnection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			}

			if (-not $DryRun) {
				$connection = Invoke-ReloadAndReconnect -Connection $connection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			} else {
				Invoke-RconCommand -Connection $connection -Command "reload" | Out-Null
			}
			Invoke-PrepareFunctions -Connection $connection -Matrix $matrix
			Ensure-CaseChunksLoaded -Connection $connection -CaseConfig $caseConfig
			$shouldClearArena = [bool](Get-OptionalProperty -Object $caseConfig -Name "clearArena" -DefaultValue $true)
			if ($shouldClearArena) {
				Clear-Arena -Connection $connection -Arena $caseConfig.arena
			}

			$targetPositions = Place-NodeGroup -Connection $connection -Group $caseConfig.targets
			$targetSerialMap = Convert-PositionsToSerialMap -Connection $connection -Positions $targetPositions
			$targetSerials = @(Get-SerialListFromMap $targetSerialMap)

			$sourcePositionGroups = @{}
			$sourceSerialMaps = @{}
			foreach ($group in $caseConfig.sources) {
				$positions = Place-NodeGroup -Connection $connection -Group $group
				$sourcePositionGroups[[string]$group.id] = $positions
				$sourceSerialMaps[[string]$group.id] = (Convert-PositionsToSerialMap -Connection $connection -Positions $positions)
			}

			$linkCommands = Build-LinkCommands -CaseConfig $caseConfig -SourceSerialMaps $sourceSerialMaps -TargetSerials $targetSerials
			$linkOperations = New-Object System.Collections.Generic.List[object]
			foreach ($command in $linkCommands) {
				$linkOperations.Add((Invoke-BenchSetupCommand -Connection $connection -Command $command -ExpectedPrefix "[RedstoneLink"))
			}

			$auditBefore = Invoke-RconCommand -Connection $connection -Command (Wrap-WithPlayerContext "redstonelink audit summary csv") -Silent
			$settleTicks = [int]$matrix.defaults.settleTicks
			if ($settleTicks -gt 0) {
				Start-Sleep -Milliseconds ($settleTicks * [int]$matrix.defaults.tickMillis)
			}

			$sparkStart = Start-SparkCapture `
				-Connection $connection `
				-SparkDefaults $matrix.defaults.spark `
				-CaseName $caseConfig.id `
				-WorldPath $SavePath
			Invoke-DriveSchedule `
				-Connection $connection `
				-CaseConfig $caseConfig `
				-SourcePositionGroups $sourcePositionGroups `
				-SourceSerialMaps $sourceSerialMaps `
				-TickMillis ([int]$matrix.defaults.tickMillis)
			$sparkStop = Stop-SparkCapture `
				-Connection $connection `
				-SparkDefaults $matrix.defaults.spark `
				-CaseName $caseConfig.id `
				-ActivityPath ([string](Get-OptionalProperty -Object $sparkStart -Name "activityPath" -DefaultValue ""))
			$auditAfter = Invoke-RconCommand -Connection $connection -Command (Wrap-WithPlayerContext "redstonelink audit summary csv") -Silent

			$result = [ordered]@{
				caseId = $caseConfig.id
				description = $caseConfig.description
				sourceSerials = $sourceSerialMaps
				targetSerials = $targetSerialMap
				linkCommands = $linkCommands
				linkOperations = @($linkOperations.ToArray())
				spark = [ordered]@{
					start = $sparkStart
					stop = $sparkStop
				}
				audit = [ordered]@{
					before = $auditBefore
					after = $auditAfter
				}
			}
			Write-ResultJson -RepoRoot $repoRoot -RelativeResultsDir ([string]$matrix.defaults.resultsDir) -CaseId $caseConfig.id -ResultObject $result | Out-Null
		}
		finally {
			Close-RconConnection -Connection $connection
		}
		break
	}
	"RunFunctionalCase" {
		$caseConfig = Get-CaseConfig -Matrix $matrix -Id $CaseId
		Install-BenchDatapack -SourcePath $datapackSource -WorldPath $SavePath
		Show-CaseSummary -CaseConfig $caseConfig
		Assert-RunCasePlayerContext

		$connection = $null
		try {
			if (-not $DryRun) {
				$connection = Open-RconConnection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			}

			if (-not $DryRun) {
				$connection = Invoke-ReloadAndReconnect -Connection $connection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			} else {
				Invoke-RconCommand -Connection $connection -Command "reload" | Out-Null
			}
			Invoke-PrepareFunctions -Connection $connection -Matrix $matrix
			Ensure-CaseChunksLoaded -Connection $connection -CaseConfig $caseConfig
			$shouldClearArena = [bool](Get-OptionalProperty -Object $caseConfig -Name "clearArena" -DefaultValue $true)
			if ($shouldClearArena) {
				Clear-Arena -Connection $connection -Arena $caseConfig.arena
			}

			$targetPositions = Place-NodeGroup -Connection $connection -Group $caseConfig.targets
			$targetSerialMap = Convert-PositionsToSerialMap -Connection $connection -Positions $targetPositions
			$targetSerials = @(Get-SerialListFromMap $targetSerialMap)

			$sourcePositionGroups = @{}
			$sourceSerialMaps = @{}
			foreach ($group in $caseConfig.sources) {
				$positions = Place-NodeGroup -Connection $connection -Group $group
				$sourcePositionGroups[[string]$group.id] = $positions
				$sourceSerialMaps[[string]$group.id] = (Convert-PositionsToSerialMap -Connection $connection -Positions $positions)
			}

			$linkCommands = Build-LinkCommands -CaseConfig $caseConfig -SourceSerialMaps $sourceSerialMaps -TargetSerials $targetSerials
			$linkOperations = New-Object System.Collections.Generic.List[object]
			foreach ($command in $linkCommands) {
				$linkOperations.Add((Invoke-BenchSetupCommand -Connection $connection -Command $command -ExpectedPrefix "[RedstoneLink"))
			}

			# 功能验证优先等待真实服务端 tick，而不是仅依赖本地睡眠。
			$functionalSettleTicks = 0
			if ($null -ne $matrix.defaults -and $null -ne $matrix.defaults.settleTicks) {
				$functionalSettleTicks = [int]$matrix.defaults.settleTicks
			}
			if ($functionalSettleTicks -gt 0) {
				Wait-ServerTicks -Connection $connection -Ticks $functionalSettleTicks | Out-Null
			}

			$phaseExecution = Invoke-FunctionalPhases -Connection $connection -CaseConfig $caseConfig -SourceSerialMaps $sourceSerialMaps -TargetSerialMap $targetSerialMap

			$result = [ordered]@{
				caseId = $caseConfig.id
				description = $caseConfig.description
				sourceSerials = $sourceSerialMaps
				targetSerials = $targetSerialMap
				linkCommands = $linkCommands
				linkOperations = @($linkOperations.ToArray())
				phases = $phaseExecution.phases
				checks = $phaseExecution.checks
				passed = $phaseExecution.passed
				failedChecks = $phaseExecution.failedChecks
				artifacts = [ordered]@{
					matrixPath = $MatrixPath
					savePath = $SavePath
				}
			}
			Write-ResultJson -RepoRoot $repoRoot -RelativeResultsDir ([string]$matrix.defaults.resultsDir) -CaseId $caseConfig.id -ResultObject $result | Out-Null
			if (-not $phaseExecution.passed) {
				throw "Functional case failed: $($caseConfig.id)"
			}
		}
		finally {
			Close-RconConnection -Connection $connection
		}
		break
	}
}
