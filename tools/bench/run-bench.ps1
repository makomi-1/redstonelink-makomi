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
	[ValidateSet("List", "PrintCase", "InstallDatapack", "RunCase")]
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
$script:BenchAsPlayer = $AsPlayer

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
	$receivedPackets = New-Object System.Collections.Generic.List[object]
	try {
		try {
			$firstPacket = Read-RconPacket -Stream $Connection.Stream
		} catch {
			if ($AllowReadTimeout) {
				if (-not $Silent) {
					Write-Host "[Bench/RCON] $Command"
					Write-Host "[Bench/RESP] <read timeout tolerated>"
				}
				return ""
			}
			throw
		}
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

function Format-SerialInputText {
	param([long[]]$Serials)
	return (($Serials | Sort-Object) -join "/")
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
	foreach ($rule in $CaseConfig.links) {
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
			Clear-Arena -Connection $connection -Arena $caseConfig.arena

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
			foreach ($command in $linkCommands) {
				Invoke-RconCommand -Connection $connection -Command $command | Out-Null
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
				-ActivityPath ([string]$sparkStart.activityPath)
			$auditAfter = Invoke-RconCommand -Connection $connection -Command (Wrap-WithPlayerContext "redstonelink audit summary csv") -Silent

			$result = [ordered]@{
				caseId = $caseConfig.id
				description = $caseConfig.description
				sourceSerials = $sourceSerialMaps
				targetSerials = $targetSerialMap
				linkCommands = $linkCommands
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
}
