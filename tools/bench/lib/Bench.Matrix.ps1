<#
.SYNOPSIS
bench 模块：矩阵、模板、case 与 serial 输入解析。
#>

function Get-OptionalProperty {
	param(
		$Object,
		[string]$Name,
		$DefaultValue = $null
	)
	if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) {
		return $DefaultValue
	}
	if ($Object -is [System.Collections.IDictionary]) {
		if ($Object.Contains($Name)) {
			return $Object[$Name]
		}
		return $DefaultValue
	}
	$property = $Object.PSObject.Properties[$Name]
	if ($null -eq $property) {
		return $DefaultValue
	}
	if ($null -eq $property.Value) {
		return $DefaultValue
	}
	return $property.Value
}

function Set-BenchObjectProperty {
	param(
		$Object,
		[string]$Name,
		$Value
	)
	if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) {
		return
	}
	if ($Object -is [System.Collections.IDictionary]) {
		$Object[$Name] = $Value
		return
	}
	# PowerShell 5 的 PSCustomObject 不能直接通过赋值新增属性，这里统一补属性。
	$Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
}

function Resolve-PathFromBase {
	param(
		[string]$BaseDirectory,
		[string]$CandidatePath
	)
	$normalizedCandidatePath = if ($null -eq $CandidatePath) {
		""
	} else {
		([string]$CandidatePath).Trim().Trim('"')
	}
	if ([string]::IsNullOrWhiteSpace($normalizedCandidatePath)) {
		return $null
	}
	$normalizedBaseDirectory = if ($null -eq $BaseDirectory) {
		""
	} else {
		([string]$BaseDirectory).Trim().Trim('"')
	}
	if ([System.IO.Path]::IsPathRooted($normalizedCandidatePath)) {
		return [System.IO.Path]::GetFullPath($normalizedCandidatePath)
	}
	return [System.IO.Path]::GetFullPath((Join-Path $normalizedBaseDirectory $normalizedCandidatePath))
}

function ConvertTo-NormalizedBenchValue {
	param($Value)
	if ($null -eq $Value) {
		return $null
	}
	if ($Value -is [System.Collections.IDictionary]) {
		$map = [ordered]@{}
		foreach ($key in $Value.Keys) {
			$map[[string]$key] = ConvertTo-NormalizedBenchValue -Value $Value[$key]
		}
		return $map
	}
	if ($Value -is [pscustomobject]) {
		$map = [ordered]@{}
		foreach ($property in $Value.PSObject.Properties) {
			$map[$property.Name] = ConvertTo-NormalizedBenchValue -Value $property.Value
		}
		return $map
	}
	if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
		$list = New-Object System.Collections.Generic.List[object]
		foreach ($item in $Value) {
			$list.Add((ConvertTo-NormalizedBenchValue -Value $item))
		}
		return @($list.ToArray())
	}
	return $Value
}

function Convert-BenchParametersToOrderedMap {
	param($Parameters)
	$map = [ordered]@{}
	if ($null -eq $Parameters) {
		return $map
	}
	if ($Parameters -is [System.Collections.IDictionary]) {
		foreach ($keyObject in $Parameters.Keys) {
			$key = [string]$keyObject
			$map[$key] = ConvertTo-NormalizedBenchValue -Value $Parameters[$keyObject]
		}
		return $map
	}
	foreach ($property in $Parameters.PSObject.Properties) {
		$map[[string]$property.Name] = ConvertTo-NormalizedBenchValue -Value $property.Value
	}
	return $map
}

function Merge-BenchParameterMaps {
	param(
		$BaseParameters,
		$OverrideParameters
	)
	$merged = Convert-BenchParametersToOrderedMap -Parameters $BaseParameters
	$overrideMap = Convert-BenchParametersToOrderedMap -Parameters $OverrideParameters
	foreach ($keyObject in $overrideMap.Keys) {
		$key = [string]$keyObject
		$merged[$key] = Copy-NormalizedBenchValue -Value $overrideMap[$key]
	}
	return $merged
}

