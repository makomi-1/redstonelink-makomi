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
