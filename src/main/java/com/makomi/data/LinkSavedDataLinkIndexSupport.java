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
	static boolean toggleLink(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		if (triggerSourceSerial <= 0L || coreSerial <= 0L) {
			return false;
		}

		Set<Long> linkedCores = data.buttonToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			if (unlinkInternal(data, triggerSourceSerial, coreSerial)) {
				markTopologyChanged(data, Set.of(triggerSourceSerial));
			}
			return false;
		}

		linkInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial));
		return true;
	}

	/**
	 * 以“来源/目标”语义切换关联关系。
	 */
	static boolean toggleLinkBySourceType(LinkSavedData data, LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		if (sourceType == null) {
			return false;
		}
		return sourceType == LinkNodeType.TRIGGER_SOURCE
			? toggleLink(data, sourceSerial, targetSerial)
			: toggleLink(data, targetSerial, sourceSerial);
	}

	/**
	 * 以“来源/目标”语义新增单条关联关系。
	 */
	static boolean addLinkBySourceType(LinkSavedData data, LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		if (sourceType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return false;
		}
		long triggerSourceSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? sourceSerial : targetSerial;
		long coreSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? targetSerial : sourceSerial;
		Set<Long> linkedCores = data.buttonToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			return false;
		}
		linkInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial));
		return true;
	}

	/**
	 * 以“来源/目标”语义移除单条关联关系。
	 */
	static boolean removeLinkBySourceType(LinkSavedData data, LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		if (sourceType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return false;
		}
		long triggerSourceSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? sourceSerial : targetSerial;
		long coreSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? targetSerial : sourceSerial;
		boolean removed = unlinkInternal(data, triggerSourceSerial, coreSerial);
		if (removed) {
			markTopologyChanged(data, Set.of(triggerSourceSerial));
		}
		return removed;
	}

	/**
	 * 以“覆盖集合”语义增量替换来源节点的目标集合。
	 */
	static LinkSavedData.ReplaceLinksResult replaceLinksBySourceType(
		LinkSavedData data,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> targetSerials
	) {
		if (sourceType == null || sourceSerial <= 0L) {
			return new LinkSavedData.ReplaceLinksResult(0, 0, 0, 0);
		}
		Set<Long> currentTargets = new HashSet<>(getLinkedTargetsBySourceType(data, sourceType, sourceSerial));
		Set<Long> normalizedTargets = normalizePositiveSerials(targetSerials);
		IncrementalReplacePlanUtil.SetReplacePlan<Long> plan = IncrementalReplacePlanUtil.buildSetReplacePlan(
			currentTargets,
			normalizedTargets
		);
		if (!plan.changed()) {
			return new LinkSavedData.ReplaceLinksResult(currentTargets.size(), 0, 0, 0);
		}

		int removed = 0;
		Set<Long> changedTriggerSources = new HashSet<>();
		for (long targetSerial : plan.toRemove()) {
			if (removeLinkWithoutDirtyBySourceType(data, sourceType, sourceSerial, targetSerial)) {
				removed++;
				changedTriggerSources.add(resolveTriggerSourceSerial(sourceType, sourceSerial, targetSerial));
			}
		}
		int added = 0;
		for (long targetSerial : plan.toAdd()) {
			if (addLinkWithoutDirtyBySourceType(data, sourceType, sourceSerial, targetSerial)) {
				added++;
				changedTriggerSources.add(resolveTriggerSourceSerial(sourceType, sourceSerial, targetSerial));
			}
		}

		if (!changedTriggerSources.isEmpty()) {
			markTopologyChanged(data, changedTriggerSources);
		}
		int currentCount = currentTargets.size() - removed + added;
		return new LinkSavedData.ReplaceLinksResult(currentCount, added, removed, added + removed);
	}

	/**
	 * 解除 triggerSource 与 core 的单条关联关系。
	 */
	static boolean unlink(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		boolean removed = unlinkInternal(data, triggerSourceSerial, coreSerial);
		if (removed) {
			markTopologyChanged(data, Set.of(triggerSourceSerial));
		}
		return removed;
	}

	/**
	 * 解除 triggerSource 与 core 的单条关联关系（不推进 revision / dirty）。
	 */
	private static boolean unlinkInternal(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		Set<Long> linkedCores = data.buttonToCores.get(triggerSourceSerial);
		if (linkedCores == null || !linkedCores.remove(coreSerial)) {
			return false;
		}

		if (linkedCores.isEmpty()) {
			data.buttonToCores.remove(triggerSourceSerial);
		}

		Set<Long> linkedTriggerSources = data.coreToButtons.get(coreSerial);
		if (linkedTriggerSources != null) {
			linkedTriggerSources.remove(triggerSourceSerial);
			if (linkedTriggerSources.isEmpty()) {
				data.coreToButtons.remove(coreSerial);
			}
		}
		return true;
	}

	/**
	 * 查询 triggerSource 关联的 core 序列号集合。
	 */
	static Set<Long> getLinkedCores(LinkSavedData data, long triggerSourceSerial) {
		Set<Long> linked = data.buttonToCores.get(triggerSourceSerial);
		if (linked == null || linked.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(linked);
	}

	/**
	 * 查询 core 被哪些 triggerSource 关联。
	 */
	static Set<Long> getLinkedTriggerSources(LinkSavedData data, long coreSerial) {
		Set<Long> linked = data.coreToButtons.get(coreSerial);
		if (linked == null || linked.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(linked);
	}

	/**
	 * 按“来源类型 + 来源序列号”查询目标集合。
	 */
	static Set<Long> getLinkedTargetsBySourceType(LinkSavedData data, LinkNodeType sourceType, long sourceSerial) {
		if (sourceType == null) {
			return Collections.emptySet();
		}
		return sourceType == LinkNodeType.TRIGGER_SOURCE
			? getLinkedCores(data, sourceSerial)
			: getLinkedTriggerSources(data, sourceSerial);
	}

	/**
	 * 按“来源类型 + 来源序列号”无拷贝遍历目标集合。
	 */
	static void forEachLinkedTargetBySourceType(
		LinkSavedData data,
		LinkNodeType sourceType,
		long sourceSerial,
		LongConsumer consumer
	) {
		if (sourceType == null || sourceSerial <= 0L || consumer == null) {
			return;
		}
		Set<Long> linkedTargets = linkedTargetsViewBySourceType(data, sourceType, sourceSerial);
		if (linkedTargets == null || linkedTargets.isEmpty()) {
			return;
		}
		for (Long targetSerial : linkedTargets) {
			if (targetSerial != null && targetSerial > 0L) {
				consumer.accept(targetSerial);
			}
		}
	}

	/**
	 * 返回内部目标集合视图（无拷贝）。
	 */
	private static Set<Long> linkedTargetsViewBySourceType(LinkSavedData data, LinkNodeType sourceType, long sourceSerial) {
		if (sourceType == null || sourceSerial <= 0L) {
			return Collections.emptySet();
		}
		Set<Long> linkedTargets = sourceType == LinkNodeType.TRIGGER_SOURCE
			? data.buttonToCores.get(sourceSerial)
			: data.coreToButtons.get(sourceSerial);
		if (linkedTargets == null || linkedTargets.isEmpty()) {
			return Collections.emptySet();
		}
		return linkedTargets;
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
			Set<Long> cores = data.buttonToCores.remove(serial);
			if (cores == null || cores.isEmpty()) {
				return 0;
			}

			for (long coreSerial : cores) {
				Set<Long> linkedTriggerSources = data.coreToButtons.get(coreSerial);
				if (linkedTriggerSources != null) {
					linkedTriggerSources.remove(serial);
					if (linkedTriggerSources.isEmpty()) {
						data.coreToButtons.remove(coreSerial);
					}
				}
				removed++;
			}
			changedTriggerSources.add(serial);
		} else {
			Set<Long> triggerSources = data.coreToButtons.remove(serial);
			if (triggerSources == null || triggerSources.isEmpty()) {
				return 0;
			}

			for (long triggerSourceSerial : triggerSources) {
				Set<Long> cores = data.buttonToCores.get(triggerSourceSerial);
				if (cores != null) {
					cores.remove(serial);
					if (cores.isEmpty()) {
						data.buttonToCores.remove(triggerSourceSerial);
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
	 * 以“来源/目标”语义新增关联（不触发 setDirty）。
	 */
	static boolean addLinkWithoutDirtyBySourceType(
		LinkSavedData data,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial
	) {
		if (sourceType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return false;
		}
		long triggerSourceSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? sourceSerial : targetSerial;
		long coreSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? targetSerial : sourceSerial;
		Set<Long> linkedCores = data.buttonToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>());
		if (linkedCores.contains(coreSerial)) {
			return false;
		}
		linkInternal(data, triggerSourceSerial, coreSerial);
		return true;
	}

	/**
	 * 以“来源/目标”语义移除关联（不触发 setDirty）。
	 */
	static boolean removeLinkWithoutDirtyBySourceType(
		LinkSavedData data,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial
	) {
		if (sourceType == null || sourceSerial <= 0L || targetSerial <= 0L) {
			return false;
		}
		long triggerSourceSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? sourceSerial : targetSerial;
		long coreSerial = sourceType == LinkNodeType.TRIGGER_SOURCE ? targetSerial : sourceSerial;
		return unlinkInternal(data, triggerSourceSerial, coreSerial);
	}

	/**
	 * 建立 triggerSource 与 core 的双向索引关系。
	 */
	static void link(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		linkInternal(data, triggerSourceSerial, coreSerial);
		markTopologyChanged(data, Set.of(triggerSourceSerial));
	}

	/**
	 * 建立 triggerSource 与 core 的双向索引关系（不推进 revision / dirty）。
	 */
	private static void linkInternal(LinkSavedData data, long triggerSourceSerial, long coreSerial) {
		data.buttonToCores.computeIfAbsent(triggerSourceSerial, unused -> new HashSet<>()).add(coreSerial);
		data.coreToButtons.computeIfAbsent(coreSerial, unused -> new HashSet<>()).add(triggerSourceSerial);
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
	 * 统一把“来源/目标”语义解析回真实的 triggerSource 序号。
	 */
	private static long resolveTriggerSourceSerial(LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		return sourceType == LinkNodeType.TRIGGER_SOURCE ? sourceSerial : targetSerial;
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
