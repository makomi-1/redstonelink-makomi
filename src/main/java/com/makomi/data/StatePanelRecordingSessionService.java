package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.data.NodeRuntimeProbe.ProbeResolution;
import com.makomi.data.NodeRuntimeProbe.TraceNodeKind;
import com.makomi.item.StatePanelToolItem;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 状态面板录制会话服务。
 * <p>
 * 该服务负责把“状态面板订阅列表”编排为一个短生命周期的录制会话：
 * </p>
 * <ul>
 * <li>开始时解析当前可录制节点并建立会话内独立采样缓冲</li>
 * <li>运行中按玩家维度维护唯一活动会话</li>
 * <li>结束时导出 recording bundle 并释放会话内样本</li>
 * </ul>
 */
public final class StatePanelRecordingSessionService {
	/** 最小采样间隔，避免 0 或负值。 */
	public static final int MIN_SAMPLE_EVERY_TICKS = 1;
	/** 当前阶段采样间隔上限，避免一版 GUI 暴露过大范围。 */
	public static final int MAX_SAMPLE_EVERY_TICKS = 20;
	/** 最小 ring buffer 容量。 */
	public static final int MIN_CAPACITY_PER_NODE = 8;
	/** 当前阶段单节点最大采样容量。 */
	public static final int MAX_CAPACITY_PER_NODE = 1200;
	/** 默认采样间隔。 */
	public static final int DEFAULT_SAMPLE_EVERY_TICKS = 2;
	/** 默认单节点容量。 */
	public static final int DEFAULT_CAPACITY_PER_NODE = 200;
	/** 默认录制时长；`0` 表示不启用自动结束。 */
	public static final int DEFAULT_DURATION_TICKS = 0;

	private static final Map<MinecraftServer, Map<UUID, ActiveSession>> ACTIVE_SESSIONS_BY_SERVER = new IdentityHashMap<>();

	private StatePanelRecordingSessionService() {
	}

	/**
	 * 注册会话生命周期清理与录制专用采样钩子。
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(StatePanelRecordingSessionService::onEndServerTick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clearPlayerSession(handler.player));
		ServerLifecycleEvents.SERVER_STOPPED.register(StatePanelRecordingSessionService::clearServerState);
	}

	/**
	 * 查询当前玩家录制会话快照。
	 */
	public static SessionSnapshot querySession(ServerPlayer player) {
		if (player == null) {
			return SessionSnapshot.inactive(0);
		}
		ActiveSession activeSession = activeSession(player);
		if (activeSession != null) {
			return activeSession.toSnapshot();
		}
		return SessionSnapshot.inactive(currentSubscriptionCount(player));
	}

