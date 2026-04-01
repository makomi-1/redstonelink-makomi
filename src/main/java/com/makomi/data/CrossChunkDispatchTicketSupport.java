package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 跨区块强加载与常驻票据 helper。
 * <p>
 * 负责强加载命中判定、瞬时票据窗口、resident 票据同步与停服释放。
 * </p>
 */
final class CrossChunkDispatchTicketSupport {
	private CrossChunkDispatchTicketSupport() {
	}

	/**
	 * 判定 pending 是否允许进入强制加载链路。
	 */
	static boolean shouldForceLoad(
		ServerLevel contextLevel,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending
	) {
		if (contextLevel == null || pending == null) {
			return false;
		}
		if (!RedstoneLinkConfig.crossChunk().forceLoadEnabled()) {
			return false;
		}
		if (!RedstoneLinkConfig.crossChunk().allowedSourceTypes().contains(pending.key().sourceType())) {
			return false;
		}
		if (!RedstoneLinkConfig.crossChunk().allowedTargetTypes().contains(pending.key().targetType())) {
			return false;
		}

		RedstoneLinkConfig.CrossChunkForceLoadMode mode = RedstoneLinkConfig.crossChunk().forceLoadMode();
		if (mode == RedstoneLinkConfig.CrossChunkForceLoadMode.ALL) {
			return true;
		}
		return matchWhitelistOrPreset(contextLevel, pending);
	}

	/**
	 * 校验是否命中运行态白名单或 preset。
	 */
	static boolean matchWhitelistOrPreset(
		ServerLevel contextLevel,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending
	) {
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(contextLevel);
		boolean whitelistMatched = whitelistSavedData.contains(
			pending.key().sourceType(),
			pending.key().sourceSerial(),
			LinkNodeSemantics.Role.SOURCE
		) || whitelistSavedData.contains(
			pending.key().targetType(),
			pending.key().targetSerial(),
			LinkNodeSemantics.Role.TARGET
		);
		if (whitelistMatched) {
			return true;
		}

		boolean presetSourceMatched = RedstoneLinkConfig.crossChunk().presetContains(
			pending.key().sourceType(),
			pending.key().sourceSerial(),
			LinkNodeSemantics.Role.SOURCE
		);
		if (presetSourceMatched) {
			return true;
		}
		return RedstoneLinkConfig.crossChunk().presetContains(
			pending.key().targetType(),
			pending.key().targetSerial(),
			LinkNodeSemantics.Role.TARGET
		);
	}

