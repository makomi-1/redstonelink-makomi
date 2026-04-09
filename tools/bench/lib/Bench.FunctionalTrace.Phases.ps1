function New-FunctionalPhaseExecutionState {
	param(
		$Connection,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap,
		$Checks,
		$FailedChecks,
		[hashtable]$PhaseContext
	)
	return [pscustomobject][ordered]@{
		Connection = $Connection
		SourceSerialMaps = $SourceSerialMaps
		TargetSerialMap = $TargetSerialMap
		Checks = $Checks
		FailedChecks = $FailedChecks
		PhaseContext = $PhaseContext
	}
}

# Register checks in one place so handlers only build check payloads.
function Add-FunctionalCheck {
	param(
		$ExecutionState,
		$Check
	)
	if ($null -eq $Check) {
		return
	}
	$ExecutionState.Checks.Add($Check)
	$passed = [bool](Get-OptionalProperty -Object $Check -Name "passed" -DefaultValue $false)
	if (-not $passed) {
		$ExecutionState.FailedChecks.Add($Check)
	}
}

# Resolve shared serial input context for phase handlers.
function Resolve-FunctionalPhaseSerialContext {
	param(
		$ExecutionState,
		$Phase,
		[string]$DefaultSerialFormat = "slash_list"
	)
	$serials = @(Resolve-PhaseSerials -Phase $Phase -SourceSerialMaps $ExecutionState.SourceSerialMaps -TargetSerialMap $ExecutionState.TargetSerialMap)
	$serialFormat = [string](Get-OptionalProperty -Object $Phase -Name "serialFormat" -DefaultValue $DefaultSerialFormat)
	$serialText = Format-SerialInputText -Serials $serials -Style $serialFormat
	return [pscustomobject][ordered]@{
		serials = $serials
		serialFormat = $serialFormat
		serialText = $serialText
	}
}

# Handle trace mount phases.
function Invoke-FunctionalTraceMountPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$type = [string](Get-OptionalProperty -Object $Phase -Name "type" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialFormat = [string]$serialContext.serialFormat
	$serialText = [string]$serialContext.serialText
	$every = [int](Get-OptionalProperty -Object $Phase -Name "every" -DefaultValue 1)
	$capacity = [int](Get-OptionalProperty -Object $Phase -Name "capacity" -DefaultValue 128)
	$chunkSize = [int](Get-OptionalProperty -Object $Phase -Name "chunkSize" -DefaultValue $(if ($serials.Count -gt 256) { 256 } else { $serials.Count }))
	$batchPauseMs = [int](Get-OptionalProperty -Object $Phase -Name "batchPauseMs" -DefaultValue $(if ($serials.Count -gt $chunkSize) { 150 } else { 0 }))
	$batchCommands = New-Object System.Collections.Generic.List[string]
	$batchResponses = New-Object System.Collections.Generic.List[string]
	$sampleList = New-Object System.Collections.Generic.List[object]
	$mountTicksBySerial = @{}
	$tickWindow = $null
	foreach ($serialBatch in @(Split-SerialsIntoBatches -Serials $serials -ChunkSize $chunkSize)) {
		$batchSerialText = Format-SerialInputText -Serials $serialBatch -Style $serialFormat
		$batchCommand = Wrap-WithPlayerContext "redstonelink node trace mount $type $batchSerialText $every $capacity"
		$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $batchCommand -Silent
		$batchResponse = [string]$commandResult.response
		$batchCommands.Add($batchCommand)
		$batchResponses.Add($batchResponse)
		foreach ($sample in @(Parse-NodeTraceSamples -ResponseText $batchResponse)) {
			$sampleList.Add($sample)
		}
		foreach ($entry in (Resolve-TraceMountTicksBySerial -ResponseText $batchResponse).GetEnumerator()) {
			$mountTicksBySerial[$entry.Key] = $entry.Value
		}
		if ($null -eq $tickWindow) {
			$tickWindow = $commandResult.tickWindow
		} elseif ($null -ne $commandResult.tickWindow) {
			$tickWindow = [ordered]@{
				startTick = [Math]::Min([long]$tickWindow.startTick, [long]$commandResult.tickWindow.startTick)
				endTick = [Math]::Max([long]$tickWindow.endTick, [long]$commandResult.tickWindow.endTick)
			}
		}
		if ($batchPauseMs -gt 0 -and $serialBatch.Count -lt $serials.Count) {
			Start-Sleep -Milliseconds $batchPauseMs
		}
	}
	$command = if ($batchCommands.Count -le 1) { [string]$batchCommands[0] } else { @($batchCommands.ToArray()) }
	$response = [string]::Join("`n", @($batchResponses.ToArray()))
	$samples = @($sampleList.ToArray())
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		type = $type
		serials = $serials
		serialText = $serialText
		every = $every
		capacity = $capacity
		command = $command
		response = $response
		tickWindow = $tickWindow
		samples = $samples
		mountTicksBySerial = $mountTicksBySerial
	}
}

# Handle trace unmount phases.
function Invoke-FunctionalTraceUnmountPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$type = [string](Get-OptionalProperty -Object $Phase -Name "type" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialFormat = [string]$serialContext.serialFormat
	$serialText = [string]$serialContext.serialText
	$chunkSize = [int](Get-OptionalProperty -Object $Phase -Name "chunkSize" -DefaultValue $(if ($serials.Count -gt 256) { 256 } else { $serials.Count }))
	$batchPauseMs = [int](Get-OptionalProperty -Object $Phase -Name "batchPauseMs" -DefaultValue $(if ($serials.Count -gt $chunkSize) { 100 } else { 0 }))
	$batchCommands = New-Object System.Collections.Generic.List[string]
	$batchResponses = New-Object System.Collections.Generic.List[string]
	foreach ($serialBatch in @(Split-SerialsIntoBatches -Serials $serials -ChunkSize $chunkSize)) {
		$batchSerialText = Format-SerialInputText -Serials $serialBatch -Style $serialFormat
		$batchCommand = Wrap-WithPlayerContext "redstonelink node trace unmount $type $batchSerialText"
		$batchResponse = Invoke-RconCommand -Connection $ExecutionState.Connection -Command $batchCommand -Silent
		$batchCommands.Add($batchCommand)
		$batchResponses.Add([string]$batchResponse)
		if ($batchPauseMs -gt 0 -and $serialBatch.Count -lt $serials.Count) {
			Start-Sleep -Milliseconds $batchPauseMs
		}
	}
	$command = if ($batchCommands.Count -le 1) { [string]$batchCommands[0] } else { @($batchCommands.ToArray()) }
	$response = [string]::Join("`n", @($batchResponses.ToArray()))
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		type = $type
		serials = $serials
		serialText = $serialText
		command = $command
		response = $response
	}
}

# Handle square waveform input phases.
function Invoke-FunctionalInputStartSquarePhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$endpoint = [string](Get-OptionalProperty -Object $Phase -Name "endpoint" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialText = [string]$serialContext.serialText
	$periodTicks = [int](Get-OptionalProperty -Object $Phase -Name "periodTicks" -DefaultValue 0)
	if ($periodTicks -le 0) {
		throw "input_start_square phase requires periodTicks > 0."
	}
	$highTicks = [int](Get-OptionalProperty -Object $Phase -Name "highTicks" -DefaultValue ([Math]::Max(1, [int]($periodTicks / 2))))
	$highPower = [int](Get-OptionalProperty -Object $Phase -Name "highPower" -DefaultValue 15)
	$lowPower = [int](Get-OptionalProperty -Object $Phase -Name "lowPower" -DefaultValue 0)
	$phaseTicks = [int](Get-OptionalProperty -Object $Phase -Name "phaseTicks" -DefaultValue 0)
	$totalTicks = [int](Get-OptionalProperty -Object $Phase -Name "totalTicks" -DefaultValue 0)
	$endpointPath = Resolve-InputEndpointCommandPath -Endpoint $endpoint
	$command = Wrap-WithPlayerContext (
		"redstonelink input start {0} square {1} {2} {3} {4} {5} {6} {7}" -f
		$endpointPath,
		$serialText,
		$periodTicks,
		$highTicks,
		$highPower,
		$lowPower,
		$phaseTicks,
		$totalTicks
	)
	if ($DryRun) {
		$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
		$jobId = [long]$script:DryRunInputJobCounter
		$script:DryRunInputJobCounter++
		$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			endpoint = $endpoint
			serials = $serials
			serialText = $serialText
			jobId = $jobId
			jobStartTick = $jobStartTick
			command = $command
			response = "[RedstoneLink/Input] Started job=$jobId [DryRun]"
			tickWindow = $commandResult.tickWindow
			waveform = [ordered]@{
				periodTicks = $periodTicks
				highTicks = $highTicks
				highPower = $highPower
				lowPower = $lowPower
				phaseTicks = $phaseTicks
				totalTicks = $totalTicks
			}
			dryRun = $true
		}
	}
	$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
	Assert-BenchCommandResponse `
		-Command $command `
		-ResponseText ([string]$commandResult.response) `
		-ExpectedPrefix "[RedstoneLink/Input]" `
		-ExpectedRegex "Started job="
	$jobId = Extract-InputJobIdFromResponse -ResponseText ([string]$commandResult.response)
	$jobStartTick = Extract-InputJobStartTickFromResponse -ResponseText ([string]$commandResult.response)
	if ($null -eq $jobStartTick) {
		$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
	}
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		endpoint = $endpoint
		serials = $serials
		serialText = $serialText
		jobId = $jobId
		jobStartTick = $jobStartTick
		command = $command
		response = $commandResult.response
		tickWindow = $commandResult.tickWindow
		waveform = [ordered]@{
			periodTicks = $periodTicks
			highTicks = $highTicks
			highPower = $highPower
			lowPower = $lowPower
			phaseTicks = $phaseTicks
			totalTicks = $totalTicks
		}
	}
}

