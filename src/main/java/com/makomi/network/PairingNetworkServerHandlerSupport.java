package com.makomi.network;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.link.CoreLinkEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CrossChunkNodeIdentity;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.NodeSnapshotQueryService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * `PairingNetwork` 的服务端请求处理壳。
 * <p>
 * 该 helper 负责校验客户端请求上下文、查询服务端快照并回包，不承载 payload 编解码职责。
 * </p>
 */
final class PairingNetworkServerHandlerSupport {
	private static final int CURRENT_LINKS_REQUEST_MAX_DISTANCE = 8;
	private static final long CURRENT_LINKS_REQUEST_MIN_INTERVAL_TICKS = 5L;
	private static final long RUNTIME_HUD_REQUEST_MIN_INTERVAL_TICKS = 5L;
	private static final long REQUEST_THROTTLE_CLEANUP_INTERVAL_TICKS = 200L;
	private static final long REQUEST_THROTTLE_STALE_TICKS = 400L;
	private static final Map<UUID, Long> LAST_CURRENT_LINKS_REQUEST_TICK_BY_PLAYER = new HashMap<>();
	private static final Map<UUID, Long> LAST_RUNTIME_HUD_REQUEST_TICK_BY_PLAYER = new HashMap<>();
	private static long lastThrottleCleanupTick = Long.MIN_VALUE;

	private PairingNetworkServerHandlerSupport() {
	}

	/**
	 * 处理 triggerSource 配对界面的结构化提交请求。
	 *
	 * @param player 发起请求的服务端玩家
	 * @param payload 客户端上传的 triggerSource 配对表达式
	 */
	static void handleSubmitTriggerSourcePairing(ServerPlayer player, PairingNetwork.SubmitTriggerSourcePairingPayload payload) {
		if (player == null || payload == null) {
			return;
		}
		if (!player.hasPermissions(RedstoneLinkConfig.command().permissionLevel())) {
			sendPairingFeedback(
				player,
				LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient")
			);
			return;
		}

		sendPairingFeedbacks(
			player,
			PairingOccSubmissionSupport
				.submitTriggerSource(
					player.createCommandSourceStack(),
					player,
					player.serverLevel(),
					payload.sourceSerial(),
					payload.targetsExpression(),
					payload.expectedSourceRevision()
				)
				.feedbacks()
		);
	}

	/**
	 * 处理 core 配对界面的结构化提交请求。
	 * <p>
	 * 该入口只接收“以 core 为观察中心”的编辑请求；实际落地时仍统一拆成多个
	 * `triggerSource -> core` 正向覆盖写入，避免把旧反向语义继续固化到协议层。
	 * </p>
	 *
	 * @param player 发起请求的服务端玩家
	 * @param payload 客户端上传的 core 配对表达式
	 */
	static void handleSubmitCorePairing(ServerPlayer player, PairingNetwork.SubmitCorePairingPayload payload) {
		if (player == null || payload == null) {
			return;
		}
		if (!player.hasPermissions(RedstoneLinkConfig.command().permissionLevel())) {
			sendPairingFeedback(
				player,
				LinkSetExecutionService.OperationFeedback.failure("message.redstonelink.permission.insufficient")
			);
			return;
		}

		sendPairingFeedbacks(
			player,
			PairingOccSubmissionSupport
				.submitCore(
					player.createCommandSourceStack(),
					player,
					player.serverLevel(),
					payload.coreSerial(),
					payload.triggerSourceExpression(),
					payload.expectedCoreRevision()
				)
				.feedbacks()
		);
	}

