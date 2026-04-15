package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * 过滤器节点集合匹配目标模式。
 * <p>
 * `SERIAL` 表示按节点序号匹配；
 * `CHANNEL` 表示按节点当前频道匹配。
 * </p>
 */
public enum LinkFilterTargetMode {
	SERIAL("serial"),
	CHANNEL("channel");

	private final String token;

	LinkFilterTargetMode(String token) {
		this.token = token;
	}

	/**
	 * @return 稳定 token
	 */
	public String token() {
		return token;
	}

	/**
	 * 解析过滤目标模式 token。
	 *
	 * @param rawToken 原始 token
	 * @return 解析结果
	 */
	public static Optional<LinkFilterTargetMode> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (LinkFilterTargetMode mode : values()) {
			if (mode.token.equals(normalized)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
