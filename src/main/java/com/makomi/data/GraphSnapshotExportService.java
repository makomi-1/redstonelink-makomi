package com.makomi.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 图快照导出服务。
 * <p>
 * 当前仅导出当前玩家可读的 `serial` 视图，用于网页端只读分析器。
 * </p>
 */
public final class GraphSnapshotExportService {
	private static final String MODE_SERIAL = "serial";
	private static final Map<UUID, CachedGraphExport> EXPORT_CACHE = new ConcurrentHashMap<>();

	private GraphSnapshotExportService() {
	}

	/**
	 * 导出当前玩家可见的 serial 图快照，并附带压缩字节与文件名。
	 */
	public static ExportBundle exportVisibleSerialGraph(ServerPlayer player, boolean forceTransfer) throws IOException {
		ViewerContext viewerContext = ViewerContext.fromPlayer(player);
		return exportVisibleSerialGraph(viewerContext, forceTransfer);
	}

	/**
	 * 导出当前命令源可见的 serial 图快照，并附带压缩字节与文件名。
	 * <p>
	 * benchmark mode 下允许控制台 / RCON 直接调用，因此这里不能再强依赖玩家实体。
	 * </p>
	 */
	public static ExportBundle exportVisibleSerialGraph(CommandSourceStack source, boolean forceTransfer) throws IOException {
		ViewerContext viewerContext = ViewerContext.fromSource(source);
		return exportVisibleSerialGraph(viewerContext, forceTransfer);
	}

	private static ExportBundle exportVisibleSerialGraph(ViewerContext viewerContext, boolean forceTransfer) throws IOException {
		GraphSnapshotBundle bundle = buildVisibleSerialGraph(viewerContext);
		String fileName = GraphSnapshotJsonSupport.buildFileName(bundle);
		ServerLevel level = viewerContext.level();
		UUID actorId = viewerContext.dedupeActorId();
		if (level != null && actorId != null) {
			if (!forceTransfer) {
				CachedGraphExport cachedGraphExport = EXPORT_CACHE.get(actorId);
				if (cachedGraphExport != null && cachedGraphExport.matches(fileName)) {
					return cachedGraphExport.toReusedBundle(bundle);
				}
				GraphExportDedupeSavedData dedupeSavedData = GraphExportDedupeSavedData.get(level);
				if (dedupeSavedData.contains(actorId, bundle.structureChecksum(), fileName)) {
					return new ExportBundle(bundle, fileName, new byte[0], true);
				}
			}
		}
		ExportBundle exportBundle = new ExportBundle(bundle, fileName, GraphSnapshotJsonSupport.toCompressedJsonBytes(bundle), false);
		if (level != null && actorId != null) {
			EXPORT_CACHE.put(actorId, new CachedGraphExport(fileName, exportBundle));
			GraphExportDedupeSavedData.get(level).remember(actorId, bundle.structureChecksum(), fileName);
		}
		return exportBundle;
	}

	/**
	 * 构建当前玩家可见的 serial 图快照。
	 */
	public static GraphSnapshotBundle buildVisibleSerialGraph(ServerPlayer player) {
		return buildVisibleSerialGraph(ViewerContext.fromPlayer(player));
	}

	/**
	 * 构建当前命令源可见的 serial 图快照。
	 */
	public static GraphSnapshotBundle buildVisibleSerialGraph(CommandSourceStack source) {
		return buildVisibleSerialGraph(ViewerContext.fromSource(source));
	}

