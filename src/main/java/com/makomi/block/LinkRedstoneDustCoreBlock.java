package com.makomi.block;

import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.network.PairingNetwork;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

public class LinkRedstoneDustCoreBlock extends BaseEntityBlock {
	public static final MapCodec<LinkRedstoneDustCoreBlock> CODEC = simpleCodec(LinkRedstoneDustCoreBlock::new);
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
	public static final EnumProperty<RedstoneSide> NORTH = net.minecraft.world.level.block.RedStoneWireBlock.NORTH;
	public static final EnumProperty<RedstoneSide> EAST = net.minecraft.world.level.block.RedStoneWireBlock.EAST;
	public static final EnumProperty<RedstoneSide> SOUTH = net.minecraft.world.level.block.RedStoneWireBlock.SOUTH;
	public static final EnumProperty<RedstoneSide> WEST = net.minecraft.world.level.block.RedStoneWireBlock.WEST;
	private static final Map<Direction, EnumProperty<RedstoneSide>> PROPERTY_BY_DIRECTION =
		net.minecraft.world.level.block.RedStoneWireBlock.PROPERTY_BY_DIRECTION;

	public LinkRedstoneDustCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(
			stateDefinition
				.any()
				.setValue(ACTIVE, false)
				.setValue(NORTH, RedstoneSide.NONE)
				.setValue(EAST, RedstoneSide.NONE)
				.setValue(SOUTH, RedstoneSide.NONE)
				.setValue(WEST, RedstoneSide.NONE)
		);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkRedstoneDustCoreBlockEntity(blockPos, blockState);
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
	public BlockState getStateForPlacement(BlockPlaceContext blockPlaceContext) {
		return getConnectionState(blockPlaceContext.getLevel(), defaultBlockState(), blockPlaceContext.getClickedPos());
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
		if (direction == Direction.DOWN) {
			return canSurviveOn(level, neighborPos, neighborState) ? state : Blocks.AIR.defaultBlockState();
		}
		if (direction == Direction.UP) {
			return getConnectionState(level, state, pos);
		}
		if (!direction.getAxis().isHorizontal()) {
			return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
		}

		EnumProperty<RedstoneSide> property = PROPERTY_BY_DIRECTION.get(direction);
		if (property == null) {
			return state;
		}

		RedstoneSide connectingSide = getConnectingSide(level, pos, direction);
		return getConnectionState(level, state.setValue(property, connectingSide), pos);
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		BlockPos belowPos = pos.below();
		return canSurviveOn(level, belowPos, level.getBlockState(belowPos));
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
				coreBlockEntity.unregisterNode();
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
		return state.getValue(ACTIVE) ? 15 : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(ACTIVE) ? 15 : 0;
	}

	@Override
	protected InteractionResult useWithoutItem(
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		BlockHitResult hitResult
	) {
		if (player.isShiftKeyDown() && isPlayerEmptyHanded(player)) {
			openPairingScreen(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		if (!level.isClientSide && level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			coreBlockEntity.triggerByPlayer();
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ACTIVE, NORTH, EAST, SOUTH, WEST);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			coreBlockEntity.onPulseTick();
		}
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

	private static boolean isPlayerEmptyHanded(Player player) {
		return player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
	}

	private BlockState getConnectionState(BlockGetter level, BlockState state, BlockPos pos) {
		boolean wasDot = isDot(state);
		BlockState withConnections = getMissingConnections(
			level,
			defaultBlockState().setValue(ACTIVE, state.getValue(ACTIVE)),
			pos
		);
		if (wasDot && isDot(withConnections)) {
			return withConnections;
		}

		boolean northConnected = withConnections.getValue(NORTH).isConnected();
		boolean southConnected = withConnections.getValue(SOUTH).isConnected();
		boolean eastConnected = withConnections.getValue(EAST).isConnected();
		boolean westConnected = withConnections.getValue(WEST).isConnected();

		boolean noNorthSouth = !northConnected && !southConnected;
		boolean noEastWest = !eastConnected && !westConnected;

		if (!westConnected && noNorthSouth) {
			withConnections = withConnections.setValue(WEST, RedstoneSide.SIDE);
		}
		if (!eastConnected && noNorthSouth) {
			withConnections = withConnections.setValue(EAST, RedstoneSide.SIDE);
		}
		if (!northConnected && noEastWest) {
			withConnections = withConnections.setValue(NORTH, RedstoneSide.SIDE);
		}
		if (!southConnected && noEastWest) {
			withConnections = withConnections.setValue(SOUTH, RedstoneSide.SIDE);
		}
		return withConnections;
	}

	private BlockState getMissingConnections(BlockGetter level, BlockState state, BlockPos pos) {
		BlockState aboveState = level.getBlockState(pos.above());
		boolean canConnectUp = !aboveState.isRedstoneConductor(level, pos.above());

		for (Direction direction : Direction.Plane.HORIZONTAL) {
			EnumProperty<RedstoneSide> property = PROPERTY_BY_DIRECTION.get(direction);
			if (property == null || state.getValue(property).isConnected()) {
				continue;
			}
			state = state.setValue(property, getConnectingSide(level, pos, direction, canConnectUp));
		}
		return state;
	}

	private RedstoneSide getConnectingSide(BlockGetter level, BlockPos pos, Direction direction) {
		BlockPos abovePos = pos.above();
		boolean canConnectUp = !level.getBlockState(abovePos).isRedstoneConductor(level, abovePos);
		return getConnectingSide(level, pos, direction, canConnectUp);
	}

	private RedstoneSide getConnectingSide(BlockGetter level, BlockPos pos, Direction direction, boolean canConnectUp) {
		BlockPos neighborPos = pos.relative(direction);
		BlockState neighborState = level.getBlockState(neighborPos);

		if (canConnectUp) {
			boolean canClimb = neighborState.getBlock() instanceof TrapDoorBlock || canSurviveOn(level, neighborPos, neighborState);
			if (canClimb && shouldConnectTo(level.getBlockState(neighborPos.above()))) {
				return neighborState.isFaceSturdy(level, neighborPos, direction.getOpposite()) ? RedstoneSide.UP : RedstoneSide.SIDE;
			}
		}

		if (
			shouldConnectTo(neighborState, direction)
				|| (!neighborState.isRedstoneConductor(level, neighborPos) && shouldConnectTo(level.getBlockState(neighborPos.below())))
		) {
			return RedstoneSide.SIDE;
		}
		return RedstoneSide.NONE;
	}

	private static boolean shouldConnectTo(BlockState state) {
		return shouldConnectTo(state, null);
	}

	private static boolean shouldConnectTo(BlockState state, Direction direction) {
		if (state.is(Blocks.REDSTONE_WIRE) || state.getBlock() instanceof LinkRedstoneDustCoreBlock) {
			return true;
		}
		if (state.is(Blocks.REPEATER)) {
			Direction facing = state.getValue(RepeaterBlock.FACING);
			return facing == direction || facing.getOpposite() == direction;
		}
		if (state.is(Blocks.OBSERVER)) {
			return direction == state.getValue(ObserverBlock.FACING);
		}
		return state.isSignalSource() && direction != null;
	}

	private static boolean isDot(BlockState state) {
		return !state.getValue(NORTH).isConnected()
			&& !state.getValue(SOUTH).isConnected()
			&& !state.getValue(EAST).isConnected()
			&& !state.getValue(WEST).isConnected();
	}

	private boolean canSurviveOn(BlockGetter level, BlockPos pos, BlockState state) {
		return state.isFaceSturdy(level, pos, Direction.UP) || state.is(Blocks.HOPPER);
	}
}
