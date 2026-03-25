<#
.SYNOPSIS
bench 模块：单 case 入口分发与主执行流程。
#>

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
