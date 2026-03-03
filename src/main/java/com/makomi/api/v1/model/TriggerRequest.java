package com.makomi.api.v1.model;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;

/**
 * 触发请求参数。
 */
public record TriggerRequest(
	ServerLevel level,
	ApiNodeType sourceType,
	long sourceSerial,
	ApiActivationMode activationMode,
	ActorContext actorContext
) {
	public TriggerRequest {
		level = Objects.requireNonNull(level, "level");
		sourceType = Objects.requireNonNull(sourceType, "sourceType");
		activationMode = Objects.requireNonNull(activationMode, "activationMode");
		actorContext = Objects.requireNonNull(actorContext, "actorContext");
		if (sourceSerial <= 0L) {
			throw new IllegalArgumentException("sourceSerial must be positive");
		}
	}
}

