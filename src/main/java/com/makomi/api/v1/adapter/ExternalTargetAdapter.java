package com.makomi.api.v1.adapter;

import com.makomi.api.v1.model.ApiActivationMode;
import com.makomi.api.v1.model.ApiNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 外部目标适配器。
 * <p>
 * 允许第三方模组把自定义方块接入 RedstoneLink 的“被触发目标”链路。
 * </p>
 */
public interface ExternalTargetAdapter {
	/**
	 * 判断当前方块是否由该适配器处理。
	 */
	boolean supports(ServerLevel level, BlockPos targetPos, BlockState state, BlockEntity blockEntity);

	/**
	 * 执行外部目标触发。
	 *
	 * @return 返回 true 代表触发成功；false 代表未处理或触发失败。
	 */
	boolean trigger(
		ServerLevel level,
		BlockPos targetPos,
		BlockState state,
		BlockEntity blockEntity,
		long sourceSerial,
		ApiNodeType sourceType,
		ApiActivationMode activationMode
	);
}

