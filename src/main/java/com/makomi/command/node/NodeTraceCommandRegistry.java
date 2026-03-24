package com.makomi.command.node;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeRuntimeProbe;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.NodeStateTraceService;
import com.makomi.util.ServerSerialValidationUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * `node trace` 命令注册器。
 * <p>
 * 负责节点运行态快照、采样挂载与历史读取命令。
 * </p>
 */
public final class NodeTraceCommandRegistry {
	private static final int TRACE_DEFAULT_EVERY = 1;
	private static final int TRACE_DEFAULT_CAPACITY = 128;
	private static final int TRACE_DEFAULT_READ_LIMIT = 10;
	private static final int TRACE_MAX_EVERY = 1200;
	private static final int TRACE_MAX_CAPACITY = 4096;
	private static final int TRACE_MAX_READ_LIMIT = 256;
	private static final int TRACE_MAX_BATCH_SERIALS = 1024;

	private NodeTraceCommandRegistry() {
	}

	/**
	 * 构建 `trace` 命令树根节点。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("trace")
			.requires(source -> RedstoneLinkConfig.command().nodeTraceEnabled() && CommandTreeSupport.hasOtherCommandPermission(source))
			.then(
				Commands
					.literal("mount")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("serials", SerialBatchArgumentType.serialBatch())
								.executes(NodeTraceCommandRegistry::executeNodeTraceMount)
								.then(
									Commands
										.argument("every", IntegerArgumentType.integer(1, TRACE_MAX_EVERY))
										.executes(NodeTraceCommandRegistry::executeNodeTraceMount)
										.then(
											Commands
												.argument("capacity", IntegerArgumentType.integer(1, TRACE_MAX_CAPACITY))
												.executes(NodeTraceCommandRegistry::executeNodeTraceMount)
										)
								)
						)
					)
			)
			.then(
				Commands
					.literal("latest")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("serials", SerialBatchArgumentType.serialBatch()).executes(NodeTraceCommandRegistry::executeNodeTraceLatest)
						)
					)
			)
			.then(
				Commands
					.literal("read")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("serial", LongArgumentType.longArg(1L))
								.executes(NodeTraceCommandRegistry::executeNodeTraceRead)
								.then(
									Commands
										.argument("limit", IntegerArgumentType.integer(1, TRACE_MAX_READ_LIMIT))
										.executes(NodeTraceCommandRegistry::executeNodeTraceRead)
								)
						)
					)
			)
			.then(
				Commands
					.literal("unmount")
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("serials", SerialBatchArgumentType.serialBatch()).executes(NodeTraceCommandRegistry::executeNodeTraceUnmount)
						)
					)
			)
			.then(Commands.literal("list").executes(NodeTraceCommandRegistry::executeNodeTraceList));
	}

	/**
	 * 挂载或更新节点历史采样器。
	 */
	private static int executeNodeTraceMount(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int everyTicks = CommandTreeSupport.getOptionalIntArg(context, "every", TRACE_DEFAULT_EVERY);
		int capacity = CommandTreeSupport.getOptionalIntArg(context, "capacity", TRACE_DEFAULT_CAPACITY);
		LinkNodeType nodeType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (nodeType == null) {
			return 0;
		}
		List<Long> serials = parseTraceSerialBatch(source, SerialBatchArgumentType.getSerialBatch(context, "serials"));
		if (serials == null) {
			return 0;
		}
		if (serials.size() == 1) {
			return executeNodeTraceMountSingle(source, nodeType, serials.get(0), everyTicks, capacity);
		}
		int commandCost = CommandRateLimitService.computeBatchCost(
			2,
			scaleTraceBatchCostItemCount(serials.size(), capacity),
			256
		);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}

		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		MinecraftServer server = source.getServer();
		List<Long> invalidSerials = new ArrayList<>();
		List<Long> unsupportedSerials = new ArrayList<>();
		List<NodeStateTraceService.MountResult> mountResults = new ArrayList<>();
		for (long serial : serials) {
			if (!isTraceSerialActive(savedData, nodeType, serial)) {
				invalidSerials.add(serial);
				continue;
			}
			NodeRuntimeProbe.TraceNodeKind traceKind = resolveTraceKindForMount(server, nodeType, serial).orElse(null);
			if (traceKind == null) {
				unsupportedSerials.add(serial);
				continue;
			}
			mountResults.add(NodeStateTraceService.mount(server, nodeType, serial, traceKind, everyTicks, capacity));
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.trace.batch.mount.header",
				CommandTreeSupport.typeCommandName(nodeType),
				serials.size(),
				mountResults.size(),
				invalidSerials.size() + unsupportedSerials.size()
			),
			false
		);
		for (NodeStateTraceService.MountResult mountResult : mountResults) {
			NodeStateTraceService.TraceMountInfo mountInfo = mountResult.mountInfo();
			String messageKey = mountResult.updated()
				? "message.redstonelink.node.trace.mount.updated"
				: "message.redstonelink.node.trace.mount";
			source.sendSuccess(
				() -> Component.translatable(
					messageKey,
					CommandTreeSupport.typeCommandName(mountInfo.nodeType()),
					mountInfo.serial(),
					mountInfo.traceKind().commandName(),
					mountInfo.everyTicks(),
					mountInfo.capacity(),
					mountInfo.sampleCount()
				),
				false
			);
			sendTraceSnapshotLine(source, mountResult.latestSnapshot());
		}
		sendTraceBatchInvalidSerials(source, nodeType, invalidSerials);
		sendTraceBatchUnsupportedSerials(source, nodeType, unsupportedSerials);
		return mountResults.isEmpty() ? 0 : Command.SINGLE_SUCCESS;
	}

	/**
	 * 读取节点当前最新状态。
	 */
	private static int executeNodeTraceLatest(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType nodeType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (nodeType == null) {
			return 0;
		}
		List<Long> serials = parseTraceSerialBatch(source, SerialBatchArgumentType.getSerialBatch(context, "serials"));
		if (serials == null) {
			return 0;
		}
		if (serials.size() == 1) {
			return executeNodeTraceLatestSingle(source, nodeType, serials.get(0));
		}
		int commandCost = CommandRateLimitService.computeBatchCost(1, serials.size(), 32);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		List<Long> invalidSerials = new ArrayList<>();
		List<Long> unsupportedSerials = new ArrayList<>();
		List<NodeRuntimeSnapshot> snapshots = new ArrayList<>();
		for (long serial : serials) {
			if (!isTraceSerialActive(savedData, nodeType, serial)) {
				invalidSerials.add(serial);
				continue;
			}
			NodeRuntimeSnapshot snapshot = resolveTraceSnapshot(source.getServer(), nodeType, serial).orElse(null);
			if (snapshot == null) {
				unsupportedSerials.add(serial);
				continue;
			}
			snapshots.add(snapshot);
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.trace.batch.latest.header",
				CommandTreeSupport.typeCommandName(nodeType),
				serials.size(),
				snapshots.size(),
				invalidSerials.size() + unsupportedSerials.size()
			),
			false
		);
		for (NodeRuntimeSnapshot snapshot : snapshots) {
			sendTraceSnapshotLine(source, snapshot);
		}
		sendTraceBatchInvalidSerials(source, nodeType, invalidSerials);
		sendTraceBatchUnsupportedSerials(source, nodeType, unsupportedSerials);
		return snapshots.isEmpty() ? 0 : Command.SINGLE_SUCCESS;
	}

	/**
	 * 读取节点最近的历史采样结果。
	 */
	private static int executeNodeTraceRead(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int limit = CommandTreeSupport.getOptionalIntArg(context, "limit", TRACE_DEFAULT_READ_LIMIT);
		int commandCost = CommandRateLimitService.computeBatchCost(1, limit, 32);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}
		LinkNodeType nodeType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (nodeType == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		List<NodeRuntimeSnapshot> samples = NodeStateTraceService.readSamples(source.getServer(), nodeType, serial, limit);
		if (samples.isEmpty()) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.node.trace.not_mounted",
				CommandTreeSupport.typeCommandName(nodeType),
				serial
			));
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.trace.read.header",
				CommandTreeSupport.typeCommandName(nodeType),
				serial,
				samples.size()
			),
			false
		);
		for (NodeRuntimeSnapshot sample : samples) {
			sendTraceSnapshotLine(source, sample);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 卸载节点历史采样器。
	 */
	private static int executeNodeTraceUnmount(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType nodeType = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (nodeType == null) {
			return 0;
		}
		List<Long> serials = parseTraceSerialBatch(source, SerialBatchArgumentType.getSerialBatch(context, "serials"));
		if (serials == null) {
			return 0;
		}
		if (serials.size() == 1) {
			return executeNodeTraceUnmountSingle(source, nodeType, serials.get(0));
		}
		int commandCost = CommandRateLimitService.computeBatchCost(1, serials.size(), 32);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}
		List<Long> unmountedSerials = new ArrayList<>();
		List<Long> notMountedSerials = new ArrayList<>();
		for (long serial : serials) {
			if (NodeStateTraceService.unmount(source.getServer(), nodeType, serial)) {
				unmountedSerials.add(serial);
				continue;
			}
			notMountedSerials.add(serial);
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.trace.batch.unmount.header",
				CommandTreeSupport.typeCommandName(nodeType),
				serials.size(),
				unmountedSerials.size(),
				notMountedSerials.size()
			),
			false
		);
		for (long serial : unmountedSerials) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.node.trace.unmount",
					CommandTreeSupport.typeCommandName(nodeType),
					serial
				),
				false
			);
		}
		sendTraceBatchNotMountedSerials(source, nodeType, notMountedSerials);
		return unmountedSerials.isEmpty() ? 0 : Command.SINGLE_SUCCESS;
	}

	/**
	 * 单节点挂载采样器。
	 */
	private static int executeNodeTraceMountSingle(
		CommandSourceStack source,
		LinkNodeType nodeType,
		long serial,
		int everyTicks,
		int capacity
	) {
		int commandCost = CommandRateLimitService.computeBatchCost(2, capacity, 256);
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, commandCost)) {
			return 0;
		}
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		if (!validateTraceSerialActive(source, savedData, nodeType, serial)) {
			return 0;
		}
		MinecraftServer server = source.getServer();
		NodeRuntimeProbe.TraceNodeKind traceKind = resolveTraceKindForMount(server, nodeType, serial).orElse(null);
		if (traceKind == null) {
			source.sendFailure(Component.translatable("message.redstonelink.node.trace.unsupported"));
			return 0;
		}
		NodeStateTraceService.MountResult mountResult = NodeStateTraceService.mount(
			server,
			nodeType,
			serial,
			traceKind,
			everyTicks,
			capacity
		);
		String messageKey = mountResult.updated()
			? "message.redstonelink.node.trace.mount.updated"
			: "message.redstonelink.node.trace.mount";
		source.sendSuccess(
			() -> Component.translatable(
				messageKey,
				CommandTreeSupport.typeCommandName(nodeType),
				serial,
				traceKind.commandName(),
				everyTicks,
				capacity,
				mountResult.mountInfo().sampleCount()
			),
			false
		);
		sendTraceSnapshotLine(source, mountResult.latestSnapshot());
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 单节点读取当前最新快照。
	 */
	private static int executeNodeTraceLatestSingle(CommandSourceStack source, LinkNodeType nodeType, long serial) {
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		if (!validateTraceSerialActive(source, savedData, nodeType, serial)) {
			return 0;
		}
		NodeRuntimeSnapshot snapshot = resolveTraceSnapshot(source.getServer(), nodeType, serial).orElse(null);
		if (snapshot == null) {
			source.sendFailure(Component.translatable("message.redstonelink.node.trace.unsupported"));
			return 0;
		}
		sendTraceSnapshotLine(source, snapshot);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 单节点卸载采样器。
	 */
	private static int executeNodeTraceUnmountSingle(CommandSourceStack source, LinkNodeType nodeType, long serial) {
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		if (!NodeStateTraceService.unmount(source.getServer(), nodeType, serial)) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.node.trace.not_mounted",
				CommandTreeSupport.typeCommandName(nodeType),
				serial
			));
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.trace.unmount",
				CommandTreeSupport.typeCommandName(nodeType),
				serial
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 列出当前服务器所有已挂载采样器。
	 */
	private static int executeNodeTraceList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		List<NodeStateTraceService.TraceMountInfo> mountInfos = NodeStateTraceService.listMounts(source.getServer());
		if (mountInfos.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("message.redstonelink.node.trace.list.empty"), false);
			return Command.SINGLE_SUCCESS;
		}
		source.sendSuccess(
			() -> Component.translatable("message.redstonelink.node.trace.list.header", mountInfos.size()),
			false
		);
		for (NodeStateTraceService.TraceMountInfo mountInfo : mountInfos) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.node.trace.list.entry",
					CommandTreeSupport.typeCommandName(mountInfo.nodeType()),
					mountInfo.serial(),
					mountInfo.traceKind().commandName(),
					mountInfo.everyTicks(),
					mountInfo.capacity(),
					mountInfo.sampleCount(),
					mountInfo.lastSampleTick() < 0L ? "-" : Long.toString(mountInfo.lastSampleTick())
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析 trace 命令当前可用的节点快照。
	 */
	private static Optional<NodeRuntimeSnapshot> resolveTraceSnapshot(
		MinecraftServer server,
		LinkNodeType nodeType,
		long serial
	) {
		Optional<NodeRuntimeProbe.ProbeResolution> currentResolution = NodeRuntimeProbe.resolveCurrent(server, nodeType, serial);
		if (currentResolution.isPresent()) {
			return currentResolution.map(NodeRuntimeProbe.ProbeResolution::snapshot);
		}
		Optional<NodeStateTraceService.TraceMountInfo> mountInfo = NodeStateTraceService.getMountInfo(server, nodeType, serial);
		if (mountInfo.isPresent()) {
			return Optional.of(NodeRuntimeProbe.snapshot(server, nodeType, serial, mountInfo.get().traceKind()));
		}
		return Optional.empty();
	}

	/**
	 * 解析挂载时应使用的探针类型。
	 */
	private static Optional<NodeRuntimeProbe.TraceNodeKind> resolveTraceKindForMount(
		MinecraftServer server,
		LinkNodeType nodeType,
		long serial
	) {
		Optional<NodeStateTraceService.TraceMountInfo> mountInfo = NodeStateTraceService.getMountInfo(server, nodeType, serial);
		if (mountInfo.isPresent()) {
			return Optional.of(mountInfo.get().traceKind());
		}
		return NodeRuntimeProbe.resolveCurrent(server, nodeType, serial).map(NodeRuntimeProbe.ProbeResolution::traceKind);
	}

	/**
	 * 校验 trace 命令序列号处于已分配且未退役状态。
	 */
	private static boolean validateTraceSerialActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial
	) {
		return nodeType == LinkNodeType.TRIGGER_SOURCE
			? ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, nodeType, serial)
			: ServerSerialValidationUtil.validateTargetSerialActive(source, savedData, nodeType, serial);
	}

	/**
	 * 判断节点序号是否已分配且未退役，供批量 trace 命令做静默过滤。
	 */
	private static boolean isTraceSerialActive(LinkSavedData savedData, LinkNodeType nodeType, long serial) {
		return savedData != null && nodeType != null && savedData.isSerialActive(nodeType, serial);
	}

	/**
	 * 解析批量 trace 序号。
	 */
	private static List<Long> parseTraceSerialBatch(CommandSourceStack source, String rawSerials) {
		var parseResult = com.makomi.util.SerialParseUtil.parseTargets(rawSerials, TRACE_MAX_BATCH_SERIALS);
		if (!parseResult.invalidEntries().isEmpty()) {
			source.sendFailure(
				Component.translatable("message.redstonelink.invalid_target_tokens", String.join(", ", parseResult.invalidEntries()))
			);
			return null;
		}
		if (parseResult.exceedLimit()) {
			source.sendFailure(Component.translatable("message.redstonelink.node.trace.too_many_serials", TRACE_MAX_BATCH_SERIALS));
			return null;
		}
		if (parseResult.targets().isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.node.trace.empty_serials"));
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
	 * 输出批量 trace 的无效序号汇总。
	 */
	private static void sendTraceBatchInvalidSerials(
		CommandSourceStack source,
		LinkNodeType nodeType,
		List<Long> invalidSerials
	) {
		if (source == null || invalidSerials == null || invalidSerials.isEmpty()) {
			return;
		}
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.node.trace.batch.invalid_serials",
				CommandTreeSupport.typeCommandName(nodeType),
				CommandTreeSupport.formatSerialCollection(invalidSerials)
			)
		);
	}

	/**
	 * 输出批量 trace 的不支持节点汇总。
	 */
	private static void sendTraceBatchUnsupportedSerials(
		CommandSourceStack source,
		LinkNodeType nodeType,
		List<Long> unsupportedSerials
	) {
		if (source == null || unsupportedSerials == null || unsupportedSerials.isEmpty()) {
			return;
		}
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.node.trace.batch.unsupported",
				CommandTreeSupport.typeCommandName(nodeType),
				CommandTreeSupport.formatSerialCollection(unsupportedSerials)
			)
		);
	}

	/**
	 * 输出批量卸载未命中的序号汇总。
	 */
	private static void sendTraceBatchNotMountedSerials(
		CommandSourceStack source,
		LinkNodeType nodeType,
		List<Long> notMountedSerials
	) {
		if (source == null || notMountedSerials == null || notMountedSerials.isEmpty()) {
			return;
		}
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.node.trace.batch.not_mounted",
				CommandTreeSupport.typeCommandName(nodeType),
				CommandTreeSupport.formatSerialCollection(notMountedSerials)
			)
		);
	}

	/**
	 * 计算批量 trace 命令的限流目标规模，避免序号数与容量相乘时溢出。
	 */
	private static int scaleTraceBatchCostItemCount(int serialCount, int scale) {
		long safeSerialCount = Math.max(1L, serialCount);
		long safeScale = Math.max(1L, scale);
		return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, safeSerialCount * safeScale));
	}

	/**
	 * 输出单条 trace 快照。
	 */
	private static void sendTraceSnapshotLine(CommandSourceStack source, NodeRuntimeSnapshot snapshot) {
		if (source == null || snapshot == null) {
			return;
		}
		String dimensionText = snapshot.dimension() == null ? "-" : snapshot.dimension().location().toString();
		String posText = snapshot.pos() == null ? "-" : CommandTreeSupport.formatBlockPos(snapshot.pos());
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.trace.sample",
				CommandTreeSupport.typeCommandName(snapshot.nodeType()),
				snapshot.serial(),
				snapshot.traceKind().commandName(),
				snapshot.sampleTick(),
				snapshot.sampleSlot(),
				Boolean.toString(snapshot.online()),
				Boolean.toString(snapshot.active()),
				snapshot.inputPower(),
				snapshot.outputPower(),
				snapshot.configuredMode(),
				snapshot.effectiveMode(),
				snapshot.resolvedStrength(),
				snapshot.lastObservedInputPower(),
				snapshot.lastDispatchedPower(),
				CommandTreeSupport.formatSerialList(snapshot.maxSourceSerials()),
				dimensionText,
				posText
			),
			false
		);
	}
}
