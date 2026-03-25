<#
.SYNOPSIS
bench 模块：spark activity 轮询与性能采集。
#>

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
