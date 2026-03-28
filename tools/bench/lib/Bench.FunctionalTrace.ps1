<#
.SYNOPSIS
bench 模块：functional phase、node trace 与断言逻辑。
#>

function Resolve-PhaseSerials {
	param(
		$Phase,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$resolvedSerials = @()
	$explicitSerials = Get-OptionalProperty -Object $Phase -Name "serials"
	if ($null -ne $explicitSerials) {
		$resolvedSerials = @(
			Get-SortedUniqueSerials (
				@($explicitSerials | ForEach-Object { [long]$_ })
			)
		)
	} else {
		$serialRef = [string](Get-OptionalProperty -Object $Phase -Name "serialRef" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($serialRef)) {
			$serialRef = [string](Get-OptionalProperty -Object $Phase -Name "sourceGroup" -DefaultValue "")
		}
		if ([string]::IsNullOrWhiteSpace($serialRef)) {
			throw "Phase kind '$($Phase.kind)' requires serialRef/sourceGroup or explicit serials."
		}
		$resolvedSerials = @(Resolve-OrderedPhaseSerialsByRef -SerialRef $serialRef -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
	}

	$requestedIndexes = New-Object System.Collections.Generic.List[int]
	$singleIndex = Get-OptionalProperty -Object $Phase -Name "serialIndex"
	if ($null -ne $singleIndex) {
		$requestedIndexes.Add([int]$singleIndex)
	}
	$multipleIndexes = Get-OptionalProperty -Object $Phase -Name "serialIndexes"
	if ($null -ne $multipleIndexes) {
		foreach ($rawIndex in @($multipleIndexes)) {
			$requestedIndexes.Add([int]$rawIndex)
		}
	}
	if ($requestedIndexes.Count -gt 0) {
		return @(Select-SerialsByIndex -Serials $resolvedSerials -IndexValues $requestedIndexes.ToArray())
	}
	return $resolvedSerials
}

function Resolve-OrderedPhaseSerialsByRef {
	param(
		[string]$SerialRef,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	if ([string]::IsNullOrWhiteSpace($SerialRef)) {
		throw "serialRef/sourceGroup cannot be empty."
	}
	if ($SerialRef -eq "targets") {
		return @(Get-OrderedSerialListFromPositionMap -Map $TargetSerialMap)
	}
	if ($null -ne $SourceSerialMaps -and $SourceSerialMaps.ContainsKey($SerialRef)) {
		return @(Get-OrderedSerialListFromPositionMap -Map $SourceSerialMaps[$SerialRef])
	}
	throw "Unknown serialRef/sourceGroup: $SerialRef"
}

function Parse-FunctionalSerialIndexes {
	param([string]$RawIndexSpec)
	if ([string]::IsNullOrWhiteSpace($RawIndexSpec)) {
		return @()
	}
	$trimmed = $RawIndexSpec.Trim()
	if (-not $trimmed.StartsWith("[") -or -not $trimmed.EndsWith("]")) {
		throw "Invalid serial index spec: $RawIndexSpec"
	}
	$body = $trimmed.Substring(1, $trimmed.Length - 2).Trim()
	if ([string]::IsNullOrWhiteSpace($body)) {
		throw "Serial index spec cannot be empty: $RawIndexSpec"
	}
	$indexes = New-Object System.Collections.Generic.List[int]
	foreach ($token in @($body -split ",")) {
		$item = ([string]$token).Trim()
		if ([string]::IsNullOrWhiteSpace($item) -or $item -notmatch "^-?\d+$") {
			throw "Invalid serial index token: $item"
		}
		$indexes.Add([int]$item)
	}
	return @($indexes.ToArray())
}

function Resolve-FunctionalSerialTemplateValue {
	param(
		[string]$SerialRef,
		[string]$RawIndexSpec,
		[string]$Style,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$serials = @(Resolve-OrderedPhaseSerialsByRef -SerialRef $SerialRef -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
	if (-not [string]::IsNullOrWhiteSpace($RawIndexSpec)) {
		$serials = @(Select-SerialsByIndex -Serials $serials -IndexValues (Parse-FunctionalSerialIndexes -RawIndexSpec $RawIndexSpec))
	}
	$normalizedSerials = @(Get-SortedUniqueSerials $serials)
	if ($normalizedSerials.Count -le 0) {
		throw "Template ref '$SerialRef' resolved no serials."
	}
	$resolvedStyle = ([string]$Style).Trim()
	if ([string]::IsNullOrWhiteSpace($resolvedStyle)) {
		if ($normalizedSerials.Count -eq 1) {
			return [string][long]$normalizedSerials[0]
		}
		$resolvedStyle = "slash_list"
	}
	switch ($resolvedStyle) {
		"single" {
			if ($normalizedSerials.Count -ne 1) {
				throw "Template ref '$SerialRef' expected exactly one serial, actual=$($normalizedSerials.Count)."
			}
			return [string][long]$normalizedSerials[0]
		}
		"count" {
			return [string]$normalizedSerials.Count
		}
		default {
			return (Format-SerialInputText -Serials $normalizedSerials -Style $resolvedStyle)
		}
	}
}

function Resolve-FunctionalPhasePropertyValue {
	param(
		[hashtable]$PhaseContext,
		[string]$PhaseName,
		[string]$PropertyPath
	)
	$value = Resolve-FunctionalPhaseResult -PhaseContext $PhaseContext -PhaseName $PhaseName
	if ([string]::IsNullOrWhiteSpace($PropertyPath)) {
		return $value
	}
	foreach ($segment in @($PropertyPath -split "\.")) {
		$currentSegment = ([string]$segment).Trim()
		if ([string]::IsNullOrWhiteSpace($currentSegment)) {
			throw "Invalid phase property path: $PropertyPath"
		}
		if ($null -eq $value) {
			throw "Phase property '$PropertyPath' resolved to null at segment '$currentSegment'."
		}
		if ($value -is [System.Collections.IDictionary]) {
			if (-not $value.Contains($currentSegment)) {
				throw "Phase property segment not found: $currentSegment"
			}
			$value = $value[$currentSegment]
			continue
		}
		if (($value -is [System.Collections.IList]) -and -not ($value -is [string])) {
			if ($currentSegment -notmatch "^-?\d+$") {
				throw "Phase list segment must be integer index: $currentSegment"
			}
			$index = [int]$currentSegment
			if ($index -lt 0 -or $index -ge $value.Count) {
				throw "Phase list index out of range: $index"
			}
			$value = $value[$index]
			continue
		}
		$property = $value.PSObject.Properties[$currentSegment]
		if ($null -eq $property) {
			throw "Phase property segment not found: $currentSegment"
		}
		$value = $property.Value
	}
	return $value
}

function Convert-FunctionalTemplateValueToString {
	param($Value)
	if ($null -eq $Value) {
		throw "Template placeholder resolved to null."
	}
	if ($Value -is [bool]) {
		return $Value.ToString().ToLowerInvariant()
	}
	if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
		$items = New-Object System.Collections.Generic.List[string]
		foreach ($item in $Value) {
			$items.Add((Convert-FunctionalTemplateValueToString -Value $item))
		}
		return ($items.ToArray() -join "/")
	}
	return [string]$Value
}

function Resolve-FunctionalCommandTemplateToken {
	param(
		[string]$Token,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap,
		[hashtable]$PhaseContext
	)
	$trimmedToken = ([string]$Token).Trim()
	if ([string]::IsNullOrWhiteSpace($trimmedToken)) {
		throw "Template token cannot be empty."
	}

	if ($trimmedToken -like "phase:*") {
		$phaseRef = $trimmedToken.Substring(6)
		$dotIndex = $phaseRef.IndexOf(".")
		if ($dotIndex -lt 1 -or $dotIndex -ge ($phaseRef.Length - 1)) {
			throw "Phase token must be phase:<phaseName>.<propertyPath>: $trimmedToken"
		}
		$phaseName = $phaseRef.Substring(0, $dotIndex)
		$propertyPath = $phaseRef.Substring($dotIndex + 1)
		$value = Resolve-FunctionalPhasePropertyValue -PhaseContext $PhaseContext -PhaseName $phaseName -PropertyPath $propertyPath
		return (Convert-FunctionalTemplateValueToString -Value $value)
	}

	$targetMatch = [System.Text.RegularExpressions.Regex]::Match(
		$trimmedToken,
		"^targets(?<indexes>\[[^\]]+\])?(?::(?<style>[A-Za-z_]+))?$"
	)
	if ($targetMatch.Success) {
		return (
			Resolve-FunctionalSerialTemplateValue `
				-SerialRef "targets" `
				-RawIndexSpec ([string]$targetMatch.Groups["indexes"].Value) `
				-Style ([string]$targetMatch.Groups["style"].Value) `
				-SourceSerialMaps $SourceSerialMaps `
				-TargetSerialMap $TargetSerialMap
		)
	}

	$sourceMatch = [System.Text.RegularExpressions.Regex]::Match(
		$trimmedToken,
		"^source:(?<group>[A-Za-z0-9_.-]+)(?<indexes>\[[^\]]+\])?(?::(?<style>[A-Za-z_]+))?$"
	)
	if ($sourceMatch.Success) {
		return (
			Resolve-FunctionalSerialTemplateValue `
				-SerialRef ([string]$sourceMatch.Groups["group"].Value) `
				-RawIndexSpec ([string]$sourceMatch.Groups["indexes"].Value) `
				-Style ([string]$sourceMatch.Groups["style"].Value) `
				-SourceSerialMaps $SourceSerialMaps `
				-TargetSerialMap $TargetSerialMap
		)
	}

	throw "Unsupported command template token: $trimmedToken"
}

function Resolve-FunctionalCommandTemplate {
	param(
		[string]$Template,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap,
		[hashtable]$PhaseContext
	)
	$rawTemplate = if ($null -eq $Template) { "" } else { [string]$Template }
	if ([string]::IsNullOrWhiteSpace($rawTemplate)) {
		return ""
	}
	$pattern = "\{\{([^{}]+)\}\}"
	return [System.Text.RegularExpressions.Regex]::Replace(
		$rawTemplate,
		$pattern,
		{
			param($match)
			return [string](
				Resolve-FunctionalCommandTemplateToken `
					-Token ([string]$match.Groups[1].Value) `
					-SourceSerialMaps $SourceSerialMaps `
					-TargetSerialMap $TargetSerialMap `
					-PhaseContext $PhaseContext
			)
		}
	)
}

function Get-FunctionalCommandPatternList {
	param(
		$Phase,
		[string]$PrimaryName,
		[string]$ListName,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap,
		[hashtable]$PhaseContext
	)
	$resolvedPatterns = New-Object System.Collections.Generic.List[string]
	$primaryPattern = Get-OptionalProperty -Object $Phase -Name $PrimaryName
	if ($null -ne $primaryPattern) {
		$resolvedPatterns.Add(
			(Resolve-FunctionalCommandTemplate -Template ([string]$primaryPattern) -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap -PhaseContext $PhaseContext)
		)
	}
	$listPatterns = Get-OptionalProperty -Object $Phase -Name $ListName
	if ($null -ne $listPatterns) {
		foreach ($item in @($listPatterns)) {
			$resolvedPatterns.Add(
				(Resolve-FunctionalCommandTemplate -Template ([string]$item) -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap -PhaseContext $PhaseContext)
			)
		}
	}
	return @(
		$resolvedPatterns.ToArray() |
			Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
	)
}

function Extract-InputJobIdFromResponse {
	param([string]$ResponseText)
	$match = [System.Text.RegularExpressions.Regex]::Match(
		([string]$ResponseText),
		"(?i)\bjob\s*=\s*(\d+)"
	)
	if (-not $match.Success) {
		return $null
	}
	return [long]$match.Groups[1].Value
}

function Test-FunctionalCommandAssertHardFailure {
	param([string]$ResponseText)
	$normalized = ([string]$ResponseText).Trim()
	if ([string]::IsNullOrWhiteSpace($normalized)) {
		return $false
	}
	$hardFailurePatterns = @(
		"(?i)\bUnknown(?: or incomplete)? command\b",
		"(?i)\bCould not parse command\b",
		"(?i)\bIncorrect argument\b",
		"(?i)\bNo entity was found\b",
		"(?i)\bNo player was found\b",
		"(?i)\bToo many requests\b",
		"(?i)\binsufficient permission\b",
		"(?i)\bplayer[- ]only\b"
	)
	foreach ($pattern in $hardFailurePatterns) {
		if ($normalized -match $pattern) {
			return $true
		}
	}
	return $false
}

function Resolve-InputEndpointCommandPath {
	param([string]$Endpoint)
	switch ([string]$Endpoint) {
		"triggerSource" { return "triggerSource" }
		"core_sync" { return "core sync" }
		default { throw "Unsupported functional input endpoint: $Endpoint" }
	}
}

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
	$normalizedLimit = [Math]::Max(1, [int]$Limit)
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
	return (-not [string]::IsNullOrWhiteSpace($ResponseText)) -and ($ResponseText -match "Too many requests")
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

function Parse-SignalSequenceText {
	param([string]$SequenceText)
	$normalized = if ($null -eq $SequenceText) { "" } else { ([string]$SequenceText).Trim() }
	if ([string]::IsNullOrWhiteSpace($normalized)) {
		throw "Signal sequence must not be empty."
	}
	if ($normalized -match '^(?:1[0-5]|[0-9])$') {
		return @([int]$normalized)
	}
	if ($normalized.IndexOfAny(@(',', '/', ':', '|')) -ge 0) {
		$tokens = [System.Text.RegularExpressions.Regex]::Split($normalized, "[,/:|]")
		$sequence = New-Object System.Collections.Generic.List[int]
		foreach ($token in $tokens) {
			$item = if ($null -eq $token) { "" } else { $token.Trim() }
			if ([string]::IsNullOrWhiteSpace($item)) {
				continue
			}
			$value = [int]$item
			if ($value -lt 0 -or $value -gt 15) {
				throw "Signal sequence token out of range: $item"
			}
			$sequence.Add($value)
		}
		if ($sequence.Count -le 0) {
			throw "Signal sequence must not be empty."
		}
		return @($sequence.ToArray())
	}
	$sequence = New-Object System.Collections.Generic.List[int]
	foreach ($ch in $normalized.ToCharArray()) {
		if ([char]::IsWhiteSpace($ch)) {
			continue
		}
		$value = [System.Convert]::ToInt32([string]$ch, 16)
		if ($value -lt 0 -or $value -gt 15) {
			throw "Invalid signal sequence char: $ch"
		}
		$sequence.Add($value)
	}
	if ($sequence.Count -le 0) {
		throw "Signal sequence must not be empty."
	}
	return @($sequence.ToArray())
}

function New-TraceExpectationSample {
	param(
		[string]$Type,
		[string]$Mode,
		[int]$Power
	)
	$normalizedPower = [Math]::Max(0, [Math]::Min(15, [int]$Power))
	$active = ($normalizedPower -gt 0)
	$sample = [ordered]@{
		active = $active
		input = $normalizedPower
		output = $normalizedPower
	}
	if ([string]$Type -eq "core") {
		$sample.resolvedStrength = $normalizedPower
		if ($active -and -not [string]::IsNullOrWhiteSpace($Mode)) {
			$sample.effectiveMode = [string]$Mode
		}
	}
	return [pscustomobject]$sample
}

function Build-SyncSquareTraceExpectations {
	param(
		$TemplatePhaseResult,
		[string]$Type,
		[int]$TickCount
	)
	$waveform = Get-OptionalProperty -Object $TemplatePhaseResult -Name "waveform"
	if ($null -eq $waveform) {
		throw "sync_square template requires waveform metadata."
	}
	$periodTicks = [int](Get-OptionalProperty -Object $waveform -Name "periodTicks" -DefaultValue 0)
	$highTicks = [int](Get-OptionalProperty -Object $waveform -Name "highTicks" -DefaultValue 0)
	$highPower = [int](Get-OptionalProperty -Object $waveform -Name "highPower" -DefaultValue 15)
	$lowPower = [int](Get-OptionalProperty -Object $waveform -Name "lowPower" -DefaultValue 0)
	$phaseTicks = [int](Get-OptionalProperty -Object $waveform -Name "phaseTicks" -DefaultValue 0)
	if ($periodTicks -le 0) {
		throw "sync_square template requires periodTicks > 0."
	}
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { $periodTicks }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$cycleTick = [int](($offset + $phaseTicks) % $periodTicks)
		$power = if ($cycleTick -lt $highTicks) { $highPower } else { $lowPower }
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "sync" -Power $power))
	}
	return @($expectations.ToArray())
}

function Build-SyncCustomTraceExpectations {
	param(
		$TemplatePhaseResult,
		[string]$Type,
		[int]$TickCount
	)
	$sequenceText = [string](Get-OptionalProperty -Object $TemplatePhaseResult -Name "sequence" -DefaultValue "")
	$phaseTicks = [int](Get-OptionalProperty -Object $TemplatePhaseResult -Name "phaseTicks" -DefaultValue 0)
	$sequence = @(Parse-SignalSequenceText -SequenceText $sequenceText)
	if ($sequence.Count -le 0) {
		throw "sync_custom template requires a non-empty sequence."
	}
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { $sequence.Count }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$index = [int](($offset + $phaseTicks) % $sequence.Count)
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "sync" -Power ([int]$sequence[$index])))
	}
	return @($expectations.ToArray())
}

