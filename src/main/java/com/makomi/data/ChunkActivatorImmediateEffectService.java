package com.makomi.data;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 区块激活器节点集新增后的即时副作用服务。
 * <p>
 * 该服务只处理“本次新进入当前生效集合”的成员：
 * </p>
 * <ul>
 * <li>`resident`：立即补 resident 常驻票；</li>
 * <li>`force_load + core`：立即按真实 `triggerSource -> core` 补发一次 sync，使其进入标准跨区块队列；</li>
 * <li>`force_load + triggerSource`：立即按当前/持久化真值补发一次 sync。</li>
 * </ul>
 */
public final class ChunkActivatorImmediateEffectService {
	private ChunkActivatorImmediateEffectService() {
	}

	/**
	 * 在区块激活器真值完成 upsert 后，对新增成员执行即时副作用。
	 */
	public static void applyAfterUpsert(
		ServerLevel contextLevel,
		PlacedChunkActivatorSavedData.ActivatorEntry previousEntry,
		PlacedChunkActivatorSavedData.ActivatorEntry nextEntry
	) {
		if (contextLevel == null || nextEntry == null) {
			return;
		}
		ImmediateEffectPlan plan = buildPlan(previousEntry, nextEntry);
		if (plan.isEmpty()) {
			return;
		}

		for (Long serial : plan.residentTriggerSources()) {
			bootstrapResidentTicket(contextLevel, LinkNodeType.TRIGGER_SOURCE, serial);
		}
		for (Long serial : plan.residentCores()) {
			bootstrapResidentTicket(contextLevel, LinkNodeType.CORE, serial);
		}
		for (Long serial : plan.replayCores()) {
			replayCoreCurrentTruth(contextLevel, serial);
		}
		for (Long serial : plan.replayTriggerSources()) {
			replayTriggerSourceCurrentTruth(contextLevel, serial);
		}
	}

	/**
	 * 计算本次 activator 变更真正新增进入当前生效集合的成员。
	 */
	static ImmediateEffectPlan buildPlan(
		PlacedChunkActivatorSavedData.ActivatorEntry previousEntry,
		PlacedChunkActivatorSavedData.ActivatorEntry nextEntry
	) {
		if (nextEntry == null || !nextEntry.active()) {
			return ImmediateEffectPlan.empty();
		}
		ChunkActivatorConfigStateSnapshot nextSnapshot = nextEntry.configStateSnapshot();
		LinkNodeType nextActiveType = nextSnapshot.activeType();
		Set<Long> nextSerials = nextEntry.serialsFor(nextActiveType);
		if (nextSerials.isEmpty()) {
			return ImmediateEffectPlan.empty();
		}

		Set<Long> previousSameTypeSerials = previousEntry != null
			&& previousEntry.active()
			&& previousEntry.configStateSnapshot().activeType() == nextActiveType
			? previousEntry.serialsFor(nextActiveType)
			: Set.of();
		Set<Long> newlyAddedActiveSerials = difference(nextSerials, previousSameTypeSerials);
		if (newlyAddedActiveSerials.isEmpty()) {
			return ImmediateEffectPlan.empty();
		}

		Set<Long> residentTriggerSources = Set.of();
		Set<Long> residentCores = Set.of();
		if (nextSnapshot.activeConfig().mode().contributesResident()) {
			if (nextActiveType == LinkNodeType.TRIGGER_SOURCE) {
				residentTriggerSources = newlyAddedActiveSerials;
			} else {
				residentCores = newlyAddedActiveSerials;
			}
		}

		Set<Long> replayCores = nextActiveType == LinkNodeType.CORE && !nextSnapshot.activeConfig().mode().contributesResident()
			? newlyAddedActiveSerials
			: Set.of();
		Set<Long> replayTriggerSources =
			nextActiveType == LinkNodeType.TRIGGER_SOURCE && !nextSnapshot.activeConfig().mode().contributesResident()
				? newlyAddedActiveSerials
				: Set.of();
		return new ImmediateEffectPlan(residentTriggerSources, residentCores, replayCores, replayTriggerSources);
	}

