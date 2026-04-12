package com.makomi.network;

import java.util.Locale;
import java.util.Optional;

/**
 * 过滤器编辑器目标类型。
 * <p>
 * 同一套过滤器编辑 GUI 既可编辑已放置过滤器，也可编辑主手手持过滤器，
 * 通过该枚举区分“配置最终写回到哪里”。
 * </p>
 */
public enum LinkFilterEditorTargetKind {
	BLOCK_ENTITY("block_entity"),
	HELD_MAIN_HAND("held_main_hand");

	private final String token;

	LinkFilterEditorTargetKind(String token) {
		this.token = token;
	}

	/**
	 * @return 网络稳定 token
	 */
	public String token() {
		return token;
	}

	/**
	 * @return 当前是否为已放置方块实体目标
	 */
	public boolean usesBlockEntityTarget() {
		return this == BLOCK_ENTITY;
	}

	/**
	 * @return 当前是否为主手手持物品目标
	 */
	public boolean usesHeldMainHandTarget() {
		return this == HELD_MAIN_HAND;
	}

	/**
	 * 解析网络 token。
	 */
	public static Optional<LinkFilterEditorTargetKind> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (LinkFilterEditorTargetKind kind : values()) {
			if (kind.token.equals(normalized)) {
				return Optional.of(kind);
			}
		}
		return Optional.empty();
	}
}
