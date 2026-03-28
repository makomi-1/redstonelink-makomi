<#
.SYNOPSIS
bench suite 模块：suite 主入口与 per-case 编排流程。
#>

if (-not (Get-Command Get-MatrixConfig -ErrorAction SilentlyContinue)) {
	. (Resolve-Path (Join-Path $PSScriptRoot "Bench.Matrix.ps1"))
}

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
	throw "ServerRoot is required."
}
if ([string]::IsNullOrWhiteSpace($TemplateWorldPath)) {
	throw "TemplateWorldPath is required."
}
if ($BuildBeforeSyncLatestModJar -and -not ($SyncLatestModJar -or $SyncLatestClientModJar)) {
	throw "BuildBeforeSyncLatestModJar requires SyncLatestModJar or SyncLatestClientModJar."
}

$rconPasswordSecure = Convert-PasswordInputToSecureString -SecretInput $RconSecret
$rconPasswordPlainText = Convert-SecureStringToPlainText -Password $rconPasswordSecure
if ([string]::IsNullOrWhiteSpace($rconPasswordPlainText)) {
	throw "RconPassword is required."
}
if ([string]::IsNullOrWhiteSpace($ServerPropertiesPath)) {
	throw "ServerPropertiesPath is required."
}

$resolvedSuite = Resolve-SuiteEntries `
	-SuiteConfigPath $SuitePath `
	-RequestedCaseIds $CaseIds `
	-DefaultBenchAction $BenchAction `
	-DefaultMatrixPath $MatrixPath
$suiteEntries = @($resolvedSuite.entries)
$entryIds = @($suiteEntries | ForEach-Object { [string]$_.entryId })
$resolvedCaseIds = @($suiteEntries | ForEach-Object { [string]$_.caseId })
$suiteTimestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$suiteOutputDirectory = New-DirectoryIfMissing -Path (Join-Path $SuiteResultsDir $suiteTimestamp)
$benchScriptPath = Join-Path $PSScriptRoot "..\run-bench.ps1"
$matrixCache = @{}
$serverRootFullPath = [System.IO.Path]::GetFullPath($ServerRoot)
$templateWorldFullPath = [System.IO.Path]::GetFullPath($TemplateWorldPath)
$serverPropertiesFullPath = [System.IO.Path]::GetFullPath($ServerPropertiesPath)
$serverConfigFullPath = Join-Path $serverRootFullPath "config\redstonelink-server.properties"
$gradleWrapperFullPath = Resolve-PathFromBase -BaseDirectory $repoRoot -CandidatePath $GradleWrapperPath
$serverModsDirectoryPath = if ([string]::IsNullOrWhiteSpace($ServerModsDir)) {
	Join-Path $serverRootFullPath "mods"
} else {
	Resolve-PathFromBase -BaseDirectory $serverRootFullPath -CandidatePath $ServerModsDir
}
$caseWorldsRootPath = Get-CaseWorldsRootPath -ServerRootPath $serverRootFullPath -DirectoryName $caseWorldsDirectoryName
$originalServerPropertiesText = Read-Utf8Text -Path $serverPropertiesFullPath
$originalServerConfigText = if (Test-Path -LiteralPath $serverConfigFullPath -PathType Leaf) {
	Read-Utf8Text -Path $serverConfigFullPath
} else {
	$null
}

if ($DeleteCaseWorldOnSuccess -and @($suiteEntries | Where-Object {
	-not [string]::IsNullOrWhiteSpace([string]$_.reuseWorldFrom)
}).Count -gt 0) {
	throw "DeleteCaseWorldOnSuccess is not supported when suite entries reuse an earlier world."
}

if (Test-RconAlreadyReachable -ServerHost $RconHost -Port $RconPort -Password $rconPasswordSecure) {
	throw "RCON is already reachable before suite start. Stop the dedicated server first to ensure each case loads its own fresh world."
}