function Convert-BenchParameterValueToString {
	param($Value)
	if ($null -eq $Value) {
		throw "Template parameter resolved to null."
	}
	if ($Value -is [bool]) {
		return $Value.ToString().ToLowerInvariant()
	}
	if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
		$list = New-Object System.Collections.Generic.List[string]
		foreach ($item in $Value) {
			$list.Add((Convert-BenchParameterValueToString -Value $item))
		}
		return [string]::Join("/", @($list.ToArray()))
	}
	return [string]$Value
}

function Resolve-BenchParameterizedString {
	param(
		[string]$Text,
		$Parameters
	)
	if ([string]::IsNullOrEmpty($Text)) {
		return $Text
	}
	$parameterMap = Convert-BenchParametersToOrderedMap -Parameters $Parameters
	$pattern = "\{\{\s*param:(?<name>[A-Za-z0-9_.-]+)\s*\}\}"
	return [System.Text.RegularExpressions.Regex]::Replace(
		$Text,
		$pattern,
		{
			param($match)
			$parameterName = [string]$match.Groups["name"].Value
			if (-not $parameterMap.Contains($parameterName)) {
				throw "Missing bench parameter: $parameterName"
			}
			return [string](Convert-BenchParameterValueToString -Value $parameterMap[$parameterName])
		}
	)
}

function Expand-BenchParameterizedValue {
	param(
		$Value,
		$Parameters
	)
	if ($null -eq $Value) {
		return $null
	}
	if ($Value -is [System.Collections.IDictionary]) {
		$expanded = [ordered]@{}
		foreach ($keyObject in $Value.Keys) {
			$key = [string]$keyObject
			$expanded[$key] = Expand-BenchParameterizedValue -Value $Value[$key] -Parameters $Parameters
		}
		return $expanded
	}
	if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
		$list = New-Object System.Collections.Generic.List[object]
		foreach ($item in $Value) {
			$list.Add((Expand-BenchParameterizedValue -Value $item -Parameters $Parameters))
		}
		return @($list.ToArray())
	}
	if ($Value -is [string]) {
		return (Resolve-BenchParameterizedString -Text ([string]$Value) -Parameters $Parameters)
	}
	return $Value
}

function Resolve-CaseConfigParameters {
	param(
		$CaseConfig,
		$Parameters
	)
	$defaultParameters = Get-OptionalProperty -Object $CaseConfig -Name "parameters"
	return (Merge-BenchParameterMaps -BaseParameters $defaultParameters -OverrideParameters $Parameters)
}

function Expand-CaseConfigWithParameters {
	param(
		$CaseConfig,
		$Parameters
	)
	$resolvedParameters = Resolve-CaseConfigParameters -CaseConfig $CaseConfig -Parameters $Parameters
	if ($resolvedParameters.Count -le 0) {
		return $CaseConfig
	}
	$expandedCase = Expand-BenchParameterizedValue `
		-Value (ConvertTo-NormalizedBenchValue -Value $CaseConfig) `
		-Parameters $resolvedParameters
	$caseObject = ConvertTo-PSObjectTree -Value $expandedCase
	Set-BenchObjectProperty -Object $caseObject -Name "id" -Value ([string](Get-OptionalProperty -Object $CaseConfig -Name "id" -DefaultValue ""))
	Set-BenchObjectProperty -Object $caseObject -Name "parameters" -Value (ConvertTo-PSObjectTree -Value $resolvedParameters)
	return $caseObject
}

function Copy-NormalizedBenchValue {
	param($Value)
	if ($null -eq $Value) {
		return $null
	}
	if ($Value -is [System.Collections.IDictionary]) {
		$copy = [ordered]@{}
		foreach ($key in $Value.Keys) {
			$copy[[string]$key] = Copy-NormalizedBenchValue -Value $Value[$key]
		}
		return $copy
	}
	if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
		$list = New-Object System.Collections.Generic.List[object]
		foreach ($item in $Value) {
			$list.Add((Copy-NormalizedBenchValue -Value $item))
		}
		return @($list.ToArray())
	}
	return $Value
}

