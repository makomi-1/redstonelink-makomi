package com.makomi.api.v1.service;

import com.makomi.api.v1.model.ActorContext;
import com.makomi.api.v1.model.ApiActivationMode;
import com.makomi.api.v1.model.TriggerRequest;
import com.makomi.api.v1.model.TriggerResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 触发投递 API。
 */
public interface TriggerApi {
	/**
	 * 按给定源序号投递触发。
	 */
	TriggerResult emit(TriggerRequest request);

	/**
	 * 通过“外部触发器适配器”从方块位置解析源序号并投递触发。
	 */
	TriggerResult emitFromExternal(
		ServerLevel level,
		BlockPos sourcePos,
		ApiActivationMode activationMode,
		ActorContext actorContext
	);
}

