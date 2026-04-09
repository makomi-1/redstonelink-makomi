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
	# Clamp to the command-side hard limit to avoid invalid trace-read requests.
	$normalizedLimit = [Math]::Min(256, [Math]::Max(1, [int]$Limit))
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

function Convert-TraceExpectationsToPowerSequence {
	param($ExpectedTicks)
	$powers = New-Object System.Collections.Generic.List[int]
	foreach ($expected in @($ExpectedTicks)) {
		$power = $null
		if ($expected -is [System.Collections.IDictionary]) {
			if ($expected.Contains("output")) {
				$power = [int]$expected["output"]
			} elseif ($expected.Contains("resolvedStrength")) {
				$power = [int]$expected["resolvedStrength"]
			}
		} else {
			$outputProperty = $expected.PSObject.Properties["output"]
			if ($null -ne $outputProperty) {
				$power = [int]$outputProperty.Value
			} else {
				$strengthProperty = $expected.PSObject.Properties["resolvedStrength"]
				if ($null -ne $strengthProperty) {
					$power = [int]$strengthProperty.Value
				}
			}
		}
		if ($null -eq $power) {
			throw "Trace expectation is missing output/resolvedStrength power."
		}
		$powers.Add([Math]::Max(0, [Math]::Min(15, [int]$power)))
	}
	return @($powers.ToArray())
}

function Format-SignalSequenceText {
	param([int[]]$Sequence)
	$items = New-Object System.Collections.Generic.List[string]
	foreach ($value in @($Sequence)) {
		$normalizedValue = [Math]::Max(0, [Math]::Min(15, [int]$value))
		$items.Add($normalizedValue.ToString("x"))
	}
	return [string]::Join("", @($items.ToArray()))
}

function Split-BenchTraceLatencyEntries {
	param([string]$ResponseText)
	$normalizedText = if ($null -eq $ResponseText) { "" } else { [string]$ResponseText }
	if ([string]::IsNullOrWhiteSpace($normalizedText)) {
		return @()
	}
	# Normalize broken multiline RCON payloads before marker extraction.
	# This prevents matched, mounted, and reason fields from being split apart.
	$lineBreakPattern = "[\u000A\u000D\u0085\u2028\u2029]+"
	$normalizedText = [System.Text.RegularExpressions.Regex]::Replace($normalizedText, $lineBreakPattern, "")
	$entryPattern = '\[RedstoneLink/Bench\]\s+trace_sync_latency_(?:summary|item)\b.*?(?=\[RedstoneLink/Bench\]\s+trace_sync_latency_(?:summary|item)\b|$)'
	$rawEntries = [System.Text.RegularExpressions.Regex]::Matches(
		$normalizedText,
		$entryPattern,
		[System.Text.RegularExpressions.RegexOptions]::Singleline
	)
	$entries = New-Object System.Collections.Generic.List[string]
	foreach ($entryMatch in $rawEntries) {
	# Repeat the cleanup for each entry to catch residual wrapped fragments.
		$current = [System.Text.RegularExpressions.Regex]::Replace(([string]$entryMatch.Value).Trim(), $lineBreakPattern, "")
		if ([string]::IsNullOrWhiteSpace($current)) {
			continue
		}
		$entries.Add($current)
	}
	return @($entries.ToArray())
}