	/**
	 * 同步 resident 白名单对应的常驻区块票据。
	 */
	static void syncResidentTickets(MinecraftServer server, CrossChunkDispatchService.DispatchState state) {
		if (server == null || state == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		LinkSavedData linkSavedData = LinkSavedData.get(overworld);
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(overworld);
		long residentWhitelistVersion = whitelistSavedData.residentStateVersion();
		long runtimeNodeVersion = linkSavedData.runtimeNodeVersion();
		state.residentSyncArmed = whitelistSavedData.hasResidents();
		if (
			state.residentWhitelistVersion == residentWhitelistVersion
				&& state.residentRuntimeNodeVersion == runtimeNodeVersion
		) {
			return;
		}

		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desiredTickets =
			state.residentDesiredTicketsScratch;
		try {
			collectDesiredResidentTickets(overworld, linkSavedData, whitelistSavedData, desiredTickets);
			syncResidentTicketDiff(server, state, desiredTickets);
			state.residentWhitelistVersion = residentWhitelistVersion;
			state.residentRuntimeNodeVersion = runtimeNodeVersion;
		} finally {
			desiredTickets.clear();
		}
	}

	/**
	 * 释放当前服务端所有 resident 票据。
	 */
	static void releaseResidentTickets(MinecraftServer server, CrossChunkDispatchService.DispatchState state) {
		if (state.residentTickets.isEmpty()) {
			return;
		}
		for (Map.Entry<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> entry :
			state.residentTickets.entrySet()) {
			removeResidentTicket(server, entry.getKey(), entry.getValue());
		}
	}

	/**
	 * 汇总当前 resident 白名单期望持有的区块票据映射。
	 */
	static void collectDesiredResidentTickets(
		ServerLevel contextLevel,
		LinkSavedData linkSavedData,
		CrossChunkWhitelistSavedData whitelistSavedData,
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desired
	) {
		if (desired == null) {
			return;
		}
		desired.clear();
		if (contextLevel == null || linkSavedData == null || whitelistSavedData == null) {
			return;
		}
		whitelistSavedData.forEachResidentSerial(
			LinkNodeSemantics.Role.SOURCE,
			(type, serial) -> appendDesiredResidentTicket(
				desired,
				type,
				serial,
				LinkNodeSemantics.Role.SOURCE,
				contextLevel,
				linkSavedData
			)
		);
		whitelistSavedData.forEachResidentSerial(
			LinkNodeSemantics.Role.TARGET,
			(type, serial) -> appendDesiredResidentTicket(
				desired,
				type,
				serial,
				LinkNodeSemantics.Role.TARGET,
				contextLevel,
				linkSavedData
			)
		);
	}

	/**
	 * 将 resident 条目追加到期望票据集合。
	 */
	static void appendDesiredResidentTickets(
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desired,
		Map<LinkNodeType, Set<Long>> residentByType,
		LinkNodeSemantics.Role role,
		ServerLevel contextLevel,
		LinkSavedData linkSavedData
	) {
		if (desired == null || residentByType == null || role == null || contextLevel == null || linkSavedData == null) {
			return;
		}
		for (Map.Entry<LinkNodeType, Set<Long>> entry : residentByType.entrySet()) {
			LinkNodeType type = entry.getKey();
			if (!LinkNodeSemantics.isAllowedForRole(type, role)) {
				continue;
			}
			for (Long serial : entry.getValue()) {
				if (serial != null) {
					appendDesiredResidentTicket(desired, type, serial, role, contextLevel, linkSavedData);
				}
			}
		}
	}

	/**
	 * 将单条 resident 序号追加到期望票据集合。
	 */
	private static void appendDesiredResidentTicket(
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desired,
		LinkNodeType type,
		long serial,
		LinkNodeSemantics.Role role,
		ServerLevel contextLevel,
		LinkSavedData linkSavedData
	) {
		if (
			desired == null
				|| type == null
				|| serial <= 0L
				|| role == null
				|| contextLevel == null
				|| linkSavedData == null
				|| !LinkNodeSemantics.isAllowedForRole(type, role)
		) {
			return;
		}
		LinkSavedData.RuntimeOnlineProbeResult probeResult = linkSavedData.probeRuntimeOnlineNodeNonBlocking(
			contextLevel,
			type,
			serial
		);
		LinkSavedData.LinkNode node = probeResult.node();
		if (!probeResult.ready() || node == null) {
			return;
		}
		int chunkX = node.pos().getX() >> 4;
		int chunkZ = node.pos().getZ() >> 4;
		CrossChunkDispatchService.ResidentTicketKey ticketKey =
			new CrossChunkDispatchService.ResidentTicketKey(role, type, serial);
		desired.put(ticketKey, new CrossChunkDispatchService.ResidentChunkKey(node.dimension(), chunkX, chunkZ));
	}

	/**
	 * 兼容旧测试入口：仅按已登记节点位置汇总 resident 票据。
	 * <p>
	 * 生产路径请优先使用带 `contextLevel` 的重载，以运行态在线语义过滤未加载节点。
	 * </p>
	 */
	static void appendDesiredResidentTickets(
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desired,
		Map<LinkNodeType, Set<Long>> residentByType,
		LinkNodeSemantics.Role role,
		LinkSavedData linkSavedData
	) {
		if (desired == null || residentByType == null || role == null || linkSavedData == null) {
			return;
		}
		for (Map.Entry<LinkNodeType, Set<Long>> entry : residentByType.entrySet()) {
			LinkNodeType type = entry.getKey();
			if (!LinkNodeSemantics.isAllowedForRole(type, role)) {
				continue;
			}
			for (Long serial : entry.getValue()) {
				if (serial == null || serial <= 0L) {
					continue;
				}
				LinkSavedData.LinkNode node = linkSavedData.findNode(type, serial).orElse(null);
				if (node == null) {
					continue;
				}
				int chunkX = node.pos().getX() >> 4;
				int chunkZ = node.pos().getZ() >> 4;
				CrossChunkDispatchService.ResidentTicketKey ticketKey =
					new CrossChunkDispatchService.ResidentTicketKey(role, type, serial);
				desired.put(ticketKey, new CrossChunkDispatchService.ResidentChunkKey(node.dimension(), chunkX, chunkZ));
			}
		}
	}

	/**
	 * 尝试为 pending 拉起目标区块。
	 */
	static void tryForceLoad(
		MinecraftServer server,
		CrossChunkDispatchService.DispatchState state,
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pending,
		long gameTime
	) {
		resetForceLoadWindow(state, gameTime);

		int maxPerTick = RedstoneLinkConfig.crossChunk().forceLoadMaxPerTick();
		if (state.forceLoadCountThisTick >= maxPerTick) {
			return;
		}
		CrossChunkDispatchService.SourceKey sourceKey =
			new CrossChunkDispatchService.SourceKey(pending.key().sourceType(), pending.key().sourceSerial());
		int sourceUsed = state.forceLoadCountBySource.getOrDefault(sourceKey, 0);
		if (sourceUsed >= RedstoneLinkConfig.crossChunk().forceLoadMaxPerSourcePerTick()) {
			return;
		}

		ServerLevel targetLevel = server.getLevel(pending.dimension());
		if (targetLevel == null) {
			return;
		}

		int chunkX = pending.pos().getX() >> 4;
		int chunkZ = pending.pos().getZ() >> 4;
		addTransientTicket(targetLevel, chunkX, chunkZ);

		CrossChunkDispatchService.ForcedChunkKey forcedChunkKey =
			new CrossChunkDispatchService.ForcedChunkKey(targetLevel.dimension(), chunkX, chunkZ);
		long expireTick = gameTime + Math.max(1L, RedstoneLinkConfig.crossChunk().forceLoadTicketTicks());
		long previousExpireTick = state.forcedChunksUntilTick.getOrDefault(forcedChunkKey, Long.MIN_VALUE);
		state.forcedChunksUntilTick.put(forcedChunkKey, Math.max(previousExpireTick, expireTick));

		state.forceLoadCountThisTick++;
		state.forceLoadCountBySource.put(sourceKey, sourceUsed + 1);
	}

	/**
	 * 释放已到期的瞬时强加载票据。
	 */
	static void releaseExpiredForcedChunks(
		MinecraftServer server,
		CrossChunkDispatchService.DispatchState state,
		long gameTime
	) {
		Iterator<Map.Entry<CrossChunkDispatchService.ForcedChunkKey, Long>> iterator =
			state.forcedChunksUntilTick.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<CrossChunkDispatchService.ForcedChunkKey, Long> entry = iterator.next();
			if (entry.getValue() > gameTime) {
				continue;
			}
			CrossChunkDispatchService.ForcedChunkKey key = entry.getKey();
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				removeTransientTicket(level, key.chunkX(), key.chunkZ());
			}
			iterator.remove();
		}
	}

	/**
	 * 停服前释放全部强加载与 resident 票据，并清空状态。
	 */
	static void releaseAllForcedChunksAndClearState(
		MinecraftServer server,
		CrossChunkDispatchService.DispatchState state,
		Map<MinecraftServer, CrossChunkDispatchService.DispatchState> stateByServer
	) {
		if (state == null) {
			return;
		}
		for (CrossChunkDispatchService.ForcedChunkKey key : state.forcedChunksUntilTick.keySet()) {
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				removeTransientTicket(level, key.chunkX(), key.chunkZ());
			}
		}
		releaseResidentTickets(server, state);
		state.forcedChunksUntilTick.clear();
		state.residentTickets.clear();
		state.residentDesiredTicketsScratch.clear();
		state.forceLoadCountBySource.clear();
		state.residentSyncArmed = false;
		state.residentWhitelistVersion = Long.MIN_VALUE;
		state.residentRuntimeNodeVersion = Long.MIN_VALUE;
		state.forceLoadCountThisTick = 0;
		state.forceLoadWindowTick = Long.MIN_VALUE;
		state.pendingCursor = 0L;
		stateByServer.remove(server);
	}

	/**
	 * 原地比对 resident 当前票据与本轮期望票据。
	 */
	private static void syncResidentTicketDiff(
		MinecraftServer server,
		CrossChunkDispatchService.DispatchState state,
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desiredTickets
	) {
		Iterator<Map.Entry<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey>> iterator =
			state.residentTickets.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> currentEntry =
				iterator.next();
			CrossChunkDispatchService.ResidentTicketKey key = currentEntry.getKey();
			CrossChunkDispatchService.ResidentChunkKey currentChunk = currentEntry.getValue();
			CrossChunkDispatchService.ResidentChunkKey desiredChunk = desiredTickets.remove(key);
			if (desiredChunk != null && desiredChunk.equals(currentChunk)) {
				continue;
			}
			removeResidentTicket(server, key, currentChunk);
			if (desiredChunk == null) {
				iterator.remove();
				continue;
			}
			if (addResidentTicket(server, key, desiredChunk)) {
				currentEntry.setValue(desiredChunk);
				continue;
			}
			iterator.remove();
		}
		for (Map.Entry<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desiredEntry :
			desiredTickets.entrySet()) {
			if (addResidentTicket(server, desiredEntry.getKey(), desiredEntry.getValue())) {
				state.residentTickets.put(desiredEntry.getKey(), desiredEntry.getValue());
			}
		}
	}

	/**
	 * 添加瞬时区块票据。
	 */
	static void addTransientTicket(ServerLevel level, int chunkX, int chunkZ) {
		ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
		level.getChunkSource().addRegionTicket(
			CrossChunkDispatchService.TRANSIENT_TICKET_TYPE,
			chunkPos,
			CrossChunkDispatchService.TRANSIENT_TICKET_LEVEL,
			chunkPos
		);
	}

	/**
	 * 释放瞬时区块票据。
	 */
	static void removeTransientTicket(ServerLevel level, int chunkX, int chunkZ) {
		ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
		level.getChunkSource().removeRegionTicket(
			CrossChunkDispatchService.TRANSIENT_TICKET_TYPE,
			chunkPos,
			CrossChunkDispatchService.TRANSIENT_TICKET_LEVEL,
			chunkPos
		);
	}

	/**
	 * 添加 resident 常驻票据。
	 */
	static boolean addResidentTicket(
		MinecraftServer server,
		CrossChunkDispatchService.ResidentTicketKey ticketKey,
		CrossChunkDispatchService.ResidentChunkKey chunkKey
	) {
		ServerLevel level = server.getLevel(chunkKey.dimension());
		if (level == null) {
			return false;
		}
		ChunkPos chunkPos = new ChunkPos(chunkKey.chunkX(), chunkKey.chunkZ());
		level.getChunkSource().addRegionTicket(
			CrossChunkDispatchService.RESIDENT_TICKET_TYPE,
			chunkPos,
			CrossChunkDispatchService.RESIDENT_TICKET_LEVEL,
			ticketKey
		);
		return true;
	}

	/**
	 * 释放 resident 常驻票据。
	 */
	static void removeResidentTicket(
		MinecraftServer server,
		CrossChunkDispatchService.ResidentTicketKey ticketKey,
		CrossChunkDispatchService.ResidentChunkKey chunkKey
	) {
		ServerLevel level = server.getLevel(chunkKey.dimension());
		if (level == null) {
			return;
		}
		ChunkPos chunkPos = new ChunkPos(chunkKey.chunkX(), chunkKey.chunkZ());
		level.getChunkSource().removeRegionTicket(
			CrossChunkDispatchService.RESIDENT_TICKET_TYPE,
			chunkPos,
			CrossChunkDispatchService.RESIDENT_TICKET_LEVEL,
			ticketKey
		);
	}

	/**
	 * 进入新 tick 时重置瞬时强加载窗口计数。
	 */
	static void resetForceLoadWindow(CrossChunkDispatchService.DispatchState state, long gameTime) {
		if (state.forceLoadWindowTick == gameTime) {
			return;
		}
		state.forceLoadWindowTick = gameTime;
		state.forceLoadCountThisTick = 0;
		state.forceLoadCountBySource.clear();
	}
}
