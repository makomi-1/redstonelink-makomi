package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 智能节点容器受控回收支撑。
 * <p>
 * 负责把“节点方块 -> 节点掉落物 -> 容器槽位/地面掉落物”的保留链路统一收口。
 * </p>
 */
public final class SmartNodeContainerRecoverySupport {
	private SmartNodeContainerRecoverySupport() {
	}

	/**
	 * 判断当前命中的方块是否属于节点容器本轮支持的可回收节点。
	 */
	public static boolean isRecoverableNode(BlockState state, BlockEntity blockEntity) {
		if (!(blockEntity instanceof PairableNodeBlockEntity)) {
			return false;
		}
		ItemStack blockItemStack = state == null ? ItemStack.EMPTY : new ItemStack(state.getBlock().asItem());
		return SmartNodeContainerData.isAllowedNodeItem(blockItemStack);
	}

	/**
	 * 受控回收一个已放置节点。
	 */
	public static RecoveryResult recoverNodeIntoContainer(
		ServerLevel level,
		Player player,
		ItemStack containerStack,
		NonNullList<ItemStack> currentContents,
		BlockPos pos,
		BlockState state,
		BlockEntity blockEntity
	) {
		if (
			level == null ||
			player == null ||
			containerStack == null ||
			currentContents == null ||
			pos == null ||
			!isRecoverableNode(state, blockEntity)
		) {
			return RecoveryResult.failed(currentContents);
		}

		List<NodeIdentity> nodeIdentities = collectNodeIdentities((PairableNodeBlockEntity) blockEntity);
		List<ItemStack> resolvedDrops = Block.getDrops(state, level, pos, blockEntity, player, containerStack.copy());
		if (resolvedDrops.isEmpty()) {
			return RecoveryResult.failed(currentContents);
		}

		NonNullList<ItemStack> updatedContents = SmartNodeContainerData.copyContents(currentContents);
		List<ItemStack> overflowDrops = new ArrayList<>();
		int insertedCount = 0;
		for (ItemStack resolvedDrop : resolvedDrops) {
			if (resolvedDrop == null || resolvedDrop.isEmpty()) {
				continue;
			}
			ItemStack preservedDrop = resolvedDrop.copy();
			if (SmartNodeContainerData.tryInsertNodeItem(updatedContents, preservedDrop)) {
				insertedCount++;
			} else {
				overflowDrops.add(preservedDrop);
			}
		}
		if (insertedCount <= 0 && overflowDrops.isEmpty()) {
			return RecoveryResult.failed(currentContents);
		}
		if (!level.removeBlock(pos, false)) {
			return RecoveryResult.failed(currentContents);
		}

		for (NodeIdentity nodeIdentity : nodeIdentities) {
			LinkNodeRetireEvents.cancelPendingRetire(level, nodeIdentity.nodeType(), nodeIdentity.serial());
		}
		for (ItemStack overflowDrop : overflowDrops) {
			if (overflowDrop != null && !overflowDrop.isEmpty()) {
				Block.popResource(level, pos, overflowDrop);
			}
		}
		return new RecoveryResult(true, updatedContents, insertedCount, overflowDrops.size());
	}

	/**
	 * 采集当前节点所承载的全部待退役身份。
	 */
	private static List<NodeIdentity> collectNodeIdentities(PairableNodeBlockEntity blockEntity) {
		List<NodeIdentity> nodeIdentities = new ArrayList<>(2);
		blockEntity.forEachNodeIdentity((nodeType, serial) -> {
			if (nodeType != null && serial > 0L) {
				nodeIdentities.add(new NodeIdentity(nodeType, serial));
			}
		});
		return nodeIdentities;
	}

	/**
	 * 单个节点身份。
	 */
	private record NodeIdentity(LinkNodeType nodeType, long serial) {}

	/**
	 * 受控回收结果。
	 */
	public record RecoveryResult(
		boolean recovered,
		NonNullList<ItemStack> updatedContents,
		int insertedCount,
		int droppedCount
	) {
		private static RecoveryResult failed(NonNullList<ItemStack> currentContents) {
			return new RecoveryResult(false, SmartNodeContainerData.copyContents(currentContents), 0, 0);
		}
	}
}
