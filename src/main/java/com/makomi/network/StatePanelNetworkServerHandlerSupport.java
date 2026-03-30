package com.makomi.network;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeIdentitySnapshot;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.NodeSnapshotQueryService;
import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.data.StatePanelToolData;
import com.makomi.item.StatePanelToolItem;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 状态面板工具服务端接包处理壳。
 */
final class StatePanelNetworkServerHandlerSupport {
	private static final Map<UUID, Long> LAST_REFRESH_TICK_BY_PLAYER = new HashMap<>();

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
		List<StatePanelToolData.SubscriptionEntry> merged = StatePanelToolData.mergeSubscriptions(
			current,
			nodeType,
			parseResult.orderedTargets()
		);
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
			NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(player.serverLevel(), subscription.nodeType(), subscription.serial());
			NodeRuntimeSnapshot runtimeSnapshot = NodeSnapshotQueryService
				.resolveRuntimeSnapshot(player.serverLevel().getServer(), subscription.nodeType(), subscription.serial())
				.orElse(null);
			boolean active = runtimeSnapshot != null && runtimeSnapshot.active();
			int inputPower = runtimeSnapshot == null ? 0 : runtimeSnapshot.inputPower();
			int outputPower = runtimeSnapshot == null ? 0 : runtimeSnapshot.outputPower();
			values.add(
				new StatePanelNetwork.StatePanelSnapshotEntry(
					subscription.nodeType(),
					subscription.serial(),
					identity.allocated(),
					identity.retired(),
					identity.online(),
					active,
					inputPower,
					outputPower
				)
			);
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
}