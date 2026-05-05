package com.makomi.block;

import com.makomi.block.entity.WirelessOpenableCoreBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化铁门。
 * <p>
 * 不再读取原版邻居红石输入，改为承接无线输入控制开关，
 * 同时保留潜行空副手右键打开 core 配对界面。
 * </p>
 */
public class WirelessIronDoorBlock extends DoorBlock implements EntityBlock, WirelessOpenableCoreBlock {
	public WirelessIronDoorBlock(BlockSetType blockSetType, BlockBehaviour.Properties properties) {
		super(blockSetType, properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return blockState.getValue(HALF) == DoubleBlockHalf.LOWER ? new WirelessOpenableCoreBlockEntity(blockPos, blockState) : null;
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
		if (state.getValue(HALF) != DoubleBlockHalf.LOWER) {
			return;
		}
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
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			return super.getDrops(state, builder);
		}
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessOpenableCoreBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
			WirelessBlockNodeSupport.unregisterOnRemove(
				this,
				pos,
				newState.getBlock(),
				level,
				WirelessOpenableCoreBlockEntity.class
			);
		}
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
		// 无线化铁门不读取原版邻居红石输入。
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
			BlockPos basePos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
			WirelessBlockNodeSupport.openCorePairing(level, basePos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		BlockPos basePos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
		if (level.getBlockEntity(basePos) instanceof WirelessOpenableCoreBlockEntity blockEntity) {
			blockEntity.onPulseTick();
		}
	}

	@Override
	public void syncWirelessOpenState(Level level, BlockPos pos, BlockState state, boolean active) {
		BlockPos basePos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
		BlockState lowerState = level.getBlockState(basePos);
		if (lowerState.getBlock() != this || lowerState.getValue(HALF) != DoubleBlockHalf.LOWER) {
			return;
		}
		if (isWirelessOpenStateAligned(level, basePos, lowerState, active)) {
			return;
		}
		setOpen(null, level, lowerState, basePos, active);
		syncPoweredState(level, basePos, active);
	}

	@Override
	public boolean isWirelessOpenStateAligned(Level level, BlockPos pos, BlockState state, boolean active) {
		BlockPos basePos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
		BlockState lowerState = level.getBlockState(basePos);
		BlockState upperState = level.getBlockState(basePos.above());
		return lowerState.getBlock() == this
			&& upperState.getBlock() == this
			&& lowerState.getValue(HALF) == DoubleBlockHalf.LOWER
			&& upperState.getValue(HALF) == DoubleBlockHalf.UPPER
			&& lowerState.getValue(OPEN) == active
			&& lowerState.getValue(POWERED) == active
			&& upperState.getValue(POWERED) == active;
	}

	/**
	 * 同步双半块的 POWERED 状态，避免无线驱动后残留旧电力外显。
	 */
	private void syncPoweredState(Level level, BlockPos basePos, boolean active) {
		BlockState lowerState = level.getBlockState(basePos);
		BlockState upperState = level.getBlockState(basePos.above());
		if (lowerState.getBlock() == this && lowerState.getValue(POWERED) != active) {
			level.setBlock(basePos, lowerState.setValue(POWERED, active), 2);
		}
		if (upperState.getBlock() == this && upperState.getValue(POWERED) != active) {
			level.setBlock(basePos.above(), upperState.setValue(POWERED, active), 2);
		}
	}
}
