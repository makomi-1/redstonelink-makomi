package com.makomi.data;

import com.makomi.util.IncrementalReplacePlanUtil;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * LinkSavedData 链接索引 helper。
 * <p>
 * 负责内部 `triggerSource -> core` 双向索引的读写、最小差异替换与只读遍历视图。
 * </p>
 */
final class LinkSavedDataLinkIndexSupport {
	private LinkSavedDataLinkIndexSupport() {
	}

	/**
	 * 切换 triggerSource 与 core 之间的关联关系。
	 */
	static boolean toggleTriggerSourceCoreLink(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		if (triggerSourceSerial <= 0L || coreSerial <= 0L) {
			return false;
		}

		Set<Long> linkedCores = data.triggerSourceToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			if (removeTriggerSourceCoreLinkInternal(data, triggerSourceSerial, coreSerial)) {
				markTopologyChanged(data, Set.of(triggerSourceSerial));
			}
			return false;
		}

		linkTriggerSourceCoreInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial));
		return true;
	}

	/**
	 * 新增一条 triggerSource -> core 关联关系。
	 */
	static boolean addTriggerSourceCoreLink(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		if (triggerSourceSerial <= 0L || coreSerial <= 0L) {
			return false;
		}
		Set<Long> linkedCores = data.triggerSourceToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			return false;
		}
		linkTriggerSourceCoreInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial));
		return true;
	}

	/**
	 * 移除一条 triggerSource -> core 关联关系。
	 */
	static boolean removeTriggerSourceCoreLink(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		if (triggerSourceSerial <= 0L || coreSerial <= 0L) {
			return false;
		}
		boolean removed = removeTriggerSourceCoreLinkInternal(data, triggerSourceSerial, coreSerial);
		if (removed) {
			markTopologyChanged(data, Set.of(triggerSourceSerial));
		}
		return removed;
	}

	/**
	 * 以“覆盖集合”语义增量替换 triggerSource 的 core 目标集合。
	 */
	static LinkSavedData.ReplaceLinksResult replaceTriggerSourceTargets(
		LinkSavedData data,
		long triggerSourceSerial,
		Set<Long> coreSerials
	) {
		if (triggerSourceSerial <= 0L) {
			return new LinkSavedData.ReplaceLinksResult(0, 0, 0, 0);
		}
		Set<Long> currentTargets = new HashSet<>(getLinkedCoresByTriggerSource(data, triggerSourceSerial));
		Set<Long> normalizedTargets = normalizePositiveSerials(coreSerials);
		IncrementalReplacePlanUtil.SetReplacePlan<Long> plan = IncrementalReplacePlanUtil.buildSetReplacePlan(
			currentTargets,
			normalizedTargets
		);
		if (!plan.changed()) {
			return new LinkSavedData.ReplaceLinksResult(currentTargets.size(), 0, 0, 0);
		}

		int removed = 0;
		for (long coreSerial : plan.toRemove()) {
			if (removeTriggerSourceCoreLinkWithoutDirty(data, triggerSourceSerial, coreSerial)) {
				removed++;
			}
		}
		int added = 0;
		for (long coreSerial : plan.toAdd()) {
			if (addTriggerSourceCoreLinkWithoutDirty(data, triggerSourceSerial, coreSerial)) {
				added++;
			}
		}

		if (removed > 0 || added > 0) {
			markTopologyChanged(data, Set.of(triggerSourceSerial));
		}
		int currentCount = currentTargets.size() - removed + added;
		return new LinkSavedData.ReplaceLinksResult(currentCount, added, removed, added + removed);
	}

	/**
	 * 解除 triggerSource 与 core 的单条关联关系（不推进 revision / dirty）。
	 */
	private static boolean removeTriggerSourceCoreLinkInternal(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		Set<Long> linkedCores = data.triggerSourceToCores.get(triggerSourceSerial);
		if (linkedCores == null || !linkedCores.remove(coreSerial)) {
			return false;
		}

		if (linkedCores.isEmpty()) {
			data.triggerSourceToCores.remove(triggerSourceSerial);
		}

		Set<Long> linkedTriggerSources = data.coreToTriggerSources.get(coreSerial);
		if (linkedTriggerSources != null) {
			linkedTriggerSources.remove(triggerSourceSerial);
			if (linkedTriggerSources.isEmpty()) {
				data.coreToTriggerSources.remove(coreSerial);
			}
		}
		return true;
	}

	/**
	 * 查询 triggerSource 关联的 core 序列号集合。
	 */
	static Set<Long> getLinkedCoresByTriggerSource(LinkSavedData data, long triggerSourceSerial) {
		Set<Long> linked = data.triggerSourceToCores.get(triggerSourceSerial);
		if (linked == null || linked.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(linked);
	}

	/**
	 * 查询 core 被哪些 triggerSource 关联。
	 */
	static Set<Long> getLinkedTriggerSourcesByCore(LinkSavedData data, long coreSerial) {
		Set<Long> linked = data.coreToTriggerSources.get(coreSerial);
		if (linked == null || linked.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(linked);
	}

	/**
	 * 按节点类型查询其关联的对侧节点集合。
	 */
	static Set<Long> getLinkedPeersByNodeType(LinkSavedData data, LinkNodeType nodeType, long serial) {
		if (nodeType == null) {
			return Collections.emptySet();
		}
		return nodeType == LinkNodeType.TRIGGER_SOURCE
			? getLinkedCoresByTriggerSource(data, serial)
			: getLinkedTriggerSourcesByCore(data, serial);
	}

	/**
	 * 按节点类型无拷贝遍历其关联的对侧节点集合。
	 */
	static void forEachLinkedPeerByNodeType(
		LinkSavedData data,
		LinkNodeType nodeType,
		long serial,
		LongConsumer consumer
	) {
		if (nodeType == null || serial <= 0L || consumer == null) {
			return;
		}
		Set<Long> linkedPeers = linkedPeersViewByNodeType(data, nodeType, serial);
		if (linkedPeers == null || linkedPeers.isEmpty()) {
			return;
		}
		for (Long peerSerial : linkedPeers) {
			if (peerSerial != null && peerSerial > 0L) {
				consumer.accept(peerSerial);
			}
		}
	}

	/**
	 * 返回内部关联节点集合视图（无拷贝）。
	 */
	private static Set<Long> linkedPeersViewByNodeType(LinkSavedData data, LinkNodeType nodeType, long serial) {
		if (nodeType == null || serial <= 0L) {
			return Collections.emptySet();
		}
		Set<Long> linkedPeers = nodeType == LinkNodeType.TRIGGER_SOURCE
			? data.triggerSourceToCores.get(serial)
			: data.coreToTriggerSources.get(serial);
		if (linkedPeers == null || linkedPeers.isEmpty()) {
			return Collections.emptySet();
		}
		return linkedPeers;
	}

	/**
	 * 清理指定节点的全部关联关系。
	 */
	static int clearLinksForNode(LinkSavedData data, LinkNodeType type, long serial) {
		if (serial <= 0L) {
			return 0;
		}

		int removed = 0;
		Set<Long> changedTriggerSources = new HashSet<>();
		if (type == LinkNodeType.TRIGGER_SOURCE) {
			Set<Long> cores = data.triggerSourceToCores.remove(serial);
			if (cores == null || cores.isEmpty()) {
				return 0;
			}

			for (long coreSerial : cores) {
				Set<Long> linkedTriggerSources = data.coreToTriggerSources.get(coreSerial);
				if (linkedTriggerSources != null) {
					linkedTriggerSources.remove(serial);
					if (linkedTriggerSources.isEmpty()) {
						data.coreToTriggerSources.remove(coreSerial);
					}
				}
				removed++;
			}
			changedTriggerSources.add(serial);
		} else {
			Set<Long> triggerSources = data.coreToTriggerSources.remove(serial);
			if (triggerSources == null || triggerSources.isEmpty()) {
				return 0;
			}

			for (long triggerSourceSerial : triggerSources) {
				Set<Long> cores = data.triggerSourceToCores.get(triggerSourceSerial);
				if (cores != null) {
					cores.remove(serial);
					if (cores.isEmpty()) {
						data.triggerSourceToCores.remove(triggerSourceSerial);
					}
				}
				removed++;
				changedTriggerSources.add(triggerSourceSerial);
			}
		}

		if (!changedTriggerSources.isEmpty()) {
			markTopologyChanged(data, changedTriggerSources);
		}
		return removed;
	}

	/**
	 * 新增 triggerSource -> core 关联（不触发 setDirty）。
	 */
	static boolean addTriggerSourceCoreLinkWithoutDirty(
		LinkSavedData data,
		long triggerSourceSerial,
		long coreSerial
	) {
		if (triggerSourceSerial <= 0L || coreSerial <= 0L) {
			return false;
		}
		Set<Long> linkedCores = data.triggerSourceToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			return false;
		}
		linkTriggerSourceCoreInternal(data, triggerSourceSerial, coreSerial);
		return true;
	}

	/**
	 * 移除 triggerSource -> core 关联（不触发 setDirty）。
	 */
	static boolean removeTriggerSourceCoreLinkWithoutDirty(
		LinkSavedData data,
		long triggerSourceSerial,
		long coreSerial
	) {
		if (triggerSourceSerial <= 0L || coreSerial <= 0L) {
			return false;
		}
		return removeTriggerSourceCoreLinkInternal(data, triggerSourceSerial, coreSerial);
	}

	/**
	 * 建立 triggerSource 与 core 的双向索引关系。
	 */
	static void linkTriggerSourceCore(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		linkTriggerSourceCoreInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial));
	}

	/**
	 * 建立 triggerSource 与 core 的双向索引关系（不推进 revision / dirty）。
	 */
	private static void linkTriggerSourceCoreInternal(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		data.triggerSourceToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>()).add(coreSerial);
		data.coreToTriggerSources.computeIfAbsent(coreSerial, unused -> new HashSet<>()).add(triggerSourceSerial);
	}

	/**
	 * 将一次真实图拓扑变更统一落到 dirty 与 revision。
	 */
	private static void markTopologyChanged(LinkSavedData data, Set<Long> changedTriggerSources) {
		if (data == null || changedTriggerSources == null || changedTriggerSources.isEmpty()) {
			return;
		}
		data.bumpGraphRevision();
		for (Long triggerSourceSerial : changedTriggerSources) {
			if (triggerSourceSerial != null && triggerSourceSerial > 0L) {
				data.bumpTriggerSourceRevision(triggerSourceSerial);
			}
		}
		data.setDirty();
	}

	/**
	 * 规范化输入集合：仅保留正序号并去重。
	 */
	static Set<Long> normalizePositiveSerials(Set<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return Set.of();
		}
		Set<Long> normalized = new HashSet<>();
		for (Long serial : serials) {
			if (serial != null && serial > 0L) {
				normalized.add(serial);
			}
		}
		return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
	}
}
