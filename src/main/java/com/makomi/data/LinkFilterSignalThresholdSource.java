package com.makomi.data;

import java.util.Locale;
import java.util.Optional;

/**
 * 信号阈值来源。
 */
public enum LinkFilterSignalThresholdSource {
	FIXED_INPUT("fixed_input"),
	NEIGHBOR_MAX_INPUT("neighbor_max_input");

	private final String token;

	LinkFilterSignalThresholdSource(String token) {
		this.token = token;
	}

	/**
	 * @return 稳定 token
	 */
	public String token() {
		return token;
	}

	/**
	 * 解析信号阈值来源。
	 *
	 * @param rawToken 原始 token
	 * @return 解析结果
	 */
	public static Optional<LinkFilterSignalThresholdSource> tryParseToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		for (LinkFilterSignalThresholdSource source : values()) {
			if (source.token.equals(normalized)) {
				return Optional.of(source);
			}
		}
		return Optional.empty();
	}
}
