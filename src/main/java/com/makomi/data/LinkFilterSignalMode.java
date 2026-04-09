package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * 信号强度判定模式。
 */
public enum LinkFilterSignalMode {
	UPPER_BOUND("upper_bound"),
	LOWER_BOUND("lower_bound"),
	DISABLED("disabled");

	private final String token;

	LinkFilterSignalMode(String token) {
		this.token = token;
	}

	/**
	 * @return 稳定 token
	 */
	public String token() {
		return token;
	}

	/**
	 * 解析信号判定模式。
	 *
	 * @param rawToken 原始 token
	 * @return 解析结果
	 */
	public static Optional<LinkFilterSignalMode> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (LinkFilterSignalMode mode : values()) {
			if (mode.token.equals(normalized)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
