package com.makomi.data;

import java.util.Collections;
import java.util.HashSet;
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
		LinkConnectionMode sourceMode = effectiveMode(
			data,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			overrideType,
			overrideSerial,
			overrideMode
		);
		long sourceChannel = effectiveChannel(
			data,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			overrideType,
			overrideSerial,
			overrideMode,
			overrideChannel
		);
		if (sourceMode == LinkConnectionMode.CHANNEL) {
			if (!isValidChannel(sourceChannel)) {
				return Set.of();
			}
			return collectEffectiveChannelCores(data, sourceChannel, overrideType, overrideSerial, overrideMode, overrideChannel);
		}
		return collectEffectiveSerialTargets(data, triggerSourceSerial, overrideType, overrideSerial, overrideMode);
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

	private static LinkConnectionMode effectiveMode(
		LinkSavedData data,
		LinkNodeType type,
		long serial,
		LinkNodeType overrideType,
		long overrideSerial,
		LinkConnectionMode overrideMode
	) {
		if (type == overrideType && serial == overrideSerial) {
			return overrideMode == null ? LinkConnectionMode.SERIAL : overrideMode;
		}
		return getConnectionMode(data, type, serial);
	}

	private static long effectiveChannel(
		LinkSavedData data,
		LinkNodeType type,
		long serial,
		LinkNodeType overrideType,
		long overrideSerial,
		LinkConnectionMode overrideMode,
		long overrideChannel
	) {
		if (type == overrideType && serial == overrideSerial) {
			return overrideMode == LinkConnectionMode.CHANNEL && isValidChannel(overrideChannel) ? overrideChannel : 0L;
		}
		return getChannel(data, type, serial);
	}

	private static Set<Long> collectEffectiveSerialTargets(
		LinkSavedData data,
		long triggerSourceSerial,
		LinkNodeType overrideType,
		long overrideSerial,
		LinkConnectionMode overrideMode
	) {
		Set<Long> desiredTargets = new HashSet<>();
		for (Long coreSerial : data.getLinkedCoresByTriggerSource(triggerSourceSerial)) {
			if (coreSerial == null || coreSerial <= 0L) {
				continue;
			}
			LinkConnectionMode coreMode = effectiveMode(
				data,
				LinkNodeType.CORE,
				coreSerial,
				overrideType,
				overrideSerial,
				overrideMode
			);
			if (coreMode == LinkConnectionMode.SERIAL) {
				desiredTargets.add(coreSerial);
			}
		}
		return desiredTargets.isEmpty() ? Set.of() : Set.copyOf(desiredTargets);
	}

	private static Set<Long> collectEffectiveChannelCores(
		LinkSavedData data,
		long channel,
		LinkNodeType overrideType,
		long overrideSerial,
		LinkConnectionMode overrideMode,
		long overrideChannel
	) {
		Set<Long> desiredTargets = new HashSet<>(getChannelMembers(data, LinkNodeType.CORE, channel));
		if (overrideType == LinkNodeType.CORE && overrideSerial > 0L) {
			long currentChannel = getChannel(data, LinkNodeType.CORE, overrideSerial);
			if (currentChannel == channel) {
				desiredTargets.remove(overrideSerial);
			}
			if (overrideMode == LinkConnectionMode.CHANNEL && overrideChannel == channel) {
				desiredTargets.add(overrideSerial);
			}
		}
		return desiredTargets.isEmpty() ? Set.of() : Set.copyOf(desiredTargets);
	}
}