	private static void bootstrapResidentTicket(ServerLevel contextLevel, LinkNodeType type, long serial) {
		if (contextLevel == null || type == null || serial <= 0L) {
			return;
		}
		LinkSavedData.LinkNode node = LinkSavedData.get(contextLevel).findNode(type, serial).orElse(null);
		if (node == null) {
			return;
		}
		MinecraftServer server = contextLevel.getServer();
		CrossChunkDispatchService.DispatchState state = CrossChunkDispatchService.getOrCreateState(server);
		CrossChunkDispatchService.ResidentTicketKey ticketKey =
			new CrossChunkDispatchService.ResidentTicketKey(resolveRole(type), type, serial);
		if (state.residentTickets.containsKey(ticketKey)) {
			return;
		}
		CrossChunkDispatchService.ResidentChunkKey chunkKey = new CrossChunkDispatchService.ResidentChunkKey(
			node.dimension(),
			node.pos().getX() >> 4,
			node.pos().getZ() >> 4
		);
		if (!CrossChunkDispatchTicketSupport.addResidentTicket(server, ticketKey, chunkKey)) {
			return;
		}
		state.residentTickets.put(ticketKey, chunkKey);
		state.residentSyncArmed = true;
	}

	private static void replayCoreCurrentTruth(ServerLevel contextLevel, long coreSerial) {
		if (contextLevel == null || coreSerial <= 0L) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(contextLevel);
		Set<Long> linkedTriggerSources = savedData.getLinkedPeersByNodeType(LinkNodeType.CORE, coreSerial);
		if (linkedTriggerSources.isEmpty()) {
			return;
		}
		for (Long triggerSourceSerial : linkedTriggerSources) {
			if (triggerSourceSerial == null || triggerSourceSerial <= 0L) {
				continue;
			}
			InternalDispatchDeltaRuleSupport.publishTriggerSourceCurrentOrPersistedSyncReplay(
				contextLevel,
				triggerSourceSerial,
				Set.of(coreSerial),
				InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH
			);
		}
	}

	private static void replayTriggerSourceCurrentTruth(ServerLevel contextLevel, long triggerSourceSerial) {
		if (contextLevel == null || triggerSourceSerial <= 0L) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(contextLevel);
		Set<Long> linkedCores = savedData.getLinkedPeersByNodeType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);
		if (linkedCores.isEmpty()) {
			return;
		}
		InternalDispatchDeltaRuleSupport.publishTriggerSourceCurrentOrPersistedSyncReplay(
			contextLevel,
			triggerSourceSerial,
			linkedCores,
			InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH
		);
	}

	private static Set<Long> difference(Set<Long> left, Set<Long> right) {
		if (left == null || left.isEmpty()) {
			return Set.of();
		}
		LinkedHashSet<Long> result = new LinkedHashSet<>();
		Set<Long> normalizedRight = right == null ? Set.of() : right;
		for (Long serial : left) {
			if (serial == null || serial <= 0L || normalizedRight.contains(serial)) {
				continue;
			}
			result.add(serial);
		}
		return result.isEmpty() ? Set.of() : Set.copyOf(result);
	}

	private static LinkNodeSemantics.Role resolveRole(LinkNodeType type) {
		return type == LinkNodeType.CORE ? LinkNodeSemantics.Role.TARGET : LinkNodeSemantics.Role.SOURCE;
	}

	/**
	 * 即时副作用执行计划。
	 */
	record ImmediateEffectPlan(
		Set<Long> residentTriggerSources,
		Set<Long> residentCores,
		Set<Long> replayCores,
		Set<Long> replayTriggerSources
	) {
		private static ImmediateEffectPlan empty() {
			return new ImmediateEffectPlan(Set.of(), Set.of(), Set.of(), Set.of());
		}

		ImmediateEffectPlan {
			residentTriggerSources = residentTriggerSources == null ? Set.of() : Set.copyOf(residentTriggerSources);
			residentCores = residentCores == null ? Set.of() : Set.copyOf(residentCores);
			replayCores = replayCores == null ? Set.of() : Set.copyOf(replayCores);
			replayTriggerSources = replayTriggerSources == null ? Set.of() : Set.copyOf(replayTriggerSources);
		}

		private boolean isEmpty() {
			return residentTriggerSources.isEmpty()
				&& residentCores.isEmpty()
				&& replayCores.isEmpty()
				&& replayTriggerSources.isEmpty();
		}
	}
}
