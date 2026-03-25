package com.makomi.data;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 节点待退役状态 helper。
 * <p>
 * 负责待退役内存桶、实体 UUID 记忆、伤害丢弃标记与持久化镜像恢复。
 * </p>
 */
final class LinkNodeRetireStateSupport {
	private LinkNodeRetireStateSupport() {
	}

	/**
	 * 判断指定节点是否已在待退役队列中。
	 */
	static boolean isPending(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		LinkNodeRetireEvents.PendingKey key
	) {
		LinkNodeRetireEvents.PendingRetireState state = states.get(server);
		return state != null && state.pendingByKey.containsKey(key);
	}

	/**
	 * 新增或更新待退役记录，并同步到内存到期桶。
	 */
	static void upsertPending(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		LinkNodeRetireEvents.PendingEntry newEntry,
		long noDueTick
	) {
		LinkNodeRetireEvents.PendingRetireState state = getOrCreateState(states, server, noDueTick);
		LinkNodeRetireEvents.PendingEntry previous = state.pendingByKey.put(newEntry.key(), newEntry);
		if (previous != null) {
			removeFromBucket(state, previous.expireTick(), newEntry.key());
		}

		state.bucketByExpireTick.computeIfAbsent(newEntry.expireTick(), ignored -> new java.util.ArrayList<>()).add(newEntry.key());
		if (newEntry.expireTick() < state.nextDueTick) {
			state.nextDueTick = newEntry.expireTick();
		}
	}

	/**
	 * 取消指定待退役记录。
	 */
	static boolean cancelPending(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		LinkNodeRetireEvents.PendingKey key,
		long noDueTick
	) {
		LinkNodeRetireEvents.PendingRetireState state = states.get(server);
		if (state == null) {
			return false;
		}
		LinkNodeRetireEvents.PendingEntry removed = state.pendingByKey.remove(key);
		if (removed == null) {
			return false;
		}

		removeFromBucket(state, removed.expireTick(), key);
		if (state.pendingByKey.isEmpty()) {
			state.nextDueTick = noDueTick;
			return true;
		}
		if (removed.expireTick() == state.nextDueTick && !state.bucketByExpireTick.containsKey(state.nextDueTick)) {
			recalculateNextDueTick(state, noDueTick);
		}
		return true;
	}

	/**
	 * 从到期桶中移除单个键。
	 */
	static void removeFromBucket(
		LinkNodeRetireEvents.PendingRetireState state,
		long expireTick,
		LinkNodeRetireEvents.PendingKey key
	) {
		List<LinkNodeRetireEvents.PendingKey> bucket = state.bucketByExpireTick.get(expireTick);
		if (bucket == null) {
			return;
		}
		bucket.removeIf(existing -> existing.equals(key));
		if (bucket.isEmpty()) {
			state.bucketByExpireTick.remove(expireTick);
		}
	}

	/**
	 * 重新计算当前最近一次到期 tick。
	 */
	static void recalculateNextDueTick(LinkNodeRetireEvents.PendingRetireState state, long noDueTick) {
		long next = noDueTick;
		for (Long dueTick : state.bucketByExpireTick.keySet()) {
			if (dueTick < next) {
				next = dueTick;
			}
		}
		state.nextDueTick = next;
	}

	/**
	 * 记录实体 UUID 与待退役键的对应关系。
	 */
	static void rememberEntityKey(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		UUID entityId,
		LinkNodeRetireEvents.PendingKey key,
		long noDueTick
	) {
		getOrCreateState(states, server, noDueTick).rememberedEntityKeys.put(entityId, key);
	}

	/**
	 * 读取实体 UUID 对应的待退役键。
	 */
	static LinkNodeRetireEvents.PendingKey getRememberedEntityKey(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		UUID entityId
	) {
		LinkNodeRetireEvents.PendingRetireState state = states.get(server);
		if (state == null) {
			return null;
		}
		return state.rememberedEntityKeys.get(entityId);
	}