	/**
	 * 启动新的录制会话。
	 */
	public static StartResult start(ServerPlayer player, StartRequest request) {
		if (player == null) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.tool_missing"),
				SessionSnapshot.inactive(0)
			);
		}
		if (!WebFeaturePermissionService.canUseRecordingFeature(player)) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.permission.insufficient"),
				SessionSnapshot.inactive(currentSubscriptionCount(player))
			);
		}
		if (activeSession(player) != null) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.already_active"),
				activeSession(player).toSnapshot()
			);
		}

		ItemStack statePanelItem = resolveStatePanelItem(player);
		if (statePanelItem.isEmpty()) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.tool_missing"),
				SessionSnapshot.inactive(0)
			);
		}

		List<StatePanelToolData.SubscriptionEntry> subscriptions = StatePanelToolData.readSubscriptions(statePanelItem);
		if (subscriptions.isEmpty()) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.no_subscriptions"),
				SessionSnapshot.inactive(0)
			);
		}

		StartRequest normalizedRequest = request == null
			? new StartRequest("", DEFAULT_SAMPLE_EVERY_TICKS, DEFAULT_CAPACITY_PER_NODE, DEFAULT_DURATION_TICKS, true, List.of())
			: request;
		List<StatePanelToolData.SubscriptionEntry> selectedSubscriptions = filterSelectedSubscriptions(
			subscriptions,
			normalizedRequest.selectedNodeKeys()
		);
		if (selectedSubscriptions.isEmpty()) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.no_selection"),
				SessionSnapshot.inactive(subscriptions.size())
			);
		}
		List<String> selectedNodeKeys = collectNodeKeys(selectedSubscriptions);
		List<MountedNode> mountedNodes = mountRecordableNodes(player, selectedSubscriptions, normalizedRequest);
		if (mountedNodes.isEmpty()) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.no_recordable_nodes"),
				SessionSnapshot.inactive(subscriptions.size())
			);
		}

		long startedTick = Math.max(0L, player.level().getGameTime());
		ActiveSession activeSession = new ActiveSession(
			UUID.randomUUID().toString(),
			player.getUUID(),
			normalizedRequest,
			subscriptions.size(),
			selectedNodeKeys,
			List.copyOf(mountedNodes),
			startedTick,
			resolveAutoStopTick(startedTick, normalizedRequest.durationTicks())
		);
		state(player.level().getServer()).put(player.getUUID(), activeSession);
		return new StartResult(
			true,
			QuickLinkOperationFeedback.success(
				"message.redstonelink.state_panel.recording.start.done",
				Integer.toString(activeSession.mountedNodeCount()),
				Integer.toString(activeSession.selectedNodeKeys().size())
			),
			activeSession.toSnapshot()
		);
	}

	/**
	 * 停止当前录制会话并导出 recording bundle。
	 */
	public static StopResult stop(ServerPlayer player) {
		if (player == null) {
			return new StopResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.stop.not_active"),
				SessionSnapshot.inactive(0),
				null
			);
		}

		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(player.level().getServer());
		ActiveSession activeSession = sessionMap == null ? null : sessionMap.remove(player.getUUID());
		if (activeSession == null) {
			return new StopResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.stop.not_active"),
				SessionSnapshot.inactive(currentSubscriptionCount(player)),
				null
			);
		}

		try {
			long endedTick = Math.max(activeSession.startedTick(), player.level().getGameTime());
			StatePanelRecordingBundle recordingBundle = buildRecordingBundle(player, activeSession, endedTick);
			byte[] compressedBytes = StatePanelRecordingJsonSupport.toCompressedJsonBytes(recordingBundle);
			String fileName = StatePanelRecordingJsonSupport.buildFileName(recordingBundle);
			return new StopResult(
				true,
				QuickLinkOperationFeedback.success(
					"message.redstonelink.state_panel.recording.stop.done",
					fileName
				),
				SessionSnapshot.inactive(currentSubscriptionCount(player)),
				new ExportBundle(fileName, compressedBytes, activeSession.request().autoOpenWeb())
			);
		} catch (IOException | RuntimeException exception) {
			RedstoneLink.LOGGER.warn("状态面板录制导出失败: player={}", player.getScoreboardName(), exception);
			return new StopResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.stop.export_failed"),
				SessionSnapshot.inactive(currentSubscriptionCount(player)),
				null
			);
		}
	}

	/**
	 * 处理本 tick 到期的自动结束录制会话。
	 */
	public static List<AutoStopOutcome> processDueAutoStops(MinecraftServer server) {
		if (server == null) {
			return List.of();
		}
		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(server);
		if (sessionMap == null || sessionMap.isEmpty()) {
			return List.of();
		}
		List<UUID> duePlayerIds = new ArrayList<>();
		for (Map.Entry<UUID, ActiveSession> entry : sessionMap.entrySet()) {
			ActiveSession activeSession = entry.getValue();
			if (activeSession == null || activeSession.autoStopTick() <= 0L) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			if (player.level().getGameTime() >= activeSession.autoStopTick()) {
				duePlayerIds.add(entry.getKey());
			}
		}
		if (duePlayerIds.isEmpty()) {
			return List.of();
		}
		List<AutoStopOutcome> outcomes = new ArrayList<>(duePlayerIds.size());
		for (UUID playerId : duePlayerIds) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				continue;
			}
			outcomes.add(new AutoStopOutcome(playerId, stop(player)));
		}
		return List.copyOf(outcomes);
	}

	/**
	 * 在 recording 专用晚相位按会话配置补采当前 tick 样本。
	 * <p>
	 * 该钩子故意不复用全局 trace ring buffer，而是直接写入 recording 会话私有缓冲，
	 * 以避免 bench/trace 口径与 recording 导出口径被强绑定。
	 * </p>
	 */
	private static void onEndServerTick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(server);
		if (sessionMap == null || sessionMap.isEmpty()) {
			return;
		}
		long nowTick = resolveServerTick(server);
		for (ActiveSession activeSession : sessionMap.values()) {
			if (activeSession == null) {
				continue;
			}
			activeSession.captureDueSamples(server, nowTick);
		}
	}

	private static StatePanelRecordingBundle buildRecordingBundle(ServerPlayer player, ActiveSession activeSession, long endedTick) {
		List<StatePanelRecordingBundle.RecordedNodeInfo> nodes = new ArrayList<>(activeSession.mountedNodeCount());
		List<StatePanelRecordingBundle.NodeSeries> series = new ArrayList<>(activeSession.mountedNodeCount());
		int sampleCount = 0;
		for (MountedNode mountedNode : activeSession.mountedNodes()) {
			List<StatePanelRecordingBundle.RecordedSample> outputSamples = mountedNode.readRecordedSamples();
			NodeIdentitySnapshot identitySnapshot = NodeIdentitySnapshot.resolve(player.level(), mountedNode.nodeType(), mountedNode.serial());
			nodes.add(
				new StatePanelRecordingBundle.RecordedNodeInfo(
					nodeKey(mountedNode.nodeType(), mountedNode.serial()),
					mountedNode.nodeType(),
					mountedNode.serial(),
					NodeAliasServerSupport.resolveDisplayText(player.level(), mountedNode.nodeType(), mountedNode.serial()),
					mountedNode.traceKind().commandName(),
					identitySnapshot.allocated(),
					identitySnapshot.retired(),
					identitySnapshot.online()
				)
			);
			series.add(new StatePanelRecordingBundle.NodeSeries(nodeKey(mountedNode.nodeType(), mountedNode.serial()), outputSamples));
			sampleCount += outputSamples.size();
		}

		StatePanelRecordingBundle.Manifest manifest = new StatePanelRecordingBundle.Manifest(
			activeSession.recordingId(),
			activeSession.request().title(),
			activeSession.startedTick(),
			endedTick,
			activeSession.request().sampleEveryTicks(),
			nodes.size(),
			sampleCount,
			StatePanelRecordingBundle.FORMAT_VERSION
		);
		List<StatePanelRecordingBundle.RecordingMarker> markers = List.of(
			new StatePanelRecordingBundle.RecordingMarker(activeSession.startedTick(), "recording-start"),
			new StatePanelRecordingBundle.RecordingMarker(endedTick, "recording-stop")
		);
		return new StatePanelRecordingBundle(manifest, nodes, series, markers);
	}

	/**
	 * 根据当前选中的订阅列表挂载可录制节点。
	 */
	private static List<MountedNode> mountRecordableNodes(
		ServerPlayer player,
		List<StatePanelToolData.SubscriptionEntry> subscriptions,
		StartRequest request
	) {
		List<MountedNode> mountedNodes = new ArrayList<>();
		for (StatePanelToolData.SubscriptionEntry subscription : subscriptions) {
			if (subscription == null || subscription.serial() <= 0L) {
				continue;
			}
			if (!CurrentLinksPrivacyService.canReadNodeState(player, subscription.nodeType(), subscription.serial())) {
				continue;
			}
			Optional<ProbeResolution> resolution = NodeRuntimeProbe.resolveCurrent(
				player.level().getServer(),
				subscription.nodeType(),
				subscription.serial()
			);
			if (resolution.isEmpty()) {
				continue;
			}
			mountedNodes.add(
				new MountedNode(
					subscription.nodeType(),
					subscription.serial(),
					resolution.get().traceKind(),
					resolution.get().snapshot(),
					request.capacityPerNode(),
					request.sampleEveryTicks()
				)
			);
		}
		return List.copyOf(mountedNodes);
	}

	/**
	 * 从当前真实订阅列表中过滤出本次请求选中的节点。
	 */
	private static List<StatePanelToolData.SubscriptionEntry> filterSelectedSubscriptions(
		List<StatePanelToolData.SubscriptionEntry> subscriptions,
		List<String> selectedNodeKeys
	) {
		if (subscriptions == null || subscriptions.isEmpty() || selectedNodeKeys == null || selectedNodeKeys.isEmpty()) {
			return List.of();
		}
		Set<String> selectedNodeKeySet = new LinkedHashSet<>(selectedNodeKeys);
		List<StatePanelToolData.SubscriptionEntry> selectedSubscriptions = new ArrayList<>();
		for (StatePanelToolData.SubscriptionEntry subscription : subscriptions) {
			if (subscription == null || subscription.serial() <= 0L) {
				continue;
			}
			if (selectedNodeKeySet.contains(nodeKey(subscription.nodeType(), subscription.serial()))) {
				selectedSubscriptions.add(subscription);
			}
		}
		return List.copyOf(selectedSubscriptions);
	}

	/**
	 * 将订阅条目稳定转换为节点 key 列表，便于 GUI 与会话快照复用。
	 */
	private static List<String> collectNodeKeys(List<StatePanelToolData.SubscriptionEntry> subscriptions) {
		if (subscriptions == null || subscriptions.isEmpty()) {
			return List.of();
		}
		Set<String> nodeKeys = new LinkedHashSet<>();
		for (StatePanelToolData.SubscriptionEntry subscription : subscriptions) {
			if (subscription == null || subscription.serial() <= 0L) {
				continue;
			}
			nodeKeys.add(nodeKey(subscription.nodeType(), subscription.serial()));
		}
		return List.copyOf(nodeKeys);
	}

	private static void clearPlayerSession(ServerPlayer player) {
		if (player == null || player.level().getServer() == null) {
			return;
		}
		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(player.level().getServer());
		if (sessionMap == null) {
			return;
		}
		sessionMap.remove(player.getUUID());
	}

	private static void clearServerState(MinecraftServer server) {
		ACTIVE_SESSIONS_BY_SERVER.remove(server);
	}

	private static ActiveSession activeSession(ServerPlayer player) {
		if (player == null || player.level().getServer() == null) {
			return null;
		}
		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(player.level().getServer());
		return sessionMap == null ? null : sessionMap.get(player.getUUID());
	}

	private static Map<UUID, ActiveSession> state(MinecraftServer server) {
		return ACTIVE_SESSIONS_BY_SERVER.computeIfAbsent(server, unused -> new LinkedHashMap<>());
	}

	private static int currentSubscriptionCount(ServerPlayer player) {
		ItemStack statePanelItem = resolveStatePanelItem(player);
		return statePanelItem.isEmpty() ? 0 : StatePanelToolData.subscriptionCount(statePanelItem);
	}

	private static ItemStack resolveStatePanelItem(ServerPlayer player) {
		if (player == null) {
			return ItemStack.EMPTY;
		}
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof StatePanelToolItem)) {
			return ItemStack.EMPTY;
		}
		return mainHandItem;
	}

	private static String nodeKey(LinkNodeType nodeType, long serial) {
		return LinkNodeSemantics.toSemanticName(nodeType) + ":" + Math.max(0L, serial);
	}

	private static long resolveAutoStopTick(long startedTick, int durationTicks) {
		if (durationTicks <= 0) {
			return 0L;
		}
		return Math.max(0L, startedTick) + durationTicks;
	}

	private static long resolveServerTick(MinecraftServer server) {
		if (server == null || server.overworld() == null) {
			return 0L;
		}
		return Math.max(0L, server.overworld().getGameTime());
	}

	/**
	 * 录制会话开始请求。
	 */
	public record StartRequest(
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		int durationTicks,
		boolean autoOpenWeb,
		List<String> selectedNodeKeys
	) {
		public StartRequest {
			title = normalizeTitle(title);
			sampleEveryTicks = Math.max(MIN_SAMPLE_EVERY_TICKS, Math.min(MAX_SAMPLE_EVERY_TICKS, sampleEveryTicks));
			capacityPerNode = Math.max(MIN_CAPACITY_PER_NODE, Math.min(MAX_CAPACITY_PER_NODE, capacityPerNode));
			durationTicks = Math.max(0, durationTicks);
			selectedNodeKeys = normalizeSelectedNodeKeys(selectedNodeKeys);
		}

		private static String normalizeTitle(String rawTitle) {
			if (rawTitle == null) {
				return "State Panel Recording";
			}
			String normalized = rawTitle.trim();
			return normalized.isEmpty() ? "State Panel Recording" : normalized;
		}

		private static List<String> normalizeSelectedNodeKeys(List<String> rawNodeKeys) {
			if (rawNodeKeys == null || rawNodeKeys.isEmpty()) {
				return List.of();
			}
			Set<String> normalizedNodeKeys = new LinkedHashSet<>();
			for (String rawNodeKey : rawNodeKeys) {
				if (rawNodeKey == null) {
					continue;
				}
				String normalizedNodeKey = rawNodeKey.trim();
				if (!normalizedNodeKey.isEmpty()) {
					normalizedNodeKeys.add(normalizedNodeKey);
				}
			}
			return List.copyOf(normalizedNodeKeys);
		}
	}

	/**
	 * 当前录制会话快照。
	 */
	public record SessionSnapshot(
		boolean active,
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		int durationTicks,
		boolean autoOpenWeb,
		int subscriptionCount,
		int mountedCount,
		List<String> selectedNodeKeys,
		long startedTick
	) {
		public SessionSnapshot {
			title = title == null ? "" : title.trim();
			sampleEveryTicks = Math.max(MIN_SAMPLE_EVERY_TICKS, sampleEveryTicks);
			capacityPerNode = Math.max(0, capacityPerNode);
			durationTicks = Math.max(0, durationTicks);
			subscriptionCount = Math.max(0, subscriptionCount);
			mountedCount = Math.max(0, mountedCount);
			selectedNodeKeys = selectedNodeKeys == null ? List.of() : List.copyOf(selectedNodeKeys);
			startedTick = Math.max(0L, startedTick);
		}

		/**
		 * 构造非活动会话快照。
		 */
		public static SessionSnapshot inactive(int subscriptionCount) {
			return new SessionSnapshot(
				false,
				"",
				DEFAULT_SAMPLE_EVERY_TICKS,
				DEFAULT_CAPACITY_PER_NODE,
				DEFAULT_DURATION_TICKS,
				true,
				subscriptionCount,
				0,
				List.of(),
				0L
			);
		}
	}

	/**
	 * 开始录制结果。
	 */
	public record StartResult(boolean success, QuickLinkOperationFeedback feedback, SessionSnapshot sessionSnapshot) {}

	/**
	 * 停止录制结果。
	 */
	public record StopResult(
		boolean success,
		QuickLinkOperationFeedback feedback,
		SessionSnapshot sessionSnapshot,
		ExportBundle exportBundle
	) {}

	/**
	 * 导出结果。
	 */
	public record ExportBundle(String fileName, byte[] compressedBytes, boolean autoOpenWeb) {
		public ExportBundle {
			fileName = fileName == null ? "" : fileName.trim();
			compressedBytes = compressedBytes == null ? new byte[0] : compressedBytes.clone();
		}
	}

	/**
	 * 自动结束录制的服务端处理结果。
	 */
	public record AutoStopOutcome(UUID ownerPlayerId, StopResult stopResult) {
		public AutoStopOutcome {
			stopResult = stopResult == null
				? new StopResult(false, QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.stop.not_active"), SessionSnapshot.inactive(0), null)
				: stopResult;
		}
	}

	/**
	 * recording 会话中的单节点采样状态。
	 */
	private static final class MountedNode {
		private final LinkNodeType nodeType;
		private final long serial;
		private final TraceNodeKind traceKind;
		private final RecordingSampleBuffer sampleBuffer;
		private long nextSampleTick;

		private MountedNode(
			LinkNodeType nodeType,
			long serial,
			TraceNodeKind traceKind,
			NodeRuntimeSnapshot initialSnapshot,
			int capacity,
			int sampleEveryTicks
		) {
			this.nodeType = nodeType == null ? LinkNodeType.CORE : nodeType;
			this.serial = Math.max(0L, serial);
			this.traceKind = traceKind == null ? TraceNodeKind.CORE : traceKind;
			this.sampleBuffer = new RecordingSampleBuffer(capacity);
			this.sampleBuffer.append(initialSnapshot);
			this.nextSampleTick = Math.max(0L, initialSnapshot == null ? 0L : initialSnapshot.sampleTick())
				+ Math.max(1, sampleEveryTicks);
		}

		private LinkNodeType nodeType() {
			return nodeType;
		}

		private long serial() {
			return serial;
		}

		private TraceNodeKind traceKind() {
			return traceKind;
		}

		/**
		 * 若当前 tick 到达采样点，则写入一条新的 recording 样本。
		 */
		private void captureIfDue(MinecraftServer server, int sampleEveryTicks, long nowTick) {
			if (nowTick < nextSampleTick) {
				return;
			}
			NodeRuntimeSnapshot snapshot = NodeRuntimeProbe.snapshot(server, nodeType, serial, traceKind);
			sampleBuffer.append(snapshot);
			nextSampleTick = Math.max(0L, snapshot.sampleTick()) + Math.max(1, sampleEveryTicks);
		}

		private List<StatePanelRecordingBundle.RecordedSample> readRecordedSamples() {
			List<NodeRuntimeSnapshot> snapshots = sampleBuffer.readChronological();
			List<StatePanelRecordingBundle.RecordedSample> recordedSamples = new ArrayList<>(snapshots.size());
			for (NodeRuntimeSnapshot snapshot : snapshots) {
				recordedSamples.add(
					new StatePanelRecordingBundle.RecordedSample(
						snapshot.sampleTick(),
						snapshot.online(),
						snapshot.active(),
						snapshot.inputPower(),
						snapshot.outputPower()
					)
				);
			}
			return List.copyOf(recordedSamples);
		}
	}

	/**
	 * 活动录制会话。
	 */
	private static final class ActiveSession {
		private final String recordingId;
		private final UUID ownerPlayerId;
		private final StartRequest request;
		private final int subscriptionCount;
		private final List<String> selectedNodeKeys;
		private final List<MountedNode> mountedNodes;
		private final long startedTick;
		private final long autoStopTick;

		private ActiveSession(
			String recordingId,
			UUID ownerPlayerId,
			StartRequest request,
			int subscriptionCount,
			List<String> selectedNodeKeys,
			List<MountedNode> mountedNodes,
			long startedTick,
			long autoStopTick
		) {
			this.recordingId = recordingId == null ? "" : recordingId;
			this.ownerPlayerId = ownerPlayerId;
			this.request = request;
			this.subscriptionCount = Math.max(0, subscriptionCount);
			this.selectedNodeKeys = selectedNodeKeys == null ? List.of() : List.copyOf(selectedNodeKeys);
			this.mountedNodes = mountedNodes == null ? List.of() : List.copyOf(mountedNodes);
			this.startedTick = Math.max(0L, startedTick);
			this.autoStopTick = Math.max(0L, autoStopTick);
		}

		private String recordingId() {
			return recordingId;
		}

		private UUID ownerPlayerId() {
			return ownerPlayerId;
		}

		private StartRequest request() {
			return request;
		}

		private List<String> selectedNodeKeys() {
			return selectedNodeKeys;
		}

		private List<MountedNode> mountedNodes() {
			return mountedNodes;
		}

		private long startedTick() {
			return startedTick;
		}

		private long autoStopTick() {
			return autoStopTick;
		}

		private int mountedNodeCount() {
			return mountedNodes.size();
		}

		/**
		 * 按会话采样周期写入当前 tick 的 recording 专用样本。
		 */
		private void captureDueSamples(MinecraftServer server, long nowTick) {
			for (MountedNode mountedNode : mountedNodes) {
				mountedNode.captureIfDue(server, request.sampleEveryTicks(), nowTick);
			}
		}

		private SessionSnapshot toSnapshot() {
			return new SessionSnapshot(
				true,
				request.title(),
				request.sampleEveryTicks(),
				request.capacityPerNode(),
				request.durationTicks(),
				request.autoOpenWeb(),
				subscriptionCount,
				mountedNodeCount(),
				selectedNodeKeys,
				startedTick
			);
		}
	}

	/**
	 * recording 会话私有 ring buffer。
	 */
	private static final class RecordingSampleBuffer {
		private final ArrayDeque<NodeRuntimeSnapshot> chronologicalSnapshots = new ArrayDeque<>();
		private final int capacity;

		private RecordingSampleBuffer(int capacity) {
			this.capacity = Math.max(1, capacity);
		}

		private void append(NodeRuntimeSnapshot snapshot) {
			if (snapshot == null) {
				return;
			}
			if (chronologicalSnapshots.size() >= capacity) {
				chronologicalSnapshots.removeFirst();
			}
			chronologicalSnapshots.addLast(snapshot);
		}

		private List<NodeRuntimeSnapshot> readChronological() {
			return List.copyOf(chronologicalSnapshots);
		}
	}
}
