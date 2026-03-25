package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

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
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desiredTickets =
			collectDesiredResidentTickets(server);
		if (!state.residentTickets.isEmpty()) {
			Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> currentSnapshot =
				Map.copyOf(state.residentTickets);
			for (Map.Entry<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> currentEntry :
				currentSnapshot.entrySet()) {
				CrossChunkDispatchService.ResidentTicketKey key = currentEntry.getKey();
				CrossChunkDispatchService.ResidentChunkKey currentChunk = currentEntry.getValue();
				CrossChunkDispatchService.ResidentChunkKey desiredChunk = desiredTickets.remove(key);
				if (desiredChunk != null && desiredChunk.equals(currentChunk)) {
					continue;
				}
				removeResidentTicket(server, key, currentChunk);
				state.residentTickets.remove(key);
				if (desiredChunk != null && addResidentTicket(server, key, desiredChunk)) {
					state.residentTickets.put(key, desiredChunk);
				}
			}
		}
		for (Map.Entry<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desiredEntry :
			desiredTickets.entrySet()) {
			if (addResidentTicket(server, desiredEntry.getKey(), desiredEntry.getValue())) {
				state.residentTickets.put(desiredEntry.getKey(), desiredEntry.getValue());
			}
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
	static Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> collectDesiredResidentTickets(
		MinecraftServer server
	) {
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return Map.of();
		}
		LinkSavedData linkSavedData = LinkSavedData.get(overworld);
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(overworld);
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desired =
			new java.util.HashMap<>();
		appendDesiredResidentTickets(
			desired,
			whitelistSavedData.residentSnapshot(LinkNodeSemantics.Role.SOURCE),
			LinkNodeSemantics.Role.SOURCE,
			linkSavedData
		);
		appendDesiredResidentTickets(
			desired,
			whitelistSavedData.residentSnapshot(LinkNodeSemantics.Role.TARGET),
			LinkNodeSemantics.Role.TARGET,
			linkSavedData
		);
		return desired;
	}

	/**
	 * 将 resident 条目追加到期望票据集合。
	 */
	static void appendDesiredResidentTickets(
		Map<CrossChunkDispatchService.ResidentTicketKey, CrossChunkDispatchService.ResidentChunkKey> desired,
		Map<LinkNodeType, Set<Long>> residentByType,
		LinkNodeSemantics.Role role,
		LinkSavedData linkSavedData
	) {
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
		state.forceLoadCountBySource.clear();
		state.forceLoadCountThisTick = 0;
		state.forceLoadWindowTick = Long.MIN_VALUE;
		state.pendingCursor = 0L;
		stateByServer.remove(server);
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
