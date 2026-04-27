package com.makomi.block;

import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.RepeaterItemData;
import com.makomi.network.RepeaterNetwork;
import com.makomi.util.NeighborFanoutUtil;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 转发器方块。
 * <p>
 * 输入侧按 `core` 语义接收，输出侧按同号 `triggerSource` 语义延迟派发。
 * </p>
 */
public class LinkRepeaterBlock extends BaseEntityBlock {
	public static final MapCodec<LinkRepeaterBlock> CODEC = simpleCodec(LinkRepeaterBlock::new);
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

	public LinkRepeaterBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkRepeaterBlockEntity(blockPos, blockState);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState blockState) {
		return RenderShape.MODEL;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof LinkRepeaterBlockEntity blockEntity)) {
			return;
		}
		long serial = RepeaterItemData.resolvePlacementSerial(stack, serverLevel, pos);
		blockEntity.setLinkData(serial);
		blockEntity.applyEditorState(RepeaterItemData.read(stack));
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		List<ItemStack> drops = new ArrayList<>(super.getDrops(state, builder));
		if (drops.isEmpty()) {
			drops.add(new ItemStack(asItem()));
		}
		if (!(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof LinkRepeaterBlockEntity blockEntity)) {
			return drops;
		}
		for (ItemStack drop : drops) {
			if (!drop.is(asItem())) {
				continue;
			}
			LinkItemData.setSerial(drop, blockEntity.getSerial());
			LinkItemData.setDestroyRetireCandidate(drop, true);
			RepeaterItemData.write(drop, blockEntity.snapshot());
			if (blockEntity.getLevel() instanceof ServerLevel serverLevel) {
				LinkItemData.syncDisplayAliasIfSingle(drop, serverLevel);
				RepeaterItemData.syncNodeSetDisplayTexts(drop, serverLevel);
			}
		}
		return drops;
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			if (level.getBlockEntity(pos) instanceof LinkRepeaterBlockEntity blockEntity) {
				blockEntity.markRepeaterPhysicalRemovalInProgress();
				blockEntity.unregisterNode(true);
			}
			if (!level.isClientSide()) {
				NeighborFanoutUtil.notifyCenterAndSixNeighbors(level, pos, state.getBlock());
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return resolveOutputPower(level, pos);
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return resolveOutputPower(level, pos);
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
			return InteractionResult.sidedSuccess(level.isClientSide());
		}
		return InteractionResult.PASS;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ACTIVE);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof LinkRepeaterBlockEntity blockEntity) {
			blockEntity.onPulseTick();
			blockEntity.onDelayTick();
		}
	}

	private static void openEditor(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (!(serverLevel.getBlockEntity(pos) instanceof LinkRepeaterBlockEntity blockEntity)) {
			return;
		}
		long resolvedSerial = com.makomi.data.LinkSavedData
			.get(serverLevel)
			.resolveRepeaterPlacementSerial(blockEntity.getSerial(), serverLevel.dimension(), pos);
		if (resolvedSerial > 0L && resolvedSerial != blockEntity.getSerial()) {
			blockEntity.setLinkData(resolvedSerial);
		}
		RepeaterNetwork.openEditor(serverPlayer, blockEntity);
	}

	private static int resolveOutputPower(BlockGetter level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof LinkRepeaterBlockEntity blockEntity) {
			return blockEntity.getCurrentDispatchedOutputPower();
		}
		return 0;
	}
}