function Build-PulseTraceExpectations {
	param(
		$Template,
		[string]$Type,
		[int]$TickCount
	)
	$activeTicks = [int](Get-OptionalProperty -Object $Template -Name "activeTicks" -DefaultValue 4)
	$activePower = [int](Get-OptionalProperty -Object $Template -Name "activePower" -DefaultValue 15)
	if ($activeTicks -le 0) {
		throw "pulse template requires activeTicks > 0."
	}
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { ($activeTicks + 2) }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$power = if ($offset -lt $activeTicks) { $activePower } else { 0 }
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "pulse" -Power $power))
	}
	return @($expectations.ToArray())
}

function Build-ToggleTraceExpectations {
	param(
		$Template,
		[string]$Type,
		[int]$TickCount
	)
	$expectedActive = Get-OptionalProperty -Object $Template -Name "expectedActive"
	if ($null -eq $expectedActive) {
		throw "toggle_hold template requires expectedActive."
	}
	$activePower = [int](Get-OptionalProperty -Object $Template -Name "activePower" -DefaultValue 15)
	$resolvedTickCount = if ($TickCount -gt 0) { $TickCount } else { 3 }
	$power = if ([bool]$expectedActive) { $activePower } else { 0 }
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($offset = 0; $offset -lt $resolvedTickCount; $offset++) {
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode "toggle" -Power $power))
	}
	return @($expectations.ToArray())
}

