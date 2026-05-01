package com.makomi.block;

import com.makomi.block.entity.HidePulseEmitterBlockEntity;
import com.makomi.block.entity.LinkTriggerSourceBlockEntity;
import com.makomi.data.HideNodeShapeAccessSupport;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 隐藏脉冲发射器。
 * <p>
 * 本体仍为真实 `triggerSource` 节点，但默认不可见且只在佩戴智能眼镜时允许交互。
 * </p>
 */
public class HidePulseEmitterBlock extends LinkPulseEmitterBlock {
	public HidePulseEmitterBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.setAllFaces(defaultBlockState(), false));
	}

	@Override
	protected LinkTriggerSourceBlockEntity createEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new HidePulseEmitterBlockEntity(blockPos, blockState);
	}

	@Override
	protected RenderShape getRenderShape(BlockState blockState) {
		return RenderShape.INVISIBLE;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return NodeFaceSetBlockStateSupport.setAllFaces(defaultBlockState(), false);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return HideNodeShapeAccessSupport.resolveInteractionShape(context);
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return HideNodeShapeAccessSupport.resolveInteractionShape(context);
	}
}
