<#
.SYNOPSIS
bench 模块：坐标、场地准备、datapack 与世界操作。
#>

function New-Vec3 {
	param(
		[int]$X,
		[int]$Y,
		[int]$Z
	)
	return [pscustomobject]@{
		X = $X
		Y = $Y
		Z = $Z
	}
}

function ConvertTo-Vec3 {
	param($ArrayValue)
	return (New-Vec3 -X ([int]$ArrayValue[0]) -Y ([int]$ArrayValue[1]) -Z ([int]$ArrayValue[2]))
}

function Format-Vec3 {
	param($Vec)
	return "$($Vec.X) $($Vec.Y) $($Vec.Z)"
}

# Helper for observation teleport coordinates that may contain decimals.
function New-PreciseVec3 {
	param(
		[double]$X,
		[double]$Y,
		[double]$Z
	)
	return [pscustomobject]@{
		X = [double]$X
		Y = [double]$Y
		Z = [double]$Z
	}
}

# Always format command coordinates with invariant culture.
function Format-PreciseVec3 {
	param($Vec)
	return [string]::Format(
		[System.Globalization.CultureInfo]::InvariantCulture,
		"{0:0.###} {1:0.###} {2:0.###}",
		[double]$Vec.X,
		[double]$Vec.Y,
		[double]$Vec.Z
	)
}

function Get-BlockIdByKind {
	param([string]$Kind)
	switch ($Kind) {
		"sync_emitter" { return "redstonelink:link_sync_emitter" }
		"toggle_emitter" { return "redstonelink:link_toggle_emitter" }
		"pulse_emitter" { return "redstonelink:link_pulse_emitter" }
		"core_block" { return "redstonelink:link_redstone_core" }
		"core_dust" { return "redstonelink:link_redstone_dust_core" }
		default { throw "Unsupported node kind: $Kind" }
	}
}

function Get-LayoutDimensionId {
	param(
		$Layout,
		[string]$DefaultDimension = "minecraft:overworld"
	)
	$dimension = [string](Get-OptionalProperty -Object $Layout -Name "dimension" -DefaultValue $DefaultDimension)
	if ([string]::IsNullOrWhiteSpace($dimension)) {
		return $DefaultDimension
	}
	return $dimension.Trim()
}

function Get-ArenaClearZones {
	param(
		$Arena,
		[string]$DefaultDimension = "minecraft:overworld"
	)
	if ($null -eq $Arena) {
		return @()
	}
	$clearZones = @(Get-OptionalProperty -Object $Arena -Name "clearZones" -DefaultValue @())
	if ($clearZones.Count -gt 0) {
		$zones = New-Object System.Collections.Generic.List[object]
		foreach ($zone in $clearZones) {
			$zones.Add([pscustomobject]@{
				dimension = [string](Get-OptionalProperty -Object $zone -Name "dimension" -DefaultValue $DefaultDimension)
				from = ConvertTo-Vec3 (Get-OptionalProperty -Object $zone -Name "clearFrom")
				to = ConvertTo-Vec3 (Get-OptionalProperty -Object $zone -Name "clearTo")
			})
		}
		return @($zones.ToArray())
	}
	return @(
		[pscustomobject]@{
			dimension = [string](Get-OptionalProperty -Object $Arena -Name "dimension" -DefaultValue $DefaultDimension)
			from = ConvertTo-Vec3 $Arena.clearFrom
			to = ConvertTo-Vec3 $Arena.clearTo
		}
	)
}

function Expand-CuboidPositions {
	param($Layout)
	if ($Layout.shape -ne "cuboid") {
		throw "Unsupported layout shape: $($Layout.shape)"
	}
	$from = ConvertTo-Vec3 $Layout.from
	$to = ConvertTo-Vec3 $Layout.to
	$minX = [Math]::Min($from.X, $to.X)
	$maxX = [Math]::Max($from.X, $to.X)
	$minY = [Math]::Min($from.Y, $to.Y)
	$maxY = [Math]::Max($from.Y, $to.Y)
	$minZ = [Math]::Min($from.Z, $to.Z)
	$maxZ = [Math]::Max($from.Z, $to.Z)
	$positions = New-Object System.Collections.Generic.List[object]
	for ($y = $minY; $y -le $maxY; $y++) {
		for ($z = $minZ; $z -le $maxZ; $z++) {
			for ($x = $minX; $x -le $maxX; $x++) {
				$positions.Add((New-Vec3 -X $x -Y $y -Z $z))
			}
		}
	}
	return @($positions.ToArray())
}