	/**
	 * 清理实体 UUID 对应的待退役键缓存。
	 */
	static void clearEntityKey(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		UUID entityId
	) {
		LinkNodeRetireEvents.PendingRetireState state = states.get(server);
		if (state == null) {
			return;
		}
		state.rememberedEntityKeys.remove(entityId);
	}

	/**
	 * 记录“伤害触发 DISCARDED”标记。
	 */
	static void markDamageDiscard(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		UUID entityId,
		long noDueTick
	) {
		getOrCreateState(states, server, noDueTick).damageDiscardedEntityIds.add(entityId);
	}

	/**
	 * 读取并移除“伤害触发 DISCARDED”标记。
	 */
	static boolean consumeDamageDiscardMark(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		UUID entityId
	) {
		LinkNodeRetireEvents.PendingRetireState state = states.get(server);
		if (state == null) {
			return false;
		}
		return state.damageDiscardedEntityIds.remove(entityId);
	}

	/**
	 * 清理“伤害触发 DISCARDED”标记。
	 */
	static void clearDamageDiscardMark(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		UUID entityId
	) {
		LinkNodeRetireEvents.PendingRetireState state = states.get(server);
		if (state == null) {
			return;
		}
		state.damageDiscardedEntityIds.remove(entityId);
	}

	/**
	 * 获取或创建该服务器的待退役状态。
	 */
	static LinkNodeRetireEvents.PendingRetireState getOrCreateState(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		long noDueTick
	) {
		return states.computeIfAbsent(server, ignored -> new LinkNodeRetireEvents.PendingRetireState(noDueTick));
	}

	/**
	 * 同步单条待退役记录到持久化镜像。
	 */
	static void upsertPendingMirror(ServerLevel level, LinkNodeRetireEvents.PendingEntry entry) {
		PendingRetireQueueSavedData pendingMirror = PendingRetireQueueSavedData.get(level);
		pendingMirror.upsert(entry.key().nodeType(), entry.key().serial(), entry.dimension(), entry.pos(), entry.expireTick());
	}

	/**
	 * 从持久化镜像中移除单条待退役记录。
	 */
	static void removePendingMirror(MinecraftServer server, LinkNodeRetireEvents.PendingKey key) {
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		removePendingMirror(PendingRetireQueueSavedData.get(overworld), key);
	}

	/**
	 * 从已获取的镜像实例中移除单条待退役记录。
	 */
	static void removePendingMirror(PendingRetireQueueSavedData pendingMirror, LinkNodeRetireEvents.PendingKey key) {
		pendingMirror.remove(key.nodeType(), key.serial());
	}

	/**
	 * 从持久化镜像恢复待退役内存态。
	 */
	static void restorePendingRetires(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		long noDueTick
	) {
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		PendingRetireQueueSavedData pendingMirror = PendingRetireQueueSavedData.get(overworld);
		List<PendingRetireQueueSavedData.PendingRetireEntry> entries = pendingMirror.entriesSnapshot();
		if (entries.isEmpty()) {
			return;
		}

		LinkNodeRetireEvents.PendingRetireState state = getOrCreateState(states, server, noDueTick);
		state.pendingByKey.clear();
		state.bucketByExpireTick.clear();
		state.rememberedEntityKeys.clear();
		state.damageDiscardedEntityIds.clear();
		state.nextDueTick = noDueTick;

		for (PendingRetireQueueSavedData.PendingRetireEntry entry : entries) {
			LinkNodeRetireEvents.PendingKey key = new LinkNodeRetireEvents.PendingKey(entry.nodeType(), entry.serial());
			LinkNodeRetireEvents.PendingEntry restoredEntry = new LinkNodeRetireEvents.PendingEntry(
				key,
				entry.dimension(),
				entry.pos(),
				entry.expireTick()
			);
			upsertPending(states, server, restoredEntry, noDueTick);
		}
	}
}
