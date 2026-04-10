package com.makomi.data;

import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.LinkTriggerSourceBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 节点生命周期与内部 delta 事件桥接器。
 * <p>
 * 当前版本不再依赖全局 `CHUNK_LOAD/CHUNK_UNLOAD` 扫描，
 * 而是改为接收节点实例自发布的轻量上下文 attach/detach 事件，
 * 再在服务端 tick 中按预算消费。
 * </p>
 * <p>
 * 这样可以把以下几类工作从热路径里搬走：
 * </p>
 * <ul>
 * <li>target chunk load replay；</li>
 * <li>core / triggerSource 加载后自愈；</li>
 * <li>可选的 triggerSource attach sync replay；</li>
 * <li>实验性 triggerSource context-detach invalidation。</li>
 * </ul>
 */
public final class LinkNodeLifecycleDispatchEvents {
	private static final int ATTACH_RETRY_MAX = 40;
	private static final Map<MinecraftServer, LifecycleState> STATE_BY_SERVER = new IdentityHashMap<>();

	private LinkNodeLifecycleDispatchEvents() {
	}

	/**
	 * 注册生命周期调度事件。
	 */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(LinkNodeLifecycleDispatchEvents::onServerStarting);
		ServerLifecycleEvents.SERVER_STARTED.register(LinkNodeLifecycleDispatchEvents::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(LinkNodeLifecycleDispatchEvents::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(LinkNodeLifecycleDispatchEvents::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(LinkNodeLifecycleDispatchEvents::onEndServerTick);
	}

	/**
	 * 由节点实例在上下文附着就绪后发布 attach。
	 */
	public static void publishNodeContextAttached(PairableNodeBlockEntity nodeBlockEntity) {
		if (nodeBlockEntity == null || !(nodeBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		long serial = nodeBlockEntity.getSerial();
		if (serial <= 0L) {
			return;
		}
		enqueueTask(
			serverLevel.getServer(),
			NodeLifecycleTask.attach(
				serverLevel.dimension(),
				nodeBlockEntity.getLinkNodeType(),
				serial,
				nodeBlockEntity.getBlockPos().immutable(),
				0
			)
		);
	}

	/**
	 * 由节点实例在普通上下文脱附时发布 detach。
	 */
	public static void publishNodeContextDetached(
		ServerLevel serverLevel,
		LinkNodeType nodeType,
		long serial,
		BlockPos blockPos
	) {
		if (serverLevel == null || nodeType == null || serial <= 0L || blockPos == null) {
			return;
		}
		enqueueTask(
			serverLevel.getServer(),
			NodeLifecycleTask.detach(serverLevel.dimension(), nodeType, serial, blockPos.immutable())
		);
	}

	private static void onServerStarting(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		state.serverStarted = false;
		state.serverStopping = false;
		state.pendingTasks.clear();
	}

	private static void onServerStarted(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		state.serverStarted = true;
		state.serverStopping = false;
	}

	private static void onServerStopping(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		state.serverStarted = false;
		state.serverStopping = true;
		state.pendingTasks.clear();
	}

	private static void onServerStopped(MinecraftServer server) {
		synchronized (STATE_BY_SERVER) {
			STATE_BY_SERVER.remove(server);
		}
	}

	/**
	 * 预算化消费节点生命周期任务。
	 */
	private static void onEndServerTick(MinecraftServer server) {
		LifecycleState state = getOrCreateState(server);
		if (!state.serverStarted || state.serverStopping || state.pendingTasks.isEmpty()) {
			return;
		}

		int budget = Math.max(1, RedstoneLinkConfig.crossChunk().dispatchMaxPerTick());
		ArrayList<NodeLifecycleTask> drainedTasks = drainBatch(state, budget);
		if (drainedTasks.isEmpty()) {
			return;
		}

		LinkedHashMap<NodeLifecycleTaskKey, NodeLifecycleTask> retryTasks = new LinkedHashMap<>();
		int maxRetry = Math.max(ATTACH_RETRY_MAX, Math.max(1, RedstoneLinkConfig.runtime().loadResyncMaxRetry()));
		for (NodeLifecycleTask task : drainedTasks) {
			ConsumeResult consumeResult = consumeTask(server, task);
			if (consumeResult != ConsumeResult.DEFERRED || task.kind() != NodeLifecycleKind.ATTACH) {
				continue;
			}
			NodeLifecycleTask retryTask = task.nextAttempt();
			if (retryTask.attempt() <= maxRetry) {
				retryTasks.put(retryTask.key(), retryTask);
			}
		}
		if (!retryTasks.isEmpty()) {
			state.pendingTasks.putAll(retryTasks);
		}
	}

	private static ConsumeResult consumeTask(MinecraftServer server, NodeLifecycleTask task) {
		return switch (task.kind()) {
			case ATTACH -> consumeAttachTask(server, task);
			case DETACH -> consumeDetachTask(server, task);
		};
	}

	/**
	 * 处理 attach：补节点在线登记、target replay 与加载后自愈。
	 */
	private static ConsumeResult consumeAttachTask(MinecraftServer server, NodeLifecycleTask task) {
		if (server == null || task == null) {
			return ConsumeResult.DROPPED;
		}
		ServerLevel level = server.getLevel(task.dimension());
		if (level == null) {
			return ConsumeResult.DEFERRED;
		}
		LevelChunk chunk = level.getChunkSource().getChunkNow(task.chunkPos().x, task.chunkPos().z);
		if (chunk == null) {
			return ConsumeResult.DEFERRED;
		}
		BlockEntity blockEntity = chunk.getBlockEntity(task.blockPos(), LevelChunk.EntityCreationType.CHECK);
		if (!(blockEntity instanceof PairableNodeBlockEntity nodeBlockEntity)) {
			return chunk.getBlockState(task.blockPos()).hasBlockEntity() ? ConsumeResult.DEFERRED : ConsumeResult.DROPPED;
		}
		if (nodeBlockEntity.getLinkNodeType() != task.nodeType() || nodeBlockEntity.getSerial() != task.serial()) {
			return ConsumeResult.DROPPED;
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		savedData.registerNode(task.serial(), level.dimension(), task.blockPos(), task.nodeType());

		if (RedstoneLinkConfig.runtime().coreLoadResyncEnabled() && nodeBlockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity) {
			targetBlockEntity.consumePendingLoadBlockStateSync();
		}
		if (
			RedstoneLinkConfig.runtime().triggerSourceLoadResyncEnabled()
				&& nodeBlockEntity instanceof LinkTriggerSourceBlockEntity triggerSourceBlockEntity
				&& triggerSourceBlockEntity.hasPendingLoadInputStateResync()
				&& triggerSourceBlockEntity.getBlockState().getBlock() instanceof LinkSignalEmitterBlock signalEmitterBlock
		) {
			signalEmitterBlock.resyncPoweredStateFromCurrentInputsWithoutTrigger(
				level,
				triggerSourceBlockEntity.getBlockPos(),
				triggerSourceBlockEntity.getBlockState()
			);
			triggerSourceBlockEntity.clearPendingLoadInputStateResync();
		}
		if (LinkNodeSemantics.isAllowedForRole(task.nodeType(), LinkNodeSemantics.Role.SOURCE)) {
			replaySourceAttachSyncIfEnabled(level, task.nodeType(), task.serial(), savedData);
		}

		if (!LinkNodeSemantics.isAllowedForRole(task.nodeType(), LinkNodeSemantics.Role.TARGET)) {
			return ConsumeResult.COMPLETED;
		}

		CrossChunkDispatchService.notifyTargetChunkLoaded(server, task.dimension(), task.chunkPos());
		if (!RedstoneLinkConfig.crossChunk().syncTargetChunkLoadReplayEnabled()) {
			return ConsumeResult.COMPLETED;
		}
		return tryReplayTargetChunkLoad(level, task.nodeType(), task.serial());
	}

	/**
	 * 处理 detach：仅在实验性失效语义开启时，为来源节点发布 context-detach invalidation。
	 */
	private static ConsumeResult consumeDetachTask(MinecraftServer server, NodeLifecycleTask task) {
		if (server == null || task == null) {
			return ConsumeResult.DROPPED;
		}
		ServerLevel level = server.getLevel(task.dimension());
		if (level == null) {
			return ConsumeResult.DROPPED;
		}
		if (!RedstoneLinkConfig.crossChunk().triggerSourceContextDetachInvalidationEnabled()) {
			return ConsumeResult.COMPLETED;
		}
		if (!LinkNodeSemantics.isAllowedForRole(task.nodeType(), LinkNodeSemantics.Role.SOURCE)) {
			return ConsumeResult.COMPLETED;
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		Set<Long> linkedPeers = savedData.getLinkedPeersByNodeType(task.nodeType(), task.serial());
		if (linkedPeers.isEmpty()) {
			return ConsumeResult.COMPLETED;
		}
		InternalDispatchDeltaEvents.publishLinkChunkUnloadedAsyncBatch(
			level,
			task.nodeType(),
			task.serial(),
			linkedPeers,
			EventMeta.of(level.getGameTime(), 0, 0L)
		);
		return ConsumeResult.COMPLETED;
	}

	/**
	 * 对 target attach 执行一次命中式 replay 判定。
	 */
	private static ConsumeResult tryReplayTargetChunkLoad(ServerLevel level, LinkNodeType nodeType, long serial) {
		LinkSavedData savedData = LinkSavedData.get(level);
		Set<Long> linkedPeers = savedData.getLinkedPeersByNodeType(nodeType, serial);
		if (linkedPeers.isEmpty()) {
			return ConsumeResult.COMPLETED;
		}
		LinkSavedData.RuntimeOnlineProbeResult probeResult = savedData.probeRuntimeOnlineNodeNonBlocking(level, nodeType, serial);
		if (probeResult.retryable()) {
			return ConsumeResult.DEFERRED;
		}
		if (!probeResult.ready()) {
			return ConsumeResult.DROPPED;
		}
		Set<Long> replayEligibleSources = TriggerSourceEffectiveActivationPolicy.filterReplayEligibleSyncSourcesForTargetAttachReplay(
			level,
			savedData,
			linkedPeers
		);
		if (replayEligibleSources.isEmpty()) {
			return ConsumeResult.COMPLETED;
		}
		LinkSavedData.LinkNode coreNode = savedData.findNode(nodeType, serial).orElse(null);
		if (coreNode == null) {
			return ConsumeResult.DROPPED;
		}
		for (Long replayEligibleSourceSerial : replayEligibleSources) {
			if (replayEligibleSourceSerial == null || replayEligibleSourceSerial <= 0L) {
				continue;
			}
			LinkSavedData.LinkNode triggerSourceNode = savedData
				.findNode(LinkNodeType.TRIGGER_SOURCE, replayEligibleSourceSerial)
				.orElse(null);
			if (triggerSourceNode == null) {
				continue;
			}
			SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot = InternalDispatchDeltaRuleSupport.resolveReplaySyncSnapshot(
				level,
				LinkNodeType.TRIGGER_SOURCE,
				replayEligibleSourceSerial
			);
			if (replaySnapshot == null) {
				continue;
			}
			// attach replay 发布前必须直接重查持久化过滤真值，避免过滤器注册晚于 replay 时误放行。
			if (
				!LinkDispatchFilterService.allowsReplayByPersistedFilters(
					level.getServer(),
					triggerSourceNode.dimension(),
					triggerSourceNode.pos(),
					replayEligibleSourceSerial,
					coreNode.dimension(),
					coreNode.pos(),
					serial,
					replaySnapshot.signalStrength()
				)
			) {
				continue;
			}
			InternalDispatchDeltaRuleSupport.publishResolvedTargetChunkLoadSyncReplay(
				level,
				replayEligibleSourceSerial,
				serial,
				replaySnapshot,
				InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH
			);
		}
		return ConsumeResult.COMPLETED;
	}

	/**
	 * 对来源 attach 执行一次可选的 sync 恢复。
	 */
	private static void replaySourceAttachSyncIfEnabled(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial,
		LinkSavedData savedData
	) {
		if (level == null || nodeType == null || serial <= 0L || savedData == null) {
			return;
		}
		if (!RedstoneLinkConfig.crossChunk().syncSourceAttachReplayEnabled()) {
			return;
		}
		Set<Long> linkedPeers = savedData.getLinkedPeersByNodeType(nodeType, serial);
		if (linkedPeers.isEmpty()) {
			return;
		}
		LinkSavedData.LinkNode triggerSourceNode = savedData.findNode(nodeType, serial).orElse(null);
		if (triggerSourceNode == null) {
			return;
		}
		int replayStrength = InternalDispatchDeltaRuleSupport.resolveReplaySyncStrength(level, nodeType, serial);
		if (replayStrength < 0) {
			return;
		}
		EventMeta replayEventMeta = EventMeta.now(level);
		for (Long linkedCoreSerial : linkedPeers) {
			if (linkedCoreSerial == null || linkedCoreSerial <= 0L) {
				continue;
			}
			LinkSavedData.LinkNode coreNode = savedData.findNode(LinkNodeType.CORE, linkedCoreSerial).orElse(null);
			if (coreNode == null) {
				continue;
			}
			// source attach replay 同样先按持久化过滤器真值补判，再发布恢复事件。
			if (
				!LinkDispatchFilterService.allowsReplayByPersistedFilters(
					level.getServer(),
					triggerSourceNode.dimension(),
					triggerSourceNode.pos(),
					serial,
					coreNode.dimension(),
					coreNode.pos(),
					linkedCoreSerial,
					replayStrength
				)
			) {
				continue;
			}
			InternalDispatchDeltaRuleSupport.publishSourceRebuildUpsertResolved(
				level,
				LinkNodeType.TRIGGER_SOURCE,
				serial,
				LinkNodeType.CORE,
				linkedCoreSerial,
				replayEventMeta,
				replayStrength,
				InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH
			);
		}
	}

	private static void enqueueTask(MinecraftServer server, NodeLifecycleTask task) {
		if (server == null || task == null) {
			return;
		}
		LifecycleState state = getOrCreateState(server);
		state.pendingTasks.put(task.key(), task);
	}

	private static ArrayList<NodeLifecycleTask> drainBatch(LifecycleState state, int budget) {
		ArrayList<NodeLifecycleTask> drainedTasks = new ArrayList<>(Math.min(budget, state.pendingTasks.size()));
		Iterator<Map.Entry<NodeLifecycleTaskKey, NodeLifecycleTask>> iterator = state.pendingTasks.entrySet().iterator();
		while (iterator.hasNext() && drainedTasks.size() < budget) {
			Map.Entry<NodeLifecycleTaskKey, NodeLifecycleTask> entry = iterator.next();
			drainedTasks.add(entry.getValue());
			iterator.remove();
		}
		return drainedTasks;
	}

	private static LifecycleState getOrCreateState(MinecraftServer server) {
		synchronized (STATE_BY_SERVER) {
			return STATE_BY_SERVER.computeIfAbsent(server, ignored -> new LifecycleState());
		}
	}

	/**
	 * 测试专用：清理静态状态，避免跨用例残留。
	 */
	static void resetForTesting() {
		synchronized (STATE_BY_SERVER) {
			STATE_BY_SERVER.clear();
		}
	}

	private enum NodeLifecycleKind {
		ATTACH,
		DETACH
	}

	private enum ConsumeResult {
		COMPLETED,
		DEFERRED,
		DROPPED
	}

	private record NodeLifecycleTaskKey(ResourceKey<Level> dimension, LinkNodeType nodeType, long serial) {}

	private record NodeLifecycleTask(
		NodeLifecycleKind kind,
		ResourceKey<Level> dimension,
		LinkNodeType nodeType,
		long serial,
		BlockPos blockPos,
		ChunkPos chunkPos,
		int attempt
	) {
		private NodeLifecycleTask {
			blockPos = blockPos == null ? BlockPos.ZERO : blockPos.immutable();
			chunkPos = chunkPos == null ? new ChunkPos(blockPos) : new ChunkPos(chunkPos.x, chunkPos.z);
		}

		private static NodeLifecycleTask attach(
			ResourceKey<Level> dimension,
			LinkNodeType nodeType,
			long serial,
			BlockPos blockPos,
			int attempt
		) {
			return new NodeLifecycleTask(NodeLifecycleKind.ATTACH, dimension, nodeType, serial, blockPos, new ChunkPos(blockPos), attempt);
		}

		private static NodeLifecycleTask detach(
			ResourceKey<Level> dimension,
			LinkNodeType nodeType,
			long serial,
			BlockPos blockPos
		) {
			return new NodeLifecycleTask(NodeLifecycleKind.DETACH, dimension, nodeType, serial, blockPos, new ChunkPos(blockPos), 0);
		}

		private NodeLifecycleTaskKey key() {
			return new NodeLifecycleTaskKey(dimension, nodeType, serial);
		}

		private NodeLifecycleTask nextAttempt() {
			return new NodeLifecycleTask(kind, dimension, nodeType, serial, blockPos, chunkPos, attempt + 1);
		}
	}

	private static final class LifecycleState {
		private boolean serverStarted;
		private boolean serverStopping;
		private final LinkedHashMap<NodeLifecycleTaskKey, NodeLifecycleTask> pendingTasks = new LinkedHashMap<>();
	}
}
