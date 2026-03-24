package com.makomi.config;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
	private static volatile RedstoneLinkConfigValues values = RedstoneLinkConfigValues.defaults();
	private static volatile RedstoneLinkCrossChunkConfigValues crossChunkValues = RedstoneLinkCrossChunkConfigValues.defaults();

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
	 * @return 配置模块统一日志器
	 */
	static Logger logger() {
		return LOGGER;
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
			LOGGER.warn("Failed to read config, falling back to defaults: {}", CONFIG_PATH.toAbsolutePath(), ex);
			values = RedstoneLinkConfigValues.defaults();
			crossChunkValues = RedstoneLinkCrossChunkConfigValues.defaults();
			return;
		}

		values = parse(props);
		crossChunkValues = parseCrossChunk(props);
		LOGGER.info("Config loaded: {}", CONFIG_PATH.toAbsolutePath());
	}

	/**
	 * @return 核心脉冲持续时长（tick）
	 */
	public static int pulseDurationTicks() {
		return values.pulseDurationTicks();
	}

	/**
	 * @return 发射器边沿触发模式（仅 toggle/pulse 发射器）
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
	 * @return 是否启用 SYNC 信号的不限时持久化（true=不受 TTL 过期影响）
	 */
	public static boolean crossChunkSyncSignalPersistent() {
		return crossChunkValues.syncSignalPersistent();
	}

	/**
	 * @return 是否启用目标区块 `CHUNK_LOAD` 时的 sync 补发
	 */
	public static boolean crossChunkSyncTargetChunkLoadReplayEnabled() {
		return crossChunkValues.syncTargetChunkLoadReplayEnabled();
	}

	/**
	 * @return 是否启用 PULSE 事件的普通 TTL relay
	 */
	public static boolean crossChunkActivationPulseRelayEnabled() {
		return crossChunkValues.activationPulseRelayEnabled();
	}

	/**
	 * @return PULSE 事件的 TTL（tick）
	 */
	public static int crossChunkActivationPulseTtlTicks() {
		return crossChunkValues.activationPulseTtlTicks();
	}

	/**
	 * @return 是否启用 PULSE 事件的实验性不限时投递
	 */
	public static boolean crossChunkActivationPulsePersistentExperimental() {
		return crossChunkValues.activationPulsePersistentExperimental();
	}

	/**
	 * @return 是否启用 TOGGLE 事件的普通 TTL relay
	 */
	public static boolean crossChunkActivationToggleRelayEnabled() {
		return crossChunkValues.activationToggleRelayEnabled();
	}

	/**
	 * @return TOGGLE 事件的 TTL（tick）
	 */
	public static int crossChunkActivationToggleTtlTicks() {
		return crossChunkValues.activationToggleTtlTicks();
	}

	/**
	 * @return 是否启用 TOGGLE 事件的实验性不限时投递
	 */
	public static boolean crossChunkActivationTogglePersistentExperimental() {
		return crossChunkValues.activationTogglePersistentExperimental();
	}

	/**
	 * @return 是否启用 triggerSource 区块卸载失效事件
	 */
	public static boolean crossChunkTriggerSourceChunkUnloadInvalidationEnabled() {
		return crossChunkValues.triggerSourceChunkUnloadInvalidationEnabled();
	}

	/**
	 * @return 是否启用 triggerSource 其它失效事件
	 */
	public static boolean crossChunkTriggerSourceInvalidationEnabled() {
		return crossChunkValues.triggerSourceInvalidationEnabled();
	}

	/**
	 * @return 跨区块持久派发队列通用 TTL（tick）
	 */
	public static int crossChunkQueueDefaultTtlTicks() {
		return crossChunkValues.queueDefaultTtlTicks();
	}

	/**
	 * @return 每 tick 跨区块持久队列最大处理条目数
	 */
	public static int crossChunkDispatchMaxPerTick() {
		return crossChunkValues.dispatchMaxPerTick();
	}

	/**
	 * @return 是否启用跨区块持久派发队列
	 */
	public static boolean crossChunkQueueEnabled() {
		return crossChunkValues.queueEnabled();
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
	 * @return “其他权限”命令组所需权限等级（0~4）
	 */
	public static int otherCommandPermissionLevel() {
		return values.otherCommandPermissionLevel();
	}

	/**
	 * @return 是否启用命令测试模式
	 */
	public static boolean commandBenchmarkModeEnabled() {
		return values.commandBenchmarkModeEnabled();
	}

	/**
	 * @return 是否启用输入播放命令与运行时服务
	 */
	public static boolean commandInputEnabled() {
		return values.commandInputEnabled();
	}

	/**
	 * @return 是否启用节点状态追踪命令与运行时服务
	 */
	public static boolean commandNodeTraceEnabled() {
		return values.commandNodeTraceEnabled();
	}

	/**
	 * @return 是否启用 `core` 读档后的异步外显自愈
	 */
	public static boolean runtimeCoreLoadResyncEnabled() {
		return values.runtimeCoreLoadResyncEnabled();
	}

	/**
	 * @return 是否启用 `triggerSource` 读档后的异步输入自愈
	 */
	public static boolean runtimeTriggerSourceLoadResyncEnabled() {
		return values.runtimeTriggerSourceLoadResyncEnabled();
	}

	/**
	 * @return 加载后自愈的最大额外重试次数
	 */
	public static int runtimeLoadResyncMaxRetry() {
		return values.runtimeLoadResyncMaxRetry();
	}

	/**
	 * @return 是否启用命令频率防护
	 */
	public static boolean commandRateLimitEnabled() {
		return values.commandRateLimitEnabled();
	}

	/**
	 * @return 命令频率防护窗口长度（tick）
	 */
	public static int commandRateLimitWindowTicks() {
		return values.commandRateLimitWindowTicks();
	}

	/**
	 * @return 全局窗口容量（所有来源共享）
	 */
	public static int commandRateLimitGlobalCapacity() {
		return values.commandRateLimitGlobalCapacity();
	}

	/**
	 * 按权限等级计算层级窗口容量。
	 *
	 * @param permissionLevel 权限等级（0~4）
	 * @return 对应层级容量
	 */
	public static int commandRateLimitTierCapacity(int permissionLevel) {
		return resolveRateLimitCapacity(
			values.commandRateLimitTierBaseCapacity(),
			values.commandRateLimitTierStepPerLevel(),
			permissionLevel
		);
	}

	/**
	 * 按权限等级计算“来源个体”窗口容量。
	 *
	 * @param permissionLevel 权限等级（0~4）
	 * @return 对应个体容量
	 */
	public static int commandRateLimitActorCapacity(int permissionLevel) {
		return resolveRateLimitCapacity(
			values.commandRateLimitActorBaseCapacity(),
			values.commandRateLimitActorStepPerLevel(),
			permissionLevel
		);
	}

	/**
	 * 按权限等级计算 `link` 组个体窗口容量。
	 *
	 * @param permissionLevel 权限等级（0~4）
	 * @return link 组容量
	 */
	public static int commandRateLimitActorLinkRwCapacity(int permissionLevel) {
		return resolveRateLimitCapacity(
			values.commandRateLimitActorGroupLinkRwBaseCapacity(),
			values.commandRateLimitActorGroupLinkRwStepPerLevel(),
			permissionLevel
		);
	}

	/**
	 * 按权限等级计算 `crosschunk` 组个体窗口容量。
	 *
	 * @param permissionLevel 权限等级（0~4）
	 * @return crosschunk 组容量
	 */
	public static int commandRateLimitActorCrossChunkCapacity(int permissionLevel) {
		return resolveRateLimitCapacity(
			values.commandRateLimitActorGroupCrossChunkBaseCapacity(),
			values.commandRateLimitActorGroupCrossChunkStepPerLevel(),
			permissionLevel
		);
	}

	/**
	 * 按权限等级计算 `other` 组个体窗口容量。
	 *
	 * @param permissionLevel 权限等级（0~4）
	 * @return other 组容量
	 */
	public static int commandRateLimitActorOtherCapacity(int permissionLevel) {
		return resolveRateLimitCapacity(
			values.commandRateLimitActorGroupOtherBaseCapacity(),
			values.commandRateLimitActorGroupOtherStepPerLevel(),
			permissionLevel
		);
	}

	/**
	 * @return 近外显“当前连接”保密模式
	 */
	public static CurrentLinksPrivacyMode currentLinksPrivacyMode() {
		return values.currentLinksPrivacyMode();
	}

	/**
	 * @return 查看被加密“当前连接”所需权限等级（0~4）
	 */
	public static int currentLinksPrivacyViewPermissionLevel() {
		return values.currentLinksPrivacyViewPermissionLevel();
	}

	/**
	 * @return 管理“当前连接加密名单”命令所需权限等级（0~4）
	 */
	public static int currentLinksPrivacyManagePermissionLevel() {
		return values.currentLinksPrivacyManagePermissionLevel();
	}

	/**
	 * @return 链接写入控制模式（full/limited/readonly）
	 */
	public static LinkWriteControlMode linkWriteControlMode() {
		return values.linkWriteControlMode();
	}

	/**
	 * @return limited 模式下允许越过“最大设置量”限制的权限等级（0~4）
	 */
	public static int linkWriteLimitedPermissionLevel() {
		return values.linkWriteLimitedPermissionLevel();
	}

	/**
	 * @return limited 模式下单次 set 允许的最大“目标设置量”
	 */
	public static int linkWriteLimitedMaxSetSize() {
		return values.linkWriteLimitedMaxSetSize();
	}

	/**
	 * @return 命中受控名单后允许写入所需权限等级（0~4）
	 */
	public static int linkWriteProtectedPermissionLevel() {
		return values.linkWriteProtectedPermissionLevel();
	}

	/**
	 * @return 维护受控名单命令所需权限等级（0~4）
	 */
	public static int linkWriteProtectedManagePermissionLevel() {
		return values.linkWriteProtectedManagePermissionLevel();
	}

	/**
	 * @return `link set` 目标输入文本最大长度（字符）
	 */
	public static int linkSetMaxInputLength() {
		return values.linkSetMaxInputLength();
	}

	/**
	 * @return `node activate` 批量来源序号上限
	 */
	public static int activateBatchMaxSerials() {
		return values.activateBatchMaxSerials();
	}

	/**
	 * @return `node retire batch` 批量序号上限
	 */
	public static int retireBatchMaxSerials() {
		return values.retireBatchMaxSerials();
	}

	/**
	 * @return `link privacy current_links mask set` 批量序号上限
	 */
	public static int currentLinksMaskSetMaxSerials() {
		return values.currentLinksMaskSetMaxSerials();
	}

	/**
	 * @return `link write_control protected set` 批量序号上限
	 */
	public static int writeControlProtectedSetMaxSerials() {
		return values.writeControlProtectedSetMaxSerials();
	}

	/**
	 * @return `crosschunk whitelist set` 批量序号上限
	 */
	public static int crossChunkWhitelistSetMaxSerials() {
		return values.crossChunkWhitelistSetMaxSerials();
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
	 * @return 是否启用运行时慢路径诊断日志
	 */
	public static boolean runtimeDiagEnabled() {
		return crossChunkValues.runtimeDiagEnabled();
	}

	/**
	 * @return 运行时慢路径诊断阈值（毫秒）
	 */
	public static int runtimeDiagWarnThresholdMs() {
		return crossChunkValues.runtimeDiagWarnThresholdMs();
	}

	/**
	 * @return 是否在 sync fanout 慢日志中输出扇出计数字段
	 */
	public static boolean runtimeDiagFanoutCountersEnabled() {
		return crossChunkValues.runtimeDiagFanoutCountersEnabled();
	}

	/**
	 * @return 跨区块派发失败重试告警阈值（次数，0=关闭）
	 */
	public static int crossChunkRetryWarnThreshold() {
		return crossChunkValues.retryWarnThreshold();
	}

	/**
	 * @return 跨区块派发失败重试错误阈值（次数，0=关闭）
	 */
	public static int crossChunkRetryErrorThreshold() {
		return crossChunkValues.retryErrorThreshold();
	}

	/**
	 * @return 跨区块派发失败重试丢弃阈值（次数，0=关闭）
	 */
	public static int crossChunkRetryDropThreshold() {
		return crossChunkValues.retryDropThreshold();
	}

	/**
	 * @return 持久 pending 当前失败次数所在的重试阶段（1~4）
	 */
	public static int crossChunkRetryPersistentStageIndex(int attempts) {
		return resolveCrossChunkRetryStageIndex(crossChunkValues, attempts);
	}

	/**
	 * @return 持久 pending 当前失败次数对应的重试间隔（tick）
	 */
	public static int crossChunkRetryPersistentIntervalTicks(int attempts) {
		return resolveCrossChunkRetryIntervalTicks(crossChunkValues, attempts);
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
		return RedstoneLinkConfigInteractionSupport.canOpenPairingByHeldItem(player, hand);
	}

	/**
	 * 统一“遥控器打开配对界面”条件校验。
	 */
	public static boolean canOpenPairingByLinker(Player player, InteractionHand hand) {
		return RedstoneLinkConfigInteractionSupport.canOpenPairingByLinker(player, hand);
	}

	/**
	 * 统一“已放置方块打开配对界面”条件校验。
	 */
	public static boolean canOpenPairingByPlacedBlock(Player player) {
		return RedstoneLinkConfigInteractionSupport.canOpenPairingByPlacedBlock(player);
	}

	/**
	 * 解析基础配置快照。
	 */
	private static RedstoneLinkConfigValues parse(Properties props) {
		return RedstoneLinkConfigParser.parse(props);
	}

	/**
	 * 解析跨区块配置快照。
	 */
	private static RedstoneLinkCrossChunkConfigValues parseCrossChunk(Properties props) {
		return RedstoneLinkCrossChunkConfigParser.parse(props);
	}

	/**
	 * 解析持久 pending 当前失败次数所在的分段索引。
	 */
	private static int resolveCrossChunkRetryStageIndex(RedstoneLinkCrossChunkConfigValues values, int attempts) {
		return RedstoneLinkConfigRuntimeSupport.resolveCrossChunkRetryStageIndex(values, attempts);
	}

	/**
	 * 解析持久 pending 当前失败次数对应的重试间隔。
	 */
	private static int resolveCrossChunkRetryIntervalTicks(RedstoneLinkCrossChunkConfigValues values, int attempts) {
		return RedstoneLinkConfigRuntimeSupport.resolveCrossChunkRetryIntervalTicks(values, attempts);
	}

	/**
	 * 根据权限等级解析分层限流容量。
	 *
	 * @param baseCapacity 0 级基础容量
	 * @param stepPerLevel 每提升 1 级权限增加容量
	 * @param permissionLevel 权限等级（0~4）
	 * @return 对应权限等级容量，最小 1
	 */
	private static int resolveRateLimitCapacity(int baseCapacity, int stepPerLevel, int permissionLevel) {
		return RedstoneLinkConfigRuntimeSupport.resolveRateLimitCapacity(baseCapacity, stepPerLevel, permissionLevel);
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
			LOGGER.warn("Failed to write default config: {}", CONFIG_PATH.toAbsolutePath(), ex);
		}
	}

	/**
	 * 生成默认配置文件内容。
	 */
	private static String defaultConfigContent() {
		return RedstoneLinkConfigTemplate.defaultConfigContent();
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
		 *
		 * @param raw 原始配置值
		 * @return 解析后的模式，非法值回退为 FULL
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
		 *
		 * @param raw 原始配置值
		 * @return 解析后的模式，非法值回退为 PLAIN
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

}
