package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * 节点集合过滤模式。
 */
public enum LinkFilterNodeSetMode {
	WHITELIST("whitelist"),
	BLOCKLIST("blocklist"),
	DISABLED("disabled");

	private final String token;

	LinkFilterNodeSetMode(String token) {
		this.token = token;
	}

	/**
	 * @return 稳定 token
	 */
	public String token() {
		return token;
	}

	/**
	 * 解析节点集合过滤模式。
	 *
	 * @param rawToken 原始 token
	 * @return 解析结果
	 */
	public static Optional<LinkFilterNodeSetMode> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (LinkFilterNodeSetMode mode : values()) {
			if (mode.token.equals(normalized)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
