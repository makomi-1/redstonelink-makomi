package com.makomi.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LinkSavedData 频道配置与运行时桶索引 helper。
 * <p>
 * 该 helper 只负责频道配置真值、索引维护与纯查询推导；
 * 真实普通边的改写由上层编辑服务负责。
 * </p>
 */
public final class LinkSavedDataChannelSupport {
	private LinkSavedDataChannelSupport() {
	}

	/**
	 * 判断频道号是否为合法的正 long。
	 */
	public static boolean isValidChannel(long channel) {
		return channel > 0L;
	}

	/**
	 * 查询节点当前连接模式。
	 */
	public static LinkConnectionMode getConnectionMode(LinkSavedData data, LinkNodeType type, long serial) {
		if (data == null || type == null || serial <= 0L) {
			return LinkConnectionMode.SERIAL;
		}
		return configMap(data, type).containsKey(serial) ? LinkConnectionMode.CHANNEL : LinkConnectionMode.SERIAL;
	}

	/**
	 * 查询节点当前频道号；非频道模式返回 0。
	 */
	public static long getChannel(LinkSavedData data, LinkNodeType type, long serial) {
		if (data == null || type == null || serial <= 0L) {
			return 0L;
		}
		return configMap(data, type).getOrDefault(serial, 0L);
	}

