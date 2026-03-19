package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 跨区块派发调度服务。
 * <p>
 * 目标区块未加载时，按照配置策略决定是否入队缓存并在后续 tick 派发；
 * 命中强制加载策略时可拉起目标区块，票据到期后自动释放。
 * </p>
 */
public final class CrossChunkDispatchService {
	private static final Map<MinecraftServer, DispatchState> STATE_BY_SERVER = new IdentityHashMap<>();
	private static final int TRANSIENT_TICKET_LEVEL = 2;
	private static final int RESIDENT_TICKET_LEVEL = 2;
	private static final TicketType<ChunkPos> TRANSIENT_TICKET_TYPE = TicketType.create(
		"redstonelink_transient",
		Comparator.comparingLong(ChunkPos::toLong)
	);
	private static final TicketType<ResidentTicketKey> RESIDENT_TICKET_TYPE = TicketType.create(
		"redstonelink_resident",
		Comparator
			.comparing((ResidentTicketKey key) -> key.role().name())
			.thenComparing(key -> key.type().name())
			.thenComparingLong(ResidentTicketKey::serial)
	);

	private CrossChunkDispatchService() {
	}

	/**
	 * 注册调度事件。
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CrossChunkDispatchService::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(CrossChunkDispatchService::releaseAllForcedChunksAndClearState);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATE_BY_SERVER.remove(server));
	}

	/**
	 * 记录激活语义（TOGGLE/PULSE）的延迟派发请求。
	 *
	 * @param sourceLevel 源节点所在维度
	 * @param targetNode 目标节点快照
	 * @param sourceType 源节点类型
	 * @param sourceSerial 源节点序号
	 * @param activationMode 激活模式
	 * @return 是否已接管该请求（入队或强制加载链路）
	 */
	public static QueueResult queueActivation(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode
	) {
		if (activationMode == null) {
			return QueueResult.rejected();
		}
		long ttlTicks = RedstoneLinkConfig.crossChunkRelayExpireTicks();
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			DispatchKind.ACTIVATION,
			activationMode,
			0,
			ttlTicks
		);
	}

	/**
	 * 记录 SYNC 语义的延迟派发请求。
	 *
	 * @param sourceLevel 源节点所在维度
	 * @param targetNode 目标节点快照
	 * @param sourceType 源节点类型
	 * @param sourceSerial 源节点序号
	 * @param signalStrength 同步信号强度（0~15）
	 * @return 是否已接管该请求（入队或强制加载链路）
	 */
	public static QueueResult queueSyncSignal(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		int signalStrength
	) {
		int normalizedStrength = Math.max(0, Math.min(15, signalStrength));
		long ttlTicks = normalizedStrength > 0
			? RedstoneLinkConfig.crossChunkSyncSignalTtlTicks()
			: RedstoneLinkConfig.crossChunkRelayExpireTicks();
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			DispatchKind.SYNC_SIGNAL,
			ActivationMode.TOGGLE,
			normalizedStrength,
			ttlTicks
		);
	}

	private static QueueResult queueDispatch(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		DispatchKind dispatchKind,
		ActivationMode activationMode,
		int syncSignalStrength,
		long ttlTicks
	) {
		if (sourceLevel == null || targetNode == null || sourceType == null || dispatchKind == null || activationMode == null) {
			return QueueResult.rejected();
		}
		if (sourceSerial <= 0L || targetNode.serial() <= 0L || ttlTicks <= 0L) {
			return QueueResult.rejected();
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return QueueResult.rejected();
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetNode.type(), LinkNodeSemantics.Role.TARGET)) {
			return QueueResult.rejected();
		}

		DispatchState state = getOrCreateState(sourceLevel.getServer());
		long expireTick = sourceLevel.getGameTime() + Math.max(1L, ttlTicks);
		DispatchKey key = new DispatchKey(
			sourceType,
			sourceSerial,
			targetNode.type(),
			targetNode.serial(),
			dispatchKind
		);
		PendingDispatch pendingDispatch = new PendingDispatch(
			key,
			targetNode.dimension(),
			targetNode.pos(),
			activationMode,
			Math.max(0, Math.min(15, syncSignalStrength)),
			expireTick
		);

		boolean canForceLoad = shouldForceLoad(sourceLevel, pendingDispatch);
		boolean relayEnabled = RedstoneLinkConfig.crossChunkRelayEnabled();
		// 最终接管判定：中继开启或可进入强制加载链路，满足其一即可接管。
		if (!relayEnabled && !canForceLoad) {
			return QueueResult.rejected();
		}

		state.pendingByKey.put(key, pendingDispatch);
		if (canForceLoad) {
			tryForceLoad(sourceLevel.getServer(), state, pendingDispatch, sourceLevel.getGameTime());
		}
		return new QueueResult(true, canForceLoad);
	}

	private static void onServerTick(MinecraftServer server) {
		long gameTime = server.overworld().getGameTime();
		DispatchState state = getOrCreateState(server);
		syncResidentTickets(server, state);
		resetForceLoadWindow(state, gameTime);
		releaseExpiredForcedChunks(server, state, gameTime);
		processPendingDispatches(server, state, gameTime);
		if (state.pendingByKey.isEmpty() && state.forcedChunksUntilTick.isEmpty() && state.residentTickets.isEmpty()) {
			STATE_BY_SERVER.remove(server);
		}
	}

	private static void processPendingDispatches(MinecraftServer server, DispatchState state, long gameTime) {
		Iterator<Map.Entry<DispatchKey, PendingDispatch>> iterator = state.pendingByKey.entrySet().iterator();
		while (iterator.hasNext()) {
			PendingDispatch pending = iterator.next().getValue();
			if (pending.expireGameTick() <= gameTime) {
				iterator.remove();
				continue;
			}
			if (tryDispatch(server, state, pending, gameTime)) {
				iterator.remove();
			}
		}
	}

	private static boolean tryDispatch(MinecraftServer server, DispatchState state, PendingDispatch pending, long gameTime) {
		ServerLevel targetLevel = server.getLevel(pending.dimension());
		if (targetLevel == null) {
			return false;
		}
		if (!targetLevel.isLoaded(pending.pos())) {
			if (shouldForceLoad(targetLevel, pending)) {
				tryForceLoad(server, state, pending, gameTime);
			}
			return false;
		}

		BlockEntity blockEntity = targetLevel.getBlockEntity(pending.pos());
		if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
			LinkSavedData.get(targetLevel).removeNode(pending.key().targetType(), pending.key().targetSerial());
			return true;
		}

		if (pending.key().dispatchKind() == DispatchKind.SYNC_SIGNAL) {
			targetBlockEntity.syncBySource(pending.key().sourceSerial(), pending.syncSignalStrength());
		} else {
			targetBlockEntity.triggerBySource(pending.key().sourceSerial(), pending.activationMode());
		}
		return true;
	}

	private static boolean shouldForceLoad(ServerLevel contextLevel, PendingDispatch pending) {
		if (contextLevel == null || pending == null) {
			return false;
		}
		if (!RedstoneLinkConfig.crossChunkForceLoadEnabled()) {
			return false;
		}
		if (!RedstoneLinkConfig.crossChunkAllowedSourceTypes().contains(pending.key().sourceType())) {
			return false;
		}
		if (!RedstoneLinkConfig.crossChunkAllowedTargetTypes().contains(pending.key().targetType())) {
			return false;
		}

		RedstoneLinkConfig.CrossChunkForceLoadMode mode = RedstoneLinkConfig.crossChunkForceLoadMode();
		if (mode == RedstoneLinkConfig.CrossChunkForceLoadMode.ALL) {
			return true;
		}
		return matchWhitelistOrPreset(contextLevel, pending);
	}

	/**
	 * 校验是否命中运行态白名单或只读 preset。
	 */
	private static boolean matchWhitelistOrPreset(ServerLevel contextLevel, PendingDispatch pending) {
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

		boolean presetSourceMatched = RedstoneLinkConfig.crossChunkPresetContains(
			pending.key().sourceType(),
			pending.key().sourceSerial(),
			LinkNodeSemantics.Role.SOURCE
		);
		if (presetSourceMatched) {
			return true;
		}
		return RedstoneLinkConfig.crossChunkPresetContains(
			pending.key().targetType(),
			pending.key().targetSerial(),
			LinkNodeSemantics.Role.TARGET
		);
	}

	/**
	 * 同步 resident 白名单对应的常驻区块票据。
	 * <p>
	 * 仅操作本模组自有 TicketType，确保与其它模组强制加载来源隔离。
	 * </p>
	 */
	private static void syncResidentTickets(MinecraftServer server, DispatchState state) {
		Map<ResidentTicketKey, ResidentChunkKey> desiredTickets = collectDesiredResidentTickets(server);
		if (!state.residentTickets.isEmpty()) {
			Map<ResidentTicketKey, ResidentChunkKey> currentSnapshot = Map.copyOf(state.residentTickets);
			for (Map.Entry<ResidentTicketKey, ResidentChunkKey> currentEntry : currentSnapshot.entrySet()) {
				ResidentTicketKey key = currentEntry.getKey();
				ResidentChunkKey currentChunk = currentEntry.getValue();
				ResidentChunkKey desiredChunk = desiredTickets.remove(key);
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
		for (Map.Entry<ResidentTicketKey, ResidentChunkKey> desiredEntry : desiredTickets.entrySet()) {
			if (addResidentTicket(server, desiredEntry.getKey(), desiredEntry.getValue())) {
				state.residentTickets.put(desiredEntry.getKey(), desiredEntry.getValue());
			}
		}
	}

	/**
	 * 释放当前服务端所有 resident 票据。
	 */
	private static void releaseResidentTickets(MinecraftServer server, DispatchState state) {
		if (state.residentTickets.isEmpty()) {
			return;
		}
		for (Map.Entry<ResidentTicketKey, ResidentChunkKey> entry : state.residentTickets.entrySet()) {
			removeResidentTicket(server, entry.getKey(), entry.getValue());
		}
	}

	/**
	 * 汇总当前白名单 resident 条目期望持有的区块票据映射。
	 */
	private static Map<ResidentTicketKey, ResidentChunkKey> collectDesiredResidentTickets(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return Map.of();
		}
		LinkSavedData linkSavedData = LinkSavedData.get(overworld);
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(overworld);
		Map<ResidentTicketKey, ResidentChunkKey> desired = new HashMap<>();
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
	 * 将指定角色下 resident 条目追加到期望票据集合。
	 */
	private static void appendDesiredResidentTickets(
		Map<ResidentTicketKey, ResidentChunkKey> desired,
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
				ResidentTicketKey ticketKey = new ResidentTicketKey(role, type, serial);
				desired.put(ticketKey, new ResidentChunkKey(node.dimension(), chunkX, chunkZ));
			}
		}
	}

	private static void tryForceLoad(
		MinecraftServer server,
		DispatchState state,
		PendingDispatch pending,
		long gameTime
	) {
		resetForceLoadWindow(state, gameTime);

		int maxPerTick = RedstoneLinkConfig.crossChunkForceLoadMaxPerTick();
		if (state.forceLoadCountThisTick >= maxPerTick) {
			return;
		}
		SourceKey sourceKey = new SourceKey(pending.key().sourceType(), pending.key().sourceSerial());
		int sourceUsed = state.forceLoadCountBySource.getOrDefault(sourceKey, 0);
		if (sourceUsed >= RedstoneLinkConfig.crossChunkForceLoadMaxPerSourcePerTick()) {
			return;
		}

		ServerLevel targetLevel = server.getLevel(pending.dimension());
		if (targetLevel == null) {
			return;
		}

		int chunkX = pending.pos().getX() >> 4;
		int chunkZ = pending.pos().getZ() >> 4;
		addTransientTicket(targetLevel, chunkX, chunkZ);

		ForcedChunkKey forcedChunkKey = new ForcedChunkKey(targetLevel.dimension(), chunkX, chunkZ);
		long expireTick = gameTime + Math.max(1L, RedstoneLinkConfig.crossChunkForceLoadTicketTicks());
		long previousExpireTick = state.forcedChunksUntilTick.getOrDefault(forcedChunkKey, Long.MIN_VALUE);
		state.forcedChunksUntilTick.put(forcedChunkKey, Math.max(previousExpireTick, expireTick));

		state.forceLoadCountThisTick++;
		state.forceLoadCountBySource.put(sourceKey, sourceUsed + 1);
	}

	private static void releaseExpiredForcedChunks(MinecraftServer server, DispatchState state, long gameTime) {
		Iterator<Map.Entry<ForcedChunkKey, Long>> iterator = state.forcedChunksUntilTick.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ForcedChunkKey, Long> entry = iterator.next();
			if (entry.getValue() > gameTime) {
				continue;
			}
			ForcedChunkKey key = entry.getKey();
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				removeTransientTicket(level, key.chunkX(), key.chunkZ());
			}
			iterator.remove();
		}
	}

	/**
	 * 停服前主动释放当前服务端所有强制加载票据，并清理调度状态。
	 * <p>
	 * 用于兜底处理“未等到过期释放就停服”的场景，避免 forced chunk 状态跨重启残留。
	 * </p>
	 *
	 * @param server 当前服务端
	 */
	private static void releaseAllForcedChunksAndClearState(MinecraftServer server) {
		DispatchState state = STATE_BY_SERVER.get(server);
		if (state == null) {
			return;
		}
		for (ForcedChunkKey key : state.forcedChunksUntilTick.keySet()) {
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				removeTransientTicket(level, key.chunkX(), key.chunkZ());
			}
		}
		releaseResidentTickets(server, state);
		state.forcedChunksUntilTick.clear();
		state.pendingByKey.clear();
		state.residentTickets.clear();
		state.forceLoadCountBySource.clear();
		state.forceLoadCountThisTick = 0;
		state.forceLoadWindowTick = Long.MIN_VALUE;
		STATE_BY_SERVER.remove(server);
	}

	/**
	 * 添加临时区块加载票据。
	 */
	private static void addTransientTicket(ServerLevel level, int chunkX, int chunkZ) {
		ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
		level.getChunkSource().addRegionTicket(TRANSIENT_TICKET_TYPE, chunkPos, TRANSIENT_TICKET_LEVEL, chunkPos);
	}

	/**
	 * 释放临时区块加载票据。
	 */
	private static void removeTransientTicket(ServerLevel level, int chunkX, int chunkZ) {
		ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
		level.getChunkSource().removeRegionTicket(TRANSIENT_TICKET_TYPE, chunkPos, TRANSIENT_TICKET_LEVEL, chunkPos);
	}

	/**
	 * 添加 resident 常驻区块票据。
	 */
	private static boolean addResidentTicket(
		MinecraftServer server,
		ResidentTicketKey ticketKey,
		ResidentChunkKey chunkKey
	) {
		ServerLevel level = server.getLevel(chunkKey.dimension());
		if (level == null) {
			return false;
		}
		ChunkPos chunkPos = new ChunkPos(chunkKey.chunkX(), chunkKey.chunkZ());
		level.getChunkSource().addRegionTicket(RESIDENT_TICKET_TYPE, chunkPos, RESIDENT_TICKET_LEVEL, ticketKey);
		return true;
	}

	/**
	 * 释放 resident 常驻区块票据。
	 */
	private static void removeResidentTicket(
		MinecraftServer server,
		ResidentTicketKey ticketKey,
		ResidentChunkKey chunkKey
	) {
		ServerLevel level = server.getLevel(chunkKey.dimension());
		if (level == null) {
			return;
		}
		ChunkPos chunkPos = new ChunkPos(chunkKey.chunkX(), chunkKey.chunkZ());
		level.getChunkSource().removeRegionTicket(RESIDENT_TICKET_TYPE, chunkPos, RESIDENT_TICKET_LEVEL, ticketKey);
	}

	private static void resetForceLoadWindow(DispatchState state, long gameTime) {
		if (state.forceLoadWindowTick == gameTime) {
			return;
		}
		state.forceLoadWindowTick = gameTime;
		state.forceLoadCountThisTick = 0;
		state.forceLoadCountBySource.clear();
	}

	private static DispatchState getOrCreateState(MinecraftServer server) {
		return STATE_BY_SERVER.computeIfAbsent(server, ignored -> new DispatchState());
	}

	/**
	 * 跨区块排队结果快照。
	 *
	 * @param accepted 是否接管请求（已入队或将尝试强加载）
	 * @param forceLoadPlanned 是否命中强加载策略
	 */
	public record QueueResult(boolean accepted, boolean forceLoadPlanned) {
		private static QueueResult rejected() {
			return new QueueResult(false, false);
		}
	}

	private enum DispatchKind {
		ACTIVATION,
		SYNC_SIGNAL
	}

	private record DispatchKey(
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		long targetSerial,
		DispatchKind dispatchKind
	) {}

	private record PendingDispatch(
		DispatchKey key,
		ResourceKey<Level> dimension,
		BlockPos pos,
		ActivationMode activationMode,
		int syncSignalStrength,
		long expireGameTick
	) {}

	private record SourceKey(LinkNodeType sourceType, long sourceSerial) {}

	private record ForcedChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

	private record ResidentTicketKey(LinkNodeSemantics.Role role, LinkNodeType type, long serial) {}

	private record ResidentChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

	/**
	 * 单服调度状态缓存。
	 */
	private static final class DispatchState {
		private final Map<DispatchKey, PendingDispatch> pendingByKey = new HashMap<>();
		private final Map<ForcedChunkKey, Long> forcedChunksUntilTick = new HashMap<>();
		private final Map<ResidentTicketKey, ResidentChunkKey> residentTickets = new HashMap<>();
		private final Map<SourceKey, Integer> forceLoadCountBySource = new HashMap<>();
		private long forceLoadWindowTick = Long.MIN_VALUE;
		private int forceLoadCountThisTick;
	}
}
