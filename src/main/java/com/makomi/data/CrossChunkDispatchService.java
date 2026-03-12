package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

	private CrossChunkDispatchService() {
	}

	/**
	 * 注册调度事件。
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CrossChunkDispatchService::onServerTick);
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
	public static boolean queueActivation(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode
	) {
		if (activationMode == null) {
			return false;
		}
		long ttlTicks = RedstoneLinkConfig.crossChunkRelayExpireTicks();
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			DispatchKind.ACTIVATION,
			activationMode,
			false,
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
	 * @param signalOn 同步信号状态
	 * @return 是否已接管该请求（入队或强制加载链路）
	 */
	public static boolean queueSyncSignal(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		boolean signalOn
	) {
		long ttlTicks = signalOn
			? RedstoneLinkConfig.crossChunkSyncSignalTtlTicks()
			: RedstoneLinkConfig.crossChunkRelayExpireTicks();
		return queueDispatch(
			sourceLevel,
			targetNode,
			sourceType,
			sourceSerial,
			DispatchKind.SYNC_SIGNAL,
			ActivationMode.TOGGLE,
			signalOn,
			ttlTicks
		);
	}

	private static boolean queueDispatch(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		DispatchKind dispatchKind,
		ActivationMode activationMode,
		boolean syncSignalOn,
		long ttlTicks
	) {
		if (sourceLevel == null || targetNode == null || sourceType == null || dispatchKind == null || activationMode == null) {
			return false;
		}
		if (sourceSerial <= 0L || targetNode.serial() <= 0L || ttlTicks <= 0L) {
			return false;
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return false;
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetNode.type(), LinkNodeSemantics.Role.TARGET)) {
			return false;
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
			syncSignalOn,
			expireTick
		);

		boolean canForceLoad = shouldForceLoad(sourceLevel, pendingDispatch);
		boolean relayEnabled = RedstoneLinkConfig.crossChunkRelayEnabled();
		// 最终接管判定：中继开启或可进入强制加载链路，满足其一即可接管。
		if (!relayEnabled && !canForceLoad) {
			return false;
		}

		state.pendingByKey.put(key, pendingDispatch);
		if (canForceLoad) {
			tryForceLoad(sourceLevel.getServer(), state, pendingDispatch, sourceLevel.getGameTime());
		}
		return true;
	}

	private static void onServerTick(MinecraftServer server) {
		DispatchState state = STATE_BY_SERVER.get(server);
		if (state == null) {
			return;
		}
		long gameTime = server.overworld().getGameTime();
		resetForceLoadWindow(state, gameTime);
		releaseExpiredForcedChunks(server, state, gameTime);
		processPendingDispatches(server, state, gameTime);
		if (state.pendingByKey.isEmpty() && state.forcedChunksUntilTick.isEmpty()) {
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
			targetBlockEntity.syncBySource(pending.key().sourceSerial(), pending.syncSignalOn());
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
		targetLevel.setChunkForced(chunkX, chunkZ, true);

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
				level.setChunkForced(key.chunkX(), key.chunkZ(), false);
			}
			iterator.remove();
		}
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
		boolean syncSignalOn,
		long expireGameTick
	) {}

	private record SourceKey(LinkNodeType sourceType, long sourceSerial) {}

	private record ForcedChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {}

	/**
	 * 单服调度状态缓存。
	 */
	private static final class DispatchState {
		private final Map<DispatchKey, PendingDispatch> pendingByKey = new HashMap<>();
		private final Map<ForcedChunkKey, Long> forcedChunksUntilTick = new HashMap<>();
		private final Map<SourceKey, Integer> forceLoadCountBySource = new HashMap<>();
		private long forceLoadWindowTick = Long.MIN_VALUE;
		private int forceLoadCountThisTick;
	}
}
