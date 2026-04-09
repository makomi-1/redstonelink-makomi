package com.makomi.data;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

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

	private LinkDispatchFilterService() {
	}

	/**
	 * 注册服务端钩子。
	 * <p>
	 * 过滤器真值已转为 `SavedData` 持久化，此处保留空实现以兼容既有初始化调用顺序。
	 * </p>
	 */
	public static void register() {
	}

	/**
	 * 写入或刷新一个已放置过滤器的持久化真值。
	 */
	public static void upsertFilter(AbstractLinkFilterBlockEntity filterBlockEntity) {
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
		ServerLevel contextLevel = server.overworld();
		if (contextLevel == null) {
			return;
		}
		PlacedLinkFilterSavedData.get(contextLevel).remove(dimension, filterKind, filterPos);
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
	 * 共用的按过滤种类求值入口。
	 */
	private static boolean allowsByKind(
		ServerLevel level,
		BlockPos nodePos,
		long serial,
		int signalStrength,
		LinkFilterKind filterKind
	) {
		if (level == null || nodePos == null || serial <= 0L || filterKind == null) {
			return true;
		}
		List<LinkFilterRuleEvaluator.FilterRuntimeView> activeFilters = PlacedLinkFilterSavedData
			.get(level)
			.collectFilters(level.dimension(), nodePos, filterKind);
		return LinkFilterRuleEvaluator.allows(activeFilters, serial, signalStrength);
	}
}
