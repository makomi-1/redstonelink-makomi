package com.makomi.config;

import java.util.Locale;
import java.util.Properties;

/**
 * 服务端配置解析基础辅助。
 */
final class RedstoneLinkConfigParseSupport {
	private RedstoneLinkConfigParseSupport() {
	}

	/**
	 * 解析整数配置并做区间收敛。
	 */
	static int parseInt(Properties props, String key, int defaultValue, int min, int max) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < min || value > max) {
				RedstoneLinkConfig.logger().warn("Config {}={} is out of range; clamped to [{}..{}]", key, value, min, max);
			}
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException ex) {
			RedstoneLinkConfig.logger().warn("Config {}={} is invalid; falling back to default {}", key, raw, defaultValue);
			return defaultValue;
		}
	}

	/**
	 * 解析布尔配置。
	 */
	static boolean parseBoolean(Properties props, String key, boolean defaultValue) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if ("true".equals(normalized) || "false".equals(normalized)) {
			return Boolean.parseBoolean(normalized);
		}
		RedstoneLinkConfig.logger().warn("Config {}={} is invalid; falling back to default {}", key, raw, defaultValue);
		return defaultValue;
	}

	/**
	 * 解析布尔配置，并在主键缺失时回退到旧键。
	 */
	static boolean parseBooleanWithFallback(Properties props, String key, String legacyKey, boolean defaultValue) {
		if (props == null) {
			return defaultValue;
		}
		if (key != null && props.getProperty(key) != null) {
			return parseBoolean(props, key, defaultValue);
		}
		if (legacyKey != null && props.getProperty(legacyKey) != null) {
			return parseBoolean(props, legacyKey, defaultValue);
		}
		return defaultValue;
	}
}
