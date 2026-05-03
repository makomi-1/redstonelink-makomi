package com.makomi.block;

import com.makomi.block.entity.WirelessStonePressurePlateBlockEntity;
import com.makomi.block.entity.WirelessSyncTriggerSourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化石压力板。
 * <p>
 * 保留原版受压触发与自动回弹逻辑，
 * 但状态变化只同步转发到已连接 `core`，不再对邻居红石网络供电。
 * </p>
 */
public class WirelessStonePressurePlateBlock extends PressurePlateBlock implements EntityBlock {
	public WirelessStonePressurePlateBlock(BlockBehaviour.Properties properties) {
		super(BlockSetType.STONE, properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessStonePressurePlateBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.TRIGGER_SOURCE,
			WirelessStonePressurePlateBlockEntity.class
		);
	}

	@Override
	protected java.util.List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessStonePressurePlateBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(
			this,
			pos,
			newState.getBlock(),
			level,
			WirelessStonePressurePlateBlockEntity.class
		);
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
		return InteractionResult.PASS;
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		if (level.isClientSide || getSignalForState(state) != 0) {
			return;
		}
		refreshWirelessPressedState(level, pos, state, entity);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (getSignalForState(state) <= 0) {
			return;
		}
		refreshWirelessPressedState(level, pos, state, null);
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

	/**
	 * 按当前实体压板占用情况刷新状态，但不通知邻居红石网络。
	 */
	private void refreshWirelessPressedState(Level level, BlockPos pos, BlockState state, Entity triggerEntity) {
		int previousSignal = getSignalForState(state);
		int currentSignal = getSignalStrength(level, pos);
		boolean wasPowered = previousSignal > 0;
		boolean isPowered = currentSignal > 0;
		if (previousSignal != currentSignal) {
			BlockState updatedState = setSignalForState(state, currentSignal);
			level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
			level.setBlocksDirty(pos, state, updatedState);
			forwardSignal(level, pos, currentSignal);
		}
		if (!isPowered && wasPowered) {
			level.playSound(null, pos, type.pressurePlateClickOff(), SoundSource.BLOCKS);
			level.gameEvent(triggerEntity, GameEvent.BLOCK_DEACTIVATE, pos);
		} else if (isPowered && !wasPowered) {
			level.playSound(null, pos, type.pressurePlateClickOn(), SoundSource.BLOCKS);
			level.gameEvent(triggerEntity, GameEvent.BLOCK_ACTIVATE, pos);
		}
		if (isPowered) {
			level.scheduleTick(pos, this, getPressedTime());
		}
	}

	/**
	 * 同步记录并转发当前压力板信号。
	 */
	private static void forwardSignal(Level level, BlockPos pos, int signalStrength) {
		if (!(level.getBlockEntity(pos) instanceof WirelessSyncTriggerSourceBlockEntity blockEntity)) {
			return;
		}
		blockEntity.setLastObservedSignalStrength(signalStrength);
		blockEntity.forwardLinkedSignal(null, signalStrength);
	}
}
