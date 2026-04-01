<#
.SYNOPSIS
bench suite 模块：suite 配置、密码输入与路径默认值解析。
#>

function New-Utf8NoBomEncoding {
	return (New-Object System.Text.UTF8Encoding($false))
}

function Write-Utf8NoBomFile {
	param(
		[string]$Path,
		[string]$Content
	)
	[System.IO.File]::WriteAllText($Path, $Content, (New-Utf8NoBomEncoding))
}

function Read-Utf8Text {
	param([string]$Path)
	return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Convert-PasswordInputToSecureString {
	param($SecretInput)
	if ($null -eq $SecretInput) {
		return $null
	}
	if ($SecretInput -is [System.Security.SecureString]) {
		return $SecretInput
	}
	if ($SecretInput -is [string]) {
		if ([string]::IsNullOrWhiteSpace($SecretInput)) {
			return $null
		}
		return (ConvertTo-SecureString -String $SecretInput -AsPlainText -Force)
	}
	throw "RconPassword must be a plain text string or SecureString."
}

function Convert-SecureStringToPlainText {
	param([System.Security.SecureString]$Password)
	if ($null -eq $Password) {
		return ""
	}
	return ([System.Net.NetworkCredential]::new("", $Password)).Password
}

function Get-OptionalObjectProperty {
	param(
		$Object,
		[string]$PropertyName,
		$DefaultValue = $null
	)
	if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($PropertyName)) {
		return $DefaultValue
	}
	if ($Object -is [System.Collections.IDictionary]) {
		if ($Object.Contains($PropertyName)) {
			return $Object[$PropertyName]
		}
		return $DefaultValue
	}
	$property = $Object.PSObject.Properties[$PropertyName]
	if ($null -eq $property) {
		return $DefaultValue
	}
	if ($null -eq $property.Value) {
		return $DefaultValue
	}
	return $property.Value
}

function Convert-OptionalObjectToOrderedMap {
	param($Object)
	$map = [ordered]@{}
	if ($null -eq $Object) {
		return $map
	}
	if ($Object -is [System.Collections.IDictionary]) {
		foreach ($entry in $Object.GetEnumerator()) {
			$map[[string]$entry.Key] = $entry.Value
		}
		return $map
	}
	foreach ($property in $Object.PSObject.Properties) {
		$map[[string]$property.Name] = $property.Value
	}
	return $map
}

function Merge-OptionalObjectMaps {
	param(
		$BaseObject,
		$OverrideObject
	)
	$merged = Convert-OptionalObjectToOrderedMap -Object $BaseObject
	$overrideMap = Convert-OptionalObjectToOrderedMap -Object $OverrideObject
	foreach ($entry in $overrideMap.GetEnumerator()) {
		$merged[[string]$entry.Key] = $entry.Value
	}
	return $merged
}

function Get-SuiteConfig {
	param([string]$Path)
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
		throw "Suite file not found: $Path"
	}
	return (Get-Content -Path $Path -Encoding UTF8 -Raw | ConvertFrom-Json)
}

function Resolve-CaseIdList {
	param(
		$Matrix,
		[string[]]$RequestedCaseIds
	)
	if ($null -eq $RequestedCaseIds -or $RequestedCaseIds.Count -eq 0) {
		return @($Matrix.cases | ForEach-Object { [string]$_.id })
	}

	$knownIds = @{}
	foreach ($case in $Matrix.cases) {
		$knownIds[[string]$case.id] = $true
	}

	$resolved = New-Object System.Collections.Generic.List[string]
	foreach ($caseId in $RequestedCaseIds) {
		if (-not $knownIds.ContainsKey([string]$caseId)) {
			throw "Case not found in matrix: $caseId"
		}
		$resolved.Add([string]$caseId)
	}
	return @($resolved.ToArray())
}