# Handle custom waveform input phases.
function Invoke-FunctionalInputStartCustomPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$endpoint = [string](Get-OptionalProperty -Object $Phase -Name "endpoint" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialText = [string]$serialContext.serialText
	$sequence = [string](Get-OptionalProperty -Object $Phase -Name "sequence" -DefaultValue "")
	if ([string]::IsNullOrWhiteSpace($sequence)) {
		throw "input_start_custom phase requires sequence."
	}
	$phaseTicks = [int](Get-OptionalProperty -Object $Phase -Name "phaseTicks" -DefaultValue 0)
	$totalTicks = [int](Get-OptionalProperty -Object $Phase -Name "totalTicks" -DefaultValue 0)
	$endpointPath = Resolve-InputEndpointCommandPath -Endpoint $endpoint
	$command = Wrap-WithPlayerContext (
		"redstonelink input start {0} custom {1} {2} {3} {4}" -f
		$endpointPath,
		$serialText,
		$sequence,
		$phaseTicks,
		$totalTicks
	)
	if ($DryRun) {
		$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
		$jobId = [long]$script:DryRunInputJobCounter
		$script:DryRunInputJobCounter++
		$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			endpoint = $endpoint
			serials = $serials
			serialText = $serialText
			jobId = $jobId
			jobStartTick = $jobStartTick
			sequence = $sequence
			phaseTicks = $phaseTicks
			totalTicks = $totalTicks
			command = $command
			response = "[RedstoneLink/Input] Started job=$jobId [DryRun]"
			tickWindow = $commandResult.tickWindow
			dryRun = $true
		}
	}
	$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
	Assert-BenchCommandResponse `
		-Command $command `
		-ResponseText ([string]$commandResult.response) `
		-ExpectedPrefix "[RedstoneLink/Input]" `
		-ExpectedRegex "Started job="
	$jobId = Extract-InputJobIdFromResponse -ResponseText ([string]$commandResult.response)
	$jobStartTick = Extract-InputJobStartTickFromResponse -ResponseText ([string]$commandResult.response)
	if ($null -eq $jobStartTick) {
		$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
	}
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		endpoint = $endpoint
		serials = $serials
		serialText = $serialText
		jobId = $jobId
		jobStartTick = $jobStartTick
		sequence = $sequence
		phaseTicks = $phaseTicks
		totalTicks = $totalTicks
		command = $command
		response = $commandResult.response
		tickWindow = $commandResult.tickWindow
	}
}

# Handle custom batch input phases.
function Invoke-FunctionalInputStartCustomBatchPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$entries = @($Phase.entries)
	if ($entries.Count -le 0) {
		throw "input_start_custom_batch phase requires entries."
	}
	$phaseTicks = [int](Get-OptionalProperty -Object $Phase -Name "phaseTicks" -DefaultValue 0)
	$totalTicks = [int](Get-OptionalProperty -Object $Phase -Name "totalTicks" -DefaultValue 0)
	$entryTokens = New-Object System.Collections.Generic.List[string]
	$resolvedEntries = New-Object System.Collections.Generic.List[object]
	foreach ($entry in $entries) {
		$entryPhase = [ordered]@{}
		foreach ($property in $entry.PSObject.Properties) {
			$entryPhase[$property.Name] = $property.Value
		}
		$entrySerials = @(Resolve-PhaseSerials -Phase $entryPhase -SourceSerialMaps $ExecutionState.SourceSerialMaps -TargetSerialMap $ExecutionState.TargetSerialMap)
		$entrySerialFormat = [string](Get-OptionalProperty -Object $entry -Name "serialFormat" -DefaultValue "slash_list")
		$entrySerialText = Format-SerialInputText -Serials $entrySerials -Style $entrySerialFormat
		$entrySequence = [string](Get-OptionalProperty -Object $entry -Name "sequence" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($entrySequence)) {
			throw "input_start_custom_batch entry requires sequence."
		}
		$entryTokens.Add(("{0}@{1}" -f $entrySerialText, $entrySequence))
		$resolvedEntries.Add([ordered]@{
			serials = $entrySerials
			serialText = $entrySerialText
			sequence = $entrySequence
		})
	}
	$batchToken = [string]::Join(";", @($entryTokens.ToArray()))
	$command = Wrap-WithPlayerContext (
		"redstonelink bench input start triggerSource custom_batch {0} {1} {2}" -f
		$batchToken,
		$phaseTicks,
		$totalTicks
	)
	if ($DryRun) {
		$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
		$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
		$dryRunJobIds = New-Object System.Collections.Generic.List[long]
		for ($index = 0; $index -lt $resolvedEntries.Count; $index++) {
			$dryRunJobIds.Add([long]$script:DryRunInputJobCounter)
			$script:DryRunInputJobCounter++
		}
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			jobId = if ($dryRunJobIds.Count -gt 0) { [long]$dryRunJobIds[0] } else { $null }
			jobIds = @($dryRunJobIds.ToArray())
			jobStartTick = $jobStartTick
			entries = @($resolvedEntries.ToArray())
			batchToken = $batchToken
			phaseTicks = $phaseTicks
			totalTicks = $totalTicks
			command = $command
			response = "[RedstoneLink/Input] Started job=$([string]::Join('/', @($dryRunJobIds.ToArray()))) [DryRun]"
			tickWindow = $commandResult.tickWindow
			dryRun = $true
		}
	}
	$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
	Assert-BenchCommandResponse `
		-Command $command `
		-ResponseText ([string]$commandResult.response) `
		-ExpectedPrefix "[RedstoneLink/Input]" `
		-ExpectedRegex "Started job="
	$jobIds = @(Extract-InputJobIdsFromResponse -ResponseText ([string]$commandResult.response))
	$jobStartTick = Extract-InputJobStartTickFromResponse -ResponseText ([string]$commandResult.response)
	if ($null -eq $jobStartTick) {
		$jobStartTick = Get-OptionalProperty -Object $commandResult.tickWindow -Name "startTick"
	}
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		jobId = if ($jobIds.Count -gt 0) { [long]$jobIds[0] } else { $null }
		jobIds = $jobIds
		jobStartTick = $jobStartTick
		entries = @($resolvedEntries.ToArray())
		batchToken = $batchToken
		phaseTicks = $phaseTicks
		totalTicks = $totalTicks
		command = $command
		response = $commandResult.response
		tickWindow = $commandResult.tickWindow
	}
}

# Handle input clear phases.
function Invoke-FunctionalInputClearPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$command = Wrap-WithPlayerContext "redstonelink input clear"
	if ($DryRun) {
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			command = $command
			response = "[RedstoneLink/Input] Cleared input jobs: [DryRun]"
			dryRun = $true
		}
	}
	$response = Invoke-RconCommand -Connection $ExecutionState.Connection -Command $command -Silent
	Assert-BenchCommandResponse `
		-Command $command `
		-ResponseText $response `
		-ExpectedPrefix "[RedstoneLink/Input]" `
		-ExpectedRegex "Cleared input jobs:"
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		command = $command
		response = $response
	}
}

