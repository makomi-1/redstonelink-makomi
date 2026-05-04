package com.makomi.block;

import com.makomi.registry.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * 无线化活塞结构解析器。
 * <p>
 * 逻辑整体沿用原版活塞结构解析，只额外放宽一件事：
 * 无线节点方块即使带有方块实体，也允许被无线化活塞推进。
 * </p>
 */
public final class WirelessPistonStructureResolver {
	private static final int MAX_PUSH = 12;

	private final Level level;
	private final BlockPos pistonPos;
	private final boolean extending;
	private final BlockPos startPos;
	private final Direction pushDirection;
	private final Direction pistonFacing;
	private final List<BlockPos> toPush = new ArrayList<>();
	private final List<BlockPos> toDestroy = new ArrayList<>();

	public WirelessPistonStructureResolver(Level level, BlockPos pistonPos, Direction pistonFacing, boolean extending) {
		this.level = level;
		this.pistonPos = pistonPos;
		this.pistonFacing = pistonFacing;
		this.extending = extending;
		if (extending) {
			this.pushDirection = pistonFacing;
			this.startPos = pistonPos.relative(pistonFacing);
		} else {
			this.pushDirection = pistonFacing.getOpposite();
			this.startPos = pistonPos.relative(pistonFacing, 2);
		}
	}

	/**
	 * 解析本次伸缩会影响的推进列表与销毁列表。
	 */
	public boolean resolve() {
		toPush.clear();
		toDestroy.clear();

		BlockState startState = level.getBlockState(startPos);
		if (!canPushBlock(startState, level, startPos, pushDirection, false, pistonFacing)) {
			if (extending && startState.getPistonPushReaction() == PushReaction.DESTROY) {
				toDestroy.add(startPos);
				return true;
			}
			return false;
		}
		if (!addBlockLine(startPos, pushDirection)) {
			return false;
		}
		for (int index = 0; index < toPush.size(); index++) {
			BlockPos pushPos = toPush.get(index);
			if (isStickyBlock(level.getBlockState(pushPos)) && !addBranchingBlocks(pushPos)) {
				return false;
			}
		}
		return true;
	}

	public List<BlockPos> getToPush() {
		return toPush;
	}

	public List<BlockPos> getToDestroy() {
		return toDestroy;
	}

	/**
	 * 沿原版推进方向收集一条可移动链。
	 */
	private boolean addBlockLine(BlockPos originPos, Direction lineDirection) {
		BlockState originState = level.getBlockState(originPos);
		if (originState.isAir()) {
			return true;
		}
		if (!canPushBlock(originState, level, originPos, pushDirection, false, lineDirection)) {
			return true;
		}
		if (originPos.equals(pistonPos) || toPush.contains(originPos)) {
			return true;
		}

		int backwardCount = 1;
		if (backwardCount + toPush.size() > MAX_PUSH) {
			return false;
		}

		while (isStickyBlock(originState)) {
			BlockPos backwardPos = originPos.relative(pushDirection.getOpposite(), backwardCount);
			BlockState previousState = originState;
			originState = level.getBlockState(backwardPos);
			if (originState.isAir()
				|| !canStickToEachOther(previousState, originState)
				|| !canPushBlock(originState, level, backwardPos, pushDirection, false, pushDirection.getOpposite())
				|| backwardPos.equals(pistonPos)) {
				break;
			}
			if (++backwardCount + toPush.size() > MAX_PUSH) {
				return false;
			}
		}

		int appendedCount = 0;
		for (int index = backwardCount - 1; index >= 0; index--) {
			toPush.add(originPos.relative(pushDirection.getOpposite(), index));
			appendedCount++;
		}

		int forwardOffset = 1;
		while (true) {
			BlockPos forwardPos = originPos.relative(pushDirection, forwardOffset);
			int existingIndex = toPush.indexOf(forwardPos);
			if (existingIndex > -1) {
				reorderPushList(appendedCount, existingIndex);
				for (int index = 0; index <= existingIndex + appendedCount; index++) {
					BlockPos branchPos = toPush.get(index);
					if (isStickyBlock(level.getBlockState(branchPos)) && !addBranchingBlocks(branchPos)) {
						return false;
					}
				}
				return true;
			}

			BlockState forwardState = level.getBlockState(forwardPos);
			if (forwardState.isAir()) {
				return true;
			}
			if (!canPushBlock(forwardState, level, forwardPos, pushDirection, true, pushDirection) || forwardPos.equals(pistonPos)) {
				return false;
			}
			if (forwardState.getPistonPushReaction() == PushReaction.DESTROY && !isMovableWirelessNode(forwardState)) {
				toDestroy.add(forwardPos);
				return true;
			}
			if (toPush.size() >= MAX_PUSH) {
				return false;
			}

			toPush.add(forwardPos);
			appendedCount++;
			forwardOffset++;
		}
	}