function Get-BoundsFromPositions {
	param($Positions)
	$xs = $Positions | ForEach-Object { $_.X }
	$ys = $Positions | ForEach-Object { $_.Y }
	$zs = $Positions | ForEach-Object { $_.Z }
	return [pscustomobject]@{
		From = New-Vec3 -X (($xs | Measure-Object -Minimum).Minimum) -Y (($ys | Measure-Object -Minimum).Minimum) -Z (($zs | Measure-Object -Minimum).Minimum)
		To = New-Vec3 -X (($xs | Measure-Object -Maximum).Maximum) -Y (($ys | Measure-Object -Maximum).Maximum) -Z (($zs | Measure-Object -Maximum).Maximum)
	}
}

# Build a stable observation point from placed triggerSource/core positions.
function Get-ObservationPointFromPositions {
	param($Positions)
	$normalizedPositions = @($Positions)
	if ($normalizedPositions.Count -le 0) {
		return $null
	}

	$bounds = Get-BoundsFromPositions -Positions $normalizedPositions
	$spanX = ([Math]::Abs([int]$bounds.To.X - [int]$bounds.From.X)) + 1
	$spanY = ([Math]::Abs([int]$bounds.To.Y - [int]$bounds.From.Y)) + 1
	$spanZ = ([Math]::Abs([int]$bounds.To.Z - [int]$bounds.From.Z)) + 1
	$maxHorizontalSpan = [Math]::Max($spanX, $spanZ)
	$verticalOffset = [Math]::Min(32, [Math]::Max(8, [int][Math]::Ceiling($maxHorizontalSpan / 3.0)))
	$centerX = ([double]$bounds.From.X + [double]$bounds.To.X + 1.0) / 2.0
	$centerZ = ([double]$bounds.From.Z + [double]$bounds.To.Z + 1.0) / 2.0
	$topY = [double][Math]::Max([int]$bounds.From.Y, [int]$bounds.To.Y)
	$position = New-PreciseVec3 -X $centerX -Y ($topY + 1.0 + $verticalOffset) -Z $centerZ
	$facing = New-PreciseVec3 -X $centerX -Y ($topY + 0.5) -Z $centerZ

	return [pscustomobject]@{
		Bounds = $bounds
		Position = $position
		Facing = $facing
		SpanX = $spanX
		SpanY = $spanY
		SpanZ = $spanZ
		VerticalOffset = $verticalOffset
	}
}

# Merge placed node positions for observation teleport calculation.
function Get-ObservationPointForPlacedNodes {
	param(
		$TargetPositions,
		[hashtable]$SourcePositionGroups,
		[string]$TargetDimension = "minecraft:overworld",
		[hashtable]$SourceDimensions = $null,
		[string]$ObservationDimension = ""
	)
	$allPositions = New-Object System.Collections.Generic.List[object]
	$resolvedObservationDimension = if ([string]::IsNullOrWhiteSpace($ObservationDimension)) {
		$TargetDimension
	} else {
		$ObservationDimension
	}
	if ([string]::IsNullOrWhiteSpace($resolvedObservationDimension) -or $TargetDimension -eq $resolvedObservationDimension) {
		foreach ($pos in @($TargetPositions)) {
			$allPositions.Add($pos)
		}
	}
	if ($null -ne $SourcePositionGroups) {
		foreach ($groupName in $SourcePositionGroups.Keys) {
			if ($null -ne $SourceDimensions -and $SourceDimensions.Count -gt 0) {
				$groupDimension = [string](Get-OptionalProperty -Object $SourceDimensions -Name $groupName -DefaultValue "minecraft:overworld")
				if ($groupDimension -ne $resolvedObservationDimension) {
					continue
				}
			}
			$groupPositions = $SourcePositionGroups[$groupName]
			foreach ($pos in @($groupPositions)) {
				$allPositions.Add($pos)
			}
		}
	}
	$observationPoint = Get-ObservationPointFromPositions -Positions @($allPositions.ToArray())
	if ($null -eq $observationPoint) {
		return $null
	}
	return [pscustomobject]@{
		Dimension = $resolvedObservationDimension
		Bounds = $observationPoint.Bounds
		Position = $observationPoint.Position
		Facing = $observationPoint.Facing
		SpanX = $observationPoint.SpanX
		SpanY = $observationPoint.SpanY
		SpanZ = $observationPoint.SpanZ
		VerticalOffset = $observationPoint.VerticalOffset
	}
}

function Get-ControlPositions {
	param(
		$Positions,
		[string]$Mode
	)
	$sourcePositions = @($Positions)
	if ($sourcePositions.Count -eq 0) {
		return @()
	}
	switch ($Mode) {
		"north_strip" {
			$bounds = Get-BoundsFromPositions $sourcePositions
			$controlPositions = New-Object System.Collections.Generic.List[object]
			for ($y = $bounds.From.Y; $y -le $bounds.To.Y; $y++) {
				for ($x = $bounds.From.X; $x -le $bounds.To.X; $x++) {
					$controlPositions.Add((New-Vec3 -X $x -Y $y -Z ($bounds.From.Z - 1)))
				}
			}
			return @($controlPositions.ToArray())
		}
		default {
			throw "Unsupported control mode: $Mode"
		}
	}
}

