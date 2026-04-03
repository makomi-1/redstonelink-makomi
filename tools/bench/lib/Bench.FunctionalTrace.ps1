<#
.SYNOPSIS
bench 模块：functional phase、node trace 与断言逻辑。
#>

function Resolve-PhaseSerialRefList {
	param($Phase)
	$serialRefs = @(Get-OptionalProperty -Object $Phase -Name "serialRefs" -DefaultValue @())
	$singleRef = [string](Get-OptionalProperty -Object $Phase -Name "serialRef" -DefaultValue "")
	$legacySourceGroup = [string](Get-OptionalProperty -Object $Phase -Name "sourceGroup" -DefaultValue "")
	if ($serialRefs.Count -gt 0) {
		if (-not [string]::IsNullOrWhiteSpace($singleRef) -or -not [string]::IsNullOrWhiteSpace($legacySourceGroup)) {
			throw "serialRefs cannot be mixed with serialRef/sourceGroup in the same phase."
		}
		$normalizedRefs = New-Object System.Collections.Generic.List[string]
		foreach ($rawRef in @($serialRefs)) {
			$trimmedRef = ([string]$rawRef).Trim()
			if ([string]::IsNullOrWhiteSpace($trimmedRef)) {
				throw "serialRefs cannot contain empty items."
			}
			$normalizedRefs.Add($trimmedRef)
		}
		return @($normalizedRefs.ToArray())
	}
	if ([string]::IsNullOrWhiteSpace($singleRef)) {
		$singleRef = $legacySourceGroup
	}
	if ([string]::IsNullOrWhiteSpace($singleRef)) {
		return @()
	}
	$trimmedSingleRef = ([string]$singleRef).Trim()
	return @($trimmedSingleRef)
}

