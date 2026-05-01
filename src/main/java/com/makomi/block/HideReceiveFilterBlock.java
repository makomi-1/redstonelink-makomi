package com.makomi.block;

import com.makomi.block.entity.HideReceiveFilterBlockEntity;
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
 * 隐藏接收过滤器。
 * <p>
 * 仍然执行真实接收过滤逻辑，但默认隐藏，且需佩戴智能眼镜才能命中与编辑。
 * </p>
 */
public class HideReceiveFilterBlock extends LinkReceiveFilterBlock {
	public static final MapCodec<HideReceiveFilterBlock> CODEC = simpleCodec(HideReceiveFilterBlock::new);

	public HideReceiveFilterBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.setAllFaces(defaultBlockState(), false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new HideReceiveFilterBlockEntity(blockPos, blockState);
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
