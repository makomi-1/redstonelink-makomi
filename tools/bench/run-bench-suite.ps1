<#
.SYNOPSIS
RedstoneLink bench suite 编排脚本。
.DESCRIPTION
面向外部 dedicated server 的“每 case 一个新档”自动化流程：
1. 复制模板世界为新 case 世界
2. 最小修改 server.properties 的 level-name
3. 启动 dedicated server 并等待 RCON 就绪
4. 调用现有 run-bench.ps1 执行单 case
5. 收集 bench 结果并发送 stop
#>
param(
	[string]$ServerRoot = "D:\OpenProjects\RedstoneLink\mcserver",
	[string]$ServerPropertiesPath,
	[string]$ServerStartCommand,
	[string]$TemplateWorldPath = "D:\OpenProjects\RedstoneLink\mcserver\rl-bench-template",
	[string[]]$CaseIds,
	[string]$SuitePath,
	[ValidateSet("RunCase", "RunFunctionalCase")]
	[string]$BenchAction = "RunCase",
	[string]$MatrixPath = (Join-Path $PSScriptRoot "matrix.json"),
	[string]$RconHost = "127.0.0.1",
	[int]$RconPort = 25575,
	[Alias("RconPassword")]
	$RconSecret,
	[string]$AsPlayer,
	[string]$SparkActivityPath,
	[switch]$SyncLatestModJar,
	[switch]$BuildBeforeSyncLatestModJar,
	[string]$BuildTask = "remapJar",
	[string]$GradleWrapperPath = (Join-Path $PSScriptRoot "..\..\gradlew.bat"),
	[string]$ModJarPath,
	[string]$ServerModsDir,
	[int]$StartupTimeoutMs = 180000,
	[int]$StartupPollIntervalMs = 1000,
	[int]$ShutdownTimeoutMs = 60000,
	[int]$ShutdownPollIntervalMs = 1000,
	[string]$CaseWorldPrefix = "rl-case",
	[string]$SuiteResultsDir = (Join-Path $PSScriptRoot "..\..\run\profiles\bench-suite-results"),
	[switch]$DeleteCaseWorldOnSuccess,
	[switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$caseWorldsDirectoryName = "rl-cases"
if ([string]::IsNullOrWhiteSpace($ServerPropertiesPath) -and -not [string]::IsNullOrWhiteSpace($ServerRoot)) {
	$ServerPropertiesPath = Join-Path $ServerRoot "server.properties"
}

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

function Resolve-PathFromBase {
	param(
		[string]$BaseDirectory,
		[string]$CandidatePath
	)
	if ([string]::IsNullOrWhiteSpace($CandidatePath)) {
		return $null
	}
	if ([System.IO.Path]::IsPathRooted($CandidatePath)) {
		return [System.IO.Path]::GetFullPath($CandidatePath)
	}
	return [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $CandidatePath))
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
	foreach ($templatePath in @(Get-OptionalObjectProperty -Object $MatrixData -PropertyName "templatePaths" -DefaultValue @())) {
		$resolvedTemplatePath = Resolve-PathFromBase -BaseDirectory $MatrixDirectory -CandidatePath ([string]$templatePath)
		if (-not (Test-Path -LiteralPath $resolvedTemplatePath -PathType Leaf)) {
			throw "Template file not found: $resolvedTemplatePath"
		}
		$templateConfig = ConvertTo-NormalizedBenchValue -Value (
			Get-Content -Path $resolvedTemplatePath -Encoding UTF8 -Raw | ConvertFrom-Json
		)
		$templateMap = Get-OptionalObjectProperty -Object $templateConfig -PropertyName "templates"
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

	foreach ($inlineCase in @(Get-OptionalObjectProperty -Object $MatrixData -PropertyName "cases" -DefaultValue @())) {
		$expandedCase = Expand-BenchTemplateRefs `
			-Value (ConvertTo-NormalizedBenchValue -Value $inlineCase) `
			-TemplateRegistry $TemplateRegistry
		$caseId = [string](Get-OptionalObjectProperty -Object $expandedCase -PropertyName "id" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($caseId)) {
			throw "Case id is required in matrix file."
		}
		if ($knownCaseIds.ContainsKey($caseId)) {
			throw "Duplicate case id in matrix: $caseId"
		}
		$knownCaseIds[$caseId] = $true
		$loadedCases.Add($expandedCase)
	}

	foreach ($casePath in @(Get-OptionalObjectProperty -Object $MatrixData -PropertyName "casePaths" -DefaultValue @())) {
		$resolvedCasePath = Resolve-PathFromBase -BaseDirectory $MatrixDirectory -CandidatePath ([string]$casePath)
		if (-not (Test-Path -LiteralPath $resolvedCasePath -PathType Leaf)) {
			throw "Case file not found: $resolvedCasePath"
		}
		$expandedCase = Expand-BenchTemplateRefs `
			-Value (ConvertTo-NormalizedBenchValue -Value (
				Get-Content -Path $resolvedCasePath -Encoding UTF8 -Raw | ConvertFrom-Json
			)) `
			-TemplateRegistry $TemplateRegistry
		$caseId = [string](Get-OptionalObjectProperty -Object $expandedCase -PropertyName "id" -DefaultValue "")
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
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
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

function Invoke-GradleBuildTask {
	param(
		[string]$RepoRootPath,
		[string]$GradleWrapperFilePath,
		[string]$TaskName
	)
	if ([string]::IsNullOrWhiteSpace($TaskName)) {
		throw "BuildTask is required when BuildBeforeSyncLatestModJar is enabled."
	}
	if ([string]::IsNullOrWhiteSpace($GradleWrapperFilePath)) {
		throw "GradleWrapperPath is required when BuildBeforeSyncLatestModJar is enabled."
	}
	if (-not (Test-Path -LiteralPath $GradleWrapperFilePath -PathType Leaf)) {
		throw "Gradle wrapper not found: $GradleWrapperFilePath"
	}

	Write-Host "[BenchSuite] Build mod jar before sync -> task=$TaskName"
	Push-Location $RepoRootPath
	try {
		& $GradleWrapperFilePath $TaskName "--no-daemon"
		if ($LASTEXITCODE -ne 0) {
			throw "Gradle task failed: $TaskName (exitCode=$LASTEXITCODE)"
		}
	} finally {
		Pop-Location
	}
}

function Resolve-LocalRuntimeModJarPath {
	param(
		[string]$RepoRootPath,
		[string]$ExplicitModJarPath
	)
	if (-not [string]::IsNullOrWhiteSpace($ExplicitModJarPath)) {
		$resolvedExplicitPath = Resolve-PathFromBase -BaseDirectory $RepoRootPath -CandidatePath $ExplicitModJarPath
		if (-not (Test-Path -LiteralPath $resolvedExplicitPath -PathType Leaf)) {
			throw "Mod jar not found: $resolvedExplicitPath"
		}
		return $resolvedExplicitPath
	}

	$buildLibsPath = Join-Path $RepoRootPath "build\libs"
	if (-not (Test-Path -LiteralPath $buildLibsPath -PathType Container)) {
		throw "Build output directory not found: $buildLibsPath"
	}

	$candidates = @(Get-ChildItem -LiteralPath $buildLibsPath -Filter "*.jar" -File |
		Where-Object {
			$_.Name -notlike "*-sources.jar" -and
			$_.Name -notlike "*-javadoc.jar" -and
			$_.Name -notlike "*-dev.jar"
		} |
		Sort-Object LastWriteTimeUtc -Descending)

	if ($candidates.Count -eq 0) {
		throw "No runtime mod jar found in build/libs. Run the build task first or provide -ModJarPath."
	}
	return $candidates[0].FullName
}

function Sync-ServerModJar {
	param(
		[string]$SourceJarPath,
		[string]$TargetModsDirectoryPath
	)
	if ([string]::IsNullOrWhiteSpace($SourceJarPath)) {
		throw "SourceJarPath is required."
	}
	if ([string]::IsNullOrWhiteSpace($TargetModsDirectoryPath)) {
		throw "TargetModsDirectoryPath is required."
	}
	if (-not (Test-Path -LiteralPath $SourceJarPath -PathType Leaf)) {
		throw "Source jar not found: $SourceJarPath"
	}

	$resolvedModsDirectory = New-DirectoryIfMissing -Path $TargetModsDirectoryPath
	$removedServerJars = New-Object System.Collections.Generic.List[string]
	foreach ($existingJar in @(Get-ChildItem -LiteralPath $resolvedModsDirectory -Filter "redstonelink*.jar" -File -ErrorAction SilentlyContinue)) {
		$removedServerJars.Add($existingJar.Name)
		Remove-Item -LiteralPath $existingJar.FullName -Force
	}

	$targetJarPath = Join-Path $resolvedModsDirectory ([System.IO.Path]::GetFileName($SourceJarPath))
	Copy-Item -LiteralPath $SourceJarPath -Destination $targetJarPath -Force
	return [ordered]@{
		serverModsDir = $resolvedModsDirectory
		sourceJarPath = [System.IO.Path]::GetFullPath($SourceJarPath)
		copiedJarPath = [System.IO.Path]::GetFullPath($targetJarPath)
		removedServerJars = @($removedServerJars.ToArray())
	}
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
				reuseWorldFrom = $null
				compareSerialsTo = $null
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
	$defaultMatrixPath = [string](Get-OptionalPsObjectPropertyValue -Object $suiteDefaults -PropertyName "matrixPath")
	if ([string]::IsNullOrWhiteSpace($defaultMatrixPath)) {
		$defaultMatrixPath = $DefaultMatrixPath
	} else {
		$defaultMatrixPath = Resolve-PathFromBase -BaseDirectory $suiteBaseDirectory -CandidatePath $defaultMatrixPath
	}

	$entries = New-Object System.Collections.Generic.List[object]
	foreach ($entry in @($suiteConfig.entries)) {
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

		$matrix = Get-MatrixConfig -Path $entryMatrixPath
		[void](Resolve-CaseIdList -Matrix $matrix -RequestedCaseIds @($caseId))

		$entries.Add([pscustomobject]@{
			entryId = $entryId
			caseId = $caseId
			benchAction = $entryBenchAction
			matrixPath = $entryMatrixPath
			reuseWorldFrom = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "reuseWorldFrom")
			compareSerialsTo = [string](Get-OptionalPsObjectPropertyValue -Object $entry -PropertyName "compareSerialsTo")
		})
	}

	return [ordered]@{
		source = "suite"
		suiteConfigPath = $fullSuitePath
		description = [string](Get-OptionalPsObjectPropertyValue -Object $suiteConfig -PropertyName "description")
		entries = @($entries.ToArray())
	}
}

function New-DirectoryIfMissing {
	param([string]$Path)
	if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
		New-Item -Path $Path -ItemType Directory -Force | Out-Null
	}
	return [System.IO.Path]::GetFullPath($Path)
}

function Get-SafeNamePart {
	param([string]$Value)
	$sanitized = [System.Text.RegularExpressions.Regex]::Replace($Value, "[^A-Za-z0-9_-]", "_")
	if ([string]::IsNullOrWhiteSpace($sanitized)) {
		return "case"
	}
	return $sanitized
}

function New-CaseWorldName {
	param(
		[string]$Prefix,
		[string]$CaseId,
		[int]$Index,
		[string]$SuiteTimestamp
	)
	$safeCaseId = Get-SafeNamePart -Value $CaseId
	return "{0}-{1}-{2:D2}-{3}" -f $Prefix, $safeCaseId, $Index, $SuiteTimestamp
}

function Get-CaseWorldsRootPath {
	param(
		[string]$ServerRootPath,
		[string]$DirectoryName
	)
	if (-not (Test-Path -LiteralPath $ServerRootPath -PathType Container)) {
		throw "Server root not found: $ServerRootPath"
	}
	return (New-DirectoryIfMissing -Path (Join-Path $ServerRootPath $DirectoryName))
}

function Get-CaseWorldLevelName {
	param(
		[string]$DirectoryName,
		[string]$WorldName
	)
	$normalizedDirectoryName = ""
	if (-not [string]::IsNullOrWhiteSpace($DirectoryName)) {
		$normalizedDirectoryName = $DirectoryName -replace "\\", "/" -replace "^/+", "" -replace "/+$", ""
	}
	if ([string]::IsNullOrWhiteSpace($normalizedDirectoryName)) {
		return $WorldName
	}
	return "$normalizedDirectoryName/$WorldName"
}

function Copy-TemplateWorld {
	param(
		[string]$TemplatePath,
		[string]$CaseWorldsRootPath,
		[string]$WorldName
	)
	if (-not (Test-Path -LiteralPath $TemplatePath -PathType Container)) {
		throw "Template world not found: $TemplatePath"
	}
	if (-not (Test-Path -LiteralPath $CaseWorldsRootPath -PathType Container)) {
		throw "Case worlds root not found: $CaseWorldsRootPath"
	}

	$targetWorldPath = Join-Path $CaseWorldsRootPath $WorldName
	if (Test-Path -LiteralPath $targetWorldPath) {
		throw "Target case world already exists: $targetWorldPath"
	}

	Copy-Item -LiteralPath $TemplatePath -Destination $targetWorldPath -Recurse -Force
	return [System.IO.Path]::GetFullPath($targetWorldPath)
}

function Set-ServerPropertyValue {
	param(
		[string]$Path,
		[string]$Key,
		[string]$Value
	)
	$text = Read-Utf8Text -Path $Path
	$lines = New-Object System.Collections.Generic.List[string]
	$reader = New-Object System.IO.StringReader($text)
	try {
		$line = $reader.ReadLine()
		$updated = $false
		while ($null -ne $line) {
			if (-not $updated -and $line -match "^\s*$([System.Text.RegularExpressions.Regex]::Escape($Key))\s*=") {
				$lines.Add("$Key=$Value")
				$updated = $true
			} else {
				$lines.Add($line)
			}
			$line = $reader.ReadLine()
		}
		if (-not $updated) {
			$lines.Add("$Key=$Value")
		}
	} finally {
		$reader.Dispose()
	}

	$joined = [string]::Join([Environment]::NewLine, $lines)
	if ($text.EndsWith("`n") -or $text.EndsWith("`r")) {
		$joined += [Environment]::NewLine
	}
	Write-Utf8NoBomFile -Path $Path -Content $joined
}

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

function New-RconPacketBytes {
	param(
		[int]$RequestId,
		[int]$PacketType,
		[string]$Body
	)
	$encoding = [System.Text.Encoding]::UTF8
	$bodyBytes = $encoding.GetBytes($Body)
	$packetLength = 4 + 4 + $bodyBytes.Length + 2
	$buffer = New-Object byte[] (4 + $packetLength)
	[System.BitConverter]::GetBytes($packetLength).CopyTo($buffer, 0)
	[System.BitConverter]::GetBytes($RequestId).CopyTo($buffer, 4)
	[System.BitConverter]::GetBytes($PacketType).CopyTo($buffer, 8)
	$bodyBytes.CopyTo($buffer, 12)
	$buffer[$buffer.Length - 2] = 0
	$buffer[$buffer.Length - 1] = 0
	return $buffer
}

function Read-ExactBytes {
	param(
		[System.IO.Stream]$Stream,
		[int]$Count
	)
	$buffer = New-Object byte[] $Count
	$offset = 0
	while ($offset -lt $Count) {
		$read = $Stream.Read($buffer, $offset, $Count - $offset)
		if ($read -le 0) {
			throw "Unexpected EOF while reading RCON packet."
		}
		$offset += $read
	}
	return $buffer
}

function Read-RconPacket {
	param([System.IO.Stream]$Stream)
	$lengthBytes = Read-ExactBytes -Stream $Stream -Count 4
	$length = [System.BitConverter]::ToInt32($lengthBytes, 0)
	$payload = Read-ExactBytes -Stream $Stream -Count $length
	$requestId = [System.BitConverter]::ToInt32($payload, 0)
	$packetType = [System.BitConverter]::ToInt32($payload, 4)
	$bodyLength = [Math]::Max(0, $length - 10)
	$body = if ($bodyLength -gt 0) {
		[System.Text.Encoding]::UTF8.GetString($payload, 8, $bodyLength)
	} else {
		""
	}
	return [pscustomobject]@{
		RequestId = $requestId
		PacketType = $packetType
		Body = $body
	}
}

function Read-RconPacketIfAvailable {
	param(
		[System.Net.Sockets.TcpClient]$Client,
		[System.IO.Stream]$Stream,
		[int]$WaitTimeoutMs = 500
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($stopwatch.ElapsedMilliseconds -lt $WaitTimeoutMs) {
		if ($Client.Available -gt 0) {
			return (Read-RconPacket -Stream $Stream)
		}
		Start-Sleep -Milliseconds 20
	}
	return $null
}

function Open-RconConnection {
	param(
		[string]$ServerHost,
		[int]$Port,
		[System.Security.SecureString]$Password
	)
	$plainTextPassword = Convert-SecureStringToPlainText -Password $Password
	if ([string]::IsNullOrWhiteSpace($plainTextPassword)) {
		throw "RconPassword is required."
	}

	$client = New-Object System.Net.Sockets.TcpClient
	$client.ReceiveTimeout = 3000
	$client.SendTimeout = 3000
	$client.Connect($ServerHost, $Port)
	$stream = $client.GetStream()

	$authPacket = New-RconPacketBytes -RequestId 1 -PacketType 3 -Body $plainTextPassword
	$stream.Write($authPacket, 0, $authPacket.Length)
	$stream.Flush()

	try {
		$first = Read-RconPacket -Stream $stream
	} catch {
		$client.Dispose()
		throw "Timed out waiting for the first RCON auth response from ${ServerHost}:$Port. $($_.Exception.Message)"
	}

	$second = $null
	$firstLooksLikePrelude = $first.PacketType -eq 0 -and [string]::IsNullOrEmpty($first.Body)
	if ($firstLooksLikePrelude) {
		$second = Read-RconPacketIfAvailable -Client $client -Stream $stream -WaitTimeoutMs 500
	} else {
		$second = Read-RconPacketIfAvailable -Client $client -Stream $stream -WaitTimeoutMs 120
	}

	$authResponse = if ($null -ne $second -and ($second.RequestId -eq 1 -or $second.RequestId -eq -1)) {
		$second
	} elseif ($first.RequestId -eq 1 -or $first.RequestId -eq -1) {
		$first
	} elseif ($null -ne $second) {
		$second
	} else {
		$first
	}
	if ($authResponse.RequestId -eq -1) {
		$client.Dispose()
		throw "RCON auth failed."
	}
	if ($authResponse.RequestId -ne 1) {
		$client.Dispose()
		throw "Unexpected RCON auth response. requestId=$($authResponse.RequestId), packetType=$($authResponse.PacketType)"
	}

	return [pscustomobject]@{
		Client = $client
		Stream = $stream
		NextRequestId = 10
	}
}

function Close-RconConnection {
	param($Connection)
	if ($null -ne $Connection) {
		if ($null -ne $Connection.Stream) {
			$Connection.Stream.Dispose()
		}
		if ($null -ne $Connection.Client) {
			$Connection.Client.Dispose()
		}
	}
}

function Invoke-RconCommand {
	param(
		$Connection,
		[string]$Command,
		[int]$ReceiveTimeoutMs = 3000
	)
	if ($null -eq $Connection) {
		throw "RCON connection is not open."
	}

	$requestId = [int]$Connection.NextRequestId
	$Connection.NextRequestId = $requestId + 1
	$packet = New-RconPacketBytes -RequestId $requestId -PacketType 2 -Body $Command
	$Connection.Stream.Write($packet, 0, $packet.Length)
	$Connection.Stream.Flush()

	$previousReceiveTimeout = $Connection.Client.ReceiveTimeout
	$Connection.Client.ReceiveTimeout = $ReceiveTimeoutMs
	$responseParts = New-Object System.Collections.Generic.List[string]
	$receivedPackets = New-Object System.Collections.Generic.List[object]
	try {
		$firstPacket = Read-RconPacket -Stream $Connection.Stream
		$receivedPackets.Add($firstPacket)
		while ($true) {
			$nextPacket = Read-RconPacketIfAvailable -Client $Connection.Client -Stream $Connection.Stream -WaitTimeoutMs 120
			if ($null -eq $nextPacket) {
				break
			}
			$receivedPackets.Add($nextPacket)
		}
		foreach ($packetResponse in $receivedPackets) {
			if ($packetResponse.RequestId -eq $requestId -and -not [string]::IsNullOrWhiteSpace($packetResponse.Body)) {
				$responseParts.Add($packetResponse.Body)
			}
		}
	} finally {
		$Connection.Client.ReceiveTimeout = $previousReceiveTimeout
	}

	return (($responseParts -join "`n").Trim())
}

function Test-RconAlreadyReachable {
	param(
		[string]$ServerHost,
		[int]$Port,
		[System.Security.SecureString]$Password
	)
	try {
		$connection = Open-RconConnection -ServerHost $ServerHost -Port $Port -Password $Password
		Close-RconConnection -Connection $connection
		return $true
	} catch {
		return $false
	}
}

function Wait-RconReady {
	param(
		[string]$ServerHost,
		[int]$Port,
		[System.Security.SecureString]$Password,
		[int]$TimeoutMs,
		[int]$PollIntervalMs
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	$lastError = $null
	while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMs) {
		try {
			$connection = Open-RconConnection -ServerHost $ServerHost -Port $Port -Password $Password
			Close-RconConnection -Connection $connection
			return
		} catch {
			$lastError = $_.Exception.Message
			if ($lastError -match "RCON auth failed|RconPassword is required") {
				throw
			}
		}
		Start-Sleep -Milliseconds $PollIntervalMs
	}
	throw "Timed out waiting for RCON to become ready. lastError=$lastError"
}

function Stop-ServerByRcon {
	param(
		[string]$ServerHost,
		[int]$Port,
		[System.Security.SecureString]$Password
	)
	$connection = $null
	try {
		$connection = Open-RconConnection -ServerHost $ServerHost -Port $Port -Password $Password
		Invoke-RconCommand -Connection $connection -Command "stop" -ReceiveTimeoutMs 8000 | Out-Null
	} finally {
		Close-RconConnection -Connection $connection
	}
}

function Wait-ProcessExit {
	param(
		[System.Diagnostics.Process]$Process,
		[int]$TimeoutMs,
		[int]$PollIntervalMs
	)
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMs) {
		if ($Process.HasExited) {
			return
		}
		Start-Sleep -Milliseconds $PollIntervalMs
		$Process.Refresh()
	}
	throw "Server process did not exit within timeout. pid=$($Process.Id)"
}

function Start-DedicatedServerProcess {
	param(
		[string]$WorkingDirectory,
		[string]$Command
	)
	if ([string]::IsNullOrWhiteSpace($Command)) {
		throw "ServerStartCommand is required."
	}

	return (Start-Process -FilePath "cmd.exe" -ArgumentList @("/c", $Command) -WorkingDirectory $WorkingDirectory -PassThru)
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

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
	throw "ServerRoot is required."
}
if ([string]::IsNullOrWhiteSpace($TemplateWorldPath)) {
	throw "TemplateWorldPath is required."
}
if ($BuildBeforeSyncLatestModJar -and -not $SyncLatestModJar) {
	throw "BuildBeforeSyncLatestModJar requires SyncLatestModJar."
}
$rconPasswordSecure = Convert-PasswordInputToSecureString -SecretInput $RconSecret
if ([string]::IsNullOrWhiteSpace((Convert-SecureStringToPlainText -Password $rconPasswordSecure))) {
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
$benchScriptPath = Join-Path $PSScriptRoot "run-bench.ps1"
$matrixCache = @{}
$serverRootFullPath = [System.IO.Path]::GetFullPath($ServerRoot)
$templateWorldFullPath = [System.IO.Path]::GetFullPath($TemplateWorldPath)
$serverPropertiesFullPath = [System.IO.Path]::GetFullPath($ServerPropertiesPath)
$gradleWrapperFullPath = Resolve-PathFromBase -BaseDirectory $repoRoot -CandidatePath $GradleWrapperPath
$serverModsDirectoryPath = if ([string]::IsNullOrWhiteSpace($ServerModsDir)) {
	Join-Path $serverRootFullPath "mods"
} else {
	Resolve-PathFromBase -BaseDirectory $serverRootFullPath -CandidatePath $ServerModsDir
}
$caseWorldsRootPath = Get-CaseWorldsRootPath -ServerRootPath $serverRootFullPath -DirectoryName $caseWorldsDirectoryName
$originalServerPropertiesText = Read-Utf8Text -Path $serverPropertiesFullPath

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
	buildBeforeSyncLatestModJar = [bool]$BuildBeforeSyncLatestModJar
	buildTask = if ($BuildBeforeSyncLatestModJar) { $BuildTask } else { $null }
	gradleWrapperPath = if ($BuildBeforeSyncLatestModJar) { $gradleWrapperFullPath } else { $null }
	requestedModJarPath = if ([string]::IsNullOrWhiteSpace($ModJarPath)) { $null } else { $ModJarPath }
	serverModsDir = if ($SyncLatestModJar) { $serverModsDirectoryPath } else { $null }
	sourceJarPath = $null
	copiedJarPath = $null
	removedServerJars = @()
}
if ($BuildBeforeSyncLatestModJar) {
	Invoke-GradleBuildTask -RepoRootPath $repoRoot -GradleWrapperFilePath $gradleWrapperFullPath -TaskName $BuildTask
}
if ($SyncLatestModJar) {
	$localModJarPath = Resolve-LocalRuntimeModJarPath -RepoRootPath $repoRoot -ExplicitModJarPath $ModJarPath
	$modSyncResult = Sync-ServerModJar -SourceJarPath $localModJarPath -TargetModsDirectoryPath $serverModsDirectoryPath
	$modSyncSummary.sourceJarPath = $modSyncResult.sourceJarPath
	$modSyncSummary.copiedJarPath = $modSyncResult.copiedJarPath
	$modSyncSummary.removedServerJars = $modSyncResult.removedServerJars
	Write-Host "[BenchSuite] Synced mod jar -> $($modSyncSummary.copiedJarPath)"
}

$suiteSummary = [ordered]@{
	suiteTimestamp = $suiteTimestamp
	suiteSource = $resolvedSuite.source
	suitePath = $resolvedSuite.suiteConfigPath
	suiteDescription = $resolvedSuite.description
	benchAction = $BenchAction
	serverRoot = $serverRootFullPath
	serverPropertiesPath = $serverPropertiesFullPath
	templateWorldPath = $templateWorldFullPath
	caseWorldsRootPath = $caseWorldsRootPath
	modSync = $modSyncSummary
	entryIds = $entryIds
	caseIds = $resolvedCaseIds
	startedAt = (Get-Date).ToString("s")
	results = @()
	restoredServerProperties = $false
}

Write-Host "[BenchSuite] Suite: $suiteTimestamp"
Write-Host "[BenchSuite] Server root: $serverRootFullPath"
Write-Host "[BenchSuite] Case worlds root: $caseWorldsRootPath"
Write-Host "[BenchSuite] Template world: $templateWorldFullPath"
if (-not [string]::IsNullOrWhiteSpace([string]$resolvedSuite.suiteConfigPath)) {
	Write-Host "[BenchSuite] Suite path: $($resolvedSuite.suiteConfigPath)"
}
Write-Host "[BenchSuite] Entries: $($entryIds -join ', ')"

$completedEntries = @{}

try {
	for ($index = 0; $index -lt $suiteEntries.Count; $index++) {
		$entry = $suiteEntries[$index]
		$entryId = [string]$entry.entryId
		$caseId = [string]$entry.caseId
		$entryBenchAction = [string]$entry.benchAction
		$entryMatrixPath = [string]$entry.matrixPath
		$reuseWorldFrom = [string]$entry.reuseWorldFrom
		$compareSerialsTo = [string]$entry.compareSerialsTo
		$worldName = $null
		$worldLevelName = $null
		if ([string]::IsNullOrWhiteSpace($reuseWorldFrom)) {
			$worldName = New-CaseWorldName -Prefix $CaseWorldPrefix -CaseId $entryId -Index ($index + 1) -SuiteTimestamp $suiteTimestamp
			$worldLevelName = Get-CaseWorldLevelName -DirectoryName $caseWorldsDirectoryName -WorldName $worldName
		}
		$caseRecord = [ordered]@{
			entryId = $entryId
			caseId = $caseId
			benchAction = $entryBenchAction
			matrixPath = $entryMatrixPath
			worldName = $worldName
			worldLevelName = $worldLevelName
			reuseWorldFrom = if ([string]::IsNullOrWhiteSpace($reuseWorldFrom)) { $null } else { $reuseWorldFrom }
			status = "pending"
			startedAt = (Get-Date).ToString("s")
		}
		$serverProcess = $null
		$worldPath = $null
		try {
			if ([string]::IsNullOrWhiteSpace($reuseWorldFrom)) {
				$worldPath = Copy-TemplateWorld -TemplatePath $templateWorldFullPath -CaseWorldsRootPath $caseWorldsRootPath -WorldName $worldName
				$caseRecord.worldPath = $worldPath
				Write-Host "[BenchSuite] Entry $entryId -> case $caseId -> world $worldName"
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
			$serverProcess = Start-DedicatedServerProcess -WorkingDirectory $serverRootFullPath -Command $ServerStartCommand
			$caseRecord.serverPid = $serverProcess.Id
			Wait-RconReady -ServerHost $RconHost -Port $RconPort -Password $rconPasswordSecure -TimeoutMs $StartupTimeoutMs -PollIntervalMs $StartupPollIntervalMs

			if (-not $matrixCache.ContainsKey($entryMatrixPath)) {
				$matrixCache[$entryMatrixPath] = Get-MatrixConfig -Path $entryMatrixPath
			}
			$entryMatrix = $matrixCache[$entryMatrixPath]
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
				RconPassword = (Convert-SecureStringToPlainText -Password $rconPasswordSecure)
			}
			if (-not [string]::IsNullOrWhiteSpace($AsPlayer)) {
				$benchArgs.AsPlayer = $AsPlayer
			}
			if (-not [string]::IsNullOrWhiteSpace($SparkActivityPath)) {
				$benchArgs.SparkActivityPath = $SparkActivityPath
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
				$caseRecord.bench = $benchSummary
				$caseRecord.resultPath = $resultPath
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

			$caseRecord.completedAt = (Get-Date).ToString("s")
			$completedEntries[$entryId] = [pscustomobject]$caseRecord
			$suiteSummary.results += $caseRecord
		}
	}
} finally {
	Write-Utf8NoBomFile -Path $serverPropertiesFullPath -Content $originalServerPropertiesText
	$suiteSummary.restoredServerProperties = $true
	$suiteSummary.completedAt = (Get-Date).ToString("s")
	$suiteSummary.summaryPath = (Join-Path $suiteOutputDirectory "summary.json")
	Write-SuiteSummaryJson -OutputPath $suiteSummary.summaryPath -SummaryObject $suiteSummary | Out-Null
	Write-Host "[BenchSuite] Summary -> $($suiteSummary.summaryPath)"
}