function Resolve-SuiteRequestedEntries {
	param(
		$SuiteEntries,
		[string[]]$RequestedCaseIds
	)
	if ($null -eq $RequestedCaseIds -or $RequestedCaseIds.Count -eq 0) {
		return @($SuiteEntries)
	}

	$requestedLookup = [ordered]@{}
	foreach ($requestedId in $RequestedCaseIds) {
		$normalizedRequestedId = [string]$requestedId
		if ([string]::IsNullOrWhiteSpace($normalizedRequestedId)) {
			continue
		}
		if (-not $requestedLookup.Contains($normalizedRequestedId)) {
			$requestedLookup[$normalizedRequestedId] = $false
		}
	}
	if ($requestedLookup.Count -eq 0) {
		return @($SuiteEntries)
	}

	$selectedEntries = New-Object System.Collections.Generic.List[object]
	$availableEntryIds = New-Object System.Collections.Generic.List[string]
	$availableCaseIds = New-Object System.Collections.Generic.List[string]
	foreach ($entry in @($SuiteEntries)) {
		$entryId = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "entryId")
		if ([string]::IsNullOrWhiteSpace($entryId)) {
			$entryId = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "id")
		}
		$caseId = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "caseId")
		if (-not [string]::IsNullOrWhiteSpace($entryId)) {
			$availableEntryIds.Add($entryId)
		}
		if (-not [string]::IsNullOrWhiteSpace($caseId)) {
			$availableCaseIds.Add($caseId)
		}

		$entrySelected = $false
		foreach ($requestedId in @($requestedLookup.Keys)) {
			if ($entryId -eq $requestedId -or $caseId -eq $requestedId) {
				$requestedLookup[$requestedId] = $true
				$entrySelected = $true
			}
		}
		if ($entrySelected) {
			$selectedEntries.Add($entry)
		}
	}

	$unmatchedIds = @(
		$requestedLookup.GetEnumerator() |
			Where-Object { -not [bool]$_.Value } |
			ForEach-Object { [string]$_.Key }
	)
	if ($unmatchedIds.Count -gt 0) {
		$availableEntryText = (@($availableEntryIds | Sort-Object -Unique) -join ", ")
		$availableCaseText = (@($availableCaseIds | Sort-Object -Unique) -join ", ")
		throw (
			"Requested suite entryId/caseId not found: {0}. Available entryIds: {1}. Available caseIds: {2}." -f
			($unmatchedIds -join ", "),
			$availableEntryText,
			$availableCaseText
		)
	}

	return @($selectedEntries.ToArray())
}

