package com.makomi.block;

import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CurrentLinksPrivacyService;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.network.PairingNetwork;
import com.makomi.util.NeighborFanoutUtil;
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
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;


/**
 * 核心红石粉方块。
 * <p>
 * 作为轻量“定向红石输出节点”实现，不再继承原版红石线逻辑：
 * 所有附着面统一走“仅输出、不连线”的定向节点语义，
 * 顶面仅保留附着与外形特性，不再保留独立计算分支。
 * </p>
 */
public class LinkRedstoneDustCoreBlock extends Block implements EntityBlock {
	public static final DirectionProperty SUPPORT_FACE = DirectionProperty.create("support_face", Direction.values());
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
	private static final VoxelShape FLOOR_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D);
	private static final VoxelShape CEILING_SHAPE = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
	private static final VoxelShape NORTH_WALL_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
	private static final VoxelShape SOUTH_WALL_SHAPE = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
	private static final VoxelShape WEST_WALL_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);
	private static final VoxelShape EAST_WALL_SHAPE = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

	public LinkRedstoneDustCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
		// 保留既有契约测试匹配片段：显式声明 active 默认值。
		registerDefaultState(defaultBlockState().setValue(SUPPORT_FACE, Direction.DOWN).setValue(ACTIVE, false));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkRedstoneDustCoreBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity)) {
			return;
		}

		long serial = LinkItemData.resolvePlacementSerial(stack, serverLevel, LinkNodeType.CORE, pos);
		coreBlockEntity.setLinkData(serial);
	}

	/**
	 * 掉落时回写序列号与链接快照，确保“挖起再放置”可保持身份连续性。
	 */
	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		List<ItemStack> drops = new ArrayList<>(super.getDrops(state, builder));
		if (drops.isEmpty()) {
			drops.add(new ItemStack(asItem()));
		}

		if (!(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity)) {
			return drops;
		}

		long serial = coreBlockEntity.getSerial();
		if (serial <= 0L) {
			return drops;
		}

		for (ItemStack drop : drops) {
			if (drop.is(asItem())) {
				LinkItemData.setSerial(drop, serial);
				LinkItemData.setDestroyRetireCandidate(drop, true);
				if (coreBlockEntity.getLevel() instanceof ServerLevel serverLevel) {
					LinkItemData.setLinkedSerials(
						drop,
						CurrentLinksPrivacyService.resolveItemSnapshotTargets(
							serverLevel,
							LinkNodeType.CORE,
							serial,
							LinkSavedData.get(serverLevel).getLinkedButtons(serial)
						)
					);
				}
			}
		}
		return drops;
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
				// 走“待确认退役”路径，避免正常掉落被误判为销毁。
				coreBlockEntity.unregisterNode(true);
			}
			if (!level.isClientSide) {
				// 核心被破坏时对齐核心块：中心 + 六方向二级扇出，确保周边红石网络立即收敛。
				NeighborFanoutUtil.notifyCenterAndSixNeighbors(level, pos, state.getBlock());
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected void neighborChanged(
		BlockState state,
		Level level,
		BlockPos pos,
		net.minecraft.world.level.block.Block block,
		BlockPos fromPos,
		boolean movedByPiston
	) {
		Direction supportFace = state.getValue(SUPPORT_FACE);
		BlockPos supportPos = pos.relative(supportFace);
		// L1 收敛：仅支撑侧变化才执行生存判定，避免无关邻居触发热路径。
		if (!fromPos.equals(supportPos)) {
			return;
		}
		if (!level.getBlockState(supportPos).isFaceSturdy(level, supportPos, supportFace.getOpposite())) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			dropResources(state, level, pos, blockEntity);
			// removeBlock 内部使用 flags=3（UPDATE_NEIGHBORS|UPDATE_CLIENTS），
			level.removeBlock(pos, false);
			return;
		}
		// 与核心块路径对齐：邻居变化不参与输出重算，避免广播回调导致重复扇出与重入计算。
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(SUPPORT_FACE, ACTIVE);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState baseState = defaultBlockState();

		Direction preferredSupportFace = context.getClickedFace().getOpposite();
		BlockState preferredState = baseState.setValue(SUPPORT_FACE, preferredSupportFace);
		if (preferredState.canSurvive(context.getLevel(), context.getClickedPos())) {
			return preferredState;
		}

		// 点击面不可附着时，按玩家视角方向回退选择可附着面。
		for (Direction lookDirection : context.getNearestLookingDirections()) {
			BlockState fallbackState = baseState.setValue(SUPPORT_FACE, lookDirection.getOpposite());
			if (fallbackState.canSurvive(context.getLevel(), context.getClickedPos())) {
				return fallbackState;
			}
		}
		return null;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		Direction supportFace = state.getValue(SUPPORT_FACE);
		BlockPos supportPos = pos.relative(supportFace);
		return level.getBlockState(supportPos).isFaceSturdy(level, supportPos, supportFace.getOpposite());
	}

	@Override
	protected BlockState updateShape(
		BlockState state,
		Direction direction,
		BlockState neighborState,
		LevelAccessor level,
		BlockPos pos,
		BlockPos neighborPos
	) {
		Direction supportFace = state.getValue(SUPPORT_FACE);
		// L1 收敛：仅当支撑方向邻居发生变化时，才需要判定是否掉落。
		if (direction != supportFace) {
			return state;
		}
		if (!neighborState.isFaceSturdy(level, neighborPos, supportFace.getOpposite())) {
			return Blocks.AIR.defaultBlockState();
		}
		return state;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(SUPPORT_FACE)) {
			case DOWN -> FLOOR_SHAPE;
			case UP -> CEILING_SHAPE;
			case NORTH -> NORTH_WALL_SHAPE;
			case SOUTH -> SOUTH_WALL_SHAPE;
			case WEST -> WEST_WALL_SHAPE;
			case EAST -> EAST_WALL_SHAPE;
		};
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel) {
			// 兜底补号调度：覆盖 setPlacedBy 未触发（如命令放置）导致的无序号场景。
			serverLevel.scheduleTick(pos, state.getBlock(), 1);
		}
		BlockState currentState = level.getBlockState(pos);
		syncPowerWithActiveState(currentState, level, pos);
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		// 所有附着面都只向附着反方向输出，确保路径一致。
		return direction == state.getValue(SUPPORT_FACE).getOpposite() ? getCoreResolvedPower(level, pos) : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		// 直接信号与弱信号保持一致，避免附着方向出现不一致。
		return direction == state.getValue(SUPPORT_FACE).getOpposite() ? getCoreResolvedPower(level, pos) : 0;
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
			openPairingScreen(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		if (!level.isClientSide && level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			coreBlockEntity.triggerByPlayer();
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			ensureCoreSerialAssigned(level, coreBlockEntity);
			// 脉冲模式到点回落由方块 tick 驱动。
			coreBlockEntity.onPulseTick();
		}
	}


	/**
	 * 打开核心配对界面。
	 * <p>
	 * 若节点尚无序列号，会先分配序列号再下发打开界面网络包。
	 * </p>
	 */
	private static void openPairingScreen(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			ensureCoreSerialAssigned(serverLevel, coreBlockEntity);
			long serial = coreBlockEntity.getSerial();
			if (serial > 0L) {
				PairingNetwork.openCorePairing(serverPlayer, serial);
			}
		}
	}

	/**
	 * 确保核心节点已分配序号。
	 * <p>
	 * 作为放置与交互链路的兜底，避免因非常规放置路径导致客户端无法外显短码。
	 * </p>
	 */
	private static void ensureCoreSerialAssigned(ServerLevel level, LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
		if (coreBlockEntity.getSerial() > 0L) {
			return;
		}
		long serial = LinkSavedData.get(level).allocateSerial(LinkNodeType.CORE);
		coreBlockEntity.setLinkData(serial);
	}


	private static void syncPowerWithActiveState(BlockState state, Level level, BlockPos pos) {
		int targetPower = getCoreResolvedPower(level, pos);
		boolean targetActive = targetPower > 0;
		// 与核心块路径对齐：输出强度以方块实体为准，方块状态仅承载可见激活态。
		BlockState targetState = state.setValue(ACTIVE, targetActive);
		BlockState currentState = level.getBlockState(pos);
		if (!targetState.equals(currentState)) {
			// 放置/重建时仅需同步可见态到客户端，邻居传播由实体侧统一扇出。
			level.setBlock(pos, targetState, Block.UPDATE_CLIENTS);
		}
	}

	private static int getCoreResolvedPower(BlockGetter level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			return coreBlockEntity.getResolvedOutputPower();
		}
		return 0;
	}

}
