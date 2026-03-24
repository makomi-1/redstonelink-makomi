package com.makomi.client.config;

import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.Logger;

/**
 * 客户端按键配置辅助。
 */
final class RedstoneLinkClientKeyConfigSupport {
	private RedstoneLinkClientKeyConfigSupport() {
	}

	/**
	 * 解析按键配置，支持完整键名与简写字母。
	 */
	static InputConstants.Key parseKey(String raw, String configKey, String defaultValue, Logger logger) {
		String normalized = normalizeKeyName(raw, defaultValue);
		InputConstants.Key parsed = InputConstants.getKey(normalized);
		if (!InputConstants.UNKNOWN.equals(parsed)) {
			return parsed;
		}

		String fallbackName = normalizeKeyName(defaultValue, defaultValue);
		InputConstants.Key fallback = InputConstants.getKey(fallbackName);
		logger.warn("客户端配置 {}={} 非法，回退默认值 {}", configKey, raw, fallbackName);
		return fallback;
	}

	/**
	 * 规范化按键名称。
	 */
	static String normalizeKeyName(String raw, String defaultValue) {
		if (raw == null || raw.isBlank()) {
			return defaultValue;
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("key.")) {
			return trimmed;
		}
		if (trimmed.length() == 1) {
			char c = trimmed.charAt(0);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
				return "key.keyboard." + Character.toLowerCase(c);
			}
		}
		return trimmed;
	}
}
