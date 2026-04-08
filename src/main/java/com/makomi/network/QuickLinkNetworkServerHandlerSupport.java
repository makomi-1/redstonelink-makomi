package com.makomi.network;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.QuickLinkCollectService;
import com.makomi.data.QuickLinkOccSubmissionSupport;
import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 快速连接工具服务端接包处理壳。
 */
final class QuickLinkNetworkServerHandlerSupport {
	private static final int QUICK_LINK_REQUEST_MAX_DISTANCE = PairableNodeRequestValidationSupport.DEFAULT_MAX_INTERACTION_DISTANCE;

	private QuickLinkNetworkServerHandlerSupport() {
	}

	/**
	 * 处理客户端缓存保存请求。
	 */
	static void handleSaveQuickLink(ServerPlayer player, QuickLinkNetwork.SaveQuickLinkPayload payload) {
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof QuickLinkToolItem)) {
			return;
		}

		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (
			isInputTooLong(payload.serialCacheExpression(), maxInputLength)
				|| isInputTooLong(payload.channelCache(), maxInputLength)
		) {
			sendFeedback(
				player,
				QuickLinkOperationFeedback.failure("message.redstonelink.link.set.input_too_long", Integer.toString(maxInputLength))
			);
			return;
		}

		QuickLinkToolData.write(
			mainHandItem,
			QuickLinkToolData.fromTokens(
				payload.modeToken(),
				payload.serialCacheTypeToken(),
				payload.serialCacheExpression(),
				payload.channelCache()
			)
		);
		player.containerMenu.broadcastChanges();
	}

	/**
	 * 处理客户端左键采集请求。
	 */
	static void handleCollectQuickLink(ServerPlayer player, QuickLinkNetwork.CollectQuickLinkPayload payload) {
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof QuickLinkToolItem)) {
			return;
		}
		PairableNodeBlockEntity requestedNode = resolveRequestedNode(
			player,
			payload.dimensionKey(),
			payload.blockPosLong(),
			payload.expectedNodeTypeToken(),
			payload.expectedNodeSerial(),
			"message.redstonelink.quick_link.collect.invalid_target"
		);
		if (requestedNode == null) {
			return;
		}

		BlockPos blockPos = requestedNode.getBlockPos();
		QuickLinkOperationFeedback feedback = QuickLinkCollectService.collect(player, player.serverLevel(), blockPos, mainHandItem);
		if (feedback.success()) {
			player.containerMenu.broadcastChanges();
		}
		sendFeedback(player, feedback);
	}

	/**
	 * 处理客户端右键应用请求。
	 */
	static void handleRequestApplyQuickLinkBaseline(
		ServerPlayer player,
		QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload payload
	) {
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof QuickLinkToolItem)) {
			return;
		}
		PairableNodeBlockEntity requestedNode = resolveRequestedNode(
			player,
			payload.dimensionKey(),
			payload.blockPosLong(),
			payload.expectedNodeTypeToken(),
			payload.expectedNodeSerial(),
			"message.redstonelink.quick_link.apply.invalid_target"
		);
		if (requestedNode == null) {
			return;
		}
		sendApplyBaseline(player, requestedNode);
	}

	/**
	 * 处理客户端右键应用请求。
	 */
	static void handleApplyQuickLink(ServerPlayer player, QuickLinkNetwork.ApplyQuickLinkPayload payload) {
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof QuickLinkToolItem)) {
			return;
		}
		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(mainHandItem);
		if (snapshot.mode() == QuickLinkToolData.Mode.CHANNEL) {
			sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.mode.channel_future"));
			return;
		}
		PairableNodeBlockEntity requestedNode = resolveRequestedNode(
			player,
			payload.dimensionKey(),
			payload.blockPosLong(),
			payload.expectedNodeTypeToken(),
			payload.expectedNodeSerial(),
			"message.redstonelink.quick_link.apply.invalid_target"
		);
		if (requestedNode == null) {
			return;
		}
		sendFeedback(
			player,
			QuickLinkOccSubmissionSupport
				.submit(
					player.createCommandSourceStack(),
					player,
					player.serverLevel(),
					requestedNode.getLinkNodeType(),
					requestedNode.getSerial(),
					snapshot.serialCacheType(),
					snapshot.serialCacheExpression(),
					payload.expectedCoreRevision(),
					payload.expectedSourceRevision()
				)
				.feedback()
		);
	}

	/**
	 * 基于命中节点回传 quick-link apply 所需的 revision 基线。
	 */
	private static void sendApplyBaseline(ServerPlayer player, PairableNodeBlockEntity requestedNode) {
		if (player == null || requestedNode == null || requestedNode.getLinkNodeType() == null || requestedNode.getSerial() <= 0L) {
			return;
		}
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(
			LinkSavedData.get(player.serverLevel()),
			requestedNode.getLinkNodeType(),
			requestedNode.getSerial()
		);
		ServerPlayNetworking.send(
			player,
			new QuickLinkNetwork.ApplyQuickLinkBaselinePayload(
				requestedNode.getLevel().dimension().location().toString(),
				requestedNode.getBlockPos().asLong(),
				LinkNodeSemantics.toSemanticName(requestedNode.getLinkNodeType()),
				requestedNode.getSerial(),
				baseline.graphRevision(),
				baseline.sourceRevision(),
				baseline.coreRevision()
			)
		);
	}

	/**
	 * 将 quick-link 结果回传给客户端 HUD。
	 */
	private static void sendFeedback(ServerPlayer player, QuickLinkOperationFeedback result) {
		ServerPlayNetworking.send(
			player,
			new QuickLinkNetwork.QuickLinkFeedbackPayload(result.success(), result.messageKey(), result.messageArgs())
		);
	}

	/**
	 * 判断输入是否超过服务端配置上限。
	 */
	private static boolean isInputTooLong(String input, int maxInputLength) {
		return input != null && input.length() > maxInputLength;
	}

	/**
	 * 解析并校验 quick-link 请求指向的节点。
	 */
	private static PairableNodeBlockEntity resolveRequestedNode(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		String invalidMessageKey
	) {
		LinkNodeType expectedNodeType = LinkNodeSemantics.tryParseCanonicalType(expectedNodeTypeToken).orElse(null);
		PairableNodeBlockEntity requestedNode = PairableNodeRequestValidationSupport.resolveRequestedNode(
			player,
			dimensionKey,
			blockPosLong,
			expectedNodeType,
			expectedNodeSerial,
			QUICK_LINK_REQUEST_MAX_DISTANCE
		);
		if (requestedNode == null) {
			sendFeedback(player, QuickLinkOperationFeedback.failure(invalidMessageKey));
		}
		return requestedNode;
	}

	/**
	 * 按目标节点语义构造 quick-link apply 的 revision 冲突反馈。
	 * <p>
	 * `triggerSource` 目标只比较 `sourceRevision`；`core` 目标只比较 `graphRevision`。
	 * </p>
	 */
	static QuickLinkOperationFeedback buildApplyRevisionConflictFeedback(
		LinkNodeType targetNodeType,
		long targetNodeSerial,
		long expectedCoreRevision,
		long expectedSourceRevision,
		long currentGraphRevision,
		long currentSourceRevision,
		long currentCoreRevision
	) {
		LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveTargetConflictWithCurrentBaseline(
			targetNodeType,
			targetNodeSerial,
			expectedCoreRevision,
			expectedSourceRevision,
			new LinkOccSupport.RevisionBaseline(currentGraphRevision, currentSourceRevision, currentCoreRevision)
		);
		return conflict == null ? null : LinkOccSupport.toQuickLinkFeedback(conflict);
	}
}
