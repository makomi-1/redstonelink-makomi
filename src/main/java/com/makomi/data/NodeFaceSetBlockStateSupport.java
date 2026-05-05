package com.makomi.data;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 节点面集 `BlockState` 支持工具。
 * <p>
 * 统一承接隐藏/可见节点的输入输出面集配置，并为运行时采样提供共享入口。
 * </p>
 */
public final class NodeFaceSetBlockStateSupport {
	public static final BooleanProperty FACE_DOWN = BooleanProperty.create("face_down");
	public static final BooleanProperty FACE_UP = BooleanProperty.create("face_up");
	public static final BooleanProperty FACE_NORTH = BooleanProperty.create("face_north");
	public static final BooleanProperty FACE_SOUTH = BooleanProperty.create("face_south");
	public static final BooleanProperty FACE_WEST = BooleanProperty.create("face_west");
	public static final BooleanProperty FACE_EAST = BooleanProperty.create("face_east");

	private NodeFaceSetBlockStateSupport() {
	}

	/**
	 * 向方块状态定义中追加 6 个面集属性。
	 */
	public static void appendProperties(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACE_DOWN, FACE_UP, FACE_NORTH, FACE_SOUTH, FACE_WEST, FACE_EAST);
	}

	/**
	 * 判断当前状态是否完整包含面集属性。
	 */
	public static boolean hasFaceProperties(BlockState state) {
		return state != null
			&& state.hasProperty(FACE_DOWN)
			&& state.hasProperty(FACE_UP)
			&& state.hasProperty(FACE_NORTH)
			&& state.hasProperty(FACE_SOUTH)
			&& state.hasProperty(FACE_WEST)
			&& state.hasProperty(FACE_EAST);
	}

	/**
	 * 判断指定面当前是否启用。
	 */
	public static boolean isFaceEnabled(BlockState state, Direction face) {
		if (!hasFaceProperties(state) || face == null) {
			return false;
		}
		return state.getValue(propertyOf(face));
	}

	/**
	 * 将指定面设置为目标启用状态。
	 */
	public static BlockState setFaceEnabled(BlockState state, Direction face, boolean enabled) {
		if (!hasFaceProperties(state) || face == null) {
			return state;
		}
		return state.setValue(propertyOf(face), enabled);
	}

	/**
	 * 切换指定面的启用状态。
	 */
	public static BlockState toggleFace(BlockState state, Direction face) {
		if (!hasFaceProperties(state) || face == null) {
			return state;
		}
		return state.setValue(propertyOf(face), !state.getValue(propertyOf(face)));
	}

	/**
	 * 批量设置全部面的启用状态。
	 */
	public static BlockState setAllFaces(BlockState state, boolean enabled) {
		if (!hasFaceProperties(state)) {
			return state;
		}
		BlockState updatedState = state;
		for (Direction direction : Direction.values()) {
			updatedState = updatedState.setValue(propertyOf(direction), enabled);
		}
		return updatedState;
	}

	/**
	 * 仅保留一个启用面，其他面全部清空。
	 */
	public static BlockState withSingleFace(BlockState state, Direction face) {
		if (!hasFaceProperties(state)) {
			return state;
		}
		BlockState updatedState = setAllFaces(state, false);
		return face == null ? updatedState : updatedState.setValue(propertyOf(face), true);
	}

	/**
	 * 将来源状态中的面集真值复制到目标状态。
	 * <p>
	 * 仅当来源和目标都完整声明了 6 个面属性时才执行复制；
	 * 否则直接返回目标状态，避免把不兼容状态误写入运行时流程。
	 * </p>
	 */
	public static BlockState copyFaces(BlockState sourceState, BlockState targetState) {
		if (!hasFaceProperties(sourceState) || !hasFaceProperties(targetState)) {
			return targetState;
		}
		BlockState updatedState = targetState;
		for (Direction direction : Direction.values()) {
			updatedState = updatedState.setValue(propertyOf(direction), sourceState.getValue(propertyOf(direction)));
		}
		return updatedState;
	}

	/**
	 * 解析当前已启用的全部方向。
	 */
	public static List<Direction> resolveEnabledFaces(BlockState state) {
		if (!hasFaceProperties(state)) {
			return List.of();
		}
		List<Direction> enabledFaces = new ArrayList<>(6);
		for (Direction direction : Direction.values()) {
			if (state.getValue(propertyOf(direction))) {
				enabledFaces.add(direction);
			}
		}
		return List.copyOf(enabledFaces);
	}

	/**
	 * 按当前面集规则采样邻居输入强度。
	 * <p>
	 * 若方块未声明面集属性，则保持原版“全向邻居最大输入”语义；
	 * 若方块声明了面集属性，则只对启用面取最大值，空面集返回 0。
	 * </p>
	 */
	public static int sampleNeighborSignalStrength(Level level, BlockPos pos, BlockState state) {
		if (level == null || pos == null) {
			return 0;
		}
		if (!hasFaceProperties(state)) {
			return Math.max(0, level.getBestNeighborSignal(pos));
		}
		List<Direction> enabledFaces = resolveEnabledFaces(state);
		if (enabledFaces.isEmpty()) {
			return 0;
		}
		int strongestSignal = 0;
		for (Direction direction : enabledFaces) {
			strongestSignal = Math.max(strongestSignal, Math.max(0, level.getSignal(pos.relative(direction), direction)));
		}
		return strongestSignal;
	}

	/**
	 * 生成适合日志/消息的稳定面集签名。
	 */
	public static String buildEnabledFaceTokenText(BlockState state) {
		List<Direction> enabledFaces = resolveEnabledFaces(state);
		if (enabledFaces.isEmpty()) {
			return "-";
		}
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < enabledFaces.size(); index++) {
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(enabledFaces.get(index).getName());
		}
		return builder.toString();
	}

	private static BooleanProperty propertyOf(Direction direction) {
		return switch (direction) {
			case DOWN -> FACE_DOWN;
			case UP -> FACE_UP;
			case NORTH -> FACE_NORTH;
			case SOUTH -> FACE_SOUTH;
			case WEST -> FACE_WEST;
			case EAST -> FACE_EAST;
		};
	}
}