	private static GraphSnapshotBundle buildVisibleSerialGraph(ViewerContext viewerContext) {
		ServerLevel level = viewerContext.level();
		if (level == null) {
			return emptyBundle();
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		long generatedAtTick = level.getGameTime();
		long graphRevision = savedData.graphRevision();
		Map<String, GraphSnapshotBundle.GraphNodeInfo> nodesByKey = new LinkedHashMap<>();
		Set<GraphSnapshotBundle.GraphEdgeInfo> edgeSet = new LinkedHashSet<>();
		int maskedSourceCount = 0;
		boolean hasViewPermission = viewerContext.hasViewPermission();

		for (long serial : sortedSerials(savedData.getActiveSerials(LinkNodeType.TRIGGER_SOURCE))) {
			if (!CurrentLinksPrivacyService.canReadNodeState(level, LinkNodeType.TRIGGER_SOURCE, serial, hasViewPermission)) {
				continue;
			}
			GraphSnapshotBundle.GraphNodeInfo sourceNode = buildNodeInfo(level, savedData, LinkNodeType.TRIGGER_SOURCE, serial);
			nodesByKey.put(sourceNode.nodeKey(), sourceNode);

			NodeLinksSnapshot linksSnapshot = NodeSnapshotQueryService.queryLinks(
				level,
				LinkNodeType.TRIGGER_SOURCE,
				serial,
				hasViewPermission
			);
			if (linksSnapshot.masked()) {
				maskedSourceCount++;
			}
			for (Long targetSerialValue : linksSnapshot.visibleTargets()) {
				long targetSerial = targetSerialValue == null ? 0L : targetSerialValue;
				if (targetSerial <= 0L) {
					continue;
				}
				if (!CurrentLinksPrivacyService.canReadNodeState(level, LinkNodeType.CORE, targetSerial, hasViewPermission)) {
					continue;
				}
				GraphSnapshotBundle.GraphNodeInfo targetNode = nodesByKey.computeIfAbsent(
					nodeKey(LinkNodeType.CORE, targetSerial),
					ignored -> buildNodeInfo(level, savedData, LinkNodeType.CORE, targetSerial)
				);
				edgeSet.add(
					new GraphSnapshotBundle.GraphEdgeInfo(
						sourceNode.nodeKey() + "->" + targetNode.nodeKey(),
						sourceNode.nodeKey(),
						targetNode.nodeKey(),
						MODE_SERIAL,
						true,
						false
					)
				);
			}
		}

		for (long serial : sortedSerials(savedData.getActiveSerials(LinkNodeType.CORE))) {
			if (!CurrentLinksPrivacyService.canReadNodeState(level, LinkNodeType.CORE, serial, hasViewPermission)) {
				continue;
			}
			nodesByKey.computeIfAbsent(nodeKey(LinkNodeType.CORE, serial), ignored -> buildNodeInfo(level, savedData, LinkNodeType.CORE, serial));
		}

		List<GraphSnapshotBundle.GraphNodeInfo> nodes = new ArrayList<>(nodesByKey.values());
		nodes.sort(
			java.util.Comparator
				.comparing((GraphSnapshotBundle.GraphNodeInfo node) -> LinkNodeSemantics.toSemanticName(node.nodeType()))
				.thenComparingLong(GraphSnapshotBundle.GraphNodeInfo::serial)
		);
		List<GraphSnapshotBundle.GraphEdgeInfo> edges = new ArrayList<>(edgeSet);
		edges.sort(
			java.util.Comparator
				.comparing(GraphSnapshotBundle.GraphEdgeInfo::sourceNodeKey)
				.thenComparing(GraphSnapshotBundle.GraphEdgeInfo::targetNodeKey)
		);

		int triggerSourceCount = 0;
		int coreCount = 0;
		for (GraphSnapshotBundle.GraphNodeInfo node : nodes) {
			if (node.nodeType() == LinkNodeType.TRIGGER_SOURCE) {
				triggerSourceCount++;
			} else {
				coreCount++;
			}
		}
		GraphSnapshotBundle.GraphStats stats = new GraphSnapshotBundle.GraphStats(
			nodes.size(),
			edges.size(),
			triggerSourceCount,
			coreCount,
			maskedSourceCount
		);
		String viewerPlayerId = viewerContext.viewerId();
		GraphSnapshotBundle provisionalBundle = new GraphSnapshotBundle(
			"graph-pending",
			MODE_SERIAL,
			graphRevision,
			generatedAtTick,
			viewerPlayerId,
			"",
			nodes,
			edges,
			stats
		);
		String structureChecksum = GraphSnapshotJsonSupport.buildStructureChecksum(provisionalBundle);
		return new GraphSnapshotBundle(
			buildSnapshotId(graphRevision, structureChecksum),
			MODE_SERIAL,
			graphRevision,
			generatedAtTick,
			viewerPlayerId,
			structureChecksum,
			nodes,
			edges,
			stats
		);
	}

	private static GraphSnapshotBundle emptyBundle() {
		return new GraphSnapshotBundle("graph-empty", MODE_SERIAL, 0L, 0L, "unknown", "empty", List.of(), List.of(), null);
	}

	/**
	 * 组装单节点图快照信息。
	 */
	private static GraphSnapshotBundle.GraphNodeInfo buildNodeInfo(
		ServerLevel level,
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial
	) {
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, nodeType, serial);
		String alias = NodeAliasServerSupport.resolveAlias(level, nodeType, serial).orElse("");
		LinkConnectionMode connectionMode = savedData.getConnectionMode(nodeType, serial);
		return new GraphSnapshotBundle.GraphNodeInfo(
			nodeKey(nodeType, serial),
			nodeType,
			serial,
			alias,
			NodeAliasDisplayUtil.formatDisplayText(alias, serial),
			identity.allocated(),
			identity.retired(),
			connectionMode.token(),
			savedData.getChannel(nodeType, serial),
			savedData.sourceRevision(nodeType, serial),
			nodeType == LinkNodeType.CORE ? savedData.coreRevision(serial) : 0L,
			resolveCapabilityFlags(savedData, nodeType, serial, connectionMode)
		);
	}

