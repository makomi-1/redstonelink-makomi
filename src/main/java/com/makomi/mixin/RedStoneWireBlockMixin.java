package com.makomi.mixin;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让“顶面核心红石粉”在原版 redstone wire 计算中以最小改动方式参与：
 * 1. 连接判定同类化；
 * 2. wire 功率读取同类化；
 * 3. 邻居输入读取补齐“跨实例 shouldSignal 不共享”导致的锁定问题。
 */
@Mixin(RedStoneWireBlock.class)
public class RedStoneWireBlockMixin {
	/**
	 * 桥接 getWireSignal 的“state.is(this)”硬编码：
	 * - 任意 wire 读取顶面核心粉时，按其 POWER 读取；
	 * - 核心粉读取原版 wire 时，按原版 wire POWER 读取。
	 */
	@Inject(method = "getWireSignal", at = @At("HEAD"), cancellable = true)
	private void injectTopCoreWireSignal(BlockState state, CallbackInfoReturnable<Integer> cir) {
		boolean thisIsCoreWire = (Object) this instanceof LinkRedstoneDustCoreBlock;
		if (state.getBlock() instanceof LinkRedstoneDustCoreBlock) {
			cir.setReturnValue(LinkRedstoneDustCoreBlock.getTopAttachedWireSignal(state));
			return;
		}
		if (thisIsCoreWire && state.is(Blocks.REDSTONE_WIRE)) {
			cir.setReturnValue(state.getValue(BlockStateProperties.POWER));
		}
	}

	/**
	 * 将顶面核心粉模拟输入并入原版 calculateTargetStrength 的邻居输入入口。
	 *
	 * 这里不直接调用 level.getBestNeighborSignal，因为原版依赖同一个 wire 单例共享 shouldSignal；
	 * 混用“原版 wire + 核心 wire 子类”时应显式排除 wire 类输入，避免反馈锁定。
	 */
	@Redirect(
		method = "calculateTargetStrength",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBestNeighborSignal(Lnet/minecraft/core/BlockPos;)I")
	)
	private int redirectBestNeighborSignal(Level instance, BlockPos queryPos, Level level, BlockPos pos) {
		int neighborSignal = getBestNeighborSignalWithoutWire(instance, queryPos);
		BlockState state = level.getBlockState(pos);
		int simulatedInputPower = LinkRedstoneDustCoreBlock.getTopSimulatedInputPower(level, pos, state);
		return Math.max(neighborSignal, simulatedInputPower);
	}

	/**
	 * 让顶面核心红石粉通过原版 shouldConnectTo 判定为“可连接红石线”。
	 */
	@Inject(
		method = "shouldConnectTo(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void injectTopCoreShouldConnectTo(BlockState state, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (!(state.getBlock() instanceof LinkRedstoneDustCoreBlock)) {
			return;
		}
		cir.setReturnValue(LinkRedstoneDustCoreBlock.isTopAttachedCoreDust(state));
	}

	/**
	 * 桥接 checkCornerChangeAt 的 state.is(this) 判定。
	 */
	@Redirect(
		method = "checkCornerChangeAt",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"
		)
	)
	private boolean redirectCornerWireEquivalence(BlockState state, Block block) {
		return isEquivalentWireState(state, block);
	}

	/**
	 * 桥接 updateIndirectNeighbourShapes 的第 1 处 state.is(this) 判定。
	 */
	@Redirect(
		method = "updateIndirectNeighbourShapes",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z",
			ordinal = 0
		)
	)
	private boolean redirectIndirectWireEquivalence0(BlockState state, Block block) {
		return isEquivalentWireState(state, block);
	}

	/**
	 * 桥接 updateIndirectNeighbourShapes 的第 2 处 state.is(this) 判定。
	 */
	@Redirect(
		method = "updateIndirectNeighbourShapes",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z",
			ordinal = 1
		)
	)
	private boolean redirectIndirectWireEquivalence1(BlockState state, Block block) {
		return isEquivalentWireState(state, block);
	}

	/**
	 * 桥接 updateIndirectNeighbourShapes 的第 3 处 state.is(this) 判定。
	 */
	@Redirect(
		method = "updateIndirectNeighbourShapes",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z",
			ordinal = 2
		)
	)
	private boolean redirectIndirectWireEquivalence2(BlockState state, Block block) {
		return isEquivalentWireState(state, block);
	}

	/**
	 * 同类 wire 等价规则：
	 * - 原始 state.is(block) 为 true 时不改变；
	 * - block 为 RedStoneWireBlock 且 state 为“原版 wire 或顶面核心粉”时，视为同类。
	 */
	private static boolean isEquivalentWireState(BlockState state, Block block) {
		if (state.is(block)) {
			return true;
		}
		if (!(block instanceof RedStoneWireBlock)) {
			return false;
		}
		return isWireLikeState(state);
	}

	/**
	 * 仅“原版 wire + 顶面核心粉”视为 wire-like。
	 */
	private static boolean isWireLikeState(BlockState state) {
		return state.is(Blocks.REDSTONE_WIRE) || LinkRedstoneDustCoreBlock.isTopAttachedCoreDust(state);
	}

	/**
	 * 模拟 SignalGetter#getBestNeighborSignal，但排除 wire-like 方块的输出。
	 * 用于修正“多 wire 实例下 shouldSignal 不共享”导致的反馈锁定。
	 */
	private static int getBestNeighborSignalWithoutWire(Level level, BlockPos pos) {
		int signal = 0;
		for (Direction direction : Direction.values()) {
			BlockPos neighborPos = pos.relative(direction);
			int neighborSignal = getSignalWithoutWire(level, neighborPos, direction);
			if (neighborSignal >= 15) {
				return 15;
			}
			if (neighborSignal > signal) {
				signal = neighborSignal;
			}
		}
		return signal;
	}

	/**
	 * 模拟 SignalGetter#getSignal，但会过滤 wire-like 输出，避免通过导体间接读到 wire 反馈。
	 */
	private static int getSignalWithoutWire(Level level, BlockPos pos, Direction direction) {
		BlockState state = level.getBlockState(pos);
		int signal = isWireLikeState(state) ? 0 : state.getSignal(level, pos, direction);
		if (state.isRedstoneConductor(level, pos)) {
			signal = Math.max(signal, getDirectSignalToWithoutWire(level, pos));
		}
		return signal;
	}

	/**
	 * 模拟 SignalGetter#getDirectSignalTo，但会过滤 wire-like 作为信号源。
	 */
	private static int getDirectSignalToWithoutWire(Level level, BlockPos pos) {
		int signal = 0;
		for (Direction direction : Direction.values()) {
			int direct = getDirectSignalWithoutWire(level, pos.relative(direction), direction);
			if (direct >= 15) {
				return 15;
			}
			if (direct > signal) {
				signal = direct;
			}
		}
		return signal;
	}

	/**
	 * 模拟 SignalGetter#getDirectSignal，但会过滤 wire-like 方块。
	 */
	private static int getDirectSignalWithoutWire(Level level, BlockPos pos, Direction direction) {
		BlockState state = level.getBlockState(pos);
		if (isWireLikeState(state)) {
			return 0;
		}
		return state.getDirectSignal(level, pos, direction);
	}
}
