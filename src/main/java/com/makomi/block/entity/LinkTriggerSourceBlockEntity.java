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
}