# Handle link command phases.
function Invoke-FunctionalLinkCommandPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$action = [string](Get-OptionalProperty -Object $Phase -Name "action" -DefaultValue "")
	$type = [string](Get-OptionalProperty -Object $Phase -Name "type" -DefaultValue "triggerSource")
	$explicitSourceSerial = Get-OptionalProperty -Object $Phase -Name "sourceSerial"
	if ($null -ne $explicitSourceSerial) {
		$sourceSerial = [long]$explicitSourceSerial
	} else {
		$sourceRef = [string](Get-OptionalProperty -Object $Phase -Name "sourceRef" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($sourceRef)) {
			throw "link_command phase requires sourceRef or sourceSerial."
		}
		$sourceIndex = [int](Get-OptionalProperty -Object $Phase -Name "sourceIndex" -DefaultValue 0)
		$sourceCandidates = @(Resolve-PhaseSerials -Phase @{
			kind = $Kind
			serialRef = $sourceRef
			serialIndex = $sourceIndex
		} -SourceSerialMaps $ExecutionState.SourceSerialMaps -TargetSerialMap $ExecutionState.TargetSerialMap)
		if ($sourceCandidates.Count -ne 1) {
			throw "link_command phase must resolve exactly one source serial."
		}
		$sourceSerial = [long]$sourceCandidates[0]
	}

	$targetSerials = @()
	if ($action -eq "add" -or $action -eq "remove" -or $action -eq "set") {
		$targetRef = [string](Get-OptionalProperty -Object $Phase -Name "targetRef" -DefaultValue "")
		$targetIndexes = Get-OptionalProperty -Object $Phase -Name "targetIndexes"
		$singleTargetIndex = Get-OptionalProperty -Object $Phase -Name "targetIndex"
		$explicitTargetSerials = Get-OptionalProperty -Object $Phase -Name "targetSerials"
		$targetPhase = @{
			kind = $Kind
			serialRef = $targetRef
		}
		if ($null -ne $explicitTargetSerials) {
			$targetPhase.serials = @($explicitTargetSerials)
		}
		if ($null -ne $singleTargetIndex) {
			$targetPhase.serialIndex = [int]$singleTargetIndex
		}
		if ($null -ne $targetIndexes) {
			$targetPhase.serialIndexes = @($targetIndexes)
		}
		$targetSerials = @(Resolve-PhaseSerials -Phase $targetPhase -SourceSerialMaps $ExecutionState.SourceSerialMaps -TargetSerialMap $ExecutionState.TargetSerialMap)
	}

	$targetSerialFormat = [string](Get-OptionalProperty -Object $Phase -Name "targetSerialFormat" -DefaultValue "slash_list")
	$forceConfirm = [bool](Get-OptionalProperty -Object $Phase -Name "forceConfirm" -DefaultValue $false)
	switch ($action) {
		"add" {
			if ($targetSerials.Count -ne 1) {
				throw "link_command add requires exactly one target serial."
			}
			$commandText = "redstonelink link add $type $sourceSerial $($targetSerials[0])"
		}
		"remove" {
			if ($targetSerials.Count -ne 1) {
				throw "link_command remove requires exactly one target serial."
			}
			$commandText = "redstonelink link remove $type $sourceSerial $($targetSerials[0])"
		}
		"set" {
			$commandText = "redstonelink link set $type $sourceSerial $(Format-SerialInputText -Serials $targetSerials -Style $targetSerialFormat)"
			if ($targetSerials.Count -gt 1 -or $forceConfirm) {
				$commandText += " confirm"
			}
		}
		"clear" {
			$commandText = "redstonelink link set $type $sourceSerial"
		}
		"get" {
			$commandText = "redstonelink link get $type $sourceSerial"
		}
		default {
			throw "Unsupported link_command action: $action"
		}
	}
	$command = Wrap-WithPlayerContext $commandText
	$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		action = $action
		type = $type
		sourceSerial = $sourceSerial
		targetSerials = $targetSerials
		targetSerialText = if ($targetSerials.Count -gt 0) { Format-SerialInputText -Serials $targetSerials -Style $targetSerialFormat } else { "" }
		command = $command
		response = $commandResult.response
		tickWindow = $commandResult.tickWindow
	}
}