function Resolve-OrderedPhaseSerialsByRefs {
	param(
		[string[]]$SerialRefs,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	if ($null -eq $SerialRefs -or $SerialRefs.Count -le 0) {
		throw "serialRefs cannot be empty."
	}
	$resolved = @()
	foreach ($serialRef in @($SerialRefs)) {
		$currentRef = ([string]$serialRef).Trim()
		$currentSerials = Resolve-OrderedPhaseSerialsByRef -SerialRef $currentRef -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap
		$resolved += @($currentSerials)
	}
	return @($resolved)
}

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
		$serialRefs = @(Resolve-PhaseSerialRefList -Phase $Phase)
		if ($serialRefs.Count -le 0) {
			throw "Phase kind '$($Phase.kind)' requires serialRef/serialRefs/sourceGroup or explicit serials."
		}
		$resolvedSerials = @(Resolve-OrderedPhaseSerialsByRefs -SerialRefs $serialRefs -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
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

function Split-SerialsIntoBatches {
	param(
		[long[]]$Serials,
		[int]$ChunkSize
	)
	$items = @($Serials)
	if ($items.Count -le 0) {
		return @()
	}
	$resolvedChunkSize = if ($ChunkSize -le 0) { $items.Count } else { $ChunkSize }
	$batches = New-Object System.Collections.Generic.List[object]
	for ($index = 0; $index -lt $items.Count; $index += $resolvedChunkSize) {
		$endExclusive = [Math]::Min($items.Count, $index + $resolvedChunkSize)
		$batch = New-Object System.Collections.Generic.List[long]
		for ($cursor = $index; $cursor -lt $endExclusive; $cursor++) {
			$batch.Add([long]$items[$cursor])
		}
		$batches.Add(@($batch.ToArray()))
	}
	return @($batches.ToArray())
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

function Format-FunctionalSerialTemplateText {
	param(
		[long[]]$Serials,
		[string]$Style
	)
	$normalizedSerials = @(Get-SortedUniqueSerials $Serials)
	if ($normalizedSerials.Count -le 0) {
		throw "Template serial list resolved no serials."
	}
	$resolvedStyle = ([string]$Style).Trim()
	if ($resolvedStyle -eq "single") {
		if ($normalizedSerials.Count -ne 1) {
			throw "Template serial list expected exactly one serial, actual=$($normalizedSerials.Count)."
		}
		return [string][long]$normalizedSerials[0]
	}
	if ($resolvedStyle -eq "count") {
		return [string]$normalizedSerials.Count
	}
	if ($resolvedStyle -eq "csv") {
		$csvItems = New-Object System.Collections.Generic.List[string]
		foreach ($serial in $normalizedSerials) {
			$csvItems.Add([string][long]$serial)
		}
		$csvArray = @($csvItems.ToArray())
		return [string]::Join(", ", $csvArray)
	}
	$inputStyleText = Format-SerialInputText -Serials $normalizedSerials -Style $Style
	return $inputStyleText
}

function Resolve-FunctionalSerialTemplateValue {
	param(
		[string[]]$SerialRefs,
		[string]$RawIndexSpec,
		[string]$Style,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$resolvedSerialRefs = @(
		@($SerialRefs) |
			Where-Object { -not [string]::IsNullOrWhiteSpace(([string]$_).Trim()) } |
			ForEach-Object { ([string]$_).Trim() }
	)
	if ($resolvedSerialRefs.Count -le 0) {
		throw "Template serialRefs cannot be empty."
	}
	$serials = @(Resolve-OrderedPhaseSerialsByRefs -SerialRefs $resolvedSerialRefs -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
	if (-not [string]::IsNullOrWhiteSpace($RawIndexSpec)) {
		$serials = @(Select-SerialsByIndex -Serials $serials -IndexValues (Parse-FunctionalSerialIndexes -RawIndexSpec $RawIndexSpec))
	}
	$normalizedSerials = @(Get-SortedUniqueSerials $serials)
	if ($normalizedSerials.Count -le 0) {
		throw "Template refs '$($resolvedSerialRefs -join "+")' resolved no serials."
	}
	$resolvedStyle = ([string]$Style).Trim()
	if ([string]::IsNullOrWhiteSpace($resolvedStyle)) {
		if ($normalizedSerials.Count -eq 1) {
			return [string][long]$normalizedSerials[0]
		}
		$resolvedStyle = "slash_list"
	}
	$resolvedText = Format-FunctionalSerialTemplateText -Serials $normalizedSerials -Style $resolvedStyle
	return $resolvedText
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
		$targetRefs = @("targets")
		return (
			Resolve-FunctionalSerialTemplateValue `
				-SerialRefs $targetRefs `
				-RawIndexSpec ([string]$targetMatch.Groups["indexes"].Value) `
				-Style ([string]$targetMatch.Groups["style"].Value) `
				-SourceSerialMaps $SourceSerialMaps `
				-TargetSerialMap $TargetSerialMap
		)
	}

	$multiSourceMatch = [System.Text.RegularExpressions.Regex]::Match(
		$trimmedToken,
		"^serialRefs:(?<refs>[A-Za-z0-9_.-]+(?:\+[A-Za-z0-9_.-]+)*)(?<indexes>\[[^\]]+\])?(?::(?<style>[A-Za-z_]+))?$"
	)
	if ($multiSourceMatch.Success) {
		$multiSourceRefs = @(([string]$multiSourceMatch.Groups["refs"].Value) -split "\+")
		return (
			Resolve-FunctionalSerialTemplateValue `
				-SerialRefs $multiSourceRefs `
				-RawIndexSpec ([string]$multiSourceMatch.Groups["indexes"].Value) `
				-Style ([string]$multiSourceMatch.Groups["style"].Value) `
				-SourceSerialMaps $SourceSerialMaps `
				-TargetSerialMap $TargetSerialMap
		)
	}

	$sourceMatch = [System.Text.RegularExpressions.Regex]::Match(
		$trimmedToken,
		"^source:(?<group>[A-Za-z0-9_.-]+)(?<indexes>\[[^\]]+\])?(?::(?<style>[A-Za-z_]+))?$"
	)
	if ($sourceMatch.Success) {
		$singleSourceRefs = @(([string]$sourceMatch.Groups["group"].Value))
		return (
			Resolve-FunctionalSerialTemplateValue `
				-SerialRefs $singleSourceRefs `
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

function Extract-InputJobStartTickFromResponse {
	param([string]$ResponseText)
	$match = [System.Text.RegularExpressions.Regex]::Match(
		([string]$ResponseText),
		"(?i)\bstartTick\s*=\s*(\d+)"
	)
	if (-not $match.Success) {
		return $null
	}
	return [long]$match.Groups[1].Value
}

function Extract-InputJobIdsFromResponse {
	param([string]$ResponseText)
	$match = [System.Text.RegularExpressions.Regex]::Match(
		([string]$ResponseText),
		"(?i)\bjob\s*=\s*([0-9/]+)"
	)
	if (-not $match.Success) {
		return @()
	}
	return @(
		$match.Groups[1].Value.Split('/', [System.StringSplitOptions]::RemoveEmptyEntries) |
			ForEach-Object { [long]$_ }
	)
}

function Test-FunctionalCommandAssertHardFailure {
	param([string]$ResponseText)
	$normalized = ([string]$ResponseText).Trim()
	if ([string]::IsNullOrWhiteSpace($normalized)) {
		return $false
	}
	if (Get-Command Test-BenchResponseLooksLikeFailure -ErrorAction SilentlyContinue) {
		if (Test-BenchResponseLooksLikeFailure -ResponseText $normalized) {
			return $true
		}
	}
	$hardFailurePatterns = @(
		"(?i)\bUnknown(?: or incomplete)? command\b",
		"(?i)\bCould not parse command\b",
		"(?i)\bIncorrect argument\b",
		"(?i)\bNo entity was found\b",
		"(?i)\bNo player was found\b",
		"(?i)\bToo many requests\b",
		"(?i)\bToo many\b",
		"(?i)\bempty\b",
		"(?i)\bmax\b",
		"(?i)\binsufficient permission\b",
		"(?i)\bplayer[- ]only\b",
		"\u65e0\u6548\u5e8f\u53f7",
		"\u975e\u6cd5\u5b57\u7b26",
		"\u76ee\u6807\u4e3a\u7a7a",
		"\u8f93\u5165\u4e3a\u7a7a",
		"\u5df2\u9000\u5f79",
		"\u6570\u91cf\u8fc7\u591a",
		"\u6700\u591a"
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
	# RCON 长响应在高并发批量回包下可能把单词硬断成多行，
	# 这里先整体移除垂直换行，再按 bench marker 抽取 entry，避免 `matched/mounted/reason` 被拆坏。
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
		# 再对单条 entry 做一次同样的清洗，兜住抽取后残留的异常换行。
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

function Build-TraceExpectationsFromPowerSequence {
	param(
		[int[]]$PowerSequence,
		[string]$Type,
		[string]$Mode
	)
	$expectations = New-Object System.Collections.Generic.List[object]
	foreach ($power in @($PowerSequence)) {
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode $Mode -Power ([int]$power)))
	}
	return @($expectations.ToArray())
}

function Resolve-MixedDirectWindowTicks {
	param($Template)
	$windowTicks = [int](Get-OptionalProperty -Object $Template -Name "windowTicks" -DefaultValue 0)
	if ($windowTicks -lt 0 -or $windowTicks -gt 2) {
		throw "mixed direct template requires windowTicks in range 0..2."
	}
	return $windowTicks
}

function Build-MixedDirectTraceExpectationsWithLeadingZeros {
	param(
		[int]$LeadingZeroTicks,
		[int[]]$TailSequence,
		[string]$Type,
		[string]$Mode
	)
	$powers = New-Object System.Collections.Generic.List[int]
	for ($index = 0; $index -lt [Math]::Max(0, $LeadingZeroTicks); $index++) {
		$powers.Add(0)
	}
	foreach ($power in @($TailSequence)) {
		$powers.Add([int]$power)
	}
	return @(Build-TraceExpectationsFromPowerSequence -PowerSequence @($powers.ToArray()) -Type $Type -Mode $Mode)
}

function Build-MixedDirectStructuredTraceExpectationsWithLeadingZeros {
	param(
		[int]$LeadingZeroTicks,
		$TailSamples,
		[string]$Type
	)
	$expectations = New-Object System.Collections.Generic.List[object]
	for ($index = 0; $index -lt [Math]::Max(0, $LeadingZeroTicks); $index++) {
		$expectations.Add((New-TraceExpectationSample -Type $Type -Power 0))
	}
	foreach ($sample in @($TailSamples)) {
		$power = [int](Get-OptionalProperty -Object $sample -Name "power" -DefaultValue 0)
		$mode = [string](Get-OptionalProperty -Object $sample -Name "mode" -DefaultValue "")
		$expectations.Add((New-TraceExpectationSample -Type $Type -Mode $mode -Power $power))
	}
	return @($expectations.ToArray())
}

function Build-MixedDirectSyncTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectSyncTraceExpectationsWithLeadingZeros -LeadingZeroTicks ($windowTicks + 1) -Type $Type)
}

function Build-MixedDirectSyncRelativeTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectSyncTraceExpectationsWithLeadingZeros -LeadingZeroTicks $windowTicks -Type $Type)
}

function Build-MixedDirectSyncTraceExpectationsWithLeadingZeros {
	param(
		[int]$LeadingZeroTicks,
		[string]$Type
	)
	return @(Build-MixedDirectTraceExpectationsWithLeadingZeros `
		-LeadingZeroTicks $LeadingZeroTicks `
		-TailSequence @(15, 0, 15, 0) `
		-Type $Type `
		-Mode "sync")
}

function Build-MixedDirectPulseTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectPulseTraceExpectationsWithLeadingZeros -LeadingZeroTicks ($windowTicks + 1) -Type $Type)
}

function Build-MixedDirectPulseRelativeTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectPulseTraceExpectationsWithLeadingZeros -LeadingZeroTicks $windowTicks -Type $Type)
}