function Resolve-TraceTickExpectations {
	param(
		$Phase,
		[string]$Type,
		[hashtable]$PhaseContext
	)
	$expectedTicksRaw = Get-OptionalProperty -Object $Phase -Name "expectTicks"
	if ($null -ne $expectedTicksRaw) {
		return @($expectedTicksRaw)
	}
	$template = Get-OptionalProperty -Object $Phase -Name "template"
	if ($null -eq $template) {
		throw "trace_read_tick_assert phase requires expectTicks or template."
	}
	$templateKind = [string](Get-OptionalProperty -Object $template -Name "kind" -DefaultValue "")
	$tickCount = [int](Get-OptionalProperty -Object $Phase -Name "tickCount" -DefaultValue 0)
	$templatePhaseRef = [string](Get-OptionalProperty -Object $template -Name "phaseRef" -DefaultValue "")
	if ([string]::IsNullOrWhiteSpace($templatePhaseRef)) {
		$templatePhaseRef = [string](Get-OptionalProperty -Object $Phase -Name "anchorRef" -DefaultValue "")
	}
	$templatePhaseResult = if ([string]::IsNullOrWhiteSpace($templatePhaseRef)) {
		$null
	} else {
		Resolve-FunctionalPhaseResult -PhaseContext $PhaseContext -PhaseName $templatePhaseRef
	}
	switch ($templateKind) {
		"sync_square" { return @(Build-SyncSquareTraceExpectations -TemplatePhaseResult $templatePhaseResult -Type $Type -TickCount $tickCount) }
		"sync_custom" { return @(Build-SyncCustomTraceExpectations -TemplatePhaseResult $templatePhaseResult -Type $Type -TickCount $tickCount) }
		"pulse" { return @(Build-PulseTraceExpectations -Template $template -Type $Type -TickCount $tickCount) }
		"toggle_hold" { return @(Build-ToggleTraceExpectations -Template $template -Type $Type -TickCount $tickCount) }
		default { throw "Unsupported trace tick template kind: $templateKind" }
	}
}

