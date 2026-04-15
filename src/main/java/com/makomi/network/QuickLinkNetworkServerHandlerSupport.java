package com.makomi.network;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.QuickLinkApplyService;
import com.makomi.data.QuickLinkCollectService;
import com.makomi.data.QuickLinkOccSubmissionSupport;
import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

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
				payload.channelCache(),
				payload.applyEditModeToken()
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
		ResolvedQuickLinkApplyTarget requestedTarget = resolveRequestedApplyTarget(
			player,
			payload.dimensionKey(),
			payload.blockPosLong(),
			payload.expectedNodeTypeToken(),
			payload.expectedNodeSerial(),
			"message.redstonelink.quick_link.apply.invalid_target"
		);
		if (requestedTarget == null) {
			return;
		}
		sendApplyBaseline(player, requestedTarget);
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
		ResolvedQuickLinkApplyTarget requestedTarget = resolveRequestedApplyTarget(
			player,
			payload.dimensionKey(),
			payload.blockPosLong(),
			payload.expectedNodeTypeToken(),
			payload.expectedNodeSerial(),
			"message.redstonelink.quick_link.apply.invalid_target"
		);
		if (requestedTarget == null) {
			return;
		}
		if (requestedTarget.filterBlockEntity() != null) {
			if (snapshot.mode() == QuickLinkToolData.Mode.CHANNEL) {
				sendFeedback(player, QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.channel_filter_unsupported"));
				return;
			}
			sendFeedback(
				player,
				QuickLinkApplyService
					.applyToFilterFromCache(
						player,
						requestedTarget.filterBlockEntity(),
						snapshot.serialCacheType(),
						snapshot.serialCacheExpression(),
						snapshot.applyEditMode()
					)
					.feedback()
			);
			return;
		}
		PairableNodeBlockEntity requestedNode = requestedTarget.nodeBlockEntity();
		sendFeedback(
			player,
			QuickLinkOccSubmissionSupport
				.submit(
					player.createCommandSourceStack(),
					player,
					player.serverLevel(),
					requestedNode.getLinkNodeType(),
					requestedNode.getSerial(),
					snapshot.mode(),
					snapshot.serialCacheType(),
					snapshot.serialCacheExpression(),
					snapshot.channelCache(),
					snapshot.applyEditMode(),
					payload.expectedCoreRevision(),
					payload.expectedSourceRevision()
				)
				.feedback()
		);
	}

	/**
	 * 基于命中节点回传 quick-link apply 所需的 revision 基线。
	 */
	private static void sendApplyBaseline(ServerPlayer player, ResolvedQuickLinkApplyTarget requestedTarget) {
		if (player == null || requestedTarget == null) {
			return;
		}
		if (requestedTarget.filterBlockEntity() != null) {
			ServerPlayNetworking.send(
				player,
				new QuickLinkNetwork.ApplyQuickLinkBaselinePayload(
					requestedTarget.dimensionKey(),
					requestedTarget.blockPosLong(),
					requestedTarget.expectedTargetToken(),
					requestedTarget.expectedTargetSerial(),
					0L,
					0L,
					0L
				)
			);
			return;
		}
		PairableNodeBlockEntity requestedNode = requestedTarget.nodeBlockEntity();
		if (requestedNode == null || requestedNode.getLinkNodeType() == null || requestedNode.getSerial() <= 0L) {
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
	 * 解析并校验 quick-link 右键 apply 指向的目标，可为节点或过滤器。
	 */
	private static ResolvedQuickLinkApplyTarget resolveRequestedApplyTarget(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong,
		String expectedTargetToken,
		long expectedTargetSerial,
		String invalidMessageKey
	) {
		LinkNodeType expectedNodeType = LinkNodeSemantics.tryParseCanonicalType(expectedTargetToken).orElse(null);
		if (expectedNodeType != null && expectedTargetSerial > 0L) {
			PairableNodeBlockEntity requestedNode = PairableNodeRequestValidationSupport.resolveRequestedNode(
				player,
				dimensionKey,
				blockPosLong,
				expectedNodeType,
				expectedTargetSerial,
				QUICK_LINK_REQUEST_MAX_DISTANCE
			);
			if (requestedNode == null) {
				sendFeedback(player, QuickLinkOperationFeedback.failure(invalidMessageKey));
				return null;
			}
			return ResolvedQuickLinkApplyTarget.forNode(requestedNode);
		}

		LinkFilterKind expectedFilterKind = LinkFilterKind.tryParseToken(expectedTargetToken).orElse(null);
		if (expectedFilterKind == null || expectedTargetSerial != 0L) {
			sendFeedback(player, QuickLinkOperationFeedback.failure(invalidMessageKey));
			return null;
		}
		AbstractLinkFilterBlockEntity requestedFilter = resolveRequestedFilter(
			player,
			dimensionKey,
			blockPosLong,
			expectedFilterKind
		);
		if (requestedFilter == null) {
			sendFeedback(player, QuickLinkOperationFeedback.failure(invalidMessageKey));
			return null;
		}
		return ResolvedQuickLinkApplyTarget.forFilter(requestedFilter);
	}

	/**
	 * 校验客户端上报的过滤器目标是否仍在当前服务端视图内有效。
	 */
	private static AbstractLinkFilterBlockEntity resolveRequestedFilter(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong,
		LinkFilterKind expectedFilterKind
	) {
		if (player == null || expectedFilterKind == null || dimensionKey == null || dimensionKey.isBlank()) {
			return null;
		}
		ServerLevel serverLevel = player.serverLevel();
		if (serverLevel == null || !serverLevel.dimension().location().toString().equals(dimensionKey)) {
			return null;
		}

		BlockPos blockPos = BlockPos.of(blockPosLong);
		if (!serverLevel.isLoaded(blockPos)) {
			return null;
		}
		if (
			!PairableNodeRequestValidationSupport.isWithinInteractionDistance(
				player.getX(),
				player.getY(),
				player.getZ(),
				blockPos,
				QUICK_LINK_REQUEST_MAX_DISTANCE
			)
		) {
			return null;
		}

		BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);
		if (!(blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity)) {
			return null;
		}
		return filterBlockEntity.filterKind() == expectedFilterKind ? filterBlockEntity : null;
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

	/**
	 * quick-link apply 目标的服务端已校验快照。
	 */
	private record ResolvedQuickLinkApplyTarget(
		String dimensionKey,
		long blockPosLong,
		String expectedTargetToken,
		long expectedTargetSerial,
		PairableNodeBlockEntity nodeBlockEntity,
		AbstractLinkFilterBlockEntity filterBlockEntity
	) {
		static ResolvedQuickLinkApplyTarget forNode(PairableNodeBlockEntity nodeBlockEntity) {
			return new ResolvedQuickLinkApplyTarget(
				nodeBlockEntity.getLevel().dimension().location().toString(),
				nodeBlockEntity.getBlockPos().asLong(),
				LinkNodeSemantics.toSemanticName(nodeBlockEntity.getLinkNodeType()),
				nodeBlockEntity.getSerial(),
				nodeBlockEntity,
				null
			);
		}

		static ResolvedQuickLinkApplyTarget forFilter(AbstractLinkFilterBlockEntity filterBlockEntity) {
			return new ResolvedQuickLinkApplyTarget(
				filterBlockEntity.getLevel().dimension().location().toString(),
				filterBlockEntity.getBlockPos().asLong(),
				filterBlockEntity.filterKind().token(),
				0L,
				null,
				filterBlockEntity
			);
		}
	}
}