function Build-MixedDirectPulseTraceExpectationsWithLeadingZeros {
	param(
		[int]$LeadingZeroTicks,
		[string]$Type
	)
	return @(Build-MixedDirectTraceExpectationsWithLeadingZeros `
		-LeadingZeroTicks $LeadingZeroTicks `
		-TailSequence @(15, 15, 15, 0, 0, 15, 15, 15, 0) `
		-Type $Type `
		-Mode "pulse")
}

function Build-MixedDirectToggleTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectToggleTraceExpectationsWithLeadingZeros -LeadingZeroTicks ($windowTicks + 1) -Type $Type)
}

function Build-MixedDirectToggleRelativeTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectToggleTraceExpectationsWithLeadingZeros -LeadingZeroTicks $windowTicks -Type $Type)
}

function Build-MixedDirectToggleTraceExpectationsWithLeadingZeros {
	param(
		[int]$LeadingZeroTicks,
		[string]$Type
	)
	return @(Build-MixedDirectTraceExpectationsWithLeadingZeros `
		-LeadingZeroTicks $LeadingZeroTicks `
		-TailSequence @(15, 15, 15, 0, 0, 0, 15, 15, 15, 15) `
		-Type $Type `
		-Mode "toggle")
}

function Build-MixedDirectSyncPulseRelativeTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectStructuredTraceExpectationsWithLeadingZeros `
		-LeadingZeroTicks $windowTicks `
		-TailSamples @(
			[ordered]@{ power = 15; mode = "sync" },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 15; mode = "sync" },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 0 }
		) `
		-Type $Type)
}

function Build-MixedDirectSyncToggleRelativeTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectStructuredTraceExpectationsWithLeadingZeros `
		-LeadingZeroTicks $windowTicks `
		-TailSamples @(
			[ordered]@{ power = 15; mode = "sync" },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 15; mode = "sync" },
			[ordered]@{ power = 15; mode = "toggle" },
			[ordered]@{ power = 15; mode = "toggle" },
			[ordered]@{ power = 15; mode = "toggle" },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 0 }
		) `
		-Type $Type)
}

function Build-MixedDirectPulseToggleRelativeTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectStructuredTraceExpectationsWithLeadingZeros `
		-LeadingZeroTicks $windowTicks `
		-TailSamples @(
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "toggle" },
			[ordered]@{ power = 15; mode = "toggle" }
		) `
		-Type $Type)
}

function Build-MixedDirectSyncPulseToggleRelativeTraceExpectations {
	param(
		$Template,
		[string]$Type
	)
	$windowTicks = Resolve-MixedDirectWindowTicks -Template $Template
	return @(Build-MixedDirectStructuredTraceExpectationsWithLeadingZeros `
		-LeadingZeroTicks $windowTicks `
		-TailSamples @(
			[ordered]@{ power = 15; mode = "sync" },
			[ordered]@{ power = 0 },
			[ordered]@{ power = 15; mode = "sync" },
			[ordered]@{ power = 15; mode = "toggle" },
			[ordered]@{ power = 15; mode = "toggle" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "pulse" },
			[ordered]@{ power = 15; mode = "toggle" },
			[ordered]@{ power = 15; mode = "toggle" }
		) `
		-Type $Type)
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
		"mixed_direct_sync" { return @(Build-MixedDirectSyncTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_sync_relative" { return @(Build-MixedDirectSyncRelativeTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_pulse" { return @(Build-MixedDirectPulseTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_pulse_relative" { return @(Build-MixedDirectPulseRelativeTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_toggle" { return @(Build-MixedDirectToggleTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_toggle_relative" { return @(Build-MixedDirectToggleRelativeTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_sync_pulse_relative" { return @(Build-MixedDirectSyncPulseRelativeTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_sync_toggle_relative" { return @(Build-MixedDirectSyncToggleRelativeTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_pulse_toggle_relative" { return @(Build-MixedDirectPulseToggleRelativeTraceExpectations -Template $template -Type $Type) }
		"mixed_direct_sync_pulse_toggle_relative" { return @(Build-MixedDirectSyncPulseToggleRelativeTraceExpectations -Template $template -Type $Type) }
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
				$chunkSize = [int](Get-OptionalProperty -Object $phase -Name "chunkSize" -DefaultValue $(if ($serials.Count -gt 256) { 256 } else { $serials.Count }))
				$batchPauseMs = [int](Get-OptionalProperty -Object $phase -Name "batchPauseMs" -DefaultValue $(if ($serials.Count -gt $chunkSize) { 150 } else { 0 }))
				$batchCommands = New-Object System.Collections.Generic.List[string]
				$batchResponses = New-Object System.Collections.Generic.List[string]
				$sampleList = New-Object System.Collections.Generic.List[object]
				$mountTicksBySerial = @{}
				$tickWindow = $null
				foreach ($serialBatch in @(Split-SerialsIntoBatches -Serials $serials -ChunkSize $chunkSize)) {
					$batchSerialText = Format-SerialInputText -Serials $serialBatch -Style $serialFormat
					$batchCommand = Wrap-WithPlayerContext "redstonelink node trace mount $type $batchSerialText $every $capacity"
					$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $batchCommand -Silent
					$batchResponse = [string]$commandResult.response
					$batchCommands.Add($batchCommand)
					$batchResponses.Add($batchResponse)
					foreach ($sample in @(Parse-NodeTraceSamples -ResponseText $batchResponse)) {
						$sampleList.Add($sample)
					}
					foreach ($entry in (Resolve-TraceMountTicksBySerial -ResponseText $batchResponse).GetEnumerator()) {
						$mountTicksBySerial[$entry.Key] = $entry.Value
					}
					if ($null -eq $tickWindow) {
						$tickWindow = $commandResult.tickWindow
					} elseif ($null -ne $commandResult.tickWindow) {
						$tickWindow = [ordered]@{
							startTick = [Math]::Min([long]$tickWindow.startTick, [long]$commandResult.tickWindow.startTick)
							endTick = [Math]::Max([long]$tickWindow.endTick, [long]$commandResult.tickWindow.endTick)
						}
					}
					if ($batchPauseMs -gt 0 -and $serialBatch.Count -lt $serials.Count) {
						Start-Sleep -Milliseconds $batchPauseMs
					}
				}
				$command = if ($batchCommands.Count -le 1) { [string]$batchCommands[0] } else { @($batchCommands.ToArray()) }
				$response = [string]::Join("`n", @($batchResponses.ToArray()))
				$samples = @($sampleList.ToArray())
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
					tickWindow = $tickWindow
					samples = $samples
					mountTicksBySerial = $mountTicksBySerial
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_unmount" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "slash_list")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$chunkSize = [int](Get-OptionalProperty -Object $phase -Name "chunkSize" -DefaultValue $(if ($serials.Count -gt 256) { 256 } else { $serials.Count }))
				$batchPauseMs = [int](Get-OptionalProperty -Object $phase -Name "batchPauseMs" -DefaultValue $(if ($serials.Count -gt $chunkSize) { 100 } else { 0 }))
				$batchCommands = New-Object System.Collections.Generic.List[string]
				$batchResponses = New-Object System.Collections.Generic.List[string]
				foreach ($serialBatch in @(Split-SerialsIntoBatches -Serials $serials -ChunkSize $chunkSize)) {
					$batchSerialText = Format-SerialInputText -Serials $serialBatch -Style $serialFormat
					$batchCommand = Wrap-WithPlayerContext "redstonelink node trace unmount $type $batchSerialText"
					$batchResponse = Invoke-RconCommand -Connection $Connection -Command $batchCommand -Silent
					$batchCommands.Add($batchCommand)
					$batchResponses.Add([string]$batchResponse)
					if ($batchPauseMs -gt 0 -and $serialBatch.Count -lt $serials.Count) {
						Start-Sleep -Milliseconds $batchPauseMs
					}
				}
				$command = if ($batchCommands.Count -le 1) { [string]$batchCommands[0] } else { @($batchCommands.ToArray()) }
				$response = [string]::Join("`n", @($batchResponses.ToArray()))
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
				if ($DryRun) {
					$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
					$jobId = [long]$script:DryRunInputJobCounter
					$script:DryRunInputJobCounter++
					$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						endpoint = $endpoint
						serials = $serials
						serialText = $serialText
						jobId = $jobId
						jobStartTick = $jobStartTick
						command = $command
						response = "[RedstoneLink/Input] Started job=$jobId [DryRun]"
						tickWindow = $commandResult.tickWindow
						waveform = [ordered]@{
							periodTicks = $periodTicks
							highTicks = $highTicks
							highPower = $highPower
							lowPower = $lowPower
							phaseTicks = $phaseTicks
							totalTicks = $totalTicks
						}
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText ([string]$commandResult.response) `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Started job="
				$jobId = Extract-InputJobIdFromResponse -ResponseText ([string]$commandResult.response)
				$jobStartTick = Extract-InputJobStartTickFromResponse -ResponseText ([string]$commandResult.response)
				if ($null -eq $jobStartTick) {
					$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					endpoint = $endpoint
					serials = $serials
					serialText = $serialText
					jobId = $jobId
					jobStartTick = $jobStartTick
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
				if ($DryRun) {
					$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
					$jobId = [long]$script:DryRunInputJobCounter
					$script:DryRunInputJobCounter++
					$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						endpoint = $endpoint
						serials = $serials
						serialText = $serialText
						jobId = $jobId
						jobStartTick = $jobStartTick
						sequence = $sequence
						phaseTicks = $phaseTicks
						totalTicks = $totalTicks
						command = $command
						response = "[RedstoneLink/Input] Started job=$jobId [DryRun]"
						tickWindow = $commandResult.tickWindow
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText ([string]$commandResult.response) `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Started job="
				$jobId = Extract-InputJobIdFromResponse -ResponseText ([string]$commandResult.response)
				$jobStartTick = Extract-InputJobStartTickFromResponse -ResponseText ([string]$commandResult.response)
				if ($null -eq $jobStartTick) {
					$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					endpoint = $endpoint
					serials = $serials
					serialText = $serialText
					jobId = $jobId
					jobStartTick = $jobStartTick
					sequence = $sequence
					phaseTicks = $phaseTicks
					totalTicks = $totalTicks
					command = $command
					response = $commandResult.response
					tickWindow = $commandResult.tickWindow
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"input_start_custom_batch" {
				$entries = @($phase.entries)
				if ($entries.Count -le 0) {
					throw "input_start_custom_batch phase requires entries."
				}
				$phaseTicks = [int](Get-OptionalProperty -Object $phase -Name "phaseTicks" -DefaultValue 0)
				$totalTicks = [int](Get-OptionalProperty -Object $phase -Name "totalTicks" -DefaultValue 0)
				$entryTokens = New-Object System.Collections.Generic.List[string]
				$resolvedEntries = New-Object System.Collections.Generic.List[object]
				foreach ($entry in $entries) {
					$entryPhase = [ordered]@{}
					foreach ($property in $entry.PSObject.Properties) {
						$entryPhase[$property.Name] = $property.Value
					}
					$entrySerials = @(Resolve-PhaseSerials -Phase $entryPhase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
					$entrySerialFormat = [string](Get-OptionalProperty -Object $entry -Name "serialFormat" -DefaultValue "slash_list")
					$entrySerialText = Format-SerialInputText -Serials $entrySerials -Style $entrySerialFormat
					$entrySequence = [string](Get-OptionalProperty -Object $entry -Name "sequence" -DefaultValue "")
					if ([string]::IsNullOrWhiteSpace($entrySequence)) {
						throw "input_start_custom_batch entry requires sequence."
					}
					$entryTokens.Add(("{0}@{1}" -f $entrySerialText, $entrySequence))
					$resolvedEntries.Add([ordered]@{
						serials = $entrySerials
						serialText = $entrySerialText
						sequence = $entrySequence
					})
				}
				$batchToken = [string]::Join(";", @($entryTokens.ToArray()))
				$command = Wrap-WithPlayerContext (
					"redstonelink bench input start triggerSource custom_batch {0} {1} {2}" -f
					$batchToken,
					$phaseTicks,
					$totalTicks
				)
				if ($DryRun) {
					$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
					$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
					$dryRunJobIds = New-Object System.Collections.Generic.List[long]
					for ($index = 0; $index -lt $resolvedEntries.Count; $index++) {
						$dryRunJobIds.Add([long]$script:DryRunInputJobCounter)
						$script:DryRunInputJobCounter++
					}
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						jobId = if ($dryRunJobIds.Count -gt 0) { [long]$dryRunJobIds[0] } else { $null }
						jobIds = @($dryRunJobIds.ToArray())
						jobStartTick = $jobStartTick
						entries = @($resolvedEntries.ToArray())
						batchToken = $batchToken
						phaseTicks = $phaseTicks
						totalTicks = $totalTicks
						command = $command
						response = "[RedstoneLink/Input] Started job=$([string]::Join('/', @($dryRunJobIds.ToArray()))) [DryRun]"
						tickWindow = $commandResult.tickWindow
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$commandResult = Invoke-RconCommandWithTickWindow -Connection $Connection -Command $command -Silent
				Assert-BenchCommandResponse `
					-Command $command `
					-ResponseText ([string]$commandResult.response) `
					-ExpectedPrefix "[RedstoneLink/Input]" `
					-ExpectedRegex "Started job="
				$jobIds = @(Extract-InputJobIdsFromResponse -ResponseText ([string]$commandResult.response))
				$jobStartTick = Extract-InputJobStartTickFromResponse -ResponseText ([string]$commandResult.response)
				if ($null -eq $jobStartTick) {
					$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					jobId = if ($jobIds.Count -gt 0) { [long]$jobIds[0] } else { $null }
					jobIds = $jobIds
					jobStartTick = $jobStartTick
					entries = @($resolvedEntries.ToArray())
					batchToken = $batchToken
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
				if ($DryRun) {
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						command = $command
						response = "[RedstoneLink/Input] Cleared input jobs: [DryRun]"
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
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
				$commandDimension = [string](Get-OptionalProperty -Object $phase -Name "commandDimension" -DefaultValue "")
				$command = Wrap-WithBenchContexts `
					-Command $commandText `
					-Dimension $commandDimension `
					-SkipPlayerContext:$skipPlayerContext
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
				$allowEmptyResponse = [bool](Get-OptionalProperty -Object $phase -Name "allowEmptyResponse" -DefaultValue $false)
				$expectFailureResponse = [bool](Get-OptionalProperty -Object $phase -Name "expectFailureResponse" -DefaultValue $false)
				$receiveTimeoutMs = [int](Get-OptionalProperty -Object $phase -Name "receiveTimeoutMs" -DefaultValue 3000)

				if ($DryRun) {
					$dryRunResponse = if (-not [string]::IsNullOrWhiteSpace($expectedPrefix)) {
						"$expectedPrefix [DryRun] skipped"
					} else {
						"[DryRun] skipped"
					}
					$check = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "command_response"
						passed = $true
						skipped = $true
						reason = "dry_run"
						command = $command
						commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
						expectFailureResponse = $expectFailureResponse
						expectedPrefix = $expectedPrefix
						expectedRegexes = $expectedRegexes
						rejectRegexes = $rejectRegexes
					}
					$checks.Add($check)
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						command = $command
						commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
						response = $dryRunResponse
						tickWindow = $null
						expectFailureResponse = $expectFailureResponse
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
				$responseLooksLikeFailure = $false
				if ([string]::IsNullOrWhiteSpace($failureReason)) {
					if ([string]::IsNullOrWhiteSpace($normalizedResponse)) {
						if (-not $allowEmptyResponse) {
							$passed = $false
							$failureReason = "empty_response"
						}
					} else {
						$responseLooksLikeFailure = Test-FunctionalCommandAssertHardFailure -ResponseText $normalizedResponse
						if ($expectFailureResponse -and -not $responseLooksLikeFailure) {
							$passed = $false
							$failureReason = "expected_failure_missing"
						} elseif (-not $expectFailureResponse -and $responseLooksLikeFailure) {
							$passed = $false
							$failureReason = "failure_response"
						}
					}
					if ($passed -and -not [string]::IsNullOrWhiteSpace($expectedPrefix) -and -not $normalizedResponse.StartsWith($expectedPrefix, [System.StringComparison]::Ordinal)) {
						$passed = $false
						$failureReason = "prefix_mismatch"
					}
					if ($passed) {
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
					commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
					response = $response
					responseLooksLikeFailure = $responseLooksLikeFailure
					expectFailureResponse = $expectFailureResponse
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
					commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
					response = $response
					tickWindow = $tickWindow
					responseLooksLikeFailure = $responseLooksLikeFailure
					expectFailureResponse = $expectFailureResponse
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
				$referencePhaseRef = [string](Get-OptionalProperty -Object $phase -Name "referencePhaseRef" -DefaultValue "")
				$referenceStartStrategy = [string](Get-OptionalProperty -Object $phase -Name "referenceStartStrategy" -DefaultValue "min")
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
				$anchorJobStartTick = Get-OptionalProperty -Object $anchorPhaseResult -Name "jobStartTick"
				$resolvedAnchorStartTick = $commandStartTick
				if ($null -ne $anchorJobStartTick -and [long]$anchorJobStartTick -ge 0L) {
					$resolvedAnchorStartTick = [long]$anchorJobStartTick
				}
				$referenceStartTick = $null
				if (-not [string]::IsNullOrWhiteSpace($referencePhaseRef)) {
					$referencePhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $referencePhaseRef
					$referenceStartTick = Resolve-TraceLatencyReferenceStartTick `
						-ReferencePhaseResult $referencePhaseResult `
						-Strategy $referenceStartStrategy
				}
				$resolvedSearchAnchorStartTick = if ($null -ne $referenceStartTick) {
					[long]$referenceStartTick
				} else {
					[long]$resolvedAnchorStartTick
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
						referencePhaseRef = $referencePhaseRef
						referenceStartStrategy = $referenceStartStrategy
						limit = $limit
						alignmentSlackTicks = $alignmentSlackTicks
						expectedTicks = $expectedTicks
						summary = [ordered]@{
							type = $type
							requested = $serials.Count
							analyzed = 0
							matched = 0
							unmatched = $serials.Count
							expectedTickCount = $expectedTicks.Count
							anchorStartTick = $resolvedAnchorStartTick
							searchAnchorStartTick = $resolvedSearchAnchorStartTick
							referencePhaseRef = $referencePhaseRef
							referenceStartStrategy = $referenceStartStrategy
							referenceStartTick = $referenceStartTick
							matchedStartTickStats = (New-TraceLatencyStats -Values @())
						}
						reads = @()
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$phasePassed = $true
				$readResults = New-Object System.Collections.Generic.List[object]
				$matchedStartTicks = New-Object System.Collections.Generic.List[long]
				foreach ($serial in $serials) {
					$mountTick = Get-TraceMountTickForSerial -MountPhaseResult $mountPhaseResult -Serial $serial
					$mountCapacity = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "capacity" -DefaultValue $limit)
					$searchTickMax = 0L
					$startTickMin = $resolvedSearchAnchorStartTick
					if ($null -ne $mountTick) {
						$startTickMin = [Math]::Max([long]$startTickMin, ([long]$mountTick + 1L))
					}
					$startTickMax = [long]$resolvedSearchAnchorStartTick + [Math]::Max(0, $alignmentSlackTicks)
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
					if ($tickCheck.passed -and $null -ne $matchResult.startTick) {
						$matchedStartTicks.Add([long]$matchResult.startTick)
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
				$matchedStartTickArray = @($matchedStartTicks.ToArray())
				$matchedStartTickStats = New-TraceLatencyStats -Values $matchedStartTickArray
				$matchedCount = $matchedStartTickArray.Count
				$analyzedCount = $readResults.Count
				$unmatchedCount = [Math]::Max(0, $serials.Count - $matchedCount)
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					mountRef = $mountRef
					anchorRef = $anchorRef
					referencePhaseRef = $referencePhaseRef
					referenceStartStrategy = $referenceStartStrategy
					limit = $limit
					alignmentSlackTicks = $alignmentSlackTicks
					expectedTicks = $expectedTicks
					summary = [ordered]@{
						type = $type
						requested = $serials.Count
						analyzed = $analyzedCount
						matched = $matchedCount
						unmatched = $unmatchedCount
						expectedTickCount = $expectedTicks.Count
						anchorStartTick = $resolvedAnchorStartTick
						searchAnchorStartTick = $resolvedSearchAnchorStartTick
						referencePhaseRef = $referencePhaseRef
						referenceStartStrategy = $referenceStartStrategy
						referenceStartTick = $referenceStartTick
						matchedStartTickStats = $matchedStartTickStats
					}
					reads = @($readResults.ToArray())
					passed = $phasePassed
				}
				Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
			}
			"trace_sync_latency_collect" {
				$type = [string](Get-OptionalProperty -Object $phase -Name "type" -DefaultValue "")
				$serials = @(Resolve-PhaseSerials -Phase $phase -SourceSerialMaps $SourceSerialMaps -TargetSerialMap $TargetSerialMap)
				$serialFormat = [string](Get-OptionalProperty -Object $phase -Name "serialFormat" -DefaultValue "range")
				$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
				$mountRef = [string](Get-OptionalProperty -Object $phase -Name "mountRef" -DefaultValue "")
				$anchorRef = [string](Get-OptionalProperty -Object $phase -Name "anchorRef" -DefaultValue "")
				if ([string]::IsNullOrWhiteSpace($mountRef)) {
					throw "trace_sync_latency_collect phase requires mountRef."
				}
				if ([string]::IsNullOrWhiteSpace($anchorRef)) {
					throw "trace_sync_latency_collect phase requires anchorRef."
				}
				$mountPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $mountRef
				$anchorPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $anchorRef
				$mountEvery = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "every" -DefaultValue 0)
				if ($mountEvery -ne 1) {
					throw "trace_sync_latency_collect requires mountRef every=1."
				}
				$anchorTickWindow = Get-OptionalProperty -Object $anchorPhaseResult -Name "tickWindow"
				if ($null -eq $anchorTickWindow) {
					throw "trace_sync_latency_collect anchorRef must point to a command phase with tickWindow."
				}
				$commandStartTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "startTick" -DefaultValue -1L)
				if ($commandStartTick -lt 0L) {
					throw "trace_sync_latency_collect anchorRef tickWindow is invalid."
				}
				$commandEndTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "endTick" -DefaultValue $commandStartTick)
				if ($commandEndTick -lt $commandStartTick) {
					$commandEndTick = $commandStartTick
				}
				$anchorJobStartTick = Get-OptionalProperty -Object $anchorPhaseResult -Name "jobStartTick"
				$resolvedCommandStartTick = $commandStartTick
				if ($null -ne $anchorJobStartTick -and [long]$anchorJobStartTick -ge 0L) {
					$resolvedCommandStartTick = [long]$anchorJobStartTick
				}
				$anchorTickStrategy = ([string](Get-OptionalProperty -Object $phase -Name "anchorTickStrategy" -DefaultValue "start")).Trim().ToLowerInvariant()
				switch ($anchorTickStrategy) {
					"start" { $commandAnchorTick = $resolvedCommandStartTick }
					"end" { $commandAnchorTick = $commandEndTick }
					default { throw "Unsupported trace_sync_latency_collect anchorTickStrategy: $anchorTickStrategy" }
				}
				$expectedTicks = @(Resolve-TraceTickExpectations -Phase $phase -Type $type -PhaseContext $phaseContext)
				if ($expectedTicks.Count -le 0) {
					throw "trace_sync_latency_collect phase resolved no expected ticks."
				}
				$expectedPowers = @(Convert-TraceExpectationsToPowerSequence -ExpectedTicks $expectedTicks)
				$expectedSequenceText = Format-SignalSequenceText -Sequence $expectedPowers
				$referencePhaseRef = [string](Get-OptionalProperty -Object $phase -Name "referencePhaseRef" -DefaultValue "")
				$referenceStartStrategy = [string](Get-OptionalProperty -Object $phase -Name "referenceStartStrategy" -DefaultValue "min")
				$expectedDelayTicksRaw = Get-OptionalProperty -Object $phase -Name "expectedDelayTicks"
				$expectedDelayTicks = if ($null -eq $expectedDelayTicksRaw) { $null } else { [long]$expectedDelayTicksRaw }
				$windowTicks = @(Resolve-TraceLatencyWindowTicks -Phase $phase)
				$windowCoverageChecks = @(Resolve-TraceLatencyWindowCoverageChecks -Phase $phase)
				foreach ($windowCoverageCheck in @($windowCoverageChecks)) {
					$windowTicks += [int](Get-OptionalProperty -Object $windowCoverageCheck -Name "windowTicks" -DefaultValue 0)
				}
				$windowTicks = @($windowTicks | Sort-Object -Unique)
				$requiredMatchRatio = [double](Get-OptionalProperty -Object $phase -Name "requiredMatchRatio" -DefaultValue 1.0)
				$maxDelayTicksRaw = Get-OptionalProperty -Object $phase -Name "maxDelayTicks"
				$maxDelayTicks = if ($null -eq $maxDelayTicksRaw) { $null } else { [Math]::Max(0, [int]$maxDelayTicksRaw) }
				$latestExpectedStartTick = if ($null -eq $maxDelayTicks) { $null } else { ([long]$commandAnchorTick + [long]$maxDelayTicks) }
				$chunkSize = [int](Get-OptionalProperty -Object $phase -Name "chunkSize" -DefaultValue $(if ($serials.Count -gt 256) { 128 } else { $serials.Count }))
				$batchPauseMs = [int](Get-OptionalProperty -Object $phase -Name "batchPauseMs" -DefaultValue $(if ($serials.Count -gt $chunkSize) { 100 } else { 0 }))
				$command = if ($serials.Count -le $chunkSize) {
					$commandText = (
						"redstonelink bench trace sync_latency {0} {1} startTick={2} powers={3}" -f
						$type,
						$serialText,
						$commandAnchorTick,
						$expectedSequenceText
					)
					if ($null -ne $latestExpectedStartTick) {
						$commandText = ("{0} latestStartTick={1}" -f $commandText, $latestExpectedStartTick)
					}
					Wrap-WithPlayerContext ($commandText)
				} else {
					@()
				}
				if ($DryRun) {
					$phaseResult = [ordered]@{
						kind = $kind
						name = $phaseName
						type = $type
						serials = $serials
						serialText = $serialText
						mountRef = $mountRef
						anchorRef = $anchorRef
						anchorTickStrategy = $anchorTickStrategy
						command = $command
						expectedStartTick = $commandAnchorTick
						maxDelayTicks = $maxDelayTicks
						expectedPowers = $expectedPowers
						summary = [ordered]@{
							type = $type
							requested = $serials.Count
							analyzed = $serials.Count
							mounted = $serials.Count
							matched = $serials.Count
							unmatched = 0
							matchedRatio = 1.0
							expectedStartTick = $commandAnchorTick
							expectedTickCount = $expectedPowers.Count
							latestExpectedStartTick = $latestExpectedStartTick
							matchedStartTickStats = (New-TraceLatencyStats -Values @())
							inputDelayStats = (New-TraceLatencyStats -Values @())
							referencePhaseRef = $referencePhaseRef
							referenceStartTick = $null
							referenceDelayStats = (New-TraceLatencyStats -Values @())
							evaluatedDelayField = "inputDelayTicks"
							expectedDelayTicks = $expectedDelayTicks
							windowCoverage = @()
						}
						items = @()
						passed = $true
						dryRun = $true
					}
					Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
					continue
				}
				$batchCommands = New-Object System.Collections.Generic.List[object]
				$batchResponses = New-Object System.Collections.Generic.List[string]
				$parsedItems = New-Object System.Collections.Generic.List[object]
				foreach ($serialBatch in @(Split-SerialsIntoBatches -Serials $serials -ChunkSize $chunkSize)) {
					$batchSerialText = Format-SerialInputText -Serials $serialBatch -Style $serialFormat
					$batchCommandText = (
						"redstonelink bench trace sync_latency {0} {1} startTick={2} powers={3}" -f
						$type,
						$batchSerialText,
						$commandAnchorTick,
						$expectedSequenceText
					)
					if ($null -ne $latestExpectedStartTick) {
						$batchCommandText = ("{0} latestStartTick={1}" -f $batchCommandText, $latestExpectedStartTick)
					}
					$batchCommand = Wrap-WithPlayerContext ($batchCommandText)
					$batchResponse = Invoke-RconCommand -Connection $Connection -Command $batchCommand -Silent
					Assert-BenchCommandResponse `
						-Command $batchCommand `
						-ResponseText ([string]$batchResponse) `
						-ExpectedPrefix "[RedstoneLink/Bench] trace_sync_latency_summary"
					$parsedResponse = Parse-BenchTraceLatencyResponse -ResponseText ([string]$batchResponse)
					$batchCommands.Add($batchCommand)
					$batchResponses.Add([string]$batchResponse)
					foreach ($parsedItem in @($parsedResponse.items)) {
						$parsedItems.Add($parsedItem)
					}
					if ($batchPauseMs -gt 0 -and $serialBatch.Count -lt $serials.Count) {
						Start-Sleep -Milliseconds $batchPauseMs
					}
				}
				$command = if ($batchCommands.Count -le 1) { [string]$batchCommands[0] } else { @($batchCommands.ToArray()) }
				$response = [string]::Join("`n", @($batchResponses.ToArray()))
				$items = New-Object System.Collections.Generic.List[object]
				$matchedStartTicks = New-Object System.Collections.Generic.List[long]
				$inputDelayValues = New-Object System.Collections.Generic.List[long]
				foreach ($item in @($parsedItems.ToArray())) {
					$currentItem = [ordered]@{
						serial = [long](Get-OptionalProperty -Object $item -Name "serial" -DefaultValue 0L)
						matched = [bool](Get-OptionalProperty -Object $item -Name "matched" -DefaultValue $false)
						mounted = [bool](Get-OptionalProperty -Object $item -Name "mounted" -DefaultValue $false)
						reason = [string](Get-OptionalProperty -Object $item -Name "reason" -DefaultValue "")
						actualStartTick = Get-OptionalProperty -Object $item -Name "actualStartTick"
						inputDelayTicks = Get-OptionalProperty -Object $item -Name "inputDelayTicks"
						mountTick = [long](Get-OptionalProperty -Object $item -Name "mountTick" -DefaultValue -1)
						latestSampleTick = [long](Get-OptionalProperty -Object $item -Name "latestSampleTick" -DefaultValue -1)
						eligibleSamples = [int](Get-OptionalProperty -Object $item -Name "eligibleSamples" -DefaultValue 0)
					}
					if ($currentItem.matched -and $null -ne $currentItem.actualStartTick) {
						$matchedStartTicks.Add([long]$currentItem.actualStartTick)
					}
					if ($currentItem.matched -and $null -ne $currentItem.inputDelayTicks) {
						$inputDelayValues.Add([long]$currentItem.inputDelayTicks)
					}
					$items.Add([pscustomobject]$currentItem)
				}
				$requestedCount = $serials.Count
				$analyzedCount = $items.Count
				$mountedCount = @($items.ToArray() | Where-Object { [bool]$_.mounted }).Count
				$matchedCount = @($items.ToArray() | Where-Object { [bool]$_.matched }).Count
				$unmatchedCount = [Math]::Max(0, $requestedCount - $matchedCount)
				$referenceStartTick = $null
				$referenceDelayValues = New-Object System.Collections.Generic.List[long]
				$evaluatedDelayField = "inputDelayTicks"
				if (-not [string]::IsNullOrWhiteSpace($referencePhaseRef)) {
					$referencePhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $phaseContext -PhaseName $referencePhaseRef
					$referenceStartTick = Resolve-TraceLatencyReferenceStartTick `
						-ReferencePhaseResult $referencePhaseResult `
						-Strategy $referenceStartStrategy
					foreach ($item in @($items.ToArray())) {
						if ($item.matched -and $null -ne $item.actualStartTick -and $null -ne $referenceStartTick) {
							$item | Add-Member -NotePropertyName "referenceDelayTicks" -NotePropertyValue ([long]$item.actualStartTick - [long]$referenceStartTick)
							$referenceDelayValues.Add([long]$item.referenceDelayTicks)
						} else {
							$item | Add-Member -NotePropertyName "referenceDelayTicks" -NotePropertyValue $null
						}
					}
					$evaluatedDelayField = "referenceDelayTicks"
				}
				$matchedRatio = if ($requestedCount -le 0) {
					0.0
				} else {
					[Math]::Round(($matchedCount / [double]$requestedCount), 6)
				}
				$windowCoverage = New-Object System.Collections.Generic.List[object]
				foreach ($windowTick in @($windowTicks)) {
					$coveredCount = 0
					foreach ($item in @($items.ToArray())) {
						$delayValue = $item.PSObject.Properties[$evaluatedDelayField].Value
						if ($item.matched -and $null -ne $delayValue -and [long]$delayValue -le [int]$windowTick) {
							$coveredCount++
						}
					}
					$coverageRatio = if ($matchedCount -le 0) { 0.0 } else { [Math]::Round(($coveredCount / [double]$matchedCount), 6) }
					$windowCoverage.Add([ordered]@{
						windowTicks = [int]$windowTick
						covered = $coveredCount
						totalMatched = $matchedCount
						coverageRatio = $coverageRatio
					})
				}
				$matchedStartTickArray = @($matchedStartTicks.ToArray())
				$inputDelayValueArray = @($inputDelayValues.ToArray())
				$referenceDelayValueArray = @($referenceDelayValues.ToArray())
				$matchedStartTickStats = New-TraceLatencyStats -Values $matchedStartTickArray
				$inputDelayStats = New-TraceLatencyStats -Values $inputDelayValueArray
				$referenceDelayStats = New-TraceLatencyStats -Values $referenceDelayValueArray
				$evaluatedDelayStats = if ($evaluatedDelayField -eq "referenceDelayTicks") {
					$referenceDelayStats
				} else {
					$inputDelayStats
				}
				$evaluatedDelayValues = if ($evaluatedDelayField -eq "referenceDelayTicks") {
					$referenceDelayValueArray
				} else {
					$inputDelayValueArray
				}
				$phasePassed = ($matchedRatio -ge $requiredMatchRatio)
				$matchCheck = [ordered]@{
					phase = $phaseName
					kind = $kind
					scope = "matched_ratio"
					type = $type
					passed = $phasePassed
					expectedMinimum = $requiredMatchRatio
					actual = $matchedRatio
					requested = $requestedCount
					matched = $matchedCount
				}
				$checks.Add($matchCheck)
				if (-not $matchCheck.passed) {
					$failedChecks.Add($matchCheck)
				}
				if ($null -ne $expectedDelayTicks) {
					# 参数化窗口场景直接校验实际延迟值，避免只看覆盖率时无法区分“正好延迟 N tick”和“更早到达”。
					$actualDelayMinimum = $null
					$actualDelayMaximum = $null
					$sortedEvaluatedDelayValues = @(@($evaluatedDelayValues) | Sort-Object)
					if ($sortedEvaluatedDelayValues.Count -gt 0) {
						$actualDelayMinimum = [long]$sortedEvaluatedDelayValues[0]
						$actualDelayMaximum = [long]$sortedEvaluatedDelayValues[$sortedEvaluatedDelayValues.Count - 1]
					}
					$delayPassed = $false
					if ($matchedCount -gt 0 -and $null -ne $actualDelayMinimum -and $null -ne $actualDelayMaximum) {
						$delayPassed = (
							([long]$actualDelayMinimum -eq [long]$expectedDelayTicks) -and
							([long]$actualDelayMaximum -eq [long]$expectedDelayTicks)
						)
					}
					$delayCheck = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "delay_ticks"
						type = $type
						delayField = $evaluatedDelayField
						passed = $delayPassed
						expected = [long]$expectedDelayTicks
						actualMinimum = $actualDelayMinimum
						actualMaximum = $actualDelayMaximum
						matched = $matchedCount
						evaluated = [int]$matchedCount
						evaluatedMatched = [int]$matchedCount
						delayCheckVersion = "v2"
					}
					$checks.Add($delayCheck)
					if (-not $delayCheck.passed) {
						$failedChecks.Add($delayCheck)
					}
					$phasePassed = $phasePassed -and $delayCheck.passed
				}
				foreach ($windowCoverageCheck in @($windowCoverageChecks)) {
					$targetWindowTick = [int](Get-OptionalProperty -Object $windowCoverageCheck -Name "windowTicks" -DefaultValue 0)
					$expectedMinimum = Get-OptionalProperty -Object $windowCoverageCheck -Name "expectedMinimum"
					$expectedMaximum = Get-OptionalProperty -Object $windowCoverageCheck -Name "expectedMaximum"
					$windowCoverageEntry = @(
						@($windowCoverage.ToArray()) |
							Where-Object { [int](Get-OptionalProperty -Object $_ -Name "windowTicks" -DefaultValue -1) -eq $targetWindowTick } |
							Select-Object -First 1
					) | Select-Object -First 1
					$coveragePassed = ($null -ne $windowCoverageEntry)
					$actualCoverage = if ($null -eq $windowCoverageEntry) {
						$null
					} else {
						[double](Get-OptionalProperty -Object $windowCoverageEntry -Name "coverageRatio" -DefaultValue 0.0)
					}
					$coveredCount = if ($null -eq $windowCoverageEntry) {
						0
					} else {
						[int](Get-OptionalProperty -Object $windowCoverageEntry -Name "covered" -DefaultValue 0)
					}
					if ($coveragePassed -and $null -ne $expectedMinimum -and [double]$actualCoverage -lt [double]$expectedMinimum) {
						$coveragePassed = $false
					}
					if ($coveragePassed -and $null -ne $expectedMaximum -and [double]$actualCoverage -gt [double]$expectedMaximum) {
						$coveragePassed = $false
					}
					$coverageCheckResult = [ordered]@{
						phase = $phaseName
						kind = $kind
						scope = "window_coverage"
						type = $type
						windowTicks = $targetWindowTick
						passed = $coveragePassed
						expectedMinimum = $expectedMinimum
						expectedMaximum = $expectedMaximum
						actual = $actualCoverage
						covered = $coveredCount
						totalMatched = $matchedCount
					}
					$checks.Add($coverageCheckResult)
					if (-not $coverageCheckResult.passed) {
						$failedChecks.Add($coverageCheckResult)
					}
					$phasePassed = $phasePassed -and $coverageCheckResult.passed
				}
				$phaseResult = [ordered]@{
					kind = $kind
					name = $phaseName
					type = $type
					serials = $serials
					serialText = $serialText
					mountRef = $mountRef
					anchorRef = $anchorRef
					anchorTickStrategy = $anchorTickStrategy
					referencePhaseRef = $referencePhaseRef
					command = $command
					response = $response
					expectedStartTick = $commandAnchorTick
					maxDelayTicks = $maxDelayTicks
					expectedPowers = $expectedPowers
					summary = [ordered]@{
						type = $type
						requested = $requestedCount
						analyzed = $analyzedCount
						mounted = $mountedCount
						matched = $matchedCount
						unmatched = $unmatchedCount
						matchedRatio = $matchedRatio
						expectedStartTick = $commandAnchorTick
						expectedTickCount = $expectedPowers.Count
						latestExpectedStartTick = $latestExpectedStartTick
						matchedStartTickStats = $matchedStartTickStats
						inputDelayStats = $inputDelayStats
						referencePhaseRef = $referencePhaseRef
						referenceStartTick = $referenceStartTick
						referenceDelayStats = $referenceDelayStats
						evaluatedDelayField = $evaluatedDelayField
						expectedDelayTicks = $expectedDelayTicks
						windowCoverage = @($windowCoverage.ToArray())
					}
					items = @($items.ToArray())
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