	/**
	 * 处理粘液块 / 蜂蜜块的侧向粘连传播。
	 */
	private boolean addBranchingBlocks(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		for (Direction direction : Direction.values()) {
			if (direction.getAxis() == pushDirection.getAxis()) {
				continue;
			}
			BlockPos branchPos = pos.relative(direction);
			BlockState branchState = level.getBlockState(branchPos);
			if (canStickToEachOther(branchState, state) && !addBlockLine(branchPos, direction)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 原版活塞在推进列表环撞时会重排队列，这里保持同样处理。
	 */
	private void reorderPushList(int movedTailSize, int collisionIndex) {
		List<BlockPos> head = new ArrayList<>(toPush.subList(0, collisionIndex));
		List<BlockPos> movedTail = new ArrayList<>(toPush.subList(toPush.size() - movedTailSize, toPush.size()));
		List<BlockPos> middle = new ArrayList<>(toPush.subList(collisionIndex, toPush.size() - movedTailSize));
		toPush.clear();
		toPush.addAll(head);
		toPush.addAll(movedTail);
		toPush.addAll(middle);
	}

	/**
	 * 在原版可推动判定基础上，仅对白名单无线节点放宽限制。
	 */
	static boolean canPushForWirelessPiston(
		BlockState state,
		Level level,
		BlockPos pos,
		Direction moveDirection,
		boolean canDestroy,
		Direction pistonDirection
	) {
		if (PistonBaseBlock.isPushable(state, level, pos, moveDirection, canDestroy, pistonDirection)) {
			return true;
		}
		if (!isMovableWirelessNode(state)) {
			return false;
		}
		if (pos.getY() < level.getMinBuildHeight()
			|| pos.getY() > level.getMaxBuildHeight() - 1
			|| !level.getWorldBorder().isWithinBounds(pos)) {
			return false;
		}
		if (state.isAir()) {
			return true;
		}
		if (state.is(Blocks.OBSIDIAN)
			|| state.is(Blocks.CRYING_OBSIDIAN)
			|| state.is(Blocks.RESPAWN_ANCHOR)
			|| state.is(Blocks.REINFORCED_DEEPSLATE)) {
			return false;
		}
		if (moveDirection == Direction.DOWN && pos.getY() == level.getMinBuildHeight()) {
			return false;
		}
		if (moveDirection == Direction.UP && pos.getY() == level.getMaxBuildHeight() - 1) {
			return false;
		}
		if (state.is(ModBlocks.WIRELESS_PISTON) && state.getValue(WirelessPistonBlock.EXTENDED)) {
			return false;
		}
		if (state.getDestroySpeed(level, pos) == -1.0F) {
			return false;
		}
		switch (state.getPistonPushReaction()) {
			case BLOCK:
				return false;
			case DESTROY:
				return canDestroy;
			case PUSH_ONLY:
				return moveDirection == pistonDirection;
			default:
				break;
		}
		return state.getDestroySpeed(level, pos) != -1.0F;
	}

	private static boolean canPushBlock(
		BlockState state,
		Level level,
		BlockPos pos,
		Direction moveDirection,
		boolean canDestroy,
		Direction pistonDirection
	) {
		return canPushForWirelessPiston(state, level, pos, moveDirection, canDestroy, pistonDirection);
	}

	/**
	 * 仅放行已经接入“移动不是销毁”语义的节点方块。
	 * <p>
	 * 现阶段包含：
	 * 1. 无线化原型块；
	 * 2. 已补活塞搬运豁免的可见核心块。
	 * </p>
	 */
	private static boolean isMovableWirelessNode(BlockState state) {
		return state.is(ModBlocks.WIRELESS_LEVER)
			|| state.is(ModBlocks.WIRELESS_STONE_BUTTON)
			|| state.is(ModBlocks.WIRELESS_STONE_PRESSURE_PLATE)
			|| state.is(ModBlocks.WIRELESS_PISTON)
			|| state.is(ModBlocks.WIRELESS_STICKY_PISTON)
			|| state.is(ModBlocks.WIRELESS_REDSTONE_LAMP)
			|| state.is(ModBlocks.WIRELESS_SEA_LANTERN)
			|| state.is(ModBlocks.LINK_REDSTONE_CORE)
			|| state.is(ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT);
	}

	private static boolean isStickyBlock(BlockState state) {
		return state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK);
	}

	private static boolean canStickToEachOther(BlockState state, BlockState adjacentState) {
		if (state.is(Blocks.HONEY_BLOCK) && adjacentState.is(Blocks.SLIME_BLOCK)) {
			return false;
		}
		if (state.is(Blocks.SLIME_BLOCK) && adjacentState.is(Blocks.HONEY_BLOCK)) {
			return false;
		}
		return isStickyBlock(state) || isStickyBlock(adjacentState);
	}
}
