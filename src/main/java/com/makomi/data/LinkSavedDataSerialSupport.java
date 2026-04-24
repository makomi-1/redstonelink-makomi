package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.util.SignalStrengths;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * LinkSavedData 序号治理 helper。
 * <p>
 * 负责 triggerSource/core 的序号分配、放置冲突修正、退役与已分配集合维护。
 * </p>
 */
final class LinkSavedDataSerialSupport {
	private LinkSavedDataSerialSupport() {
	}

	/**
	 * 为指定节点类型分配新序列号并立即标记为已分配。
	 */
	static long allocateSerial(LinkSavedData data, LinkNodeType type) {
		long serial = allocateFromCounter(data, type);
		markAllocatedInternal(data, type, serial);
		data.setDirty();
		return serial;
	}

	/**
	 * 为转发器分配统一序号，并同步登记到 `core/triggerSource` 双侧已分配集合。
	 */
	static long allocateRepeaterSerial(LinkSavedData data) {
		long serial = allocateRepeaterFromCounters(data);
		markAllocatedInternal(data, LinkNodeType.CORE, serial);
		markAllocatedInternal(data, LinkNodeType.TRIGGER_SOURCE, serial);
		markRepeaterInternal(data, serial);
		data.setDirty();
		return serial;
	}

	/**
	 * 解析放置场景下最终可用的序列号。
	 */
	static long resolvePlacementSerial(
		LinkSavedData data,
		LinkNodeType type,
		long preferredSerial,
		ResourceKey<Level> dimension,
		BlockPos pos
	) {
		long serial = preferredSerial;
		boolean changed = false;

		if (serial <= 0L || isSerialRetired(data, type, serial)) {
			serial = allocateFromCounter(data, type);
			markAllocatedInternal(data, type, serial);
			changed = true;
		} else if (!isSerialAllocated(data, type, serial)) {
			changed = markAllocatedInternal(data, type, serial);
		}

		LinkSavedData.LinkNode existing = data.nodeMap(type).get(serial);
		if (existing != null && (!existing.dimension().equals(dimension) || !existing.pos().equals(pos))) {
			serial = allocateFromCounter(data, type);
			markAllocatedInternal(data, type, serial);
			changed = true;
		}

		if (changed) {
			data.setDirty();
		}
		return serial;
	}

	/**
	 * 解析转发器放置场景下最终可用的统一序号。
	 */
	static long resolveRepeaterPlacementSerial(
		LinkSavedData data,
		long preferredSerial,
		ResourceKey<Level> dimension,
		BlockPos pos
	) {
		long serial = preferredSerial;
		boolean changed = false;

		if (
			serial <= 0L
				|| isSerialRetired(data, LinkNodeType.CORE, serial)
				|| isSerialRetired(data, LinkNodeType.TRIGGER_SOURCE, serial)
		) {
			serial = allocateRepeaterFromCounters(data);
			changed = true;
		}

		LinkSavedData.LinkNode existingCore = data.nodeMap(LinkNodeType.CORE).get(serial);
		LinkSavedData.LinkNode existingTriggerSource = data.nodeMap(LinkNodeType.TRIGGER_SOURCE).get(serial);
		if (isNodeOccupiedByOtherPosition(existingCore, dimension, pos) || isNodeOccupiedByOtherPosition(existingTriggerSource, dimension, pos)) {
			serial = allocateRepeaterFromCounters(data);
			changed = true;
		}

		changed |= markAllocatedInternal(data, LinkNodeType.CORE, serial);
		changed |= markAllocatedInternal(data, LinkNodeType.TRIGGER_SOURCE, serial);
		changed |= markRepeaterInternal(data, serial);

		if (changed) {
			data.setDirty();
		}
		return serial;
	}

