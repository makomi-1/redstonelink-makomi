package com.makomi.api.v1.model;

import java.util.Objects;
import net.minecraft.world.entity.player.Player;

/**
 * API 操作发起方上下文。
 * <p>
 * 用于记录是谁发起了配对、触发、退役等操作，便于外部模组做审计或权限控制。
 * </p>
 */
public record ActorContext(String actorId, String displayName) {
	public ActorContext {
		actorId = Objects.requireNonNull(actorId, "actorId");
		displayName = Objects.requireNonNull(displayName, "displayName");
	}

	/**
	 * 构造系统级调用上下文。
	 */
	public static ActorContext system(String actorId) {
		return new ActorContext(actorId, actorId);
	}

	/**
	 * 从玩家对象构造调用上下文。
	 */
	public static ActorContext fromPlayer(Player player) {
		if (player == null) {
			return system("system");
		}
		String id = player.getUUID().toString();
		String name = player.getGameProfile().getName();
		return new ActorContext(id, name == null ? id : name);
	}
}

