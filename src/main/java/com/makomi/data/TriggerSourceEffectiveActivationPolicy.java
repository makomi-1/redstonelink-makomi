package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.server.level.ServerLevel;

/**
 * `triggerSource` 有效激活语义收口。
 * <p>
 * 负责两类判断：
 * </p>
 * <ul>
 * <li>`hard invalidation` 是否生效；</li>
 * <li>`target attach replay` 前，`sync` 来源是否满足“有效激活”口径。</li>
 * </ul>
 * <p>
 * 当前版本中，hard invalidation 固定开启，不再暴露为配置项。
 * </p>
 */
final class TriggerSourceEffectiveActivationPolicy {
	/**
	 * 是否始终要求过滤“硬下线”来源。
	 * <p>
	 * 该常量固定为真，用于表达“硬下线来源始终需要被过滤”这一内部语义。
	 * </p>
	 */
	private static final boolean REQUIRE_HARD_DOWN_FILTER = true;

	private TriggerSourceEffectiveActivationPolicy() {
	}

	/**
	 * `sync` 来源在 replay 前所需满足的有效激活口径。
	 */
	enum EffectiveActivationRequirement {
		/**
		 * 无约束：不校验来源生命周期状态。
		 */
		NONE,
		/**
		 * 非硬下线：来源仍保留节点登记，但允许软下线。
		 */
		NON_HARD_DOWN,
		/**
		 * 非下线：来源当前必须真正在线且就绪。
		 */
		NON_OFFLINE
	}

	/**
	 * `triggerSource` 的 hard invalidation 当前是否生效。
	 */
	static boolean hardDownFilteringEnabled() {
		return REQUIRE_HARD_DOWN_FILTER;
	}

	/**
	 * 解析当前 `sync` 来源 replay 应采用的“有效激活”口径。
	 */
	static EffectiveActivationRequirement resolveSyncReplayRequirement() {
		if (!REQUIRE_HARD_DOWN_FILTER) {
			return EffectiveActivationRequirement.NONE;
		}
		return RedstoneLinkConfig.crossChunk().triggerSourceContextDetachInvalidationEnabled()
			? EffectiveActivationRequirement.NON_OFFLINE
			: EffectiveActivationRequirement.NON_HARD_DOWN;
	}

	/**
	 * 按给定口径过滤一批 `sync` 来源。
	 * <p>
	 * 该入口只关注来源是否满足 replay 资格，不负责读取快照。
	 * </p>
	 */
	static Set<Long> filterReplayEligibleSyncSources(
		EffectiveActivationRequirement requirement,
		Set<Long> sourceSerials,
		LongPredicate sourceRegistered,
		LongPredicate sourceOnlineReady
	) {
		if (requirement == null || sourceSerials == null || sourceSerials.isEmpty()) {
			return Set.of();
		}
		if (sourceRegistered == null || sourceOnlineReady == null) {
			return Set.of();
		}
		LinkedHashSet<Long> eligible = new LinkedHashSet<>();
		for (Long sourceSerial : sourceSerials) {
			if (sourceSerial == null || sourceSerial <= 0L) {
				continue;
			}
			if (!isReplayEligible(requirement, sourceSerial, sourceRegistered, sourceOnlineReady)) {
				continue;
			}
			eligible.add(sourceSerial);
		}
		return eligible.isEmpty() ? Set.of() : Set.copyOf(eligible);
	}

	/**
	 * 基于当前运行态视图，筛出允许参与 `target attach replay` 的 `sync` 来源。
	 */
	static Set<Long> filterReplayEligibleSyncSourcesForTargetAttachReplay(
		ServerLevel contextLevel,
		LinkSavedData savedData,
		Set<Long> sourceSerials
	) {
		if (contextLevel == null || savedData == null || sourceSerials == null || sourceSerials.isEmpty()) {
			return Set.of();
		}
		return filterReplayEligibleSyncSources(
			resolveSyncReplayRequirement(),
			sourceSerials,
			sourceSerial -> savedData.findNode(LinkNodeType.TRIGGER_SOURCE, sourceSerial).isPresent(),
			sourceSerial -> savedData.probeRuntimeOnlineNodeNonBlocking(contextLevel, LinkNodeType.TRIGGER_SOURCE, sourceSerial).ready()
		);
	}

	/**
	 * 判断单个 `sync` 来源是否满足指定 replay 资格。
	 */
	private static boolean isReplayEligible(
		EffectiveActivationRequirement requirement,
		long sourceSerial,
		LongPredicate sourceRegistered,
		LongPredicate sourceOnlineReady
	) {
		return switch (requirement) {
			case NONE -> true;
			case NON_HARD_DOWN -> sourceRegistered.test(sourceSerial);
			case NON_OFFLINE -> sourceOnlineReady.test(sourceSerial);
		};
	}
}
