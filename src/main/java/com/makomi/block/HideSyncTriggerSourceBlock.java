package com.makomi.block;

import com.makomi.block.entity.HideSyncTriggerSourceBlockEntity;
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
 * 隐藏同步 `triggerSource`。
 * <p>
 * 本体仍为真实 `triggerSource` 节点，只在选中的输入面采样红石，并在普通视角下保持隐藏。
 * </p>
 */
public class HideSyncTriggerSourceBlock extends LinkSyncEmitterBlock {
	public HideSyncTriggerSourceBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.setAllFaces(defaultBlockState(), false));
	}

	@Override
	protected LinkTriggerSourceBlockEntity createEmitterBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new HideSyncTriggerSourceBlockEntity(blockPos, blockState);
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
