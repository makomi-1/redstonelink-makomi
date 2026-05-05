package com.makomi.block;

import com.makomi.block.entity.WirelessTntBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化 TNT。
 * <p>
 * 不再响应原版邻居红石输入，
 * 改为在无线输入激活时按原版 TNT 受电逻辑点燃自身。
 * </p>
 */
public class WirelessTntBlock extends TntBlock implements EntityBlock {
	public WirelessTntBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessTntBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.CORE,
			WirelessTntBlockEntity.class
		);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessTntBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(
			this,
			pos,
			newState.getBlock(),
			level,
			WirelessTntBlockEntity.class
		);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		// 无线化 TNT 不响应原版邻居红石输入。
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
		// 无线化 TNT 不响应原版邻居红石输入。
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
		return InteractionResult.PASS;
	}

	/**
	 * 以原版受电 TNT 的方式点燃并移除当前方块。
	 */
	public void primeWireless(Level level, BlockPos pos) {
		TntBlock.explode(level, pos);
		level.removeBlock(pos, false);
	}
}
