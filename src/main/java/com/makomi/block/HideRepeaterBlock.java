package com.makomi.block;

import com.makomi.block.entity.HideRepeaterBlockEntity;
import com.makomi.data.HideNodeShapeAccessSupport;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 隐藏转发器。
 * <p>
 * 本体仍承载真实转发器的 `core + triggerSource` 双身份，只在普通视角下保持隐藏。
 * </p>
 */
public class HideRepeaterBlock extends LinkRepeaterBlock {
	public static final MapCodec<HideRepeaterBlock> CODEC = simpleCodec(HideRepeaterBlock::new);

	public HideRepeaterBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.setAllFaces(defaultBlockState(), false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new HideRepeaterBlockEntity(blockPos, blockState);
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
