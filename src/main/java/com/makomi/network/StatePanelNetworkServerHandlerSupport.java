package com.makomi.network;

import com.makomi.advancement.RedstoneLinkAdvancementService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CurrentLinksPrivacyService;
import com.makomi.data.GraphSnapshotExportService;
import com.makomi.data.GraphWriteService;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeIdentitySnapshot;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasServerSupport;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.NodeSnapshotQueryService;
import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.data.StatePanelRecordingSessionService;
import com.makomi.data.StatePanelRecordingSessionService.ExportBundle;
import com.makomi.data.StatePanelToolData;
import com.makomi.item.StatePanelToolItem;
import com.makomi.util.SerialParseUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 状态面板工具服务端接包处理壳。
 */
final class StatePanelNetworkServerHandlerSupport {
	private static final Map<UUID, Long> LAST_REFRESH_TICK_BY_PLAYER = new HashMap<>();
	private static final int RECORDING_EXPORT_CHUNK_BYTES = 24576;

	private StatePanelNetworkServerHandlerSupport() {
	}

	/**
	 * 处理订阅请求。
	 */
	static void handleSubscribe(ServerPlayer player, StatePanelNetwork.SubscribeStatePanelPayload payload) {
		ItemStack mainHandItem = resolveStatePanelItem(player);
		if (mainHandItem.isEmpty()) {
			return;
		}

		LinkNodeType nodeType = LinkNodeSemantics.tryParseCanonicalType(payload.nodeTypeToken()).orElse(null);
		if (nodeType == null) {
			sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.subscribe.invalid_type"));
			return;
		}

		String rawExpression = payload.serialExpression() == null ? "" : payload.serialExpression().trim();
		if (rawExpression.isEmpty()) {
			sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.subscribe.empty"));
			return;
		}

		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (rawExpression.length() > maxInputLength) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure("message.redstonelink.link.set.input_too_long", Integer.toString(maxInputLength))
			);
			return;
		}

		int maxSubscriptions = RedstoneLinkConfig.general().statePanelMaxSubscriptions();
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawExpression, maxSubscriptions);
		if (!parseResult.invalidEntries().isEmpty()) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure(
					"message.redstonelink.invalid_target_tokens",
					String.join(", ", parseResult.invalidEntries())
				)
			);
			return;
		}
		if (parseResult.exceedLimit()) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.subscribe.too_many", Integer.toString(maxSubscriptions))
			);
			return;
		}
		if (parseResult.orderedTargets().isEmpty()) {
			sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.subscribe.empty"));
			return;
		}

		List<StatePanelToolData.SubscriptionEntry> current = StatePanelToolData.readSubscriptions(mainHandItem);
		List<Long> newSerials = collectNewSerials(current, nodeType, parseResult.orderedTargets());
		if (newSerials.isEmpty()) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.success(
					"message.redstonelink.state_panel.subscribe.done",
					Integer.toString(current.size())
				)
			);
			return;
		}

		List<String> unallocatedSerials = new ArrayList<>();
		List<String> retiredSerials = new ArrayList<>();
		collectInvalidNewSerials(player, nodeType, newSerials, unallocatedSerials, retiredSerials);
		if (!unallocatedSerials.isEmpty()) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure(
					"message.redstonelink.invalid_target_unallocated",
					String.join(", ", unallocatedSerials)
				)
			);
			return;
		}
		if (!retiredSerials.isEmpty()) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure(
					"message.redstonelink.invalid_target_retired",
					String.join(", ", retiredSerials)
				)
			);
			return;
		}
		List<String> privacyDeniedSerials = collectPrivacyDeniedSerials(player, nodeType, newSerials);
		if (!privacyDeniedSerials.isEmpty()) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure(
					"message.redstonelink.state_panel.subscribe.privacy_denied",
					String.join(", ", privacyDeniedSerials)
				)
			);
			return;
		}

		List<StatePanelToolData.SubscriptionEntry> merged = StatePanelToolData.mergeSubscriptions(current, nodeType, newSerials);
		if (merged.size() > maxSubscriptions) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure(
					"message.redstonelink.state_panel.subscribe.limit_reached",
					Integer.toString(maxSubscriptions)
				)
			);
			return;
		}

		StatePanelToolData.writeSubscriptions(mainHandItem, merged);
		player.containerMenu.broadcastChanges();
		sendFeedback(
			player,
			QuickLinkOperationFeedback.success(
				"message.redstonelink.state_panel.subscribe.done",
				Integer.toString(merged.size())
			)
		);
		RedstoneLinkAdvancementService.awardMasterStrategist(player);
		// 仅在本次确有新增订阅时回传一次快照，避免无效重复刷新。
		sendSnapshot(player, merged);
	}

	/**
	 * 处理刷新请求。
	 */
	static void handleRefresh(ServerPlayer player) {
		ItemStack mainHandItem = resolveStatePanelItem(player);
		if (mainHandItem.isEmpty()) {
			return;
		}
		if (isRefreshThrottled(player)) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure(
					"message.redstonelink.state_panel.refresh.throttled",
					Integer.toString(RedstoneLinkConfig.general().statePanelRefreshHz())
				)
			);
			return;
		}
		sendSnapshot(player, StatePanelToolData.readSubscriptions(mainHandItem));
	}

	/**
	 * 查询当前录制会话状态。
	 */
	static void handleQueryRecordingSession(ServerPlayer player) {
		sendRecordingSession(player, StatePanelRecordingSessionService.querySession(player));
	}

	/**
	 * 处理开始录制请求。
	 */
	static void handleStartRecording(ServerPlayer player, StatePanelNetwork.StartStatePanelRecordingPayload payload) {
		StatePanelRecordingSessionService.StartResult startResult = StatePanelRecordingSessionService.start(
			player,
			new StatePanelRecordingSessionService.StartRequest(
				payload.title(),
				payload.sampleEveryTicks(),
				payload.capacityPerNode(),
				payload.durationTicks(),
				payload.autoOpenWeb(),
				payload.selectedNodeKeys()
			)
		);
		sendRecordingSession(player, startResult.sessionSnapshot());
		sendFeedback(player, startResult.feedback());
	}

	/**
	 * 处理结束录制请求。
	 */
	static void handleStopRecording(ServerPlayer player) {
		StatePanelRecordingSessionService.StopResult stopResult = StatePanelRecordingSessionService.stop(player);
		sendRecordingSession(player, stopResult.sessionSnapshot());
		if (stopResult.exportBundle() != null) {
			sendRecordingExport(player, stopResult.exportBundle());
		}
		sendFeedback(player, stopResult.feedback());
	}

	/**
	 * 处理服务端 tick 上到期的自动结束录制。
	 */
	static void handleAutoStopTick(MinecraftServer server) {
		for (StatePanelRecordingSessionService.AutoStopOutcome autoStopOutcome : StatePanelRecordingSessionService.processDueAutoStops(server)) {
			if (autoStopOutcome == null || autoStopOutcome.ownerPlayerId() == null) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(autoStopOutcome.ownerPlayerId());
			if (player == null) {
				continue;
			}
			StatePanelRecordingSessionService.StopResult stopResult = autoStopOutcome.stopResult();
			sendRecordingSession(player, stopResult.sessionSnapshot());
			if (stopResult.exportBundle() != null) {
				sendRecordingExport(player, stopResult.exportBundle());
			}
			sendFeedback(player, stopResult.feedback());
		}
	}

	/**
	 * 处理删除单条订阅请求。
	 */
	static void handleRemove(ServerPlayer player, StatePanelNetwork.RemoveStatePanelSerialPayload payload) {
		ItemStack mainHandItem = resolveStatePanelItem(player);
		if (mainHandItem.isEmpty()) {
			return;
		}

		LinkNodeType nodeType = LinkNodeSemantics.tryParseCanonicalType(payload.nodeTypeToken()).orElse(null);
		if (nodeType == null || payload.serial() <= 0L) {
			sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.remove.invalid"));
			return;
		}

		boolean removed = StatePanelToolData.removeSubscription(mainHandItem, nodeType, payload.serial());
		if (!removed) {
			sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.remove.not_found"));
			return;
		}

		player.containerMenu.broadcastChanges();
		sendFeedback(player, QuickLinkOperationFeedback.success("message.redstonelink.state_panel.remove.done"));
		sendSnapshot(player, StatePanelToolData.readSubscriptions(mainHandItem));
	}

	/**
	 * 处理录制按钮请求（当前仅预留入口）。
	 */
	static void handleRecord(ServerPlayer player) {
		ItemStack mainHandItem = resolveStatePanelItem(player);
		if (mainHandItem.isEmpty()) {
			return;
		}
		sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.record.future"));
	}

	/**
	 * 导出当前玩家可见的 serial 图快照。
	 */
	static void handleExportGraph(ServerPlayer player, StatePanelNetwork.ExportStatePanelGraphPayload payload) {
		if (player == null) {
			return;
		}
		try {
			boolean forceTransfer = payload != null && payload.forceTransfer();
			GraphSnapshotExportService.ExportBundle exportBundle = GraphExportNetworkSupport.exportVisibleSerialGraph(
				player,
				forceTransfer,
				true
			);
			sendFeedback(player, QuickLinkOperationFeedback.success("message.redstonelink.graph.export.done", exportBundle.fileName()));
		} catch (IOException | RuntimeException exception) {
			com.makomi.RedstoneLink.LOGGER.warn("导出图快照失败: player={}", player.getScoreboardName(), exception);
			sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.graph.export.failed"));
		}
	}

	/**
	 * 处理网页 graph 显式保存请求。
	 */
	static void handleSubmitGraphWrite(ServerPlayer player, StatePanelNetwork.SubmitGraphWritePayload payload) {
		if (player == null || payload == null || payload.requestId().isBlank()) {
			return;
		}
		String responseJson = GraphWriteService.submit(player, payload.requestJson());
		ServerPlayNetworking.send(player, new StatePanelNetwork.GraphWriteResultPayload(payload.requestId(), responseJson));
	}

	/**
	 * 处理清空全部订阅请求。
	 */
	static void handleCleanAll(ServerPlayer player) {
		ItemStack mainHandItem = resolveStatePanelItem(player);
		if (mainHandItem.isEmpty()) {
			return;
		}
		List<StatePanelToolData.SubscriptionEntry> current = StatePanelToolData.readSubscriptions(mainHandItem);
		int removedCount = current.size();
		StatePanelToolData.writeSubscriptions(mainHandItem, List.of());
		player.containerMenu.broadcastChanges();
		sendFeedback(
			player,
			QuickLinkOperationFeedback.success(
				"message.redstonelink.state_panel.clean_all.done",
				Integer.toString(removedCount)
			)
		);
		sendSnapshot(player, List.of());
	}

	/**
	 * 发送状态面板快照。
	 */
	private static void sendSnapshot(ServerPlayer player, List<StatePanelToolData.SubscriptionEntry> subscriptions) {
		List<StatePanelNetwork.StatePanelSnapshotEntry> entries = buildSnapshotEntries(player, subscriptions);
		ServerPlayNetworking.send(player, new StatePanelNetwork.StatePanelSnapshotPayload(entries));
	}

	/**
	 * 将订阅项映射为可显示快照。
	 */
	private static List<StatePanelNetwork.StatePanelSnapshotEntry> buildSnapshotEntries(
		ServerPlayer player,
		List<StatePanelToolData.SubscriptionEntry> subscriptions
	) {
		if (subscriptions == null || subscriptions.isEmpty()) {
			return List.of();
		}
		List<StatePanelNetwork.StatePanelSnapshotEntry> values = new ArrayList<>(subscriptions.size());
		for (StatePanelToolData.SubscriptionEntry subscription : subscriptions) {
			boolean readable = CurrentLinksPrivacyService.canReadNodeState(player, subscription.nodeType(), subscription.serial());
			NodeIdentitySnapshot identity = readable
				? NodeIdentitySnapshot.resolve(player.serverLevel(), subscription.nodeType(), subscription.serial())
				: null;
			NodeRuntimeSnapshot runtimeSnapshot = readable
				? NodeSnapshotQueryService
					.resolveRuntimeSnapshot(player.serverLevel().getServer(), subscription.nodeType(), subscription.serial())
					.orElse(null)
				: null;
			String displayText = readable
				? NodeAliasServerSupport.resolveDisplayText(player.serverLevel(), subscription.nodeType(), subscription.serial())
				: NodeAliasDisplayUtil.formatDisplayText("", subscription.serial());
			values.add(buildSnapshotEntry(subscription, readable, identity, runtimeSnapshot, displayText));
		}
		return List.copyOf(values);
	}

	/**
	 * 统一状态面板反馈回传。
	 */
	private static void sendFeedback(ServerPlayer player, QuickLinkOperationFeedback feedback) {
		ServerPlayNetworking.send(
			player,
			new StatePanelNetwork.StatePanelFeedbackPayload(feedback.success(), feedback.messageKey(), feedback.messageArgs())
		);
	}

	/**
	 * 发送录制会话状态。
	 */
	private static void sendRecordingSession(
		ServerPlayer player,
		StatePanelRecordingSessionService.SessionSnapshot sessionSnapshot
	) {
		StatePanelRecordingSessionService.SessionSnapshot snapshot = sessionSnapshot == null
			? StatePanelRecordingSessionService.SessionSnapshot.inactive(0)
			: sessionSnapshot;
		ServerPlayNetworking.send(
			player,
			new StatePanelNetwork.StatePanelRecordingSessionPayload(
				snapshot.active(),
				snapshot.title(),
				snapshot.sampleEveryTicks(),
				snapshot.capacityPerNode(),
				snapshot.durationTicks(),
				snapshot.autoOpenWeb(),
				snapshot.subscriptionCount(),
				snapshot.mountedCount(),
				snapshot.selectedNodeKeys(),
				snapshot.startedTick()
			)
		);
	}

	/**
	 * 分块发送录制结果，避免单包体积过大。
	 */
	private static void sendRecordingExport(ServerPlayer player, ExportBundle exportBundle) {
		byte[] compressedBytes = exportBundle == null ? null : exportBundle.compressedBytes();
		if (player == null || exportBundle == null || compressedBytes == null || compressedBytes.length <= 0) {
			return;
		}
		int totalChunks = Math.max(1, (compressedBytes.length + RECORDING_EXPORT_CHUNK_BYTES - 1) / RECORDING_EXPORT_CHUNK_BYTES);
		for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
			int startOffset = chunkIndex * RECORDING_EXPORT_CHUNK_BYTES;
			int endOffset = Math.min(compressedBytes.length, startOffset + RECORDING_EXPORT_CHUNK_BYTES);
			int chunkLength = Math.max(0, endOffset - startOffset);
			byte[] chunkBytes = new byte[chunkLength];
			System.arraycopy(compressedBytes, startOffset, chunkBytes, 0, chunkLength);
			ServerPlayNetworking.send(
				player,
				new StatePanelNetwork.StatePanelRecordingExportChunkPayload(
					exportBundle.fileName(),
					chunkIndex,
					totalChunks,
					exportBundle.autoOpenWeb(),
					chunkBytes
				)
			);
		}
	}

	/**
	 * 判断当前刷新请求是否触发节流。
	 */
	private static boolean isRefreshThrottled(ServerPlayer player) {
		int refreshHz = RedstoneLinkConfig.general().statePanelRefreshHz();
		long minIntervalTicks = Math.max(1L, (20L + refreshHz - 1L) / refreshHz);
		long nowTick = player.serverLevel().getGameTime();
		UUID playerId = player.getUUID();
		Long lastTick = LAST_REFRESH_TICK_BY_PLAYER.get(playerId);
		if (lastTick != null && nowTick - lastTick < minIntervalTicks) {
			return true;
		}
		LAST_REFRESH_TICK_BY_PLAYER.put(playerId, nowTick);
		return false;
	}

	/**
	 * 校验主手是否持有状态面板工具。
	 */
	private static ItemStack resolveStatePanelItem(ServerPlayer player) {
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof StatePanelToolItem)) {
			return ItemStack.EMPTY;
		}
		return mainHandItem;
	}

	/**
	 * 从输入序号中筛出“本次新加入订阅”的序号。
	 */
	private static List<Long> collectNewSerials(
		List<StatePanelToolData.SubscriptionEntry> current,
		LinkNodeType nodeType,
		List<Long> requestedSerials
	) {
		if (requestedSerials == null || requestedSerials.isEmpty()) {
			return List.of();
		}
		Set<Long> existing = new HashSet<>();
		if (current != null && !current.isEmpty()) {
			for (StatePanelToolData.SubscriptionEntry entry : current) {
				if (entry.nodeType() == nodeType && entry.serial() > 0L) {
					existing.add(entry.serial());
				}
			}
		}
		List<Long> newSerials = new ArrayList<>();
		for (Long requested : requestedSerials) {
			long serial = requested == null ? 0L : requested;
			if (serial <= 0L || existing.contains(serial)) {
				continue;
			}
			newSerials.add(serial);
		}
		return newSerials.isEmpty() ? List.of() : List.copyOf(newSerials);
	}

	/**
	 * 对“新增订阅序号组”执行已分配且未退役校验。
	 */
	private static void collectInvalidNewSerials(
		ServerPlayer player,
		LinkNodeType nodeType,
		List<Long> newSerials,
		List<String> unallocatedSerials,
		List<String> retiredSerials
	) {
		if (newSerials == null || newSerials.isEmpty()) {
			return;
		}
		for (Long serialValue : newSerials) {
			long serial = serialValue == null ? 0L : serialValue;
			if (serial <= 0L) {
				continue;
			}
			NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(player.serverLevel(), nodeType, serial);
			if (!identity.allocated()) {
				unallocatedSerials.add(Long.toString(serial));
				continue;
			}
			if (identity.retired()) {
				retiredSerials.add(Long.toString(serial));
			}
		}
	}

	/**
	 * 收集因隐私读控而不允许新增订阅的序号。
	 */
	private static List<String> collectPrivacyDeniedSerials(ServerPlayer player, LinkNodeType nodeType, List<Long> newSerials) {
		if (player == null || nodeType == null || newSerials == null || newSerials.isEmpty()) {
			return List.of();
		}
		List<String> deniedSerials = new ArrayList<>();
		for (Long serialValue : newSerials) {
			long serial = serialValue == null ? 0L : serialValue;
			if (serial <= 0L) {
				continue;
			}
			if (!CurrentLinksPrivacyService.canReadNodeState(player, nodeType, serial)) {
				deniedSerials.add(Long.toString(serial));
			}
		}
		return deniedSerials.isEmpty() ? List.of() : List.copyOf(deniedSerials);
	}

	/**
	 * 构造状态面板单条快照；不可读条目统一回传隐藏态。
	 */
	static StatePanelNetwork.StatePanelSnapshotEntry buildSnapshotEntry(
		StatePanelToolData.SubscriptionEntry subscription,
		boolean readable,
		NodeIdentitySnapshot identity,
		NodeRuntimeSnapshot runtimeSnapshot,
		String displayText
	) {
		LinkNodeType nodeType = subscription == null ? LinkNodeType.CORE : subscription.nodeType();
		long serial = subscription == null ? 0L : subscription.serial();
		if (!readable) {
			return new StatePanelNetwork.StatePanelSnapshotEntry(
				nodeType,
				serial,
				displayText,
				false,
				false,
				false,
				false,
				0,
				0,
				false
			);
		}
		NodeIdentitySnapshot resolvedIdentity = identity == null
			? new NodeIdentitySnapshot(nodeType, serial, false, false, false, null, null)
			: identity;
		boolean active = runtimeSnapshot != null && runtimeSnapshot.active();
		int inputPower = runtimeSnapshot == null ? 0 : runtimeSnapshot.inputPower();
		int outputPower = runtimeSnapshot == null ? 0 : runtimeSnapshot.outputPower();
		return new StatePanelNetwork.StatePanelSnapshotEntry(
			nodeType,
			serial,
			displayText,
			resolvedIdentity.allocated(),
			resolvedIdentity.retired(),
			resolvedIdentity.online(),
			active,
			inputPower,
			outputPower,
			true
		);
	}
}