$modSyncSummary = [ordered]@{
	syncLatestModJar = [bool]$SyncLatestModJar
	syncLatestClientModJar = [bool]$SyncLatestClientModJar
	buildBeforeSyncLatestModJar = [bool]$BuildBeforeSyncLatestModJar
	buildTask = if ($BuildBeforeSyncLatestModJar) { $BuildTask } else { $null }
	gradleWrapperPath = if ($BuildBeforeSyncLatestModJar) { $gradleWrapperFullPath } else { $null }
	requestedModJarPath = if ([string]::IsNullOrWhiteSpace($ModJarPath)) { $null } else { $ModJarPath }
	serverModsDir = if ($SyncLatestModJar) { $serverModsDirectoryPath } else { $null }
	clientModsDir = $null
	sourceJarPath = $null
	copiedJarPath = $null
	clientCopiedJarPath = $null
	removedServerJars = @()
	removedClientJars = @()
}
if ($BuildBeforeSyncLatestModJar) {
	Invoke-GradleBuildTask -RepoRootPath $repoRoot -GradleWrapperFilePath $gradleWrapperFullPath -TaskName $BuildTask
}
if ($SyncLatestModJar -or $SyncLatestClientModJar) {
	$localModJarPath = Resolve-LocalRuntimeModJarPath -RepoRootPath $repoRoot -ExplicitModJarPath $ModJarPath
	$modSyncSummary.sourceJarPath = $localModJarPath
	if ($SyncLatestModJar) {
		$serverModSyncResult = Sync-ServerModJar -SourceJarPath $localModJarPath -TargetModsDirectoryPath $serverModsDirectoryPath
		$modSyncSummary.copiedJarPath = $serverModSyncResult.copiedJarPath
		$modSyncSummary.removedServerJars = $serverModSyncResult.removedServerJars
		Write-Host "[BenchSuite] Synced server mod jar -> $($modSyncSummary.copiedJarPath)"
	}
	if ($SyncLatestClientModJar) {
		$benchClientInstanceRootPath = Resolve-BenchClientInstanceRootPath -RepoRootPath $repoRoot
		$clientModsDirectoryPath = Resolve-BenchClientModsDirectoryPath -BenchClientInstanceRootPath $benchClientInstanceRootPath
		$clientModSyncResult = Sync-ClientModJar -SourceJarPath $localModJarPath -TargetModsDirectoryPath $clientModsDirectoryPath
		$modSyncSummary.clientModsDir = $clientModsDirectoryPath
		$modSyncSummary.clientCopiedJarPath = $clientModSyncResult.copiedJarPath
		$modSyncSummary.removedClientJars = $clientModSyncResult.removedServerJars
		Write-Host "[BenchSuite] Synced client mod jar -> $($modSyncSummary.clientCopiedJarPath)"
	}
}

$suiteSummary = [ordered]@{
	suiteTimestamp = $suiteTimestamp
	suiteSource = $resolvedSuite.source
	suitePath = $resolvedSuite.suiteConfigPath
	suiteDescription = $resolvedSuite.description
	benchAction = $BenchAction
	serverWindowMode = $ServerWindowMode
	serverPriorityClass = if ([string]::IsNullOrWhiteSpace([string]$ServerPriorityClass)) { $null } else { ([string]$ServerPriorityClass).Trim() }
	serverRoot = $serverRootFullPath
	serverPropertiesPath = $serverPropertiesFullPath
	serverConfigPath = if ($null -eq $originalServerConfigText) { $null } else { $serverConfigFullPath }
	templateWorldPath = $templateWorldFullPath
	caseWorldsRootPath = $caseWorldsRootPath
	modSync = $modSyncSummary
	entryIds = $entryIds
	caseIds = $resolvedCaseIds
	startedAt = (Get-Date).ToString("s")
	results = @()
	restoredServerProperties = $false
	restoredServerConfig = ($null -eq $originalServerConfigText)
	benchClient = $null
	benchClientRequested = [bool]$script:BenchAutoStartClient
	benchClientStartAttempted = $false
	benchClientStartSucceeded = $false
	benchClientStartError = $null
}

