package com.makomi.data;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 派发过滤器查询门面。
 * <p>
 * 当前过滤器真值由 `PlacedLinkFilterSavedData` 持久化保存，过滤查询不再依赖区块是否已加载；
 * 该服务在保留统一 query 入口的同时，也负责把过滤器运行时变化协调为 `sync` 失效/补发事件。
 * </p>
 */
public final class LinkDispatchFilterService {
	/**
	 * 过滤器立方域半径：以方块中心为中心，向六向各扩展 8 格。
	 */
	public static final int FILTER_RADIUS = 8;
	private static boolean callbacksRegistered;

	private LinkDispatchFilterService() {
	}

	/**
	 * 注册服务端钩子。
	 */
	public static void register() {
		if (callbacksRegistered) {
			return;
		}
		callbacksRegistered = true;
		ServerLifecycleEvents.SERVER_STARTED.register(LinkDispatchFilterService::refreshLoadedNeighborSignalSnapshotsAfterServerStarted);
	}

	/**
	 * 写入或刷新一个已放置过滤器的持久化真值，但不主动重采样邻居输入。
	 * <p>
	 * 启动附着阶段应优先使用该入口，避免把潜在阻塞式世界读取带回 `clearRemoved()` 链路。
	 * </p>
	 */
	public static void upsertFilter(AbstractLinkFilterBlockEntity filterBlockEntity) {
		if (filterBlockEntity == null || !(filterBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		PlacedLinkFilterSavedData
			.get(serverLevel)
			.upsertPreservingNeighborSignal(
				filterBlockEntity.filterKind(),
				serverLevel.dimension(),
				filterBlockEntity.getBlockPos(),
				filterBlockEntity.snapshot()
			);
	}

	/**
	 * 以当前世界态重采样邻居输入，并刷新过滤器持久化真值。
	 * <p>
	 * 该入口属于“运行时真实变化”路径：若过滤器生效结果发生变化，会进一步协调 `sync` 失效/补发。
	 * </p>
	 */
	public static void refreshFilterWithCurrentNeighborSignal(AbstractLinkFilterBlockEntity filterBlockEntity) {
		if (filterBlockEntity == null || !(filterBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		PlacedLinkFilterSavedData filterSavedData = PlacedLinkFilterSavedData.get(serverLevel);
		PlacedLinkFilterSavedData.FilterEntry beforeEntry = filterSavedData
			.findEntry(serverLevel.dimension(), filterBlockEntity.filterKind(), filterBlockEntity.getBlockPos())
			.orElse(null);
		boolean changed = filterSavedData.upsert(
			filterBlockEntity.filterKind(),
			serverLevel.dimension(),
			filterBlockEntity.getBlockPos(),
			filterBlockEntity.snapshot(),
			filterBlockEntity.sampleNeighborSignalStrength()
		);
		if (!changed) {
			return;
		}
		PlacedLinkFilterSavedData.FilterEntry afterEntry = filterSavedData
			.findEntry(serverLevel.dimension(), filterBlockEntity.filterKind(), filterBlockEntity.getBlockPos())
			.orElse(null);
		reconcileSyncDeltaAfterFilterChange(serverLevel.getServer(), filterSavedData, filterBlockEntity.filterKind(), beforeEntry, afterEntry);
	}

	/**
	 * 从持久化真值中移除一个过滤器。
	 * <p>
	 * 该入口仅用于真实物理移除；普通 context-detach 不应走到这里。
	 * </p>
	 */
	public static void removeFilter(AbstractLinkFilterBlockEntity filterBlockEntity) {
		if (filterBlockEntity == null || !(filterBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		removeFilter(serverLevel.getServer(), serverLevel.dimension(), filterBlockEntity.filterKind(), filterBlockEntity.getBlockPos());
	}

	/**
	 * 从持久化真值中移除一个过滤器引用。
	 */
	public static void removeFilter(
		MinecraftServer server,
		ResourceKey<Level> dimension,
		LinkFilterKind filterKind,
		BlockPos filterPos
	) {
		if (server == null || dimension == null || filterKind == null || filterPos == null) {
			return;
		}
		PlacedLinkFilterSavedData filterSavedData = resolveSharedSavedData(server);
		if (filterSavedData == null) {
			return;
		}
		PlacedLinkFilterSavedData.FilterEntry beforeEntry = filterSavedData.findEntry(dimension, filterKind, filterPos).orElse(null);
		if (!filterSavedData.remove(dimension, filterKind, filterPos)) {
			return;
		}
		reconcileSyncDeltaAfterFilterChange(server, filterSavedData, filterKind, beforeEntry, null);
	}

	/**
	 * 判断一个 `triggerSource` 派发是否可通过发送过滤器。
	 */
	public static boolean allowsSend(
		ServerLevel sourceLevel,
		BlockPos sourcePos,
		long triggerSourceSerial,
		int signalStrength
	) {
		return allowsByKind(sourceLevel, sourcePos, triggerSourceSerial, signalStrength, LinkFilterKind.SEND);
	}

	/**
	 * 判断一个 `core` 候选目标是否可通过接收过滤器。
	 */
	public static boolean allowsReceive(
		MinecraftServer server,
		ResourceKey<Level> targetDimension,
		BlockPos targetPos,
		long coreSerial,
		int signalStrength
	) {
		if (server == null || targetDimension == null) {
			return true;
		}
		ServerLevel targetLevel = server.getLevel(targetDimension);
		return targetLevel == null || allowsByKind(targetLevel, targetPos, coreSerial, signalStrength, LinkFilterKind.RECEIVE);
	}

	/**
	 * 判断一条 attach replay 是否可通过持久化 send/receive 过滤器真值。
	 * <p>
	 * 该入口直接读取 `PlacedLinkFilterSavedData`，不依赖过滤器实例附着顺序。
	 * </p>
	 */
	public static boolean allowsReplayByPersistedFilters(
		MinecraftServer server,
		ResourceKey<Level> triggerSourceDimension,
		BlockPos triggerSourcePos,
		long triggerSourceSerial,
		ResourceKey<Level> coreDimension,
		BlockPos corePos,
		long coreSerial,
		int signalStrength
	) {
		return allowsReplayByPersistedFilters(
			resolveSharedSavedData(server),
			resolveSharedLinkSavedData(server),
			triggerSourceDimension,
			triggerSourcePos,
			triggerSourceSerial,
			coreDimension,
			corePos,
			coreSerial,
			signalStrength
		);
	}

	/**
	 * 发送过滤器拦截当前 `sync` 新信号时，若旧信号先前仍放行，则补发一次 `sync` 失效。
	 */
	public static void reconcileBlockedSyncDispatchBySendFilter(
		ServerLevel sourceLevel,
		BlockPos sourcePos,
		long triggerSourceSerial,
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot,
		EventMeta eventMeta
	) {
		if (
			sourceLevel == null
				|| sourcePos == null
				|| triggerSourceSerial <= 0L
				|| previousSnapshot == null
				|| sourceLevel.getServer() == null
		) {
			return;
		}
		PlacedLinkFilterSavedData filterSavedData = resolveSharedSavedData(sourceLevel.getServer());
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		if (filterSavedData == null) {
			return;
		}
		if (
			!allowsByKind(
				filterSavedData,
				savedData,
				sourceLevel.dimension(),
				sourcePos,
				triggerSourceSerial,
				previousSnapshot.signalStrength(),
				LinkFilterKind.SEND
			)
		) {
			return;
		}
		for (Long coreSerial : savedData.getLinkedCoresByTriggerSource(triggerSourceSerial)) {
			if (coreSerial == null || coreSerial <= 0L) {
				continue;
			}
			LinkSavedData.LinkNode coreNode = savedData.findNode(LinkNodeType.CORE, coreSerial).orElse(null);
			if (coreNode == null) {
				continue;
			}
			if (
				!allowsByKind(
					filterSavedData,
					savedData,
					coreNode.dimension(),
					coreNode.pos(),
					coreSerial,
					previousSnapshot.signalStrength(),
					LinkFilterKind.RECEIVE
				)
			) {
				continue;
			}
			InternalDispatchDeltaEvents.publishSourceInvalidation(
				sourceLevel,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				LinkNodeType.CORE,
				coreSerial,
				normalizeEventMeta(sourceLevel, eventMeta)
			);
		}
	}

	/**
	 * 接收过滤器拦截当前 `sync` 新信号时，若旧信号先前仍放行，则补发一次该链路的 `sync` 失效。
	 */
	public static void reconcileBlockedSyncDispatchByReceiveFilter(
		ServerLevel sourceLevel,
		BlockPos sourcePos,
		long triggerSourceSerial,
		ResourceKey<Level> coreDimension,
		BlockPos corePos,
		long coreSerial,
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot,
		EventMeta eventMeta
	) {
		if (
			sourceLevel == null
				|| sourcePos == null
				|| triggerSourceSerial <= 0L
				|| coreDimension == null
				|| corePos == null
				|| coreSerial <= 0L
				|| previousSnapshot == null
				|| sourceLevel.getServer() == null
		) {
			return;
		}
		PlacedLinkFilterSavedData filterSavedData = resolveSharedSavedData(sourceLevel.getServer());
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		if (
			!allowsReplayByPersistedFilters(
				filterSavedData,
				savedData,
				sourceLevel.dimension(),
				sourcePos,
				triggerSourceSerial,
				coreDimension,
				corePos,
				coreSerial,
				previousSnapshot.signalStrength()
			)
		) {
			return;
		}
		InternalDispatchDeltaEvents.publishSourceInvalidation(
			sourceLevel,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			LinkNodeType.CORE,
			coreSerial,
			normalizeEventMeta(sourceLevel, eventMeta)
		);
	}

	/**
	 * 测试专用：基于指定持久化过滤真值判断 replay 是否放行。
	 */
	static boolean allowsReplayByPersistedFilters(
		PlacedLinkFilterSavedData filterSavedData,
		ResourceKey<Level> triggerSourceDimension,
		BlockPos triggerSourcePos,
		long triggerSourceSerial,
		ResourceKey<Level> coreDimension,
		BlockPos corePos,
		long coreSerial,
		int signalStrength
	) {
		return allowsReplayByPersistedFilters(
			filterSavedData,
			null,
			triggerSourceDimension,
			triggerSourcePos,
			triggerSourceSerial,
			coreDimension,
			corePos,
			coreSerial,
			signalStrength
		);
	}

	/**
	 * 测试专用：基于指定持久化过滤真值与连接模式信息判断 replay 是否放行。
	 */
	static boolean allowsReplayByPersistedFilters(
		PlacedLinkFilterSavedData filterSavedData,
		LinkSavedData linkSavedData,
		ResourceKey<Level> triggerSourceDimension,
		BlockPos triggerSourcePos,
		long triggerSourceSerial,
		ResourceKey<Level> coreDimension,
		BlockPos corePos,
		long coreSerial,
		int signalStrength
	) {
		if (
			filterSavedData == null
				|| triggerSourceDimension == null
				|| triggerSourcePos == null
				|| triggerSourceSerial <= 0L
				|| coreDimension == null
				|| corePos == null
				|| coreSerial <= 0L
		) {
			return false;
		}
		return allowsByKind(
			filterSavedData,
			linkSavedData,
			triggerSourceDimension,
			triggerSourcePos,
			triggerSourceSerial,
			signalStrength,
			LinkFilterKind.SEND
		)
			&& allowsByKind(filterSavedData, linkSavedData, coreDimension, corePos, coreSerial, signalStrength, LinkFilterKind.RECEIVE);
	}

	/**
	 * 共用的按过滤种类求值入口。
	 */
	private static boolean allowsByKind(
		ServerLevel level,
		BlockPos nodePos,
		long serial,
		int signalStrength,
		LinkFilterKind filterKind
	) {
		if (level == null) {
			return true;
		}
		return allowsByKind(
			PlacedLinkFilterSavedData.get(level),
			LinkSavedData.get(level),
			level.dimension(),
			nodePos,
			serial,
			signalStrength,
			filterKind
		);
	}

	/**
	 * 基于已解析持久化真值的过滤种类求值入口。
	 */
	private static boolean allowsByKind(
		PlacedLinkFilterSavedData filterSavedData,
		LinkSavedData linkSavedData,
		ResourceKey<Level> dimension,
		BlockPos nodePos,
		long serial,
		int signalStrength,
		LinkFilterKind filterKind
	) {
		if (filterSavedData == null || dimension == null || nodePos == null || serial <= 0L || filterKind == null) {
			return true;
		}
		List<LinkFilterRuleEvaluator.FilterRuntimeView> activeFilters = filterSavedData.collectFilters(dimension, nodePos, filterKind);
		FilterEvaluationTarget evaluationTarget = resolveFilterEvaluationTarget(linkSavedData, filterKind, serial);
		return LinkFilterRuleEvaluator.allows(activeFilters, evaluationTarget.targetMode(), evaluationTarget.targetValue(), signalStrength);
	}

	/**
	 * 基于“当前条目已写入 SavedData”的前提，回算旧过滤器视图是否放行。
	 */
	private static boolean allowsByKindWithChangedEntry(
		PlacedLinkFilterSavedData filterSavedData,
		LinkSavedData linkSavedData,
		ResourceKey<Level> dimension,
		BlockPos nodePos,
		long serial,
		int signalStrength,
		LinkFilterKind filterKind,
		PlacedLinkFilterSavedData.FilterEntry currentEntry,
		PlacedLinkFilterSavedData.FilterEntry previousEntry
	) {
		if (filterSavedData == null || dimension == null || nodePos == null || serial <= 0L || filterKind == null) {
			return true;
		}
		List<LinkFilterRuleEvaluator.FilterRuntimeView> activeFilters = new ArrayList<>();
		boolean replaced = false;
		for (PlacedLinkFilterSavedData.FilterEntry entry : filterSavedData.collectEntries(dimension, nodePos, filterKind)) {
			if (currentEntry != null && entry.sameFilter(currentEntry)) {
				replaced = true;
				if (previousEntry != null && previousEntry.covers(nodePos)) {
					activeFilters.add(previousEntry.toRuntimeView());
				}
				continue;
			}
			activeFilters.add(entry.toRuntimeView());
		}
		if (!replaced && previousEntry != null && previousEntry.covers(nodePos)) {
			activeFilters.add(previousEntry.toRuntimeView());
		}
		FilterEvaluationTarget evaluationTarget = resolveFilterEvaluationTarget(linkSavedData, filterKind, serial);
		return LinkFilterRuleEvaluator.allows(activeFilters, evaluationTarget.targetMode(), evaluationTarget.targetValue(), signalStrength);
	}

	/**
	 * 过滤器变化后按 `before/after` 差分协调受影响的 `sync` 失效/补发。
	 */
	private static void reconcileSyncDeltaAfterFilterChange(
		MinecraftServer server,
		PlacedLinkFilterSavedData filterSavedData,
		LinkFilterKind filterKind,
		PlacedLinkFilterSavedData.FilterEntry beforeEntry,
		PlacedLinkFilterSavedData.FilterEntry afterEntry
	) {
		PlacedLinkFilterSavedData.FilterEntry referenceEntry = afterEntry == null ? beforeEntry : afterEntry;
		if (server == null || filterSavedData == null || filterKind == null || referenceEntry == null) {
			return;
		}
		ServerLevel contextLevel = resolveEventContextLevel(server, referenceEntry.dimension());
		if (contextLevel == null) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(contextLevel);
		if (filterKind == LinkFilterKind.SEND) {
			reconcileSendFilterChange(server, filterSavedData, savedData, beforeEntry, afterEntry, referenceEntry);
			return;
		}
		reconcileReceiveFilterChange(server, filterSavedData, savedData, beforeEntry, afterEntry, referenceEntry);
	}

	/**
	 * send 过滤器变化：先找受影响的 `triggerSource`，再展开其已连接 `core`。
	 */
	private static void reconcileSendFilterChange(
		MinecraftServer server,
		PlacedLinkFilterSavedData filterSavedData,
		LinkSavedData savedData,
		PlacedLinkFilterSavedData.FilterEntry beforeEntry,
		PlacedLinkFilterSavedData.FilterEntry afterEntry,
		PlacedLinkFilterSavedData.FilterEntry referenceEntry
	) {
		for (LinkSavedData.LinkNode triggerSourceNode : savedData.collectNodesInCube(
			referenceEntry.dimension(),
			LinkNodeType.TRIGGER_SOURCE,
			referenceEntry.filterPos(),
			FILTER_RADIUS
		)) {
			ServerLevel sourceLevel = server.getLevel(triggerSourceNode.dimension());
			if (sourceLevel == null) {
				continue;
			}
			SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot = InternalDispatchDeltaRuleSupport.resolveReplaySyncSnapshot(
				sourceLevel,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceNode.serial()
			);
			if (replaySnapshot == null) {
				continue;
			}
			boolean beforeSendAllowed = allowsByKindWithChangedEntry(
				filterSavedData,
				savedData,
				triggerSourceNode.dimension(),
				triggerSourceNode.pos(),
				triggerSourceNode.serial(),
				replaySnapshot.signalStrength(),
				LinkFilterKind.SEND,
				afterEntry,
				beforeEntry
			);
			boolean afterSendAllowed = allowsByKind(
				filterSavedData,
				savedData,
				triggerSourceNode.dimension(),
				triggerSourceNode.pos(),
				triggerSourceNode.serial(),
				replaySnapshot.signalStrength(),
				LinkFilterKind.SEND
			);
			if (beforeSendAllowed == afterSendAllowed) {
				continue;
			}
			for (Long coreSerial : savedData.getLinkedCoresByTriggerSource(triggerSourceNode.serial())) {
				if (coreSerial == null || coreSerial <= 0L) {
					continue;
				}
				LinkSavedData.LinkNode coreNode = savedData.findNode(LinkNodeType.CORE, coreSerial).orElse(null);
				if (coreNode == null) {
					continue;
				}
				boolean receiveAllowed = allowsByKind(
					filterSavedData,
					savedData,
					coreNode.dimension(),
					coreNode.pos(),
					coreSerial,
					replaySnapshot.signalStrength(),
					LinkFilterKind.RECEIVE
				);
				publishSyncPathTransition(
					sourceLevel,
					savedData,
					triggerSourceNode,
					coreNode,
					replaySnapshot,
					beforeSendAllowed && receiveAllowed,
					afterSendAllowed && receiveAllowed
				);
			}
		}
	}

	/**
	 * receive 过滤器变化：先找受影响的 `core`，再反查其已连接 `triggerSource`。
	 */
	private static void reconcileReceiveFilterChange(
		MinecraftServer server,
		PlacedLinkFilterSavedData filterSavedData,
		LinkSavedData savedData,
		PlacedLinkFilterSavedData.FilterEntry beforeEntry,
		PlacedLinkFilterSavedData.FilterEntry afterEntry,
		PlacedLinkFilterSavedData.FilterEntry referenceEntry
	) {
		for (LinkSavedData.LinkNode coreNode : savedData.collectNodesInCube(
			referenceEntry.dimension(),
			LinkNodeType.CORE,
			referenceEntry.filterPos(),
			FILTER_RADIUS
		)) {
			for (Long triggerSourceSerial : savedData.getLinkedTriggerSourcesByCore(coreNode.serial())) {
				if (triggerSourceSerial == null || triggerSourceSerial <= 0L) {
					continue;
				}
				LinkSavedData.LinkNode triggerSourceNode = savedData
					.findNode(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)
					.orElse(null);
				if (triggerSourceNode == null) {
					continue;
				}
				ServerLevel sourceLevel = server.getLevel(triggerSourceNode.dimension());
				if (sourceLevel == null) {
					continue;
				}
				SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot = InternalDispatchDeltaRuleSupport.resolveReplaySyncSnapshot(
					sourceLevel,
					LinkNodeType.TRIGGER_SOURCE,
					triggerSourceSerial
				);
				if (replaySnapshot == null) {
					continue;
				}
				boolean sendAllowed = allowsByKind(
					filterSavedData,
					savedData,
					triggerSourceNode.dimension(),
					triggerSourceNode.pos(),
					triggerSourceSerial,
					replaySnapshot.signalStrength(),
					LinkFilterKind.SEND
				);
				boolean beforeReceiveAllowed = allowsByKindWithChangedEntry(
					filterSavedData,
					savedData,
					coreNode.dimension(),
					coreNode.pos(),
					coreNode.serial(),
					replaySnapshot.signalStrength(),
					LinkFilterKind.RECEIVE,
					afterEntry,
					beforeEntry
				);
				boolean afterReceiveAllowed = allowsByKind(
					filterSavedData,
					savedData,
					coreNode.dimension(),
					coreNode.pos(),
					coreNode.serial(),
					replaySnapshot.signalStrength(),
					LinkFilterKind.RECEIVE
				);
				publishSyncPathTransition(
					sourceLevel,
					savedData,
					triggerSourceNode,
					coreNode,
					replaySnapshot,
					sendAllowed && beforeReceiveAllowed,
					sendAllowed && afterReceiveAllowed
				);
			}
		}
	}

	/**
	 * 按路径布尔差分发布一次 `sync` 失效或补发。
	 */
	private static void publishSyncPathTransition(
		ServerLevel sourceLevel,
		LinkSavedData savedData,
		LinkSavedData.LinkNode triggerSourceNode,
		LinkSavedData.LinkNode coreNode,
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot replaySnapshot,
		boolean beforeAllowed,
		boolean afterAllowed
	) {
		if (
			sourceLevel == null
				|| savedData == null
				|| triggerSourceNode == null
				|| coreNode == null
				|| replaySnapshot == null
				|| beforeAllowed == afterAllowed
		) {
			return;
		}
		EventMeta eventMeta = EventMeta.now(sourceLevel);
		if (beforeAllowed) {
			InternalDispatchDeltaEvents.publishSourceInvalidation(
				sourceLevel,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceNode.serial(),
				LinkNodeType.CORE,
				coreNode.serial(),
				eventMeta
			);
			return;
		}
		if (!TriggerSourceEffectiveActivationPolicy.isReplayEligibleSyncSource(sourceLevel, savedData, triggerSourceNode.serial())) {
			return;
		}
		InternalDispatchDeltaRuleSupport.publishSourceRebuildUpsertResolved(
			sourceLevel,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceNode.serial(),
			LinkNodeType.CORE,
			coreNode.serial(),
			eventMeta,
			replaySnapshot.signalStrength(),
			InternalDispatchDeltaEvents.DeliveryMode.ASYNC_BATCH
		);
	}

	/**
	 * 在 `SERVER_STARTED` 后对已加载过滤器补做一次非阻塞邻居输入采样。
	 * <p>
	 * 这样既避免在启动附着链路里同步取邻居输入，也能在服务端真正启动后尽快刷新
	 * `NEIGHBOR_MAX_INPUT` 过滤器的阈值快照。
	 * </p>
	 */
	static void refreshLoadedNeighborSignalSnapshotsAfterServerStarted(MinecraftServer server) {
		if (server == null) {
			return;
		}
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		for (PlacedLinkFilterSavedData.FilterEntry entry : PlacedLinkFilterSavedData.get(overworld).entriesSnapshot()) {
			if (!shouldRefreshNeighborSignalAfterServerStarted(entry)) {
				continue;
			}
			AbstractLinkFilterBlockEntity filterBlockEntity = resolveLoadedFilterBlockEntity(server, entry);
			if (filterBlockEntity == null) {
				continue;
			}
			refreshFilterWithCurrentNeighborSignal(filterBlockEntity);
		}
	}

	/**
	 * 判断条目是否需要在服务端启动后补采样邻居输入。
	 */
	static boolean shouldRefreshNeighborSignalAfterServerStarted(PlacedLinkFilterSavedData.FilterEntry entry) {
		return entry != null && entry.usesNeighborSignalThreshold();
	}

	/**
	 * 以非阻塞方式解析当前已加载的过滤器方块实体。
	 */
	private static AbstractLinkFilterBlockEntity resolveLoadedFilterBlockEntity(
		MinecraftServer server,
		PlacedLinkFilterSavedData.FilterEntry entry
	) {
		if (server == null || entry == null) {
			return null;
		}
		ServerLevel level = server.getLevel(entry.dimension());
		if (level == null) {
			return null;
		}
		LevelChunk chunk = level.getChunkSource().getChunkNow(entry.filterPos().getX() >> 4, entry.filterPos().getZ() >> 4);
		if (chunk == null) {
			return null;
		}
		BlockEntity blockEntity = chunk.getBlockEntity(entry.filterPos(), LevelChunk.EntityCreationType.CHECK);
		if (!(blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity)) {
			return null;
		}
		if (filterBlockEntity.filterKind() != entry.filterKind()) {
			return null;
		}
		return filterBlockEntity;
	}

	/**
	 * 统一兜底事件时间键，避免空入参污染协调事件。
	 */
	private static EventMeta normalizeEventMeta(ServerLevel level, EventMeta eventMeta) {
		return eventMeta == null ? EventMeta.now(level) : eventMeta;
	}

	/**
	 * 解析当前服务端共享的过滤器持久化真值实例。
	 */
	private static PlacedLinkFilterSavedData resolveSharedSavedData(MinecraftServer server) {
		if (server == null) {
			return null;
		}
		ServerLevel overworld = server.overworld();
		return overworld == null ? null : PlacedLinkFilterSavedData.get(overworld);
	}

	/**
	 * 解析当前服务端共享的连接与频道配置实例。
	 */
	private static LinkSavedData resolveSharedLinkSavedData(MinecraftServer server) {
		if (server == null) {
			return null;
		}
		ServerLevel overworld = server.overworld();
		return overworld == null ? null : LinkSavedData.get(overworld);
	}

	/**
	 * 将“节点当前连接模式”解析为过滤求值所需的目标模式和值。
	 */
	private static FilterEvaluationTarget resolveFilterEvaluationTarget(
		LinkSavedData linkSavedData,
		LinkFilterKind filterKind,
		long serial
	) {
		if (filterKind == null || serial <= 0L || linkSavedData == null) {
			return new FilterEvaluationTarget(LinkFilterTargetMode.SERIAL, Math.max(0L, serial));
		}
		LinkNodeType servicedNodeType = filterKind.servicedNodeType();
		if (linkSavedData.getConnectionMode(servicedNodeType, serial) == LinkConnectionMode.CHANNEL) {
			return new FilterEvaluationTarget(LinkFilterTargetMode.CHANNEL, linkSavedData.getChannel(servicedNodeType, serial));
		}
		return new FilterEvaluationTarget(LinkFilterTargetMode.SERIAL, serial);
	}

	/**
	 * 按优先维度解析一个可用于当前事件的服务端上下文。
	 */
	private static ServerLevel resolveEventContextLevel(MinecraftServer server, ResourceKey<Level> preferredDimension) {
		if (server == null) {
			return null;
		}
		ServerLevel preferredLevel = preferredDimension == null ? null : server.getLevel(preferredDimension);
		return preferredLevel == null ? server.overworld() : preferredLevel;
	}

	/**
	 * 测试专用：复位注册标记，避免多轮单测共享状态。
	 */
	static void resetForTesting() {
		callbacksRegistered = false;
	}

	/**
	 * 过滤求值所需的当前节点目标值。
	 */
	private record FilterEvaluationTarget(LinkFilterTargetMode targetMode, long targetValue) {
		private FilterEvaluationTarget {
			targetMode = targetMode == null ? LinkFilterTargetMode.SERIAL : targetMode;
			targetValue = Math.max(0L, targetValue);
		}
	}
}
