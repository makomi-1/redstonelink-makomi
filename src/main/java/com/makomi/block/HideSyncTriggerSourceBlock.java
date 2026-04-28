package com.makomi.block;

import com.makomi.block.entity.HideSyncTriggerSourceBlockEntity;
import com.makomi.block.entity.LinkTriggerSourceBlockEntity;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * 隐藏同步 `triggerSource`。
 * <p>
 * 本体仍为真实 `triggerSource` 节点，只在选中的输入面采样红石，并在普通视角下保持隐藏。
 * </p>
 */
public class HideSyncTriggerSourceBlock extends LinkSyncEmitterBlock {
	public HideSyncTriggerSourceBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.withSingleFace(defaultBlockState(), Direction.NORTH));
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
		Direction clickedFace = context == null ? Direction.NORTH : context.getClickedFace();
		return NodeFaceSetBlockStateSupport.withSingleFace(defaultBlockState(), clickedFace == null ? Direction.NORTH : clickedFace);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		builder.add(POWERED);
		NodeFaceSetBlockStateSupport.appendProperties(builder);
	}

	@Override
	protected int resolveInputSignalStrength(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		int realInputPower = 0;
		if (NodeFaceSetBlockStateSupport.hasFaceProperties(state)) {
			List<Direction> enabledFaces = NodeFaceSetBlockStateSupport.resolveEnabledFaces(state);
			for (Direction direction : enabledFaces) {
				realInputPower = Math.max(realInputPower, Math.max(0, level.getSignal(pos.relative(direction), direction)));
			}
		}
		int simulatedInputPower = 0;
		if (level.getBlockEntity(pos) instanceof LinkTriggerSourceBlockEntity triggerSourceBlockEntity) {
			simulatedInputPower = triggerSourceBlockEntity.getSimulatedInputPower();
		}
		return Math.max(realInputPower, simulatedInputPower);
	}
}
