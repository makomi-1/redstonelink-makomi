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

# Centralize functional phase runtime dependencies.
