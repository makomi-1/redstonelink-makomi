<#
.SYNOPSIS
bench suite 模块：结果发现、summary 写出与 serial compare。
#>

function Get-ResultsDirectoryPath {
	param(
		[string]$RepoRootPath,
		$Matrix
	)
	return (New-DirectoryIfMissing -Path (Join-Path $RepoRootPath ([string]$Matrix.defaults.resultsDir)))
}

function Get-ResultFileSnapshot {
	param([string]$ResultsDirPath)
	$snapshot = @{}
	if (-not (Test-Path -LiteralPath $ResultsDirPath -PathType Container)) {
		return $snapshot
	}
	foreach ($file in (Get-ChildItem -LiteralPath $ResultsDirPath -Filter "*.json" -File)) {
		$snapshot[$file.FullName] = $file.LastWriteTimeUtc
	}
	return $snapshot
}

function Find-NewBenchResultFile {
	param(
		[string]$ResultsDirPath,
		[hashtable]$BeforeSnapshot,
		[string]$CaseId,
		[datetime]$StartedAtUtc
	)
	$caseSuffix = "_$CaseId.json"
	$candidates = @(Get-ChildItem -LiteralPath $ResultsDirPath -Filter "*.json" -File |
		Where-Object { $_.Name.EndsWith($caseSuffix, [System.StringComparison]::OrdinalIgnoreCase) } |
		Sort-Object LastWriteTimeUtc -Descending)

	$newFiles = @($candidates | Where-Object { -not $BeforeSnapshot.ContainsKey($_.FullName) })
	if ($newFiles.Count -gt 0) {
		return $newFiles[0].FullName
	}

	$timeBased = @($candidates | Where-Object { $_.LastWriteTimeUtc -ge $StartedAtUtc } | Sort-Object LastWriteTimeUtc -Descending)
	if ($timeBased.Count -gt 0) {
		return $timeBased[0].FullName
	}
	return $null
}

function Read-BenchResultSummary {
	param([string]$ResultPath)
	if ([string]::IsNullOrWhiteSpace($ResultPath) -or -not (Test-Path -LiteralPath $ResultPath -PathType Leaf)) {
		return $null
	}
	$parsed = Get-Content -Path $ResultPath -Encoding UTF8 -Raw | ConvertFrom-Json
	$passed = Get-OptionalPsObjectPropertyValue -Object $parsed -PropertyName "passed"
	$checks = Get-OptionalPsObjectPropertyValue -Object $parsed -PropertyName "checks"
	$failedChecks = Get-OptionalPsObjectPropertyValue -Object $parsed -PropertyName "failedChecks"
	$checksCount = if ($null -eq $checks) { $null } else { @($checks).Count }
	$failedChecksCount = if ($null -eq $failedChecks) { $null } else { @($failedChecks).Count }
	$spark = Get-OptionalPsObjectPropertyValue -Object $parsed -PropertyName "spark"
	$stop = Get-OptionalPsObjectPropertyValue -Object $spark -PropertyName "stop"
	$profilerUrl = Get-OptionalActivityResultPrimaryValue -ActivityResult (
		Get-OptionalPsObjectPropertyValue -Object $stop -PropertyName "stopCpuActivity"
	)
	$healthUrl = Get-OptionalActivityResultPrimaryValue -ActivityResult (
		Get-OptionalPsObjectPropertyValue -Object $stop -PropertyName "healthActivity"
	)
	return [ordered]@{
		caseId = [string]$parsed.caseId
		resultPath = $ResultPath
		resultKind = if ($null -ne $passed) { "functional" } else { "performance" }
		passed = $passed
		checksCount = $checksCount
		failedChecksCount = $failedChecksCount
		profilerUrl = $profilerUrl
		healthUrl = $healthUrl
	}
}

function Get-OptionalActivityResultPrimaryValue {
	param($ActivityResult)
	if ($null -eq $ActivityResult) {
		return $null
	}
	$url = Get-OptionalPsObjectPropertyValue -Object $ActivityResult -PropertyName "url"
	if (-not [string]::IsNullOrWhiteSpace([string]$url)) {
		return [string]$url
	}
	$fallbackResponse = Get-OptionalPsObjectPropertyValue -Object $ActivityResult -PropertyName "fallbackResponse"
	if (-not [string]::IsNullOrWhiteSpace([string]$fallbackResponse)) {
		return [string]$fallbackResponse
	}
	return $null
}

