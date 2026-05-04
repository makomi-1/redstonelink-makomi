package com.makomi.block;

import com.makomi.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;

/**
 * 无线化活塞头。
 * <p>
 * 复用原版活塞头的几何与破坏语义，只放宽“合法底座”判定，
 * 让头部在伸出后能够识别无线化活塞本体。
 * </p>
 */
public class WirelessPistonHeadBlock extends PistonHeadBlock {
	public static final MapCodec<WirelessPistonHeadBlock> CODEC = simpleCodec(WirelessPistonHeadBlock::new);

	public WirelessPistonHeadBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected MapCodec<PistonHeadBlock> codec() {
		return (MapCodec<PistonHeadBlock>) (MapCodec<?>) CODEC;
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
		return direction.getOpposite() == state.getValue(FACING) && !state.canSurvive(level, pos)
			? Blocks.AIR.defaultBlockState()
			: super.updateShape(state, direction, neighborState, level, pos, neighborPos);
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		BlockPos basePos = pos.relative(state.getValue(FACING).getOpposite());
		return isFittingBase(state, level.getBlockState(basePos));
	}

	@Override
	protected void neighborChanged(
		BlockState state,
		Level level,
		BlockPos pos,
		Block neighborBlock,
		BlockPos neighborPos,
		boolean movedByPiston
	) {
		if (!state.canSurvive(level, pos)) {
			level.destroyBlock(pos, true);
		}
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide && player.getAbilities().instabuild) {
			BlockPos basePos = pos.relative(state.getValue(FACING).getOpposite());
			BlockState baseState = level.getBlockState(basePos);
			if (isFittingBase(state, baseState)) {
				level.destroyBlock(basePos, false);
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			BlockPos basePos = pos.relative(state.getValue(FACING).getOpposite());
			BlockState baseState = level.getBlockState(basePos);
			if (isFittingBase(state, baseState)) {
				level.destroyBlock(basePos, true);
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
		return new ItemStack(ModBlocks.WIRELESS_PISTON);
	}

	/**
	 * 判断头部后方是否接到了合法底座。
	 * <p>
	 * 兼容原版活塞、原版移动活塞，以及无线化活塞本体。
	 * </p>
	 */
	private static boolean isFittingBase(BlockState headState, BlockState baseState) {
		PistonType pistonType = headState.getValue(TYPE);
		Block expectedBase = pistonType == PistonType.STICKY ? Blocks.STICKY_PISTON : Blocks.PISTON;
		if (baseState.is(expectedBase)
			&& baseState.getValue(PistonBaseBlock.EXTENDED)
			&& baseState.getValue(PistonBaseBlock.FACING) == headState.getValue(FACING)) {
			return true;
		}
		if (baseState.is(ModBlocks.WIRELESS_PISTON)
			&& baseState.getValue(WirelessPistonBlock.EXTENDED)
			&& baseState.getValue(WirelessPistonBlock.FACING) == headState.getValue(FACING)) {
			return true;
		}
		return baseState.is(Blocks.MOVING_PISTON) && baseState.getValue(MovingPistonBlock.FACING) == headState.getValue(FACING);
	}
}
