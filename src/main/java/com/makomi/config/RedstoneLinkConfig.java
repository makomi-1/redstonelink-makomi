package com.makomi.config;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RedstoneLink 服务端配置读取器。
 */
public final class RedstoneLinkConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/config");
	private static final Path CONFIG_PATH = resolveConfigPath();
	private static volatile Values values = Values.defaults();
	private static volatile CrossChunkValues crossChunkValues = CrossChunkValues.defaults();

	/**
	 * 发射器边沿触发模式。
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
		 *
		 * @param raw 原始配置值
		 * @return 解析后的模式，非法值回退为 WHITELIST
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
		 *
		 * @param raw 原始配置值
		 * @return 解析后的模式，非法值回退为 SIMPLE
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

	private RedstoneLinkConfig() {
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
			crossChunkValues = CrossChunkValues.defaults();
			return;
		}

		values = parse(props);
		crossChunkValues = parseCrossChunk(props);
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
	 * @return 是否启用 lithium 严格模式
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
	 * @return 跨区块 SYNC=ON 的 TTL（tick）
	 */
	public static int crossChunkSyncSignalTtlTicks() {
		return crossChunkValues.syncSignalTtlTicks();
	}

	/**
	 * @return Relay 缓存过期时长（tick）
	 */
	public static int crossChunkRelayExpireTicks() {
		return crossChunkValues.relayExpireTicks();
	}

	/**
	 * @return 是否启用中继缓冲
	 */
	public static boolean crossChunkRelayEnabled() {
		return crossChunkValues.relayEnabled();
	}

	/**
	 * @return 是否启用强制加载
	 */
	public static boolean crossChunkForceLoadEnabled() {
		return crossChunkValues.forceLoadEnabled();
	}

	/**
	 * @return 强制加载模式（all/whitelist）
	 */
	public static CrossChunkForceLoadMode crossChunkForceLoadMode() {
		return crossChunkValues.forceLoadMode();
	}

	/**
	 * @return 强制加载票据持续时长（tick）
	 */
	public static int crossChunkForceLoadTicketTicks() {
		return crossChunkValues.forceLoadTicketTicks();
	}

	/**
	 * @return 每 tick 最大强制加载次数
	 */
	public static int crossChunkForceLoadMaxPerTick() {
		return crossChunkValues.forceLoadMaxPerTick();
	}

	/**
	 * @return 每来源每 tick 最大强制加载次数
	 */
	public static int crossChunkForceLoadMaxPerSourcePerTick() {
		return crossChunkValues.forceLoadMaxPerSourcePerTick();
	}

	/**
	 * @return 是否启用跨区块命令树
	 */
	public static boolean crossChunkCommandEnabled() {
		return crossChunkValues.commandEnabled();
	}

	/**
	 * @return 跨区块命令权限等级
	 */
	public static int crossChunkCommandPermissionLevel() {
		return crossChunkValues.commandPermissionLevel();
	}

	/**
	 * @return /redstonelink 命令树权限等级（0~4）
	 */
	public static int commandPermissionLevel() {
		return values.commandPermissionLevel();
	}

	/**
	 * @return 是否启用跨区块接管提示
	 */
	public static boolean crossChunkNotifyEnabled() {
		return crossChunkValues.notifyEnabled();
	}

	/**
	 * @return 跨区块接管提示模式
	 */
	public static CrossChunkNotifyMode crossChunkNotifyMode() {
		return crossChunkValues.notifyMode();
	}

	/**
	 * @return 允许作为来源的类型集合
	 */
	public static Set<LinkNodeType> crossChunkAllowedSourceTypes() {
		return crossChunkValues.allowedSourceTypes();
	}

	/**
	 * @return 允许作为目标的类型集合
	 */
	public static Set<LinkNodeType> crossChunkAllowedTargetTypes() {
		return crossChunkValues.allowedTargetTypes();
	}

	/**
	 * @return 配置中的只读 preset 名称列表
	 */
	public static List<String> crossChunkPresetNames() {
		return crossChunkValues.presets().keySet().stream().sorted().toList();
	}

	/**
	 * 读取指定名称的只读 preset。
	 */
	public static Optional<CrossChunkPreset> crossChunkPreset(String presetName) {
		if (presetName == null) {
			return Optional.empty();
		}
		String normalized = presetName.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(crossChunkValues.presets().get(normalized));
	}

	/**
	 * 判断给定类型+序号是否命中预设白名单。
	 */
	public static boolean crossChunkPresetContains(LinkNodeType type, long serial, LinkNodeSemantics.Role role) {
		if (type == null || serial <= 0L || role == null) {
			return false;
		}
		Map<LinkNodeType, Set<Long>> mergedBucket = role == LinkNodeSemantics.Role.SOURCE
			? crossChunkValues.mergedPresetSources()
			: crossChunkValues.mergedPresetTargets();
		Set<Long> serials = mergedBucket.get(type);
		return serials != null && serials.contains(serial);
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
	 * 解析基础配置快照。
	 */
	private static Values parse(Properties props) {
		return new Values(
			parseInt(props, "server.pulseDurationTicks", 4, 1, 40),
			EmitterEdgeMode.fromConfigValue(props.getProperty("server.emitterEdgeMode", "rising")),
			parseInt(props, "server.coreOutputPower", 15, 0, 15),
			parseInt(props, "server.maxTargetsPerSetLinks", 1024, 1, 4096),
			parseBoolean(props, "server.allowOfflineTargetBinding", true),
			parseInt(props, "server.command.permissionLevel", 2, 0, 4),
			parseBoolean(props, "interaction.requireSneakToOpenPairing", true),
			parseBoolean(props, "interaction.requireSneakToOpenLinkerPairing", true),
			parseBoolean(props, "interaction.requireEmptyOffhandToOpenPairing", true),
			parseBoolean(props, "compat.lithiumStrictMode", false)
		);
	}

	/**
	 * 解析跨区块配置快照。
	 */
	private static CrossChunkValues parseCrossChunk(Properties props) {
		Set<LinkNodeType> allowedSourceTypes = parseCrossChunkTypeSet(
			props,
			"crosschunk.whitelist.sourceTypes",
			Set.of(LinkNodeType.TRIGGER_SOURCE),
			LinkNodeSemantics.Role.SOURCE
		);
		Set<LinkNodeType> allowedTargetTypes = parseCrossChunkTypeSet(
			props,
			"crosschunk.whitelist.targetTypes",
			Set.of(LinkNodeType.CORE),
			LinkNodeSemantics.Role.TARGET
		);
		Map<String, CrossChunkPreset> presets = parseCrossChunkPresets(props, allowedSourceTypes, allowedTargetTypes);
		return new CrossChunkValues(
			parseInt(props, "crosschunk.syncSignalTtlTicks", 40, 1, 72_000),
			parseBoolean(props, "crosschunk.relay.enabled", true),
			parseInt(props, "crosschunk.relayExpireTicks", 200, 1, 72_000),
			parseBoolean(props, "crosschunk.forceLoad.enabled", true),
			CrossChunkForceLoadMode.fromConfigValue(props.getProperty("crosschunk.forceLoad.mode", "whitelist")),
			parseInt(props, "crosschunk.forceLoad.ticketTicks", 80, 1, 7_200),
			parseInt(props, "crosschunk.forceLoad.maxPerTick", 8, 1, 128),
			parseInt(props, "crosschunk.forceLoad.maxPerSourcePerTick", 2, 1, 32),
			parseBoolean(props, "crosschunk.command.enabled", true),
			parseInt(props, "crosschunk.command.permissionLevel", 2, 0, 4),
			parseBoolean(props, "crosschunk.notify.enabled", true),
			CrossChunkNotifyMode.fromConfigValue(props.getProperty("crosschunk.notify.mode", "simple")),
			allowedSourceTypes,
			allowedTargetTypes,
			presets,
			mergePresetBuckets(presets, LinkNodeSemantics.Role.SOURCE),
			mergePresetBuckets(presets, LinkNodeSemantics.Role.TARGET)
		);
	}

	/**
	 * 解析跨区块类型集合配置。
	 */
	private static Set<LinkNodeType> parseCrossChunkTypeSet(
		Properties props,
		String key,
		Set<LinkNodeType> defaults,
		LinkNodeSemantics.Role role
	) {
		String raw = props.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return Set.copyOf(defaults);
		}

		Set<LinkNodeType> parsedTypes = new HashSet<>();
		for (String token : raw.split("[,;\\s]+")) {
			if (token == null || token.isBlank()) {
				continue;
			}
			Optional<LinkNodeType> parsedType = resolveConfigTypeToken(key, token, token, role, null);
			if (parsedType.isEmpty()) {
				continue;
			}
			parsedTypes.add(parsedType.get());
		}
		if (parsedTypes.isEmpty()) {
			LOGGER.warn("配置 {} 未解析出有效类型，回退默认值", key);
			return Set.copyOf(defaults);
		}
		return Set.copyOf(parsedTypes);
	}

	/**
	 * 解析配置中的跨区块 preset 定义。
	 */
	private static Map<String, CrossChunkPreset> parseCrossChunkPresets(
		Properties props,
		Set<LinkNodeType> allowedSourceTypes,
		Set<LinkNodeType> allowedTargetTypes
	) {
		final String keyPrefix = "crosschunk.preset.";
		Map<String, MutablePreset> mutablePresets = new HashMap<>();
		for (String propertyKey : props.stringPropertyNames()) {
			if (!propertyKey.startsWith(keyPrefix)) {
				continue;
			}
			String suffix = propertyKey.substring(keyPrefix.length());
			int splitIndex = suffix.lastIndexOf('.');
			if (splitIndex <= 0 || splitIndex >= suffix.length() - 1) {
				continue;
			}
			String presetName = suffix.substring(0, splitIndex).trim().toLowerCase(Locale.ROOT);
			String rolePart = suffix.substring(splitIndex + 1).trim().toLowerCase(Locale.ROOT);
			if (presetName.isEmpty()) {
				continue;
			}
			LinkNodeSemantics.Role role = switch (rolePart) {
				case "sources" -> LinkNodeSemantics.Role.SOURCE;
				case "targets" -> LinkNodeSemantics.Role.TARGET;
				default -> null;
			};
			if (role == null) {
				continue;
			}
			Set<LinkNodeType> allowedTypes = role == LinkNodeSemantics.Role.SOURCE
				? allowedSourceTypes
				: allowedTargetTypes;
			Map<LinkNodeType, Set<Long>> parsedBucket = parsePresetBucket(
				props.getProperty(propertyKey),
				propertyKey,
				role,
				allowedTypes
			);
			if (parsedBucket.isEmpty()) {
				continue;
			}
			MutablePreset mutablePreset = mutablePresets.computeIfAbsent(presetName, ignored -> new MutablePreset());
			Map<LinkNodeType, Set<Long>> mutableBucket = role == LinkNodeSemantics.Role.SOURCE
				? mutablePreset.sources
				: mutablePreset.targets;
			mergeBucket(mutableBucket, parsedBucket);
		}

		Map<String, CrossChunkPreset> snapshots = new HashMap<>();
		for (Map.Entry<String, MutablePreset> entry : mutablePresets.entrySet()) {
			Map<LinkNodeType, Set<Long>> sourceBucket = immutableBucket(entry.getValue().sources);
			Map<LinkNodeType, Set<Long>> targetBucket = immutableBucket(entry.getValue().targets);
			if (sourceBucket.isEmpty() && targetBucket.isEmpty()) {
				continue;
			}
			snapshots.put(entry.getKey(), new CrossChunkPreset(sourceBucket, targetBucket));
		}
		return snapshots.isEmpty() ? Map.of() : Map.copyOf(snapshots);
	}

	/**
	 * 解析单个 preset 的来源或目标条目。
	 */
	private static Map<LinkNodeType, Set<Long>> parsePresetBucket(
		String raw,
		String key,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		if (raw == null || raw.isBlank()) {
			return Map.of();
		}

		Map<LinkNodeType, Set<Long>> parsedBucket = new HashMap<>();
		for (String token : raw.split("[,;\\s]+")) {
			if (token == null || token.isBlank()) {
				continue;
			}
			int splitIndex = token.indexOf(':');
			if (splitIndex <= 0 || splitIndex >= token.length() - 1) {
				LOGGER.warn("配置 {}={} 格式非法，应为 type:serial，已忽略", key, token);
				continue;
			}
			String typePart = token.substring(0, splitIndex);
			String serialPart = token.substring(splitIndex + 1);
			Optional<LinkNodeType> parsedType = resolveConfigTypeToken(
				key,
				token,
				typePart,
				role,
				allowedTypes
			);
			if (parsedType.isEmpty()) {
				continue;
			}

			long serial;
			try {
				serial = Long.parseLong(serialPart);
			} catch (NumberFormatException ex) {
				LOGGER.warn("配置 {}={} 序号不是合法整数，已忽略", key, token);
				continue;
			}
			if (serial <= 0L) {
				LOGGER.warn("配置 {}={} 序号必须大于 0，已忽略", key, token);
				continue;
			}
			parsedBucket.computeIfAbsent(parsedType.get(), ignored -> new HashSet<>()).add(serial);
		}
		return immutableBucket(parsedBucket);
	}

	/**
	 * 按语义中转层解析并校验配置中的节点类型 token。
	 *
	 * @param key 配置键
	 * @param tokenText 原始 token（用于日志）
	 * @param rawType 待解析类型文本
	 * @param role 语义角色
	 * @param allowedTypes 允许类型集合；null 表示不做该层校验
	 * @return 解析后的类型；失败时返回 empty
	 */
	private static Optional<LinkNodeType> resolveConfigTypeToken(
		String key,
		String tokenText,
		String rawType,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		var semanticResult = LinkNodeSemantics.resolveStrictTypeForRole(rawType, role, allowedTypes);
		if (semanticResult.isSuccess()) {
			return Optional.of(semanticResult.value());
		}
		switch (semanticResult.error()) {
			case ROLE_NOT_ALLOWED -> LOGGER.warn("配置 {}={} 语义角色不匹配，已忽略", key, tokenText);
			case CONFIG_NOT_ALLOWED -> LOGGER.warn("配置 {}={} 未包含在允许类型列表中，已忽略", key, tokenText);
			case INVALID_TYPE, NONE -> LOGGER.warn("配置 {}={} 含有非法类型，已忽略", key, tokenText);
		}
		return Optional.empty();
	}

	/**
	 * 合并所有 preset 的来源或目标桶，供快速命中判断。
	 */
	private static Map<LinkNodeType, Set<Long>> mergePresetBuckets(
		Map<String, CrossChunkPreset> presets,
		LinkNodeSemantics.Role role
	) {
		if (presets.isEmpty()) {
			return Map.of();
		}
		Map<LinkNodeType, Set<Long>> mergedBucket = new HashMap<>();
		for (CrossChunkPreset preset : presets.values()) {
			Map<LinkNodeType, Set<Long>> roleBucket = role == LinkNodeSemantics.Role.SOURCE
				? preset.sources()
				: preset.targets();
			mergeBucket(mergedBucket, roleBucket);
		}
		return immutableBucket(mergedBucket);
	}

	/**
	 * 将来源桶内容合并到目标桶。
	 */
	private static void mergeBucket(Map<LinkNodeType, Set<Long>> target, Map<LinkNodeType, Set<Long>> source) {
		for (Map.Entry<LinkNodeType, Set<Long>> entry : source.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isEmpty()) {
				continue;
			}
			target.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).addAll(entry.getValue());
		}
	}

	/**
	 * 将可变桶转换为不可变快照。
	 */
	private static Map<LinkNodeType, Set<Long>> immutableBucket(Map<LinkNodeType, Set<Long>> source) {
		if (source.isEmpty()) {
			return Map.of();
		}
		Map<LinkNodeType, Set<Long>> snapshot = new HashMap<>();
		for (Map.Entry<LinkNodeType, Set<Long>> entry : source.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isEmpty()) {
				continue;
			}
			snapshot.put(entry.getKey(), Set.copyOf(entry.getValue()));
		}
		return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
	}

	/**
	 * 解析整数配置并做区间收敛。
	 */
	private static int parseInt(Properties props, String key, int defaultValue, int min, int max) {
		String raw = props.getProperty(key);
		if (raw == null) {
			return defaultValue;
		}
		try {
			int value = Integer.parseInt(raw.trim());
			if (value < min || value > max) {
				LOGGER.warn("配置 {}={} 越界，已夹紧到 [{}..{}]", key, value, min, max);
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
	 * 生成默认配置文件内容。
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
			server.maxTargetsPerSetLinks=1024
			
			# server.allowOfflineTargetBinding
			# zh: 是否允许绑定离线目标（未加载区块/不在线端点）。
			# en: Whether offline targets can be bound.
			server.allowOfflineTargetBinding=true

			# server.command.permissionLevel
			# zh: /redstonelink 整个命令树所需权限等级（0~4）。
			# en: Permission level required for the whole /redstonelink command tree (0~4).
			server.command.permissionLevel=2
			
			# interaction.requireSneakToOpenPairing
			# zh: 打开配对 UI 是否必须潜行。
			# en: Require sneaking to open pairing UI.
			interaction.requireSneakToOpenPairing=true

			# interaction.requireSneakToOpenLinkerPairing
			# zh: 遥控器打开配对 UI 是否必须潜行。默认 true，避免与站立右键触发冲突。
			# en: Require sneaking when opening pairing UI via linker.
			interaction.requireSneakToOpenLinkerPairing=true
			
			# interaction.requireEmptyOffhandToOpenPairing
			# zh: 打开配对 UI 是否必须副手为空。
			# en: Require empty offhand to open pairing UI.
			interaction.requireEmptyOffhandToOpenPairing=true

			# compat.lithiumStrictMode
			# zh: Lithium 严格模式。true 时若发现关键兼容异常将直接中止启动。
			# en: Lithium strict mode. If true, startup fails fast on critical anomalies.
			compat.lithiumStrictMode=false

			# crosschunk.syncSignalTtlTicks
			# zh: 跨区块 SYNC=ON 缓存保活时长（tick）。
			# en: TTL in ticks for queued cross-chunk SYNC=ON events.
			crosschunk.syncSignalTtlTicks=40

			# crosschunk.relayExpireTicks
			# zh: 跨区块 Relay 缓存通用过期时长（tick）。
			# en: Common expiry ticks for relay cache entries.
			crosschunk.relayExpireTicks=200

			# crosschunk.relay.enabled
			# zh: 是否启用 Relay 中继缓冲。
			# en: Whether relay buffering is enabled.
			crosschunk.relay.enabled=true

			# crosschunk.forceLoad.enabled
			# zh: 是否允许命中白名单时触发强制加载。
			# en: Whether force-load is allowed when whitelist matches.
			crosschunk.forceLoad.enabled=true

			# crosschunk.forceLoad.mode
			# zh: 强制加载模式，all=不依赖白名单，whitelist=仅白名单/preset 生效。
			# en: Force-load mode, all or whitelist.
			crosschunk.forceLoad.mode=whitelist

			# crosschunk.forceLoad.ticketTicks
			# zh: 强制加载票据保活时长（tick）。
			# en: Lifetime of force-load ticket in ticks.
			crosschunk.forceLoad.ticketTicks=80

			# crosschunk.forceLoad.maxPerTick
			# zh: 每 tick 最多执行的强制加载请求数量。
			# en: Maximum force-load requests per tick.
			crosschunk.forceLoad.maxPerTick=8

			# crosschunk.forceLoad.maxPerSourcePerTick
			# zh: 每个来源每 tick 最多执行的强制加载请求数量。
			# en: Maximum force-load requests per source per tick.
			crosschunk.forceLoad.maxPerSourcePerTick=2

			# crosschunk.command.enabled
			# zh: 是否启用 /redstonelink crosschunk 命令树。
			# en: Whether /redstonelink crosschunk command tree is enabled.
			crosschunk.command.enabled=true

			# crosschunk.command.permissionLevel
			# zh: /redstonelink crosschunk 命令所需权限等级（0~4）。
			# en: Permission level required for /redstonelink crosschunk commands.
			crosschunk.command.permissionLevel=2

			# crosschunk.notify.enabled
			# zh: 是否启用跨区块接管生效提示。
			# en: Whether to notify when cross-chunk takeover is accepted.
			crosschunk.notify.enabled=true

			# crosschunk.notify.mode
			# zh: 跨区块提示模式：simple/detailed。
			# en: Cross-chunk notify mode: simple/detailed.
			crosschunk.notify.mode=simple

			# crosschunk.whitelist.sourceTypes
			# zh: 允许作为来源的类型列表（逗号/空格分隔）。
			# en: Allowed source types for cross-chunk whitelist.
			crosschunk.whitelist.sourceTypes=triggerSource

			# crosschunk.whitelist.targetTypes
			# zh: 允许作为目标的类型列表（逗号/空格分隔）。
			# en: Allowed target types for cross-chunk whitelist.
			crosschunk.whitelist.targetTypes=core

			# crosschunk.preset.<name>.sources / crosschunk.preset.<name>.targets
			# zh: 只读 preset，格式为 type:serial（逗号/空格分隔）。
			# en: Read-only preset entries using type:serial format.
			# crosschunk.preset.keypath.sources=triggerSource:1001
			# crosschunk.preset.keypath.targets=core:2001

			""";
	}

	/**
	 * 基础运行时配置快照。
	 */
	private record Values(
		int pulseDurationTicks,
		EmitterEdgeMode emitterEdgeMode,
		int coreOutputPower,
		int maxTargetsPerSetLinks,
		boolean allowOfflineTargetBinding,
		int commandPermissionLevel,
		boolean requireSneakToOpenPairing,
		boolean requireSneakToOpenLinkerPairing,
		boolean requireEmptyOffhandToOpenPairing,
		boolean lithiumStrictMode
	) {
		/**
		 * @return 基础配置默认值
		 */
		private static Values defaults() {
			return new Values(4, EmitterEdgeMode.RISING, 15, 1024, true, 2, true, true, true, false);
		}
	}

	/**
	 * 跨区块只读 preset 快照。
	 */
	public record CrossChunkPreset(Map<LinkNodeType, Set<Long>> sources, Map<LinkNodeType, Set<Long>> targets) {}

	/**
	 * 跨区块运行时配置快照。
	 */
	private record CrossChunkValues(
		int syncSignalTtlTicks,
		boolean relayEnabled,
		int relayExpireTicks,
		boolean forceLoadEnabled,
		CrossChunkForceLoadMode forceLoadMode,
		int forceLoadTicketTicks,
		int forceLoadMaxPerTick,
		int forceLoadMaxPerSourcePerTick,
		boolean commandEnabled,
		int commandPermissionLevel,
		boolean notifyEnabled,
		CrossChunkNotifyMode notifyMode,
		Set<LinkNodeType> allowedSourceTypes,
		Set<LinkNodeType> allowedTargetTypes,
		Map<String, CrossChunkPreset> presets,
		Map<LinkNodeType, Set<Long>> mergedPresetSources,
		Map<LinkNodeType, Set<Long>> mergedPresetTargets
	) {
		/**
		 * @return 跨区块配置默认值
		 */
		private static CrossChunkValues defaults() {
			return new CrossChunkValues(
				40,
				true,
				200,
				true,
				CrossChunkForceLoadMode.WHITELIST,
				80,
				8,
				2,
				true,
				2,
				true,
				CrossChunkNotifyMode.SIMPLE,
				Set.of(LinkNodeType.TRIGGER_SOURCE),
				Set.of(LinkNodeType.CORE),
				Map.of(),
				Map.of(),
				Map.of()
			);
		}
	}

	/**
	 * preset 解析过程中的可变中间态。
	 */
	private static final class MutablePreset {
		private final Map<LinkNodeType, Set<Long>> sources = new HashMap<>();
		private final Map<LinkNodeType, Set<Long>> targets = new HashMap<>();
	}
}