Write-Host "[BenchSuite] Suite: $suiteTimestamp"
Write-Host "[BenchSuite] Server root: $serverRootFullPath"
Write-Host "[BenchSuite] Case worlds root: $caseWorldsRootPath"
Write-Host "[BenchSuite] Template world: $templateWorldFullPath"
if (-not [string]::IsNullOrWhiteSpace([string]$resolvedSuite.suiteConfigPath)) {
	Write-Host "[BenchSuite] Suite path: $($resolvedSuite.suiteConfigPath)"
}
Write-Host "[BenchSuite] Entries: $($entryIds -join ', ')"
if ($script:BenchAutoStartClient) {
	Write-Host "[BenchSuite] Bench client requested: true player=$script:BenchClientPlayerName instanceRoot=$script:BenchClientInstanceRoot"
} else {
	Write-Host "[BenchSuite] Bench client requested: false"
}

$completedEntries = @{}
$benchClientSession = $null

try {
	for ($index = 0; $index -lt $suiteEntries.Count; $index++) {
		$entry = $suiteEntries[$index]
		$entryId = [string]$entry.entryId
		$caseId = [string]$entry.caseId
		$entryBenchAction = [string]$entry.benchAction
		$entryMatrixPath = [string]$entry.matrixPath
		$entryTemplateWorldPath = [string]$entry.templateWorldPath
		$reuseWorldFrom = [string]$entry.reuseWorldFrom
		$compareSerialsTo = [string]$entry.compareSerialsTo
		$entryServerConfigOverrides = Convert-OptionalObjectToOrderedMap -Object $entry.serverConfigOverrides
		$resolvedTemplateWorldPath = if ([string]::IsNullOrWhiteSpace($entryTemplateWorldPath)) {
			$templateWorldFullPath
		} else {
			[System.IO.Path]::GetFullPath($entryTemplateWorldPath)
		}
		if (-not $matrixCache.ContainsKey($entryMatrixPath)) {
			$matrixCache[$entryMatrixPath] = Get-MatrixConfig -Path $entryMatrixPath
		}
		$entryMatrix = $matrixCache[$entryMatrixPath]
		$entryCaseConfig = Get-CaseConfig -Matrix $entryMatrix -Id $caseId
		$worldName = $null
		$worldLevelName = $null
		if ([string]::IsNullOrWhiteSpace($reuseWorldFrom)) {
			$worldName = New-CaseWorldName -Prefix $CaseWorldPrefix -CaseId $entryId -Index ($index + 1) -SuiteTimestamp $suiteTimestamp
			$worldLevelName = Get-CaseWorldLevelName -DirectoryName $caseWorldsDirectoryName -WorldName $worldName
		}
		$caseRecord = New-SuiteCaseRecord `
			-Entry $entry `
			-CaseConfig $entryCaseConfig `
			-WorldName $worldName `
			-WorldLevelName $worldLevelName `
			-WorldReuseSource $reuseWorldFrom `
			-TemplateWorldPath $resolvedTemplateWorldPath
		$serverProcess = $null
		$worldPath = $null
		try {
			if ([string]::IsNullOrWhiteSpace($reuseWorldFrom)) {
				$worldPath = Copy-TemplateWorld -TemplatePath $resolvedTemplateWorldPath -CaseWorldsRootPath $caseWorldsRootPath -WorldName $worldName
				$caseRecord.worldPath = $worldPath
				Write-Host "[BenchSuite] Entry $entryId -> case $caseId -> world $worldName template=$resolvedTemplateWorldPath"
			} else {
				if (-not $completedEntries.ContainsKey($reuseWorldFrom)) {
					throw "Entry '$entryId' references unknown reuseWorldFrom entry: $reuseWorldFrom"
				}
				$reusedRecord = $completedEntries[$reuseWorldFrom]
				if ($reusedRecord.status -ne "success") {
					throw "Entry '$entryId' cannot reuse world from failed entry: $reuseWorldFrom"
				}
				$worldPath = [string]$reusedRecord.worldPath
				$worldName = [string]$reusedRecord.worldName
				$worldLevelName = [string]$reusedRecord.worldLevelName
				$caseRecord.worldPath = $worldPath
				$caseRecord.worldName = $worldName
				$caseRecord.worldLevelName = $worldLevelName
				Write-Host "[BenchSuite] Entry $entryId -> case $caseId -> reuse world from $reuseWorldFrom ($worldName)"
			}

			Set-ServerPropertyValue -Path $serverPropertiesFullPath -Key "level-name" -Value $worldLevelName
			if ($null -ne $originalServerConfigText) {
				Restore-ExactFileText -Path $serverConfigFullPath -Text $originalServerConfigText
				if ($entryServerConfigOverrides.Count -gt 0) {
					Set-PropertiesFileValues -Path $serverConfigFullPath -Properties $entryServerConfigOverrides
					Write-Host "[BenchSuite] Applied server config overrides for $($entryId): $($entryServerConfigOverrides.Keys -join ', ')"
				}
			} elseif ($entryServerConfigOverrides.Count -gt 0) {
				throw "Suite entry '$entryId' requested serverConfigOverrides but config file was not found: $serverConfigFullPath"
			}
			$serverProcess = Start-DedicatedServerProcess -WorkingDirectory $serverRootFullPath -Command $ServerStartCommand -WindowMode $ServerWindowMode -PriorityClass $ServerPriorityClass
			$caseRecord.serverPid = $serverProcess.Id
			if ($serverProcess.PSObject.Properties.Name -contains "launcherProcessId") {
				$caseRecord.serverLauncherPid = [int]$serverProcess.launcherProcessId
			}
			if ($serverProcess.PSObject.Properties.Name -contains "trackedProcessKind") {
				$caseRecord.serverTrackedProcessKind = [string]$serverProcess.trackedProcessKind
			}
			if ($serverProcess.PSObject.Properties.Name -contains "priorityResult") {
				$caseRecord.serverPriority = $serverProcess.priorityResult
			}
			$serverPriorityApplied = if (
				($serverProcess.PSObject.Properties.Name -contains "priorityResult") -and
				($null -ne $serverProcess.priorityResult) -and
				($serverProcess.priorityResult.Contains("applied"))
			) {
				[string]$serverProcess.priorityResult.applied
			} else {
				"<none>"
			}
			$serverLauncherPidText = if ($serverProcess.PSObject.Properties.Name -contains "launcherProcessId") {
				[string]$serverProcess.launcherProcessId
			} else {
				"<same>"
			}
			$serverTrackedProcessKindText = if ($serverProcess.PSObject.Properties.Name -contains "trackedProcessKind") {
				[string]$serverProcess.trackedProcessKind
			} else {
				"launcherProcess"
			}
			Write-Host "[BenchSuite] Server process started trackedPid=$($serverProcess.Id) launcherPid=$serverLauncherPidText kind=$serverTrackedProcessKindText priority=$serverPriorityApplied"
			Write-Host "[BenchSuite] Waiting RCON ready for entry $entryId ($caseId)"
			Wait-RconReady -ServerHost $RconHost -Port $RconPort -Password $rconPasswordSecure -TimeoutMs $StartupTimeoutMs -PollIntervalMs $StartupPollIntervalMs
			Write-Host "[BenchSuite] RCON ready for entry $entryId ($caseId)"
			if ($script:BenchAutoStartClient -and $null -eq $benchClientSession) {
				# suite 外部客户端延后到首个 dedicated server 真正 ready 后再启动，
				# 避免启动器自带 --server 时在服务端未就绪阶段提前连服。
				$suiteSummary.benchClientStartAttempted = $true
				Write-Host "[BenchSuite] Starting bench client after server ready. player=$script:BenchClientPlayerName instanceRoot=$script:BenchClientInstanceRoot"
				try {
					$benchClientSession = Start-BenchClientAutomationSession -RepoRootPath $repoRoot -SkipModSync
					if ($null -eq $benchClientSession) {
						throw "AutoStartBenchClient was requested but Start-BenchClientAutomationSession returned null."
					}
					$suiteSummary.benchClientStartSucceeded = $true
					$suiteSummary.benchClientStartError = $null
					$suiteSummary.benchClient = Get-BenchClientSessionSummary -Session $benchClientSession
					Write-Host "[BenchSuite] Bench client session ready trackedPid=$($benchClientSession.trackedProcessId) kind=$($benchClientSession.trackedProcessKind)"
				} catch {
					$suiteSummary.benchClientStartSucceeded = $false
					$suiteSummary.benchClientStartError = $_.Exception.Message
					Write-Host "[BenchSuite] Bench client start failed: $($suiteSummary.benchClientStartError)"
					throw
				}
			}

			$resultsDirPath = Get-ResultsDirectoryPath -RepoRootPath $repoRoot -Matrix $entryMatrix
			$beforeSnapshot = Get-ResultFileSnapshot -ResultsDirPath $resultsDirPath
			$startedAtUtc = [datetime]::UtcNow
			$benchArgs = @{
				Action = $entryBenchAction
				CaseId = $caseId
				MatrixPath = $entryMatrixPath
				SavePath = $worldPath
				RconHost = $RconHost
				RconPort = $RconPort
				RconPassword = $rconPasswordPlainText
				PlayerReadyTimeoutMs = $PlayerReadyTimeoutMs
				PlayerReadyPollIntervalMs = $PlayerReadyPollIntervalMs
				PlayerReadyProbeCommand = $PlayerReadyProbeCommand
			}
			if (-not [string]::IsNullOrWhiteSpace($AsPlayer)) {
				$benchArgs.AsPlayer = $AsPlayer
			}
			if (@($PlayerSetupCommands).Count -gt 0) {
				$benchArgs.PlayerSetupCommands = @($PlayerSetupCommands)
			}
			if ($AutoTeleportPlayerToObservationPoint) {
				$benchArgs.AutoTeleportPlayerToObservationPoint = $true
			}
			if (-not [string]::IsNullOrWhiteSpace($SparkActivityPath)) {
				$benchArgs.SparkActivityPath = $SparkActivityPath
			}
			if (-not [string]::IsNullOrWhiteSpace($AsPlayer)) {
				Write-Host "[BenchSuite] Entering bench player-ready wait. entry=$entryId asPlayer=$AsPlayer timeoutMs=$PlayerReadyTimeoutMs"
			}

			$runException = $null
			try {
				& $benchScriptPath @benchArgs
			} catch {
				$runException = $_.Exception
			}

			$resultPath = Find-NewBenchResultFile -ResultsDirPath $resultsDirPath -BeforeSnapshot $beforeSnapshot -CaseId $caseId -StartedAtUtc $startedAtUtc
			if ($null -ne $resultPath) {
				$benchSummary = Read-BenchResultSummary -ResultPath $resultPath
				Set-SuiteCaseRecordBenchSummary -CaseRecord $caseRecord -BenchSummary $benchSummary
			}

			if ($null -ne $runException) {
				$caseRecord.status = "failed"
				$caseRecord.error = $runException.Message
				if ($null -eq $resultPath) {
					$caseRecord.resultMissing = $true
				}
				Write-Host "[BenchSuite] Case failed: $caseId"
				Write-Host "[BenchSuite] Error: $($caseRecord.error)"
				if (-not $ContinueOnFailure) {
					throw $runException
				}
				continue
			}

			if ($null -eq $resultPath) {
				throw "Bench result JSON not found for case: $caseId"
			}
			if (-not [string]::IsNullOrWhiteSpace($compareSerialsTo)) {
				if (-not $completedEntries.ContainsKey($compareSerialsTo)) {
					throw "Entry '$entryId' references unknown compareSerialsTo entry: $compareSerialsTo"
				}
				$compareRecord = $completedEntries[$compareSerialsTo]
				$compareResultPath = [string]$compareRecord.resultPath
				if ([string]::IsNullOrWhiteSpace($compareResultPath)) {
					throw "Entry '$entryId' cannot compare serials because '$compareSerialsTo' has no resultPath."
				}
				$serialComparison = Compare-BenchResultSerials -ExpectedResultPath $compareResultPath -ActualResultPath $resultPath
				$caseRecord.serialComparison = $serialComparison
				$caseRecord.serialCompare = $serialComparison
				if (-not $serialComparison.passed) {
					throw "Serial comparison failed for entry '$entryId' against '$compareSerialsTo'."
				}
			}
			$caseRecord.status = "success"
		} catch {
			$caseRecord.status = "failed"
			$caseRecord.error = $_.Exception.Message
			Write-Host "[BenchSuite] Case failed: $entryId ($caseId)"
			Write-Host "[BenchSuite] Error: $($caseRecord.error)"
			if (-not $ContinueOnFailure) {
				throw
			}
		} finally {
			if ($null -ne $serverProcess) {
				try {
					if (-not $serverProcess.HasExited) {
						Stop-ServerByRcon -ServerHost $RconHost -Port $RconPort -Password $rconPasswordSecure
						Wait-ProcessExit -Process $serverProcess -TimeoutMs $ShutdownTimeoutMs -PollIntervalMs $ShutdownPollIntervalMs
					}
				} catch {
					$caseRecord.stopError = $_.Exception.Message
					Write-Host "[BenchSuite] Stop server failed for case ${caseId}: $($caseRecord.stopError)"
					if (-not $ContinueOnFailure -and $caseRecord.status -eq "success") {
						throw
					}
				}
			}

			if ($DeleteCaseWorldOnSuccess -and $caseRecord.status -eq "success" -and -not [string]::IsNullOrWhiteSpace([string]$worldPath) -and (Test-Path -LiteralPath $worldPath)) {
				Remove-Item -LiteralPath $worldPath -Recurse -Force
				$caseRecord.worldDeleted = $true
			}
			if ($null -ne $originalServerConfigText) {
				Restore-ExactFileText -Path $serverConfigFullPath -Text $originalServerConfigText
			}

			$caseRecord.completedAt = (Get-Date).ToString("s")
			$completedEntries[$entryId] = [pscustomobject]$caseRecord
			$suiteSummary.results += $caseRecord
		}
	}
} finally {
	if ($null -ne $benchClientSession) {
		$suiteSummary.benchClient = Get-BenchClientSessionSummary -Session $benchClientSession
	}
	try {
		Stop-BenchClientAutomationSession -Session $benchClientSession
	} catch {
		$suiteSummary.benchClientStopError = $_.Exception.Message
		Write-Host "[BenchSuite] Stop bench client failed: $($suiteSummary.benchClientStopError)"
	}
	Write-Utf8NoBomFile -Path $serverPropertiesFullPath -Content $originalServerPropertiesText
	$suiteSummary.restoredServerProperties = $true
	if ($null -ne $originalServerConfigText) {
		Restore-ExactFileText -Path $serverConfigFullPath -Text $originalServerConfigText
		$suiteSummary.restoredServerConfig = $true
	}
	$suiteSummary.completedAt = (Get-Date).ToString("s")
	$suiteSummary.summaryPath = (Join-Path $suiteOutputDirectory "summary.json")
	Write-SuiteSummaryJson -OutputPath $suiteSummary.summaryPath -SummaryObject $suiteSummary | Out-Null
	Write-Host "[BenchSuite] Summary -> $($suiteSummary.summaryPath)"
}
