package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * triggerSource 公共方块实体基类。
 * <p>
 * 固定声明自身为 TRIGGER_SOURCE 节点，目标节点类型为 CORE。
 * </p>
 */
public abstract class LinkTriggerSourceBlockEntity extends TriggerSourceBlockEntity {
	// 运行态模拟输入功率，仅供输入播放服务使用，不参与持久化。
	private int simulatedInputPower;
	// 输入播放服务刷新窗口标记：用于区分“真实派发”与“运行时模拟派发”。
	private int runtimeInputRefreshDepth;

	// 该层承载所有 triggerSource 实体的公共落地实现。
	protected LinkTriggerSourceBlockEntity(
		BlockEntityType<? extends LinkTriggerSourceBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected LinkNodeType getNodeType() {
		return LinkNodeType.TRIGGER_SOURCE;
	}

	@Override
	protected LinkNodeType getTargetNodeType() {
		return LinkNodeType.CORE;
	}

	/**
	 * 返回当前运行态模拟输入功率。
	 */
	public final int getSimulatedInputPower() {
		return simulatedInputPower;
	}

	/**
	 * 设置当前运行态模拟输入功率。
	 * <p>
	 * 该值只在内存中生效，不进入持久化。
	 * </p>
	 */
	public final void setSimulatedInputPower(int simulatedInputPower) {
		this.simulatedInputPower = Math.max(0, Math.min(15, simulatedInputPower));
	}

	/**
	 * 标记进入输入播放服务的运行时刷新窗口。
	 * <p>
	 * 该标记仅存在于内存中，用于让下游区分“真实来源状态变化”和“输入器模拟刷新”。
	 * </p>
	 */
	public final void beginRuntimeInputRefresh() {
		runtimeInputRefreshDepth++;
	}

	/**
	 * 标记退出输入播放服务的运行时刷新窗口。
	 */
	public final void endRuntimeInputRefresh() {
		runtimeInputRefreshDepth = Math.max(0, runtimeInputRefreshDepth - 1);
	}

	/**
	 * 返回当前是否处于输入播放服务的运行时刷新窗口。
	 */
	public final boolean isRuntimeInputRefreshInProgress() {
		return runtimeInputRefreshDepth > 0;
	}
}
