package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 按钮源节点方块实体基类。
 * <p>
 * 固定声明自身为 BUTTON 节点，目标节点类型为 CORE。
 * </p>
 */
public abstract class LinkButtonBlockEntity extends TriggerSourceBlockEntity {
	// 历史命名保留为 Button；语义统一视为 TriggerSource（触发器源）节点。
	protected LinkButtonBlockEntity(
		BlockEntityType<? extends LinkButtonBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected LinkNodeType getNodeType() {
		return LinkNodeType.BUTTON;
	}

	@Override
	protected LinkNodeType getTargetNodeType() {
		return LinkNodeType.CORE;
	}
}
