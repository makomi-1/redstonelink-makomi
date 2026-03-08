package com.makomi.config;

import com.makomi.RedstoneLink;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RedstoneLink 服务端配置读取器。
 * <p>
 * 配置文件路径：{@code config/redstonelink-server.properties}。
 * </p>
 */
public final class RedstoneLinkConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/config");
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("redstonelink-server.properties");
	private static volatile Values values = Values.defaults();

	/**
	 * 发射器边沿触发模式。
	 */
	public enum EmitterEdgeMode {
		RISING,
		FALLING,
		BOTH;

		/**
		 * 判断从旧电平切换到新电平时是否应触发联动。
		 *
		 * @param wasPowered 旧电平是否为高
		 * @param hasSignal 新电平是否为高
		 * @return 是否触发
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
		 *
		 * @param raw 配置文本
		 * @return 解析后的模式，无法识别时返回 {@link #RISING}
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

	private RedstoneLinkConfig() {
	}

	/**
	 * 加载（或首次生成）配置文件。
	 */
	public static void load() {
		ensureConfigFileExists();
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
			props.load(reader);
		} catch (IOException ex) {
			LOGGER.warn("读取配置失败，回退默认值: {}", CONFIG_PATH.toAbsolutePath(), ex);
			values = Values.defaults();
			return;
		}

		values = parse(props);
		LOGGER.info("配置加载完成: {}", CONFIG_PATH.toAbsolutePath());
	}

	/**
	 * @return 核心脉冲持续时长（tick）
	 */
	public static int pulseDurationTicks() {
		return values.pulseDurationTicks();
	}

	/**
	 * @return 发射器边沿触发模式
	 */
	public static EmitterEdgeMode emitterEdgeMode() {
		return values.emitterEdgeMode();
	}

	/**
	 * @return 核心输出红石强度（0~15）
	 */
	public static int coreOutputPower() {
		return values.coreOutputPower();
	}

	/**
	 * @return 单次 set_links 允许的最大目标数
	 */
	public static int maxTargetsPerSetLinks() {
		return values.maxTargetsPerSetLinks();
	}

	/**
	 * @return 是否允许绑定离线目标
	 */
	public static boolean allowOfflineTargetBinding() {
		return values.allowOfflineTargetBinding();
	}

	/**
	 * @return 打开配对界面是否要求潜行
	 */
	public static boolean requireSneakToOpenPairing() {
		return values.requireSneakToOpenPairing();
	}

	/**
	 * @return 通过遥控器（Linker）打开配对界面是否要求潜行
	 */
	public static boolean requireSneakToOpenLinkerPairing() {
		return values.requireSneakToOpenLinkerPairing();
	}

	/**
	 * @return 是否启用 lithium 严格模式（发现关键异常时直接失败）
	 */
	public static boolean lithiumStrictMode() {
		return values.lithiumStrictMode();
	}

	/**
	 * @return 打开配对界面是否要求副手为空
	 */
	public static boolean requireEmptyOffhandToOpenPairing() {
		return values.requireEmptyOffhandToOpenPairing();
	}

	/**
	 * 统一“手持物品打开配对界面”条件校验。
	 */
	public static boolean canOpenPairingByHeldItem(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (requireSneakToOpenPairing() && !player.isShiftKeyDown()) {
			return false;
		}
		if (requireEmptyOffhandToOpenPairing() && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}

	/**
	 * 统一“遥控器打开配对界面”条件校验。
	 * <p>
	 * 与通用手持入口分离，避免全局潜行配置影响遥控器“站立右键触发”语义。
	 * </p>
	 */
	public static boolean canOpenPairingByLinker(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (requireSneakToOpenLinkerPairing() && !player.isShiftKeyDown()) {
			return false;
		}
		if (requireEmptyOffhandToOpenPairing() && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}

	/**
	 * 统一“已放置方块打开配对界面”条件校验。
	 * <p>
	 * 为避免误触，主手为空始终为硬约束；潜行与副手条件由配置控制。
	 * </p>
	 */
	public static boolean canOpenPairingByPlacedBlock(Player player) {
		if (!player.getMainHandItem().isEmpty()) {
			return false;
		}
		if (requireSneakToOpenPairing() && !player.isShiftKeyDown()) {
			return false;
		}
		if (requireEmptyOffhandToOpenPairing() && !player.getOffhandItem().isEmpty()) {
			return false;
		}
		return true;
	}

	/**
	 * 解析配置对象为运行时配置值。
	 */
	private static Values parse(Properties props) {
		return new Values(
			parseInt(props, "server.pulseDurationTicks", 4, 1, 40),
			EmitterEdgeMode.fromConfigValue(props.getProperty("server.emitterEdgeMode", "rising")),
			parseInt(props, "server.coreOutputPower", 15, 0, 15),
			parseInt(props, "server.maxTargetsPerSetLinks", 128, 1, 512),
			parseBoolean(props, "server.allowOfflineTargetBinding", true),
			parseBoolean(props, "interaction.requireSneakToOpenPairing", true),
			parseBoolean(props, "interaction.requireSneakToOpenLinkerPairing", true),
			parseBoolean(props, "interaction.requireEmptyOffhandToOpenPairing", true),
			parseBoolean(props, "compat.lithiumStrictMode", false)
		);
	}

	/**
	 * 解析整型配置并做区间收敛。
	 */
	private static int parseInt(Properties props, String key, int defaultValue, int min, int max) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < min || value > max) {
				LOGGER.warn("配置 {}={} 越界，已夹紧到[{}..{}]", key, value, min, max);
			}
			return Math.max(min, Math.min(max, value));
		} catch (NumberFormatException ex) {
			LOGGER.warn("配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
			return defaultValue;
		}
	}

	/**
	 * 解析布尔配置。
	 */
	private static boolean parseBoolean(Properties props, String key, boolean defaultValue) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		if ("true".equals(normalized) || "false".equals(normalized)) {
			return Boolean.parseBoolean(normalized);
		}
		LOGGER.warn("配置 {}={} 非法，回退默认值 {}", key, raw, defaultValue);
		return defaultValue;
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
			Files.writeString(CONFIG_PATH, defaultConfigContent(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			LOGGER.warn("写入默认配置失败: {}", CONFIG_PATH.toAbsolutePath(), ex);
		}
	}

	/**
	 * 生成默认配置文件内容（双语注释）。
	 */
	private static String defaultConfigContent() {
		return """
			# RedstoneLink server config / RedstoneLink 服务器配置
			#
			# server.pulseDurationTicks
			# zh: 核心脉冲持续时长（tick），PULSE 模式触发后保持激活的时间。
			# en: Pulse duration (ticks) for core activation in PULSE mode.
			server.pulseDurationTicks=4
			
			# server.emitterEdgeMode
			# zh: 发射器红石边沿触发模式，可选：rising / falling / both。
			# en: Emitter edge trigger mode: rising / falling / both.
			server.emitterEdgeMode=rising
			
			# server.coreOutputPower
			# zh: 核心输出红石强度（0~15）。
			# en: Core redstone output power (0~15).
			server.coreOutputPower=15
			
			# server.maxTargetsPerSetLinks
			# zh: 单次 set_links 允许设置的最大目标数。
			# en: Maximum target count allowed in one set_links operation.
			server.maxTargetsPerSetLinks=128
			
			# server.allowOfflineTargetBinding
			# zh: 是否允许绑定离线目标（未加载区块/不在线端点）。
			# en: Whether offline targets can be bound.
			server.allowOfflineTargetBinding=true
			
			# interaction.requireSneakToOpenPairing
			# zh: 打开配对 UI 是否必须潜行。
			# en: Require sneaking to open pairing UI.
			interaction.requireSneakToOpenPairing=true

			# interaction.requireSneakToOpenLinkerPairing
			# zh: 遥控器打开配对 UI 是否必须潜行。默认 true，避免与站立右键触发冲突。
			# en: Require sneaking when opening pairing UI via linker. Default true to avoid conflict with standing right-click trigger.
			interaction.requireSneakToOpenLinkerPairing=true
			
			# interaction.requireEmptyOffhandToOpenPairing
			# zh: 打开配对 UI 是否必须副手为空。
			# en: Require empty offhand to open pairing UI.
			interaction.requireEmptyOffhandToOpenPairing=true

			# compat.lithiumStrictMode
			# zh: lithium 严格模式。true 时若检测到关键兼容异常（如动态方法缺失）将直接抛错中止启动。
			# en: Lithium strict mode. If true, startup fails fast when critical compatibility anomalies are detected.
			compat.lithiumStrictMode=false

			""";
	}

	/**
	 * 运行时配置快照。
	 */
	private record Values(
		int pulseDurationTicks,
		EmitterEdgeMode emitterEdgeMode,
		int coreOutputPower,
		int maxTargetsPerSetLinks,
		boolean allowOfflineTargetBinding,
		boolean requireSneakToOpenPairing,
		boolean requireSneakToOpenLinkerPairing,
		boolean requireEmptyOffhandToOpenPairing,
		boolean lithiumStrictMode
	) {
		/**
		 * @return 配置默认值
		 */
		private static Values defaults() {
			return new Values(4, EmitterEdgeMode.RISING, 15, 128, true, true, true, true, false);
		}
	}
}
