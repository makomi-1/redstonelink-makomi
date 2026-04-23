package com.makomi.network;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络侧 serial 活跃性校验支撑。
 * <p>
 * 统一提供“已分配且未退役”校验，避免多个编辑器保存链路各自散落重复逻辑。
 * </p>
 */
final class NetworkActiveSerialValidationSupport {
	private NetworkActiveSerialValidationSupport() {
	}

	/**
	 * 按节点类型批量收集无效 serial，并区分“未分配”与“已退役”两类错误。
	 */
	static ValidationResult collectInvalidSerials(LinkSavedData savedData, LinkNodeType nodeType, List<Long> serials) {
		if (savedData == null || nodeType == null || serials == null || serials.isEmpty()) {
			return ValidationResult.empty();
		}
		List<String> unallocatedSerials = new ArrayList<>();
		List<String> retiredSerials = new ArrayList<>();
		for (Long serialValue : serials) {
			long serial = serialValue == null ? 0L : serialValue;
			if (serial <= 0L) {
				continue;
			}
			if (!savedData.isSerialAllocated(nodeType, serial)) {
				unallocatedSerials.add(Long.toString(serial));
				continue;
			}
			if (savedData.isSerialRetired(nodeType, serial)) {
				retiredSerials.add(Long.toString(serial));
			}
		}
		return new ValidationResult(unallocatedSerials, retiredSerials);
	}

	/**
	 * serial 活跃性校验结果。
	 */
	record ValidationResult(List<String> unallocatedSerials, List<String> retiredSerials) {
		private static final ValidationResult EMPTY = new ValidationResult(List.of(), List.of());

		ValidationResult {
			unallocatedSerials = List.copyOf(unallocatedSerials == null ? List.of() : unallocatedSerials);
			retiredSerials = List.copyOf(retiredSerials == null ? List.of() : retiredSerials);
		}

		static ValidationResult empty() {
			return EMPTY;
		}

		boolean hasUnallocated() {
			return !unallocatedSerials.isEmpty();
		}

		boolean hasRetired() {
			return !retiredSerials.isEmpty();
		}
	}
}
