package com.makomi.block;

import com.makomi.block.entity.HideCoreBlockEntity;
import com.makomi.data.HideNodeShapeAccessSupport;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 隐藏核心块。
 * <p>
 * 本体仍为真实 `core` 节点，只是在普通视角下不渲染，并通过面集限制输出方向。
 * </p>
 */
public class HideCoreBlock extends LinkCoreBlock {
	public static final MapCodec<HideCoreBlock> CODEC = simpleCodec(HideCoreBlock::new);

	public HideCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.withSingleFace(defaultBlockState(), Direction.NORTH));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new HideCoreBlockEntity(blockPos, blockState);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState blockState) {
		return RenderShape.INVISIBLE;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction clickedFace = context == null ? Direction.NORTH : context.getClickedFace();
		Direction storedFace = clickedFace == null ? Direction.SOUTH : clickedFace.getOpposite();
		return NodeFaceSetBlockStateSupport.withSingleFace(defaultBlockState(), storedFace);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return HideNodeShapeAccessSupport.resolveInteractionShape(context);
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return HideNodeShapeAccessSupport.resolveInteractionShape(context);
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return NodeFaceSetBlockStateSupport.isFaceEnabled(state, direction) ? super.getSignal(state, level, pos, direction) : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return NodeFaceSetBlockStateSupport.isFaceEnabled(state, direction)
			? super.getDirectSignal(state, level, pos, direction)
			: 0;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		builder.add(ACTIVE);
		NodeFaceSetBlockStateSupport.appendProperties(builder);
	}
}
