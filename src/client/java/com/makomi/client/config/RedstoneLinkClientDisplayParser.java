package com.makomi.client.config;

import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;

/**
 * 客户端显示配置解析器。
 */
final class RedstoneLinkClientDisplayParser {
	static final String KEY_SERIAL_OVERLAY_MODE = "client.serialOverlayMode";
	static final String KEY_SERIAL_OVERLAY_MAX_DISTANCE = "client.serialOverlayMaxDistance";
	static final String KEY_SERIAL_OVERLAY_FONT_SCALE = "client.serialOverlayFontScale";
	static final String KEY_SERIAL_OVERLAY_TOGGLE_KEY = "client.serialOverlayToggleKey";
	static final String KEY_SERIAL_OVERLAY_FAR_SEE_THROUGH = "client.serialOverlayFarSeeThrough";
	static final String KEY_PAIRING_INPUT_MAX_LENGTH = "client.pairingInputMaxLength";
	static final String KEY_QUICK_LINK_MODE_TOGGLE_KEY = "client.quickLinkModeToggleKey";
	static final String KEY_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH = "client.quickLinkSerialCacheMaxLength";
	static final int DEFAULT_SERIAL_OVERLAY_MAX_DISTANCE = 24;
	static final int MIN_SERIAL_OVERLAY_MAX_DISTANCE = 4;
	static final int MAX_SERIAL_OVERLAY_MAX_DISTANCE = 256;
	static final float DEFAULT_SERIAL_OVERLAY_FONT_SCALE = 1.0F;
	static final float MIN_SERIAL_OVERLAY_FONT_SCALE = 0.50F;
	static final float MAX_SERIAL_OVERLAY_FONT_SCALE = 3.00F;
	static final int DEFAULT_NEAR_OVERLAY_DISTANCE = 8;
	static final String DEFAULT_SERIAL_OVERLAY_TOGGLE_KEY = "key.keyboard.k";
	static final boolean DEFAULT_SERIAL_OVERLAY_FAR_SEE_THROUGH = false;
	static final int DEFAULT_PAIRING_INPUT_MAX_LENGTH = 1024;
	static final String DEFAULT_QUICK_LINK_MODE_TOGGLE_KEY = "key.keyboard.b";
	static final int DEFAULT_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH = 1024;
	static final int MIN_PAIRING_INPUT_MAX_LENGTH = 64;
	static final int MAX_PAIRING_INPUT_MAX_LENGTH = 32768;
	static final int MIN_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH = 64;
	static final int MAX_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH = 32768;

	private RedstoneLinkClientDisplayParser() {
	}

	/**
	 * 解析客户端显示配置。
	 */
	static RedstoneLinkClientDisplaySnapshot parse(Properties properties, Logger logger) {
		RedstoneLinkClientDisplayConfig.SerialOverlayMode overlayMode = parseOverlayMode(properties, logger);
		return new RedstoneLinkClientDisplaySnapshot(
			new RedstoneLinkClientOverlayConfig(
				overlayMode,
				parseInt(
					properties,
					KEY_SERIAL_OVERLAY_MAX_DISTANCE,
					DEFAULT_SERIAL_OVERLAY_MAX_DISTANCE,
					MIN_SERIAL_OVERLAY_MAX_DISTANCE,
					MAX_SERIAL_OVERLAY_MAX_DISTANCE,
					logger
				),
				parseFloat(
					properties,
					KEY_SERIAL_OVERLAY_FONT_SCALE,
					DEFAULT_SERIAL_OVERLAY_FONT_SCALE,
					MIN_SERIAL_OVERLAY_FONT_SCALE,
					MAX_SERIAL_OVERLAY_FONT_SCALE,
					logger
				),
				DEFAULT_NEAR_OVERLAY_DISTANCE,
				RedstoneLinkClientKeyConfigSupport.parseKey(
					properties.getProperty(KEY_SERIAL_OVERLAY_TOGGLE_KEY),
					KEY_SERIAL_OVERLAY_TOGGLE_KEY,
					DEFAULT_SERIAL_OVERLAY_TOGGLE_KEY,
					logger
				),
				parseBoolean(
					properties,
					KEY_SERIAL_OVERLAY_FAR_SEE_THROUGH,
					DEFAULT_SERIAL_OVERLAY_FAR_SEE_THROUGH,
					logger
				)
			),
			new RedstoneLinkClientPairingConfig(
				parseInt(
					properties,
					KEY_PAIRING_INPUT_MAX_LENGTH,
					DEFAULT_PAIRING_INPUT_MAX_LENGTH,
					MIN_PAIRING_INPUT_MAX_LENGTH,
					MAX_PAIRING_INPUT_MAX_LENGTH,
					logger
				)
			),
			new RedstoneLinkClientQuickLinkConfig(
				RedstoneLinkClientKeyConfigSupport.parseKey(
					properties.getProperty(KEY_QUICK_LINK_MODE_TOGGLE_KEY),
					KEY_QUICK_LINK_MODE_TOGGLE_KEY,
					DEFAULT_QUICK_LINK_MODE_TOGGLE_KEY,
					logger
				),
				parseInt(
					properties,
					KEY_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH,
					DEFAULT_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH,
					MIN_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH,
					MAX_QUICK_LINK_SERIAL_CACHE_MAX_LENGTH,
					logger
				)
			)
		);
	}

	/**
	 * 解析外显模式。
	 */
	private static RedstoneLinkClientDisplayConfig.SerialOverlayMode parseOverlayMode(Properties properties, Logger logger) {
		String rawMode = properties.getProperty(KEY_SERIAL_OVERLAY_MODE);
		if (rawMode != null) {
			Optional<RedstoneLinkClientDisplayConfig.SerialOverlayMode> parsed =
				RedstoneLinkClientDisplayConfig.SerialOverlayMode.tryParse(rawMode);
			if (parsed.isPresent()) {
				return parsed.get();
			}
			logger.warn(
				"客户端配置 {}={} 非法，回退默认值 {}",
				KEY_SERIAL_OVERLAY_MODE,
				rawMode,
				RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_ONLY.configToken()
			);
			return RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_ONLY;
		}
		return RedstoneLinkClientDisplayConfig.SerialOverlayMode.FAR_ONLY;
	}

	/**
	 * 解析整数配置并做区间收敛。
	 */
	private static int parseInt(
		Properties properties,
		String key,
		int defaultValue,
		int min,
		int max,
		Logger logger
	) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < min || value > max) {
				logger.warn("客户端配置 {}={} 越界，已夹紧到 [{}..{}]", key, value, min, max);
			}
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException ex) {
			logger.warn("客户端配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
			return defaultValue;
		}
	}

	/**
	 * 解析浮点配置并做区间收敛。
	 */
	private static float parseFloat(
		Properties properties,
		String key,
		float defaultValue,
		float min,
		float max,
		Logger logger
	) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			float value = Float.parseFloat(raw.trim());
			if (value < min || value > max) {
				logger.warn("客户端配置 {}={} 越界，已夹紧到 [{},{}]", key, value, min, max);
			}
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException ex) {
			logger.warn("客户端配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
			return defaultValue;
		}
	}

	/**
	 * 解析布尔配置。
	 */
	private static boolean parseBoolean(Properties properties, String key, boolean defaultValue, Logger logger) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if ("true".equals(normalized) || "false".equals(normalized)) {
			return Boolean.parseBoolean(normalized);
		}
		logger.warn("客户端配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
		return defaultValue;
	}
}
