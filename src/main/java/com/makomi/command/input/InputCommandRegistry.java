package com.makomi.command.input;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.argument.SignalSequenceArgumentType;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.input.InputEndpointKind;
import com.makomi.data.input.InputJobSpec;
import com.makomi.data.input.InputPlaybackService;
import com.makomi.data.input.InputWaveformSpec;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * `input` 命令注册器。
 * <p>
 * 负责输入模拟 job 的注册、启动、停止与列表反馈。
 * </p>
 */
public final class InputCommandRegistry {
	private static final int INPUT_MAX_TARGETS = 1024;
	private static final int INPUT_MAX_PERIOD_TICKS = 72_000;
	private static final int INPUT_MAX_PHASE_TICKS = 72_000;
	private static final int INPUT_MAX_TOTAL_TICKS = 72_000;
	private static final int INPUT_MAX_SEQUENCE_RAW_LENGTH = 4096;
	private static final int INPUT_DEFAULT_HIGH_POWER = 15;
	private static final int INPUT_DEFAULT_LOW_POWER = 0;
	private static final int INPUT_DEFAULT_PHASE_TICKS = 0;
	private static final int INPUT_DEFAULT_TOTAL_TICKS = 0;

	private InputCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink input` 命令根。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("input")
			.requires(source -> RedstoneLinkConfig.commandInputEnabled() && CommandTreeSupport.hasOtherCommandPermission(source))
			.then(createInputStartRoot())
			.then(
				Commands
					.literal("stop")
					.then(Commands.argument("job_id", LongArgumentType.longArg(1L)).executes(InputCommandRegistry::executeInputStop))
			)
			.then(Commands.literal("list").executes(InputCommandRegistry::executeInputList))
			.then(Commands.literal("clear").executes(InputCommandRegistry::executeInputClear));
	}

	/**
	 * 构建 `/redstonelink input start` 子树。
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> createInputStartRoot() {
		return Commands
			.literal("start")
			.then(
				Commands
					.literal("triggerSource")
					.then(createInputSquareBranch(InputEndpointKind.TRIGGER_SOURCE_INPUT))
					.then(createInputCustomBranch(InputEndpointKind.TRIGGER_SOURCE_INPUT))
			)
			.then(
				Commands
					.literal("core")
					.then(
						Commands
							.literal("sync")
							.then(createInputSquareBranch(InputEndpointKind.CORE_SYNC_DIRECT))
							.then(createInputCustomBranch(InputEndpointKind.CORE_SYNC_DIRECT))
					)
			);
	}

	/**
	 * 构建方波输入参数树。
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> createInputSquareBranch(InputEndpointKind endpointKind) {
		return Commands
			.literal("square")
			.then(
				Commands.argument("serials", SerialBatchArgumentType.serialBatch()).then(
					Commands
						.argument("period_ticks", IntegerArgumentType.integer(1, INPUT_MAX_PERIOD_TICKS))
						.executes(context -> executeInputStartSquare(context, endpointKind))
						.then(
							Commands
								.argument("high_ticks", IntegerArgumentType.integer(1, INPUT_MAX_PERIOD_TICKS))
								.executes(context -> executeInputStartSquare(context, endpointKind))
								.then(
									Commands
										.argument("high_power", IntegerArgumentType.integer(0, 15))
										.executes(context -> executeInputStartSquare(context, endpointKind))
										.then(
											Commands
												.argument("low_power", IntegerArgumentType.integer(0, 15))
												.executes(context -> executeInputStartSquare(context, endpointKind))
												.then(
													Commands
														.argument("phase_ticks", IntegerArgumentType.integer(0, INPUT_MAX_PHASE_TICKS))
														.executes(context -> executeInputStartSquare(context, endpointKind))
														.then(
															Commands
																.argument("total_ticks", IntegerArgumentType.integer(0, INPUT_MAX_TOTAL_TICKS))
																.executes(context -> executeInputStartSquare(context, endpointKind))
														)
												)
										)
								)
						)
				)
			);
	}

	/**
	 * 构建自定义序列输入参数树。
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> createInputCustomBranch(InputEndpointKind endpointKind) {
		return Commands
			.literal("custom")
			.then(
				Commands.argument("serials", SerialBatchArgumentType.serialBatch()).then(
					Commands
						.argument("sequence", SignalSequenceArgumentType.signalSequence())
						.executes(context -> executeInputStartCustom(context, endpointKind))
						.then(
							Commands
								.argument("phase_ticks", IntegerArgumentType.integer(0, INPUT_MAX_PHASE_TICKS))
								.executes(context -> executeInputStartCustom(context, endpointKind))
								.then(
									Commands
										.argument("total_ticks", IntegerArgumentType.integer(0, INPUT_MAX_TOTAL_TICKS))
										.executes(context -> executeInputStartCustom(context, endpointKind))
								)
						)
				)
			);
	}

	/**
	 * 启动方波输入 job。
	 */
	private static int executeInputStartSquare(CommandContext<CommandSourceStack> context, InputEndpointKind endpointKind) {
		CommandSourceStack source = context.getSource();
		List<Long> targetSerials = parseInputTargetSerials(
			source,
			SerialBatchArgumentType.getSerialBatch(context, "serials"),
			endpointKind
		);
		if (targetSerials == null) {
			return 0;
		}
		int periodTicks = IntegerArgumentType.getInteger(context, "period_ticks");
		int highTicks = CommandTreeSupport.getOptionalIntArg(context, "high_ticks", Math.max(1, periodTicks / 2));
		int highPower = CommandTreeSupport.getOptionalIntArg(context, "high_power", INPUT_DEFAULT_HIGH_POWER);
		int lowPower = CommandTreeSupport.getOptionalIntArg(context, "low_power", INPUT_DEFAULT_LOW_POWER);
		int phaseTicks = CommandTreeSupport.getOptionalIntArg(context, "phase_ticks", INPUT_DEFAULT_PHASE_TICKS);
		int totalTicks = CommandTreeSupport.getOptionalIntArg(context, "total_ticks", INPUT_DEFAULT_TOTAL_TICKS);
		int commandCost = CommandRateLimitService.computeBatchCost(2, targetSerials.size(), 64);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}
		InputWaveformSpec waveform = InputWaveformSpec.square(periodTicks, highTicks, highPower, lowPower, phaseTicks);
		return executeInputStart(source, endpointKind, targetSerials, waveform, totalTicks);
	}

	/**
	 * 启动自定义序列输入 job。
	 */
	private static int executeInputStartCustom(CommandContext<CommandSourceStack> context, InputEndpointKind endpointKind) {
		CommandSourceStack source = context.getSource();
		List<Long> targetSerials = parseInputTargetSerials(
			source,
			SerialBatchArgumentType.getSerialBatch(context, "serials"),
			endpointKind
		);
		if (targetSerials == null) {
			return 0;
		}
		String rawSequence = SignalSequenceArgumentType.getSignalSequence(context, "sequence");
		if (rawSequence != null && rawSequence.length() > INPUT_MAX_SEQUENCE_RAW_LENGTH) {
			source.sendFailure(Component.translatable("message.redstonelink.input.invalid_sequence", rawSequence));
			return 0;
		}
		List<Integer> sequence;
		try {
			sequence = InputWaveformSpec.parseCustomSequence(rawSequence);
		} catch (IllegalArgumentException ex) {
			source.sendFailure(Component.translatable("message.redstonelink.input.invalid_sequence", rawSequence));
			return 0;
		}
		int phaseTicks = CommandTreeSupport.getOptionalIntArg(context, "phase_ticks", INPUT_DEFAULT_PHASE_TICKS);
		int totalTicks = CommandTreeSupport.getOptionalIntArg(context, "total_ticks", INPUT_DEFAULT_TOTAL_TICKS);
		int commandCost = CommandRateLimitService.computeBatchCost(2, targetSerials.size() + sequence.size(), 64);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}
		InputWaveformSpec waveform = InputWaveformSpec.customSequence(sequence, phaseTicks);
		return executeInputStart(source, endpointKind, targetSerials, waveform, totalTicks);
	}

	/**
	 * 统一执行输入 job 启动。
	 */
	private static int executeInputStart(
		CommandSourceStack source,
		InputEndpointKind endpointKind,
		List<Long> targetSerials,
		InputWaveformSpec waveform,
		int totalTicks
	) {
		InputPlaybackService.StartJobResult startResult = InputPlaybackService.start(
			source.getServer(),
			new InputJobSpec(endpointKind, targetSerials, waveform, totalTicks)
		);
		if (!startResult.started()) {
			if (!startResult.offlineSerials().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.input.start.offline",
						CommandTreeSupport.typeCommandName(resolveInputTargetNodeType(endpointKind)),
						CommandTreeSupport.formatSerialCollection(startResult.offlineSerials())
					)
				);
				return 0;
			}
			if (!startResult.unsupportedSerials().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.input.start.unsupported",
						CommandTreeSupport.typeCommandName(resolveInputTargetNodeType(endpointKind)),
						CommandTreeSupport.formatSerialCollection(startResult.unsupportedSerials())
					)
				);
				return 0;
			}
			source.sendFailure(Component.translatable("message.redstonelink.input.start.unsupported", endpointKind.commandName(), "-"));
			return 0;
		}

		InputPlaybackService.JobInfo jobInfo = startResult.jobInfo();
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.input.start.done",
				jobInfo.jobId(),
				jobInfo.endpointKind().commandName(),
				jobInfo.waveformSummary(),
				jobInfo.targetCount(),
				jobInfo.totalTicks() <= 0 ? "-" : Integer.toString(jobInfo.totalTicks()),
				CommandTreeSupport.formatSerialList(jobInfo.targetSerials())
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 停止输入 job。
	 */
	private static int executeInputStop(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		long jobId = LongArgumentType.getLong(context, "job_id");
		if (!InputPlaybackService.stop(source.getServer(), jobId)) {
			source.sendFailure(Component.translatable("message.redstonelink.input.stop.not_found", jobId));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("message.redstonelink.input.stop.done", jobId), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 列出运行中的输入 job。
	 */
	private static int executeInputList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		List<InputPlaybackService.JobInfo> jobs = InputPlaybackService.listJobs(source.getServer());
		if (jobs.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("message.redstonelink.input.list.empty"), false);
			return Command.SINGLE_SUCCESS;
		}
		source.sendSuccess(() -> Component.translatable("message.redstonelink.input.list.header", jobs.size()), false);
		for (InputPlaybackService.JobInfo jobInfo : jobs) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.input.list.entry",
					jobInfo.jobId(),
					jobInfo.endpointKind().commandName(),
					jobInfo.waveformSummary(),
					jobInfo.targetCount(),
					jobInfo.totalTicks() <= 0 ? "-" : Integer.toString(jobInfo.totalTicks()),
					jobInfo.elapsedTicks(),
					CommandTreeSupport.formatSerialList(jobInfo.targetSerials())
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 清空所有运行中的输入 job。
	 */
	private static int executeInputClear(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		int cleared = InputPlaybackService.clear(source.getServer());
		source.sendSuccess(() -> Component.translatable("message.redstonelink.input.clear.done", cleared), false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析并校验输入 job 的目标序列号集合。
	 */
	private static List<Long> parseInputTargetSerials(
		CommandSourceStack source,
		String rawSerials,
		InputEndpointKind endpointKind
	) {
		var parseResult = com.makomi.util.SerialParseUtil.parseTargets(rawSerials, INPUT_MAX_TARGETS);
		if (!parseResult.invalidEntries().isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_tokens",
					String.join(", ", parseResult.invalidEntries())
				)
			);
			return null;
		}
		if (parseResult.exceedLimit()) {
			source.sendFailure(Component.translatable("message.redstonelink.input.too_many_targets", INPUT_MAX_TARGETS));
			return null;
		}
		if (parseResult.targets().isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.input.empty_targets"));
			return null;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.batch_serials_deduped",
					CommandTreeSupport.formatSerialCollection(parseResult.duplicateEntries())
				),
				false
			);
		}
		LinkNodeType targetType = resolveInputTargetNodeType(endpointKind);
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		List<Long> invalidSerials = new ArrayList<>();
		List<Long> sortedTargets = parseResult.targets().stream().sorted().toList();
		for (Long serial : sortedTargets) {
			if (serial == null || serial <= 0L) {
				continue;
			}
			boolean active = savedData.isSerialAllocated(targetType, serial) && !savedData.isSerialRetired(targetType, serial);
			if (!active) {
				invalidSerials.add(serial);
			}
		}
		if (!invalidSerials.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.input.invalid_serials",
					CommandTreeSupport.typeCommandName(targetType),
					CommandTreeSupport.formatSerialCollection(invalidSerials)
				)
			);
			return null;
		}
		return List.copyOf(sortedTargets);
	}

	/**
	 * 解析输入命令所面向的节点类型。
	 */
	private static LinkNodeType resolveInputTargetNodeType(InputEndpointKind endpointKind) {
		return endpointKind == InputEndpointKind.CORE_SYNC_DIRECT ? LinkNodeType.CORE : LinkNodeType.TRIGGER_SOURCE;
	}
}
