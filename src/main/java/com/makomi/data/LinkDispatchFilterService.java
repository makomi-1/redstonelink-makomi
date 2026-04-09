package com.makomi.data;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 派发过滤器运行时索引服务。
 * <p>
 * 仅维护内存态“维度 -> 过滤种类 -> chunkKey -> filterRefs”索引；
 * 真值配置仍只保存在过滤器方块实体 NBT 中，不写入 `SavedData`。
 * </p>
 */
public final class LinkDispatchFilterService {
	/**
	 * 过滤器立方域半径：以方块中心为中心，向六向各扩展 8 格。
	 */
	public static final int FILTER_RADIUS = 8;

	private static final Map<MinecraftServer, RuntimeState> STATE_BY_SERVER = new IdentityHashMap<>();

	private LinkDispatchFilterService() {
	}

	/**
	 * 注册服务端生命周期清理钩子。
	 */
	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			synchronized (STATE_BY_SERVER) {
				STATE_BY_SERVER.remove(server);
			}
		});
	}

	/**
	 * 上线或刷新一个过滤器的运行时索引。
	 *
	 * @param filterBlockEntity 过滤器方块实体
	 */
	public static void upsertFilter(AbstractLinkFilterBlockEntity filterBlockEntity) {
		if (filterBlockEntity == null || !(filterBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		RuntimeState runtimeState = getOrCreateState(serverLevel.getServer());
		FilterRegistrationKey registrationKey = new FilterRegistrationKey(
			serverLevel.dimension(),
			filterBlockEntity.filterKind(),
			filterBlockEntity.getBlockPos().immutable()
		);
		removeRegistration(runtimeState, registrationKey);
		FilterRegistration registration = new FilterRegistration(
			registrationKey,
			computeCoveredChunkKeys(filterBlockEntity.getBlockPos())
		);
		runtimeState.registrations.put(registrationKey, registration);
		for (long coveredChunkKey : registration.coveredChunkKeys()) {
			runtimeState.chunkIndex
				.computeIfAbsent(serverLevel.dimension(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(filterBlockEntity.filterKind(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(coveredChunkKey, ignored -> new LinkedHashSet<>())
				.add(registrationKey);
		}
	}

	/**
	 * 从运行时索引中移除一个过滤器。
	 *
	 * @param filterBlockEntity 过滤器方块实体
	 */
	public static void removeFilter(AbstractLinkFilterBlockEntity filterBlockEntity) {
		if (filterBlockEntity == null || !(filterBlockEntity.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		removeFilter(serverLevel.getServer(), serverLevel.dimension(), filterBlockEntity.filterKind(), filterBlockEntity.getBlockPos());
	}

	/**
	 * 从运行时索引中移除一个过滤器引用。
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
		RuntimeState runtimeState = getOrCreateState(server);
		removeRegistration(runtimeState, new FilterRegistrationKey(dimension, filterKind, filterPos.immutable()));
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
		List<LinkFilterRuleEvaluator.FilterRuntimeView> activeFilters = collectActiveFilters(level, nodePos, filterKind);
		return LinkFilterRuleEvaluator.allows(activeFilters, serial, signalStrength);
	}

	/**
	 * 收集当前节点命中的所有已激活过滤器视图。
	 */
	private static List<LinkFilterRuleEvaluator.FilterRuntimeView> collectActiveFilters(
		ServerLevel level,
		BlockPos nodePos,
		LinkFilterKind filterKind
	) {
		RuntimeState runtimeState = getOrCreateState(level.getServer());
		Map<LinkFilterKind, Map<Long, LinkedHashSet<FilterRegistrationKey>>> kindIndex = runtimeState.chunkIndex.get(level.dimension());
		if (kindIndex == null) {
			return List.of();
		}
		Map<Long, LinkedHashSet<FilterRegistrationKey>> chunkIndex = kindIndex.get(filterKind);
		if (chunkIndex == null) {
			return List.of();
		}
		LinkedHashSet<FilterRegistrationKey> candidates = chunkIndex.get(new ChunkPos(nodePos).toLong());
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		List<LinkFilterRuleEvaluator.FilterRuntimeView> activeFilters = new ArrayList<>(candidates.size());
		for (FilterRegistrationKey registrationKey : candidates) {
			BlockEntity blockEntity = level.getBlockEntity(registrationKey.filterPos());
			if (!(blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity)) {
				continue;
			}
			if (filterBlockEntity.filterKind() != filterKind) {
				continue;
			}
			if (!filterBlockEntity.covers(nodePos)) {
				continue;
			}
			LinkFilterRuleEvaluator.FilterRuntimeView runtimeView = filterBlockEntity.buildRuntimeViewIfEnabled();
			if (runtimeView != null) {
				activeFilters.add(runtimeView);
			}
		}
		return activeFilters.isEmpty() ? List.of() : List.copyOf(activeFilters);
	}

	/**
	 * 计算过滤器立方域会覆盖到的全部区块键。
	 */
	private static List<Long> computeCoveredChunkKeys(BlockPos filterPos) {
		int minChunkX = SectionPos.blockToSectionCoord(filterPos.getX() - FILTER_RADIUS);
		int maxChunkX = SectionPos.blockToSectionCoord(filterPos.getX() + FILTER_RADIUS);
		int minChunkZ = SectionPos.blockToSectionCoord(filterPos.getZ() - FILTER_RADIUS);
		int maxChunkZ = SectionPos.blockToSectionCoord(filterPos.getZ() + FILTER_RADIUS);
		List<Long> coveredChunkKeys = new ArrayList<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				coveredChunkKeys.add(new ChunkPos(chunkX, chunkZ).toLong());
			}
		}
		return List.copyOf(coveredChunkKeys);
	}

	/**
	 * 从索引与注册表中移除旧注册。
	 */
	private static void removeRegistration(RuntimeState runtimeState, FilterRegistrationKey registrationKey) {
		FilterRegistration previousRegistration = runtimeState.registrations.remove(registrationKey);
		if (previousRegistration == null) {
			return;
		}
		Map<LinkFilterKind, Map<Long, LinkedHashSet<FilterRegistrationKey>>> kindIndex = runtimeState.chunkIndex.get(
			registrationKey.dimension()
		);
		if (kindIndex == null) {
			return;
		}
		Map<Long, LinkedHashSet<FilterRegistrationKey>> chunkIndex = kindIndex.get(registrationKey.filterKind());
		if (chunkIndex == null) {
			return;
		}
		for (long coveredChunkKey : previousRegistration.coveredChunkKeys()) {
			LinkedHashSet<FilterRegistrationKey> registrations = chunkIndex.get(coveredChunkKey);
			if (registrations == null) {
				continue;
			}
			registrations.remove(registrationKey);
			if (registrations.isEmpty()) {
				chunkIndex.remove(coveredChunkKey);
			}
		}
		if (chunkIndex.isEmpty()) {
			kindIndex.remove(registrationKey.filterKind());
		}
		if (kindIndex.isEmpty()) {
			runtimeState.chunkIndex.remove(registrationKey.dimension());
		}
	}

	private static RuntimeState getOrCreateState(MinecraftServer server) {
		synchronized (STATE_BY_SERVER) {
			return STATE_BY_SERVER.computeIfAbsent(server, ignored -> new RuntimeState());
		}
	}

	/**
	 * 单个过滤器注册键。
	 */
	private record FilterRegistrationKey(ResourceKey<Level> dimension, LinkFilterKind filterKind, BlockPos filterPos) {}

	/**
	 * 单个过滤器的运行时注册记录。
	 */
	private record FilterRegistration(FilterRegistrationKey key, List<Long> coveredChunkKeys) {}

	/**
	 * 服务端运行时索引状态。
	 */
	private static final class RuntimeState {
		private final Map<FilterRegistrationKey, FilterRegistration> registrations = new LinkedHashMap<>();
		private final Map<ResourceKey<Level>, Map<LinkFilterKind, Map<Long, LinkedHashSet<FilterRegistrationKey>>>> chunkIndex =
			new LinkedHashMap<>();
	}
}
