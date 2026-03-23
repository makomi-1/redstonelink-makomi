package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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
	// 读档后待处理的输入重采样标记：只在当前进程内使用，不参与持久化。
	private boolean pendingLoadInputStateResync;

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

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		clearRuntimeInputState();
		refreshPendingLoadInputStateResyncFlag();
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

	/**
	 * 当前实体是否仍有待处理的加载后输入静默重采样任务。
	 */
	public final boolean hasPendingLoadInputStateResync() {
		return pendingLoadInputStateResync;
	}

	/**
	 * 主动标记该实体需要在加载后补一次输入静默重采样。
	 */
	public final void markPendingLoadInputStateResync() {
		pendingLoadInputStateResync = true;
	}

	/**
	 * 清空本次加载后的输入静默重采样标记。
	 */
	public final void clearPendingLoadInputStateResync() {
		pendingLoadInputStateResync = false;
	}

	/**
	 * 按当前已读入状态重新计算“加载后是否需要静默重采样”标记。
	 * <p>
	 * 子类可在补充读取自己的持久字段后再次调用，以纳入更细的判据。
	 * </p>
	 */
	protected final void refreshPendingLoadInputStateResyncFlag() {
		pendingLoadInputStateResync = shouldQueueLoadInputStateResync();
	}

	/**
	 * 判断当前读档状态是否需要在加载后补一次输入静默重采样。
	 * <p>
	 * 默认仅当方块外显仍处于 `POWERED=true` 时入队，优先清理最容易残留的外显激活态。
	 * </p>
	 */
	protected boolean shouldQueueLoadInputStateResync() {
		BlockState state = getBlockState();
		return state != null && state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
	}

	/**
	 * 清空只存在于当前 JVM 进程内的运行态字段，避免旧实例状态跨读档残留。
	 */
	private void clearRuntimeInputState() {
		simulatedInputPower = 0;
		runtimeInputRefreshDepth = 0;
	}
}
