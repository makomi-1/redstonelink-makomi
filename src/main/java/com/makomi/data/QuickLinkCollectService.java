package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 快速连接工具服务端采集服务。
 */
public final class QuickLinkCollectService {
	private QuickLinkCollectService() {
	}

	/**
	 * 将当前命中节点采集到工具缓存。
	 */
	public static QuickLinkOperationFeedback collect(ServerPlayer player, ServerLevel level, BlockPos blockPos, ItemStack stack) {
		if (player == null || level == null || blockPos == null || stack == null || stack.isEmpty()) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.collect.invalid_target");
		}

		BlockEntity blockEntity = level.getBlockEntity(blockPos);
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.collect.invalid_target");
		}
		if (pairableNodeBlockEntity.getLinkNodeType() == null || pairableNodeBlockEntity.getSerial() <= 0L) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.collect.invalid_target");
		}

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(stack);
		if (snapshot.mode() == QuickLinkToolData.Mode.CHANNEL) {
			return QuickLinkOperationFeedback.failure("message.redstonelink.quick_link.mode.channel_future");
		}

		QuickLinkToolData.SerialCollectOutcome outcome = QuickLinkToolData.collectSerial(
			stack,
			pairableNodeBlockEntity.getLinkNodeType(),
			pairableNodeBlockEntity.getSerial()
		);

		return switch (outcome.action()) {
			case REPLACED -> QuickLinkOperationFeedback.success(
				"message.redstonelink.quick_link.collect.serial.replaced",
				LinkNodeSemantics.toSemanticName(outcome.serialCacheType()),
				Long.toString(outcome.collectedSerial()),
				Integer.toString(outcome.serialCount())
			);
			case APPENDED -> QuickLinkOperationFeedback.success(
				"message.redstonelink.quick_link.collect.serial.added",
				LinkNodeSemantics.toSemanticName(outcome.serialCacheType()),
				Long.toString(outcome.collectedSerial()),
				Integer.toString(outcome.serialCount())
			);
			case DUPLICATE -> QuickLinkOperationFeedback.success(
				"message.redstonelink.quick_link.collect.serial.duplicate",
				LinkNodeSemantics.toSemanticName(outcome.serialCacheType()),
				Long.toString(outcome.collectedSerial()),
				Integer.toString(outcome.serialCount())
			);
		};
	}
}