function Convert-BenchTraceLatencyFieldValue {
	param(
		[string]$EntryKind,
		[string]$FieldName,
		[string]$RawValue
	)
	$normalized = if ($null -eq $RawValue) { "" } else { ([string]$RawValue).Trim() }
	$normalized = $normalized.TrimEnd('.', ',', ';')
	if ($normalized -eq "-") {
		return $null
	}
	$parseLong = {
		param([string]$Text)
		$match = [System.Text.RegularExpressions.Regex]::Match($Text, "^-?\d+")
		if (-not $match.Success) {
			throw "Invalid numeric latency field: $Text"
		}
		return [long]$match.Value
	}
	$parseInt = {
		param([string]$Text)
		$match = [System.Text.RegularExpressions.Regex]::Match($Text, "^-?\d+")
		if (-not $match.Success) {
			throw "Invalid numeric latency field: $Text"
		}
		return [int]$match.Value
	}
	$parseBool = {
		param([string]$Text)
		$boolMatch = [System.Text.RegularExpressions.Regex]::Match($Text, "^(?i:true|tru|t|false|fals|f)")
		if ($boolMatch.Success) {
			$prefix = $boolMatch.Value.ToLowerInvariant()
			return ($prefix.StartsWith("t"))
		}
		$digitMatch = [System.Text.RegularExpressions.Regex]::Match($Text, "^[01]")
		if ($digitMatch.Success) {
			return ($digitMatch.Value -eq "1")
		}
		throw "Invalid boolean latency field: $Text"
	}
	switch ([string]$EntryKind) {
		"summary" {
			switch ([string]$FieldName) {
				"requested" { return (& $parseInt $normalized) }
				"analyzed" { return (& $parseInt $normalized) }
				"mounted" { return (& $parseInt $normalized) }
				"matched" { return (& $parseInt $normalized) }
				"unmatched" { return (& $parseInt $normalized) }
				"expectedStartTick" { return (& $parseLong $normalized) }
				"expectedTickCount" { return (& $parseInt $normalized) }
				"latestExpectedStartTick" { return (& $parseLong $normalized) }
				default { return $normalized }
			}
		}
		"item" {
			switch ([string]$FieldName) {
				"serial" { return (& $parseLong $normalized) }
				"matched" { return (& $parseBool $normalized) }
				"mounted" { return (& $parseBool $normalized) }
				"actualStartTick" { return (& $parseLong $normalized) }
				"inputDelayTicks" { return (& $parseLong $normalized) }
				"mountTick" { return (& $parseLong $normalized) }
				"latestSampleTick" { return (& $parseLong $normalized) }
				"eligibleSamples" { return (& $parseInt $normalized) }
				default { return $normalized }
			}
		}
		default { return $normalized }
	}
}