function Install-BenchDatapack {
	param(
		[string]$SourcePath,
		[string]$WorldPath
	)
	$resolvedSourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
	$resolvedWorldPath = [System.IO.Path]::GetFullPath($WorldPath)
	if (-not (Test-Path -LiteralPath $resolvedSourcePath)) {
		throw "Datapack source not found: $SourcePath"
	}
	$datapacksDir = Join-Path $resolvedWorldPath "datapacks"
	$targetPath = Join-Path $datapacksDir "rl_bench"
	if (-not (Test-Path -LiteralPath $datapacksDir -PathType Container)) {
		New-Item -Path $datapacksDir -ItemType Directory -Force | Out-Null
	}
	if (Test-Path -LiteralPath $targetPath) {
		Remove-Item -LiteralPath $targetPath -Recurse -Force -ErrorAction SilentlyContinue
	}
	Copy-Item -LiteralPath $resolvedSourcePath -Destination $targetPath -Recurse -Force
	Write-Host "[Bench] Datapack installed -> $targetPath"
}

function Get-CaseExecutionBoundsByDimension {
	param($CaseConfig)
	$positionsByDimension = @{}
	if ($null -ne $CaseConfig.targets) {
		$targetDimension = Get-LayoutDimensionId -Layout $CaseConfig.targets.layout
		if (-not $positionsByDimension.ContainsKey($targetDimension)) {
			$positionsByDimension[$targetDimension] = New-Object System.Collections.Generic.List[object]
		}
		foreach ($pos in @(Expand-CuboidPositions $CaseConfig.targets.layout)) {
			$positionsByDimension[$targetDimension].Add($pos)
		}
	}
	foreach ($group in @(Get-OptionalProperty -Object $CaseConfig -Name "sources" -DefaultValue @())) {
		$groupDimension = Get-LayoutDimensionId -Layout $group.layout
		if (-not $positionsByDimension.ContainsKey($groupDimension)) {
			$positionsByDimension[$groupDimension] = New-Object System.Collections.Generic.List[object]
		}
		foreach ($pos in @(Expand-CuboidPositions $group.layout)) {
			$positionsByDimension[$groupDimension].Add($pos)
		}
	}
	$boundsList = New-Object System.Collections.Generic.List[object]
	foreach ($dimension in $positionsByDimension.Keys) {
		$positions = @($positionsByDimension[$dimension].ToArray())
		if ($positions.Count -le 0) {
			continue
		}
		$boundsList.Add([pscustomobject]@{
			dimension = $dimension
			bounds = Get-BoundsFromPositions -Positions $positions
		})
	}
	return @($boundsList.ToArray())
}

function Ensure-CaseChunksLoaded {
	param(
		$Connection,
		$CaseConfig
	)
	if ($DryRun) {
		return
	}
	$boundsByDimension = @(Get-CaseExecutionBoundsByDimension -CaseConfig $CaseConfig)
	if ($boundsByDimension.Count -le 0) {
		return
	}
	foreach ($entry in $boundsByDimension) {
		$bounds = $entry.bounds
		$minX = [Math]::Min([int]$bounds.From.X, [int]$bounds.To.X)
		$maxX = [Math]::Max([int]$bounds.From.X, [int]$bounds.To.X)
		$minZ = [Math]::Min([int]$bounds.From.Z, [int]$bounds.To.Z)
		$maxZ = [Math]::Max([int]$bounds.From.Z, [int]$bounds.To.Z)
		$command = Wrap-WithBenchContexts `
			-Command ("forceload add {0} {1} {2} {3}" -f $minX, $minZ, $maxX, $maxZ) `
			-Dimension ([string]$entry.dimension)
		Invoke-RconCommand -Connection $Connection -Command $command | Out-Null
	}
}

function Clear-Arena {
	param(
		$Connection,
		$Arena
	)
	if ($null -eq $Arena) {
		return
	}
	foreach ($zone in @(Get-ArenaClearZones -Arena $Arena)) {
		Invoke-RconCommand `
			-Connection $Connection `
			-Command (Wrap-WithBenchContexts `
				-Command ("fill {0} {1} minecraft:air replace" -f (Format-Vec3 $zone.from), (Format-Vec3 $zone.to)) `
				-Dimension ([string]$zone.dimension)
			) | Out-Null
	}
}

function Invoke-PrepareFunctions {
	param(
		$Connection,
		$Matrix
	)
	foreach ($fn in $Matrix.defaults.prepareFunctions) {
		Invoke-RconCommand -Connection $Connection -Command (Wrap-WithPlayerContext ("function {0}" -f $fn)) | Out-Null
	}
}
