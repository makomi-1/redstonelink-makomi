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
		if (isRepeaterSelfLink(data, triggerSourceSerial, coreSerial)) {
			boolean removed = removeTriggerSourceCoreLinkInternal(data, triggerSourceSerial, coreSerial);
			if (removed) {
				markTopologyChanged(data, Set.of(triggerSourceSerial), Set.of(coreSerial));
			}
			return false;
		}

		Set<Long> linkedCores = data.triggerSourceToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			if (removeTriggerSourceCoreLinkInternal(data, triggerSourceSerial, coreSerial)) {
				markTopologyChanged(data, Set.of(triggerSourceSerial), Set.of(coreSerial));
			}
			return false;
		}

		linkTriggerSourceCoreInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial), Set.of(coreSerial));
		return true;
	}

	/**
	 * 新增一条 triggerSource -> core 关联关系。
	 */
	static boolean addTriggerSourceCoreLink(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		if (triggerSourceSerial <= 0L || coreSerial <= 0L) {
			return false;
		}
		if (isRepeaterSelfLink(data, triggerSourceSerial, coreSerial)) {
			return false;
		}
		Set<Long> linkedCores = data.triggerSourceToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			return false;
		}
		linkTriggerSourceCoreInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial), Set.of(coreSerial));
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
			markTopologyChanged(data, Set.of(triggerSourceSerial), Set.of(coreSerial));
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
		Set<Long> normalizedTargets = filterRepeaterSelfTargets(data, triggerSourceSerial, normalizePositiveSerials(coreSerials));
		IncrementalReplacePlanUtil.SetReplacePlan<Long> plan = IncrementalReplacePlanUtil.buildSetReplacePlan(
			currentTargets,
			normalizedTargets
		);
		if (!plan.changed()) {
			return new LinkSavedData.ReplaceLinksResult(currentTargets.size(), 0, 0, 0);
		}

		Set<Long> changedCores = new HashSet<>(plan.toRemove());
		changedCores.addAll(plan.toAdd());
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
			markTopologyChanged(data, Set.of(triggerSourceSerial), changedCores);
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
		return copyVisibleLinkedPeers(data, LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial, linked);
	}

	/**
	 * 查询 core 被哪些 triggerSource 关联。
	 */
	static Set<Long> getLinkedTriggerSourcesByCore(LinkSavedData data, long coreSerial) {
		Set<Long> linked = data.coreToTriggerSources.get(coreSerial);
		if (linked == null || linked.isEmpty()) {
			return Collections.emptySet();
		}
		return copyVisibleLinkedPeers(data, LinkNodeType.CORE, coreSerial, linked);
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
			if (
				peerSerial != null &&
				peerSerial > 0L &&
				!isRepeaterSelfLink(
					data,
					nodeType == LinkNodeType.TRIGGER_SOURCE ? serial : peerSerial,
					nodeType == LinkNodeType.TRIGGER_SOURCE ? peerSerial : serial
				)
			) {
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
		Set<Long> changedCores = new HashSet<>();
		if (type == LinkNodeType.TRIGGER_SOURCE) {
			Set<Long> cores = data.triggerSourceToCores.remove(serial);
			if (cores == null || cores.isEmpty()) {
				return 0;
			}

			for (long coreSerial : cores) {
				changedCores.add(coreSerial);
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

			changedCores.add(serial);
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

		if (!changedTriggerSources.isEmpty() || !changedCores.isEmpty()) {
			markTopologyChanged(data, changedTriggerSources, changedCores);
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
		if (isRepeaterSelfLink(data, triggerSourceSerial, coreSerial)) {
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
		if (isRepeaterSelfLink(data, triggerSourceSerial, coreSerial)) {
			return;
		}
		linkTriggerSourceCoreInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial), Set.of(coreSerial));
	}

	/**
	 * 建立 triggerSource 与 core 的双向索引关系（不推进 revision / dirty）。
	 */
	private static void linkTriggerSourceCoreInternal(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		if (isRepeaterSelfLink(data, triggerSourceSerial, coreSerial)) {
			return;
		}
		data.triggerSourceToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>()).add(coreSerial);
		data.coreToTriggerSources.computeIfAbsent(coreSerial, unused -> new HashSet<>()).add(triggerSourceSerial);
	}

	/**
	 * 将一次真实图拓扑变更统一落到 dirty 与 revision。
	 */
	private static void markTopologyChanged(LinkSavedData data, Set<Long> changedTriggerSources, Set<Long> changedCores) {
		if (data == null) {
			return;
		}
		boolean hasChangedTriggerSources = changedTriggerSources != null && !changedTriggerSources.isEmpty();
		boolean hasChangedCores = changedCores != null && !changedCores.isEmpty();
		if (!hasChangedTriggerSources && !hasChangedCores) {
			return;
		}
		data.bumpGraphRevision();
		if (hasChangedTriggerSources) {
			for (Long triggerSourceSerial : changedTriggerSources) {
				if (triggerSourceSerial != null && triggerSourceSerial > 0L) {
					data.bumpTriggerSourceRevision(triggerSourceSerial);
				}
			}
		}
		if (hasChangedCores) {
			for (Long coreSerial : changedCores) {
				if (coreSerial != null && coreSerial > 0L) {
					data.bumpCoreRevision(coreSerial);
				}
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

	/**
	 * 判断一条边是否为转发器非法自连。
	 */
	static boolean isRepeaterSelfLink(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		return data != null
			&& triggerSourceSerial > 0L
			&& triggerSourceSerial == coreSerial
			&& data.isRepeaterSerial(triggerSourceSerial);
	}

	/**
	 * 过滤目标集合中的转发器非法自连。
	 */
	private static Set<Long> filterRepeaterSelfTargets(LinkSavedData data, long triggerSourceSerial, Set<Long> coreSerials) {
		if (data == null || triggerSourceSerial <= 0L || coreSerials == null || coreSerials.isEmpty()) {
			return coreSerials == null ? Set.of() : coreSerials;
		}
		if (!data.isRepeaterSerial(triggerSourceSerial) || !coreSerials.contains(triggerSourceSerial)) {
			return coreSerials;
		}
		Set<Long> filteredTargets = new HashSet<>(coreSerials);
		filteredTargets.remove(triggerSourceSerial);
		return filteredTargets.isEmpty() ? Set.of() : Set.copyOf(filteredTargets);
	}

	/**
	 * 构造对外可见的稳定链接快照，自动隐藏转发器非法自连。
	 */
	private static Set<Long> copyVisibleLinkedPeers(
		LinkSavedData data,
		LinkNodeType nodeType,
		long serial,
		Set<Long> rawLinkedPeers
	) {
		if (data == null || nodeType == null || serial <= 0L || rawLinkedPeers == null || rawLinkedPeers.isEmpty()) {
			return Collections.emptySet();
		}
		boolean containsIllegalSelfLink = rawLinkedPeers.contains(serial) && data.isRepeaterSerial(serial);
		if (!containsIllegalSelfLink) {
			return Set.copyOf(rawLinkedPeers);
		}
		Set<Long> visibleLinkedPeers = new HashSet<>();
		for (Long peerSerial : rawLinkedPeers) {
			if (
				peerSerial != null &&
				peerSerial > 0L &&
				!isRepeaterSelfLink(
					data,
					nodeType == LinkNodeType.TRIGGER_SOURCE ? serial : peerSerial,
					nodeType == LinkNodeType.TRIGGER_SOURCE ? peerSerial : serial
				)
			) {
				visibleLinkedPeers.add(peerSerial);
			}
		}
		return visibleLinkedPeers.isEmpty() ? Collections.emptySet() : Set.copyOf(visibleLinkedPeers);
	}
}
