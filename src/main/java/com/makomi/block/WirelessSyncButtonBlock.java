package com.makomi.block;

import com.makomi.block.entity.WirelessSyncTriggerSourceBlockEntity;
import com.makomi.block.entity.WirelessVariantSyncTriggerSourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化同步按钮基类。
 * <p>
 * 保留原版按钮的按下、回弹与木按钮箭矢触发节奏，
 * 但不再对邻居红石网络供电，只把当前信号强度同步转发给已连接 core。
 * </p>
 */
public class WirelessSyncButtonBlock extends ButtonBlock implements EntityBlock {
	private final int ticksToStayPressed;
	private final BlockSetType blockSetType;

	public WirelessSyncButtonBlock(
		BlockSetType blockSetType,
		int ticksToStayPressed,
		BlockBehaviour.Properties properties
	) {
		super(blockSetType, ticksToStayPressed, properties);
		this.blockSetType = blockSetType;
		this.ticksToStayPressed = ticksToStayPressed;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessVariantSyncTriggerSourceBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.TRIGGER_SOURCE,
			WirelessVariantSyncTriggerSourceBlockEntity.class
		);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessVariantSyncTriggerSourceBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(
			this,
			pos,
			newState.getBlock(),
			level,
			WirelessVariantSyncTriggerSourceBlockEntity.class
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
		return super.useWithoutItem(state, level, pos, player, hitResult);
	}

	@Override
	public void press(BlockState state, Level level, BlockPos pos, Player player) {
		BlockState updatedState = state.setValue(POWERED, true);
		level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
		playSound(level, pos, true);
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
		if (blockSetType.canButtonBeActivatedByArrows()) {
			checkPressed(state, level, pos);
			return;
		}
		BlockState updatedState = state.setValue(POWERED, false);
		level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
		playSound(level, pos, false);
		level.gameEvent(null, GameEvent.BLOCK_DEACTIVATE, pos);
		forwardSignal(level, pos, null, 0);
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		if (level.isClientSide || !blockSetType.canButtonBeActivatedByArrows() || state.getValue(POWERED)) {
			return;
		}
		checkPressed(state, level, pos);
	}

	@Override
	protected void checkPressed(BlockState state, Level level, BlockPos pos) {
		AbstractArrow firstArrow = null;
		if (blockSetType.canButtonBeActivatedByArrows()) {
			AABB arrowCheckBox = state.getShape(level, pos).bounds().move(pos);
			firstArrow = level.getEntitiesOfClass(AbstractArrow.class, arrowCheckBox).stream().findFirst().orElse(null);
		}
		boolean shouldStayPowered = firstArrow != null;
		boolean wasPowered = state.getValue(POWERED);
		if (shouldStayPowered != wasPowered) {
			BlockState updatedState = state.setValue(POWERED, shouldStayPowered);
			level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
			playSound(level, pos, shouldStayPowered);
			level.gameEvent(firstArrow, shouldStayPowered ? GameEvent.BLOCK_ACTIVATE : GameEvent.BLOCK_DEACTIVATE, pos);
			forwardSignal(level, pos, null, shouldStayPowered ? 15 : 0);
		}
		if (shouldStayPowered) {
			level.scheduleTick(new BlockPos(pos), this, ticksToStayPressed);
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

	/**
	 * 播放原版按钮音效，但不通知邻居。
	 */
	protected void playSound(Level level, BlockPos pos, boolean powered) {
		level.playSound(null, pos, getSound(powered), SoundSource.BLOCKS);
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
