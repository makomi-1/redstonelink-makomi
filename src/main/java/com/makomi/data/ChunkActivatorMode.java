package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * 区块激活器模式。
 * <p>
 * `FORCE_LOAD` 仅贡献普通强加载白名单；
 * `RESIDENT` 同时贡献强加载与常驻票据白名单。
 * </p>
 */
public enum ChunkActivatorMode {
	FORCE_LOAD("force_load"),
	RESIDENT("resident");

	private final String token;

	ChunkActivatorMode(String token) {
		this.token = token;
	}

	/**
	 * @return 网络与持久化统一使用的稳定 token
	 */
	public String token() {
		return token;
	}

	/**
	 * @return 当前模式是否额外贡献 resident 白名单
	 */
	public boolean contributesResident() {
		return this == RESIDENT;
	}

	/**
	 * 解析区块激活器模式 token。
	 *
	 * @param rawToken 原始 token
	 * @return 解析结果
	 */
	public static Optional<ChunkActivatorMode> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (ChunkActivatorMode mode : values()) {
			if (mode.token.equals(normalized)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