	private static List<String> resolveCapabilityFlags(
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial,
		LinkConnectionMode connectionMode
	) {
		List<String> capabilityFlags = new ArrayList<>(4);
		capabilityFlags.add(nodeType == LinkNodeType.TRIGGER_SOURCE ? "outbound" : "inbound");
		capabilityFlags.add("readonly");
		if (savedData != null && savedData.isRepeaterSerial(serial)) {
			capabilityFlags.add("repeater");
		}
		if (connectionMode == LinkConnectionMode.CHANNEL) {
			capabilityFlags.add("channel");
		}
		return List.copyOf(capabilityFlags);
	}

	private static List<Long> sortedSerials(Set<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		return serials.stream().filter(serial -> serial != null && serial > 0L).sorted().toList();
	}

	private static String nodeKey(LinkNodeType nodeType, long serial) {
		return LinkNodeSemantics.toSemanticName(nodeType) + ":" + Math.max(0L, serial);
	}

	private static String buildSnapshotId(long graphRevision, String structureChecksum) {
		String normalizedChecksum = structureChecksum == null ? "graph" : structureChecksum.trim();
		String suffix = normalizedChecksum.length() <= 12 ? normalizedChecksum : normalizedChecksum.substring(0, 12);
		return "serial-r%d-%s".formatted(Math.max(0L, graphRevision), suffix);
	}

	/**
	 * 图快照导出结果。
	 */
	public record ExportBundle(GraphSnapshotBundle bundle, String fileName, byte[] compressedBytes, boolean reusedExisting) {
		public ExportBundle(GraphSnapshotBundle bundle, String fileName, byte[] compressedBytes) {
			this(bundle, fileName, compressedBytes, false);
		}

		public ExportBundle {
			bundle = bundle == null ? emptyBundle() : bundle;
			fileName = fileName == null ? GraphSnapshotJsonSupport.buildFileName(bundle) : fileName;
			compressedBytes = compressedBytes == null ? new byte[0] : compressedBytes.clone();
		}

		@Override
		public byte[] compressedBytes() {
			return compressedBytes.clone();
		}
	}

	/**
	 * 当前玩家的 graph 导出缓存。
	 */
	private record CachedGraphExport(String fileName, ExportBundle exportBundle) {
		private CachedGraphExport {
			fileName = fileName == null ? GraphSnapshotJsonSupport.buildFileName(emptyBundle()) : fileName;
			exportBundle = exportBundle == null
				? new ExportBundle(emptyBundle(), GraphSnapshotJsonSupport.buildFileName(emptyBundle()), new byte[0], false)
				: exportBundle;
		}

		private boolean matches(String expectedFileName) {
			return fileName.equals(expectedFileName);
		}

		private ExportBundle toReusedBundle(GraphSnapshotBundle bundle) {
			return new ExportBundle(
				bundle == null ? exportBundle.bundle() : bundle,
				fileName,
				new byte[0],
				true
			);
		}
	}

	/**
	 * graph 可见性与 dedupe 所需的最小查看者上下文。
	 */
	private record ViewerContext(ServerLevel level, boolean hasViewPermission, String viewerId, UUID dedupeActorId) {
		private static ViewerContext fromPlayer(ServerPlayer player) {
			if (player == null) {
				return new ViewerContext(null, false, "unknown", null);
			}
			return new ViewerContext(
				player.level(),
				com.makomi.command.CommandPermissionCompat.hasPermission(player, com.makomi.config.RedstoneLinkConfig.privacy().viewPermissionLevel()),
				player.getUUID().toString(),
				player.getUUID()
			);
		}

		private static ViewerContext fromSource(CommandSourceStack source) {
			if (source == null) {
				return new ViewerContext(null, false, "unknown", null);
			}
			ServerPlayer player = source.getPlayer();
			if (player != null) {
				return fromPlayer(player);
			}
			String principal = normalizePrincipal(source.getTextName());
			UUID syntheticActorId = UUID.nameUUIDFromBytes(
				("redstonelink:graph_export:" + principal).getBytes(StandardCharsets.UTF_8)
			);
			return new ViewerContext(
				source.getLevel(),
				com.makomi.command.CommandPermissionCompat.hasPermission(source, com.makomi.config.RedstoneLinkConfig.privacy().viewPermissionLevel()),
				"system:" + principal,
				syntheticActorId
			);
		}

		private static String normalizePrincipal(String rawPrincipal) {
			String normalized = rawPrincipal == null ? "" : rawPrincipal.trim();
			return normalized.isEmpty() ? "system" : normalized;
		}
	}
}
