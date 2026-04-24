package com.makomi.network;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkOccSupport;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkGuiDisplayContext;
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
	private static final String QUICK_LINK_CHUNK_ACTIVATOR_TARGET_TOKEN = "chunk_activator";
	private static final String QUICK_LINK_REPEATER_TARGET_TOKEN = LinkGuiDisplayContext.LINK_REPEATER;

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

		LinkNodeType expectedNodeType = LinkNodeSemantics.tryParseCanonicalType(payload.expectedNodeTypeToken()).orElse(null);
		BlockPos blockPos = requestedNode.getBlockPos();
		QuickLinkOperationFeedback feedback = QuickLinkCollectService.collect(
			player,
			player.serverLevel(),
			blockPos,
			mainHandItem,
			expectedNodeType,
			payload.expectedNodeSerial()
		);
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
		sendApplyBaseline(player, requestedTarget, QuickLinkToolData.read(mainHandItem));
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
			sendFeedback(
				player,
				QuickLinkApplyService
					.applyToFilterFromCache(
						player,
						requestedTarget.filterBlockEntity(),
						snapshot.mode(),
						snapshot.serialCacheType(),
						snapshot.serialCacheExpression(),
						snapshot.channelCache(),
						snapshot.applyEditMode()
					)
					.feedback()
			);
			return;
		}
		if (requestedTarget.chunkActivatorBlockEntity() != null) {
			sendFeedback(
				player,
				QuickLinkApplyService
					.applyToChunkActivatorFromCache(
						player,
						requestedTarget.chunkActivatorBlockEntity(),
						snapshot.mode(),
						snapshot.serialCacheType(),
						snapshot.serialCacheExpression(),
						snapshot.channelCache(),
						snapshot.applyEditMode()
					)
					.feedback()
			);
			return;
		}
		if (requestedTarget.repeaterBlockEntity() != null) {
			sendFeedback(
				player,
				applyQuickLinkToRepeater(
					player,
					requestedTarget.repeaterBlockEntity(),
					snapshot,
					payload.expectedCoreRevision(),
					payload.expectedSourceRevision()
				)
			);
			return;
		}
		PairableNodeBlockEntity requestedNode = requestedTarget.nodeBlockEntity();
		LinkNodeType requestedNodeType = LinkNodeSemantics.tryParseCanonicalType(requestedTarget.expectedTargetToken()).orElse(null);
		sendFeedback(
			player,
			QuickLinkOccSubmissionSupport
				.submit(
					player.createCommandSourceStack(),
					player,
					player.serverLevel(),
					requestedNodeType,
					requestedTarget.expectedTargetSerial(),
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
	private static void sendApplyBaseline(
		ServerPlayer player,
		ResolvedQuickLinkApplyTarget requestedTarget,
		QuickLinkToolData.Snapshot snapshot
	) {
		if (player == null || requestedTarget == null) {
			return;
		}
		if (requestedTarget.filterBlockEntity() != null || requestedTarget.chunkActivatorBlockEntity() != null) {
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
		if (requestedTarget.repeaterBlockEntity() != null) {
			LinkNodeType occTargetType = resolveRepeaterOccTargetType(snapshot);
			LinkOccSupport.RevisionBaseline baseline = occTargetType == null
				? new LinkOccSupport.RevisionBaseline(0L, 0L, 0L)
				: LinkOccSupport.readBaseline(
					LinkSavedData.get(player.serverLevel()),
					occTargetType,
					requestedTarget.expectedTargetSerial()
				);
			ServerPlayNetworking.send(
				player,
				new QuickLinkNetwork.ApplyQuickLinkBaselinePayload(
					requestedTarget.dimensionKey(),
					requestedTarget.blockPosLong(),
					requestedTarget.expectedTargetToken(),
					requestedTarget.expectedTargetSerial(),
					baseline.graphRevision(),
					baseline.sourceRevision(),
					baseline.coreRevision()
				)
			);
			return;
		}
		PairableNodeBlockEntity requestedNode = requestedTarget.nodeBlockEntity();
		LinkNodeType requestedNodeType = LinkNodeSemantics.tryParseCanonicalType(requestedTarget.expectedTargetToken()).orElse(null);
		if (requestedNode == null || requestedNodeType == null || requestedTarget.expectedTargetSerial() <= 0L) {
			return;
		}
		LinkOccSupport.RevisionBaseline baseline = LinkOccSupport.readBaseline(
			LinkSavedData.get(player.serverLevel()),
			requestedNodeType,
			requestedTarget.expectedTargetSerial()
		);
		ServerPlayNetworking.send(
			player,
			new QuickLinkNetwork.ApplyQuickLinkBaselinePayload(
				requestedTarget.dimensionKey(),
				requestedTarget.blockPosLong(),
				requestedTarget.expectedTargetToken(),
				requestedTarget.expectedTargetSerial(),
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
			return ResolvedQuickLinkApplyTarget.forNode(requestedNode, expectedNodeType, expectedTargetSerial);
		}

		LinkFilterKind expectedFilterKind = LinkFilterKind.tryParseToken(expectedTargetToken).orElse(null);
		if (expectedFilterKind != null && expectedTargetSerial == 0L) {
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
		if (QUICK_LINK_REPEATER_TARGET_TOKEN.equals(expectedTargetToken) && expectedTargetSerial > 0L) {
			LinkRepeaterBlockEntity requestedRepeater = resolveRequestedRepeater(
				player,
				dimensionKey,
				blockPosLong,
				expectedTargetSerial
			);
			if (requestedRepeater == null) {
				sendFeedback(player, QuickLinkOperationFeedback.failure(invalidMessageKey));
				return null;
			}
			return ResolvedQuickLinkApplyTarget.forRepeater(requestedRepeater);
		}

		if (!QUICK_LINK_CHUNK_ACTIVATOR_TARGET_TOKEN.equals(expectedTargetToken) || expectedTargetSerial != 0L) {
			sendFeedback(player, QuickLinkOperationFeedback.failure(invalidMessageKey));
			return null;
		}
		LinkChunkActivatorBlockEntity requestedChunkActivator = resolveRequestedChunkActivator(
			player,
			dimensionKey,
			blockPosLong
		);
		if (requestedChunkActivator == null) {
			sendFeedback(player, QuickLinkOperationFeedback.failure(invalidMessageKey));
			return null;
		}
		return ResolvedQuickLinkApplyTarget.forChunkActivator(requestedChunkActivator);
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
	 * 校验客户端上报的转发器目标是否仍在当前服务端视图内有效。
	 */
	private static LinkRepeaterBlockEntity resolveRequestedRepeater(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong,
		long expectedTargetSerial
	) {
		if (player == null || dimensionKey == null || dimensionKey.isBlank() || expectedTargetSerial <= 0L) {
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
		if (!(blockEntity instanceof LinkRepeaterBlockEntity repeaterBlockEntity)) {
			return null;
		}
		return repeaterBlockEntity.getSerial() == expectedTargetSerial ? repeaterBlockEntity : null;
	}

	/**
	 * 校验客户端上报的区块激活器目标是否仍在当前服务端视图内有效。
	 */
	private static LinkChunkActivatorBlockEntity resolveRequestedChunkActivator(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong
	) {
		if (player == null || dimensionKey == null || dimensionKey.isBlank()) {
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
		return blockEntity instanceof LinkChunkActivatorBlockEntity chunkActivatorBlockEntity ? chunkActivatorBlockEntity : null;
	}

	/**
	 * 将 quick-link 缓存应用到转发器，并按“输入写 core / 输出写 triggerSource”做 OCC 校验。
	 */
	private static QuickLinkOperationFeedback applyQuickLinkToRepeater(
		ServerPlayer player,
		LinkRepeaterBlockEntity repeaterBlockEntity,
		QuickLinkToolData.Snapshot snapshot,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		if (player == null || repeaterBlockEntity == null || snapshot == null) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.apply.invalid_target");
		}
		LinkNodeType occTargetType = resolveRepeaterOccTargetType(snapshot);
		if (occTargetType != null) {
			LinkOccSupport.OccConflict conflict = LinkOccSupport.resolveTargetConflict(
				LinkSavedData.get(player.serverLevel()),
				occTargetType,
				repeaterBlockEntity.getSerial(),
				expectedCoreRevision,
				expectedSourceRevision
			);
			if (conflict != null) {
				return LinkOccSupport.toQuickLinkFeedback(conflict);
			}
		}
		return QuickLinkApplyService
			.applyToRepeaterFromCache(
				player,
				repeaterBlockEntity,
				snapshot.mode(),
				snapshot.serialCacheType(),
				snapshot.serialCacheExpression(),
				snapshot.channelCache(),
				snapshot.applyEditMode()
			)
			.feedback();
	}

	/**
	 * 根据缓存类型解析转发器应走哪一侧 OCC 语义。
	 * <p>
	 * `triggerSource` 缓存写输入配置，因此按 `core` 侧冲突口径校验；
	 * `core` 缓存写输出配置，因此按 `triggerSource` 侧冲突口径校验。
	 * </p>
	 */
	static LinkNodeType resolveRepeaterOccTargetType(LinkNodeType cacheType) {
		if (cacheType == LinkNodeType.TRIGGER_SOURCE) {
			return LinkNodeType.CORE;
		}
		if (cacheType == LinkNodeType.CORE) {
			return LinkNodeType.TRIGGER_SOURCE;
		}
		return null;
	}

	/**
	 * 仅在转发器可实际进入 serial 写入路径时返回 OCC 目标类型。
	 */
	private static LinkNodeType resolveRepeaterOccTargetType(QuickLinkToolData.Snapshot snapshot) {
		if (snapshot == null || snapshot.mode() != QuickLinkToolData.Mode.SERIAL) {
			return null;
		}
		return resolveRepeaterOccTargetType(snapshot.serialCacheType());
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
		AbstractLinkFilterBlockEntity filterBlockEntity,
		LinkChunkActivatorBlockEntity chunkActivatorBlockEntity,
		LinkRepeaterBlockEntity repeaterBlockEntity
	) {
		static ResolvedQuickLinkApplyTarget forNode(
			PairableNodeBlockEntity nodeBlockEntity,
			LinkNodeType expectedNodeType,
			long expectedNodeSerial
		) {
			return new ResolvedQuickLinkApplyTarget(
				nodeBlockEntity.getLevel().dimension().location().toString(),
				nodeBlockEntity.getBlockPos().asLong(),
				LinkNodeSemantics.toSemanticName(expectedNodeType),
				expectedNodeSerial,
				nodeBlockEntity,
				null,
				null,
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
				filterBlockEntity,
				null,
				null
			);
		}

		static ResolvedQuickLinkApplyTarget forChunkActivator(LinkChunkActivatorBlockEntity chunkActivatorBlockEntity) {
			return new ResolvedQuickLinkApplyTarget(
				chunkActivatorBlockEntity.getLevel().dimension().location().toString(),
				chunkActivatorBlockEntity.getBlockPos().asLong(),
				QUICK_LINK_CHUNK_ACTIVATOR_TARGET_TOKEN,
				0L,
				null,
				null,
				chunkActivatorBlockEntity,
				null
			);
		}

		static ResolvedQuickLinkApplyTarget forRepeater(LinkRepeaterBlockEntity repeaterBlockEntity) {
			return new ResolvedQuickLinkApplyTarget(
				repeaterBlockEntity.getLevel().dimension().location().toString(),
				repeaterBlockEntity.getBlockPos().asLong(),
				QUICK_LINK_REPEATER_TARGET_TOKEN,
				repeaterBlockEntity.getSerial(),
				null,
				null,
				null,
				repeaterBlockEntity
			);
		}
	}
}