function Merge-BenchTemplateData {
	param(
		[System.Collections.IDictionary]$BaseValue,
		[System.Collections.IDictionary]$OverrideValue
	)
	$merged = [ordered]@{}
	if ($null -ne $BaseValue) {
		foreach ($key in $BaseValue.Keys) {
			$merged[[string]$key] = Copy-NormalizedBenchValue -Value $BaseValue[$key]
		}
	}
	if ($null -eq $OverrideValue) {
		return $merged
	}
	foreach ($keyObject in $OverrideValue.Keys) {
		$key = [string]$keyObject
		if ($key -eq "templateRef") {
			continue
		}
		$overrideEntry = $OverrideValue[$key]
		if ($merged.Contains($key) -and $merged[$key] -is [System.Collections.IDictionary] -and $overrideEntry -is [System.Collections.IDictionary]) {
			$merged[$key] = Merge-BenchTemplateData -BaseValue $merged[$key] -OverrideValue $overrideEntry
			continue
		}
		$merged[$key] = Copy-NormalizedBenchValue -Value $overrideEntry
	}
	return $merged
}

function Expand-BenchTemplateRefs {
	param(
		$Value,
		[System.Collections.IDictionary]$TemplateRegistry
	)
	if ($null -eq $Value) {
		return $null
	}
	if ($Value -is [System.Collections.IDictionary]) {
		$working = $Value
		if ($working.Contains("templateRef")) {
			$templateRef = [string]$working["templateRef"]
			if ([string]::IsNullOrWhiteSpace($templateRef)) {
				throw "templateRef cannot be empty."
			}
			if ($null -eq $TemplateRegistry -or -not $TemplateRegistry.Contains($templateRef)) {
				throw "Template not found: $templateRef"
			}
			$working = Merge-BenchTemplateData `
				-BaseValue (Expand-BenchTemplateRefs -Value $TemplateRegistry[$templateRef] -TemplateRegistry $TemplateRegistry) `
				-OverrideValue $working
		}
		$expanded = [ordered]@{}
		foreach ($keyObject in $working.Keys) {
			$key = [string]$keyObject
			if ($key -eq "templateRef") {
				continue
			}
			$expanded[$key] = Expand-BenchTemplateRefs -Value $working[$key] -TemplateRegistry $TemplateRegistry
		}
		return $expanded
	}
	if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
		$list = New-Object System.Collections.Generic.List[object]
		foreach ($item in $Value) {
			$list.Add((Expand-BenchTemplateRefs -Value $item -TemplateRegistry $TemplateRegistry))
		}
		return @($list.ToArray())
	}
	return $Value
}

function ConvertTo-PSObjectTree {
	param($Value)
	if ($null -eq $Value) {
		return $null
	}
	if ($Value -is [System.Collections.IDictionary]) {
		$properties = [ordered]@{}
		foreach ($keyObject in $Value.Keys) {
			$key = [string]$keyObject
			$properties[$key] = ConvertTo-PSObjectTree -Value $Value[$key]
		}
		return [pscustomobject]$properties
	}
	if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
		$list = New-Object System.Collections.Generic.List[object]
		foreach ($item in $Value) {
			$list.Add((ConvertTo-PSObjectTree -Value $item))
		}
		return @($list.ToArray())
	}
	return $Value
}

function Import-BenchTemplateRegistry {
	param(
		[string]$MatrixDirectory,
		$MatrixData
	)
	$registry = [ordered]@{}
	foreach ($templatePath in @(Get-OptionalProperty -Object $MatrixData -Name "templatePaths" -DefaultValue @())) {
		$resolvedTemplatePath = Resolve-PathFromBase -BaseDirectory $MatrixDirectory -CandidatePath ([string]$templatePath)
		if (-not (Test-Path -LiteralPath $resolvedTemplatePath -PathType Leaf)) {
			throw "Template file not found: $resolvedTemplatePath"
		}
		$templateConfig = ConvertTo-NormalizedBenchValue -Value (
			Get-Content -Path $resolvedTemplatePath -Encoding UTF8 -Raw | ConvertFrom-Json
		)
		$templateMap = Get-OptionalProperty -Object $templateConfig -Name "templates"
		if ($null -eq $templateMap) {
			throw "Template file must provide templates object: $resolvedTemplatePath"
		}
		foreach ($templateName in $templateMap.Keys) {
			$registry[[string]$templateName] = Copy-NormalizedBenchValue -Value $templateMap[$templateName]
		}
	}
	return $registry
}