	/**
	 * 处理客户端“当前连接”查询请求。
	 *
	 * @param player 发起请求的服务端玩家
	 * @param payload 客户端上传的节点定位上下文
	 */
	static void handleRequestCurrentLinks(ServerPlayer player, PairingNetwork.RequestCurrentLinksPayload payload) {
		if (!canReceiveNearOverlayPackets(player)) {
			return;
		}
		if (isCurrentLinksRequestThrottled(player)) {
			return;
		}
		Optional<LinkNodeType> requestedType = LinkNodeSemantics.tryParseCanonicalType(payload.sourceType());
		if (requestedType.isEmpty() || payload.sourceSerial() <= 0L) {
			return;
		}

		List<Long> visibleTargets = List.of();
		CrossChunkNodeIdentity crossChunkIdentity = CrossChunkNodeIdentity.NORMAL;
		PairableNodeBlockEntity pairableNode = resolveRequestedNode(
			player,
			payload.dimensionKey(),
			payload.blockPos(),
			requestedType.get(),
			payload.sourceSerial()
		);
		if (pairableNode != null) {
			visibleTargets = NodeSnapshotQueryService
				.queryLinks(player, pairableNode.getLinkNodeType(), pairableNode.getSerial())
				.visibleTargets();
			if (pairableNode.getLevel() instanceof net.minecraft.server.level.ServerLevel requestedLevel) {
				crossChunkIdentity = NodeSnapshotQueryService.resolveCrossChunkNodeIdentity(
					requestedLevel,
					pairableNode.getLinkNodeType(),
					pairableNode.getSerial()
				);
			}
		}

		sendCurrentLinksSnapshot(player, payload, visibleTargets, crossChunkIdentity);
	}

	/**
	 * 处理客户端近外显“最终 IO”查询请求。
	 *
	 * @param player 发起请求的服务端玩家
	 * @param payload 客户端上传的节点定位上下文
	 */
	static void handleRequestRuntimeHudSnapshot(ServerPlayer player, PairingNetwork.RequestRuntimeHudSnapshotPayload payload) {
		if (!canReceiveNearOverlayPackets(player)) {
			return;
		}
		if (isRuntimeHudRequestThrottled(player)) {
			return;
		}
		Optional<LinkNodeType> requestedType = LinkNodeSemantics.tryParseCanonicalType(payload.sourceType());
		if (requestedType.isEmpty() || payload.sourceSerial() <= 0L) {
			return;
		}

		ResolvedRuntimeHudSnapshot runtimeSnapshot = new ResolvedRuntimeHudSnapshot(false, 0, 0);
		PairableNodeBlockEntity pairableNode = resolveRequestedNode(
			player,
			payload.dimensionKey(),
			payload.blockPos(),
			requestedType.get(),
			payload.sourceSerial()
		);
		if (pairableNode != null) {
			NodeRuntimeSnapshot snapshot = NodeSnapshotQueryService
				.resolveRuntimeSnapshot(player.getServer(), pairableNode.getLinkNodeType(), pairableNode.getSerial())
				.orElse(null);
			if (snapshot != null) {
				runtimeSnapshot = new ResolvedRuntimeHudSnapshot(true, snapshot.inputPower(), snapshot.outputPower());
			}
		}

		sendRuntimeHudSnapshot(player, payload, runtimeSnapshot);
	}

	/**
	 * 解析并校验客户端请求指向的节点。
	 * <p>
	 * 只有维度一致、区块已加载、玩家距离足够近且方块实体的类型/序号完全匹配时，才允许读取运行态。
	 * </p>
	 *
	 * @param player 请求发起者
	 * @param dimensionKey 客户端上报维度键
	 * @param blockPosLong 客户端上报方块坐标
	 * @param requestedType 客户端上报语义类型
	 * @param requestedSerial 客户端上报序列号
	 * @return 通过校验的配对节点；不通过时返回 `null`
	 */
	private static PairableNodeBlockEntity resolveRequestedNode(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong,
		LinkNodeType requestedType,
		long requestedSerial
	) {
		return PairableNodeRequestValidationSupport.resolveRequestedNode(
			player,
			dimensionKey,
			blockPosLong,
			requestedType,
			requestedSerial,
			CURRENT_LINKS_REQUEST_MAX_DISTANCE
		);
	}

	/**
	 * 判断玩家是否具备接收近外显回包的最低权限。
	 */
	private static boolean canReceiveNearOverlayPackets(ServerPlayer player) {
		return player != null && player.hasPermissions(RedstoneLinkConfig.privacy().overlayResponsePermissionLevel());
	}

	/**
	 * 判断“当前连接”查询是否触发服务端节流。
	 */
	private static boolean isCurrentLinksRequestThrottled(ServerPlayer player) {
		return isOverlayRequestThrottled(
			player,
			LAST_CURRENT_LINKS_REQUEST_TICK_BY_PLAYER,
			CURRENT_LINKS_REQUEST_MIN_INTERVAL_TICKS
		);
	}

