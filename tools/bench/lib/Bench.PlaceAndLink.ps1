<#
.SYNOPSIS
bench 模块：节点放置、取号、建链与驱动步骤。
#>

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

function Build-LinkCommands {
	param(
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$linkCommands = New-Object System.Collections.Generic.List[string]
	$linkRules = @(Get-OptionalProperty -Object $CaseConfig -Name "links" -DefaultValue @())
	$orderedTargetSerials = @(Get-OrderedSerialListFromPositionMap -Map $TargetSerialMap)
	foreach ($rule in $linkRules) {
		$groupName = [string]$rule.sourceGroup
		$sourceSerials = @(Get-OrderedSerialListFromPositionMap -Map $SourceSerialMaps[$groupName])
		if ($sourceSerials.Count -eq 0) {
			continue
		}
		for ($index = 0; $index -lt $sourceSerials.Count; $index++) {
			$sourceSerial = $sourceSerials[$index]
			$mappedTargets = @(Get-ResolvedLinkTargetsForRule -Rule $rule -SourceIndex $index -TargetSerials $orderedTargetSerials)
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
		[hashtable]$TargetSerialMap
	)
	$serials = @(Resolve-DriveStepSerials -Step $Step -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
	if ($serials.Count -le 0) {
		return $null
	}
	$serialFormat = [string](Get-OptionalProperty -Object $Step -Name "serialFormat" -DefaultValue "slash_list")
	$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
	$endpoint = [string](Get-OptionalProperty -Object $Step -Name "endpoint" -DefaultValue "triggerSource")
	$endpointPath = Resolve-DriveInputEndpointCommandPath -Endpoint $endpoint
	$totalTicks = [int](Get-OptionalProperty -Object $Step -Name "totalTicks" -DefaultValue ([int]$CaseConfig.drive.totalTicks))
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
		[int]$TickMillis
	)
	$totalTicks = [int]$CaseConfig.drive.totalTicks
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
							-TargetSerialMap $TargetSerialMap
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
							-TargetSerialMap $TargetSerialMap
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
