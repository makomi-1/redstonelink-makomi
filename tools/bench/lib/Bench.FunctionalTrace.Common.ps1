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
		"(?i)\binsufficient permission\b",
		"(?i)\bplayer[- ]only\b",
		"\u6ca1\u6709\u8db3\u591f\u6743\u9650",
		"\u64cd\u4f5c\u8fc7\u4e8e\u9891\u7e41",
		"\u65e0\u6548\u5e8f\u53f7",
		"\u975e\u6cd5\u5b57\u7b26",
		"\u76ee\u6807\u4e3a\u7a7a",
		"\u8f93\u5165\u4e3a\u7a7a",
		"\u6e90\u5e8f\u53f7\\s*\\d+\\s*\u5df2\u9000\u5f79",
		"\u76ee\u6807\u5e8f\u53f7\\s*\\d+\\s*\u5df2\u9000\u5f79",
		"\u4ee5\u4e0b\u76ee\u6807\u5e8f\u53f7\u5df2\u9000\u5f79",
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

