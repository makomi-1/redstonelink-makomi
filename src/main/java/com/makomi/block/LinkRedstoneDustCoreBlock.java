package com.makomi.block;

import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
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

public class LinkRedstoneDustCoreBlock extends RedStoneWireBlock implements EntityBlock {
	public static final DirectionProperty SUPPORT_FACE = DirectionProperty.create("support_face", Direction.values());
	public static final BooleanProperty POWERED = BooleanProperty.create("powered");
	private static final Vector3f BLUE_PARTICLE_BASE_COLOR = new Vector3f(0.36F, 0.66F, 1.0F);
	private static final VoxelShape FLOOR_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D);
	private static final VoxelShape CEILING_SHAPE = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
	private static final VoxelShape NORTH_WALL_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
	private static final VoxelShape SOUTH_WALL_SHAPE = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
	private static final VoxelShape WEST_WALL_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);
	private static final VoxelShape EAST_WALL_SHAPE = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

	public LinkRedstoneDustCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(SUPPORT_FACE, Direction.DOWN).setValue(POWERED, false));
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
					LinkItemData.setLinkedSerials(drop, LinkSavedData.get(serverLevel).getLinkedButtons(serial));
				}
			}
		}
		return drops;
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
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

		// 顶面：信号与形态计算完全走原版，再叠加核心触发态模拟输入。
		if (!isNonTopAttached(state)) {
			super.neighborChanged(state, level, pos, block, fromPos, movedByPiston);
			syncTopPoweredVisual(level, pos);
			return;
		}

		// 侧面/底面：保持既有语义，不参与红石线连接计算。
		BlockState normalizedState = normalizeForSupportFace(state);
		if (!normalizedState.equals(level.getBlockState(pos))) {
			level.setBlock(pos, normalizedState, Block.UPDATE_ALL);
		}
		syncPowerWithActiveState(level.getBlockState(pos), level, pos);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(SUPPORT_FACE, POWERED);
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
		if (!isNonTopAttached(state)) {
			return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
		}

		// 侧面/底面附着时禁止连接态演算，避免任何连线或形状变化。
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
		BlockState currentState = level.getBlockState(pos);
		if (!isNonTopAttached(currentState)) {
			// 顶面放置后保持原版结果，不额外强制写回。
			syncTopPoweredVisual(level, pos);
			return;
		}

		BlockState normalizedState = normalizeForSupportFace(currentState);
		if (!normalizedState.equals(level.getBlockState(pos))) {
			level.setBlock(pos, normalizedState, Block.UPDATE_ALL);
		}
		syncPowerWithActiveState(level.getBlockState(pos), level, pos);
	}

	/**
	 * 在核心触发态变化时刷新方块状态。
	 * 顶面：先走原版重算，再并入模拟输入；
	 * 侧面/底面：保持既有仅由 active 驱动的语义。
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

		if (!isNonTopAttached(state)) {
			super.neighborChanged(state, level, pos, this, pos, false);
			syncTopPoweredVisual(level, pos);
			return;
		}

		syncPowerWithActiveState(state, level, pos);
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		if (!isNonTopAttached(state)) {
			return super.getSignal(state, level, pos, direction);
		}
		// C-1修正：非顶面附着时只向附着方向输出，激活对象即附着支撑方块。
		return direction == state.getValue(SUPPORT_FACE).getOpposite() ? state.getValue(POWER) : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		if (!isNonTopAttached(state)) {
			return super.getDirectSignal(state, level, pos, direction);
		}
		// 直接信号与弱信号保持一致，避免侧/底附着时出现方向不一致。
		return direction == state.getValue(SUPPORT_FACE).getOpposite() ? state.getValue(POWER) : 0;
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		if (isNonTopAttached(state)) {
			// 让红石粉连接判定完全忽略侧面/底面附着核心粉。
			return false;
		}
		return super.isSignalSource(state);
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

	private static void openPairingScreen(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			long serial = coreBlockEntity.getSerial();
			if (serial <= 0L) {
				serial = LinkSavedData.get(serverLevel).allocateSerial(LinkNodeType.CORE);
				coreBlockEntity.setLinkData(serial);
			}
			if (serial > 0L) {
				PairingNetwork.openCorePairing(serverPlayer, serial);
			}
		}
	}

	private static boolean isCoreActive(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity && coreBlockEntity.isActive();
	}

	/**
	 * 供原版红石粉计算注入点调用：仅顶面核心红石粉返回模拟输入功率。
	 */
	public static int getTopSimulatedInputPower(BlockGetter level, BlockPos pos, BlockState state) {
		if (!isTopAttachedCoreDust(state)) {
			return 0;
		}
		return isCoreActive(level, pos) ? RedstoneLinkConfig.coreOutputPower() : 0;
	}

	/**
	 * 供原版 wire 连接判定调用：仅顶面核心红石粉可按“红石线同类”参与连接。
	 */
	public static boolean isTopAttachedCoreDust(BlockState state) {
		return state.getBlock() instanceof LinkRedstoneDustCoreBlock && !isNonTopAttached(state);
	}

	/**
	 * 供原版 wire 网络传播调用：顶面核心红石粉按当前 POWER 返回线网功率。
	 */
	public static int getTopAttachedWireSignal(BlockState state) {
		if (!isTopAttachedCoreDust(state)) {
			return 0;
		}
		return state.getValue(POWER);
	}

	private static BlockState normalizeForSupportFace(BlockState state) {
		if (!isNonTopAttached(state)) {
			return state;
		}
		// C-1：侧面和底面附着不参与与其他红石粉连接交互，四向连接全部固定为 none。
		return state
			.setValue(NORTH, RedstoneSide.NONE)
			.setValue(EAST, RedstoneSide.NONE)
			.setValue(SOUTH, RedstoneSide.NONE)
			.setValue(WEST, RedstoneSide.NONE);
	}

	private static boolean isNonTopAttached(BlockState state) {
		return state.getValue(SUPPORT_FACE) != Direction.DOWN;
	}

	/**
	 * 顶面材质状态同步：只将 POWERED 与 POWER>0 对齐，不参与功率计算。
	 */
	private static void syncTopPoweredVisual(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof LinkRedstoneDustCoreBlock) || isNonTopAttached(state)) {
			return;
		}

		boolean targetPowered = state.getValue(POWER) > 0;
		if (state.getValue(POWERED) != targetPowered) {
			level.setBlock(pos, state.setValue(POWERED, targetPowered), Block.UPDATE_CLIENTS);
		}
	}

	private static void syncPowerWithActiveState(BlockState state, Level level, BlockPos pos) {
		int targetPower = isCoreActive(level, pos) ? RedstoneLinkConfig.coreOutputPower() : 0;
		boolean targetPowered = targetPower > 0;
		if (state.getValue(POWER) != targetPower || state.getValue(POWERED) != targetPowered) {
			level.setBlock(pos, state.setValue(POWER, targetPower).setValue(POWERED, targetPowered), Block.UPDATE_ALL);
		}
	}
}
