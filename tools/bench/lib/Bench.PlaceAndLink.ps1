<#
.SYNOPSIS
bench 模块：节点放置、取号、建链与驱动步骤。
#>

function Convert-PositionsToSerialMap {
	param(
		$Connection,
		$Positions,
		[string]$Dimension = "minecraft:overworld"
	)
	$result = @{}
	foreach ($pos in $Positions) {
		if ($DryRun) {
			$key = (Format-Vec3 $pos)
			$result[$key] = $script:DryRunSerialCounter
			$script:DryRunSerialCounter++
			continue
		}
		$key = (Format-Vec3 $pos)
		$result[$key] = Get-BlockSerialWithRetry -Connection $Connection -Position $pos -Dimension $Dimension
	}
	return $result
}

function Get-NodeTypeNameByKind {
	param([string]$Kind)
	switch ([string]$Kind) {
		"sync_emitter" { return "triggerSource" }
		"toggle_emitter" { return "triggerSource" }
		"pulse_emitter" { return "triggerSource" }
		"core_block" { return "core" }
		"core_dust" { return "core" }
		default { throw "Unsupported node kind for semantic type resolution: $Kind" }
	}
}

function Parse-PlaceSerialSummary {
	param(
		[string]$ResponseText
	)
	$normalized = ([string]$ResponseText).Trim()
	if ([string]::IsNullOrWhiteSpace($normalized)) {
		return $null
	}
	$match = [System.Text.RegularExpressions.Regex]::Match(
		$normalized,
		"\[RedstoneLink/Bench\]\s+place_summary\s+type=(triggerSource|core)\s+order=([a-z]+)\s+count=(\d+)\s+startSerial=(\d+)\s+endSerial=(\d+)"
	)
	if (-not $match.Success) {
		return $null
	}
	return [ordered]@{
		type = [string]$match.Groups[1].Value
		order = [string]$match.Groups[2].Value
		count = [int]$match.Groups[3].Value
		startSerial = [long]$match.Groups[4].Value
		endSerial = [long]$match.Groups[5].Value
	}
}

function New-SequentialSerialMap {
	param(
		$Positions,
		[long]$StartSerial
	)
	$result = @{}
	for ($index = 0; $index -lt @($Positions).Count; $index++) {
		$key = Format-Vec3 $Positions[$index]
		$result[$key] = $StartSerial + $index
	}
	return $result
}

function Resolve-SerialMapFromPlaceSummary {
	param(
		$Group,
		$Positions,
		$PlacementResponse
	)
	$summary = Parse-PlaceSerialSummary -ResponseText ([string](Get-OptionalProperty -Object $PlacementResponse -Name "response" -DefaultValue ""))
	if ($null -eq $summary) {
		return [ordered]@{
			ok = $false
			reason = "summary_missing"
			summary = $null
			serialMap = $null
		}
	}
	$expectedType = Get-NodeTypeNameByKind -Kind ([string]$Group.kind)
	if ([string]$summary.type -ne $expectedType) {
		return [ordered]@{
			ok = $false
			reason = "type_mismatch"
			summary = $summary
			serialMap = $null
		}
	}
	if ([string]$summary.order -ne "yzx") {
		return [ordered]@{
			ok = $false
			reason = "order_mismatch"
			summary = $summary
			serialMap = $null
		}
	}
	if ([int]$summary.count -ne @($Positions).Count) {
		return [ordered]@{
			ok = $false
			reason = "count_mismatch"
			summary = $summary
			serialMap = $null
		}
	}
	$expectedEndSerial = [long]$summary.startSerial + [long]$summary.count - 1L
	if ([long]$summary.endSerial -ne $expectedEndSerial) {
		return [ordered]@{
			ok = $false
			reason = "serial_gap"
			summary = $summary
			serialMap = $null
		}
	}
	return [ordered]@{
		ok = $true
		reason = "place_summary"
		summary = $summary
		serialMap = (New-SequentialSerialMap -Positions $Positions -StartSerial ([long]$summary.startSerial))
	}
}

