package com.makomi.command.bench;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.KeyValueTokenArgumentType;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeStateTraceService;
import com.makomi.data.input.InputWaveformSpec;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * bench/internal trace 批量分析命令注册器。
 * <p>
 * 该命令仅服务 benchmark 自动化流程，读取已挂载的 trace ring buffer，
 * 在服务端一次性完成大批量 `SYNC` 延迟匹配，避免 bench 侧逐 serial RCON 轮询。
 * </p>
 */
public final class BenchTraceCommandRegistry {
	private static final int TRACE_MAX_BATCH_SERIALS = 4096;

	private BenchTraceCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink bench trace` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("trace")
			.then(
				Commands
					.literal("sync_latency")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("serials", SerialBatchArgumentType.serialBatch()).then(
								Commands.argument("start_tick_spec", KeyValueTokenArgumentType.keyValueToken()).then(
									Commands
										.argument("powers_spec", KeyValueTokenArgumentType.keyValueToken())
										.executes(BenchTraceCommandRegistry::executeSyncLatencyCollect)
										.then(
											Commands
												.argument("latest_start_tick_spec", KeyValueTokenArgumentType.keyValueToken())
												.executes(BenchTraceCommandRegistry::executeSyncLatencyCollect)
										)
								)
							)
						)
					)
			);
	}

	/**
	 * 执行批量 SYNC 延迟收集。
	 */
	private static int executeSyncLatencyCollect(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType nodeType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (nodeType == null) {
			return 0;
		}
		List<Long> serials = parseTraceSerialBatch(source, SerialBatchArgumentType.getSerialBatch(context, "serials"));
		if (serials == null) {
			return 0;
		}
		Long expectedStartTick = parseNamedLongSpec(
			source,
			StringArgumentType.getString(context, "start_tick_spec"),
			"startTick"
		);
		if (expectedStartTick == null) {
			return 0;
		}
		List<Integer> expectedPowers = parseNamedPowerSequenceSpec(
			source,
			StringArgumentType.getString(context, "powers_spec"),
			"powers"
		);
		if (expectedPowers == null) {
			return 0;
		}
		Long latestExpectedStartTick = parseOptionalNamedLongSpec(
			context,
			source,
			"latest_start_tick_spec",
			"latestStartTick"
		);
		if (latestExpectedStartTick == null && hasArgument(context, "latest_start_tick_spec")) {
			return 0;
		}
		int commandCost = CommandRateLimitService.computeBatchCost(
			2,
			serials.size() + expectedPowers.size(),
			256
		);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}

		NodeStateTraceService.TraceLatencyBatchResult batchResult = NodeStateTraceService.analyzeTraceLatencyBatch(
			source.getServer(),
			nodeType,
			serials,
			expectedStartTick,
			expectedPowers,
			latestExpectedStartTick
		);
		source.sendSuccess(
			() -> Component.literal(
				String.format(
					java.util.Locale.ROOT,
					"[RedstoneLink/Bench] trace_sync_latency_summary type=%s requested=%d analyzed=%d mounted=%d matched=%d unmatched=%d expectedStartTick=%d expectedTickCount=%d latestExpectedStartTick=%s",
					CommandTreeSupport.typeCommandName(nodeType),
					batchResult.requestedCount(),
					batchResult.analyzedCount(),
					batchResult.mountedCount(),
					batchResult.matchedCount(),
					batchResult.unmatchedCount(),
					batchResult.expectedStartTick(),
					batchResult.expectedTickCount(),
					formatNullableLong(latestExpectedStartTick)
				)
			),
			false
		);
		for (NodeStateTraceService.TraceLatencySampleResult sampleResult : batchResult.sampleResults()) {
			source.sendSuccess(
				() -> Component.literal(
					String.format(
						java.util.Locale.ROOT,
						"[RedstoneLink/Bench] trace_sync_latency_item serial=%d matched=%s mounted=%s reason=%s actualStartTick=%s inputDelayTicks=%s mountTick=%d latestSampleTick=%d eligibleSamples=%d",
						sampleResult.serial(),
						Boolean.toString(sampleResult.matched()),
						Boolean.toString(sampleResult.mounted()),
						sampleResult.reason(),
						formatNullableLong(sampleResult.actualStartTick()),
						formatNullableLong(sampleResult.inputDelayTicks()),
						sampleResult.mountTick(),
						sampleResult.latestSampleTick(),
						sampleResult.eligibleSampleCount()
					)
				),
				false
			);
		}
		return batchResult.analyzedCount() > 0 ? Command.SINGLE_SUCCESS : 0;
	}

	/**
	 * 解析可选的 `key=value` 形式长整型参数。
	 */
	private static Long parseOptionalNamedLongSpec(
		CommandContext<CommandSourceStack> context,
		CommandSourceStack source,
		String argumentName,
		String expectedKey
	) {
		if (!hasArgument(context, argumentName)) {
			return null;
		}
		return parseNamedLongSpec(source, StringArgumentType.getString(context, argumentName), expectedKey);
	}

	/**
	 * 解析 `key=value` 形式的起始 tick。
	 */
	private static Long parseNamedLongSpec(CommandSourceStack source, String rawSpec, String expectedKey) {
		String valueText = parseNamedSpecPrefix(source, rawSpec, expectedKey);
		if (valueText == null) {
			return null;
		}
		try {
			return Long.parseLong(valueText);
		} catch (NumberFormatException exception) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid latency spec: expected " + expectedKey + "=<long>, got " + rawSpec
				)
			);
			return null;
		}
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
	 * 解析 `key=value` 形式的功率序列。
	 */
	private static List<Integer> parseNamedPowerSequenceSpec(
		CommandSourceStack source,
		String rawSpec,
		String expectedKey
	) {
		String valueText = parseNamedSpecPrefix(source, rawSpec, expectedKey);
		if (valueText == null) {
			return null;
		}
		try {
			return InputWaveformSpec.parseCustomSequence(valueText);
		} catch (IllegalArgumentException exception) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid latency spec: expected " + expectedKey + "=<sequence>, got " + rawSpec
				)
			);
			return null;
		}
	}

	/**
	 * 校验 `key=value` 前缀。
	 */
	private static String parseNamedSpecPrefix(CommandSourceStack source, String rawSpec, String expectedKey) {
		String prefix = expectedKey + "=";
		if (rawSpec != null && rawSpec.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return rawSpec.substring(prefix.length());
		}
		source.sendFailure(
			Component.literal(
				"[RedstoneLink/Bench] Invalid latency spec order: expected " + prefix + "..., got " + rawSpec
			)
		);
		return null;
	}

	/**
	 * 解析 bench/internal 批量 trace 序号。
	 */
	private static List<Long> parseTraceSerialBatch(CommandSourceStack source, String rawSerials) {
		SerialParseUtil.TargetParseResult parseResult = SerialParseUtil.parseTargets(rawSerials, TRACE_MAX_BATCH_SERIALS);
		if (!parseResult.invalidEntries().isEmpty()) {
			source.sendFailure(
				Component.translatable("message.redstonelink.invalid_target_tokens", String.join(", ", parseResult.invalidEntries()))
			);
			return null;
		}
		if (parseResult.exceedLimit()) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] Too many serials for trace latency batch."));
			return null;
		}
		if (parseResult.targets().isEmpty()) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] Trace latency batch serials cannot be empty."));
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
		List<Long> sortedSerials = new ArrayList<>(parseResult.targets());
		sortedSerials.sort(Long::compareTo);
		return List.copyOf(sortedSerials);
	}

	/**
	 * 统一格式化可空 long 字段，便于 PowerShell 解析。
	 */
	private static String formatNullableLong(Long value) {
		return value == null ? "-" : Long.toString(value);
	}
}