function Load-BenchCaseDefinitions {
	param(
		[string]$MatrixDirectory,
		$MatrixData,
		[System.Collections.IDictionary]$TemplateRegistry
	)
	$loadedCases = New-Object System.Collections.Generic.List[object]
	$knownCaseIds = @{}

	foreach ($inlineCase in @(Get-OptionalProperty -Object $MatrixData -Name "cases" -DefaultValue @())) {
		$expandedCase = Expand-BenchTemplateRefs `
			-Value (ConvertTo-NormalizedBenchValue -Value $inlineCase) `
			-TemplateRegistry $TemplateRegistry
		$caseId = [string](Get-OptionalProperty -Object $expandedCase -Name "id" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($caseId)) {
			throw "Case id is required in matrix file."
		}
		if ($knownCaseIds.ContainsKey($caseId)) {
			throw "Duplicate case id in matrix: $caseId"
		}
		$knownCaseIds[$caseId] = $true
		$loadedCases.Add($expandedCase)
	}

	foreach ($casePath in @(Get-OptionalProperty -Object $MatrixData -Name "casePaths" -DefaultValue @())) {
		$resolvedCasePath = Resolve-PathFromBase -BaseDirectory $MatrixDirectory -CandidatePath ([string]$casePath)
		if (-not (Test-Path -LiteralPath $resolvedCasePath -PathType Leaf)) {
			throw "Case file not found: $resolvedCasePath"
		}
		$expandedCase = Expand-BenchTemplateRefs `
			-Value (ConvertTo-NormalizedBenchValue -Value (
				Get-Content -Path $resolvedCasePath -Encoding UTF8 -Raw | ConvertFrom-Json
			)) `
			-TemplateRegistry $TemplateRegistry
		$caseId = [string](Get-OptionalProperty -Object $expandedCase -Name "id" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($caseId)) {
			throw "Case id is required in case file: $resolvedCasePath"
		}
		if ($knownCaseIds.ContainsKey($caseId)) {
			throw "Duplicate case id in matrix: $caseId"
		}
		$knownCaseIds[$caseId] = $true
		$loadedCases.Add($expandedCase)
	}

	return @($loadedCases.ToArray())
}