# Handle generic command assertion phases.
function Invoke-FunctionalCommandAssertPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$rawCommand = [string](Get-OptionalProperty -Object $Phase -Name "command" -DefaultValue "")
	$commandTemplate = [string](Get-OptionalProperty -Object $Phase -Name "commandTemplate" -DefaultValue "")
	$commandText = if (-not [string]::IsNullOrWhiteSpace($commandTemplate)) {
		Resolve-FunctionalCommandTemplate -Template $commandTemplate -SourceSerialMaps $ExecutionState.SourceSerialMaps -TargetSerialMap $ExecutionState.TargetSerialMap -PhaseContext $ExecutionState.PhaseContext
	} else {
		Resolve-FunctionalCommandTemplate -Template $rawCommand -SourceSerialMaps $ExecutionState.SourceSerialMaps -TargetSerialMap $ExecutionState.TargetSerialMap -PhaseContext $ExecutionState.PhaseContext
	}
	if ([string]::IsNullOrWhiteSpace($commandText)) {
		throw "command_assert phase requires command or commandTemplate."
	}
	$skipPlayerContext = [bool](Get-OptionalProperty -Object $Phase -Name "skipPlayerContext" -DefaultValue $false)
	$commandDimension = [string](Get-OptionalProperty -Object $Phase -Name "commandDimension" -DefaultValue "")
	$command = Wrap-WithBenchContexts `
		-Command $commandText `
		-Dimension $commandDimension `
		-SkipPlayerContext:$skipPlayerContext
	$expectedPrefix = Resolve-FunctionalCommandTemplate `
		-Template ([string](Get-OptionalProperty -Object $Phase -Name "expectedPrefix" -DefaultValue "")) `
		-SourceSerialMaps $ExecutionState.SourceSerialMaps `
		-TargetSerialMap $ExecutionState.TargetSerialMap `
		-PhaseContext $ExecutionState.PhaseContext
	$expectedRegexes = @(Get-FunctionalCommandPatternList `
		-Phase $Phase `
		-PrimaryName "expectedRegex" `
		-ListName "expectedRegexes" `
		-SourceSerialMaps $ExecutionState.SourceSerialMaps `
		-TargetSerialMap $ExecutionState.TargetSerialMap `
		-PhaseContext $ExecutionState.PhaseContext)
	$rejectRegexes = @(Get-FunctionalCommandPatternList `
		-Phase $Phase `
		-PrimaryName "rejectRegex" `
		-ListName "rejectRegexes" `
		-SourceSerialMaps $ExecutionState.SourceSerialMaps `
		-TargetSerialMap $ExecutionState.TargetSerialMap `
		-PhaseContext $ExecutionState.PhaseContext)
	$captureRegex = Resolve-FunctionalCommandTemplate `
		-Template ([string](Get-OptionalProperty -Object $Phase -Name "captureRegex" -DefaultValue "")) `
		-SourceSerialMaps $ExecutionState.SourceSerialMaps `
		-TargetSerialMap $ExecutionState.TargetSerialMap `
		-PhaseContext $ExecutionState.PhaseContext
	$captureTickWindow = [bool](Get-OptionalProperty -Object $Phase -Name "captureTickWindow" -DefaultValue $false)
	$allowReadTimeout = [bool](Get-OptionalProperty -Object $Phase -Name "allowReadTimeout" -DefaultValue $false)
	$allowEmptyResponse = [bool](Get-OptionalProperty -Object $Phase -Name "allowEmptyResponse" -DefaultValue $false)
	$expectFailureResponse = [bool](Get-OptionalProperty -Object $Phase -Name "expectFailureResponse" -DefaultValue $false)
	$receiveTimeoutMs = [int](Get-OptionalProperty -Object $Phase -Name "receiveTimeoutMs" -DefaultValue 3000)

	if ($DryRun) {
		$dryRunResponse = if (-not [string]::IsNullOrWhiteSpace($expectedPrefix)) {
			"$expectedPrefix [DryRun] skipped"
		} else {
			"[DryRun] skipped"
		}
		$dryRunCaptures = [ordered]@{}
		if (-not [string]::IsNullOrWhiteSpace($captureRegex)) {
			$dryRunCapturePattern = [System.Text.RegularExpressions.Regex]::new($captureRegex)
			foreach ($groupName in @($dryRunCapturePattern.GetGroupNames())) {
				if ($groupName -match "^\d+$") {
					continue
				}
				$dryRunCaptures[$groupName] = "0"
			}
		}
		$check = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "command_response"
			passed = $true
			skipped = $true
			reason = "dry_run"
			command = $command
			commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
			expectFailureResponse = $expectFailureResponse
			expectedPrefix = $expectedPrefix
			expectedRegexes = $expectedRegexes
			rejectRegexes = $rejectRegexes
			captureRegex = $captureRegex
			captures = $dryRunCaptures
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $check
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			command = $command
			commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
			response = $dryRunResponse
			tickWindow = $null
			expectFailureResponse = $expectFailureResponse
			expectedPrefix = $expectedPrefix
			expectedRegexes = $expectedRegexes
			rejectRegexes = $rejectRegexes
			captureRegex = $captureRegex
			captures = $dryRunCaptures
			passed = $true
			dryRun = $true
		}
	}

	$response = ""
	$tickWindow = $null
	$failureReason = ""
	$errorDetail = ""
	try {
		if ($captureTickWindow) {
			$commandResult = Invoke-RconCommandWithTickWindow `
				-Connection $ExecutionState.Connection `
				-Command $command `
				-Silent `
				-ReceiveTimeoutMs $receiveTimeoutMs `
				-AllowReadTimeout:$allowReadTimeout
			$response = [string]$commandResult.response
			$tickWindow = $commandResult.tickWindow
		} else {
			$response = Invoke-RconCommand `
				-Connection $ExecutionState.Connection `
				-Command $command `
				-Silent `
				-ReceiveTimeoutMs $receiveTimeoutMs `
				-AllowReadTimeout:$allowReadTimeout
		}
	} catch {
		$failureReason = "execution_error"
		$errorDetail = $_.Exception.Message
	}

	$normalizedResponse = ([string]$response).Trim()
	$passed = $true
	$responseLooksLikeFailure = $false
	$captures = [ordered]@{}
	if ([string]::IsNullOrWhiteSpace($failureReason)) {
		if ([string]::IsNullOrWhiteSpace($normalizedResponse)) {
			if (-not $allowEmptyResponse) {
				$passed = $false
				$failureReason = "empty_response"
			}
		} else {
			$responseLooksLikeFailure = Test-FunctionalCommandAssertHardFailure -ResponseText $normalizedResponse
			if ($expectFailureResponse -and -not $responseLooksLikeFailure) {
				$passed = $false
				$failureReason = "expected_failure_missing"
			} elseif (-not $expectFailureResponse -and $responseLooksLikeFailure) {
				$passed = $false
				$failureReason = "failure_response"
			}
		}
		if ($passed -and -not [string]::IsNullOrWhiteSpace($expectedPrefix) -and -not $normalizedResponse.StartsWith($expectedPrefix, [System.StringComparison]::Ordinal)) {
			$passed = $false
			$failureReason = "prefix_mismatch"
		}
		if ($passed) {
			foreach ($expectedRegex in $expectedRegexes) {
				if (-not [System.Text.RegularExpressions.Regex]::IsMatch($normalizedResponse, $expectedRegex)) {
					$passed = $false
					$failureReason = "expected_regex_missing"
					$errorDetail = $expectedRegex
					break
				}
			}
			if ($passed) {
				foreach ($rejectRegex in $rejectRegexes) {
					if ([System.Text.RegularExpressions.Regex]::IsMatch($normalizedResponse, $rejectRegex)) {
						$passed = $false
						$failureReason = "reject_regex_matched"
						$errorDetail = $rejectRegex
						break
					}
				}
			}
		}
		if ($passed -and -not [string]::IsNullOrWhiteSpace($captureRegex)) {
			$capturePattern = [System.Text.RegularExpressions.Regex]::new($captureRegex)
			$captureMatch = $capturePattern.Match($normalizedResponse)
			if (-not $captureMatch.Success) {
				$passed = $false
				$failureReason = "capture_regex_missing"
				$errorDetail = $captureRegex
			} else {
				foreach ($groupName in @($capturePattern.GetGroupNames())) {
					if ($groupName -match "^\d+$") {
						continue
					}
					$captures[$groupName] = [string]$captureMatch.Groups[$groupName].Value
				}
			}
		}
	} else {
		$passed = $false
	}

	$check = [ordered]@{
		phase = $PhaseName
		kind = $Kind
		scope = "command_response"
		passed = $passed
		command = $command
		commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
		response = $response
		responseLooksLikeFailure = $responseLooksLikeFailure
		expectFailureResponse = $expectFailureResponse
		expectedPrefix = $expectedPrefix
		expectedRegexes = $expectedRegexes
		rejectRegexes = $rejectRegexes
		captureRegex = $captureRegex
		captures = $captures
		failureReason = $failureReason
		errorDetail = $errorDetail
	}
	Add-FunctionalCheck -ExecutionState $ExecutionState -Check $check
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		command = $command
		commandDimension = if ([string]::IsNullOrWhiteSpace($commandDimension)) { $null } else { $commandDimension }
		response = $response
		tickWindow = $tickWindow
		responseLooksLikeFailure = $responseLooksLikeFailure
		expectFailureResponse = $expectFailureResponse
		expectedPrefix = $expectedPrefix
		expectedRegexes = $expectedRegexes
		rejectRegexes = $rejectRegexes
		captureRegex = $captureRegex
		captures = $captures
		passed = $passed
		failureReason = $failureReason
		errorDetail = $errorDetail
	}
}

# Handle batch activation phases.
function Invoke-FunctionalActivateBatchPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialText = [string]$serialContext.serialText
	$mode = [string](Get-OptionalProperty -Object $Phase -Name "mode" -DefaultValue "toggle")
	$command = Wrap-WithPlayerContext "redstonelink node activate triggerSource $serialText $mode"
	$commandResult = Invoke-RconCommandWithTickWindow -Connection $ExecutionState.Connection -Command $command -Silent
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		mode = $mode
		serials = $serials
		serialText = $serialText
		command = $command
		response = $commandResult.response
		tickWindow = $commandResult.tickWindow
	}
}

# Handle wait tick phases.
function Invoke-FunctionalWaitTicksPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$ticks = [int](Get-OptionalProperty -Object $Phase -Name "ticks" -DefaultValue 0)
	$waitInfo = Wait-ServerTicks -Connection $ExecutionState.Connection -Ticks $ticks
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		wait = $waitInfo
	}
}

# Handle latest trace assertion phases.
function Invoke-FunctionalTraceLatestAssertPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$type = [string](Get-OptionalProperty -Object $Phase -Name "type" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialText = [string]$serialContext.serialText
	$expected = Get-OptionalProperty -Object $Phase -Name "expect"
	if ($null -eq $expected) {
		throw "trace_latest_assert phase requires expect."
	}
	$expectedCount = [int](Get-OptionalProperty -Object $Phase -Name "expectedCount" -DefaultValue $serials.Count)
	$command = Wrap-WithPlayerContext "redstonelink node trace latest $type $serialText"
	$response = Invoke-RconCommand -Connection $ExecutionState.Connection -Command $command -Silent
	if ($DryRun) {
		$check = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			passed = $true
			skipped = $true
			reason = "dry_run"
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $check
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			type = $type
			serials = $serials
			serialText = $serialText
			command = $command
			response = $response
			samples = @()
			expected = $expected
			passed = $true
			dryRun = $true
		}
	}
	$samples = @(Parse-NodeTraceSamples -ResponseText $response)
	$countCheck = [ordered]@{
		phase = $PhaseName
		kind = $Kind
		scope = "sample_count"
		passed = ($samples.Count -eq $expectedCount)
		expected = $expectedCount
		actual = $samples.Count
	}
	Add-FunctionalCheck -ExecutionState $ExecutionState -Check $countCheck
	foreach ($sample in $samples) {
		$comparison = Compare-TraceSampleAgainstExpectation -Sample $sample -Expected $expected
		$sampleCheck = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "sample"
			type = $type
			serial = $sample.serial
			passed = ($comparison.mismatches.Count -eq 0)
			expected = $expected
			actual = $comparison.actual
			mismatches = $comparison.mismatches
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $sampleCheck
	}
	$phasePassed = ($countCheck.passed -and (@($samples | Where-Object {
		$comparison = Compare-TraceSampleAgainstExpectation -Sample $_ -Expected $expected
		$comparison.mismatches.Count -eq 0
	}).Count -eq $samples.Count))
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		type = $type
		serials = $serials
		serialText = $serialText
		command = $command
		response = $response
		samples = $samples
		expected = $expected
		passed = $phasePassed
	}
}

# Handle trace cycle assertion phases.
function Invoke-FunctionalTraceReadCycleAssertPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$type = [string](Get-OptionalProperty -Object $Phase -Name "type" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialText = [string]$serialContext.serialText
	$expectedCycleRaw = Get-OptionalProperty -Object $Phase -Name "expectCycle"
	$expectedCycle = if ($null -eq $expectedCycleRaw) { @() } else { @($expectedCycleRaw) }
	if ($expectedCycle.Count -le 0) {
		throw "trace_read_cycle_assert phase requires expectCycle."
	}
	$limit = [int](Get-OptionalProperty -Object $Phase -Name "limit" -DefaultValue ([Math]::Max($expectedCycle.Count, 8)))
	$minimumCount = [int](Get-OptionalProperty -Object $Phase -Name "minimumCount" -DefaultValue $expectedCycle.Count)
	if ($DryRun) {
		$check = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			passed = $true
			skipped = $true
			reason = "dry_run"
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $check
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			type = $type
			serials = $serials
			serialText = $serialText
			limit = $limit
			minimumCount = $minimumCount
			expectedCycle = $expectedCycle
			reads = @()
			passed = $true
			dryRun = $true
		}
	}
	$phasePassed = $true
	$readResults = New-Object System.Collections.Generic.List[object]
	foreach ($serial in $serials) {
		$readResult = Invoke-NodeTraceRead -Connection $ExecutionState.Connection -Type $type -Serial $serial -Limit $limit
		$samples = @($readResult.samples)
		$countCheck = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "sample_count"
			type = $type
			serial = $serial
			passed = ($samples.Count -ge $minimumCount)
			expectedMinimum = $minimumCount
			actual = $samples.Count
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $countCheck
		if (-not $countCheck.passed) {
			$phasePassed = $false
		}
		$matchResult = if ($countCheck.passed) {
			Find-TraceCycleMatch -Samples $samples -ExpectedCycle $expectedCycle
		} else {
			[ordered]@{
				matched = $false
				rotation = $null
				searchWindowCount = 0
				actualWindow = @($samples)
				failures = @(
					[ordered]@{
						reason = "count_check_failed"
					}
				)
			}
		}
		$cycleCheck = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "cycle"
			type = $type
			serial = $serial
			passed = [bool]$matchResult.matched
			expectedCycle = $expectedCycle
			actualWindow = $matchResult.actualWindow
			rotation = $matchResult.rotation
			searchWindowCount = $matchResult.searchWindowCount
			failures = $matchResult.failures
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $cycleCheck
		if (-not $cycleCheck.passed) {
			$phasePassed = $false
		}
		$readResults.Add([ordered]@{
			serial = $serial
			command = $readResult.command
			response = $readResult.response
			sampleCount = $samples.Count
			samples = $samples
			matched = [bool]$matchResult.matched
			rotation = $matchResult.rotation
			searchWindowCount = $matchResult.searchWindowCount
			actualWindow = $matchResult.actualWindow
			failures = $matchResult.failures
		})
	}
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		type = $type
		serials = $serials
		serialText = $serialText
		limit = $limit
		minimumCount = $minimumCount
		expectedCycle = $expectedCycle
		reads = @($readResults.ToArray())
		passed = $phasePassed
	}
}

# Handle trace tick window assertion phases.
function Invoke-FunctionalTraceReadTickAssertPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$type = [string](Get-OptionalProperty -Object $Phase -Name "type" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase
	$serials = @($serialContext.serials)
	$serialText = [string]$serialContext.serialText
	$mountRef = [string](Get-OptionalProperty -Object $Phase -Name "mountRef" -DefaultValue "")
	$anchorRef = [string](Get-OptionalProperty -Object $Phase -Name "anchorRef" -DefaultValue "")
	$referencePhaseRef = [string](Get-OptionalProperty -Object $Phase -Name "referencePhaseRef" -DefaultValue "")
	$referenceStartStrategy = [string](Get-OptionalProperty -Object $Phase -Name "referenceStartStrategy" -DefaultValue "min")
	if ([string]::IsNullOrWhiteSpace($mountRef)) {
		throw "trace_read_tick_assert phase requires mountRef."
	}
	if ([string]::IsNullOrWhiteSpace($anchorRef)) {
		throw "trace_read_tick_assert phase requires anchorRef."
	}
	$mountPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $ExecutionState.PhaseContext -PhaseName $mountRef
	$anchorPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $ExecutionState.PhaseContext -PhaseName $anchorRef
	$mountEvery = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "every" -DefaultValue 0)
	if ($mountEvery -ne 1) {
		throw "trace_read_tick_assert requires mountRef every=1."
	}
	$anchorTickWindow = Get-OptionalProperty -Object $anchorPhaseResult -Name "tickWindow"
	if ($null -eq $anchorTickWindow) {
		throw "trace_read_tick_assert anchorRef must point to a command phase with tickWindow."
	}
	$commandStartTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "startTick" -DefaultValue -1L)
	$commandEndTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "endTick" -DefaultValue -1L)
	if ($commandStartTick -lt 0L -or $commandEndTick -lt 0L) {
		throw "trace_read_tick_assert anchorRef tickWindow is invalid."
	}
	$anchorJobStartTick = Get-OptionalProperty -Object $anchorPhaseResult -Name "jobStartTick"
	$resolvedAnchorStartTick = $commandStartTick
	if ($null -ne $anchorJobStartTick -and [long]$anchorJobStartTick -ge 0L) {
		$resolvedAnchorStartTick = [long]$anchorJobStartTick
	}
	$referenceStartTick = $null
	if (-not [string]::IsNullOrWhiteSpace($referencePhaseRef)) {
		$referencePhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $ExecutionState.PhaseContext -PhaseName $referencePhaseRef
		$referenceStartTick = Resolve-TraceLatencyReferenceStartTick `
			-ReferencePhaseResult $referencePhaseResult `
			-Strategy $referenceStartStrategy
	}
	$resolvedSearchAnchorStartTick = if ($null -ne $referenceStartTick) {
		[long]$referenceStartTick
	} else {
		[long]$resolvedAnchorStartTick
	}
	$expectedTicks = @(Resolve-TraceTickExpectations -Phase $Phase -Type $type -PhaseContext $ExecutionState.PhaseContext)
	if ($expectedTicks.Count -le 0) {
		throw "trace_read_tick_assert phase resolved no expected ticks."
	}
	$alignmentSlackTicks = [int](Get-OptionalProperty -Object $Phase -Name "alignmentSlackTicks" -DefaultValue 1)
	$limit = [int](Get-OptionalProperty -Object $Phase -Name "limit" -DefaultValue ([Math]::Max($expectedTicks.Count + $alignmentSlackTicks + 4, 8)))
	if ($DryRun) {
		$check = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			passed = $true
			skipped = $true
			reason = "dry_run"
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $check
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			type = $type
			serials = $serials
			serialText = $serialText
			mountRef = $mountRef
			anchorRef = $anchorRef
			referencePhaseRef = $referencePhaseRef
			referenceStartStrategy = $referenceStartStrategy
			limit = $limit
			alignmentSlackTicks = $alignmentSlackTicks
			expectedTicks = $expectedTicks
			summary = [ordered]@{
				type = $type
				requested = $serials.Count
				analyzed = 0
				matched = 0
				unmatched = $serials.Count
				expectedTickCount = $expectedTicks.Count
				anchorStartTick = $resolvedAnchorStartTick
				searchAnchorStartTick = $resolvedSearchAnchorStartTick
				referencePhaseRef = $referencePhaseRef
				referenceStartStrategy = $referenceStartStrategy
				referenceStartTick = $referenceStartTick
				matchedStartTickStats = (New-TraceLatencyStats -Values @())
			}
			reads = @()
			passed = $true
			dryRun = $true
		}
	}
	$phasePassed = $true
	$readResults = New-Object System.Collections.Generic.List[object]
	$matchedStartTicks = New-Object System.Collections.Generic.List[long]
	foreach ($serial in $serials) {
		$mountTick = Get-TraceMountTickForSerial -MountPhaseResult $mountPhaseResult -Serial $serial
		$mountCapacity = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "capacity" -DefaultValue $limit)
		$searchTickMax = 0L
		$startTickMin = $resolvedSearchAnchorStartTick
		if ($null -ne $mountTick) {
			$startTickMin = [Math]::Max([long]$startTickMin, ([long]$mountTick + 1L))
		}
		$startTickMax = [long]$resolvedSearchAnchorStartTick + [Math]::Max(0, $alignmentSlackTicks)
		$searchTickMax = [long]$startTickMax
		$currentLimit = [Math]::Min([Math]::Max(1, $limit), [Math]::Max(1, $mountCapacity))
		$readResult = Invoke-NodeTraceRead -Connection $ExecutionState.Connection -Type $type -Serial $serial -Limit $currentLimit
		$samples = @($readResult.samples)
		$chronologicalSamples = @(Convert-ToChronologicalTraceSamples -Samples $samples)
		while ($currentLimit -lt $mountCapacity) {
			$expandedLimit = Get-ExpandedTraceReadLimit `
				-CurrentLimit $currentLimit `
				-Capacity $mountCapacity `
				-StartTickMin $startTickMin `
				-Samples $chronologicalSamples
			if ($expandedLimit -le $currentLimit) {
				break
			}
			$previousLimit = $currentLimit
			$previousReadResult = $readResult
			$previousSamples = @($samples)
			$previousChronologicalSamples = @($chronologicalSamples)
			$currentLimit = $expandedLimit
			$expandedReadResult = Invoke-NodeTraceRead -Connection $ExecutionState.Connection -Type $type -Serial $serial -Limit $currentLimit
			if (Test-NodeTraceReadRateLimited -ResponseText ([string]$expandedReadResult.response)) {
				$currentLimit = $previousLimit
				$readResult = $previousReadResult
				$samples = @($previousSamples)
				$chronologicalSamples = @($previousChronologicalSamples)
				break
			}
			$readResult = $expandedReadResult
			$samples = @($readResult.samples)
			$chronologicalSamples = @(Convert-ToChronologicalTraceSamples -Samples $samples)
		}
		$eligibleSamples = @(
			$chronologicalSamples |
				Where-Object {
					$tickProperty = $_.PSObject.Properties["tick"]
					$null -ne $tickProperty -and [long]$tickProperty.Value -ge $startTickMin
				}
		)
		if ($eligibleSamples.Count -gt 0) {
			$eligibleLastTick = [long](@($eligibleSamples | Select-Object -Last 1)[0].tick)
			$latestCompleteWindowStart = $eligibleLastTick - [long]($expectedTicks.Count - 1)
			if ($latestCompleteWindowStart -gt $searchTickMax) {
				$searchTickMax = $latestCompleteWindowStart
			}
		}
		$countCheck = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "eligible_sample_count"
			type = $type
			serial = $serial
			passed = ($eligibleSamples.Count -ge $expectedTicks.Count)
			expectedMinimum = $expectedTicks.Count
			actual = $eligibleSamples.Count
			rawSampleCount = $samples.Count
			startTickMin = $startTickMin
			startTickMax = $startTickMax
			searchTickMax = $searchTickMax
			mountTick = $mountTick
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $countCheck
		if (-not $countCheck.passed) {
			$phasePassed = $false
		}
		if ($countCheck.passed) {
			$matchResult = Find-TraceTickWindowMatch `
				-Samples $eligibleSamples `
				-ExpectedTicks $expectedTicks `
				-StartTickMin $startTickMin `
				-SearchTickMax $searchTickMax
		} else {
			$matchResult = [ordered]@{
				matched = $false
				startTick = $null
				alignmentOffset = $null
				candidateCount = 0
				actualWindow = @($eligibleSamples)
				failures = @(
					[ordered]@{
						reason = "count_check_failed"
					}
				)
			}
		}
		$tickCheck = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "tick_window"
			type = $type
			serial = $serial
			passed = [bool]$matchResult.matched
			expectedTicks = $expectedTicks
			actualWindow = $matchResult.actualWindow
			startTick = $matchResult.startTick
			alignmentOffset = $matchResult.alignmentOffset
			candidateCount = $matchResult.candidateCount
			failures = $matchResult.failures
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $tickCheck
		if (-not $tickCheck.passed) {
			$phasePassed = $false
		}
		if ($tickCheck.passed -and $null -ne $matchResult.startTick) {
			$matchedStartTicks.Add([long]$matchResult.startTick)
		}
		$readResults.Add([ordered]@{
			serial = $serial
			command = $readResult.command
			response = $readResult.response
			requestedLimit = $limit
			readLimit = $currentLimit
			rawSampleCount = $samples.Count
			samples = $samples
			eligibleSamples = $eligibleSamples
			mountTick = $mountTick
			startTickMin = $startTickMin
			startTickMax = $startTickMax
			searchTickMax = $searchTickMax
			matched = [bool]$matchResult.matched
			startTick = $matchResult.startTick
			alignmentOffset = $matchResult.alignmentOffset
			candidateCount = $matchResult.candidateCount
			actualWindow = $matchResult.actualWindow
			failures = $matchResult.failures
		})
	}
	$matchedStartTickArray = @($matchedStartTicks.ToArray())
	$matchedStartTickStats = New-TraceLatencyStats -Values $matchedStartTickArray
	$matchedCount = $matchedStartTickArray.Count
	$analyzedCount = $readResults.Count
	$unmatchedCount = [Math]::Max(0, $serials.Count - $matchedCount)
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		type = $type
		serials = $serials
		serialText = $serialText
		mountRef = $mountRef
		anchorRef = $anchorRef
		referencePhaseRef = $referencePhaseRef
		referenceStartStrategy = $referenceStartStrategy
		limit = $limit
		alignmentSlackTicks = $alignmentSlackTicks
		expectedTicks = $expectedTicks
		summary = [ordered]@{
			type = $type
			requested = $serials.Count
			analyzed = $analyzedCount
			matched = $matchedCount
			unmatched = $unmatchedCount
			expectedTickCount = $expectedTicks.Count
			anchorStartTick = $resolvedAnchorStartTick
			searchAnchorStartTick = $resolvedSearchAnchorStartTick
			referencePhaseRef = $referencePhaseRef
			referenceStartStrategy = $referenceStartStrategy
			referenceStartTick = $referenceStartTick
			matchedStartTickStats = $matchedStartTickStats
		}
		reads = @($readResults.ToArray())
		passed = $phasePassed
	}
}

# Handle sync latency collection phases.
function Invoke-FunctionalTraceSyncLatencyCollectPhase {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	$type = [string](Get-OptionalProperty -Object $Phase -Name "type" -DefaultValue "")
	$serialContext = Resolve-FunctionalPhaseSerialContext -ExecutionState $ExecutionState -Phase $Phase -DefaultSerialFormat "range"
	$serials = @($serialContext.serials)
	$serialFormat = [string]$serialContext.serialFormat
	$serialText = [string]$serialContext.serialText
	$mountRef = [string](Get-OptionalProperty -Object $Phase -Name "mountRef" -DefaultValue "")
	$anchorRef = [string](Get-OptionalProperty -Object $Phase -Name "anchorRef" -DefaultValue "")
	if ([string]::IsNullOrWhiteSpace($mountRef)) {
		throw "trace_sync_latency_collect phase requires mountRef."
	}
	if ([string]::IsNullOrWhiteSpace($anchorRef)) {
		throw "trace_sync_latency_collect phase requires anchorRef."
	}
	$mountPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $ExecutionState.PhaseContext -PhaseName $mountRef
	$anchorPhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $ExecutionState.PhaseContext -PhaseName $anchorRef
	$mountEvery = [int](Get-OptionalProperty -Object $mountPhaseResult -Name "every" -DefaultValue 0)
	if ($mountEvery -ne 1) {
		throw "trace_sync_latency_collect requires mountRef every=1."
	}
	$anchorTickWindow = Get-OptionalProperty -Object $anchorPhaseResult -Name "tickWindow"
	if ($null -eq $anchorTickWindow) {
		throw "trace_sync_latency_collect anchorRef must point to a command phase with tickWindow."
	}
	$commandStartTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "startTick" -DefaultValue -1L)
	if ($commandStartTick -lt 0L) {
		throw "trace_sync_latency_collect anchorRef tickWindow is invalid."
	}
	$commandEndTick = [long](Get-OptionalProperty -Object $anchorTickWindow -Name "endTick" -DefaultValue $commandStartTick)
	if ($commandEndTick -lt $commandStartTick) {
		$commandEndTick = $commandStartTick
	}
	$anchorJobStartTick = Get-OptionalProperty -Object $anchorPhaseResult -Name "jobStartTick"
	$resolvedCommandStartTick = $commandStartTick
	if ($null -ne $anchorJobStartTick -and [long]$anchorJobStartTick -ge 0L) {
		$resolvedCommandStartTick = [long]$anchorJobStartTick
	}
	$anchorTickStrategy = ([string](Get-OptionalProperty -Object $Phase -Name "anchorTickStrategy" -DefaultValue "start")).Trim().ToLowerInvariant()
	switch ($anchorTickStrategy) {
		"start" { $commandAnchorTick = $resolvedCommandStartTick }
		"end" { $commandAnchorTick = $commandEndTick }
		default { throw "Unsupported trace_sync_latency_collect anchorTickStrategy: $anchorTickStrategy" }
	}
	$expectedTicks = @(Resolve-TraceTickExpectations -Phase $Phase -Type $type -PhaseContext $ExecutionState.PhaseContext)
	if ($expectedTicks.Count -le 0) {
		throw "trace_sync_latency_collect phase resolved no expected ticks."
	}
	$expectedPowers = @(Convert-TraceExpectationsToPowerSequence -ExpectedTicks $expectedTicks)
	$expectedSequenceText = Format-SignalSequenceText -Sequence $expectedPowers
	$referencePhaseRef = [string](Get-OptionalProperty -Object $Phase -Name "referencePhaseRef" -DefaultValue "")
	$referenceStartStrategy = [string](Get-OptionalProperty -Object $Phase -Name "referenceStartStrategy" -DefaultValue "min")
	$expectedDelayTicksRaw = Get-OptionalProperty -Object $Phase -Name "expectedDelayTicks"
	$expectedDelayTicks = if ($null -eq $expectedDelayTicksRaw) { $null } else { [long]$expectedDelayTicksRaw }
	$windowTicks = @(Resolve-TraceLatencyWindowTicks -Phase $Phase)
	$windowCoverageChecks = @(Resolve-TraceLatencyWindowCoverageChecks -Phase $Phase)
	foreach ($windowCoverageCheck in @($windowCoverageChecks)) {
		$windowTicks += [int](Get-OptionalProperty -Object $windowCoverageCheck -Name "windowTicks" -DefaultValue 0)
	}
	$windowTicks = @($windowTicks | Sort-Object -Unique)
	$requiredMatchRatio = [double](Get-OptionalProperty -Object $Phase -Name "requiredMatchRatio" -DefaultValue 1.0)
	$maxDelayTicksRaw = Get-OptionalProperty -Object $Phase -Name "maxDelayTicks"
	$maxDelayTicks = if ($null -eq $maxDelayTicksRaw) { $null } else { [Math]::Max(0, [int]$maxDelayTicksRaw) }
	$latestExpectedStartTick = if ($null -eq $maxDelayTicks) { $null } else { ([long]$commandAnchorTick + [long]$maxDelayTicks) }
	$chunkSize = [int](Get-OptionalProperty -Object $Phase -Name "chunkSize" -DefaultValue $(if ($serials.Count -gt 256) { 128 } else { $serials.Count }))
	$batchPauseMs = [int](Get-OptionalProperty -Object $Phase -Name "batchPauseMs" -DefaultValue $(if ($serials.Count -gt $chunkSize) { 100 } else { 0 }))
	$command = if ($serials.Count -le $chunkSize) {
		$commandText = (
			"redstonelink bench trace sync_latency {0} {1} startTick={2} powers={3}" -f
			$type,
			$serialText,
			$commandAnchorTick,
			$expectedSequenceText
		)
		if ($null -ne $latestExpectedStartTick) {
			$commandText = ("{0} latestStartTick={1}" -f $commandText, $latestExpectedStartTick)
		}
		Wrap-WithPlayerContext ($commandText)
	} else {
		@()
	}
	if ($DryRun) {
		return [ordered]@{
			kind = $Kind
			name = $PhaseName
			type = $type
			serials = $serials
			serialText = $serialText
			mountRef = $mountRef
			anchorRef = $anchorRef
			anchorTickStrategy = $anchorTickStrategy
			command = $command
			expectedStartTick = $commandAnchorTick
			maxDelayTicks = $maxDelayTicks
			expectedPowers = $expectedPowers
			summary = [ordered]@{
				type = $type
				requested = $serials.Count
				analyzed = $serials.Count
				mounted = $serials.Count
				matched = $serials.Count
				unmatched = 0
				matchedRatio = 1.0
				expectedStartTick = $commandAnchorTick
				expectedTickCount = $expectedPowers.Count
				latestExpectedStartTick = $latestExpectedStartTick
				matchedStartTickStats = (New-TraceLatencyStats -Values @())
				inputDelayStats = (New-TraceLatencyStats -Values @())
				referencePhaseRef = $referencePhaseRef
				referenceStartTick = $null
				referenceDelayStats = (New-TraceLatencyStats -Values @())
				evaluatedDelayField = "inputDelayTicks"
				expectedDelayTicks = $expectedDelayTicks
				windowCoverage = @()
			}
			items = @()
			passed = $true
			dryRun = $true
		}
	}
	$batchCommands = New-Object System.Collections.Generic.List[object]
	$batchResponses = New-Object System.Collections.Generic.List[string]
	$parsedItems = New-Object System.Collections.Generic.List[object]
	foreach ($serialBatch in @(Split-SerialsIntoBatches -Serials $serials -ChunkSize $chunkSize)) {
		$batchSerialText = Format-SerialInputText -Serials $serialBatch -Style $serialFormat
		$batchCommandText = (
			"redstonelink bench trace sync_latency {0} {1} startTick={2} powers={3}" -f
			$type,
			$batchSerialText,
			$commandAnchorTick,
			$expectedSequenceText
		)
		if ($null -ne $latestExpectedStartTick) {
			$batchCommandText = ("{0} latestStartTick={1}" -f $batchCommandText, $latestExpectedStartTick)
		}
		$batchCommand = Wrap-WithPlayerContext ($batchCommandText)
		$batchResponse = Invoke-RconCommand -Connection $ExecutionState.Connection -Command $batchCommand -Silent
		Assert-BenchCommandResponse `
			-Command $batchCommand `
			-ResponseText ([string]$batchResponse) `
			-ExpectedPrefix "[RedstoneLink/Bench] trace_sync_latency_summary"
		$parsedResponse = Parse-BenchTraceLatencyResponse -ResponseText ([string]$batchResponse)
		$batchCommands.Add($batchCommand)
		$batchResponses.Add([string]$batchResponse)
		foreach ($parsedItem in @($parsedResponse.items)) {
			$parsedItems.Add($parsedItem)
		}
		if ($batchPauseMs -gt 0 -and $serialBatch.Count -lt $serials.Count) {
			Start-Sleep -Milliseconds $batchPauseMs
		}
	}
	$command = if ($batchCommands.Count -le 1) { [string]$batchCommands[0] } else { @($batchCommands.ToArray()) }
	$response = [string]::Join("`n", @($batchResponses.ToArray()))
	$items = New-Object System.Collections.Generic.List[object]
	$matchedStartTicks = New-Object System.Collections.Generic.List[long]
	$inputDelayValues = New-Object System.Collections.Generic.List[long]
	foreach ($item in @($parsedItems.ToArray())) {
		$currentItem = [ordered]@{
			serial = [long](Get-OptionalProperty -Object $item -Name "serial" -DefaultValue 0L)
			matched = [bool](Get-OptionalProperty -Object $item -Name "matched" -DefaultValue $false)
			mounted = [bool](Get-OptionalProperty -Object $item -Name "mounted" -DefaultValue $false)
			reason = [string](Get-OptionalProperty -Object $item -Name "reason" -DefaultValue "")
			actualStartTick = Get-OptionalProperty -Object $item -Name "actualStartTick"
			inputDelayTicks = Get-OptionalProperty -Object $item -Name "inputDelayTicks"
			mountTick = [long](Get-OptionalProperty -Object $item -Name "mountTick" -DefaultValue -1)
			latestSampleTick = [long](Get-OptionalProperty -Object $item -Name "latestSampleTick" -DefaultValue -1)
			eligibleSamples = [int](Get-OptionalProperty -Object $item -Name "eligibleSamples" -DefaultValue 0)
		}
		if ($currentItem.matched -and $null -ne $currentItem.actualStartTick) {
			$matchedStartTicks.Add([long]$currentItem.actualStartTick)
		}
		if ($currentItem.matched -and $null -ne $currentItem.inputDelayTicks) {
			$inputDelayValues.Add([long]$currentItem.inputDelayTicks)
		}
		$items.Add([pscustomobject]$currentItem)
	}
	$requestedCount = $serials.Count
	$analyzedCount = $items.Count
	$mountedCount = @($items.ToArray() | Where-Object { [bool]$_.mounted }).Count
	$matchedCount = @($items.ToArray() | Where-Object { [bool]$_.matched }).Count
	$unmatchedCount = [Math]::Max(0, $requestedCount - $matchedCount)
	$referenceStartTick = $null
	$referenceDelayValues = New-Object System.Collections.Generic.List[long]
	$evaluatedDelayField = "inputDelayTicks"
	if (-not [string]::IsNullOrWhiteSpace($referencePhaseRef)) {
		$referencePhaseResult = Resolve-FunctionalPhaseResult -PhaseContext $ExecutionState.PhaseContext -PhaseName $referencePhaseRef
		$referenceStartTick = Resolve-TraceLatencyReferenceStartTick `
			-ReferencePhaseResult $referencePhaseResult `
			-Strategy $referenceStartStrategy
		foreach ($item in @($items.ToArray())) {
			if ($item.matched -and $null -ne $item.actualStartTick -and $null -ne $referenceStartTick) {
				$item | Add-Member -NotePropertyName "referenceDelayTicks" -NotePropertyValue ([long]$item.actualStartTick - [long]$referenceStartTick)
				$referenceDelayValues.Add([long]$item.referenceDelayTicks)
			} else {
				$item | Add-Member -NotePropertyName "referenceDelayTicks" -NotePropertyValue $null
			}
		}
		$evaluatedDelayField = "referenceDelayTicks"
	}
	$matchedRatio = if ($requestedCount -le 0) {
		0.0
	} else {
		[Math]::Round(($matchedCount / [double]$requestedCount), 6)
	}
	$windowCoverage = New-Object System.Collections.Generic.List[object]
	foreach ($windowTick in @($windowTicks)) {
		$coveredCount = 0
		foreach ($item in @($items.ToArray())) {
			$delayValue = $item.PSObject.Properties[$evaluatedDelayField].Value
			if ($item.matched -and $null -ne $delayValue -and [long]$delayValue -le [int]$windowTick) {
				$coveredCount++
			}
		}
		$coverageRatio = if ($matchedCount -le 0) { 0.0 } else { [Math]::Round(($coveredCount / [double]$matchedCount), 6) }
		$windowCoverage.Add([ordered]@{
			windowTicks = [int]$windowTick
			covered = $coveredCount
			totalMatched = $matchedCount
			coverageRatio = $coverageRatio
		})
	}
	$matchedStartTickArray = @($matchedStartTicks.ToArray())
	$inputDelayValueArray = @($inputDelayValues.ToArray())
	$referenceDelayValueArray = @($referenceDelayValues.ToArray())
	$matchedStartTickStats = New-TraceLatencyStats -Values $matchedStartTickArray
	$inputDelayStats = New-TraceLatencyStats -Values $inputDelayValueArray
	$referenceDelayStats = New-TraceLatencyStats -Values $referenceDelayValueArray
	$evaluatedDelayStats = if ($evaluatedDelayField -eq "referenceDelayTicks") {
		$referenceDelayStats
	} else {
		$inputDelayStats
	}
	$evaluatedDelayValues = if ($evaluatedDelayField -eq "referenceDelayTicks") {
		$referenceDelayValueArray
	} else {
		$inputDelayValueArray
	}
	$phasePassed = ($matchedRatio -ge $requiredMatchRatio)
	$matchCheck = [ordered]@{
		phase = $PhaseName
		kind = $Kind
		scope = "matched_ratio"
		type = $type
		passed = $phasePassed
		expectedMinimum = $requiredMatchRatio
		actual = $matchedRatio
		requested = $requestedCount
		matched = $matchedCount
	}
	Add-FunctionalCheck -ExecutionState $ExecutionState -Check $matchCheck
	if ($null -ne $expectedDelayTicks) {
		# Parameterized windows validate exact delay values, not only coverage.
		$actualDelayMinimum = $null
		$actualDelayMaximum = $null
		$sortedEvaluatedDelayValues = @(@($evaluatedDelayValues) | Sort-Object)
		if ($sortedEvaluatedDelayValues.Count -gt 0) {
			$actualDelayMinimum = [long]$sortedEvaluatedDelayValues[0]
			$actualDelayMaximum = [long]$sortedEvaluatedDelayValues[$sortedEvaluatedDelayValues.Count - 1]
		}
		$delayPassed = $false
		if ($matchedCount -gt 0 -and $null -ne $actualDelayMinimum -and $null -ne $actualDelayMaximum) {
			$delayPassed = (
				([long]$actualDelayMinimum -eq [long]$expectedDelayTicks) -and
				([long]$actualDelayMaximum -eq [long]$expectedDelayTicks)
			)
		}
		$delayCheck = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "delay_ticks"
			type = $type
			delayField = $evaluatedDelayField
			passed = $delayPassed
			expected = [long]$expectedDelayTicks
			actualMinimum = $actualDelayMinimum
			actualMaximum = $actualDelayMaximum
			matched = $matchedCount
			evaluated = [int]$matchedCount
			evaluatedMatched = [int]$matchedCount
			delayCheckVersion = "v2"
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $delayCheck
		$phasePassed = $phasePassed -and $delayCheck.passed
	}
	foreach ($windowCoverageCheck in @($windowCoverageChecks)) {
		$targetWindowTick = [int](Get-OptionalProperty -Object $windowCoverageCheck -Name "windowTicks" -DefaultValue 0)
		$expectedMinimum = Get-OptionalProperty -Object $windowCoverageCheck -Name "expectedMinimum"
		$expectedMaximum = Get-OptionalProperty -Object $windowCoverageCheck -Name "expectedMaximum"
		$windowCoverageEntry = @(
			@($windowCoverage.ToArray()) |
				Where-Object { [int](Get-OptionalProperty -Object $_ -Name "windowTicks" -DefaultValue -1) -eq $targetWindowTick } |
				Select-Object -First 1
		) | Select-Object -First 1
		$coveragePassed = ($null -ne $windowCoverageEntry)
		$actualCoverage = if ($null -eq $windowCoverageEntry) {
			$null
		} else {
			[double](Get-OptionalProperty -Object $windowCoverageEntry -Name "coverageRatio" -DefaultValue 0.0)
		}
		$coveredCount = if ($null -eq $windowCoverageEntry) {
			0
		} else {
			[int](Get-OptionalProperty -Object $windowCoverageEntry -Name "covered" -DefaultValue 0)
		}
		if ($coveragePassed -and $null -ne $expectedMinimum -and [double]$actualCoverage -lt [double]$expectedMinimum) {
			$coveragePassed = $false
		}
		if ($coveragePassed -and $null -ne $expectedMaximum -and [double]$actualCoverage -gt [double]$expectedMaximum) {
			$coveragePassed = $false
		}
		$coverageCheckResult = [ordered]@{
			phase = $PhaseName
			kind = $Kind
			scope = "window_coverage"
			type = $type
			windowTicks = $targetWindowTick
			passed = $coveragePassed
			expectedMinimum = $expectedMinimum
			expectedMaximum = $expectedMaximum
			actual = $actualCoverage
			covered = $coveredCount
			totalMatched = $matchedCount
		}
		Add-FunctionalCheck -ExecutionState $ExecutionState -Check $coverageCheckResult
		$phasePassed = $phasePassed -and $coverageCheckResult.passed
	}
	return [ordered]@{
		kind = $Kind
		name = $PhaseName
		type = $type
		serials = $serials
		serialText = $serialText
		mountRef = $mountRef
		anchorRef = $anchorRef
		anchorTickStrategy = $anchorTickStrategy
		referencePhaseRef = $referencePhaseRef
		command = $command
		response = $response
		expectedStartTick = $commandAnchorTick
		maxDelayTicks = $maxDelayTicks
		expectedPowers = $expectedPowers
		summary = [ordered]@{
			type = $type
			requested = $requestedCount
			analyzed = $analyzedCount
			mounted = $mountedCount
			matched = $matchedCount
			unmatched = $unmatchedCount
			matchedRatio = $matchedRatio
			expectedStartTick = $commandAnchorTick
			expectedTickCount = $expectedPowers.Count
			latestExpectedStartTick = $latestExpectedStartTick
			matchedStartTickStats = $matchedStartTickStats
			inputDelayStats = $inputDelayStats
			referencePhaseRef = $referencePhaseRef
			referenceStartTick = $referenceStartTick
			referenceDelayStats = $referenceDelayStats
			evaluatedDelayField = $evaluatedDelayField
			expectedDelayTicks = $expectedDelayTicks
			windowCoverage = @($windowCoverage.ToArray())
		}
		items = @($items.ToArray())
		passed = $phasePassed
	}
}

