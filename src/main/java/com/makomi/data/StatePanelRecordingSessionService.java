package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.data.NodeRuntimeProbe.ProbeResolution;
import com.makomi.data.NodeRuntimeProbe.TraceNodeKind;
import com.makomi.item.StatePanelToolItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
 * <li>开始时解析当前可录制节点并挂载 trace</li>
 * <li>运行中按玩家维度维护唯一活动会话</li>
 * <li>结束时导出 recording bundle 并释放挂载</li>
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

	private static final Map<MinecraftServer, Map<UUID, ActiveSession>> ACTIVE_SESSIONS_BY_SERVER = new IdentityHashMap<>();

	private StatePanelRecordingSessionService() {
	}

	/**
	 * 注册会话生命周期清理钩子。
	 */
	public static void register() {
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

		StartRequest normalizedRequest = request == null ? new StartRequest("", DEFAULT_SAMPLE_EVERY_TICKS, DEFAULT_CAPACITY_PER_NODE, true) : request;
		List<MountedNode> mountedNodes = mountRecordableNodes(player, subscriptions, normalizedRequest);
		if (mountedNodes.isEmpty()) {
			return new StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.no_recordable_nodes"),
				SessionSnapshot.inactive(subscriptions.size())
			);
		}

		long startedTick = Math.max(0L, player.serverLevel().getGameTime());
		ActiveSession activeSession = new ActiveSession(
			UUID.randomUUID().toString(),
			player.getUUID(),
			normalizedRequest,
			subscriptions.size(),
			List.copyOf(mountedNodes),
			startedTick
		);
		state(player.getServer()).put(player.getUUID(), activeSession);
		return new StartResult(
			true,
			QuickLinkOperationFeedback.success(
				"message.redstonelink.state_panel.recording.start.done",
				Integer.toString(activeSession.mountedNodes().size()),
				Integer.toString(activeSession.subscriptionCount())
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

		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(player.getServer());
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
			long endedTick = Math.max(activeSession.startedTick(), player.serverLevel().getGameTime());
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
			RedstoneLink.LOGGER.warn("状态面板录制导出失败: player={}", player.getGameProfile().getName(), exception);
			return new StopResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.stop.export_failed"),
				SessionSnapshot.inactive(currentSubscriptionCount(player)),
				null
			);
		} finally {
			unmountAll(player.getServer(), activeSession.mountedNodes());
		}
	}

	private static StatePanelRecordingBundle buildRecordingBundle(ServerPlayer player, ActiveSession activeSession, long endedTick) {
		List<StatePanelRecordingBundle.RecordedNodeInfo> nodes = new ArrayList<>(activeSession.mountedNodes().size());
		List<StatePanelRecordingBundle.NodeSeries> series = new ArrayList<>(activeSession.mountedNodes().size());
		int sampleCount = 0;
		for (MountedNode mountedNode : activeSession.mountedNodes()) {
			List<NodeRuntimeSnapshot> recordedSamples = new ArrayList<>(
				NodeStateTraceService.readSamples(
					player.getServer(),
					mountedNode.nodeType(),
					mountedNode.serial(),
					activeSession.request().capacityPerNode()
				)
			);
			Collections.reverse(recordedSamples);
			List<StatePanelRecordingBundle.RecordedSample> outputSamples = new ArrayList<>(recordedSamples.size());
			for (NodeRuntimeSnapshot recordedSample : recordedSamples) {
				outputSamples.add(
					new StatePanelRecordingBundle.RecordedSample(
						recordedSample.sampleTick(),
						recordedSample.online(),
						recordedSample.active(),
						recordedSample.inputPower(),
						recordedSample.outputPower()
					)
				);
			}
			NodeIdentitySnapshot identitySnapshot = NodeIdentitySnapshot.resolve(player.serverLevel(), mountedNode.nodeType(), mountedNode.serial());
			nodes.add(
				new StatePanelRecordingBundle.RecordedNodeInfo(
					nodeKey(mountedNode.nodeType(), mountedNode.serial()),
					mountedNode.nodeType(),
					mountedNode.serial(),
					NodeAliasServerSupport.resolveDisplayText(player.serverLevel(), mountedNode.nodeType(), mountedNode.serial()),
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
	 * 根据当前订阅列表挂载全部可录制节点。
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
				player.getServer(),
				subscription.nodeType(),
				subscription.serial()
			);
			if (resolution.isEmpty()) {
				continue;
			}
			NodeStateTraceService.mount(
				player.getServer(),
				subscription.nodeType(),
				subscription.serial(),
				resolution.get().traceKind(),
				request.sampleEveryTicks(),
				request.capacityPerNode()
			);
			mountedNodes.add(new MountedNode(subscription.nodeType(), subscription.serial(), resolution.get().traceKind()));
		}
		return List.copyOf(mountedNodes);
	}

	private static void clearPlayerSession(ServerPlayer player) {
		if (player == null || player.getServer() == null) {
			return;
		}
		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(player.getServer());
		if (sessionMap == null) {
			return;
		}
		ActiveSession activeSession = sessionMap.remove(player.getUUID());
		if (activeSession != null) {
			unmountAll(player.getServer(), activeSession.mountedNodes());
		}
	}

	private static void clearServerState(MinecraftServer server) {
		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.remove(server);
		if (sessionMap == null || sessionMap.isEmpty()) {
			return;
		}
		for (ActiveSession activeSession : sessionMap.values()) {
			unmountAll(server, activeSession.mountedNodes());
		}
	}

	private static void unmountAll(MinecraftServer server, List<MountedNode> mountedNodes) {
		if (server == null || mountedNodes == null || mountedNodes.isEmpty()) {
			return;
		}
		for (MountedNode mountedNode : mountedNodes) {
			NodeStateTraceService.unmount(server, mountedNode.nodeType(), mountedNode.serial());
		}
	}

	private static ActiveSession activeSession(ServerPlayer player) {
		if (player == null || player.getServer() == null) {
			return null;
		}
		Map<UUID, ActiveSession> sessionMap = ACTIVE_SESSIONS_BY_SERVER.get(player.getServer());
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

	/**
	 * 录制会话开始请求。
	 */
	public record StartRequest(String title, int sampleEveryTicks, int capacityPerNode, boolean autoOpenWeb) {
		public StartRequest {
			title = normalizeTitle(title);
			sampleEveryTicks = Math.max(MIN_SAMPLE_EVERY_TICKS, Math.min(MAX_SAMPLE_EVERY_TICKS, sampleEveryTicks));
			capacityPerNode = Math.max(MIN_CAPACITY_PER_NODE, Math.min(MAX_CAPACITY_PER_NODE, capacityPerNode));
		}

		private static String normalizeTitle(String rawTitle) {
			if (rawTitle == null) {
				return "State Panel Recording";
			}
			String normalized = rawTitle.trim();
			return normalized.isEmpty() ? "State Panel Recording" : normalized;
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
		boolean autoOpenWeb,
		int subscriptionCount,
		int mountedCount,
		long startedTick
	) {
		public SessionSnapshot {
			title = title == null ? "" : title.trim();
			sampleEveryTicks = Math.max(MIN_SAMPLE_EVERY_TICKS, sampleEveryTicks);
			capacityPerNode = Math.max(0, capacityPerNode);
			subscriptionCount = Math.max(0, subscriptionCount);
			mountedCount = Math.max(0, mountedCount);
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
				true,
				subscriptionCount,
				0,
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
	 * 已挂载到 trace 服务的节点引用。
	 */
	private record MountedNode(LinkNodeType nodeType, long serial, TraceNodeKind traceKind) {
	}

	/**
	 * 活动录制会话。
	 */
	private record ActiveSession(
		String recordingId,
		UUID ownerPlayerId,
		StartRequest request,
		int subscriptionCount,
		List<MountedNode> mountedNodes,
		long startedTick
	) {
		private SessionSnapshot toSnapshot() {
			return new SessionSnapshot(
				true,
				request.title(),
				request.sampleEveryTicks(),
				request.capacityPerNode(),
				request.autoOpenWeb(),
				subscriptionCount,
				mountedNodes.size(),
				startedTick
			);
		}
	}
}
