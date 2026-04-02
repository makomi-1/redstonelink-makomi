package com.makomi.config;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkNodeType;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RedstoneLink 服务端配置门面。
 * <p>
 * 对外仅暴露按域访问入口、配置加载入口与少量公共配置类型。
 * </p>
 */
public final class RedstoneLinkConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/config");
	private static final Path CONFIG_PATH = resolveConfigPath();
	private static volatile RedstoneLinkServerConfigSnapshot snapshot = RedstoneLinkServerConfigSnapshot.defaults();

	/**
	 * 发射器边沿触发模式（仅 toggle/pulse）。
	 */
	public enum EmitterEdgeMode {
		RISING,
		FALLING,
		BOTH;

		/**
		 * 判断从旧电平切换到新电平时是否应触发联动。
		 */
		public boolean shouldTrigger(boolean wasPowered, boolean hasSignal) {
			return switch (this) {
				case RISING -> !wasPowered && hasSignal;
				case FALLING -> wasPowered && !hasSignal;
				case BOTH -> wasPowered != hasSignal;
			};
		}

		/**
		 * 由配置值解析边沿模式。
		 */
		public static EmitterEdgeMode fromConfigValue(String raw) {
			if (raw == null) {
				return RISING;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "falling" -> FALLING;
				case "both" -> BOTH;
				default -> RISING;
			};
		}
	}

	/**
	 * 强制加载模式。
	 */
	public enum CrossChunkForceLoadMode {
		ALL,
		WHITELIST;

		/**
		 * 解析强制加载模式配置。
		 */
		public static CrossChunkForceLoadMode fromConfigValue(String raw) {
			if (raw == null) {
				return WHITELIST;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "all" -> ALL;
				case "whitelist" -> WHITELIST;
				default -> WHITELIST;
			};
		}
	}

	/**
	 * 跨区块提示展示模式。
	 */
	public enum CrossChunkNotifyMode {
		SIMPLE,
		DETAILED;

		/**
		 * 解析提示模式配置值。
		 */
		public static CrossChunkNotifyMode fromConfigValue(String raw) {
			if (raw == null) {
				return SIMPLE;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "detailed" -> DETAILED;
				case "simple" -> SIMPLE;
				default -> SIMPLE;
			};
		}
	}

	/**
	 * loaded direct 链路批提交模式。
	 */
	public enum CrossChunkDirectBatchingMode {
		OFF,
		QUEUED_ONLY,
		ALL_DIRECT;

		/**
		 * 解析 loaded direct 链路批提交模式配置。
		 */
		public static CrossChunkDirectBatchingMode fromConfigValue(String raw) {
			if (raw == null) {
				return ALL_DIRECT;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "off" -> OFF;
				case "all_direct", "all_sync" -> ALL_DIRECT;
				case "queued_only" -> QUEUED_ONLY;
				default -> ALL_DIRECT;
			};
		}
	}

	/**
	 * 链接写入控制模式。
	 */
	public enum LinkWriteControlMode {
		FULL,
		LIMITED,
		READONLY;

		/**
		 * 解析写入控制模式配置值。
		 */
		public static LinkWriteControlMode fromConfigValue(String raw) {
			if (raw == null) {
				return FULL;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "limited" -> LIMITED;
				case "readonly" -> READONLY;
				case "full" -> FULL;
				default -> FULL;
			};
		}
	}

	/**
	 * 近外显“当前连接”保密模式。
	 */
	public enum CurrentLinksPrivacyMode {
		HIDDEN,
		MASKED,
		PLAIN;

		/**
		 * 解析保密模式配置值。
		 */
		public static CurrentLinksPrivacyMode fromConfigValue(String raw) {
			if (raw == null) {
				return PLAIN;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "hidden" -> HIDDEN;
				case "masked" -> MASKED;
				case "plain" -> PLAIN;
				default -> PLAIN;
			};
		}
	}

	/**
	 * 跨区块只读 preset 快照。
	 */
	public record CrossChunkPreset(Map<LinkNodeType, Set<Long>> sources, Map<LinkNodeType, Set<Long>> targets) {}

	private RedstoneLinkConfig() {
	}

	/**
	 * @return 配置模块统一日志器
	 */
	static Logger logger() {
		return LOGGER;
	}

	/**
	 * 加载（或首次生成）配置文件。
	 */
	public static void load() {
		ensureConfigFileExists();
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
			props.load(reader);
			snapshot = RedstoneLinkConfigParser.parse(props);
			LOGGER.info("Config loaded: {}", CONFIG_PATH.toAbsolutePath());
		} catch (IOException ex) {
			LOGGER.warn("Failed to read config, falling back to defaults: {}", CONFIG_PATH.toAbsolutePath(), ex);
			snapshot = RedstoneLinkServerConfigSnapshot.defaults();
		}
	}

	/**
	 * @return 基础通用配置
	 */
	public static RedstoneLinkGeneralConfig general() {
		return snapshot.general();
	}

	/**
	 * @return 命令配置
	 */
	public static RedstoneLinkCommandConfig command() {
		return snapshot.command();
	}

	/**
	 * @return 命令限流配置
	 */
	public static RedstoneLinkRateLimitConfig rateLimit() {
		return snapshot.rateLimit();
	}

	/**
	 * @return 当前连接隐私配置
	 */
	public static RedstoneLinkPrivacyConfig privacy() {
		return snapshot.privacy();
	}

	/**
	 * @return 链接写控配置
	 */
	public static RedstoneLinkWriteControlConfig writeControl() {
		return snapshot.writeControl();
	}

	/**
	 * @return 交互门禁配置
	 */
	public static RedstoneLinkInteractionConfig interaction() {
		return snapshot.interaction();
	}

	/**
	 * @return 服务端运行期配置
	 */
	public static RedstoneLinkRuntimeConfig runtime() {
		return snapshot.runtime();
	}

	/**
	 * @return 跨区块配置
	 */
	public static RedstoneLinkCrossChunkConfig crossChunk() {
		return snapshot.crossChunk();
	}

	/**
	 * 统一“手持物品打开配对界面”条件校验。
	 */
	public static boolean canOpenPairingByHeldItem(Player player, InteractionHand hand) {
		return interaction().canOpenPairingByHeldItem(player, hand);
	}

	/**
	 * 统一“遥控器打开配对界面”条件校验。
	 */
	public static boolean canOpenPairingByLinker(Player player, InteractionHand hand) {
		return interaction().canOpenPairingByLinker(player, hand);
	}

	/**
	 * 统一“已放置方块打开配对界面”条件校验。
	 */
	public static boolean canOpenPairingByPlacedBlock(Player player) {
		return interaction().canOpenPairingByPlacedBlock(player);
	}

	/**
	 * 解析配置文件路径。测试环境中 FabricLoader 可能不可用，此时回退到相对路径。
	 */
	private static Path resolveConfigPath() {
		try {
			FabricLoader loader = FabricLoader.getInstance();
			if (loader != null && loader.getConfigDir() != null) {
				return loader.getConfigDir().resolve("redstonelink-server.properties");
			}
		} catch (RuntimeException ignored) {
			// 单元测试环境允许回退到默认相对路径。
		}
		return Path.of("config").resolve("redstonelink-server.properties");
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
			Files.writeString(CONFIG_PATH, RedstoneLinkConfigTemplate.defaultConfigContent(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			LOGGER.warn("Failed to write default config: {}", CONFIG_PATH.toAbsolutePath(), ex);
		}
	}
}