# Dispatch phase kinds to dedicated handlers.
function Invoke-FunctionalPhaseHandler {
	param(
		$ExecutionState,
		$Phase,
		[string]$Kind,
		[string]$PhaseName
	)
	switch ($Kind) {
		"trace_mount" { return Invoke-FunctionalTraceMountPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"trace_unmount" { return Invoke-FunctionalTraceUnmountPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"input_start_square" { return Invoke-FunctionalInputStartSquarePhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"input_start_custom" { return Invoke-FunctionalInputStartCustomPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"input_start_custom_batch" { return Invoke-FunctionalInputStartCustomBatchPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"input_clear" { return Invoke-FunctionalInputClearPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"link_command" { return Invoke-FunctionalLinkCommandPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"command_assert" { return Invoke-FunctionalCommandAssertPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"activate_batch" { return Invoke-FunctionalActivateBatchPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"wait_ticks" { return Invoke-FunctionalWaitTicksPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"trace_latest_assert" { return Invoke-FunctionalTraceLatestAssertPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"trace_read_cycle_assert" { return Invoke-FunctionalTraceReadCycleAssertPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"trace_read_tick_assert" { return Invoke-FunctionalTraceReadTickAssertPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		"trace_sync_latency_collect" { return Invoke-FunctionalTraceSyncLatencyCollectPhase -ExecutionState $ExecutionState -Phase $Phase -Kind $Kind -PhaseName $PhaseName }
		default { throw "Unsupported functional phase kind: $Kind" }
	}
}

function Invoke-FunctionalPhases {
	param(
		$Connection,
		$CaseConfig,
		[hashtable]$SourceSerialMaps,
		[hashtable]$TargetSerialMap
	)
	$phaseResults = New-Object System.Collections.Generic.List[object]
	$checks = New-Object System.Collections.Generic.List[object]
	$failedChecks = New-Object System.Collections.Generic.List[object]
	$phaseContext = @{}
	$executionState = New-FunctionalPhaseExecutionState `
		-Connection $Connection `
		-SourceSerialMaps $SourceSerialMaps `
		-TargetSerialMap $TargetSerialMap `
		-Checks $checks `
		-FailedChecks $failedChecks `
		-PhaseContext $phaseContext
	foreach ($phase in $CaseConfig.phases) {
		$kind = [string](Get-OptionalProperty -Object $phase -Name "kind" -DefaultValue "")
		if ([string]::IsNullOrWhiteSpace($kind)) {
			throw "Functional phase kind is required."
		}
		$phaseName = [string](Get-OptionalProperty -Object $phase -Name "name" -DefaultValue $kind)
		$phaseResult = Invoke-FunctionalPhaseHandler `
			-ExecutionState $executionState `
			-Phase $phase `
			-Kind $kind `
			-PhaseName $phaseName
		Add-FunctionalPhaseResult -PhaseResults $phaseResults -PhaseContext $phaseContext -PhaseName $phaseName -PhaseResult $phaseResult
	}
	return [ordered]@{
		phases = @($phaseResults.ToArray())
		checks = @($checks.ToArray())
		failedChecks = @($failedChecks.ToArray())
		passed = ($failedChecks.Count -eq 0)
	}
}
