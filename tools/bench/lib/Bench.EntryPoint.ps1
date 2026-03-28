<#
.SYNOPSIS
bench 模块：单 case 入口分发与主执行流程。
#>

if (-not (Get-Command Get-MatrixConfig -ErrorAction SilentlyContinue)) {
	. (Resolve-Path (Join-Path $PSScriptRoot "Bench.Matrix.ps1"))
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
		$benchClientSession = $null
		try {
			$benchClientSession = Start-BenchClientAutomationSession -RepoRootPath $repoRoot
			if (-not $DryRun) {
				$connection = Open-RconConnection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			}

			if (-not $DryRun) {
				$connection = Invoke-ReloadAndReconnect -Connection $connection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			} else {
				Invoke-RconCommand -Connection $connection -Command "reload" | Out-Null
			}
			Invoke-PrepareFunctions -Connection $connection -Matrix $matrix
			$caseStageStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
			$playerContextExecution = Ensure-PlayerContextReadyAndSetup -Connection $connection
			Invoke-BenchClientRefocus -Session $benchClientSession -StageName "player_ready" | Out-Null
			Ensure-CaseChunksLoaded -Connection $connection -CaseConfig $caseConfig
			$shouldClearArena = [bool](Get-OptionalProperty -Object $caseConfig -Name "clearArena" -DefaultValue $true)
			if ($shouldClearArena) {
				Clear-Arena -Connection $connection -Arena $caseConfig.arena
			}

			$targetPlacement = Place-NodeGroup -Connection $connection -Group $caseConfig.targets
			$targetPositions = @($targetPlacement.positions)
			$targetSerialMap = $targetPlacement.serialMap
			$targetSerials = @(Get-SerialListFromMap $targetSerialMap)
			$targetPlacementElapsedMs = [long](Get-OptionalProperty -Object $targetPlacement -Name "elapsedMs" -DefaultValue 0L)

			$sourcePositionGroups = @{}
			$sourceSerialMaps = @{}
			$sourceSerialResolution = [ordered]@{}
			$sourcePlacementSummary = [ordered]@{}
			$placementElapsedMs = $targetPlacementElapsedMs
			foreach ($group in $caseConfig.sources) {
				$groupPlacement = Place-NodeGroup -Connection $connection -Group $group
				$positions = @($groupPlacement.positions)
				$sourcePositionGroups[[string]$group.id] = $positions
				$sourceSerialMaps[[string]$group.id] = $groupPlacement.serialMap
				$sourceSerialResolution[[string]$group.id] = $groupPlacement.serialResolution
				$groupPlacementElapsedMs = [long](Get-OptionalProperty -Object $groupPlacement -Name "elapsedMs" -DefaultValue 0L)
				$placementElapsedMs += $groupPlacementElapsedMs
				$sourcePlacementSummary[[string]$group.id] = [ordered]@{
					elapsedMs = $groupPlacementElapsedMs
					placement = $groupPlacement.placement
				}
			}
			$placementSummary = [ordered]@{
				elapsedMs = $placementElapsedMs
				target = [ordered]@{
					elapsedMs = $targetPlacementElapsedMs
					placement = $targetPlacement.placement
				}
				sources = $sourcePlacementSummary
			}

			$linkCommandBuildStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
			$linkCommandPlans = Build-LinkCommands -CaseConfig $caseConfig -SourceSerialMaps $sourceSerialMaps -TargetSerialMap $targetSerialMap
			$linkCommandBuildStopwatch.Stop()
			$linkExecutionStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
			$linkExecution = Invoke-LinkCommandPlans -Connection $connection -LinkCommandPlans $linkCommandPlans
			$linkExecutionStopwatch.Stop()
			$linkCommands = @($linkExecution.executedCommands)
			$linkOperations = @($linkExecution.operations)
			$linkCommandCount = [int](Get-OptionalProperty -Object $linkExecution -Name "executedCommandCount" -DefaultValue 0)
			$linkExecutionElapsedMs = $linkExecutionStopwatch.ElapsedMilliseconds
			$linkBuildSummary = [ordered]@{
				planCount = [int](Get-OptionalProperty -Object $linkExecution -Name "planCount" -DefaultValue 0)
				commandCount = $linkCommandCount
				structuredPlanCount = [int](Get-OptionalProperty -Object $linkExecution -Name "structuredPlanCount" -DefaultValue 0)
				classicPlanCount = [int](Get-OptionalProperty -Object $linkExecution -Name "classicPlanCount" -DefaultValue 0)
				fallbackPlanCount = [int](Get-OptionalProperty -Object $linkExecution -Name "fallbackPlanCount" -DefaultValue 0)
				commandTextBuildElapsedMs = $linkCommandBuildStopwatch.ElapsedMilliseconds
				commandExecuteElapsedMs = $linkExecutionElapsedMs
				elapsedMs = ($linkCommandBuildStopwatch.ElapsedMilliseconds + $linkExecutionElapsedMs)
				avgCommandMs = if ($linkCommandCount -gt 0) {
					[Math]::Round(($linkExecutionStopwatch.Elapsed.TotalMilliseconds / $linkCommandCount), 2)
				} else {
					0.0
				}
			}

			$observationPoint = Get-ObservationPointForPlacedNodes -TargetPositions $targetPositions -SourcePositionGroups $sourcePositionGroups
			$observationTeleport = Invoke-PlayerObservationTeleport -Connection $connection -ObservationPoint $observationPoint
			Invoke-BenchClientRefocus -Session $benchClientSession -StageName "observation_teleport" -DelayMs 250 | Out-Null
			$auditBefore = Invoke-RconCommand -Connection $connection -Command (Wrap-WithPlayerContext "redstonelink audit summary csv") -Silent
			$settleTicks = [int]$matrix.defaults.settleTicks
			if ($settleTicks -gt 0) {
				Start-Sleep -Milliseconds ($settleTicks * [int]$matrix.defaults.tickMillis)
			}

			$driveExecution = $null
			$inputCleanup = $null
			$caseUsesInputDrive = Test-CaseUsesDriveInput -CaseConfig $caseConfig
			$caseUsesOnlyInputDrive = Test-CaseUsesOnlyDriveInput -CaseConfig $caseConfig
			$performanceWindow = Get-CasePerformanceWindow -CaseConfig $caseConfig -Matrix $matrix
			$sparkStart = $null
			$sparkStop = $null
			$performanceWindowWaits = $null
			$driveStartElapsedMs = [long]$caseStageStopwatch.ElapsedMilliseconds
			$driveExecutionStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
			try {
				Invoke-BenchClientRefocus -Session $benchClientSession -StageName "before_drive" | Out-Null
				if (($null -ne $performanceWindow) -and $caseUsesOnlyInputDrive) {
					$performanceWindowStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
					$driveExecution = Invoke-InputDriveSchedule `
						-Connection $connection `
						-CaseConfig $caseConfig `
						-SourceSerialMaps $sourceSerialMaps `
						-TargetSerialMap $targetSerialMap `
						-TotalTicks ([int]$performanceWindow.totalTicks)

					$warmupWait = $null
					$measureWait = $null
					$cooldownWait = $null
					if ([int]$performanceWindow.warmupTicks -gt 0) {
						$warmupWait = Wait-ServerTicks -Connection $connection -Ticks ([int]$performanceWindow.warmupTicks)
					}
					$sparkStart = Start-SparkCapture `
						-Connection $connection `
						-SparkDefaults $matrix.defaults.spark `
						-CaseName $caseConfig.id `
						-WorldPath $SavePath
					if ([int]$performanceWindow.measureTicks -gt 0) {
						$measureWait = Wait-ServerTicks -Connection $connection -Ticks ([int]$performanceWindow.measureTicks)
					}
					if ([int]$performanceWindow.cooldownTicks -gt 0) {
						$cooldownWait = Wait-ServerTicks -Connection $connection -Ticks ([int]$performanceWindow.cooldownTicks)
					}
					$performanceWindowWaits = [ordered]@{
						warmup = $warmupWait
						measure = $measureWait
						cooldown = $cooldownWait
					}
					$performanceWindowStopwatch.Stop()
					$performanceWindow["elapsedMs"] = $performanceWindowStopwatch.ElapsedMilliseconds
					$driveExecution["performanceWindow"] = $performanceWindow
					$driveExecution["performanceWindowWaits"] = $performanceWindowWaits
				} else {
					$sparkStart = Start-SparkCapture `
						-Connection $connection `
						-SparkDefaults $matrix.defaults.spark `
						-CaseName $caseConfig.id `
						-WorldPath $SavePath
					$driveExecution = Invoke-DriveSchedule `
						-Connection $connection `
						-CaseConfig $caseConfig `
						-SourcePositionGroups $sourcePositionGroups `
						-SourceSerialMaps $sourceSerialMaps `
						-TargetSerialMap $targetSerialMap `
						-TickMillis ([int]$matrix.defaults.tickMillis) `
						-TotalTicksOverride (Get-CaseDriveTotalTicks -CaseConfig $caseConfig -Matrix $matrix)
					if ($null -ne $performanceWindow) {
						$performanceWindow["elapsedMs"] = $null
						$driveExecution["performanceWindow"] = $performanceWindow
					}
				}
			}
			finally {
				try {
					if ($null -ne $sparkStart) {
						$sparkStop = Stop-SparkCapture `
							-Connection $connection `
							-SparkDefaults $matrix.defaults.spark `
							-CaseName $caseConfig.id `
							-ActivityPath ([string](Get-OptionalProperty -Object $sparkStart -Name "activityPath" -DefaultValue ""))
					}
				}
				finally {
					if ($caseUsesInputDrive) {
						$inputCleanup = Clear-DriveInputJobs -Connection $connection
					}
				}
			}
			$driveExecutionStopwatch.Stop()
			if ($null -ne $driveExecution) {
				$driveExecution["startElapsedMs"] = $driveStartElapsedMs
				$driveExecution["elapsedMs"] = $driveExecutionStopwatch.ElapsedMilliseconds
			}
			$auditAfter = Invoke-RconCommand -Connection $connection -Command (Wrap-WithPlayerContext "redstonelink audit summary csv") -Silent

			$result = [ordered]@{
				caseId = $caseConfig.id
				description = $caseConfig.description
				sourceSerials = $sourceSerialMaps
				targetSerials = $targetSerialMap
				placement = $placementSummary
				serialResolution = [ordered]@{
					target = $targetPlacement.serialResolution
					sources = $sourceSerialResolution
				}
				linkBuild = $linkBuildSummary
				linkCommands = $linkCommands
				linkOperations = @($linkOperations)
				spark = [ordered]@{
					start = $sparkStart
					stop = $sparkStop
					performanceWindow = $performanceWindow
				}
				playerContext = $playerContextExecution
				observationTeleport = $observationTeleport
				benchClient = (Get-BenchClientSessionSummary -Session $benchClientSession)
				drive = $driveExecution
				inputCleanup = $inputCleanup
				audit = [ordered]@{
					before = $auditBefore
					after = $auditAfter
				}
			}
			Write-ResultJson -RepoRoot $repoRoot -RelativeResultsDir ([string]$matrix.defaults.resultsDir) -CaseId $caseConfig.id -ResultObject $result | Out-Null
		}
		finally {
			Close-RconConnection -Connection $connection
			Stop-BenchClientAutomationSession -Session $benchClientSession
		}
		break
	}
	"RunFunctionalCase" {
		$caseConfig = Get-CaseConfig -Matrix $matrix -Id $CaseId
		Install-BenchDatapack -SourcePath $datapackSource -WorldPath $SavePath
		Show-CaseSummary -CaseConfig $caseConfig
		Assert-RunCasePlayerContext

		$connection = $null
		$benchClientSession = $null
		try {
			$benchClientSession = Start-BenchClientAutomationSession -RepoRootPath $repoRoot
			if (-not $DryRun) {
				$connection = Open-RconConnection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			}

			if (-not $DryRun) {
				$connection = Invoke-ReloadAndReconnect -Connection $connection -ServerHost $RconHost -Port $RconPort -Password $RconPassword
			} else {
				Invoke-RconCommand -Connection $connection -Command "reload" | Out-Null
			}
			Invoke-PrepareFunctions -Connection $connection -Matrix $matrix
			$playerContextExecution = Ensure-PlayerContextReadyAndSetup -Connection $connection
			Invoke-BenchClientRefocus -Session $benchClientSession -StageName "player_ready" | Out-Null
			Ensure-CaseChunksLoaded -Connection $connection -CaseConfig $caseConfig
			$shouldClearArena = [bool](Get-OptionalProperty -Object $caseConfig -Name "clearArena" -DefaultValue $true)
			if ($shouldClearArena) {
				Clear-Arena -Connection $connection -Arena $caseConfig.arena
			}

			$targetPlacement = Place-NodeGroup -Connection $connection -Group $caseConfig.targets
			$targetPositions = @($targetPlacement.positions)
			$targetSerialMap = $targetPlacement.serialMap
			$targetSerials = @(Get-SerialListFromMap $targetSerialMap)
			$targetPlacementElapsedMs = [long](Get-OptionalProperty -Object $targetPlacement -Name "elapsedMs" -DefaultValue 0L)

			$sourcePositionGroups = @{}
			$sourceSerialMaps = @{}
			$sourceSerialResolution = [ordered]@{}
			$sourcePlacementSummary = [ordered]@{}
			$placementElapsedMs = $targetPlacementElapsedMs
			foreach ($group in $caseConfig.sources) {
				$groupPlacement = Place-NodeGroup -Connection $connection -Group $group
				$positions = @($groupPlacement.positions)
				$sourcePositionGroups[[string]$group.id] = $positions
				$sourceSerialMaps[[string]$group.id] = $groupPlacement.serialMap
				$sourceSerialResolution[[string]$group.id] = $groupPlacement.serialResolution
				$groupPlacementElapsedMs = [long](Get-OptionalProperty -Object $groupPlacement -Name "elapsedMs" -DefaultValue 0L)
				$placementElapsedMs += $groupPlacementElapsedMs
				$sourcePlacementSummary[[string]$group.id] = [ordered]@{
					elapsedMs = $groupPlacementElapsedMs
					placement = $groupPlacement.placement
				}
			}
			$placementSummary = [ordered]@{
				elapsedMs = $placementElapsedMs
				target = [ordered]@{
					elapsedMs = $targetPlacementElapsedMs
					placement = $targetPlacement.placement
				}
				sources = $sourcePlacementSummary
			}

			$linkCommandBuildStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
			$linkCommandPlans = Build-LinkCommands -CaseConfig $caseConfig -SourceSerialMaps $sourceSerialMaps -TargetSerialMap $targetSerialMap
			$linkCommandBuildStopwatch.Stop()
			$linkExecutionStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
			$linkExecution = Invoke-LinkCommandPlans -Connection $connection -LinkCommandPlans $linkCommandPlans
			$linkExecutionStopwatch.Stop()
			$linkCommands = @($linkExecution.executedCommands)
			$linkOperations = @($linkExecution.operations)
			$linkCommandCount = [int](Get-OptionalProperty -Object $linkExecution -Name "executedCommandCount" -DefaultValue 0)
			$linkExecutionElapsedMs = $linkExecutionStopwatch.ElapsedMilliseconds
			$linkBuildSummary = [ordered]@{
				planCount = [int](Get-OptionalProperty -Object $linkExecution -Name "planCount" -DefaultValue 0)
				commandCount = $linkCommandCount
				structuredPlanCount = [int](Get-OptionalProperty -Object $linkExecution -Name "structuredPlanCount" -DefaultValue 0)
				classicPlanCount = [int](Get-OptionalProperty -Object $linkExecution -Name "classicPlanCount" -DefaultValue 0)
				fallbackPlanCount = [int](Get-OptionalProperty -Object $linkExecution -Name "fallbackPlanCount" -DefaultValue 0)
				commandTextBuildElapsedMs = $linkCommandBuildStopwatch.ElapsedMilliseconds
				commandExecuteElapsedMs = $linkExecutionElapsedMs
				elapsedMs = ($linkCommandBuildStopwatch.ElapsedMilliseconds + $linkExecutionElapsedMs)
				avgCommandMs = if ($linkCommandCount -gt 0) {
					[Math]::Round(($linkExecutionStopwatch.Elapsed.TotalMilliseconds / $linkCommandCount), 2)
				} else {
					0.0
				}
			}

			$observationPoint = Get-ObservationPointForPlacedNodes -TargetPositions $targetPositions -SourcePositionGroups $sourcePositionGroups
			$observationTeleport = Invoke-PlayerObservationTeleport -Connection $connection -ObservationPoint $observationPoint
			Invoke-BenchClientRefocus -Session $benchClientSession -StageName "observation_teleport" -DelayMs 250 | Out-Null
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
				placement = $placementSummary
				serialResolution = [ordered]@{
					target = $targetPlacement.serialResolution
					sources = $sourceSerialResolution
				}
				linkBuild = $linkBuildSummary
				linkCommands = $linkCommands
				linkOperations = @($linkOperations)
				playerContext = $playerContextExecution
				observationTeleport = $observationTeleport
				benchClient = (Get-BenchClientSessionSummary -Session $benchClientSession)
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
			Stop-BenchClientAutomationSession -Session $benchClientSession
		}
		break
	}
}
