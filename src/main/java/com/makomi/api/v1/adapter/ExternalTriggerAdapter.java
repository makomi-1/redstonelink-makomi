package com.makomi.api.v1.adapter;

import com.makomi.api.v1.model.ApiNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 外部触发器适配器。
 * <p>
 * 用于把第三方方块位置解析为 RedstoneLink 的“源序号”并发起触发。
 * </p>
 */
public interface ExternalTriggerAdapter {
	/**
	 * 判断当前方块是否由该适配器处理。
	 */
	boolean supports(ServerLevel level, BlockPos sourcePos, BlockState state, BlockEntity blockEntity);

	/**
	 * 解析 RedstoneLink 源序号。
	 *
	 * @return 返回大于 0 的序号表示解析成功；返回 0 或负数表示无法解析。
	 */
	long resolveSourceSerial(ServerLevel level, BlockPos sourcePos, BlockState state, BlockEntity blockEntity);

	/**
	 * 声明解析出的源节点类型，默认视为按钮源。
	 */
	default ApiNodeType sourceType() {
		return ApiNodeType.BUTTON;
	}
}

