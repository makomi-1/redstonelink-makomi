package com.makomi.block;

import com.makomi.block.entity.WirelessStoneButtonBlockEntity;
import com.makomi.block.entity.WirelessSyncTriggerSourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化石按钮。
 * <p>
 * 保留原版石按钮的按下/回弹节奏，
 * 但不再给邻居供电，只把 `15 -> 0` 的脉冲同步转发到已连接 `core`。
 * </p>
 */
public class WirelessStoneButtonBlock extends ButtonBlock implements EntityBlock {
	private final int ticksToStayPressed;

	public WirelessStoneButtonBlock(BlockBehaviour.Properties properties) {
		this(BlockSetType.STONE, 20, properties);
	}

	protected WirelessStoneButtonBlock(
		BlockSetType blockSetType,
		int ticksToStayPressed,
		BlockBehaviour.Properties properties
	) {
		super(blockSetType, ticksToStayPressed, properties);
		this.ticksToStayPressed = ticksToStayPressed;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessStoneButtonBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.TRIGGER_SOURCE,
			WirelessStoneButtonBlockEntity.class
		);
	}

	@Override
	protected java.util.List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessStoneButtonBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(this, pos, newState.getBlock(), level, WirelessStoneButtonBlockEntity.class);
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
	public void press(BlockState state, Level level, BlockPos pos, Player player) {
		BlockState updatedState = state.setValue(POWERED, true);
		level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
		playSound(player, level, pos, true);
		if (level.isClientSide) {
			return;
		}
		level.scheduleTick(pos, this, ticksToStayPressed);
		level.gameEvent(player, GameEvent.BLOCK_ACTIVATE, pos);
		forwardSignal(level, pos, player, 15);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (!state.getValue(POWERED)) {
			return;
		}
		BlockState updatedState = state.setValue(POWERED, false);
		level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
		playSound(null, level, pos, false);
		level.gameEvent(null, GameEvent.BLOCK_DEACTIVATE, pos);
		forwardSignal(level, pos, null, 0);
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
	 * 同步记录并转发当前按钮信号。
	 */
	private static void forwardSignal(Level level, BlockPos pos, Player player, int signalStrength) {
		if (!(level.getBlockEntity(pos) instanceof WirelessSyncTriggerSourceBlockEntity blockEntity)) {
			return;
		}
		blockEntity.setLastObservedSignalStrength(signalStrength);
		blockEntity.forwardLinkedSignal(player, signalStrength);
	}
}