	/**
	 * 查询指定频道下的成员集合。
	 */
	public static Set<Long> getChannelMembers(LinkSavedData data, LinkNodeType type, long channel) {
		if (data == null || type == null || !isValidChannel(channel)) {
			return Collections.emptySet();
		}
		Set<Long> members = channelIndex(data, type).get(channel);
		if (members == null || members.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(members);
	}

	/**
	 * 重建全部频道运行时桶索引。
	 */
	public static void rebuildChannelIndex(LinkSavedData data) {
		if (data == null) {
			return;
		}
		data.channelToTriggerSources.clear();
		data.channelToCores.clear();
		rebuildIndexForType(data, LinkNodeType.TRIGGER_SOURCE);
		rebuildIndexForType(data, LinkNodeType.CORE);
	}

	/**
	 * 写入节点频道配置。
	 *
	 * @return 是否真实发生变化
	 */
	public static boolean putChannelConfig(LinkSavedData data, LinkNodeType type, long serial, long channel) {
		if (data == null || type == null || serial <= 0L || !isValidChannel(channel)) {
			return false;
		}
		Map<Long, Long> configs = configMap(data, type);
		Long previousChannel = configs.put(serial, channel);
		if (previousChannel != null && previousChannel == channel) {
			return false;
		}
		if (previousChannel != null && previousChannel > 0L) {
			removeMemberFromIndex(channelIndex(data, type), previousChannel, serial);
		}
		channelIndex(data, type).computeIfAbsent(channel, ignored -> new HashSet<>()).add(serial);
		data.setDirty();
		return true;
	}

	/**
	 * 清理节点频道配置。
	 *
	 * @return 是否真实发生变化
	 */
	public static boolean clearChannelConfig(LinkSavedData data, LinkNodeType type, long serial) {
		if (data == null || type == null || serial <= 0L) {
			return false;
		}
		Long removedChannel = configMap(data, type).remove(serial);
		if (removedChannel == null || removedChannel <= 0L) {
			return false;
		}
		removeMemberFromIndex(channelIndex(data, type), removedChannel, serial);
		data.setDirty();
		return true;
	}

	/**
	 * 在“单节点配置即将切换”的假设下，推导某个 triggerSource 应有的目标集合。
	 * <p>
	 * 该推导只读取频道配置与当前普通边，不直接写回任何状态，供 prepare 阶段复用。
	 * </p>
	 */
	public static Set<Long> resolveDesiredTargetsForTriggerSourceWithOverride(
		LinkSavedData data,
		long triggerSourceSerial,
		LinkNodeType overrideType,
		long overrideSerial,
		LinkConnectionMode overrideMode,
		long overrideChannel
	) {
		if (data == null || triggerSourceSerial <= 0L) {
			return Set.of();
		}
		return resolveDesiredTargetsForTriggerSourceWithContext(
			data,
			triggerSourceSerial,
			createBatchTargetResolutionContext(
				Set.of(
					new ChannelOverride(
						overrideType,
						overrideSerial,
						overrideMode == LinkConnectionMode.CHANNEL ? overrideChannel : 0L
					)
				)
			)
		);
	}

	/**
	 * 在“多节点频道配置即将整体切换”的假设下，推导某个 triggerSource 应有的目标集合。
	 * <p>
	 * 该方法只读取当前真值与本次批量覆盖结果，不直接写回任何状态。
	 * </p>
	 */
	public static Set<Long> resolveDesiredTargetsForTriggerSourceWithOverrides(
		LinkSavedData data,
		long triggerSourceSerial,
		Iterable<ChannelOverride> overrides
	) {
		return resolveDesiredTargetsForTriggerSourceWithContext(
			data,
			triggerSourceSerial,
			createBatchTargetResolutionContext(overrides)
		);
	}

	/**
	 * 为一批频道覆盖构造可复用的目标推导上下文。
	 * <p>
	 * 该上下文会缓存：
	 * </p>
	 * <ul>
	 * <li>按 `nodeKey` 索引后的 overrides；</li>
	 * <li>仅影响 core 成员推导的 core overrides；</li>
	 * <li>按频道号缓存的“最终 core 成员集合”。</li>
	 * </ul>
	 */
	public static BatchTargetResolutionContext createBatchTargetResolutionContext(Iterable<ChannelOverride> overrides) {
		Map<String, ChannelOverride> overridesByNodeKey = new LinkedHashMap<>();
		List<ChannelOverride> coreOverrides = new ArrayList<>();
		if (overrides != null) {
			for (ChannelOverride override : overrides) {
				if (override == null || !override.valid()) {
					continue;
				}
				overridesByNodeKey.put(buildOverrideNodeKey(override.nodeType(), override.serial()), override);
				if (override.nodeType() == LinkNodeType.CORE) {
					coreOverrides.add(override);
				}
			}
		}
		return new BatchTargetResolutionContext(
			overridesByNodeKey.isEmpty() ? Map.of() : Map.copyOf(overridesByNodeKey),
			coreOverrides.isEmpty() ? List.of() : List.copyOf(coreOverrides)
		);
	}

	/**
	 * 基于可复用上下文推导某个 triggerSource 的最终目标集合。
	 * <p>
	 * 该入口供批量 prepare 复用，避免在大频道场景下为每个 triggerSource 重建 overrides 索引，
	 * 并允许同一频道的 core 成员结果在整批中只计算一次。
	 * </p>
	 */
	public static Set<Long> resolveDesiredTargetsForTriggerSourceWithContext(
		LinkSavedData data,
		long triggerSourceSerial,
		BatchTargetResolutionContext resolutionContext
	) {
		if (data == null || triggerSourceSerial <= 0L) {
			return Set.of();
		}
		Map<String, ChannelOverride> overridesByNodeKey = resolutionContext == null ? Map.of() : resolutionContext.overridesByNodeKey();
		LinkConnectionMode sourceMode = effectiveMode(data, LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial, overridesByNodeKey);
		long sourceChannel = effectiveChannel(data, LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial, overridesByNodeKey);
		if (sourceMode == LinkConnectionMode.CHANNEL) {
			if (!isValidChannel(sourceChannel)) {
				return Set.of();
			}
			return collectEffectiveChannelCores(data, sourceChannel, resolutionContext);
		}
		return collectEffectiveSerialTargets(data, triggerSourceSerial, overridesByNodeKey);
	}

	/**
	 * 查询节点当前频道配置表。
	 */
	static Map<Long, Long> configMap(LinkSavedData data, LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? data.triggerSourceChannelConfigs : data.coreChannelConfigs;
	}

	/**
	 * 查询节点当前频道桶索引。
	 */
	static Map<Long, Set<Long>> channelIndex(LinkSavedData data, LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? data.channelToTriggerSources : data.channelToCores;
	}

	private static void rebuildIndexForType(LinkSavedData data, LinkNodeType type) {
		for (Map.Entry<Long, Long> entry : configMap(data, type).entrySet()) {
			long serial = entry.getKey() == null ? 0L : entry.getKey();
			long channel = entry.getValue() == null ? 0L : entry.getValue();
			if (serial <= 0L || !isValidChannel(channel)) {
				continue;
			}
			channelIndex(data, type).computeIfAbsent(channel, ignored -> new HashSet<>()).add(serial);
		}
	}

	private static void removeMemberFromIndex(Map<Long, Set<Long>> index, long channel, long serial) {
		if (index == null || !isValidChannel(channel) || serial <= 0L) {
			return;
		}
		Set<Long> members = index.get(channel);
		if (members == null) {
			return;
		}
		members.remove(serial);
		if (members.isEmpty()) {
			index.remove(channel);
		}
	}

	private static LinkConnectionMode effectiveMode(LinkSavedData data, LinkNodeType type, long serial, Map<String, ChannelOverride> overridesByNodeKey) {
		ChannelOverride override = overridesByNodeKey.get(buildOverrideNodeKey(type, serial));
		if (override != null) {
			return override.mode();
		}
		return getConnectionMode(data, type, serial);
	}

	private static long effectiveChannel(LinkSavedData data, LinkNodeType type, long serial, Map<String, ChannelOverride> overridesByNodeKey) {
		ChannelOverride override = overridesByNodeKey.get(buildOverrideNodeKey(type, serial));
		if (override != null) {
			return override.mode() == LinkConnectionMode.CHANNEL ? override.channel() : 0L;
		}
		return getChannel(data, type, serial);
	}

	private static Set<Long> collectEffectiveSerialTargets(LinkSavedData data, long triggerSourceSerial, Map<String, ChannelOverride> overridesByNodeKey) {
		Set<Long> desiredTargets = new HashSet<>();
		for (Long coreSerial : data.getLinkedCoresByTriggerSource(triggerSourceSerial)) {
			if (coreSerial == null || coreSerial <= 0L) {
				continue;
			}
			LinkConnectionMode coreMode = effectiveMode(data, LinkNodeType.CORE, coreSerial, overridesByNodeKey);
			if (coreMode == LinkConnectionMode.SERIAL) {
				desiredTargets.add(coreSerial);
			}
		}
		return desiredTargets.isEmpty() ? Set.of() : Set.copyOf(desiredTargets);
	}

	private static Set<Long> collectEffectiveChannelCores(
		LinkSavedData data,
		long channel,
		BatchTargetResolutionContext resolutionContext
	) {
		if (resolutionContext != null) {
			Set<Long> cachedTargets = resolutionContext.cachedChannelCoreTargets(channel);
			if (cachedTargets != null) {
				return cachedTargets;
			}
		}
		Set<Long> desiredTargets = new HashSet<>(getChannelMembers(data, LinkNodeType.CORE, channel));
		List<ChannelOverride> coreOverrides = resolutionContext == null ? List.of() : resolutionContext.coreOverrides();
		for (ChannelOverride override : coreOverrides) {
			if (override.serial() <= 0L) {
				continue;
			}
			long currentChannel = getChannel(data, LinkNodeType.CORE, override.serial());
			if (currentChannel == channel) {
				desiredTargets.remove(override.serial());
			}
			if (override.mode() == LinkConnectionMode.CHANNEL && override.channel() == channel) {
				desiredTargets.add(override.serial());
			}
		}
		Set<Long> resolvedTargets = desiredTargets.isEmpty() ? Set.of() : Set.copyOf(desiredTargets);
		if (resolutionContext != null) {
			resolutionContext.cacheChannelCoreTargets(channel, resolvedTargets);
		}
		return resolvedTargets;
	}

	private static String buildOverrideNodeKey(LinkNodeType type, long serial) {
		return (type == LinkNodeType.TRIGGER_SOURCE ? "triggerSource" : "core") + ":" + Math.max(0L, serial);
	}

	/**
	 * 批量频道覆盖中的单节点最终配置。
	 */
	public record ChannelOverride(LinkNodeType nodeType, long serial, long channel) {
		public ChannelOverride {
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE
				? LinkNodeType.TRIGGER_SOURCE
				: nodeType == LinkNodeType.CORE ? LinkNodeType.CORE : null;
			serial = Math.max(0L, serial);
			channel = Math.max(0L, channel);
		}

		/**
		 * @return 当前覆盖结果对应的连接模式
		 */
		public LinkConnectionMode mode() {
			return isValidChannel(channel) ? LinkConnectionMode.CHANNEL : LinkConnectionMode.SERIAL;
		}

		/**
		 * @return 当前覆盖是否指向有效节点
		 */
		public boolean valid() {
			return nodeType != null && serial > 0L;
		}
	}

	/**
	 * 批量频道目标推导上下文。
	 * <p>
	 * 该上下文只服务于 prepare / preview 阶段，不参与任何真值写回。
	 * </p>
	 */
	public static final class BatchTargetResolutionContext {
		private final Map<String, ChannelOverride> overridesByNodeKey;
		private final List<ChannelOverride> coreOverrides;
		private final Map<Long, Set<Long>> cachedChannelCoreTargetsByChannel;

		private BatchTargetResolutionContext(
			Map<String, ChannelOverride> overridesByNodeKey,
			List<ChannelOverride> coreOverrides
		) {
			this.overridesByNodeKey = overridesByNodeKey == null ? Map.of() : overridesByNodeKey;
			this.coreOverrides = coreOverrides == null ? List.of() : coreOverrides;
			this.cachedChannelCoreTargetsByChannel = new LinkedHashMap<>();
		}

		private Map<String, ChannelOverride> overridesByNodeKey() {
			return overridesByNodeKey;
		}

		private List<ChannelOverride> coreOverrides() {
			return coreOverrides;
		}

		private Set<Long> cachedChannelCoreTargets(long channel) {
			return cachedChannelCoreTargetsByChannel.get(channel);
		}

		private void cacheChannelCoreTargets(long channel, Set<Long> targets) {
			if (!isValidChannel(channel)) {
				return;
			}
			cachedChannelCoreTargetsByChannel.put(channel, targets == null ? Set.of() : targets);
		}
	}
}
