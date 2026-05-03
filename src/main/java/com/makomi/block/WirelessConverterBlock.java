package com.makomi.block;

import com.makomi.block.entity.WirelessConverterBlockEntity;
import com.makomi.menu.WirelessConverterMenu;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化转化器方块。
 * <p>
 * 复用炉式槽位、漏斗交互与燃烧进度，
 * 但配方类型改为“原版物到无线化节点物”的专用转化。
 * </p>
 */
public class WirelessConverterBlock extends AbstractFurnaceBlock {
	public static final MapCodec<WirelessConverterBlock> CODEC = simpleCodec(WirelessConverterBlock::new);

	public WirelessConverterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends AbstractFurnaceBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessConverterBlockEntity(blockPos, blockState);
	}

	@Override
	protected void openContainer(Level level, BlockPos pos, Player player) {
		if (!(level.getBlockEntity(pos) instanceof WirelessConverterBlockEntity blockEntity) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		serverPlayer.openMenu(blockEntity);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		return super.useWithoutItem(state, level, pos, player, hitResult);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
		return createFurnaceTicker(level, blockEntityType, com.makomi.registry.ModBlockEntities.WIRELESS_CONVERTER);
	}
}
