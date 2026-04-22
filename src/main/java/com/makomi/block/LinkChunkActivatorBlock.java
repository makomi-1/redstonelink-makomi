package com.makomi.block;

import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.ChunkActivatorItemData;
import com.makomi.network.ChunkActivatorNetwork;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 区块激活器方块。
 * <p>
 * 统一承接 `POWERED` 外显、区块激活器真值刷新与编辑 GUI 打开逻辑。
 * </p>
 */
public class LinkChunkActivatorBlock extends BaseEntityBlock {
	public static final MapCodec<LinkChunkActivatorBlock> CODEC = simpleCodec(LinkChunkActivatorBlock::new);
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public LinkChunkActivatorBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(POWERED, false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkChunkActivatorBlockEntity(blockPos, blockState);
	}

	@Override
	protected RenderShape getRenderShape(BlockState blockState) {
		return RenderShape.MODEL;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (!oldState.is(state.getBlock())) {
			refreshPoweredState(level, pos, state);
			refreshPlacedActivatorState(level, pos);
		}
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof LinkChunkActivatorBlockEntity blockEntity)) {
			return;
		}
		blockEntity.applyEditorState(
			ChunkActivatorItemData.getDisplayAlias(stack),
			ChunkActivatorItemData.read(stack)
		);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		List<ItemStack> drops = new ArrayList<>(super.getDrops(state, builder));
		if (drops.isEmpty()) {
			drops.add(new ItemStack(asItem()));
		}
		if (!(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof LinkChunkActivatorBlockEntity blockEntity)) {
			return drops;
		}
		for (ItemStack drop : drops) {
			if (drop.is(asItem())) {
				ChunkActivatorItemData.write(drop, blockEntity.snapshot());
				ChunkActivatorItemData.setDisplayAlias(drop, blockEntity.displayAlias());
			}
		}
		return drops;
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
		super.neighborChanged(state, level, pos, block, fromPos, movedByPiston);
		refreshPoweredState(level, pos, state);
		refreshPlacedActivatorState(level, pos);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof LinkChunkActivatorBlockEntity blockEntity) {
			blockEntity.markPhysicalRemovalInProgress();
		}
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
			openEditor(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	private static void openEditor(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverLevel.getBlockEntity(pos) instanceof LinkChunkActivatorBlockEntity blockEntity) {
			ChunkActivatorNetwork.openEditor(serverPlayer, blockEntity);
		}
	}

	/**
	 * 按邻居最大输入刷新 `POWERED` 状态，仅用于外显。
	 */
	private static void refreshPoweredState(Level level, BlockPos pos, BlockState state) {
		if (level.isClientSide) {
			return;
		}
		boolean powered = level.getBestNeighborSignal(pos) > 0;
		if (state.getValue(POWERED) == powered) {
			return;
		}
		level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
	}

	/**
	 * 将当前已放置区块激活器的世界态同步到持久化真值。
	 */
	private static void refreshPlacedActivatorState(Level level, BlockPos pos) {
		if (level.isClientSide) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkChunkActivatorBlockEntity blockEntity) {
			blockEntity.refreshPlacedActivatorState();
		}
	}
}
