package com.makomi.data;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 派发过滤器查询门面。
 * <p>
 * 当前过滤器真值由 `PlacedLinkFilterSavedData` 持久化保存，过滤查询不再依赖区块是否已加载；
 * 该服务仅保留统一的 upsert/remove/query 入口，供派发主链和过滤器方块实体复用。
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
	 */
	public static void refreshFilterWithCurrentNeighborSignal(AbstractLinkFilterBlockEntity filterBlockEntity) {
		if (filterBlockEntity == null || !(filterBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		PlacedLinkFilterSavedData
			.get(serverLevel)
			.upsert(
				filterBlockEntity.filterKind(),
				serverLevel.dimension(),
				filterBlockEntity.getBlockPos(),
				filterBlockEntity.snapshot(),
				filterBlockEntity.sampleNeighborSignalStrength()
			);
	}

	/**
	 * 从持久化真值中移除一个过滤器。
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
		filterSavedData.remove(dimension, filterKind, filterPos);
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
			triggerSourceDimension,
			triggerSourcePos,
			triggerSourceSerial,
			signalStrength,
			LinkFilterKind.SEND
		)
			&& allowsByKind(filterSavedData, coreDimension, corePos, coreSerial, signalStrength, LinkFilterKind.RECEIVE);
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
		return allowsByKind(PlacedLinkFilterSavedData.get(level), level.dimension(), nodePos, serial, signalStrength, filterKind);
	}

	/**
	 * 基于已解析持久化真值的过滤种类求值入口。
	 */
	private static boolean allowsByKind(
		PlacedLinkFilterSavedData filterSavedData,
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
		return LinkFilterRuleEvaluator.allows(activeFilters, serial, signalStrength);
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
	 * 测试专用：复位注册标记，避免多轮单测共享状态。
	 */
	static void resetForTesting() {
		callbacksRegistered = false;
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
}
