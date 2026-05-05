package com.makomi.block;

import com.makomi.block.entity.WirelessOpenableCoreBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化活板门。
 * <p>
 * 保留原版活板门的玩家开合体验与水浸语义，
 * 但不再读取原版邻居红石输入，只把无线输入转接成原版受电开合表现。
 * </p>
 */
public class WirelessTrapdoorCoreBlock extends TrapDoorBlock implements EntityBlock, WirelessOpenableCoreBlock {
	private final BlockSetType blockSetType;

	public WirelessTrapdoorCoreBlock(BlockSetType blockSetType, BlockBehaviour.Properties properties) {
		super(blockSetType, properties);
		this.blockSetType = blockSetType;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessOpenableCoreBlockEntity(blockPos, blockState);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState placementState = super.getStateForPlacement(context);
		if (placementState == null) {
			return null;
		}
		return placementState.setValue(OPEN, false).setValue(POWERED, false);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.CORE,
			WirelessOpenableCoreBlockEntity.class
		);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessOpenableCoreBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(
			this,
			pos,
			newState.getBlock(),
			level,
			WirelessOpenableCoreBlockEntity.class
		);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected void neighborChanged(
		BlockState state,
		Level level,
		BlockPos pos,
		Block block,
		BlockPos fromPos,
		boolean movedByPiston
	) {
		// 无线化活板门不读取原版邻居红石输入。
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
			WirelessBlockNodeSupport.openCorePairing(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return blockSetType.canOpenByHand() ? super.useWithoutItem(state, level, pos, player, hitResult) : InteractionResult.PASS;
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof WirelessOpenableCoreBlockEntity blockEntity) {
			blockEntity.onPulseTick();
		}
	}

	@Override
	public void syncWirelessOpenState(Level level, BlockPos pos, BlockState state, boolean active) {
		if (state.getBlock() != this) {
			return;
		}
		BlockState updatedState = state.setValue(OPEN, active).setValue(POWERED, active);
		if (updatedState == state) {
			return;
		}
		level.setBlock(pos, updatedState, 2);
		if (updatedState.getValue(WATERLOGGED)) {
			level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
		}
		playWirelessSound(level, pos, active);
	}

	@Override
	public boolean isWirelessOpenStateAligned(Level level, BlockPos pos, BlockState state, boolean active) {
		return state.getBlock() == this
			&& state.getValue(OPEN) == active
			&& state.getValue(POWERED) == active;
	}

	/**
	 * 复用原版活板门的开关声效与游戏事件。
	 */
	private void playWirelessSound(Level level, BlockPos pos, boolean active) {
		level.playSound(
			null,
			pos,
			active ? blockSetType.trapdoorOpen() : blockSetType.trapdoorClose(),
			SoundSource.BLOCKS,
			1.0F,
			level.getRandom().nextFloat() * 0.1F + 0.9F
		);
		level.gameEvent(null, active ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
	}
}