function Read-BenchResultJson {
	param([string]$ResultPath)
	if ([string]::IsNullOrWhiteSpace($ResultPath) -or -not (Test-Path -LiteralPath $ResultPath -PathType Leaf)) {
		throw "Bench result file not found: $ResultPath"
	}
	return (Get-Content -Path $ResultPath -Encoding UTF8 -Raw | ConvertFrom-Json)
}

function Convert-PsObjectToHashtable {
	param($Object)
	$result = @{}
	if ($null -eq $Object) {
		return $result
	}
	if ($Object -is [System.Collections.IDictionary]) {
		foreach ($entry in $Object.GetEnumerator()) {
			$result[[string]$entry.Key] = $entry.Value
		}
		return $result
	}
	foreach ($property in $Object.PSObject.Properties) {
		$result[[string]$property.Name] = $property.Value
	}
	return $result
}

function Compare-LongMapValues {
	param(
		[hashtable]$Expected,
		[hashtable]$Actual,
		[string]$Scope
	)
	$failures = New-Object System.Collections.Generic.List[object]
	$allKeys = @($Expected.Keys + $Actual.Keys | Sort-Object -Unique)
	foreach ($key in $allKeys) {
		$expectedExists = $Expected.ContainsKey($key)
		$actualExists = $Actual.ContainsKey($key)
		if (-not $expectedExists -or -not $actualExists) {
			$failures.Add([ordered]@{
				scope = $Scope
				key = $key
				reason = "missing_key"
				expectedPresent = $expectedExists
				actualPresent = $actualExists
			})
			continue
		}
		$expectedValue = [long]$Expected[$key]
		$actualValue = [long]$Actual[$key]
		if ($expectedValue -ne $actualValue) {
			$failures.Add([ordered]@{
				scope = $Scope
				key = $key
				reason = "value_mismatch"
				expected = $expectedValue
				actual = $actualValue
			})
		}
	}
	return @($failures.ToArray())
}

function Compare-BenchResultSerials {
	param(
		[string]$ExpectedResultPath,
		[string]$ActualResultPath
	)
	$expectedResult = Read-BenchResultJson -ResultPath $ExpectedResultPath
	$actualResult = Read-BenchResultJson -ResultPath $ActualResultPath
	$failures = New-Object System.Collections.Generic.List[object]

	$expectedTargets = Convert-PsObjectToHashtable -Object $expectedResult.targetSerials
	$actualTargets = Convert-PsObjectToHashtable -Object $actualResult.targetSerials
	foreach ($failure in @(Compare-LongMapValues -Expected $expectedTargets -Actual $actualTargets -Scope "targetSerials")) {
		$failures.Add($failure)
	}

	$expectedSources = Convert-PsObjectToHashtable -Object $expectedResult.sourceSerials
	$actualSources = Convert-PsObjectToHashtable -Object $actualResult.sourceSerials
	$sourceGroupNames = @($expectedSources.Keys + $actualSources.Keys | Sort-Object -Unique)
	foreach ($groupName in $sourceGroupNames) {
		$expectedGroupExists = $expectedSources.ContainsKey($groupName)
		$actualGroupExists = $actualSources.ContainsKey($groupName)
		if (-not $expectedGroupExists -or -not $actualGroupExists) {
			$failures.Add([ordered]@{
				scope = "sourceSerials"
				group = $groupName
				reason = "missing_group"
				expectedPresent = $expectedGroupExists
				actualPresent = $actualGroupExists
			})
			continue
		}
		$expectedGroup = Convert-PsObjectToHashtable -Object $expectedSources[$groupName]
		$actualGroup = Convert-PsObjectToHashtable -Object $actualSources[$groupName]
		foreach ($failure in @(Compare-LongMapValues -Expected $expectedGroup -Actual $actualGroup -Scope "sourceSerials.$groupName")) {
			$failures.Add($failure)
		}
	}

	return [ordered]@{
		passed = ($failures.Count -eq 0)
		expectedResultPath = $ExpectedResultPath
		actualResultPath = $ActualResultPath
		failures = @($failures.ToArray())
	}
}

