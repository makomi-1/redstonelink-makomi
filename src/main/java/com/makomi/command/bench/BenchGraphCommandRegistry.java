package com.makomi.command.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.GraphSnapshotBundle;
import com.makomi.data.GraphSnapshotExportService;
import com.makomi.data.GraphWriteService;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.WebFeaturePermissionService;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * bench/internal graph 回归命令注册器。
 * <p>
 * 该入口只覆盖 serial 主链路：
 * </p>
 * <ul>
 * <li>导出当前可见 graph；</li>
 * <li>以 `triggerSource -> core` 覆盖写入构造 preview/save 请求；</li>
 * <li>输出 machine-readable summary 供 functional case 稳定断言。</li>
 * </ul>
 */
public final class BenchGraphCommandRegistry {
	private BenchGraphCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink bench graph` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("graph")
			.then(
				Commands
					.literal("export")
					.executes(context -> executeExport(context, false))
					.then(Commands.literal("force").executes(context -> executeExport(context, true)))
			)
			.then(
				Commands
					.literal("preview")
					.then(createReplaceTargetsBranch(BenchGraphCommandRegistry::executePreviewReplaceTargets))
			)
			.then(
				Commands
					.literal("save")
					.then(createReplaceTargetsBranch(BenchGraphCommandRegistry::executeSaveReplaceTargets))
			);
	}

	/**
	 * 复用一份 `triggerSource -> core` 覆盖写入参数树，避免 preview/save 分叉维护。
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> createReplaceTargetsBranch(
		Command<CommandSourceStack> command
	) {
		return Commands
			.literal("triggerSource")
			.then(
				Commands.argument("serial", LongArgumentType.longArg(1L)).then(
					Commands
						.literal("core")
						.then(Commands.argument("target_serials", SerialBatchArgumentType.serialBatch()).executes(command))
				)
			);
	}

	/**
	 * 导出当前玩家可见 graph，并输出稳定 summary。
	 */
	private static int executeExport(CommandContext<CommandSourceStack> context, boolean forceTransfer) {
		CommandSourceStack source = context.getSource();
		if (!WebFeaturePermissionService.canUseGraphFeature(source)) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] graph_export outcome=rejected reason=permission_denied"));
			return 0;
		}
		try {
			GraphSnapshotExportService.ExportBundle exportBundle = GraphSnapshotExportService.exportVisibleSerialGraph(source, forceTransfer);
			String summary = buildExportSummary(
				exportBundle.bundle(),
				exportBundle.fileName(),
				exportBundle.reusedExisting()
			);
			source.sendSuccess(() -> Component.literal(summary), false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException | RuntimeException exception) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] graph_export outcome=error reason=exception"));
			return 0;
		}
	}

	/**
	 * 执行 graph preview 主链路。
	 */
	private static int executePreviewReplaceTargets(CommandContext<CommandSourceStack> context) {
		return executeReplaceTargetsWrite(context, false);
	}

	/**
	 * 执行 graph save 主链路。
	 */
	private static int executeSaveReplaceTargets(CommandContext<CommandSourceStack> context) {
		return executeReplaceTargetsWrite(context, true);
	}

	/**
	 * 统一处理 `triggerSource -> core` 的 preview/save。
	 */
	private static int executeReplaceTargetsWrite(CommandContext<CommandSourceStack> context, boolean applySave) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();

		long triggerSourceSerial = LongArgumentType.getLong(context, "serial");
		String rawTargetSerials = SerialBatchArgumentType.getSerialBatch(context, "target_serials");
		List<Long> targetCoreSerials = parseTargetCoreSerials(source, rawTargetSerials);
		if (targetCoreSerials == null) {
			return 0;
		}

		String requestJson = buildReplaceTargetsRequestJson(source, player, triggerSourceSerial, targetCoreSerials);
		String responseJson = applySave
			? GraphWriteService.submit(source, requestJson)
			: GraphWriteService.preview(source, requestJson);
		String action = applySave ? "graph_save" : "graph_preview";
		String summary = buildWriteSummary(action, triggerSourceSerial, targetCoreSerials.size(), responseJson);
		String outcome = resolveWriteOutcome(responseJson);
		if ("applied".equals(outcome) || "preview".equals(outcome) || "conflict".equals(outcome)) {
			source.sendSuccess(() -> Component.literal(summary), false);
			return Command.SINGLE_SUCCESS;
		}
		source.sendFailure(Component.literal(summary));
		return 0;
	}

	/**
	 * 解析目标 core 序号批次，并在 bench 侧先拦截格式错误与超限。
	 */
	private static List<Long> parseTargetCoreSerials(CommandSourceStack source, String rawTargetSerials) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			rawTargetSerials,
			RedstoneLinkConfig.general().maxTargetsPerSetLinks()
		);
		if (parseResult.exceedLimit()) {
			source.sendFailure(
				Component.literal("[RedstoneLink/Bench] graph_write_parse outcome=rejected reason=target_limit_exceeded")
			);
			return null;
		}
		if (!parseResult.invalidEntries().isEmpty()) {
			source.sendFailure(
				Component.literal("[RedstoneLink/Bench] graph_write_parse outcome=rejected reason=invalid_target_serials")
			);
			return null;
		}
		return parseResult.orderedTargets();
	}

	/**
	 * 以当前真值快照为基线，构造一份最小 serial graph 写入请求。
	 */
	private static String buildReplaceTargetsRequestJson(
		CommandSourceStack source,
		ServerPlayer player,
		long triggerSourceSerial,
		List<Long> targetCoreSerials
	) {
		GraphSnapshotBundle snapshotBundle = player == null
			? GraphSnapshotExportService.buildVisibleSerialGraph(source)
			: GraphSnapshotExportService.buildVisibleSerialGraph(player);
		CommandSourceStack effectiveSource = source != null ? source : (player == null ? null : player.createCommandSourceStack());
		LinkSavedData savedData = LinkSavedData.get(effectiveSource.getLevel());
		JsonObject root = new JsonObject();
		root.addProperty("draftId", "bench-graph-draft");
		root.addProperty("baseSnapshotId", snapshotBundle.snapshotId());
		root.addProperty("mode", "serial");
		root.addProperty("baseGraphRevision", snapshotBundle.graphRevision());
		JsonArray operations = new JsonArray();
		JsonObject replaceOperation = new JsonObject();
		replaceOperation.addProperty("type", "ReplaceTriggerSourceTargets");
		replaceOperation.addProperty("triggerSourceSerial", triggerSourceSerial);
		replaceOperation.addProperty(
			"expectedSourceRevision",
			savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)
		);
		JsonArray targetArray = new JsonArray();
		for (Long targetCoreSerialValue : targetCoreSerials) {
			long targetCoreSerial = targetCoreSerialValue == null ? 0L : targetCoreSerialValue;
			if (targetCoreSerial > 0L) {
				targetArray.add(targetCoreSerial);
			}
		}
		replaceOperation.add("targetCoreSerials", targetArray);
		operations.add(replaceOperation);
		root.add("operations", operations);
		return root.toString();
	}

	/**
	 * 构造 graph 导出 summary。
	 */
	static String buildExportSummary(GraphSnapshotBundle bundle, String fileName, boolean reusedExisting) {
		GraphSnapshotBundle graphSnapshotBundle = bundle == null
			? new GraphSnapshotBundle("graph-empty", "serial", 0L, 0L, "unknown", "empty", List.of(), List.of(), null)
			: bundle;
		GraphSnapshotBundle.GraphStats stats = graphSnapshotBundle.stats();
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] graph_export outcome=%s mode=%s graphRevision=%d snapshotId=%s nodeCount=%d edgeCount=%d triggerSourceCount=%d coreCount=%d maskedSourceCount=%d reusedExisting=%s fileName=%s checksum=%s",
			reusedExisting ? "reused" : "exported",
			graphSnapshotBundle.mode(),
			graphSnapshotBundle.graphRevision(),
			graphSnapshotBundle.snapshotId(),
			stats.nodeCount(),
			stats.edgeCount(),
			stats.triggerSourceCount(),
			stats.coreCount(),
			stats.maskedSourceCount(),
			reusedExisting,
			fileName == null ? "-" : fileName,
			graphSnapshotBundle.structureChecksum()
		);
	}

	/**
	 * 构造 graph preview/save summary。
	 */
	static String buildWriteSummary(
		String action,
		long triggerSourceSerial,
		int requestedTargetCount,
		String responseJson
	) {
		ParsedWriteResponse parsedWriteResponse = parseWriteResponse(responseJson);
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] %s outcome=%s sourceType=triggerSource sourceSerial=%d requestedTargetCount=%d status=%s result=%s reason=%s graphRevision=%d updatedNodeCount=%d previewCanSave=%s previewGraphCost=%d previewGraphWriteUnitCount=%d",
			action,
			resolveWriteOutcome(responseJson),
			triggerSourceSerial,
			Math.max(0, requestedTargetCount),
			parsedWriteResponse.status(),
			parsedWriteResponse.result(),
			parsedWriteResponse.reason(),
			parsedWriteResponse.graphRevision(),
			parsedWriteResponse.updatedNodeCount(),
			parsedWriteResponse.previewCanSave(),
			parsedWriteResponse.previewGraphCost(),
			parsedWriteResponse.previewGraphWriteUnitCount()
		);
	}

	/**
	 * 解析 graph 写入返回体的统一 outcome。
	 */
	static String resolveWriteOutcome(String responseJson) {
		ParsedWriteResponse parsedWriteResponse = parseWriteResponse(responseJson);
		if ("error".equals(parsedWriteResponse.status())) {
			return "error";
		}
		if ("applied".equals(parsedWriteResponse.result())) {
			return "applied";
		}
		if ("preview".equals(parsedWriteResponse.result())) {
			return "preview";
		}
		if ("conflict".equals(parsedWriteResponse.result())) {
			return "conflict";
		}
		if ("rejected".equals(parsedWriteResponse.result())) {
			return "rejected";
		}
		return "unknown";
	}

	/**
	 * 解析 graph 写入 JSON，提取 functional case 需要的稳定字段。
	 */
	private static ParsedWriteResponse parseWriteResponse(String responseJson) {
		try {
			JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
			JsonArray updatedNodes = root.has("updatedNodes") && root.get("updatedNodes").isJsonArray()
				? root.getAsJsonArray("updatedNodes")
				: new JsonArray();
			int changedNodeCount = root.has("changedNodeCount") ? (int) readLong(root, "changedNodeCount") : updatedNodes.size();
			JsonObject preview = root.has("preview") && root.get("preview").isJsonObject()
				? root.getAsJsonObject("preview")
				: null;
			return new ParsedWriteResponse(
				readString(root, "status", "error"),
				readString(root, "result", ""),
				readString(root, "reason", ""),
				readLong(root, "graphRevision"),
				changedNodeCount,
				preview == null ? "-" : Boolean.toString(readBoolean(preview, "canSave")),
				preview == null ? 0 : (int) readLong(preview, "graphCost"),
				preview == null ? 0 : (int) readLong(preview, "graphWriteUnitCount")
			);
		} catch (RuntimeException exception) {
			return new ParsedWriteResponse("error", "", "invalid_json", 0L, 0, "-", 0, 0);
		}
	}

	/**
	 * 读取字符串字段。
	 */
	private static String readString(JsonObject object, String memberName, String fallback) {
		if (object == null || memberName == null || memberName.isBlank() || !object.has(memberName)) {
			return fallback;
		}
		JsonElement element = object.get(memberName);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}
		try {
			String value = element.getAsString();
			return value == null || value.isBlank() ? fallback : value.trim();
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	/**
	 * 读取 long 字段。
	 */
	private static long readLong(JsonObject object, String memberName) {
		if (object == null || memberName == null || memberName.isBlank() || !object.has(memberName)) {
			return 0L;
		}
		JsonElement element = object.get(memberName);
		if (element == null || !element.isJsonPrimitive()) {
			return 0L;
		}
		try {
			return Math.max(0L, element.getAsLong());
		} catch (RuntimeException exception) {
			return 0L;
		}
	}

	/**
	 * 读取布尔字段。
	 */
	private static boolean readBoolean(JsonObject object, String memberName) {
		if (object == null || memberName == null || memberName.isBlank() || !object.has(memberName)) {
			return false;
		}
		JsonElement element = object.get(memberName);
		if (element == null || !element.isJsonPrimitive()) {
			return false;
		}
		try {
			return element.getAsBoolean();
		} catch (RuntimeException exception) {
			return false;
		}
	}

	/**
	 * graph 写入返回体的最小稳定摘要。
	 */
	private record ParsedWriteResponse(
		String status,
		String result,
		String reason,
		long graphRevision,
		int updatedNodeCount,
		String previewCanSave,
		int previewGraphCost,
		int previewGraphWriteUnitCount
	) {
	}
}
