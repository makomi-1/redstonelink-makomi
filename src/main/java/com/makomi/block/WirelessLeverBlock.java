package com.makomi.block;

import com.makomi.block.entity.WirelessLeverBlockEntity;
import com.makomi.block.entity.WirelessSyncTriggerSourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化拉杆。
 * <p>
 * 保留原版拨杆的交互手感与 `POWERED` 外显，
 * 但不再向邻居输出红石，仅把当前开关状态同步转接到已连接 `core`。
 * </p>
 */
public class WirelessLeverBlock extends LeverBlock implements EntityBlock {
	public WirelessLeverBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessLeverBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.TRIGGER_SOURCE,
			WirelessLeverBlockEntity.class
		);
	}

	@Override
	protected java.util.List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessLeverBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(this, pos, newState.getBlock(), level, WirelessLeverBlockEntity.class);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected InteractionResult useWithoutItem(
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		BlockHitResult hitResult
	) {
		if (RedstoneLinkConfig.canOpenPairingByPlacedBlock(player)) {
			WirelessBlockNodeSupport.openTriggerSourcePairing(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return super.useWithoutItem(state, level, pos, player, hitResult);
	}

	@Override
	public void pull(BlockState state, Level level, BlockPos pos, Player player) {
		BlockState updatedState = state.cycle(POWERED);
		level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
		playSound(player, level, pos, updatedState);
		if (level.isClientSide) {
			return;
		}
		int signalStrength = updatedState.getValue(POWERED) ? 15 : 0;
		level.gameEvent(player, updatedState.getValue(POWERED) ? GameEvent.BLOCK_ACTIVATE : GameEvent.BLOCK_DEACTIVATE, pos);
		if (level.getBlockEntity(pos) instanceof WirelessSyncTriggerSourceBlockEntity blockEntity) {
			blockEntity.setLastObservedSignalStrength(signalStrength);
			blockEntity.forwardLinkedSignal(player, signalStrength);
		}
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return false;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return 0;
	}
}