function Resolve-SuiteEntries {
	param(
		[string]$SuiteConfigPath,
		[string[]]$RequestedCaseIds,
		[string]$DefaultBenchAction,
		[string]$DefaultMatrixPath
	)
	if ([string]::IsNullOrWhiteSpace($SuiteConfigPath)) {
		$matrix = Get-MatrixConfig -Path $DefaultMatrixPath
		$resolvedCaseIds = @(Resolve-CaseIdList -Matrix $matrix -RequestedCaseIds $RequestedCaseIds)
		$entries = New-Object System.Collections.Generic.List[object]
		foreach ($caseId in $resolvedCaseIds) {
			$entries.Add([pscustomobject]@{
				entryId = [string]$caseId
				caseId = [string]$caseId
				benchAction = $DefaultBenchAction
				matrixPath = [System.IO.Path]::GetFullPath($DefaultMatrixPath)
				templateWorldPath = $null
				reuseWorldFrom = $null
				compareSerialsTo = $null
				serverConfigOverrides = [ordered]@{}
			})
		}
		return [ordered]@{
			source = "cli"
			suiteConfigPath = $null
			description = $null
			entries = @($entries.ToArray())
		}
	}

	$fullSuitePath = [System.IO.Path]::GetFullPath($SuiteConfigPath)
	$suiteConfig = Get-SuiteConfig -Path $fullSuitePath
	$suiteBaseDirectory = Split-Path -Path $fullSuitePath -Parent
	$suiteDefaults = Get-OptionalPsObjectPropertyValue -Object $suiteConfig -PropertyName "defaults"
	$defaultBenchAction = [string](Get-OptionalPsObjectPropertyValue -Object $suiteDefaults -PropertyName "benchAction")
	if ([string]::IsNullOrWhiteSpace($defaultBenchAction)) {
		$defaultBenchAction = $DefaultBenchAction
	}
	$defaultServerConfigOverrides = Convert-OptionalObjectToOrderedMap -Object (
		Get-OptionalPsObjectPropertyValue -Object $suiteDefaults -PropertyName "serverConfigOverrides"
	)
	$defaultMatrixPath = [string](Get-OptionalPsObjectPropertyValue -Object $suiteDefaults -PropertyName "matrixPath")
	if ([string]::IsNullOrWhiteSpace($defaultMatrixPath)) {
		$defaultMatrixPath = $DefaultMatrixPath
	} else {
		$defaultMatrixPath = Resolve-PathFromBase -BaseDirectory $suiteBaseDirectory -CandidatePath $defaultMatrixPath
	}

	$entries = New-Object System.Collections.Generic.List[object]
	$resolvedSuiteEntries = @(Resolve-SuiteRequestedEntries -SuiteEntries @($suiteConfig.entries) -RequestedCaseIds $RequestedCaseIds)
	foreach ($entry in $resolvedSuiteEntries) {
		$entryId = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "entryId")
		if ([string]::IsNullOrWhiteSpace($entryId)) {
			$entryId = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "id")
		}
		$caseId = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "caseId")
		if ([string]::IsNullOrWhiteSpace($entryId)) {
			$entryId = $caseId
		}
		if ([string]::IsNullOrWhiteSpace($entryId) -or [string]::IsNullOrWhiteSpace($caseId)) {
			throw "Each suite entry must provide entryId/id and caseId."
		}

		$entryBenchAction = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "benchAction")
		if ([string]::IsNullOrWhiteSpace($entryBenchAction)) {
			$entryBenchAction = $defaultBenchAction
		}
		$rawMatrixPath = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "matrixPath")
		$entryMatrixPath = if ([string]::IsNullOrWhiteSpace($rawMatrixPath)) {
			$defaultMatrixPath
		} else {
			Resolve-PathFromBase -BaseDirectory $suiteBaseDirectory -CandidatePath $rawMatrixPath
		}
		if ([string]::IsNullOrWhiteSpace($entryMatrixPath)) {
			throw "Suite entry matrixPath is required: $entryId"
		}
		$entryServerConfigOverrides = Merge-OptionalObjectMaps `
			-BaseObject $defaultServerConfigOverrides `
			-OverrideObject (Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "serverConfigOverrides")

		$matrix = Get-MatrixConfig -Path $entryMatrixPath
		[void](Resolve-CaseIdList -Matrix $matrix -RequestedCaseIds @($caseId))

		$entries.Add([pscustomobject]@{
			entryId = $entryId
			caseId = $caseId
			benchAction = $entryBenchAction
			matrixPath = $entryMatrixPath
			templateWorldPath = Resolve-PathFromBase -BaseDirectory $suiteBaseDirectory -CandidatePath ([string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "templateWorldPath"))
			reuseWorldFrom = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "reuseWorldFrom")
			compareSerialsTo = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "compareSerialsTo")
			serverConfigOverrides = $entryServerConfigOverrides
		})
	}

	return [ordered]@{
		source = "suite"
		suiteConfigPath = $fullSuitePath
		description = [string](Get-OptionalPsObjectPropertyValue -Object $suiteConfig -PropertyName "description")
		entries = @($entries.ToArray())
	}
}

function Get-OptionalPsObjectPropertyValue {
	param(
		$Object,
		[string]$PropertyName
	)
	if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($PropertyName)) {
		return $null
	}
	$properties = $Object.PSObject.Properties
	if ($null -eq $properties -or -not ($properties.Name -contains $PropertyName)) {
		return $null
	}
	return $Object.$PropertyName
}