	/**
	 * 注册（或更新）在线节点坐标信息。
	 */
	static void registerNode(
		LinkSavedData data,
		long serial,
		ResourceKey<Level> dimension,
		BlockPos pos,
		LinkNodeType type
	) {
		if (serial <= 0L) {
			return;
		}

		LinkSavedData.LinkNode node = new LinkSavedData.LinkNode(serial, dimension, pos.immutable(), type);
		LinkSavedData.LinkNode previous = data.nodeMap(type).get(serial);
		if (previous != null && !previous.equals(node)) {
			data.unindexNode(previous);
		}
		data.nodeMap(type).put(serial, node);
		boolean changed = previous == null || !previous.equals(node);
		if (changed) {
			data.indexNode(node);
			data.bumpRuntimeNodeVersion();
		}
		if (markAllocatedInternal(data, type, serial) || changed) {
			data.setDirty();
		}
	}

	/**
	 * 从在线节点表中移除指定节点。
	 */
	static void removeNode(LinkSavedData data, LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return;
		}
		LinkSavedData.LinkNode removed = data.nodeMap(type).remove(serial);
		if (removed != null) {
			data.unindexNode(removed);
			data.bumpRuntimeNodeVersion();
			data.setDirty();
		}
	}

	/**
	 * 退役节点并清理其关联关系。
	 */
	static LinkSavedData.RetireResult retireNode(LinkSavedData data, LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return new LinkSavedData.RetireResult(false, 0, false);
		}

		boolean allocatedMarked = markAllocatedInternal(data, type, serial);
		boolean retiredMarked = markRetiredInternal(data, type, serial);
		LinkSavedData.LinkNode removedNode = data.nodeMap(type).remove(serial);
		boolean removed = removedNode != null;
		boolean channelConfigRemoved = LinkSavedDataChannelSupport.clearChannelConfig(data, type, serial);
		boolean replaySnapshotRemoved = type == LinkNodeType.TRIGGER_SOURCE
			&& data.triggerSourceReplaySyncSnapshots.remove(serial) != null;
		int clearedLinks = LinkSavedDataLinkIndexSupport.clearLinksForNode(data, type, serial);
		if (removed) {
			data.unindexNode(removedNode);
			data.bumpRuntimeNodeVersion();
		}
		if (allocatedMarked || retiredMarked || removed || channelConfigRemoved || replaySnapshotRemoved || clearedLinks > 0) {
			data.setDirty();
		}
		return new LinkSavedData.RetireResult(removed, clearedLinks, retiredMarked);
	}

	/**
	 * 判断序列号是否已登记分配。
	 */
	static boolean isSerialAllocated(LinkSavedData data, LinkNodeType type, long serial) {
		return serial > 0L && data.allocatedSerialSet(type).contains(serial);
	}

	/**
	 * 判断序列号是否已退役。
	 */
	static boolean isSerialRetired(LinkSavedData data, LinkNodeType type, long serial) {
		return serial > 0L && data.retiredSerialSet(type).contains(serial);
	}

	/**
	 * 判断序列号是否处于可用激活状态。
	 */
	static boolean isSerialActive(LinkSavedData data, LinkNodeType type, long serial) {
		return isSerialAllocated(data, type, serial) && !isSerialRetired(data, type, serial);
	}

	/**
	 * 手动登记序列号为“已分配”。
	 */
	static boolean markSerialAllocated(LinkSavedData data, LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return false;
		}
		boolean changed = markAllocatedInternal(data, type, serial);
		if (changed) {
			data.setDirty();
		}
		return changed;
	}

	/**
	 * 手动登记序列号为“转发器统一序号”。
	 */
	static boolean markRepeaterSerial(LinkSavedData data, long serial) {
		if (serial <= 0L) {
			return false;
		}
		boolean changed = markRepeaterInternal(data, serial);
		if (changed) {
			markAllocatedInternal(data, LinkNodeType.CORE, serial);
			markAllocatedInternal(data, LinkNodeType.TRIGGER_SOURCE, serial);
			data.setDirty();
		}
		return changed;
	}

	/**
	 * 从“转发器统一序号”集合中移除指定序号。
	 */
	static boolean unmarkRepeaterSerial(LinkSavedData data, long serial) {
		if (serial <= 0L) {
			return false;
		}
		boolean changed = data.repeaterSerialSet().remove(serial);
		if (changed) {
			data.setDirty();
		}
		return changed;
	}

	/**
	 * 判断指定序号是否登记为转发器统一序号。
	 */
	static boolean isRepeaterSerial(LinkSavedData data, long serial) {
		return serial > 0L && data.repeaterSerialSet().contains(serial);
	}

	/**
	 * 更新 triggerSource 最近一次真实 sync replay 快照。
	 */
	static void putTriggerSourceReplaySyncSnapshot(
		LinkSavedData data,
		long triggerSourceSerial,
		EventMeta eventMeta,
		int signalStrength
	) {
		if (data == null || triggerSourceSerial <= 0L || eventMeta == null) {
			return;
		}
		LinkSavedData.ReplaySyncSnapshotRecord nextSnapshot = new LinkSavedData.ReplaySyncSnapshotRecord(
			SignalStrengths.clamp(signalStrength),
			eventMeta
		);
		LinkSavedData.ReplaySyncSnapshotRecord previous = data.triggerSourceReplaySyncSnapshots.put(
			triggerSourceSerial,
			nextSnapshot
		);
		if (!nextSnapshot.equals(previous)) {
			data.setDirty();
		}
	}

	/**
	 * 修正下一可分配序列号，确保始终大于当前已知最大序列号。
	 */
	static void correctNextSerials(LinkSavedData data) {
		long maxTriggerSourceSerial = 0L;
		long maxCoreSerial = 0L;

		for (long serial : data.triggerSourceNodes.keySet()) {
			maxTriggerSourceSerial = Math.max(maxTriggerSourceSerial, serial);
		}
		for (long serial : data.coreNodes.keySet()) {
			maxCoreSerial = Math.max(maxCoreSerial, serial);
		}
		for (Map.Entry<Long, Set<Long>> entry : data.triggerSourceToCores.entrySet()) {
			maxTriggerSourceSerial = Math.max(maxTriggerSourceSerial, entry.getKey());
			for (long coreSerial : entry.getValue()) {
				maxCoreSerial = Math.max(maxCoreSerial, coreSerial);
			}
		}
		maxTriggerSourceSerial = Math.max(maxTriggerSourceSerial, maxValue(data.allocatedTriggerSourceSerials));
		maxTriggerSourceSerial = Math.max(maxTriggerSourceSerial, maxValue(data.retiredTriggerSourceSerials));
		maxTriggerSourceSerial = Math.max(maxTriggerSourceSerial, maxValue(data.triggerSourceReplaySyncSnapshots.keySet()));
		maxTriggerSourceSerial = Math.max(maxTriggerSourceSerial, maxValue(data.triggerSourceChannelConfigs.keySet()));
		maxTriggerSourceSerial = Math.max(maxTriggerSourceSerial, maxValue(data.repeaterSerialSet()));
		maxCoreSerial = Math.max(maxCoreSerial, maxValue(data.allocatedCoreSerials));
		maxCoreSerial = Math.max(maxCoreSerial, maxValue(data.retiredCoreSerials));
		maxCoreSerial = Math.max(maxCoreSerial, maxValue(data.coreChannelConfigs.keySet()));
		maxCoreSerial = Math.max(maxCoreSerial, maxValue(data.repeaterSerialSet()));

		data.nextTriggerSourceSerial = Math.max(data.nextTriggerSourceSerial, maxTriggerSourceSerial + 1L);
		data.nextCoreSerial = Math.max(data.nextCoreSerial, maxCoreSerial + 1L);
	}

	/**
	 * 从计数器分配序列号，并跳过在线/已分配/已退役序列号。
	 */
	static long allocateFromCounter(LinkSavedData data, LinkNodeType type) {
		Map<Long, LinkSavedData.LinkNode> onlineNodes = data.nodeMap(type);
		Set<Long> allocatedSerials = data.allocatedSerialSet(type);
		Set<Long> retiredSerials = data.retiredSerialSet(type);
		if (type == LinkNodeType.TRIGGER_SOURCE) {
			while (
				onlineNodes.containsKey(data.nextTriggerSourceSerial)
					|| allocatedSerials.contains(data.nextTriggerSourceSerial)
					|| retiredSerials.contains(data.nextTriggerSourceSerial)
			) {
				data.nextTriggerSourceSerial++;
			}
			long serial = data.nextTriggerSourceSerial;
			data.nextTriggerSourceSerial++;
			return serial;
		}

		while (
			onlineNodes.containsKey(data.nextCoreSerial)
				|| allocatedSerials.contains(data.nextCoreSerial)
				|| retiredSerials.contains(data.nextCoreSerial)
		) {
			data.nextCoreSerial++;
		}
		long serial = data.nextCoreSerial;
		data.nextCoreSerial++;
		return serial;
	}

	/**
	 * 为转发器选择一个对 `core/triggerSource` 双侧都未占用的统一序号。
	 */
	static long allocateRepeaterFromCounters(LinkSavedData data) {
		long candidate = Math.max(data.nextCoreSerial, data.nextTriggerSourceSerial);
		while (isRepeaterSerialUnavailable(data, candidate)) {
			candidate++;
		}
		data.nextCoreSerial = Math.max(data.nextCoreSerial, candidate + 1L);
		data.nextTriggerSourceSerial = Math.max(data.nextTriggerSourceSerial, candidate + 1L);
		return candidate;
	}

	/**
	 * 标记序列号进入“已分配”集合。
	 */
	static boolean markAllocatedInternal(LinkSavedData data, LinkNodeType type, long serial) {
		return data.allocatedSerialSet(type).add(serial);
	}

	/**
	 * 标记序列号进入“转发器统一序号”集合。
	 */
	static boolean markRepeaterInternal(LinkSavedData data, long serial) {
		return data.repeaterSerialSet().add(serial);
	}

	/**
	 * 标记序列号进入“已退役”集合。
	 */
	static boolean markRetiredInternal(LinkSavedData data, LinkNodeType type, long serial) {
		return data.retiredSerialSet(type).add(serial);
	}

	/**
	 * 将已知节点与链接中出现过的序列号补登记到“已分配集合”。
	 */
	static void ensureKnownSerialsAllocated(LinkSavedData data) {
		for (long serial : data.coreNodes.keySet()) {
			data.allocatedCoreSerials.add(serial);
		}
		for (long serial : data.triggerSourceNodes.keySet()) {
			data.allocatedTriggerSourceSerials.add(serial);
		}
		for (Map.Entry<Long, Set<Long>> entry : data.triggerSourceToCores.entrySet()) {
			data.allocatedTriggerSourceSerials.add(entry.getKey());
			for (long coreSerial : entry.getValue()) {
				data.allocatedCoreSerials.add(coreSerial);
			}
		}
		data.allocatedTriggerSourceSerials.addAll(data.triggerSourceReplaySyncSnapshots.keySet());
		data.allocatedTriggerSourceSerials.addAll(data.triggerSourceChannelConfigs.keySet());
		data.allocatedCoreSerials.addAll(data.coreChannelConfigs.keySet());
		data.allocatedTriggerSourceSerials.addAll(data.repeaterSerialSet());
		data.allocatedCoreSerials.addAll(data.repeaterSerialSet());
	}

	/**
	 * 求集合中的最大值；空集合返回 0。
	 */
	static long maxValue(Set<Long> values) {
		long max = 0L;
		for (long value : values) {
			max = Math.max(max, value);
		}
		return max;
	}

	/**
	 * 判断给定统一序号是否已被任一节点身份占用。
	 */
	private static boolean isRepeaterSerialUnavailable(LinkSavedData data, long serial) {
		if (serial <= 0L) {
			return true;
		}
		return data.nodeMap(LinkNodeType.CORE).containsKey(serial)
			|| data.nodeMap(LinkNodeType.TRIGGER_SOURCE).containsKey(serial)
			|| data.allocatedSerialSet(LinkNodeType.CORE).contains(serial)
			|| data.allocatedSerialSet(LinkNodeType.TRIGGER_SOURCE).contains(serial)
			|| data.retiredSerialSet(LinkNodeType.CORE).contains(serial)
			|| data.retiredSerialSet(LinkNodeType.TRIGGER_SOURCE).contains(serial);
	}

	/**
	 * 判断在线节点是否占用了其他物理位置。
	 */
	private static boolean isNodeOccupiedByOtherPosition(
		LinkSavedData.LinkNode node,
		ResourceKey<Level> dimension,
		BlockPos pos
	) {
		return node != null && (!node.dimension().equals(dimension) || !node.pos().equals(pos));
	}
}
