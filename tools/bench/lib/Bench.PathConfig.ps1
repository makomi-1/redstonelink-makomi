<#
.SYNOPSIS
bench path config loader.
#>

function Get-BenchPathConfigFilePath {
	return (Join-Path (Split-Path -Parent $PSScriptRoot) "bench.path-config.json")
}

function Get-BenchPathConfigBaseDirectory {
	return (Split-Path -Parent (Get-BenchPathConfigFilePath))
}

function Resolve-BenchPathCandidate {
	param(
		[string]$CandidatePath,
		[string]$BaseDirectory
	)
	if ([string]::IsNullOrWhiteSpace($CandidatePath)) {
		return ""
	}
	$trimmedPath = $CandidatePath.Trim()
	if ([System.IO.Path]::IsPathRooted($trimmedPath)) {
		return [System.IO.Path]::GetFullPath($trimmedPath)
	}
	if ([string]::IsNullOrWhiteSpace($BaseDirectory)) {
		return [System.IO.Path]::GetFullPath($trimmedPath)
	}
	return [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $trimmedPath))
}

function Get-BenchPathConfig {
	$cachedConfigVariable = Get-Variable -Name BenchPathConfigCache -Scope Script -ErrorAction SilentlyContinue
	if ($null -ne $cachedConfigVariable -and $null -ne $cachedConfigVariable.Value) {
		return $cachedConfigVariable.Value
	}
	$configPath = Get-BenchPathConfigFilePath
	if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
		throw "Bench path config file not found: $configPath"
	}
	$script:BenchPathConfigCache = Get-Content -Path $configPath -Encoding UTF8 -Raw | ConvertFrom-Json
	return $script:BenchPathConfigCache
}

function Get-BenchPathConfigValue {
	param([string]$Name)
	if ([string]::IsNullOrWhiteSpace($Name)) {
		throw "Bench path config key is required."
	}
	$config = Get-BenchPathConfig
	$property = $config.PSObject.Properties[$Name]
	if ($null -eq $property) {
		throw "Bench path config key not found: $Name"
	}
	$value = [string]$property.Value
	if ([string]::IsNullOrWhiteSpace($value)) {
		throw "Bench path config value is empty: $Name"
	}
	return $value.Trim()
}

function Get-BenchConfiguredServerRootPath {
	$baseDirectory = Get-BenchPathConfigBaseDirectory
	return (Resolve-BenchPathCandidate -CandidatePath (Get-BenchPathConfigValue -Name "serverRoot") -BaseDirectory $baseDirectory)
}

function Get-BenchConfiguredTemplateWorldPath {
	param([string]$ServerRootPath)
	$resolvedServerRootPath = if ([string]::IsNullOrWhiteSpace($ServerRootPath)) {
		Get-BenchConfiguredServerRootPath
	} else {
		[System.IO.Path]::GetFullPath($ServerRootPath)
	}
	return (Resolve-BenchPathCandidate -CandidatePath (Get-BenchPathConfigValue -Name "templateWorld") -BaseDirectory $resolvedServerRootPath)
}

function Get-BenchConfiguredReloadTemplateWorldPath {
	param([string]$ServerRootPath)
	$resolvedServerRootPath = if ([string]::IsNullOrWhiteSpace($ServerRootPath)) {
		Get-BenchConfiguredServerRootPath
	} else {
		[System.IO.Path]::GetFullPath($ServerRootPath)
	}
	return (Resolve-BenchPathCandidate -CandidatePath (Get-BenchPathConfigValue -Name "reloadTemplateWorld") -BaseDirectory $resolvedServerRootPath)
}

function Get-BenchConfiguredPrismLauncherPath {
	$baseDirectory = Get-BenchPathConfigBaseDirectory
	return (Resolve-BenchPathCandidate -CandidatePath (Get-BenchPathConfigValue -Name "prismLauncher") -BaseDirectory $baseDirectory)
}

function Get-BenchConfiguredPrismRootDirPath {
	$baseDirectory = Get-BenchPathConfigBaseDirectory
	return (Resolve-BenchPathCandidate -CandidatePath (Get-BenchPathConfigValue -Name "prismRootDir") -BaseDirectory $baseDirectory)
}

function Get-BenchPathParameters {
	param([string]$ServerRootPath)
	$resolvedServerRootPath = if ([string]::IsNullOrWhiteSpace($ServerRootPath)) {
		Get-BenchConfiguredServerRootPath
	} else {
		[System.IO.Path]::GetFullPath($ServerRootPath)
	}
	return [ordered]@{
		serverRoot = $resolvedServerRootPath
		templateWorld = Get-BenchConfiguredTemplateWorldPath -ServerRootPath $resolvedServerRootPath
		reloadTemplateWorld = Get-BenchConfiguredReloadTemplateWorldPath -ServerRootPath $resolvedServerRootPath
		prismLauncher = Get-BenchConfiguredPrismLauncherPath
		prismRootDir = Get-BenchConfiguredPrismRootDirPath
	}
}
