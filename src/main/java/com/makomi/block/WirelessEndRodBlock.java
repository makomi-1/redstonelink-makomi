package com.makomi.block;

import com.makomi.block.entity.WirelessLitCoreBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.function.Supplier;
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
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化末地烛。
 * <p>
 * 保留原版末地烛的朝向与放置语义，仅把亮灭切到无线 `core`。
 * </p>
 */
public class WirelessEndRodBlock extends EndRodBlock implements EntityBlock, WirelessLitCoreBlock {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	private final Supplier<? extends BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity>> blockEntityTypeSupplier;

	public WirelessEndRodBlock(
		BlockBehaviour.Properties properties,
		Supplier<? extends BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity>> blockEntityTypeSupplier
	) {
		super(properties.lightLevel(state -> state.getValue(LIT) ? 14 : 0));
		this.blockEntityTypeSupplier = blockEntityTypeSupplier;
		registerDefaultState(
			stateDefinition.any()
				.setValue(FACING, net.minecraft.core.Direction.UP)
				.setValue(LIT, false)
		);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessLitCoreBlockEntity(blockEntityTypeSupplier.get(), blockPos, blockState);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState placementState = super.getStateForPlacement(context);
		return placementState == null ? null : placementState.setValue(LIT, false);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.CORE,
			WirelessLitCoreBlockEntity.class
		);
	}

	@Override
	protected java.util.List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessLitCoreBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(this, pos, newState.getBlock(), level, WirelessLitCoreBlockEntity.class);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean movedByPiston) {
		// 无线化末地烛不读取原版邻居红石输入。
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (RedstoneLinkConfig.canOpenPairingByPlacedBlock(player)) {
			WirelessBlockNodeSupport.openCorePairing(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof WirelessLitCoreBlockEntity blockEntity) {
			blockEntity.onPulseTick();
		}
	}

	@Override
	public void syncWirelessLitState(Level level, BlockPos pos, boolean active) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() != this || state.getValue(LIT) == active) {
			return;
		}
		level.setBlock(pos, state.setValue(LIT, active), 2);
	}

	@Override
	public boolean isWirelessLitStateAligned(Level level, BlockPos pos, boolean active) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() == this && state.getValue(LIT) == active;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(LIT);
	}
}
