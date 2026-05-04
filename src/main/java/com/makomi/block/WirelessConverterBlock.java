package com.makomi.block;

import com.makomi.block.entity.WirelessConverterBlockEntity;
import com.makomi.menu.WirelessConverterMenu;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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

	/**
	 * 无线化活塞搬运转化器时，沿用“搬运而非销毁”语义。
	 * <p>
	 * 原版炉式方块会在 {@code onRemove()} 中按真实移除吐出库存；
	 * 这里若确认当前位置正被无线化活塞搬运，则先摘掉旧方块实体，
	 * 让父类只处理方块替换，不再把库存额外抛到世界里。
	 * </p>
	 */
	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && WirelessPistonNodeMoveSupport.isMoveInProgress(level, pos)) {
			level.removeBlockEntity(pos);
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
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
