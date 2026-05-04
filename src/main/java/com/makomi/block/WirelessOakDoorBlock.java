package com.makomi.block;

import com.makomi.block.entity.WirelessOakDoorBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
 * 无线化橡木门。
 * <p>
 * 保留原版木门的玩家交互开关体验，
 * 但不再读取原版邻居红石输入，只接受已连接 `triggerSource` 的无线输入。
 * </p>
 */
public class WirelessOakDoorBlock extends DoorBlock implements EntityBlock {
	public WirelessOakDoorBlock(BlockSetType blockSetType, BlockBehaviour.Properties properties) {
		super(blockSetType, properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return blockState.getValue(HALF) == DoubleBlockHalf.LOWER ? new WirelessOakDoorBlockEntity(blockPos, blockState) : null;
	}

	/**
	 * 按无线 `core` 激活态同步门的开关表现。
	 * <p>
	 * 复用原版门的 `setOpen`，统一处理双半块状态与门开关声效。
	 * </p>
	 */
	public void syncWirelessOpenState(Level level, BlockState state, BlockPos pos, boolean open) {
		setOpen(null, level, state, pos, open);
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
			WirelessOakDoorBlockEntity.class
		);
	}

	@Override
	protected java.util.List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			return super.getDrops(state, builder);
		}
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessOakDoorBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
			WirelessBlockNodeSupport.unregisterOnRemove(this, pos, newState.getBlock(), level, WirelessOakDoorBlockEntity.class);
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
		// 无线化木门不读取原版邻居红石输入。
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
		return super.useWithoutItem(state, level, pos, player, hitResult);
	}
}
