<#
.SYNOPSIS
bench suite 模块：本地构建与服务器 mod jar 同步。
#>

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
