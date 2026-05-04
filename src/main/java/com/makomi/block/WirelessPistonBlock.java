package com.makomi.block;

import com.makomi.block.entity.WirelessPistonBlockEntity;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.makomi.registry.ModBlocks;

/**
 * 无线化活塞。
 * <p>
 * 仅接受已连接 triggerSource 的无线输入，
 * 不读取也不输出原版邻居红石信号。
 * </p>
 */
public class WirelessPistonBlock extends Block implements EntityBlock {
	public static final MapCodec<WirelessPistonBlock> CODEC = simpleCodec(WirelessPistonBlock::new);
	public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
		net.minecraft.world.level.block.DirectionalBlock.FACING;
	public static final BooleanProperty EXTENDED = BlockStateProperties.EXTENDED;
	protected static final VoxelShape EAST_AABB = Block.box(0.0, 0.0, 0.0, 12.0, 16.0, 16.0);
	protected static final VoxelShape WEST_AABB = Block.box(4.0, 0.0, 0.0, 16.0, 16.0, 16.0);
	protected static final VoxelShape SOUTH_AABB = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 12.0);
	protected static final VoxelShape NORTH_AABB = Block.box(0.0, 0.0, 4.0, 16.0, 16.0, 16.0);
	protected static final VoxelShape UP_AABB = Block.box(0.0, 0.0, 0.0, 16.0, 12.0, 16.0);
	protected static final VoxelShape DOWN_AABB = Block.box(0.0, 4.0, 0.0, 16.0, 16.0, 16.0);
	private final boolean sticky;

	public WirelessPistonBlock(BlockBehaviour.Properties properties) {
		this(properties, false);
	}

	public WirelessPistonBlock(BlockBehaviour.Properties properties, boolean sticky) {
		super(properties);
		this.sticky = sticky;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(EXTENDED, false));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessPistonBlockEntity(resolveBlockEntityType(), blockPos, blockState);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext collisionContext) {
		if (!state.getValue(EXTENDED)) {
			return Shapes.block();
		}
		return switch (state.getValue(FACING)) {
			case DOWN -> DOWN_AABB;
			case UP -> UP_AABB;
			case NORTH -> NORTH_AABB;
			case SOUTH -> SOUTH_AABB;
			case WEST -> WEST_AABB;
			case EAST -> EAST_AABB;
		};
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite()).setValue(EXTENDED, false);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.CORE,
			WirelessPistonBlockEntity.class
		);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(this, builder, super.getDrops(state, builder), WirelessPistonBlockEntity.class);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(this, pos, newState.getBlock(), level, WirelessPistonBlockEntity.class);
		super.onRemove(state, level, pos, newState, movedByPiston);
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
		// 无线化活塞不读取邻居红石输入。
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		// 无线化活塞不在放置时主动探测邻居红石。
		if (!oldState.is(state.getBlock())) {
			super.onPlace(state, level, pos, oldState, movedByPiston);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		BlockHitResult hitResult
	) {
		if (com.makomi.config.RedstoneLinkConfig.canOpenPairingByPlacedBlock(player)) {
			WirelessBlockNodeSupport.openCorePairing(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int eventId, int eventParam) {
		Direction direction = state.getValue(FACING);
		boolean shouldExtend = isWirelessActive(level, pos);
		BlockState extendedState = state.setValue(EXTENDED, true);
		if (!level.isClientSide) {
			if (shouldExtend && (eventId == 1 || eventId == 2)) {
				level.setBlock(pos, extendedState, 2);
				return false;
			}
			if (!shouldExtend && eventId == 0) {
				return false;
			}
		}
		if (eventId == 0) {
			if (!moveBlocks(level, pos, direction, true)) {
				return false;
			}
			level.setBlock(pos, extendedState, 67);
			level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.25F + 0.6F);
			level.gameEvent(GameEvent.BLOCK_ACTIVATE, pos, GameEvent.Context.of(extendedState));
			return true;
		}
		if (eventId != 1 && eventId != 2) {
			return true;
		}

		BlockEntity frontBlockEntity = level.getBlockEntity(pos.relative(direction));
		if (frontBlockEntity instanceof PistonMovingBlockEntity movingBlockEntity) {
			movingBlockEntity.finalTick();
		}

		BlockState movingBaseState = Blocks.MOVING_PISTON
			.defaultBlockState()
			.setValue(MovingPistonBlock.FACING, direction)
			.setValue(MovingPistonBlock.TYPE, getPistonType());
		WirelessPistonNodeMoveSupport.captureMovingNode(level, pos, pos);
		level.setBlock(pos, movingBaseState, 20);
		level.setBlockEntity(
			MovingPistonBlock.newMovingBlockEntity(
				pos,
				movingBaseState,
				defaultBlockState().setValue(FACING, Direction.from3DDataValue(eventParam & 7)),
				direction,
				false,
				true
			)
		);
		level.blockUpdated(pos, movingBaseState.getBlock());
		movingBaseState.updateNeighbourShapes(level, pos, 2);

		BlockPos frontPos = pos.relative(direction);
		BlockPos pullingPos = frontPos.relative(direction);
		boolean handledByStickyPull = false;
		if (sticky) {
			handledByStickyPull = tryPullBlockOnRetract(level, pos, direction, pullingPos);
		}
		if (!handledByStickyPull) {
			level.removeBlock(frontPos, false);
		}
		level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.15F + 0.6F);
		level.gameEvent(GameEvent.BLOCK_DEACTIVATE, pos, GameEvent.Context.of(movingBaseState));
		return true;
	}

	/**
	 * 按无线激活态同步活塞伸缩事件。
	 */
	public void syncWirelessExtension(Level level, BlockPos pos, BlockState state, boolean active) {
		if (level.isClientSide) {
			return;
		}
		boolean extended = state.getValue(EXTENDED);
		Direction direction = state.getValue(FACING);
		if (active && !extended) {
			if (new WirelessPistonStructureResolver(level, pos, direction, true).resolve()) {
				level.blockEvent(pos, this, 0, direction.get3DDataValue());
			}
			return;
		}
		if (!active && extended) {
			int eventId = 1;
			BlockPos movingPos = pos.relative(direction, 2);
			BlockState movingState = level.getBlockState(movingPos);
			BlockEntity movingEntity = level.getBlockEntity(movingPos);
			if (
				movingState.is(Blocks.MOVING_PISTON)
					&& movingState.getValue(MovingPistonBlock.FACING) == direction
					&& movingEntity instanceof PistonMovingBlockEntity pistonMovingBlockEntity
					&& pistonMovingBlockEntity.isExtending()
					&& (
						pistonMovingBlockEntity.getProgress(0.0F) < 0.5F
							|| level.getGameTime() == pistonMovingBlockEntity.getLastTicked()
							|| (((ServerLevel) level).isHandlingTick())
					)
			) {
				eventId = 2;
			}
			level.blockEvent(pos, this, eventId, direction.get3DDataValue());
		}
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return state.getValue(EXTENDED);
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
		return false;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, EXTENDED);
	}

	/**
	 * 读取当前无线激活态。
	 */
	private static boolean isWirelessActive(Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof WirelessPistonBlockEntity blockEntity && blockEntity.isActive();
	}

	/**
	 * 复用原版活塞推进逻辑，仅把触发来源改为无线状态。
	 */
	private boolean moveBlocks(Level level, BlockPos pos, Direction direction, boolean extending) {
		BlockPos frontPos = pos.relative(direction);
		if (!extending && level.getBlockState(frontPos).is(ModBlocks.WIRELESS_PISTON_HEAD)) {
			level.setBlock(frontPos, Blocks.AIR.defaultBlockState(), 20);
		}

		WirelessPistonStructureResolver resolver = new WirelessPistonStructureResolver(level, pos, direction, extending);
		if (!resolver.resolve()) {
			return false;
		}

		Map<BlockPos, BlockState> movedStateMap = new HashMap<>();
		List<BlockPos> toPush = resolver.getToPush();
		List<BlockState> pushedStates = new ArrayList<>();
		for (BlockPos pushPos : toPush) {
			BlockState pushState = level.getBlockState(pushPos);
			pushedStates.add(pushState);
			movedStateMap.put(pushPos, pushState);
		}

		List<BlockPos> toDestroy = resolver.getToDestroy();
		BlockState[] changedStates = new BlockState[toPush.size() + toDestroy.size()];
		Direction moveDirection = extending ? direction : direction.getOpposite();
		int changedIndex = 0;

		for (int index = toDestroy.size() - 1; index >= 0; index--) {
			BlockPos destroyPos = toDestroy.get(index);
			BlockState destroyState = level.getBlockState(destroyPos);
			BlockEntity destroyEntity = destroyState.hasBlockEntity() ? level.getBlockEntity(destroyPos) : null;
			dropResources(destroyState, level, destroyPos, destroyEntity);
			level.setBlock(destroyPos, Blocks.AIR.defaultBlockState(), 18);
			level.gameEvent(GameEvent.BLOCK_DESTROY, destroyPos, GameEvent.Context.of(destroyState));
			if (!destroyState.is(net.minecraft.tags.BlockTags.FIRE)) {
				level.addDestroyBlockEffect(destroyPos, destroyState);
			}
			changedStates[changedIndex++] = destroyState;
		}

		for (int index = toPush.size() - 1; index >= 0; index--) {
			BlockPos originalPos = toPush.get(index);
			BlockState originalState = level.getBlockState(originalPos);
			BlockPos shiftedPos = originalPos.relative(moveDirection);
			movedStateMap.remove(shiftedPos);
			WirelessPistonNodeMoveSupport.captureMovingNode(level, originalPos, shiftedPos);
			BlockState movingState = Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, direction);
			level.setBlock(shiftedPos, movingState, 68);
			level.setBlockEntity(
				MovingPistonBlock.newMovingBlockEntity(shiftedPos, movingState, pushedStates.get(index), direction, extending, false)
			);
			changedStates[changedIndex++] = originalState;
		}

		if (extending) {
			BlockState headState = ModBlocks.WIRELESS_PISTON_HEAD
				.defaultBlockState()
				.setValue(PistonHeadBlock.FACING, direction)
				.setValue(PistonHeadBlock.TYPE, getPistonType());
			BlockState movingState = Blocks.MOVING_PISTON
				.defaultBlockState()
				.setValue(MovingPistonBlock.FACING, direction)
				.setValue(MovingPistonBlock.TYPE, getPistonType());
			movedStateMap.remove(frontPos);
			level.setBlock(frontPos, movingState, 68);
			level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(frontPos, movingState, headState, direction, true, true));
		}

		BlockState airState = Blocks.AIR.defaultBlockState();
		for (BlockPos clearedPos : movedStateMap.keySet()) {
			level.setBlock(clearedPos, airState, 82);
			WirelessPistonNodeMoveSupport.finishSourceMove(level, clearedPos);
		}

		for (Map.Entry<BlockPos, BlockState> entry : movedStateMap.entrySet()) {
			BlockPos clearedPos = entry.getKey();
			BlockState previousState = entry.getValue();
			previousState.updateIndirectNeighbourShapes(level, clearedPos, 2);
			airState.updateNeighbourShapes(level, clearedPos, 2);
			airState.updateIndirectNeighbourShapes(level, clearedPos, 2);
		}

		changedIndex = 0;
		for (int index = toDestroy.size() - 1; index >= 0; index--) {
			BlockState changedState = changedStates[changedIndex++];
			BlockPos destroyPos = toDestroy.get(index);
			changedState.updateIndirectNeighbourShapes(level, destroyPos, 2);
			level.updateNeighborsAt(destroyPos, changedState.getBlock());
		}

		for (int index = toPush.size() - 1; index >= 0; index--) {
			level.updateNeighborsAt(toPush.get(index), changedStates[changedIndex++].getBlock());
		}

		if (extending) {
			level.updateNeighborsAt(frontPos, ModBlocks.WIRELESS_PISTON_HEAD);
		}
		return true;
	}

	/**
	 * 返回当前无线化活塞对应的原版活塞类型。
	 */
	public final PistonType getPistonType() {
		return sticky ? PistonType.STICKY : PistonType.DEFAULT;
	}

	/**
	 * 返回当前无线化活塞是否为粘性变体。
	 */
	public final boolean isSticky() {
		return sticky;
	}

	/**
	 * 回缩时尝试拉回前方一个方块。
	 * <p>
	 * 这里只复用既有无线结构解析与节点搬运协议，不额外放宽新的可推块边界。
	 * </p>
	 */
	private boolean tryPullBlockOnRetract(Level level, BlockPos pistonPos, Direction direction, BlockPos pullingPos) {
		BlockState pullingState = level.getBlockState(pullingPos);
		if (pullingState.isAir()) {
			return false;
		}
		if (pullingState.is(Blocks.MOVING_PISTON)) {
			BlockEntity blockEntity = level.getBlockEntity(pullingPos);
			if (
				blockEntity instanceof PistonMovingBlockEntity pistonMovingBlockEntity
					&& pistonMovingBlockEntity.getDirection() == direction
					&& pistonMovingBlockEntity.isExtending()
			) {
				pistonMovingBlockEntity.finalTick();
				return true;
			}
		}
		if (!canPullBlock(level, pullingPos, pullingState, direction)) {
			return false;
		}
		return moveBlocks(level, pistonPos, direction, false);
	}

	/**
	 * 判断回缩时前方方块是否允许被当前无线化粘性活塞拉回。
	 */
	private boolean canPullBlock(Level level, BlockPos pos, BlockState state, Direction direction) {
		if (state.isAir()) {
			return false;
		}
		if (state.is(Blocks.OBSIDIAN)
			|| state.is(Blocks.CRYING_OBSIDIAN)
			|| state.is(Blocks.RESPAWN_ANCHOR)
			|| state.is(Blocks.REINFORCED_DEEPSLATE)) {
			return false;
		}
		if (state.getDestroySpeed(level, pos) == -1.0F) {
			return false;
		}
		if (state.is(ModBlocks.WIRELESS_PISTON_HEAD)) {
			return false;
		}
		return WirelessPistonStructureResolver.canPushForWirelessPiston(
			state,
			level,
			pos,
			direction.getOpposite(),
			false,
			direction
		);
	}

	private net.minecraft.world.level.block.entity.BlockEntityType<? extends WirelessPistonBlockEntity> resolveBlockEntityType() {
		return sticky ? com.makomi.registry.ModBlockEntities.WIRELESS_STICKY_PISTON : com.makomi.registry.ModBlockEntities.WIRELESS_PISTON;
	}
}
