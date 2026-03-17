package com.makomi.client.config;

import com.makomi.RedstoneLink;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端显示配置读取器。
 * <p>
 * 仅管理“序号外显”相关的本地展示配置，不参与服务端逻辑判断。
 * </p>
 */
public final class RedstoneLinkClientDisplayConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/client-config");
	private static final String KEY_SERIAL_OVERLAY_ENABLED = "client.serialOverlayEnabled";
	private static final String KEY_SERIAL_OVERLAY_MODE = "client.serialOverlayMode";
	private static final String KEY_SERIAL_OVERLAY_MAX_DISTANCE = "client.serialOverlayMaxDistance";
	private static final String KEY_SERIAL_OVERLAY_FONT_SCALE = "client.serialOverlayFontScale";
	private static final String KEY_SERIAL_OVERLAY_TOGGLE_KEY = "client.serialOverlayToggleKey";
	private static final SerialOverlayMode DEFAULT_SERIAL_OVERLAY_MODE = SerialOverlayMode.FAR_ONLY;
	private static final int DEFAULT_SERIAL_OVERLAY_MAX_DISTANCE = 24;
	private static final int MIN_SERIAL_OVERLAY_MAX_DISTANCE = 4;
	private static final int MAX_SERIAL_OVERLAY_MAX_DISTANCE = 256;
	private static final float DEFAULT_SERIAL_OVERLAY_FONT_SCALE = 1.0F;
	private static final float MIN_SERIAL_OVERLAY_FONT_SCALE = 0.50F;
	private static final float MAX_SERIAL_OVERLAY_FONT_SCALE = 3.00F;
	private static final int NEAR_OVERLAY_MAX_DISTANCE = 8;
	private static final String DEFAULT_SERIAL_OVERLAY_TOGGLE_KEY = "key.keyboard.k";
	private static final Path CONFIG_PATH = resolveConfigPath();

	private static volatile SerialOverlayMode serialOverlayMode = DEFAULT_SERIAL_OVERLAY_MODE;
	private static volatile int serialOverlayMaxDistance = DEFAULT_SERIAL_OVERLAY_MAX_DISTANCE;
	private static volatile float serialOverlayFontScale = DEFAULT_SERIAL_OVERLAY_FONT_SCALE;
	private static volatile InputConstants.Key serialOverlayToggleKey = InputConstants.getKey(DEFAULT_SERIAL_OVERLAY_TOGGLE_KEY);

	/**
	 * 客户端序号外显模式。
	 */
	public enum SerialOverlayMode {
		FAR_ONLY("far", "message.redstonelink.client.serial_overlay.mode_far"),
		NEAR_ONLY("near", "message.redstonelink.client.serial_overlay.mode_near"),
		FAR_AND_NEAR("both", "message.redstonelink.client.serial_overlay.mode_both"),
		OFF("off", "message.redstonelink.client.serial_overlay.mode_off");

		private final String configToken;
		private final String messageKey;

		SerialOverlayMode(String configToken, String messageKey) {
			this.configToken = configToken;
			this.messageKey = messageKey;
		}

		public String configToken() {
			return configToken;
		}

		public String messageKey() {
			return messageKey;
		}

		public SerialOverlayMode next() {
			return switch (this) {
				case FAR_ONLY -> NEAR_ONLY;
				case NEAR_ONLY -> FAR_AND_NEAR;
				case FAR_AND_NEAR -> OFF;
				case OFF -> FAR_ONLY;
			};
		}

		public static Optional<SerialOverlayMode> tryParse(String raw) {
			if (raw == null || raw.isBlank()) {
				return Optional.empty();
			}
			String normalized = raw.trim().toLowerCase(Locale.ROOT);
			for (SerialOverlayMode mode : values()) {
				if (mode.configToken.equals(normalized)) {
					return Optional.of(mode);
				}
			}
			return Optional.empty();
		}
	}

	private RedstoneLinkClientDisplayConfig() {
	}

	/**
	 * 加载（或首次生成）客户端显示配置。
	 */
	public static void load() {
		ensureConfigFileExists();
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException ex) {
			LOGGER.warn("读取客户端配置失败，回退默认值: {}", CONFIG_PATH.toAbsolutePath(), ex);
			serialOverlayMode = DEFAULT_SERIAL_OVERLAY_MODE;
			serialOverlayMaxDistance = DEFAULT_SERIAL_OVERLAY_MAX_DISTANCE;
			serialOverlayFontScale = DEFAULT_SERIAL_OVERLAY_FONT_SCALE;
			return;
		}

		serialOverlayMode = parseOverlayMode(properties);
		serialOverlayMaxDistance = parseInt(
			properties,
			KEY_SERIAL_OVERLAY_MAX_DISTANCE,
			DEFAULT_SERIAL_OVERLAY_MAX_DISTANCE,
			MIN_SERIAL_OVERLAY_MAX_DISTANCE,
			MAX_SERIAL_OVERLAY_MAX_DISTANCE
		);
		serialOverlayFontScale = parseFloat(
			properties,
			KEY_SERIAL_OVERLAY_FONT_SCALE,
			DEFAULT_SERIAL_OVERLAY_FONT_SCALE,
			MIN_SERIAL_OVERLAY_FONT_SCALE,
			MAX_SERIAL_OVERLAY_FONT_SCALE
		);
		serialOverlayToggleKey = parseKey(
			properties,
			KEY_SERIAL_OVERLAY_TOGGLE_KEY,
			DEFAULT_SERIAL_OVERLAY_TOGGLE_KEY
		);
		LOGGER.info("客户端显示配置加载完成: {}", CONFIG_PATH.toAbsolutePath());
	}

	/**
	 * @return 当前外显模式
	 */
	public static SerialOverlayMode serialOverlayMode() {
		return serialOverlayMode;
	}

	/**
	 * @return 是否启用远距离外显
	 */
	public static boolean isFarOverlayEnabled() {
		return serialOverlayMode == SerialOverlayMode.FAR_ONLY || serialOverlayMode == SerialOverlayMode.FAR_AND_NEAR;
	}

	/**
	 * @return 是否启用近距离外显
	 */
	public static boolean isNearOverlayEnabled() {
		return serialOverlayMode == SerialOverlayMode.NEAR_ONLY || serialOverlayMode == SerialOverlayMode.FAR_AND_NEAR;
	}

	/**
	 * @return 核心粉序号外显最大可见距离（格）
	 */
	public static int serialOverlayMaxDistance() {
		return serialOverlayMaxDistance;
	}

	/**
	 * @return 序号外显字体缩放倍数
	 */
	public static float serialOverlayFontScale() {
		return serialOverlayFontScale;
	}

	/**
	 * @return 近外显判定最大距离（格）
	 */
	public static int serialOverlayNearDistance() {
		return NEAR_OVERLAY_MAX_DISTANCE;
	}

	/**
	 * @return 序号外显切换按键（客户端默认按键）
	 */
	public static InputConstants.Key serialOverlayToggleKey() {
		return serialOverlayToggleKey;
	}

	/**
	 * 按“远 -> 近 -> 远+近 -> 关闭”切换序号外显模式，并持久化到客户端配置文件。
	 *
	 * @return 切换后的外显模式
	 */
	public static SerialOverlayMode cycleSerialOverlayMode() {
		serialOverlayMode = serialOverlayMode.next();
		saveCurrentValues();
		return serialOverlayMode;
	}

	/**
	 * 持久化当前显示配置。
	 */
	private static void saveCurrentValues() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(
				CONFIG_PATH,
				buildConfigContent(serialOverlayMode, serialOverlayMaxDistance, serialOverlayFontScale, serialOverlayToggleKey.getName()),
				StandardCharsets.UTF_8
			);
		} catch (IOException ex) {
			LOGGER.warn("写入客户端配置失败: {}", CONFIG_PATH.toAbsolutePath(), ex);
		}
	}

	/**
	 * 解析客户端配置文件路径。测试环境中 FabricLoader 可能不可用，需回退相对路径。
	 */
	private static Path resolveConfigPath() {
		try {
			FabricLoader loader = FabricLoader.getInstance();
			if (loader != null && loader.getConfigDir() != null) {
				return loader.getConfigDir().resolve("redstonelink-client.properties");
			}
		} catch (RuntimeException ignored) {
			// 单元测试环境允许回退到默认相对路径。
		}
		return Path.of("config").resolve("redstonelink-client.properties");
	}

	/**
	 * 若配置文件不存在，则写入默认模板。
	 */
	private static void ensureConfigFileExists() {
		if (Files.exists(CONFIG_PATH)) {
			return;
		}
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(
				CONFIG_PATH,
				buildConfigContent(
					DEFAULT_SERIAL_OVERLAY_MODE,
					DEFAULT_SERIAL_OVERLAY_MAX_DISTANCE,
					DEFAULT_SERIAL_OVERLAY_FONT_SCALE,
					DEFAULT_SERIAL_OVERLAY_TOGGLE_KEY
				),
				StandardCharsets.UTF_8
			);
		} catch (IOException ex) {
			LOGGER.warn("写入默认客户端配置失败: {}", CONFIG_PATH.toAbsolutePath(), ex);
		}
	}

	/**
	 * 生成客户端配置模板文本。
	 */
	private static String buildConfigContent(
		SerialOverlayMode overlayMode,
		int overlayMaxDistance,
		float overlayFontScale,
		String overlayToggleKey
	) {
		return """
			# RedstoneLink client display config / RedstoneLink 客户端显示配置
			#
			# client.serialOverlayMode
			# zh: 序号外显模式：far(远外显)、near(近外显)、both(远+近)、off(关闭)。
			# en: Serial overlay mode: far, near, both, off.
			client.serialOverlayMode=%s
			
			# client.serialOverlayMaxDistance
			# zh: 远外显最大可见距离（格），范围 4~256。
			# en: Max visible distance (blocks) for serial overlay, range 4~256.
			client.serialOverlayMaxDistance=%s

			# client.serialOverlayFontScale
			# zh: 序号外显字体缩放倍数，范围 0.50~3.00。
			# en: Font scale for serial overlay, range 0.50~3.00.
			client.serialOverlayFontScale=%.2f
			
			# client.serialOverlayToggleKey
			# zh: 序号外显开关按键（推荐使用 key.keyboard.k 这种完整键名，单字母如 K 也可）。
			# en: Toggle key for serial overlay (prefer full key name like key.keyboard.k; single letter like K is also accepted).
			client.serialOverlayToggleKey=%s
			""".formatted(overlayMode.configToken(), overlayMaxDistance, overlayFontScale, overlayToggleKey);
	}

	/**
	 * 解析按键配置，支持完整键名与简写字母。
	 */
	private static InputConstants.Key parseKey(Properties properties, String key, String defaultValue) {
		String raw = properties.getProperty(key);
		String normalized = normalizeKeyName(raw);
		InputConstants.Key parsed = InputConstants.getKey(normalized);
		if (!InputConstants.UNKNOWN.equals(parsed)) {
			return parsed;
		}

		String defaultNormalized = normalizeKeyName(defaultValue);
		InputConstants.Key fallback = InputConstants.getKey(defaultNormalized);
		LOGGER.warn("客户端配置 {}={} 非法，回退默认值 {}", key, raw, defaultNormalized);
		return fallback;
	}

	/**
	 * 规范化按键名称：
	 * <p>
	 * - `key.*` 形式原样使用；
	 * - 单字母（如 `K`）自动映射为 `key.keyboard.k`；
	 * - 其它输入按原值透传给 InputConstants 再校验。
	 * </p>
	 */
	private static String normalizeKeyName(String raw) {
		if (raw == null || raw.isBlank()) {
			return DEFAULT_SERIAL_OVERLAY_TOGGLE_KEY;
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

	/**
	 * 解析外显模式。
	 * <p>
	 * 优先读取新字段 `client.serialOverlayMode`；若缺失则兼容旧字段 `client.serialOverlayEnabled`。
	 * </p>
	 */
	private static SerialOverlayMode parseOverlayMode(Properties properties) {
		String rawMode = properties.getProperty(KEY_SERIAL_OVERLAY_MODE);
		if (rawMode != null) {
			Optional<SerialOverlayMode> parsed = SerialOverlayMode.tryParse(rawMode);
			if (parsed.isPresent()) {
				return parsed.get();
			}
			LOGGER.warn(
				"客户端配置 {}={} 非法，回退默认值 {}",
				KEY_SERIAL_OVERLAY_MODE,
				rawMode,
				DEFAULT_SERIAL_OVERLAY_MODE.configToken()
			);
			return DEFAULT_SERIAL_OVERLAY_MODE;
		}

		boolean legacyEnabled = parseBoolean(properties, KEY_SERIAL_OVERLAY_ENABLED, true);
		return legacyEnabled ? SerialOverlayMode.FAR_ONLY : SerialOverlayMode.OFF;
	}

	/**
	 * 解析整数配置并做区间收敛。
	 */
	private static int parseInt(Properties properties, String key, int defaultValue, int min, int max) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < min || value > max) {
				LOGGER.warn("客户端配置 {}={} 越界，已夹紧到 [{}..{}]", key, value, min, max);
			}
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException ex) {
			LOGGER.warn("客户端配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
			return defaultValue;
		}
	}

	/**
	 * 解析浮点配置并做区间收敛。
	 */
	private static float parseFloat(Properties properties, String key, float defaultValue, float min, float max) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			float value = Float.parseFloat(raw.trim());
			if (value < min || value > max) {
				LOGGER.warn("客户端配置 {}={} 越界，已夹紧到 [{},{}]", key, value, min, max);
			}
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException ex) {
			LOGGER.warn("客户端配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
			return defaultValue;
		}
	}

	/**
	 * 解析布尔配置。
	 */
	private static boolean parseBoolean(Properties properties, String key, boolean defaultValue) {
		String raw = properties.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if ("true".equals(normalized) || "false".equals(normalized)) {
			return Boolean.parseBoolean(normalized);
		}
		LOGGER.warn("客户端配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
		return defaultValue;
	}
}
