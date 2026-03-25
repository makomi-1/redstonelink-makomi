<#
.SYNOPSIS
bench suite 模块：case 世界管理与 server.properties 写回。
#>

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