	/**
	 * 判断“最终 IO”查询是否触发服务端节流。
	 */
	private static boolean isRuntimeHudRequestThrottled(ServerPlayer player) {
		return isOverlayRequestThrottled(
			player,
			LAST_RUNTIME_HUD_REQUEST_TICK_BY_PLAYER,
			RUNTIME_HUD_REQUEST_MIN_INTERVAL_TICKS
		);
	}

	/**
	 * 判断 expected revision 是否已与当前真值不一致。
	 */
	static boolean isRevisionMismatch(long expectedRevision, long currentRevision) {
		return LinkOccSupport.isRevisionMismatch(expectedRevision, currentRevision);
	}

	/**
	 * 通用近外显请求节流：按玩家限最小 tick 间隔，并定期清理陈旧记录。
	 */
	private static boolean isOverlayRequestThrottled(
		ServerPlayer player,
		Map<UUID, Long> lastRequestTickByPlayer,
		long minIntervalTicks
	) {
		if (player == null || lastRequestTickByPlayer == null) {
			return true;
		}
		long nowTick = player.serverLevel().getGameTime();
		cleanupThrottleStateIfNeeded(nowTick);
		UUID playerId = player.getUUID();
		Long lastTick = lastRequestTickByPlayer.get(playerId);
		if (lastTick != null && isRequestInsideThrottleWindow(lastTick, nowTick, minIntervalTicks)) {
			return true;
		}
		lastRequestTickByPlayer.put(playerId, nowTick);
		return false;
	}

	/**
	 * 判断当前请求是否仍处于节流窗口内。
	 */
	static boolean isRequestInsideThrottleWindow(long lastTick, long nowTick, long minIntervalTicks) {
		long safeInterval = Math.max(1L, minIntervalTicks);
		return nowTick - lastTick < safeInterval;
	}

	/**
	 * 定期清理长时间未再请求的玩家记录，避免 UUID 表无限增长。
	 */
	private static void cleanupThrottleStateIfNeeded(long nowTick) {
		if (
			lastThrottleCleanupTick != Long.MIN_VALUE
				&& nowTick - lastThrottleCleanupTick < REQUEST_THROTTLE_CLEANUP_INTERVAL_TICKS
		) {
			return;
		}
		lastThrottleCleanupTick = nowTick;
		cleanupThrottleMap(LAST_CURRENT_LINKS_REQUEST_TICK_BY_PLAYER, nowTick);
		cleanupThrottleMap(LAST_RUNTIME_HUD_REQUEST_TICK_BY_PLAYER, nowTick);
	}

	/**
	 * 清理单个近外显请求节流表中的陈旧项。
	 */
	private static void cleanupThrottleMap(Map<UUID, Long> lastRequestTickByPlayer, long nowTick) {
		lastRequestTickByPlayer.entrySet().removeIf(entry -> nowTick - entry.getValue() > REQUEST_THROTTLE_STALE_TICKS);
	}

	/**
	 * 回包“当前连接”快照，保持请求上下文与客户端命中节点一致。
	 *
	 * @param player 回包目标玩家
	 * @param payload 原始请求
	 * @param visibleTargets 服务端按权限筛出的可见目标
	 */
	private static void sendCurrentLinksSnapshot(
		ServerPlayer player,
		PairingNetwork.RequestCurrentLinksPayload payload,
		List<Long> visibleTargets,
		CrossChunkNodeIdentity crossChunkIdentity
	) {
		ServerPlayNetworking.send(
			player,
			new PairingNetwork.CurrentLinksSnapshotPayload(
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				visibleTargets,
				crossChunkIdentity
			)
		);
	}

	/**
	 * 回包“最终 IO”快照，保持请求上下文与客户端命中节点一致。
	 *
	 * @param player 回包目标玩家
	 * @param payload 原始请求
	 * @param runtimeSnapshot 服务端解析出的运行态快照
	 */
	private static void sendRuntimeHudSnapshot(
		ServerPlayer player,
		PairingNetwork.RequestRuntimeHudSnapshotPayload payload,
		ResolvedRuntimeHudSnapshot runtimeSnapshot
	) {
		ServerPlayNetworking.send(
			player,
			new PairingNetwork.RuntimeHudSnapshotPayload(
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				runtimeSnapshot.available(),
				runtimeSnapshot.inputPower(),
				runtimeSnapshot.outputPower()
			)
		);
	}

