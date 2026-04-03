package com.makomi.command.bench;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.SerialSequenceBatchArgumentType;
import com.makomi.command.input.InputCommandSupport;
import com.makomi.data.input.InputEndpointKind;
import com.makomi.data.input.InputJobSpec;
import com.makomi.data.input.InputPlaybackService;
import com.makomi.data.input.InputWaveformSpec;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * bench/internal 输入命令注册器。
 * <p>
 * 该命令只服务 bench 自动化，补齐“一次命令内同 tick 原子启动多组不同 custom sequence”
 * 的能力，避免多条 RCON 命令天然跨 tick 错位。
 * </p>
 */
public final class BenchInputCommandRegistry {
	private static final int INPUT_MAX_TARGETS = 1024;
	private static final int INPUT_MAX_PHASE_TICKS = 72_000;
	private static final int INPUT_MAX_TOTAL_TICKS = 72_000;
	private static final int INPUT_MAX_SEQUENCE_RAW_LENGTH = 4096;
	private static final int INPUT_MAX_BATCH_ENTRIES = 256;
	private static final int INPUT_DEFAULT_PHASE_TICKS = 0;
	private static final int INPUT_DEFAULT_TOTAL_TICKS = 0;

	private BenchInputCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink bench input` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("input")
			.then(
				Commands
					.literal("start")
					.then(
						Commands
							.literal("triggerSource")
							.then(createTriggerSourceCustomBatchBranch())
					)
			);
	}

	/**
	 * 构建 triggerSource custom 批量原子启动参数树。
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> createTriggerSourceCustomBatchBranch() {
		return Commands
			.literal("custom_batch")
			.then(
				Commands
					.argument("serial_sequence_batch", SerialSequenceBatchArgumentType.serialSequenceBatch())
					.executes(BenchInputCommandRegistry::executeTriggerSourceCustomBatchStart)
					.then(
						Commands
							.argument("phase_ticks", IntegerArgumentType.integer(0, INPUT_MAX_PHASE_TICKS))
							.executes(BenchInputCommandRegistry::executeTriggerSourceCustomBatchStart)
							.then(
								Commands
									.argument("total_ticks", IntegerArgumentType.integer(0, INPUT_MAX_TOTAL_TICKS))
									.executes(BenchInputCommandRegistry::executeTriggerSourceCustomBatchStart)
							)
					)
			);
	}

	/**
	 * 执行 triggerSource custom 批量原子启动。
	 */
	private static int executeTriggerSourceCustomBatchStart(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String rawBatchToken = SerialSequenceBatchArgumentType.getSerialSequenceBatch(context, "serial_sequence_batch");
		int phaseTicks = hasArgument(context, "phase_ticks")
			? IntegerArgumentType.getInteger(context, "phase_ticks")
			: INPUT_DEFAULT_PHASE_TICKS;
		int totalTicks = hasArgument(context, "total_ticks")
			? IntegerArgumentType.getInteger(context, "total_ticks")
			: INPUT_DEFAULT_TOTAL_TICKS;

		BatchParseResult parseResult = parseCustomBatchPlans(source, rawBatchToken, phaseTicks, totalTicks);
		if (!parseResult.valid()) {
			return 0;
		}
		int commandCost = CommandRateLimitService.computeBatchCost(
			2,
			parseResult.totalTargetCount() + parseResult.totalSequenceSampleCount(),
			64
		);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}

		InputPlaybackService.StartBatchResult startResult = InputPlaybackService.startBatch(source.getServer(), parseResult.jobSpecs());
		if (!startResult.started()) {
			if (!startResult.offlineSerials().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.input.start.offline",
						CommandTreeSupport.typeCommandName(
							InputCommandSupport.resolveInputTargetNodeType(InputEndpointKind.TRIGGER_SOURCE_INPUT)
						),
						CommandTreeSupport.formatSerialCollection(startResult.offlineSerials())
					)
				);
				return 0;
			}
			if (!startResult.unsupportedSerials().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.input.start.unsupported",
						CommandTreeSupport.typeCommandName(
							InputCommandSupport.resolveInputTargetNodeType(InputEndpointKind.TRIGGER_SOURCE_INPUT)
						),
						CommandTreeSupport.formatSerialCollection(startResult.unsupportedSerials())
					)
				);
				return 0;
			}
			source.sendFailure(Component.literal("[RedstoneLink/Input] Failed to start atomic custom batch."));
			return 0;
		}

		List<InputPlaybackService.JobInfo> jobInfos = startResult.jobInfos();
		long startTick = jobInfos.isEmpty() ? 0L : jobInfos.get(0).startTick();
		String jobIds = jobInfos.stream().map(jobInfo -> Long.toString(jobInfo.jobId())).collect(Collectors.joining("/"));
		String endpointNames = jobInfos
			.stream()
			.map(jobInfo -> jobInfo.endpointKind().commandName())
			.distinct()
			.collect(Collectors.joining("/"));
		int totalTargetCount = jobInfos.stream().mapToInt(InputPlaybackService.JobInfo::targetCount).sum();
		source.sendSuccess(
			() -> Component.literal(
				String.format(
					Locale.ROOT,
					"[RedstoneLink/Input] Started job=%s, startTick=%d, endpoint=%s, batchJobs=%d, targets=%d, totalTicks=%s, plans=%s.",
					jobIds,
					startTick,
					endpointNames,
					jobInfos.size(),
					totalTargetCount,
					totalTicks <= 0 ? "-" : Integer.toString(totalTicks),
					rawBatchToken
				)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析 bench/internal 的多 `serials@sequence` 批量计划。
	 */
	private static BatchParseResult parseCustomBatchPlans(
		CommandSourceStack source,
		String rawBatchToken,
		int phaseTicks,
		int totalTicks
	) {
		String normalized = rawBatchToken == null ? "" : rawBatchToken.trim();
		if (normalized.isEmpty()) {
			source.sendFailure(Component.literal("[RedstoneLink/Input] custom_batch requires non-empty serial_sequence_batch."));
			return BatchParseResult.invalid();
		}

		String[] rawEntries = normalized.split(";", -1);
		List<InputJobSpec> jobSpecs = new ArrayList<>();
		int totalTargetCount = 0;
		int totalSequenceSampleCount = 0;
		int nonEmptyEntryCount = 0;
		for (String rawEntry : rawEntries) {
			String entry = rawEntry == null ? "" : rawEntry.trim();
			if (entry.isEmpty()) {
				continue;
			}
			nonEmptyEntryCount++;
			if (nonEmptyEntryCount > INPUT_MAX_BATCH_ENTRIES) {
				source.sendFailure(
					Component.literal(
						"[RedstoneLink/Input] custom_batch entries exceed limit: " + INPUT_MAX_BATCH_ENTRIES
					)
				);
				return BatchParseResult.invalid();
			}
			int separatorIndex = entry.indexOf('@');
			if (separatorIndex <= 0 || separatorIndex != entry.lastIndexOf('@') || separatorIndex >= entry.length() - 1) {
				source.sendFailure(Component.literal("[RedstoneLink/Input] Invalid custom_batch entry: " + entry));
				return BatchParseResult.invalid();
			}
			String rawSerials = entry.substring(0, separatorIndex);
			String rawSequence = entry.substring(separatorIndex + 1);
			List<Long> targetSerials = InputCommandSupport.parseAndValidateTargetSerials(
				source,
				rawSerials,
				InputEndpointKind.TRIGGER_SOURCE_INPUT,
				INPUT_MAX_TARGETS
			);
			if (targetSerials == null) {
				return BatchParseResult.invalid();
			}
			List<Integer> sequence = InputCommandSupport.parseAndValidateCustomSequence(
				source,
				rawSequence,
				INPUT_MAX_SEQUENCE_RAW_LENGTH
			);
			if (sequence == null) {
				return BatchParseResult.invalid();
			}
			totalTargetCount += targetSerials.size();
			if (totalTargetCount > INPUT_MAX_TARGETS) {
				source.sendFailure(Component.translatable("message.redstonelink.input.too_many_targets", INPUT_MAX_TARGETS));
				return BatchParseResult.invalid();
			}
			totalSequenceSampleCount += sequence.size();
			jobSpecs.add(
				new InputJobSpec(
					InputEndpointKind.TRIGGER_SOURCE_INPUT,
					targetSerials,
					InputWaveformSpec.customSequence(sequence, phaseTicks),
					totalTicks
				)
			);
		}
		if (jobSpecs.isEmpty()) {
			source.sendFailure(Component.literal("[RedstoneLink/Input] custom_batch resolved no valid entries."));
			return BatchParseResult.invalid();
		}
		return new BatchParseResult(true, List.copyOf(jobSpecs), totalTargetCount, totalSequenceSampleCount);
	}

	/**
	 * 判断命令上下文中是否包含指定参数。
	 */
	private static boolean hasArgument(CommandContext<CommandSourceStack> context, String argumentName) {
		try {
			context.getArgument(argumentName, Object.class);
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	/**
	 * 批量计划解析结果。
	 */
	private record BatchParseResult(
		boolean valid,
		List<InputJobSpec> jobSpecs,
		int totalTargetCount,
		int totalSequenceSampleCount
	) {
		private static BatchParseResult invalid() {
			return new BatchParseResult(false, List.of(), 0, 0);
		}
	}
}