function Find-TraceTickWindowMatch {
	param(
		$Samples,
		$ExpectedTicks,
		[long]$StartTickMin,
		[long]$SearchTickMax
	)
	$expectedItems = @($ExpectedTicks)
	if ($expectedItems.Count -le 0) {
		return [ordered]@{
			matched = $true
			startTick = $StartTickMin
			alignmentOffset = 0
			candidateCount = 0
			actualWindow = @()
			failures = @()
		}
	}
	if ($SearchTickMax -lt $StartTickMin) {
		return [ordered]@{
			matched = $false
			startTick = $null
			alignmentOffset = $null
			candidateCount = 0
			actualWindow = @()
			failures = @(
				[ordered]@{
					reason = "invalid_anchor_window"
					startTickMin = $StartTickMin
					searchTickMax = $SearchTickMax
				}
			)
		}
	}
	$samplesByTick = @{}
	foreach ($sample in @(Convert-ToChronologicalTraceSamples -Samples $Samples)) {
		$tickProperty = $sample.PSObject.Properties["tick"]
		if ($null -eq $tickProperty) {
			continue
		}
		$tick = [long]$tickProperty.Value
		if (-not $samplesByTick.ContainsKey($tick)) {
			$samplesByTick[$tick] = $sample
		}
	}
	$candidateCount = 0
	$bestFailure = $null
	for ($candidateStartTick = [long]$StartTickMin; $candidateStartTick -le [long]$SearchTickMax; $candidateStartTick++) {
		$candidateCount++
		$actualWindow = New-Object System.Collections.Generic.List[object]
		$failures = New-Object System.Collections.Generic.List[object]
		for ($offset = 0; $offset -lt $expectedItems.Count; $offset++) {
			$expectedTick = $candidateStartTick + $offset
			if (-not $samplesByTick.ContainsKey($expectedTick)) {
				$failures.Add([ordered]@{
					reason = "missing_tick"
					sampleOffset = $offset
					tick = $expectedTick
					expected = $expectedItems[$offset]
				})
				continue
			}
			$actualSample = $samplesByTick[$expectedTick]
			$actualWindow.Add($actualSample)
			$comparison = Compare-TraceSampleAgainstExpectation -Sample $actualSample -Expected $expectedItems[$offset]
			if ($comparison.mismatches.Count -gt 0) {
				$failures.Add([ordered]@{
					reason = "field_mismatch"
					sampleOffset = $offset
					tick = $expectedTick
					expected = $expectedItems[$offset]
					actual = $comparison.actual
					mismatches = $comparison.mismatches
				})
			}
		}
		if ($failures.Count -eq 0) {
			return [ordered]@{
				matched = $true
				startTick = $candidateStartTick
				alignmentOffset = ($candidateStartTick - $StartTickMin)
				candidateCount = $candidateCount
				actualWindow = @($actualWindow.ToArray())
				failures = @()
			}
		}
		if ($null -eq $bestFailure -or $failures.Count -lt $bestFailure.failures.Count) {
			$bestFailure = [ordered]@{
				matched = $false
				startTick = $candidateStartTick
				alignmentOffset = ($candidateStartTick - $StartTickMin)
				candidateCount = $candidateCount
				actualWindow = @($actualWindow.ToArray())
				failures = @($failures.ToArray())
			}
		}
	}
	if ($null -ne $bestFailure) {
		$bestFailure.candidateCount = $candidateCount
		return $bestFailure
	}
	return [ordered]@{
		matched = $false
		startTick = $null
		alignmentOffset = $null
		candidateCount = $candidateCount
		actualWindow = @()
		failures = @(
			[ordered]@{
				reason = "no_candidate_checked"
			}
		)
	}
}