	/**
	 * 回传单条配对反馈。
	 */
	private static void sendPairingFeedback(ServerPlayer player, LinkSetExecutionService.OperationFeedback feedback) {
		if (player == null || feedback == null || feedback.messageKey() == null || feedback.messageKey().isBlank()) {
			return;
		}
		ServerPlayNetworking.send(
			player,
			new PairingNetwork.PairingFeedbackPayload(feedback.success(), feedback.messageKey(), feedback.messageArgs())
		);
	}

	/**
	 * 回传多条配对反馈，保持服务端执行顺序。
	 */
	private static void sendPairingFeedbacks(ServerPlayer player, List<LinkSetExecutionService.OperationFeedback> feedbacks) {
		if (feedbacks == null || feedbacks.isEmpty()) {
			return;
		}
		for (LinkSetExecutionService.OperationFeedback feedback : feedbacks) {
			sendPairingFeedback(player, feedback);
		}
	}

	/**
	 * 按“core 视角编辑请求”解析 triggerSource 输入表达式。
	 */
	static CorePairingParseResult parseCorePairingTriggerSources(String rawTriggerSourceExpression) {
		CoreLinkEditingService.ParseResult parseResult = CoreLinkEditingService.parseTriggerSourcesExpression(
			rawTriggerSourceExpression
		);
		return new CorePairingParseResult(
			parseResult.orderedTriggerSources(),
			parseResult.invalidEntries(),
			parseResult.duplicateEntries(),
			parseResult.exceedLimit()
		);
	}

	/**
	 * 读取本次 core 编辑会涉及到的 triggerSource 当前目标集合。
	 */
	static Map<Long, Set<Long>> loadCurrentCoreTargetsByTriggerSource(
		LinkSavedData savedData,
		Set<Long> currentTriggerSources,
		List<Long> desiredTriggerSources
	) {
		return CoreLinkEditingService.loadCurrentTargetsByTriggerSource(savedData, currentTriggerSources, desiredTriggerSources);
	}

	/**
	 * 将“core 视角输入”转换为需要真正执行的 `triggerSource -> core` 正向覆盖目标集合。
	 * <p>
	 * 返回结果仅包含“目标集合发生变化”的 triggerSource，避免对未变化来源重复执行写入。
	 * </p>
	 */
	static LinkedHashMap<Long, Set<Long>> buildChangedCoreTargetsByTriggerSource(
		long coreSerial,
		Set<Long> currentTriggerSources,
		List<Long> desiredTriggerSources,
		Map<Long, Set<Long>> currentTargetsByTriggerSource
	) {
		return CoreLinkEditingService.buildChangedTargetsByTriggerSource(
			coreSerial,
			currentTriggerSources,
			desiredTriggerSources,
			currentTargetsByTriggerSource
		);
	}

	/**
	 * 饱和累加命令成本，避免极端批量下整数溢出。
	 */
	static int saturatingAdd(int currentCost, int nextCost) {
		return CoreLinkEditingService.saturatingAdd(currentCost, nextCost);
	}

	/**
	 * 近外显最终 IO 的服务端读模型。
	 */
	private record ResolvedRuntimeHudSnapshot(boolean available, int inputPower, int outputPower) {}

	/**
	 * core 配对输入解析结果。
	 */
	static record CorePairingParseResult(
		List<Long> orderedTriggerSources,
		List<String> invalidEntries,
		List<Long> duplicateEntries,
		boolean exceedLimit
	) {
		CorePairingParseResult {
			orderedTriggerSources = List.copyOf(orderedTriggerSources == null ? List.of() : orderedTriggerSources);
			invalidEntries = List.copyOf(invalidEntries == null ? List.of() : invalidEntries);
			duplicateEntries = List.copyOf(duplicateEntries == null ? List.of() : duplicateEntries);
		}
	}
}