function Get-BlockSerialWithRetry {
	param(
		$Connection,
		$Position,
		[string]$Dimension = "minecraft:overworld",
		[int]$MaxAttempts = 8,
		[int]$RetryDelayMs = 250
	)
	$command = Wrap-WithBenchContexts `
		-Command ("data get block {0} Serial" -f (Format-Vec3 $Position)) `
		-Dimension $Dimension
	$normalizedAttempts = [Math]::Max(1, [int]$MaxAttempts)
	$normalizedDelayMs = [Math]::Max(50, [int]$RetryDelayMs)
	$lastResponse = ""
	$lastPlayerReady = $null
	for ($attempt = 1; $attempt -le $normalizedAttempts; $attempt++) {
		$response = Invoke-RconCommand `
			-Connection $Connection `
			-Command $command `
			-Silent
		$lastResponse = ([string]$response).Trim()
		$matches = [System.Text.RegularExpressions.Regex]::Matches($lastResponse, "-?\d+")
		if ($matches.Count -gt 0) {
			return [long]$matches[$matches.Count - 1].Value
		}

		if (-not [string]::IsNullOrWhiteSpace($script:BenchAsPlayer)) {
			$lastPlayerReady = Test-PlayerContextReady -Connection $Connection
			if (-not [bool]$lastPlayerReady.ready) {
				Wait-PlayerContextReady -Connection $Connection | Out-Null
			}
		}

		if ($attempt -lt $normalizedAttempts) {
			Start-Sleep -Milliseconds $normalizedDelayMs
		}
	}

	$playerContextDetail = ""
	if ($null -ne $lastPlayerReady) {
		$playerContextDetail = " | playerReady=$([bool]$lastPlayerReady.ready) probeResponse=$([string]$lastPlayerReady.response)"
	}
	throw "Failed to parse Serial from response: $lastResponse | command=$command$playerContextDetail"
}

function Invoke-BenchPlaceCommand {
	param(
		$Connection,
		[string]$Command
	)
	$response = Invoke-RconCommand -Connection $Connection -Command $Command -Silent
	$normalized = ([string]$response).Trim()
	if (Test-BenchResponseLooksLikeFailure -ResponseText $normalized) {
		throw "Bench place command returned failure response: $Command | response=$normalized"
	}
	return [ordered]@{
		command = $Command
		response = $response
	}
}

function Place-NodeGroup {
	param(
		$Connection,
		$Group
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	$positions = @(Expand-CuboidPositions $Group.layout)
	$dimension = Get-LayoutDimensionId -Layout $Group.layout
	if ($DryRun) {
		return [ordered]@{
			positions = $positions
			dimension = $dimension
			serialMap = (Convert-PositionsToSerialMap -Connection $Connection -Positions $positions -Dimension $dimension)
			placement = $null
			serialResolution = [ordered]@{
				mode = "dry_run_counter"
				reason = "dry_run_counter"
				summary = $null
			}
			elapsedMs = $stopwatch.ElapsedMilliseconds
		}
	}
	$reuseExisting = [bool](Get-OptionalProperty -Object $Group -Name "reuseExisting" -DefaultValue $false)
	if ($reuseExisting) {
		return [ordered]@{
			positions = $positions
			dimension = $dimension
			serialMap = (Convert-PositionsToSerialMap -Connection $Connection -Positions $positions -Dimension $dimension)
			placement = $null
			serialResolution = [ordered]@{
				mode = "reuse_existing_probe"
				reason = "explicit_reuse_existing"
				summary = $null
			}
			elapsedMs = $stopwatch.ElapsedMilliseconds
		}
	}
	$bounds = Get-BoundsFromPositions $positions
	$blockId = Get-BlockIdByKind $Group.kind
	$command = ""
	if ($positions.Count -gt 1) {
		$command = Wrap-WithBenchContexts `
			-Command ("redstonelink place fill {0} {1} {2} force bench" -f (Format-Vec3 $bounds.From), (Format-Vec3 $bounds.To), $blockId) `
			-Dimension $dimension
	} else {
		$command = Wrap-WithBenchContexts `
			-Command ("redstonelink place setblock {0} {1} force bench" -f (Format-Vec3 $positions[0]), $blockId) `
			-Dimension $dimension
	}
	$placement = Invoke-BenchPlaceCommand -Connection $Connection -Command $command
	$summaryResolution = Resolve-SerialMapFromPlaceSummary -Group $Group -Positions $positions -PlacementResponse $placement
	if ([bool]$summaryResolution.ok) {
		return [ordered]@{
			positions = $positions
			dimension = $dimension
			serialMap = $summaryResolution.serialMap
			placement = $placement
			serialResolution = [ordered]@{
				mode = "place_summary"
				reason = [string]$summaryResolution.reason
				summary = $summaryResolution.summary
			}
			elapsedMs = $stopwatch.ElapsedMilliseconds
		}
	}
	return [ordered]@{
		positions = $positions
		dimension = $dimension
		serialMap = (Convert-PositionsToSerialMap -Connection $Connection -Positions $positions -Dimension $dimension)
		placement = $placement
		serialResolution = [ordered]@{
			mode = "fallback_probe"
			reason = [string]$summaryResolution.reason
			summary = $summaryResolution.summary
		}
		elapsedMs = $stopwatch.ElapsedMilliseconds
	}
}

function Get-OrderedSerialListFromPositionMap {
	param([hashtable]$Map)
	if ($null -eq $Map -or $Map.Count -le 0) {
		return @()
	}
	$entries = @($Map.GetEnumerator())
	$parsedEntries = New-Object System.Collections.Generic.List[object]
	$allKeysArePositions = $true
	foreach ($entry in $entries) {
		$keyText = [string]$entry.Key
		$match = [System.Text.RegularExpressions.Regex]::Match($keyText, '^\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*$')
		if (-not $match.Success) {
			$allKeysArePositions = $false
			break
		}
		$parsedEntries.Add([pscustomobject]@{
			Key = $keyText
			X = [int]$match.Groups[1].Value
			Y = [int]$match.Groups[2].Value
			Z = [int]$match.Groups[3].Value
			Serial = [long]$entry.Value
		})
	}
	if (-not $allKeysArePositions) {
		return @(
			$entries |
				Sort-Object Name |
				ForEach-Object { [long]$_.Value }
		)
	}
	return @(
		$parsedEntries |
			Sort-Object Y, Z, X |
			ForEach-Object { [long]$_.Serial }
	)
}

function Get-ResolvedLinkTargetsForRule {
	param(
		$Rule,
		[int]$SourceIndex,
		[long[]]$TargetSerials
	)
	$normalizedTargets = @(Get-SortedUniqueSerials $TargetSerials)
	if ($normalizedTargets.Count -le 0) {
		return @()
	}
	switch ([string]$Rule.mapping) {
		"broadcast_all" { return $normalizedTargets }
		"fan_in_first" { return @($normalizedTargets[0]) }
		"round_robin" { return @($normalizedTargets[$SourceIndex % $normalizedTargets.Count]) }
		"zip" {
			if ($SourceIndex -lt $normalizedTargets.Count) {
				return @($normalizedTargets[$SourceIndex])
			}
			return @()
		}
		"banded" {
			$fanout = [int](Get-OptionalProperty -Object $Rule -Name "fanout" -DefaultValue 0)
			if ($fanout -le 0) {
				throw "banded mapping requires fanout > 0."
			}
			$stride = [int](Get-OptionalProperty -Object $Rule -Name "stride" -DefaultValue $fanout)
			$offset = [int](Get-OptionalProperty -Object $Rule -Name "offset" -DefaultValue 0)
			$wrap = [bool](Get-OptionalProperty -Object $Rule -Name "wrap" -DefaultValue $true)
			$targetCount = $normalizedTargets.Count
			$startIndex = $offset + ($SourceIndex * $stride)
			if ($wrap) {
				$startIndex = (($startIndex % $targetCount) + $targetCount) % $targetCount
			} elseif ($startIndex -ge $targetCount) {
				return @()
			}
			$resolved = New-Object System.Collections.Generic.List[long]
			for ($windowOffset = 0; $windowOffset -lt $fanout; $windowOffset++) {
				$targetIndex = $startIndex + $windowOffset
				if ($wrap) {
					$targetIndex = $targetIndex % $targetCount
				} elseif ($targetIndex -ge $targetCount) {
					break
				}
				$resolved.Add([long]$normalizedTargets[$targetIndex])
			}
			return @(Get-SortedUniqueSerials $resolved.ToArray())
		}
		default { throw "Unsupported mapping mode: $($Rule.mapping)" }
	}
}

function Resolve-LinkTargetSerialFormat {
	param(
		$Rule,
		[long[]]$TargetSerials
	)
	$explicitFormat = [string](Get-OptionalProperty -Object $Rule -Name "targetSerialFormat" -DefaultValue "")
	if (-not [string]::IsNullOrWhiteSpace($explicitFormat)) {
		return $explicitFormat
	}

	$normalizedTargets = @(Get-SortedUniqueSerials $TargetSerials)
	if ($normalizedTargets.Count -le 1) {
		return "slash_list"
	}

	$slashText = Format-SerialInputText -Serials $normalizedTargets -Style "slash_list"
	$rangeText = Format-SerialInputText -Serials $normalizedTargets -Style "range"
	if ([string]::IsNullOrWhiteSpace($rangeText)) {
		return "slash_list"
	}
	if ($rangeText.Length -lt $slashText.Length) {
		return "range"
	}
	if ($rangeText.Length -eq $slashText.Length -and $rangeText.Contains(":")) {
		return "range"
	}
	return "slash_list"
}

function Test-SupportsStructuredBenchLinkMapping {
	param($Rule)
	switch ([string]$Rule.mapping) {
		"broadcast_all" { return $true }
		"fan_in_first" { return $true }
		"banded" { return $true }
		default { return $false }
	}
}

function Build-ClassicLinkCommandPlansForRule {
	param(
		$Rule,
		[long[]]$SourceSerials,
		[long[]]$TargetSerials
	)
	$linkPlans = New-Object System.Collections.Generic.List[object]
	for ($index = 0; $index -lt $SourceSerials.Count; $index++) {
		$sourceSerial = [long]$SourceSerials[$index]
		$mappedTargets = @(Get-ResolvedLinkTargetsForRule -Rule $Rule -SourceIndex $index -TargetSerials $TargetSerials)
		if ($mappedTargets.Count -eq 0) {
			continue
		}
		$targetSerialFormat = Resolve-LinkTargetSerialFormat -Rule $Rule -TargetSerials $mappedTargets
		$targetSerialText = Format-SerialInputText -Serials $mappedTargets -Style $targetSerialFormat
		$command = "redstonelink link set triggerSource $sourceSerial $targetSerialText"
		if ($mappedTargets.Count -gt 1) {
			$command += " confirm"
		}
		$linkPlans.Add([ordered]@{
			mode = "classic"
			mapping = [string]$Rule.mapping
			sourceGroup = [string]$Rule.sourceGroup
			command = (Wrap-WithPlayerContext $command)
			fallbackCommands = @()
			sourceCount = 1
			targetCount = $mappedTargets.Count
		})
	}
	return @($linkPlans.ToArray())
}

function New-StructuredLinkCommandPlan {
	param(
		$Rule,
		[long[]]$SourceSerials,
		[long[]]$TargetSerials
	)
	$sourceSerialText = Format-SerialInputText -Serials $SourceSerials -Style "range"
	$targetSerialText = Format-SerialInputText -Serials $TargetSerials -Style "range"
	$mappingCommandText = ""
	switch ([string]$Rule.mapping) {
		"broadcast_all" {
			$mappingCommandText = "broadcast_all"
		}
		"fan_in_first" {
			$mappingCommandText = "fan_in_first"
		}
		"banded" {
			$fanout = [int](Get-OptionalProperty -Object $Rule -Name "fanout" -DefaultValue 0)
			if ($fanout -le 0) {
				throw "banded mapping requires fanout > 0."
			}
			$stride = [int](Get-OptionalProperty -Object $Rule -Name "stride" -DefaultValue $fanout)
			$offset = [int](Get-OptionalProperty -Object $Rule -Name "offset" -DefaultValue 0)
			$wrap = [bool](Get-OptionalProperty -Object $Rule -Name "wrap" -DefaultValue $true)
			$wrapText = if ($wrap) { "true" } else { "false" }
			$mappingCommandText = "banded fanout=$fanout stride=$stride offset=$offset wrap=$wrapText"
		}
		default {
			throw "Unsupported structured mapping mode: $($Rule.mapping)"
		}
	}
	$command = Wrap-WithPlayerContext (
		"redstonelink bench link apply triggerSource {0} core {1} {2}" -f
		$sourceSerialText,
		$targetSerialText,
		$mappingCommandText
	)
	return [ordered]@{
		mode = "structured"
		mapping = [string]$Rule.mapping
		sourceGroup = [string]$Rule.sourceGroup
		command = $command
		fallbackCommands = @(
			Build-ClassicLinkCommandPlansForRule -Rule $Rule -SourceSerials $SourceSerials -TargetSerials $TargetSerials |
				ForEach-Object { [string]$_.command }
		)
		sourceCount = $SourceSerials.Count
		targetCount = $TargetSerials.Count
	}
}

function Build-LinkCommands {
	param(
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$linkPlans = New-Object System.Collections.Generic.List[object]
	$linkRules = @(Get-OptionalProperty -Object $CaseConfig -Name "links" -DefaultValue @())
	$orderedTargetSerials = @(Get-OrderedSerialListFromPositionMap -Map $TargetSerialMap)
	foreach ($rule in $linkRules) {
		$groupName = [string]$rule.sourceGroup
		$sourceSerials = @(Get-OrderedSerialListFromPositionMap -Map $SourceSerialMaps[$groupName])
		if ($sourceSerials.Count -eq 0) {
			continue
		}
		if (Test-SupportsStructuredBenchLinkMapping -Rule $rule) {
			$linkPlans.Add((New-StructuredLinkCommandPlan -Rule $rule -SourceSerials $sourceSerials -TargetSerials $orderedTargetSerials))
			continue
		}
		foreach ($plan in @(Build-ClassicLinkCommandPlansForRule -Rule $rule -SourceSerials $sourceSerials -TargetSerials $orderedTargetSerials)) {
			$linkPlans.Add($plan)
		}
	}
	return @($linkPlans.ToArray())
}

function Invoke-BenchSetupCommand {
	param(
		$Connection,
		[string]$Command,
		[string]$ExpectedPrefix = "",
		[string]$ExpectedRegex = ""
	)
	if ($DryRun) {
		$response = if ($ExpectedPrefix -eq "[RedstoneLink/Input]") {
			if (-not [string]::IsNullOrWhiteSpace($ExpectedRegex) -and $ExpectedRegex -match "Started job=") {
				$jobId = [long]$script:DryRunInputJobCounter
				$script:DryRunInputJobCounter++
				"[RedstoneLink/Input] Started job=$jobId [DryRun]"
			} elseif (-not [string]::IsNullOrWhiteSpace($ExpectedRegex) -and $ExpectedRegex -match "Cleared input jobs:") {
				"[RedstoneLink/Input] Cleared input jobs: [DryRun]"
			} else {
				"$ExpectedPrefix [DryRun] skipped"
			}
		} elseif (-not [string]::IsNullOrWhiteSpace($ExpectedPrefix)) {
			"$ExpectedPrefix [DryRun] skipped"
		} else {
			"[RedstoneLink] [DryRun] skipped"
		}
		return [ordered]@{
			command = $Command
			response = $response
		}
	}
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

function Invoke-LinkCommandPlans {
	param(
		$Connection,
		$LinkCommandPlans
	)
	$operations = New-Object System.Collections.Generic.List[object]
	$executedCommands = New-Object System.Collections.Generic.List[string]
	$structuredPlanCount = 0
	$classicPlanCount = 0
	$fallbackPlanCount = 0
	foreach ($plan in @($LinkCommandPlans)) {
		$mode = [string](Get-OptionalProperty -Object $plan -Name "mode" -DefaultValue "classic")
		$mapping = [string](Get-OptionalProperty -Object $plan -Name "mapping" -DefaultValue "")
		$sourceGroup = [string](Get-OptionalProperty -Object $plan -Name "sourceGroup" -DefaultValue "")
		$command = [string](Get-OptionalProperty -Object $plan -Name "command" -DefaultValue "")
		$fallbackCommands = @((Get-OptionalProperty -Object $plan -Name "fallbackCommands" -DefaultValue @()))
		if ($mode -eq "structured") {
			$structuredPlanCount++
		} else {
			$classicPlanCount++
		}
		if ([string]::IsNullOrWhiteSpace($command)) {
			continue
		}

		if ($mode -ne "structured") {
			$operation = Invoke-BenchSetupCommand -Connection $Connection -Command $command -ExpectedPrefix "[RedstoneLink"
			$executedCommands.Add($command)
			$operations.Add([ordered]@{
				mode = $mode
				mapping = $mapping
				sourceGroup = $sourceGroup
				fallbackUsed = $false
				primaryCommand = $command
				primaryResponse = $operation.response
				executedCommands = @($command)
				fallbackCommands = @()
			})
			continue
		}

		$primaryResponse = Invoke-RconCommand -Connection $Connection -Command $command -Silent
		$normalizedResponse = ([string]$primaryResponse).Trim()
		$shouldFallback = ([string]::IsNullOrWhiteSpace($normalizedResponse) -or (Test-BenchResponseLooksLikeFailure -ResponseText $normalizedResponse))
		if ($shouldFallback -and $fallbackCommands.Count -gt 0) {
			$fallbackPlanCount++
			Write-Host "[Bench] Structured link apply fallback. mapping=$mapping sourceGroup=$sourceGroup response=$normalizedResponse"
			$fallbackOperations = New-Object System.Collections.Generic.List[object]
			$fallbackExecutedCommands = New-Object System.Collections.Generic.List[string]
			foreach ($fallbackCommand in $fallbackCommands) {
				$fallbackOperation = Invoke-BenchSetupCommand -Connection $Connection -Command ([string]$fallbackCommand) -ExpectedPrefix "[RedstoneLink"
				$fallbackExecutedCommands.Add([string]$fallbackCommand)
				$executedCommands.Add([string]$fallbackCommand)
				$fallbackOperations.Add($fallbackOperation)
			}
			$operations.Add([ordered]@{
				mode = $mode
				mapping = $mapping
				sourceGroup = $sourceGroup
				fallbackUsed = $true
				fallbackReason = $normalizedResponse
				primaryCommand = $command
				primaryResponse = $primaryResponse
				executedCommands = @($fallbackExecutedCommands.ToArray())
				fallbackCommands = @($fallbackCommands)
				fallbackOperations = @($fallbackOperations.ToArray())
			})
			continue
		}

		Assert-BenchCommandResponse -Command $command -ResponseText $primaryResponse -ExpectedPrefix "[RedstoneLink"
		$executedCommands.Add($command)
		$operations.Add([ordered]@{
			mode = $mode
			mapping = $mapping
			sourceGroup = $sourceGroup
			fallbackUsed = $false
			primaryCommand = $command
			primaryResponse = $primaryResponse
			executedCommands = @($command)
			fallbackCommands = @($fallbackCommands)
		})
	}

	return [ordered]@{
		planCount = @($LinkCommandPlans).Count
		executedCommandCount = $executedCommands.Count
		structuredPlanCount = $structuredPlanCount
		classicPlanCount = $classicPlanCount
		fallbackPlanCount = $fallbackPlanCount
		executedCommands = @($executedCommands.ToArray())
		operations = @($operations.ToArray())
	}
}

function Test-CaseUsesDriveInput {
	param($CaseConfig)
	foreach ($step in @($CaseConfig.drive.steps)) {
		$kind = [string](Get-OptionalProperty -Object $step -Name "kind" -DefaultValue "")
		if ($kind -eq "input_square" -or $kind -eq "input_custom") {
			return $true
		}
	}
	return $false
}

function Test-CaseUsesOnlyDriveInput {
	param($CaseConfig)
	$steps = @($CaseConfig.drive.steps)
	if ($steps.Count -le 0) {
		return $false
	}
	foreach ($step in $steps) {
		$kind = [string](Get-OptionalProperty -Object $step -Name "kind" -DefaultValue "")
		if ($kind -ne "input_square" -and $kind -ne "input_custom") {
			return $false
		}
	}
	return $true
}

function Get-CaseDriveTotalTicks {
	param(
		$CaseConfig,
		$Matrix
	)
	$drive = Get-OptionalProperty -Object $CaseConfig -Name "drive"
	$configuredTotalTicks = [int](Get-OptionalProperty -Object $drive -Name "totalTicks" -DefaultValue 0)
	$performanceWindow = Get-OptionalProperty -Object (Get-OptionalProperty -Object $Matrix -Name "defaults") -Name "performanceWindow"
	$windowTotalTicks = [int](Get-OptionalProperty -Object $performanceWindow -Name "totalTicks" -DefaultValue 0)
	if ($windowTotalTicks -gt 0) {
		return $windowTotalTicks
	}
	return $configuredTotalTicks
}

function Get-CasePerformanceWindow {
	param(
		$CaseConfig,
		$Matrix
	)
	$performanceWindow = Get-OptionalProperty -Object (Get-OptionalProperty -Object $Matrix -Name "defaults") -Name "performanceWindow"
	if ($null -eq $performanceWindow) {
		return $null
	}
	$totalTicks = Get-CaseDriveTotalTicks -CaseConfig $CaseConfig -Matrix $Matrix
	$warmupTicks = [int](Get-OptionalProperty -Object $performanceWindow -Name "warmupTicks" -DefaultValue 0)
	$measureTicks = [int](Get-OptionalProperty -Object $performanceWindow -Name "measureTicks" -DefaultValue 0)
	if ($totalTicks -le 0 -or $measureTicks -le 0) {
		throw "performanceWindow requires totalTicks > 0 and measureTicks > 0."
	}
	if (($warmupTicks + $measureTicks) -gt $totalTicks) {
		throw "performanceWindow exceeds totalTicks. warmup=$warmupTicks measure=$measureTicks total=$totalTicks"
	}
	return [ordered]@{
		totalTicks = $totalTicks
		warmupTicks = $warmupTicks
		measureTicks = $measureTicks
		cooldownTicks = ($totalTicks - $warmupTicks - $measureTicks)
	}
}

function Resolve-DriveInputEndpointCommandPath {
	param([string]$Endpoint)
	switch ([string]$Endpoint) {
		"triggerSource" { return "triggerSource" }
		"core_sync" { return "core sync" }
		default { throw "Unsupported performance input endpoint: $Endpoint" }
	}
}

function Resolve-DriveStepSerials {
	param(
		$Step,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$explicitSerials = Get-OptionalProperty -Object $Step -Name "serials"
	if ($null -ne $explicitSerials) {
		return @(Get-SortedUniqueSerials (@($explicitSerials | ForEach-Object { [long]$_ })))
	}
	$serialRef = [string](Get-OptionalProperty -Object $Step -Name "serialRef" -DefaultValue "")
	if ([string]::IsNullOrWhiteSpace($serialRef)) {
		$serialRef = [string](Get-OptionalProperty -Object $Step -Name "sourceGroup" -DefaultValue "")
	}
	if ([string]::IsNullOrWhiteSpace($serialRef)) {
		throw "Drive step kind '$($Step.kind)' requires serialRef/sourceGroup or explicit serials."
	}
	if ($serialRef -eq "targets") {
		return @(Get-SerialListFromMap $TargetSerialMap)
	}
	if ($SourceSerialMaps.ContainsKey($serialRef)) {
		return @(Get-SerialListFromMap $SourceSerialMaps[$serialRef])
	}
	throw "Unknown drive serialRef/sourceGroup: $serialRef"
}

function Start-DriveInputStep {
	param(
		$Connection,
		$Step,
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap,
		[int]$TotalTicksOverride = 0
	)
	$serials = @(Resolve-DriveStepSerials -Step $Step -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
	if ($serials.Count -le 0) {
		return $null
	}
	$serialFormat = [string](Get-OptionalProperty -Object $Step -Name "serialFormat" -DefaultValue "slash_list")
	$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
	$endpoint = [string](Get-OptionalProperty -Object $Step -Name "endpoint" -DefaultValue "triggerSource")
	$endpointPath = Resolve-DriveInputEndpointCommandPath -Endpoint $endpoint
	$totalTicks = if ($TotalTicksOverride -gt 0) {
		[int]$TotalTicksOverride
	} else {
		[int](Get-OptionalProperty -Object $Step -Name "totalTicks" -DefaultValue ([int]$CaseConfig.drive.totalTicks))
	}
	switch ([string]$Step.kind) {
		"input_square" {
			$periodTicks = [int](Get-OptionalProperty -Object $Step -Name "periodTicks" -DefaultValue 0)
			if ($periodTicks -le 0) {
				throw "input_square drive step requires periodTicks > 0."
			}
			$highTicks = [int](Get-OptionalProperty -Object $Step -Name "highTicks" -DefaultValue ([Math]::Max(1, [int]($periodTicks / 2))))
			$highPower = [int](Get-OptionalProperty -Object $Step -Name "highPower" -DefaultValue 15)
			$lowPower = [int](Get-OptionalProperty -Object $Step -Name "lowPower" -DefaultValue 0)
			$phaseTicks = [int](Get-OptionalProperty -Object $Step -Name "phaseTicks" -DefaultValue 0)
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
			$commandResult = Invoke-BenchSetupCommand -Connection $Connection -Command $command -ExpectedPrefix "[RedstoneLink/Input]" -ExpectedRegex "Started job="
			return [ordered]@{
				kind = [string]$Step.kind
				endpoint = $endpoint
				serials = $serials
				serialText = $serialText
				command = $commandResult.command
				response = $commandResult.response
				waveform = [ordered]@{
					periodTicks = $periodTicks
					highTicks = $highTicks
					highPower = $highPower
					lowPower = $lowPower
					phaseTicks = $phaseTicks
					totalTicks = $totalTicks
				}
			}
		}
		"input_custom" {
			$sequence = [string](Get-OptionalProperty -Object $Step -Name "sequence" -DefaultValue "")
			if ([string]::IsNullOrWhiteSpace($sequence)) {
				throw "input_custom drive step requires sequence."
			}
			$phaseTicks = [int](Get-OptionalProperty -Object $Step -Name "phaseTicks" -DefaultValue 0)
			$command = Wrap-WithPlayerContext (
				"redstonelink input start {0} custom {1} {2} {3} {4}" -f
				$endpointPath,
				$serialText,
				$sequence,
				$phaseTicks,
				$totalTicks
			)
			$commandResult = Invoke-BenchSetupCommand -Connection $Connection -Command $command -ExpectedPrefix "[RedstoneLink/Input]" -ExpectedRegex "Started job="
			return [ordered]@{
				kind = [string]$Step.kind
				endpoint = $endpoint
				serials = $serials
				serialText = $serialText
				command = $commandResult.command
				response = $commandResult.response
				waveform = [ordered]@{
					sequence = $sequence
					phaseTicks = $phaseTicks
					totalTicks = $totalTicks
				}
			}
		}
		default {
			throw "Unsupported performance input drive kind: $($Step.kind)"
		}
	}
}

function Clear-DriveInputJobs {
	param($Connection)
	if ($DryRun) {
		return [ordered]@{
			command = "redstonelink input clear"
			response = "[DryRun] skipped"
		}
	}
	return Invoke-BenchSetupCommand `
		-Connection $Connection `
		-Command (Wrap-WithPlayerContext "redstonelink input clear") `
		-ExpectedPrefix "[RedstoneLink/Input]"
}

function Invoke-DriveSchedule {
	param(
		$Connection,
		$CaseConfig,
		[hashtable]$SourcePositionGroups,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap,
		[int]$TickMillis,
		[int]$TotalTicksOverride = 0
	)
	$totalTicks = if ($TotalTicksOverride -gt 0) { [int]$TotalTicksOverride } else { [int]$CaseConfig.drive.totalTicks }
	$stepStates = @{}
	$startedInputCommands = New-Object System.Collections.Generic.List[object]
	for ($tick = 0; $tick -lt $totalTicks; $tick++) {
		foreach ($step in $CaseConfig.drive.steps) {
			$stepKey = "{0}:{1}" -f $step.kind, $step.sourceGroup
			switch ([string]$step.kind) {
				"input_square" {
					if (-not $stepStates.ContainsKey($stepKey)) {
						$commandResult = Start-DriveInputStep `
							-Connection $Connection `
							-Step $step `
							-CaseConfig $CaseConfig `
							-SourceSerialMaps $SourceSerialMaps `
							-TargetSerialMap $TargetSerialMap `
							-TotalTicksOverride $totalTicks
						if ($null -ne $commandResult) {
							$startedInputCommands.Add($commandResult)
						}
						$stepStates[$stepKey] = $true
					}
				}
				"input_custom" {
					if (-not $stepStates.ContainsKey($stepKey)) {
						$commandResult = Start-DriveInputStep `
							-Connection $Connection `
							-Step $step `
							-CaseConfig $CaseConfig `
							-SourceSerialMaps $SourceSerialMaps `
							-TargetSerialMap $TargetSerialMap `
							-TotalTicksOverride $totalTicks
						if ($null -ne $commandResult) {
							$startedInputCommands.Add($commandResult)
						}
						$stepStates[$stepKey] = $true
					}
				}
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
	return [ordered]@{
		totalTicks = $totalTicks
		usedInputDrive = ($startedInputCommands.Count -gt 0)
		inputCommands = @($startedInputCommands.ToArray())
	}
}

function Invoke-InputDriveSchedule {
	param(
		$Connection,
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap,
		[int]$TotalTicks
	)
	$normalizedTotalTicks = [Math]::Max(1, [int]$TotalTicks)
	$startedInputCommands = New-Object System.Collections.Generic.List[object]
	foreach ($step in @($CaseConfig.drive.steps)) {
		$kind = [string](Get-OptionalProperty -Object $step -Name "kind" -DefaultValue "")
		if ($kind -ne "input_square" -and $kind -ne "input_custom") {
			throw "Invoke-InputDriveSchedule only supports input_square/input_custom steps. kind=$kind"
		}
		$commandResult = Start-DriveInputStep `
			-Connection $Connection `
			-Step $step `
			-CaseConfig $CaseConfig `
			-SourceSerialMaps $SourceSerialMaps `
			-TargetSerialMap $TargetSerialMap `
			-TotalTicksOverride $normalizedTotalTicks
		if ($null -ne $commandResult) {
			$startedInputCommands.Add($commandResult)
		}
	}
	return [ordered]@{
		totalTicks = $normalizedTotalTicks
		usedInputDrive = ($startedInputCommands.Count -gt 0)
		inputCommands = @($startedInputCommands.ToArray())
	}
}
