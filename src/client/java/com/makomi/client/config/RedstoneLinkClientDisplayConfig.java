package com.makomi.client.config;

import com.makomi.RedstoneLink;
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
 * 客户端显示配置门面。
 * <p>
 * 负责本地“序号外显 + 配对输入框”配置的加载、持久化与按域访问。
 * </p>
 */
public final class RedstoneLinkClientDisplayConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/client-config");
	private static final Path CONFIG_PATH = resolveConfigPath();
	private static volatile RedstoneLinkClientDisplaySnapshot snapshot = RedstoneLinkClientDisplaySnapshot.defaults();

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
			snapshot = RedstoneLinkClientDisplayParser.parse(properties, LOGGER);
			LOGGER.info("客户端显示配置加载完成: {}", CONFIG_PATH.toAbsolutePath());
		} catch (IOException ex) {
			LOGGER.warn("读取客户端配置失败，回退默认值: {}", CONFIG_PATH.toAbsolutePath(), ex);
			snapshot = RedstoneLinkClientDisplaySnapshot.defaults();
		}
	}

	/**
	 * @return 客户端序号外显配置
	 */
	public static RedstoneLinkClientOverlayConfig overlay() {
		return snapshot.overlay();
	}

	/**
	 * @return 客户端配对界面配置
	 */
	public static RedstoneLinkClientPairingConfig pairing() {
		return snapshot.pairing();
	}

	/**
	 * 按“远 -> 近 -> 远+近 -> 关闭”切换序号外显模式，并持久化到客户端配置文件。
	 */
	public static SerialOverlayMode cycleSerialOverlayMode() {
		RedstoneLinkClientOverlayConfig current = overlay();
		snapshot = new RedstoneLinkClientDisplaySnapshot(
			new RedstoneLinkClientOverlayConfig(
				current.mode().next(),
				current.maxDistance(),
				current.fontScale(),
				current.nearDistance(),
				current.toggleKey(),
				current.farSeeThrough()
			),
			pairing()
		);
		saveCurrentValues();
		return snapshot.overlay().mode();
	}

	/**
	 * 更新远外显穿透显示状态，并持久化到客户端配置文件。
	 */
	public static void setFarOverlaySeeThroughEnabled(boolean seeThrough) {
		RedstoneLinkClientOverlayConfig current = overlay();
		snapshot = new RedstoneLinkClientDisplaySnapshot(
			new RedstoneLinkClientOverlayConfig(
				current.mode(),
				current.maxDistance(),
				current.fontScale(),
				current.nearDistance(),
				current.toggleKey(),
				seeThrough
			),
			pairing()
		);
		saveCurrentValues();
	}

	/**
	 * 持久化当前显示配置。
	 */
	private static void saveCurrentValues() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(
				CONFIG_PATH,
				RedstoneLinkClientDisplayTemplate.buildConfigContent(snapshot),
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
				RedstoneLinkClientDisplayTemplate.buildConfigContent(RedstoneLinkClientDisplaySnapshot.defaults()),
				StandardCharsets.UTF_8
			);
		} catch (IOException ex) {
			LOGGER.warn("写入默认客户端配置失败: {}", CONFIG_PATH.toAbsolutePath(), ex);
		}
	}
}
