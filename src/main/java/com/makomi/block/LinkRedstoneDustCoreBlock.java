package com.makomi.block;

import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CurrentLinksPrivacyService;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.network.PairingNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * 核心红石粉方块。
 * <p>
 * 基于原版 {@link RedStoneWireBlock} 扩展：
 * 所有附着面统一走“仅输出、不连线”的定向节点语义，
 * 顶面仅保留附着与外形特性，不再保留独立计算分支。
 * </p>
 */
public class LinkRedstoneDustCoreBlock extends RedStoneWireBlock implements EntityBlock {
	public static final DirectionProperty SUPPORT_FACE = DirectionProperty.create("support_face", Direction.values());
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
	private static final Vector3f BLUE_PARTICLE_BASE_COLOR = new Vector3f(0.36F, 0.66F, 1.0F);
	private static final VoxelShape FLOOR_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D);
	private static final VoxelShape CEILING_SHAPE = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
	private static final VoxelShape NORTH_WALL_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
	private static final VoxelShape SOUTH_WALL_SHAPE = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
	private static final VoxelShape WEST_WALL_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);
	private static final VoxelShape EAST_WALL_SHAPE = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

	public LinkRedstoneDustCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
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
		// 先处理附着合法性，避免后续对已失效方块继续演算。
		if (!state.canSurvive(level, pos)) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			dropResources(state, level, pos, blockEntity);
			level.removeBlock(pos, false);
			return;
		}
		syncPowerWithActiveState(state, level, pos);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(SUPPORT_FACE, ACTIVE);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState baseState = super.getStateForPlacement(context);
		if (baseState == null) {
			baseState = defaultBlockState();
		}

		Direction preferredSupportFace = context.getClickedFace().getOpposite();
		BlockState preferredState = normalizeForSupportFace(baseState.setValue(SUPPORT_FACE, preferredSupportFace));
		if (preferredState.canSurvive(context.getLevel(), context.getClickedPos())) {
			return preferredState;
		}

		// 点击面不可附着时，按玩家视角方向回退选择可附着面。
		for (Direction lookDirection : context.getNearestLookingDirections()) {
			BlockState fallbackState = normalizeForSupportFace(baseState.setValue(SUPPORT_FACE, lookDirection.getOpposite()));
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
		// 所有附着面统一不参与连接态演算，避免顶面和非顶面行为分叉。
		if (!state.canSurvive(level, pos)) {
			return Blocks.AIR.defaultBlockState();
		}
		return normalizeForSupportFace(state);
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
		BlockState normalizedState = normalizeForSupportFace(currentState);
		syncPowerWithActiveState(normalizedState, level, pos);
	}

	/**
	 * 在核心触发态变化时刷新方块状态。
	 */
	public void onCoreActivationStateChanged(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof LinkRedstoneDustCoreBlock)) {
			return;
		}
		if (!state.canSurvive(level, pos)) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			dropResources(state, level, pos, blockEntity);
			level.removeBlock(pos, false);
			return;
		}
		syncPowerWithActiveState(state, level, pos);
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		// 所有附着面都只向附着反方向输出，确保路径一致。
		return direction == state.getValue(SUPPORT_FACE).getOpposite() ? state.getValue(POWER) : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		// 直接信号与弱信号保持一致，避免附着方向出现不一致。
		return direction == state.getValue(SUPPORT_FACE).getOpposite() ? state.getValue(POWER) : 0;
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		// 统一关闭“红石线同类连接”信号源判定，避免顶面路径再次特殊化。
		return false;
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
	 * 核心红石粉粒子改造：仅在激活态渲染本地蓝色粒子，避免继承原版红色粒子表现。
	 */
	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		int power = state.getValue(POWER);
		if (power <= 0 || random.nextFloat() > 0.35F) {
			return;
		}

		float intensity = power / 15.0F;
		float red = BLUE_PARTICLE_BASE_COLOR.x() * (0.55F + 0.45F * intensity);
		float green = BLUE_PARTICLE_BASE_COLOR.y() * (0.55F + 0.45F * intensity);
		float blue = BLUE_PARTICLE_BASE_COLOR.z() * (0.55F + 0.45F * intensity);
		float scale = 0.45F + 0.25F * intensity;

		double x = pos.getX() + 0.5D + (random.nextDouble() - 0.5D) * 0.5D;
		double y = pos.getY() + 0.0625D;
		double z = pos.getZ() + 0.5D + (random.nextDouble() - 0.5D) * 0.5D;
		level.addParticle(new DustParticleOptions(new Vector3f(red, green, blue), scale), x, y, z, 0.0D, 0.0D, 0.0D);
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

	private static boolean isCoreActive(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity && coreBlockEntity.isActive();
	}

	private static BlockState normalizeForSupportFace(BlockState state) {
		// 所有附着面均不参与与其他红石粉连接交互，四向连接固定为 none。
		return state
			.setValue(NORTH, RedstoneSide.NONE)
			.setValue(EAST, RedstoneSide.NONE)
			.setValue(SOUTH, RedstoneSide.NONE)
			.setValue(WEST, RedstoneSide.NONE);
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
		boolean targetActive = isCoreActive(level, pos);
		int targetPower = targetActive ? RedstoneLinkConfig.coreOutputPower() : 0;
		BlockState normalizedState = normalizeForSupportFace(state);
		BlockState targetState = normalizedState.setValue(POWER, targetPower).setValue(ACTIVE, targetActive);
		BlockState currentState = level.getBlockState(pos);
		if (!targetState.equals(currentState)) {		
			// 做局部状态更新并手动通知附着目标邻居。
			level.setBlock(pos, targetState, Block.UPDATE_ALL);
			notifyAttachedNeighbor(level, pos, targetState);
			return;
				
		}
	}

	private static void notifyAttachedNeighbor(Level level, BlockPos pos, BlockState state) {
		// 顶面与非顶面统一仅通知附着块，避免对附着块周边重复扇出。
		Direction supportDirection = state.getValue(SUPPORT_FACE);
		BlockPos attachedPos = pos.relative(supportDirection);
		Block block = state.getBlock();
		level.updateNeighborsAt(attachedPos, block);
	}
}
