<#
.SYNOPSIS
bench 模块：case 摘要与结果文件输出。
#>

function Ensure-ResultsDirectory {
	param(
		[string]$RepoRoot,
		[string]$RelativePath
	)
	$fullPath = Join-Path $RepoRoot $RelativePath
	if (-not (Test-Path $fullPath)) {
		New-Item -Path $fullPath -ItemType Directory -Force | Out-Null
	}
	return $fullPath
}

function Write-ResultJson {
	param(
		[string]$RepoRoot,
		[string]$RelativeResultsDir,
		[string]$CaseId,
		$ResultObject
	)
	$resultsDir = Ensure-ResultsDirectory -RepoRoot $RepoRoot -RelativePath $RelativeResultsDir
	$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
	$resultPath = Join-Path $resultsDir "${timestamp}_${CaseId}.json"
	$jsonText = $ResultObject | ConvertTo-Json -Depth 10
	$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
	[System.IO.File]::WriteAllText($resultPath, $jsonText, $utf8NoBom)
	Write-Host "[Bench] Result -> $resultPath"
	return $resultPath
}

function Show-CaseSummary {
	param($CaseConfig)
	Write-Host "[Bench] Case: $($CaseConfig.id)"
	Write-Host "[Bench] Desc: $($CaseConfig.description)"
	$layer = [string](Get-OptionalProperty -Object $CaseConfig -Name "layer" -DefaultValue "")
	$scenarioId = [string](Get-OptionalProperty -Object $CaseConfig -Name "scenarioId" -DefaultValue "")
	if (-not [string]::IsNullOrWhiteSpace($layer) -or -not [string]::IsNullOrWhiteSpace($scenarioId)) {
		Write-Host "[Bench] Scenario: layer=$layer id=$scenarioId"
	}
	Write-Host "[Bench] Target kind: $($CaseConfig.targets.kind)"
	Write-Host "[Bench] Target count: $(@(Expand-CuboidPositions $CaseConfig.targets.layout).Count)"
	foreach ($group in $CaseConfig.sources) {
		Write-Host "[Bench] Source group: $($group.id) kind=$($group.kind) count=$(@(Expand-CuboidPositions $group.layout).Count)"
	}
}