function Get-MatrixConfig {
	param([string]$Path)
	if (-not (Test-Path $Path)) {
		throw "Matrix file not found: $Path"
	}
	$fullMatrixPath = [System.IO.Path]::GetFullPath($Path)
	$matrixDirectory = Split-Path -Path $fullMatrixPath -Parent
	$matrixConfig = ConvertTo-NormalizedBenchValue -Value (
		Get-Content -Path $fullMatrixPath -Encoding UTF8 -Raw | ConvertFrom-Json
	)
	$templateRegistry = Import-BenchTemplateRegistry -MatrixDirectory $matrixDirectory -MatrixData $matrixConfig
	$matrixConfig["cases"] = Load-BenchCaseDefinitions `
		-MatrixDirectory $matrixDirectory `
		-MatrixData $matrixConfig `
		-TemplateRegistry $templateRegistry
	return (ConvertTo-PSObjectTree -Value $matrixConfig)
}

function Get-CaseConfig {
	param(
		$Matrix,
		[string]$Id,
		$Parameters = $null
	)
	if ([string]::IsNullOrWhiteSpace($Id)) {
		throw "CaseId is required for action $Action."
	}
	foreach ($case in $Matrix.cases) {
		if ($case.id -eq $Id) {
			return (Expand-CaseConfigWithParameters -CaseConfig $case -Parameters $Parameters)
		}
	}
	throw "Case not found: $Id"
}

function Get-SerialListFromMap {
	param([hashtable]$Map)
	return @($Map.GetEnumerator() | Sort-Object Name | ForEach-Object { [long]$_.Value })
}

function Get-SortedUniqueSerials {
	param([long[]]$Serials)
	if ($null -eq $Serials) {
		return @()
	}
	return @(
		$Serials |
			Where-Object { $null -ne $_ -and [long]$_ -gt 0L } |
			ForEach-Object { [long]$_ } |
			Sort-Object -Unique
	)
}

function Format-RangeSerialSegments {
	param([long[]]$Serials)
	$normalized = @(Get-SortedUniqueSerials $Serials)
	if ($normalized.Count -eq 0) {
		return @()
	}
	$segments = New-Object System.Collections.Generic.List[string]
	$runStart = $normalized[0]
	$runEnd = $normalized[0]
	for ($index = 1; $index -lt $normalized.Count; $index++) {
		$current = [long]$normalized[$index]
		if ($current -eq ($runEnd + 1L)) {
			$runEnd = $current
			continue
		}
		if ($runStart -eq $runEnd) {
			$segments.Add([string]$runStart)
		} else {
			$segments.Add(("{0}:{1}" -f $runStart, $runEnd))
		}
		$runStart = $current
		$runEnd = $current
	}
	if ($runStart -eq $runEnd) {
		$segments.Add([string]$runStart)
	} else {
		$segments.Add(("{0}:{1}" -f $runStart, $runEnd))
	}
	return @($segments.ToArray())
}

function Format-MixedSerialSegments {
	param([long[]]$Serials)
	$normalized = @(Get-SortedUniqueSerials $Serials)
	if ($normalized.Count -eq 0) {
		return @()
	}
	$segments = New-Object System.Collections.Generic.List[string]
	$index = 0
	while ($index -lt $normalized.Count) {
		$current = [long]$normalized[$index]
		if ($index + 1 -lt $normalized.Count) {
			$next = [long]$normalized[$index + 1]
			if ($next -eq ($current + 1L)) {
				$segments.Add(("{0}:{1}" -f $current, $next))
				$index += 2
				continue
			}
		}
		$segments.Add([string]$current)
		$index++
	}
	return @($segments.ToArray())
}

function Format-SerialInputText {
	param(
		[long[]]$Serials,
		[string]$Style = "slash_list"
	)
	$normalized = @(Get-SortedUniqueSerials $Serials)
	switch ([string]$Style) {
		"slash_list" {
			return ($normalized -join "/")
		}
		"range" {
			return ((Format-RangeSerialSegments $normalized) -join "/")
		}
		"mixed" {
			return ((Format-MixedSerialSegments $normalized) -join "/")
		}
		"duplicate_mixed" {
			if ($normalized.Count -eq 0) {
				return ""
			}
			$base = (Format-MixedSerialSegments $normalized) -join "/"
			return "$base/$($normalized[0])"
		}
		default {
			throw "Unsupported serial format style: $Style"
		}
	}
}

function Select-SerialsByIndex {
	param(
		[long[]]$Serials,
		$IndexValues
	)
	$normalizedSerials = @(Get-SortedUniqueSerials $Serials)
	if ($null -eq $IndexValues) {
		return $normalizedSerials
	}
	$rawIndexes = @($IndexValues)
	if ($rawIndexes.Count -eq 0) {
		return $normalizedSerials
	}
	$selected = New-Object System.Collections.Generic.List[long]
	foreach ($rawIndex in $rawIndexes) {
		$index = [int]$rawIndex
		if ($index -lt 0 -or $index -ge $normalizedSerials.Count) {
			throw "Serial index out of range: $index (count=$($normalizedSerials.Count))"
		}
		$selected.Add([long]$normalizedSerials[$index])
	}
	return @(Get-SortedUniqueSerials $selected.ToArray())
}
