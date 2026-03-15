package com.makomi.block.entity;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 核心红石粉节点方块实体。
 * <p>
 * 负责把 active 状态同步到 {@link LinkRedstoneDustCoreBlock}，
 * 并在侧/底附着场景补发邻居更新。
 * </p>
 */
public class LinkRedstoneDustCoreBlockEntity extends ActivatableTargetBlockEntity {
	private boolean topActiveSyncPending;
	private boolean topActiveSyncTarget;
	private long topActiveNextSyncGameTime = Long.MIN_VALUE;
	private long topActiveLastAppliedGameTime = Long.MIN_VALUE;

	public LinkRedstoneDustCoreBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_REDSTONE_DUST_CORE, blockPos, blockState);
	}

	protected LinkRedstoneDustCoreBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected LinkNodeType getNodeType() {
		return LinkNodeType.CORE;
	}

	@Override
	protected void onActiveChanged(boolean active) {
		BlockState state = level.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof LinkRedstoneDustCoreBlock coreBlock)) {
			return;
		}

		// 顶面逻辑完全委托方块实现（含原版线网兼容）。
		coreBlock.onCoreActivationStateChanged(level, worldPosition);

		BlockState updatedState = level.getBlockState(worldPosition);
		if (!(updatedState.getBlock() instanceof LinkRedstoneDustCoreBlock)) {
			return;
		}

		// 侧/底附着额外补一次邻居刷新，确保输出立刻可见。
		if (updatedState.getValue(LinkRedstoneDustCoreBlock.SUPPORT_FACE) == Direction.DOWN) {
			return;
		}

		Block block = updatedState.getBlock();
		level.updateNeighborsAt(worldPosition, block);
		for (Direction direction : Direction.values()) {
			level.updateNeighborsAt(worldPosition.relative(direction), block);
		}
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			// 复用方块 tick 回调做脉冲回落。
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), pulseTicks);
		}
	}

	/**
	 * 请求顶面 ACTIVE 状态客户端同步（节流）。
	 * <p>
	 * 用于 F3 可观测态；同窗口内仅保留最后一次目标值。
	 * </p>
	 *
	 * @param targetActive 目标 ACTIVE 状态
	 */
	public void requestTopActiveClientSync(boolean targetActive) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		BlockState state = serverLevel.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof LinkRedstoneDustCoreBlock)) {
			clearTopActiveSyncRequest();
			return;
		}

		boolean currentActive = state.getValue(LinkRedstoneDustCoreBlock.ACTIVE);
		if (currentActive == targetActive) {
			// 目标值已落地，清理可能残留的历史待同步请求。
			clearTopActiveSyncRequest();
			return;
		}

		topActiveSyncPending = true;
		topActiveSyncTarget = targetActive;

		long now = serverLevel.getGameTime();
		int throttleTicks = Math.max(1, RedstoneLinkConfig.topActiveSyncThrottleTicks());
		long earliestDueTick = topActiveLastAppliedGameTime == Long.MIN_VALUE
			? now
			: topActiveLastAppliedGameTime + throttleTicks;
		long dueTick = Math.max(now + 1L, earliestDueTick);
		if (topActiveNextSyncGameTime == Long.MIN_VALUE || dueTick < topActiveNextSyncGameTime) {
			topActiveNextSyncGameTime = dueTick;
		}

		int delay = (int) Math.max(1L, topActiveNextSyncGameTime - now);
		serverLevel.scheduleTick(worldPosition, state.getBlock(), delay);
	}

	/**
	 * 在方块 tick 中处理顶面 ACTIVE 的延迟客户端同步。
	 */
	public void flushTopActiveClientSyncIfDue() {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!topActiveSyncPending) {
			return;
		}
		if (topActiveNextSyncGameTime == Long.MIN_VALUE) {
			topActiveNextSyncGameTime = serverLevel.getGameTime();
		}
		long now = serverLevel.getGameTime();
		if (now < topActiveNextSyncGameTime) {
			BlockState state = serverLevel.getBlockState(worldPosition);
			if (state.getBlock() instanceof LinkRedstoneDustCoreBlock) {
				int delay = (int) Math.max(1L, topActiveNextSyncGameTime - now);
				serverLevel.scheduleTick(worldPosition, state.getBlock(), delay);
			}
			return;
		}

		BlockState state = serverLevel.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof LinkRedstoneDustCoreBlock)) {
			clearTopActiveSyncRequest();
			return;
		}

		if (state.getValue(LinkRedstoneDustCoreBlock.ACTIVE) != topActiveSyncTarget) {
			serverLevel.setBlock(
				worldPosition,
				state.setValue(LinkRedstoneDustCoreBlock.ACTIVE, topActiveSyncTarget),
				Block.UPDATE_CLIENTS
			);
		}
		topActiveLastAppliedGameTime = now;
		clearTopActiveSyncRequest();
	}

	private void clearTopActiveSyncRequest() {
		topActiveSyncPending = false;
		topActiveNextSyncGameTime = Long.MIN_VALUE;
	}


}
