package com.makomi.block;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkFilterItemData;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import com.makomi.network.LinkFilterNetwork;
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
 * 发送/接收过滤器公共方块基类。
 * <p>
 * 统一承接 `POWERED` 外显、已放置过滤器真值刷新与 GUI 打开逻辑。
 * </p>
 */
public abstract class AbstractLinkFilterBlock extends BaseEntityBlock {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	protected AbstractLinkFilterBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.setAllFaces(stateDefinition.any().setValue(POWERED, false), true));
	}

	@Override
	protected abstract MapCodec<? extends BaseEntityBlock> codec();

	@Override
	public abstract BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState);

	@Override
	protected RenderShape getRenderShape(BlockState blockState) {
		return RenderShape.MODEL;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (!oldState.is(state.getBlock())) {
			refreshStateFromCurrentInputs(level, pos, state);
		}
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof AbstractLinkFilterBlockEntity filterBlockEntity)) {
			return;
		}
		filterBlockEntity.applyEditorState(LinkFilterItemData.getDisplayAlias(stack), LinkFilterItemData.read(stack));
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		List<ItemStack> drops = new ArrayList<>(super.getDrops(state, builder));
		if (drops.isEmpty()) {
			drops.add(new ItemStack(asItem()));
		}
		if (!(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof AbstractLinkFilterBlockEntity filterBlockEntity)) {
			return drops;
		}
		for (ItemStack drop : drops) {
			if (drop.is(asItem())) {
				LinkFilterItemData.write(drop, filterBlockEntity.snapshot());
				LinkFilterItemData.setDisplayAlias(drop, filterBlockEntity.displayAlias());
				if (filterBlockEntity.getLevel() instanceof ServerLevel serverLevel) {
					LinkFilterItemData.syncNodeSetDisplayTexts(drop, serverLevel, filterBlockEntity.filterKind().servicedNodeType());
				}
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
		refreshStateFromCurrentInputs(level, pos, state);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof AbstractLinkFilterBlockEntity filterBlockEntity) {
			filterBlockEntity.markPhysicalRemovalInProgress();
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
		NodeFaceSetBlockStateSupport.appendProperties(builder);
	}

	/**
	 * 立即按当前面集输入重采样过滤器的外显与真值。
	 */
	public final void refreshStateFromCurrentInputs(Level level, BlockPos pos, BlockState state) {
		refreshPoweredState(level, pos, state);
		refreshPlacedFilterState(level, pos);
	}

	/**
	 * 打开过滤器编辑界面。
	 */
	private static void openEditor(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverLevel.getBlockEntity(pos) instanceof AbstractLinkFilterBlockEntity filterBlockEntity) {
			LinkFilterNetwork.openEditor(serverPlayer, filterBlockEntity);
		}
	}

	/**
	 * 按邻居最大输入刷新 `POWERED` 状态，仅用于外显。
	 */
	private static void refreshPoweredState(Level level, BlockPos pos, BlockState state) {
		if (level.isClientSide) {
			return;
		}
		boolean powered = NodeFaceSetBlockStateSupport.sampleNeighborSignalStrength(level, pos, state) > 0;
		if (state.getValue(POWERED) == powered) {
			return;
		}
		level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
	}

	/**
	 * 将当前已放置过滤器的世界态同步到持久化真值。
	 */
	private static void refreshPlacedFilterState(Level level, BlockPos pos) {
		if (level.isClientSide) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof AbstractLinkFilterBlockEntity filterBlockEntity) {
			filterBlockEntity.refreshPlacedFilterState();
		}
	}
}