function Parse-BenchTraceLatencyResponseLocal {
	param([string]$ResponseText)
	$summary = $null
	$items = New-Object System.Collections.Generic.List[object]
	foreach ($entry in @(Split-BenchTraceLatencyEntries -ResponseText $ResponseText)) {
		$trimmedEntry = ([string]$entry).Trim()
		$entryKind = ""
		if ($trimmedEntry -match "^\[RedstoneLink/Bench\]\s+trace_sync_latency_summary\b") {
			$entryKind = "summary"
		} elseif ($trimmedEntry -match "^\[RedstoneLink/Bench\]\s+trace_sync_latency_item\b") {
			$entryKind = "item"
		} else {
			continue
		}
		$data = [ordered]@{}
		$fieldMatches = [System.Text.RegularExpressions.Regex]::Matches($trimmedEntry, "([A-Za-z][A-Za-z0-9]*)=([^\s\[]+)")
		foreach ($fieldMatch in @($fieldMatches)) {
			$fieldName = [string]$fieldMatch.Groups[1].Value
			$data[$fieldName] = Convert-BenchTraceLatencyFieldValue `
				-EntryKind $entryKind `
				-FieldName $fieldName `
				-RawValue ([string]$fieldMatch.Groups[2].Value)
		}
		if ($entryKind -eq "summary") {
			$summary = [pscustomobject]$data
		} else {
			$items.Add([pscustomobject]$data)
		}
	}
	if ($null -eq $summary) {
		throw "trace_sync_latency response missing summary entry."
	}
	return [ordered]@{
		summary = $summary
		items = @($items.ToArray())
	}
}

function Invoke-BenchTraceLatencyPythonParser {
	param([string]$ResponseText)
	$parserPath = Join-Path $PSScriptRoot "Bench.TraceLatencyParser.py"
	if (-not (Test-Path -LiteralPath $parserPath)) {
		return $null
	}
	$pythonCandidates = New-Object System.Collections.Generic.List[object]
	$pyCommand = Get-Command py -ErrorAction SilentlyContinue
	if ($null -ne $pyCommand) {
		$pythonCandidates.Add([pscustomobject]@{
			command = [string]$pyCommand.Source
			args = @("-3")
		})
	}
	$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
	if ($null -ne $pythonCommand) {
		$pythonCandidates.Add([pscustomobject]@{
			command = [string]$pythonCommand.Source
			args = @()
		})
	}
	if ($pythonCandidates.Count -le 0) {
		return $null
	}
	$inputPath = [System.IO.Path]::GetTempFileName()
	$outputPath = [System.IO.Path]::GetTempFileName()
	$enc = New-Object System.Text.UTF8Encoding($false)
	try {
		[System.IO.File]::WriteAllText(
			$inputPath,
			$(if ($null -eq $ResponseText) { "" } else { [string]$ResponseText }),
			$enc
		)
		foreach ($candidate in @($pythonCandidates.ToArray())) {
			$invokeArgs = New-Object System.Collections.Generic.List[string]
			foreach ($arg in @($candidate.args)) {
				$invokeArgs.Add([string]$arg)
			}
			$invokeArgs.Add($parserPath)
			$invokeArgs.Add($inputPath)
			$invokeArgs.Add($outputPath)
			& $candidate.command @($invokeArgs.ToArray()) | Out-Null
			if ($LASTEXITCODE -ne 0) {
				continue
			}
			$jsonText = Get-Content -Raw -Encoding UTF8 -LiteralPath $outputPath
			if ([string]::IsNullOrWhiteSpace($jsonText)) {
				continue
			}
			$parsed = $jsonText | ConvertFrom-Json
			if ($null -ne $parsed.summary) {
				return [ordered]@{
					summary = $parsed.summary
					items = @($parsed.items)
				}
			}
		}
	} finally {
		if (Test-Path -LiteralPath $inputPath) {
			Remove-Item -LiteralPath $inputPath -Force -ErrorAction SilentlyContinue
		}
		if (Test-Path -LiteralPath $outputPath) {
			Remove-Item -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue
		}
	}
	return $null
}

function Parse-BenchTraceLatencyResponse {
	param([string]$ResponseText)
	$pythonParsed = Invoke-BenchTraceLatencyPythonParser -ResponseText $ResponseText
	if ($null -ne $pythonParsed) {
		return $pythonParsed
	}
	return Parse-BenchTraceLatencyResponseLocal -ResponseText $ResponseText
}

function New-TraceLatencyStats {
	param([long[]]$Values)
	$normalized = @(
		@($Values) |
			Where-Object { $null -ne $_ } |
			ForEach-Object { [long]$_ } |
			Sort-Object
	)
	if ($normalized.Count -le 0) {
		return [ordered]@{
			count = 0
			min = $null
			max = $null
			avg = $null
			p50 = $null
			p95 = $null
		}
	}
	$sum = 0.0
	foreach ($value in $normalized) {
		$sum += [double]$value
	}
	$p50Index = [Math]::Max(0, [Math]::Ceiling($normalized.Count * 0.50) - 1)
	$p95Index = [Math]::Max(0, [Math]::Ceiling($normalized.Count * 0.95) - 1)
	return [ordered]@{
		count = $normalized.Count
		min = [long]$normalized[0]
		max = [long]$normalized[$normalized.Count - 1]
		avg = [Math]::Round(($sum / $normalized.Count), 3)
		p50 = [long]$normalized[$p50Index]
		p95 = [long]$normalized[$p95Index]
	}
}

function Resolve-TraceLatencyReferenceStartTick {
	param(
		$ReferencePhaseResult,
		[string]$Strategy
	)
	if ($null -eq $ReferencePhaseResult) {
		return $null
	}
	$summary = Get-OptionalProperty -Object $ReferencePhaseResult -Name "summary"
	if ($null -eq $summary) {
		throw "referencePhaseRef must point to a phase result with summary."
	}
	$matchedStartTickStats = Get-OptionalProperty -Object $summary -Name "matchedStartTickStats"
	if ($null -eq $matchedStartTickStats) {
		throw "referencePhaseRef summary is missing matchedStartTickStats."
	}
	$resolvedStrategy = ([string]$Strategy).Trim().ToLowerInvariant()
	if ([string]::IsNullOrWhiteSpace($resolvedStrategy)) {
		$resolvedStrategy = "min"
	}
	switch ($resolvedStrategy) {
		"min" { return Get-OptionalProperty -Object $matchedStartTickStats -Name "min" }
		"p50" { return Get-OptionalProperty -Object $matchedStartTickStats -Name "p50" }
		"p95" { return Get-OptionalProperty -Object $matchedStartTickStats -Name "p95" }
		default { throw "Unsupported referenceStartStrategy: $Strategy" }
	}
}

function Resolve-TraceLatencyWindowTicks {
	param($Phase)
	$rawWindowTicks = Get-OptionalProperty -Object $Phase -Name "windowTicks"
	if ($null -eq $rawWindowTicks) {
		return @(0, 1, 2)
	}
	$items = @($rawWindowTicks)
	if ($items.Count -le 0) {
		return @(0, 1, 2)
	}
	$normalized = New-Object System.Collections.Generic.List[int]
	foreach ($item in $items) {
		$normalized.Add([Math]::Max(0, [int]$item))
	}
	return @($normalized.ToArray() | Sort-Object -Unique)
}

function Resolve-TraceLatencyWindowCoverageChecks {
	param($Phase)
	$rawChecks = Get-OptionalProperty -Object $Phase -Name "windowCoverageChecks"
	if ($null -eq $rawChecks) {
		return @()
	}
	$items = @($rawChecks)
	if ($items.Count -le 0) {
		return @()
	}
	$resolvedChecks = New-Object System.Collections.Generic.List[object]
	foreach ($item in $items) {
		if ($null -eq $item) {
			continue
		}
		$windowTickRaw = Get-OptionalProperty -Object $item -Name "windowTicks"
		if ($null -eq $windowTickRaw) {
			throw "windowCoverageChecks requires windowTicks."
		}
		$expectedRatioRaw = Get-OptionalProperty -Object $item -Name "expectedRatio"
		$expectedMinimumRaw = Get-OptionalProperty -Object $item -Name "expectedMinimum"
		$expectedMaximumRaw = Get-OptionalProperty -Object $item -Name "expectedMaximum"
		$expectedMinimum = if ($null -ne $expectedRatioRaw) {
			[Math]::Max(0.0, [Math]::Min(1.0, [double]$expectedRatioRaw))
		} elseif ($null -ne $expectedMinimumRaw) {
			[Math]::Max(0.0, [Math]::Min(1.0, [double]$expectedMinimumRaw))
		} else {
			$null
		}
		$expectedMaximum = if ($null -ne $expectedRatioRaw) {
			[Math]::Max(0.0, [Math]::Min(1.0, [double]$expectedRatioRaw))
		} elseif ($null -ne $expectedMaximumRaw) {
			[Math]::Max(0.0, [Math]::Min(1.0, [double]$expectedMaximumRaw))
		} else {
			$null
		}
		if ($null -eq $expectedMinimum -and $null -eq $expectedMaximum) {
			throw "windowCoverageChecks requires expectedRatio or expectedMinimum/expectedMaximum."
		}
		if ($null -ne $expectedMinimum -and $null -ne $expectedMaximum -and [double]$expectedMinimum -gt [double]$expectedMaximum) {
			throw "windowCoverageChecks expectedMinimum must be <= expectedMaximum."
		}
		$resolvedChecks.Add([ordered]@{
			windowTicks = [Math]::Max(0, [int]$windowTickRaw)
			expectedMinimum = $expectedMinimum
			expectedMaximum = $expectedMaximum
		})
	}
	return @($resolvedChecks.ToArray())
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
	return (-not [string]::IsNullOrWhiteSpace($ResponseText)) -and ($ResponseText -match "(?i)(Too many requests|\u64cd\u4f5c\u8fc7\u4e8e\u9891\u7e41)")
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

