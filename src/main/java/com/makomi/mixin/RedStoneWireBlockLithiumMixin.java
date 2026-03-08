package com.makomi.mixin;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.compat.LithiumHitCounter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * lithium 专用兼容注入：
 * 在“有锂环境”下承载完整桥接能力（通用桥接 + lithium 快路径桥接）。
 */
@Mixin(value = RedStoneWireBlock.class, priority = 1100)
public class RedStoneWireBlockLithiumMixin {

	/**
	 * 桥接 getWireSignal 的“state.is(this)”硬编码：
	 * - 任意 wire 读取顶面核心粉时，按其 POWER 返回；
	 * - 顶面核心粉读取原版 wire 时，按原版 wire POWER 返回。
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
	 * 将顶面核心粉模拟输入并入 calculateTargetStrength 的邻居输入读取入口。
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
	 * 对 calculateTargetStrength 结果做统一兜底合并。
	 */
	@Inject(method = "calculateTargetStrength", at = @At("RETURN"), cancellable = true)
	private void injectTopCoreTargetStrengthFallback(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		mergeTopCoreSimulatedInput(level, pos, cir);
	}

	/**
	 * lithium 二级兜底：对 getReceivedPower 返回值再做一次并入。
	 */
	@Inject(method = "getReceivedPower", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
	private void injectTopCoreReceivedPowerFallback(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		mergeTopCoreSimulatedInput(level, pos, cir);
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
	 * 桥接 checkCornerChangeAt 的 state.is(this) 判定，补齐角落传播链路中的跨类型等价识别。
	 */
	@Redirect(
		method = "checkCornerChangeAt",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"
		),
		require = 0
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
	 * lithium 兼容：其 getReceivedPower 快路径只按 state.is(this) 识别同类 wire。
	 * 仅按方法名匹配，降低外部运行环境命名空间差异导致的注入丢失概率。
	 */
	@Dynamic("Lithium 在运行时向 RedStoneWireBlock 注入该方法")
	@Redirect(
		method = "getReceivedPower",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z",
			remap = true
		),
		require = 0,
		remap = false
	)
	private boolean redirectLithiumReceivedPowerWireEquivalence(BlockState state, Block block) {
		LithiumHitCounter.recordReceivedPowerIs();
		return isEquivalentWireState(state, block);
	}

	/**
	 * lithium 兼容：桥接 getPowerFromSide 中全部 state.is(this) 判定。
	 * 去除 ordinal 约束，并按方法名匹配，降低不同运行环境下的注入脆弱性。
	 */
	@Dynamic("Lithium 在运行时向 RedStoneWireBlock 注入该方法")
	@Redirect(
		method = "getPowerFromSide",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z",
			remap = true
		),
		require = 0,
		remap = false
	)
	private boolean redirectLithiumPowerFromSideWireEquivalence(BlockState state, Block block) {
		LithiumHitCounter.recordPowerFromSideIs();
		return isEquivalentWireState(state, block);
	}

	/**
	 * lithium 兼容：桥接 getPowerFromSide 中全部 getValue(POWER) 读取。
	 * 去除 ordinal 约束，并按方法名匹配，避免调用点变化导致的静默失效。
	 */
	@Dynamic("Lithium 在运行时向 RedStoneWireBlock 注入该方法")
	@Redirect(
		method = "getPowerFromSide",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;",
			remap = true
		),
		require = 0,
		remap = false
	)
	private Comparable<?> redirectLithiumPowerFromSideWirePowerValue(BlockState state, Property<?> property) {
		LithiumHitCounter.recordPowerFromSideValue();
		return getEquivalentWirePower(state, property);
	}

	/**
	 * lithium 兼容：桥接 getStrongPowerTo 的 state.is(this) 判定，避免把 wire-like 当作直接信号源。
	 * 按方法名匹配，降低环境差异导致的动态方法选择失败风险。
	 */
	@Dynamic("Lithium 在运行时向 RedStoneWireBlock 注入该方法")
	@Redirect(
		method = "getStrongPowerTo",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z",
			remap = true
		),
		require = 0,
		remap = false
	)
	private boolean redirectLithiumStrongPowerToWireEquivalence(BlockState state, Block block) {
		LithiumHitCounter.recordStrongPowerToIs();
		return isEquivalentWireState(state, block);
	}

	/**
	 * 仅“原版 wire + 顶面核心粉”视为 wire-like。
	 */
	private static boolean isWireLikeState(BlockState state) {
		return state.is(Blocks.REDSTONE_WIRE) || LinkRedstoneDustCoreBlock.isTopAttachedCoreDust(state);
	}

	/**
	 * 双向跨类型等价规则：
	 * 1. 原生 state.is(block) 为 true 时保持；
	 * 2. 目标 block 为任意 RedStoneWireBlock 且 state 为 wire-like 时视为等价。
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
	 * 模拟 SignalGetter#getBestNeighborSignal，但排除 wire-like 方块输出。
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
	 * 模拟 SignalGetter#getSignal，但会过滤 wire-like 输出，避免导体间接读取 wire 反馈。
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

	/**
	 * 将顶面核心粉模拟输入并入当前返回值。
	 */
	private static void mergeTopCoreSimulatedInput(Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		BlockState state = level.getBlockState(pos);
		int simulatedInputPower = LinkRedstoneDustCoreBlock.getTopSimulatedInputPower(level, pos, state);
		if (simulatedInputPower <= 0) {
			return;
		}
		cir.setReturnValue(Math.max(cir.getReturnValueI(), simulatedInputPower));
	}

	/**
	 * 统一锂快路径中的 wire 功率读取语义：
	 * 顶面核心粉走核心粉信号读取，原版粉保持 POWER 读取。
	 */
	private static Comparable<?> getEquivalentWirePower(BlockState state, Property<?> property) {
		if (state.getBlock() instanceof LinkRedstoneDustCoreBlock) {
			return LinkRedstoneDustCoreBlock.getTopAttachedWireSignal(state);
		}
		return state.getValue(property);
	}
}