function Invoke-FunctionalPhases {
	param(
		$Connection,
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$phaseResults = New-Object System.Collections.Generic.List[object]
	$checks = New-Object System.Collections.Generic.List[object]
	$failedChecks = New-Object System.Collections.Generic.List[object]
	$phaseContext = @{}
	foreach ($phase in $CaseConfig.phases) {
		$kind = [string](Get-OptionalProperty -Object $phase -Name "kind" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($kind)) {
			throw "Functional phase kind is required."
		}
		$phaseName = [string](Get-OptionalProperty -Object $phase -Name "name" -DefaultValue $kind)
		switch ($kind) {
			"trace_mount" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$every = [int](Get-OptionalProperty -Object $phase -Name "every" -DefaultValue 1)
				$capacity = [int](Get-OptionalProperty -Object $phase -Name "capacity" -DefaultValue 128)
				$command = Wrap-WithPlayerContext "redstonelink node trace mount $type $serialText $every $capacity"
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				$response = [string]$commandResult.response
				$samples = @(Parse-NodeTraceSamples -ResponseText $response)
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					every = $every
					capacity = $capacity
					command = $command
					response = $response
					tickWindow = $commandResult.tickWindow
					samples = $samples
					mountTicksBySerial = (Resolve-TraceMountTicksBySerial -ResponseText $response)
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_unmount" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$command = Wrap-WithPlayerContext "redstonelink node trace unmount $type $serialText"
				$response = Invoke-RconCommand -Connection $Connection -Command $command -Silent
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					command = $command
					response = $response
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"input_start_square" {
				$endpoint = [string](Get-OptionalProperty -Object $phase -Name "endpoint" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$periodTicks = [int](Get-OptionalProperty -Object $phase -Name "periodTicks" -DefaultValue 0)
				if ($periodTicks -le 0) {
					throw "input_start_square phase requires periodTicks > 0."
				}
				$highTicks = [int](Get-OptionalProperty -Object $phase -Name "highTicks" -DefaultValue ([Math]::Max(1, [int]($periodTicks / 2))))
				$highPower = [int](Get-OptionalProperty -Object $phase -Name "highPower" -DefaultValue 15)
				$lowPower = [int](Get-OptionalProperty -Object $phase -Name "lowPower" -DefaultValue 0)
				$phaseTicks = [int](Get-OptionalProperty -Object $phase -Name "phaseTicks" -DefaultValue 0)
				$totalTicks = [int](Get-OptionalProperty -Object $phase -Name "totalTicks" -DefaultValue 0)
				$endpointPath = Resolve-InputEndpointCommandPath -Endpoint $endpoint
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
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText ([string]$commandResult.response) `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Started job="
				$jobId = Extract-InputJobIdFromResponse -ResponseText ([string]$commandResult.response)
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					endpoint = $endpoint
					serials = $serials
					serialText = $serialText
					jobId = $jobId
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
					waveform = [ordered]@{
						periodTicks = $periodTicks
						highTicks = $highTicks
						highPower = $highPower
						lowPower = $lowPower
						phaseTicks = $phaseTicks
						totalTicks = $totalTicks
					}
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"input_start_custom" {
				$endpoint = [string](Get-OptionalProperty -Object $phase -Name "endpoint" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$sequence = [string](Get-OptionalProperty -Object $phase -Name "sequence" -DefaultValue "")
				if ([string]::IsNullOrWhiteSpace($sequence)) {
					throw "input_start_custom phase requires sequence."
				}
				$phaseTicks = [int](Get-OptionalProperty -Object $phase -Name "phaseTicks" -DefaultValue 0)
				$totalTicks = [int](Get-OptionalProperty -Object $phase -Name "totalTicks" -DefaultValue 0)
				$endpointPath = Resolve-InputEndpointCommandPath -Endpoint $endpoint
				$command = Wrap-WithPlayerContext (
					"redstonelink input start {0} custom {1} {2} {3} {4}" -f
					$endpointPath,
					$serialText,
					$sequence,
					$phaseTicks,
					$totalTicks
				)
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText ([string]$commandResult.response) `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Started job="
				$jobId = Extract-InputJobIdFromResponse -ResponseText ([string]$commandResult.response)
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					endpoint = $endpoint
					serials = $serials
					serialText = $serialText
					jobId = $jobId
					sequence = $sequence
					phaseTicks = $phaseTicks
					totalTicks = $totalTicks
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"input_clear" {
				$command = Wrap-WithPlayerContext "redstonelink input clear"
				$response = Invoke-RconCommand -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText $response `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Cleared input jobs:"
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					command = $command
					response = $response
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"link_command" {
				$action = [string](Get-OptionalProperty -Object $phase -Name "action" -DefaultValue "")
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "triggerSource")
				$explicitSourceSerial = Get-OptionalProperty -Object $phase -Name "sourceSerial"
				if ($null -ne $explicitSourceSerial) {
					$sourceSerial = [long]$explicitSourceSerial
				} else {
					$sourceRef = [string](Get-OptionalProperty -Object $phase -Name "sourceRef" -DefaultValue "")
					if ([string]::IsNullOrWhiteSpace($sourceRef)) {
						throw "link_command phase requires sourceRef or sourceSerial."
					}
					$sourceIndex = [int](Get-OptionalProperty -Object $phase -Name "sourceIndex" -DefaultValue 0)
					$sourceCandidates = @(Resolve-PhaseSerials -Phase @{
						kind = $kind
						serialRef = $sourceRef
						serialIndex = $sourceIndex
					} -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
					if ($sourceCandidates.Count -ne 1) {
						throw "link_command phase must resolve exactly one source serial."
					}
					$sourceSerial = [long]$sourceCandidates[0]
				}

				$targetSerials = @()
				if ($action -eq "add" -or $action -eq "remove" -or $action -eq "set") {
					$targetRef = [string](Get-OptionalProperty -Object $phase -Name "targetRef" -DefaultValue "")
					$targetIndexes = Get-OptionalProperty -Object $phase -Name "targetIndexes"
					$singleTargetIndex = Get-OptionalProperty -Object $phase -Name "targetIndex"
					$explicitTargetSerials = Get-OptionalProperty -Object $phase -Name "targetSerials"
					$targetPhase = @{
						kind = $kind
						serialRef = $targetRef
					}
					if ($null -ne $explicitTargetSerials) {
						$targetPhase.serials = @($explicitTargetSerials)
					}
					if ($null -ne $singleTargetIndex) {
						$targetPhase.serialIndex = [int]$singleTargetIndex
					}
					if ($null -ne $targetIndexes) {
						$targetPhase.serialIndexes = @($targetIndexes)
					}
					$targetSerials = @(Resolve-PhaseSerials -Phase $targetPhase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				}

				$targetSerialFormat = [string](Get-OptionalProperty -Object $phase -Name "targetSerialFormat" -DefaultValue "slash_list")
				$forceConfirm = [bool](Get-OptionalProperty -Object $phase -Name "forceConfirm" -DefaultValue $false)
				switch ($action) {
					"add" {
						if ($targetSerials.Count -ne 1) {
							throw "link_command add requires exactly one target serial."
						}
						$commandText = "redstonelink link add $type $sourceSerial $($targetSerials[0])"
					}
					"remove" {
						if ($targetSerials.Count -ne 1) {
							throw "link_command remove requires exactly one target serial."
						}
						$commandText = "redstonelink link remove $type $sourceSerial $($targetSerials[0])"
					}
					"set" {
						$commandText = "redstonelink link set $type $sourceSerial $(Format-SerialInputText -Serials $targetSerials -Style $targetSerialFormat)"
						if ($targetSerials.Count -gt 1 -or $forceConfirm) {
							$commandText += " confirm"
						}
					}
					"clear" {
						$commandText = "redstonelink link set $type $sourceSerial"
					}
					"get" {
						$commandText = "redstonelink link get $type $sourceSerial"
					}
					default {
						throw "Unsupported link_command action: $action"
					}
				}
				$command = Wrap-WithPlayerContext $commandText
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					action = $action
					type = $type
					sourceSerial = $sourceSerial
					targetSerials = $targetSerials
					targetSerialText = if ($targetSerials.Count -gt 0) { Format-SerialInputText -Serials $targetSerials -Style $targetSerialFormat } else { "" }
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"command_assert" {
				$rawCommand = [string](Get-OptionalProperty -Object $phase -Name "command" -DefaultValue "")
				$commandTemplate = [string](Get-OptionalProperty -Object $phase -Name "commandTemplate" -DefaultValue "")
				$commandText = if (-not [string]::IsNullOrWhiteSpace($commandTemplate)) {
					Resolve-FunctionalCommandTemplate -Template $commandTemplate -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap -PhaseContext $phaseContext
				} else {
					Resolve-FunctionalCommandTemplate -Template $rawCommand -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap -PhaseContext $phaseContext
				}
				if ([string]::IsNullOrWhiteSpace($commandText)) {
					throw "command_assert phase requires command or commandTemplate."
				}
				$skipPlayerContext = [bool](Get-OptionalProperty -Object $phase -Name "skipPlayerContext" -DefaultValue $false)
				$command = if ($skipPlayerContext) { $commandText } else { Wrap-WithPlayerContext $commandText }
				$expectedPrefix = Resolve-FunctionalCommandTemplate `
					-Template ([string](Get-OptionalProperty -Object $phase -Name "expectedPrefix" -DefaultValue "")) `
					-SourceSerialMaps $SourceSerialMaps `
					-TargetSerialMap $TargetSerialMap `
					-PhaseContext $phaseContext
				$expectedRegexes = @(Get-FunctionalCommandPatternList `
					-Phase $phase `
					-PrimaryName "expectedRegex" `
					-ListName "expectedRegexes" `
					-SourceSerialMaps $SourceSerialMaps `
					-TargetSerialMap $TargetSerialMap `
					-PhaseContext $phaseContext)
				$rejectRegexes = @(Get-FunctionalCommandPatternList `
					-Phase $phase `
					-PrimaryName "rejectRegex" `
					-ListName "rejectRegexes" `
					-SourceSerialMaps $SourceSerialMaps `
					-TargetSerialMap $TargetSerialMap `
					-PhaseContext $phaseContext)
				$captureTickWindow = [bool](Get-OptionalProperty -Object $phase -Name "captureTickWindow" -DefaultValue $false)
				$allowReadTimeout = [bool](Get-OptionalProperty -Object $phase -Name "allowReadTimeout" -DefaultValue $false)
				$receiveTimeoutMs = [int](Get-OptionalProperty -Object $phase -Name "receiveTimeoutMs" -DefaultValue 3000)

				if ($DryRun) {
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "command_response"
						passed = $true
						skipped = $true
						reason = "dry_run"
						command = $command
						expectedPrefix = $expectedPrefix
						expectedRegexes = $expectedRegexes
						rejectRegexes = $rejectRegexes
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						command = $command
						response = ""
						tickWindow = $null
						expectedPrefix = $expectedPrefix
						expectedRegexes = $expectedRegexes
						rejectRegexes = $rejectRegexes
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}

				$response = ""
				$tickWindow = $null
				$failureReason = ""
				$errorDetail = ""
				try {
					if ($captureTickWindow) {
						$commandResult = Invoke-RconCommandWithTickWindow `
							-Connection $Connection `
							-Command $command `
							-Silent `
							-ReceiveTimeoutMs $receiveTimeoutMs `
							-AllowReadTimeout:$allowReadTimeout
						$response = [string]$commandResult.response
						$tickWindow = $commandResult.tickWindow
					} else {
						$response = Invoke-RconCommand `
							-Connection $Connection `
							-Command $command `
							-Silent `
							-ReceiveTimeoutMs $receiveTimeoutMs `
							-AllowReadTimeout:$allowReadTimeout
					}
				} catch {
					$failureReason = "execution_error"
					$errorDetail = $_.Exception.Message
				}

				$normalizedResponse = ([string]$response).Trim()
				$passed = $true
				if ([string]::IsNullOrWhiteSpace($failureReason)) {
					if ([string]::IsNullOrWhiteSpace($normalizedResponse)) {
						$passed = $false
						$failureReason = "empty_response"
					} elseif (Test-FunctionalCommandAssertHardFailure -ResponseText $normalizedResponse) {
						$passed = $false
						$failureReason = "failure_response"
					} elseif (-not [string]::IsNullOrWhiteSpace($expectedPrefix) -and -not $normalizedResponse.StartsWith($expectedPrefix, [System.StringComparison]::Ordinal)) {
						$passed = $false
						$failureReason = "prefix_mismatch"
					} else {
						foreach ($expectedRegex in $expectedRegexes) {
							if (-not [System.Text.RegularExpressions.Regex]::IsMatch($normalizedResponse, $expectedRegex)) {
								$passed = $false
								$failureReason = "expected_regex_missing"
								$errorDetail = $expectedRegex
								break
							}
						}
						if ($passed) {
							foreach ($rejectRegex in $rejectRegexes) {
								if ([System.Text.RegularExpressions.Regex]::IsMatch($normalizedResponse, $rejectRegex)) {
									$passed = $false
									$failureReason = "reject_regex_matched"
									$errorDetail = $rejectRegex
									break
								}
							}
						}
					}
				} else {
					$passed = $false
				}

				$check = [ordered]@{
					phase = $phaseName
					kind = $kind
					scope = "command_response"
					passed = $passed
					command = $command
					response = $response
					expectedPrefix = $expectedPrefix
					expectedRegexes = $expectedRegexes
					rejectRegexes = $rejectRegexes
					failureReason = $failureReason
					errorDetail = $errorDetail
				}
				$checks.Add($check)
				if (-not $passed) {
					$failedChecks.Add($check)
				}

				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					command = $command
					response = $response
					tickWindow = $tickWindow
					expectedPrefix = $expectedPrefix
					expectedRegexes = $expectedRegexes
					rejectRegexes = $rejectRegexes
					passed = $passed
					failureReason = $failureReason
					errorDetail = $errorDetail
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"activate_batch" {
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$mode = [string](Get-OptionalProperty -Object $phase -Name "mode" -DefaultValue "toggle")
				$command = Wrap-WithPlayerContext "redstonelink node activate triggerSource $serialText $mode"
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					mode = $mode
					serials = $serials
					serialText = $serialText
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"wait_ticks" {
				$ticks = [int](Get-OptionalProperty -Object $phase -Name "ticks" -DefaultValue 0)
				$waitInfo = Wait-ServerTicks -Connection $Connection -Ticks $ticks
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					wait = $waitInfo
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_latest_assert" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$expected = Get-OptionalProperty -Object $phase -Name "expect"
				if ($null -eq $expected) {
					throw "trace_latest_assert phase requires expect."
				}
				$expectedCount = [int](Get-OptionalProperty -Object $phase -Name "expectedCount" -DefaultValue $serials.Count)
				$command = Wrap-WithPlayerContext "redstonelink node trace latest $type $serialText"
				$response = Invoke-RconCommand -Connection $Connection -Command $command -Silent
				if ($DryRun) {
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						passed = $true
						skipped = $true
						reason = "dry_run"
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						type = $type
						serials = $serials
						serialText = $serialText
						command = $command
						response = $response
						samples = @()
						expected = $expected
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$samples = @(Parse-NodeTraceSamples -ResponseText $response)
				$countCheck = [ordered]@{
					phase = $phaseName
					kind = $kind
					scope = "sample_count"
					passed = ($samples.Count -eq $expectedCount)
					expected = $expectedCount
					actual = $samples.Count
				}
				$checks.Add($countCheck)
				if (-not $countCheck.passed) {
					$failedChecks.Add($countCheck)
				}
				foreach ($sample in $samples) {
					$comparison = Compare-TraceSampleAgainstExpectation -Sample $sample -Expected $expected
					$sampleCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "sample"
						type = $type
						serial = $sample.serial
						passed = ($comparison.mismatches.Count -eq 0)
						expected = $expected
						actual = $comparison.actual
						mismatches = $comparison.mismatches
					}
					$checks.Add($sampleCheck)
					if (-not $sampleCheck.passed) {
						$failedChecks.Add($sampleCheck)
					}
				}
				$phasePassed = ($countCheck.passed -and (@($samples | Where-Object {
					$comparison = Compare-TraceSampleAgainstExpectation -Sample $_ -Expected $expected
					$comparison.mismatches.Count -eq 0
				}).Count -eq $samples.Count))
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					command = $command
					response = $response
					samples = $samples
					expected = $expected
					passed = $phasePassed
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_read_cycle_assert" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$expectedCycleRaw = Get-OptionalProperty -Object $phase -Name "expectCycle"
				$expectedCycle = if ($null -eq $expectedCycleRaw) { @() } else { @($expectedCycleRaw) }
				if ($expectedCycle.Count -le 0) {
					throw "trace_read_cycle_assert phase requires expectCycle."
				}
				$limit = [int](Get-OptionalProperty -Object $phase -Name "limit" -DefaultValue ([Math]::Max($expectedCycle.Count, 8)))
				$minimumCount = [int](Get-OptionalProperty -Object $phase -Name "minimumCount" -DefaultValue $expectedCycle.Count)
				if ($DryRun) {
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						passed = $true
						skipped = $true
						reason = "dry_run"
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						type = $type
						serials = $serials
						serialText = $serialText
						limit = $limit
						minimumCount = $minimumCount
						expectedCycle = $expectedCycle
						reads = @()
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$phasePassed = $true
				$readResults = New-Object System.Collections.Generic.List[object]
				foreach ($serial in $serials) {
					$readResult = Invoke-NodeTraceRead -Connection $Connection -Type $type -Serial $serial -Limit $limit
					$samples = @($readResult.samples)
					$countCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "sample_count"
						type = $type
						serial = $serial
						passed = ($samples.Count -ge $minimumCount)
						expectedMinimum = $minimumCount
						actual = $samples.Count
					}
					$checks.Add($countCheck)
					if (-not $countCheck.passed) {
						$failedChecks.Add($countCheck)
						$phasePassed = $false
					}
					$matchResult = if ($countCheck.passed) {
						Find-TraceCycleMatch -Samples $samples -ExpectedCycle $expectedCycle
					} else {
						[ordered]@{
							matched = $false
							rotation = $null
							searchWindowCount = 0
							actualWindow = @($samples)
							failures = @(
								[ordered]@{
									reason = "count_check_failed"
								}
							)
						}
					}
					$cycleCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "cycle"
						type = $type
						serial = $serial
						passed = [bool]$matchResult.matched
						expectedCycle = $expectedCycle
						actualWindow = $matchResult.actualWindow
						rotation = $matchResult.rotation
						searchWindowCount = $matchResult.searchWindowCount
						failures = $matchResult.failures
					}
					$checks.Add($cycleCheck)
					if (-not $cycleCheck.passed) {
						$failedChecks.Add($cycleCheck)
						$phasePassed = $false
					}
					$readResults.Add([ordered]@{
						serial = $serial
						command = $readResult.command
						response = $readResult.response
						sampleCount = $samples.Count
						samples = $samples
						matched = [bool]$matchResult.matched
						rotation = $matchResult.rotation
						searchWindowCount = $matchResult.searchWindowCount
						actualWindow = $matchResult.actualWindow
						failures = $matchResult.failures
					})
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					limit = $limit
					minimumCount = $minimumCount
					expectedCycle = $expectedCycle
					reads = @($readResults.ToArray())
					passed = $phasePassed
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_read_tick_assert" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$mountRef = [string](Get-OptionalProperty -Object $phase -Name "mountRef" -DefaultValue "")
				$anchorRef = [string](Get-OptionalProperty -Object $phase -Name "anchorRef" -DefaultValue "")
				if ([string]::IsNullOrWhiteSpace($mountRef)) {
					throw "trace_read_tick_assert phase requires mountRef."
				}
				if ([string]::IsNullOrWhiteSpace($anchorRef)) {
					throw "trace_read_tick_assert phase requires anchorRef."
				}
				$mountPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $mountRef
				$anchorPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $anchorRef
				$mountEvery = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "every" -DefaultValue 0)
				if ($mountEvery -ne 1) {
					throw "trace_read_tick_assert requires mountRef every=1."
				}
				$anchorTickWindow = Get-OptionalProperty -Object $anchorPhaseResult -Name "tickWindow"
				if ($null -eq $anchorTickWindow) {
					throw "trace_read_tick_assert anchorRef must point to a command phase with tickWindow."
				}
				$commandStartTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "startTick" -DefaultValue -1L)
				$commandEndTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "endTick" -DefaultValue -1L)
				if ($commandStartTick -lt 0L -or $commandEndTick -lt 0L) {
					throw "trace_read_tick_assert anchorRef tickWindow is invalid."
				}
				$expectedTicks = @(Resolve-TraceTickExpectations -Phase $phase -Type $type -PhaseContext $phaseContext)
				if ($expectedTicks.Count -le 0) {
					throw "trace_read_tick_assert phase resolved no expected ticks."
				}
				$alignmentSlackTicks = [int](Get-OptionalProperty -Object $phase -Name "alignmentSlackTicks" -DefaultValue 1)
				$limit = [int](Get-OptionalProperty -Object $phase -Name "limit" -DefaultValue ([Math]::Max($expectedTicks.Count + $alignmentSlackTicks + 4, 8)))
				if ($DryRun) {
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						passed = $true
						skipped = $true
						reason = "dry_run"
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						type = $type
						serials = $serials
						serialText = $serialText
						mountRef = $mountRef
						anchorRef = $anchorRef
						limit = $limit
						alignmentSlackTicks = $alignmentSlackTicks
						expectedTicks = $expectedTicks
						reads = @()
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$phasePassed = $true
				$readResults = New-Object System.Collections.Generic.List[object]
				foreach ($serial in $serials) {
					$mountTick = Get-TraceMountTickForSerial -MountPhaseResult $mountPhaseResult -Serial $serial
					$mountCapacity = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "capacity" -DefaultValue $limit)
					$searchTickMax = 0L
					$startTickMin = $commandStartTick
					if ($null -ne $mountTick) {
						$startTickMin = [Math]::Max([long]$startTickMin, ([long]$mountTick + 1L))
					}
					$startTickMax = [long]$commandEndTick + [Math]::Max(0, $alignmentSlackTicks)
					$searchTickMax = [long]$startTickMax
					$currentLimit = [Math]::Min([Math]::Max(1, $limit), [Math]::Max(1, $mountCapacity))
					$readResult = Invoke-NodeTraceRead -Connection $Connection -Type $type -Serial $serial -Limit $currentLimit
					$samples = @($readResult.samples)
					$chronologicalSamples = @(Convert-ToChronologicalTraceSamples -Samples $samples)
					while ($currentLimit -lt $mountCapacity) {
						$expandedLimit = Get-ExpandedTraceReadLimit `
							-CurrentLimit $currentLimit `
							-Capacity $mountCapacity `
							-StartTickMin $startTickMin `
							-Samples $chronologicalSamples
						if ($expandedLimit -le $currentLimit) {
							break
						}
						$previousLimit = $currentLimit
						$previousReadResult = $readResult
						$previousSamples = @($samples)
						$previousChronologicalSamples = @($chronologicalSamples)
						$currentLimit = $expandedLimit
						$expandedReadResult = Invoke-NodeTraceRead -Connection $Connection -Type $type -Serial $serial -Limit $currentLimit
						if (Test-NodeTraceReadRateLimited -ResponseText ([string]$expandedReadResult.response)) {
							$currentLimit = $previousLimit
							$readResult = $previousReadResult
							$samples = @($previousSamples)
							$chronologicalSamples = @($previousChronologicalSamples)
							break
						}
						$readResult = $expandedReadResult
						$samples = @($readResult.samples)
						$chronologicalSamples = @(Convert-ToChronologicalTraceSamples -Samples $samples)
					}
					$eligibleSamples = @(
						$chronologicalSamples |
							Where-Object {
								$tickProperty = $_.PSObject.Properties["tick"]
								$null -ne $tickProperty -and [long]$tickProperty.Value -ge $startTickMin
							}
					)
					if ($eligibleSamples.Count -gt 0) {
						$eligibleLastTick = [long](@($eligibleSamples | Select-Object -Last 1)[0].tick)
						$latestCompleteWindowStart = $eligibleLastTick - [long]($expectedTicks.Count - 1)
						if ($latestCompleteWindowStart -gt $searchTickMax) {
							$searchTickMax = $latestCompleteWindowStart
						}
					}
					$countCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "eligible_sample_count"
						type = $type
						serial = $serial
						passed = ($eligibleSamples.Count -ge $expectedTicks.Count)
						expectedMinimum = $expectedTicks.Count
						actual = $eligibleSamples.Count
						rawSampleCount = $samples.Count
						startTickMin = $startTickMin
						startTickMax = $startTickMax
						searchTickMax = $searchTickMax
						mountTick = $mountTick
					}
					$checks.Add($countCheck)
					if (-not $countCheck.passed) {
						$failedChecks.Add($countCheck)
						$phasePassed = $false
					}
					if ($countCheck.passed) {
						$matchResult = Find-TraceTickWindowMatch `
							-Samples $eligibleSamples `
							-ExpectedTicks $expectedTicks `
							-StartTickMin $startTickMin `
							-SearchTickMax $searchTickMax
					} else {
						$matchResult = [ordered]@{
							matched = $false
							startTick = $null
							alignmentOffset = $null
							candidateCount = 0
							actualWindow = @($eligibleSamples)
							failures = @(
								[ordered]@{
									reason = "count_check_failed"
								}
							)
						}
					}
					$tickCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "tick_window"
						type = $type
						serial = $serial
						passed = [bool]$matchResult.matched
						expectedTicks = $expectedTicks
						actualWindow = $matchResult.actualWindow
						startTick = $matchResult.startTick
						alignmentOffset = $matchResult.alignmentOffset
						candidateCount = $matchResult.candidateCount
						failures = $matchResult.failures
					}
					$checks.Add($tickCheck)
					if (-not $tickCheck.passed) {
						$failedChecks.Add($tickCheck)
						$phasePassed = $false
					}
					$readResults.Add([ordered]@{
						serial = $serial
						command = $readResult.command
						response = $readResult.response
						requestedLimit = $limit
						readLimit = $currentLimit
						rawSampleCount = $samples.Count
						samples = $samples
						eligibleSamples = $eligibleSamples
						mountTick = $mountTick
						startTickMin = $startTickMin
						startTickMax = $startTickMax
						searchTickMax = $searchTickMax
						matched = [bool]$matchResult.matched
						startTick = $matchResult.startTick
						alignmentOffset = $matchResult.alignmentOffset
						candidateCount = $matchResult.candidateCount
						actualWindow = $matchResult.actualWindow
						failures = $matchResult.failures
					})
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					mountRef = $mountRef
					anchorRef = $anchorRef
					limit = $limit
					alignmentSlackTicks = $alignmentSlackTicks
					expectedTicks = $expectedTicks
					reads = @($readResults.ToArray())
					passed = $phasePassed
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			default {
				throw "Unsupported functional phase kind: $kind"
			}
		}
	}
	return [ordered]@{
		phases = @($phaseResults.ToArray())
		checks = @($checks.ToArray())
		failedChecks = @($failedChecks.ToArray())
		passed = ($failedChecks.Count -eq 0)
	}
}