function Write-SuiteSummaryJson {
	param(
		[string]$OutputPath,
		$SummaryObject
	)
	$jsonText = $SummaryObject | ConvertTo-Json -Depth 12
	Write-Utf8NoBomFile -Path $OutputPath -Content $jsonText
	return $OutputPath
}

function New-SuiteActivityResults {
	param(
		[string]$ProfilerUrl = $null,
		[string]$HealthUrl = $null
	)
	return [ordered]@{
		profilerUrl = if ([string]::IsNullOrWhiteSpace($ProfilerUrl)) { $null } else { $ProfilerUrl }
		healthUrl = if ([string]::IsNullOrWhiteSpace($HealthUrl)) { $null } else { $HealthUrl }
	}
}

function New-SuiteCaseRecord {
	param(
		$Entry,
		$CaseConfig,
		[string]$WorldName,
		[string]$WorldLevelName,
		[string]$WorldReuseSource,
		[string]$TemplateWorldPath = $null
	)
	$resolvedEntryId = [string]$Entry.entryId
	$resolvedMatrixCaseId = [string]$Entry.caseId
	$resolvedSummaryCaseId = [string](Get-OptionalPsObjectPropertyValue -Object $Entry -PropertyName "summaryCaseId")
	if ([string]::IsNullOrWhiteSpace($resolvedSummaryCaseId)) {
		$resolvedSummaryCaseId = $resolvedMatrixCaseId
	}
	$resolvedTitle = [string](Get-OptionalPsObjectPropertyValue -Object $Entry -PropertyName "title")
	if ([string]::IsNullOrWhiteSpace($resolvedTitle)) {
		$resolvedTitle = [string](Get-OptionalProperty -Object $CaseConfig -Name "description" -DefaultValue "")
	}
	return [ordered]@{
		entryId = $resolvedEntryId
		caseId = $resolvedSummaryCaseId
		matrixCaseId = $resolvedMatrixCaseId
		title = $resolvedTitle
		scenarioId = [string](Get-OptionalProperty -Object $CaseConfig -Name "scenarioId" -DefaultValue "")
		layer = [string](Get-OptionalProperty -Object $CaseConfig -Name "layer" -DefaultValue "")
		benchAction = [string]$Entry.benchAction
		matrixPath = [string]$Entry.matrixPath
		parameters = Convert-BenchParametersToOrderedMap -Parameters (Get-OptionalPsObjectPropertyValue -Object $Entry -PropertyName "parameters")
		worldName = $WorldName
		worldLevelName = $WorldLevelName
		worldPath = $null
		templateWorldPath = if ([string]::IsNullOrWhiteSpace($TemplateWorldPath)) { $null } else { $TemplateWorldPath }
		reuseWorldFrom = if ([string]::IsNullOrWhiteSpace($WorldReuseSource)) { $null } else { $WorldReuseSource }
		worldReuseSource = if ([string]::IsNullOrWhiteSpace($WorldReuseSource)) { $null } else { $WorldReuseSource }
		serverConfigOverrides = Convert-OptionalObjectToOrderedMap -Object (Get-OptionalPsObjectPropertyValue -Object $Entry -PropertyName "serverConfigOverrides")
		status = "pending"
		startedAt = (Get-Date).ToString("s")
		completedAt = $null
		serverPid = $null
		resultKind = $null
		checks = $null
		passed = $null
		activityResults = (New-SuiteActivityResults)
		serialCompare = $null
		serialComparison = $null
		resultPath = $null
	}
}

function Set-SuiteCaseRecordBenchSummary {
	param(
		$CaseRecord,
		$BenchSummary
	)
	if ($null -eq $CaseRecord -or $null -eq $BenchSummary) {
		return
	}
	$CaseRecord.bench = $BenchSummary
	$CaseRecord.resultPath = $BenchSummary.resultPath
	$CaseRecord.resultKind = $BenchSummary.resultKind
	$CaseRecord.checks = $BenchSummary.checksCount
	$CaseRecord.passed = $BenchSummary.passed
	$CaseRecord.activityResults = New-SuiteActivityResults -ProfilerUrl $BenchSummary.profilerUrl -HealthUrl $BenchSummary.healthUrl
}
