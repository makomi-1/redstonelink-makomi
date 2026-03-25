package com.makomi.client.render;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import com.makomi.util.SerialCollectionFormatUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 近外显快照读取与缓存支持。
 * <p>
 * 负责服务端回包缓存、请求节流、懒请求与红石强度归一，不承担文案与绘制职责。
 * </p>
 */
final class LinkSerialHudOverlaySnapshotSupport {
	/**
	 * 同一节点“当前连接”请求最小间隔（毫秒），用于限流。
	 */
	private static final long CURRENT_LINKS_REQUEST_INTERVAL_MILLIS = 250L;
	/**
	 * “当前连接”快照缓存存活时长（毫秒）。
	 */
	private static final long CURRENT_LINKS_CACHE_TTL_MILLIS = 1500L;
	/**
	 * “当前连接”缓存最大条目数，超过后按最旧过期时间裁剪。
	 */
	private static final int CURRENT_LINKS_CACHE_MAX_ENTRIES = 256;
	/**
	 * 同一节点“最终 IO”请求最小间隔（毫秒），用于限流。
	 */
	private static final long RUNTIME_HUD_REQUEST_INTERVAL_MILLIS = 250L;
	/**
	 * “最终 IO”快照缓存存活时长（毫秒）。
	 * <p>
	 * 保持略高于轮询间隔即可，避免旧值在 HUD 上停留过久。
	 * </p>
	 */
	private static final long RUNTIME_HUD_CACHE_TTL_MILLIS = 500L;
	/**
	 * “最终 IO”缓存最大条目数，超过后按最旧过期时间裁剪。
	 */
	private static final int RUNTIME_HUD_CACHE_MAX_ENTRIES = 256;
	/**
	 * “当前连接”缓存（按维度+坐标+来源类型+来源序号）。
	 */
	private static final Map<OverlayTargetKey, CachedCurrentLinksSnapshot> currentLinksSnapshotCache = new HashMap<>();
	/**
	 * “当前连接”请求节流表（按维度+坐标+来源类型+来源序号）。
	 */
	private static final Map<OverlayTargetKey, Long> currentLinksRequestDeadlines = new HashMap<>();
	/**
	 * “最终 IO”缓存（按维度+坐标+来源类型+来源序号）。
	 */
	private static final Map<OverlayTargetKey, CachedRuntimeHudSnapshot> runtimeHudSnapshotCache = new HashMap<>();
	/**
	 * “最终 IO”请求节流表（按维度+坐标+来源类型+来源序号）。
	 */
	private static final Map<OverlayTargetKey, Long> runtimeHudRequestDeadlines = new HashMap<>();

	private LinkSerialHudOverlaySnapshotSupport() {
	}

	/**
	 * 接收服务端下发的“当前连接”快照并写入本地缓存。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPosLong 方块坐标压缩值
	 * @param sourceType 语义类型（triggerSource/core）
	 * @param sourceSerial 来源序号
	 * @param linkedTargets 可见目标列表（已脱敏）
	 */
	static void updateCurrentLinksSnapshot(
		String dimensionKey,
		long blockPosLong,
		String sourceType,
		long sourceSerial,
		List<Long> linkedTargets
	) {
		Optional<LinkNodeType> parsedType = LinkNodeSemantics.tryParseCanonicalType(sourceType);
		if (parsedType.isEmpty() || sourceSerial <= 0L || dimensionKey == null || dimensionKey.isBlank()) {
			return;
		}
		OverlayTargetKey targetKey = new OverlayTargetKey(dimensionKey, blockPosLong, parsedType.get(), sourceSerial);
		long now = System.currentTimeMillis();
		currentLinksSnapshotCache.put(
			targetKey,
			new CachedCurrentLinksSnapshot(now + CURRENT_LINKS_CACHE_TTL_MILLIS, normalizeLinkedTargets(linkedTargets))
		);
		currentLinksRequestDeadlines.remove(targetKey);
		trimCurrentLinksCacheIfNeeded();
	}

	/**
	 * 接收服务端下发的“最终 IO”快照并写入本地缓存。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPosLong 方块坐标压缩值
	 * @param sourceType 语义类型（triggerSource/core）
	 * @param sourceSerial 来源序号
	 * @param available 当前是否存在可读运行态
	 * @param inputPower 最终输入强度
	 * @param outputPower 最终输出强度
	 */
	static void updateRuntimeHudSnapshot(
		String dimensionKey,
		long blockPosLong,
		String sourceType,
		long sourceSerial,
		boolean available,
		int inputPower,
		int outputPower
	) {
		Optional<LinkNodeType> parsedType = LinkNodeSemantics.tryParseCanonicalType(sourceType);
		if (parsedType.isEmpty() || sourceSerial <= 0L || dimensionKey == null || dimensionKey.isBlank()) {
			return;
		}
		OverlayTargetKey targetKey = new OverlayTargetKey(dimensionKey, blockPosLong, parsedType.get(), sourceSerial);
		long now = System.currentTimeMillis();
		runtimeHudSnapshotCache.put(
			targetKey,
			new CachedRuntimeHudSnapshot(
				now + RUNTIME_HUD_CACHE_TTL_MILLIS,
				available,
				normalizeHudPower(inputPower),
				normalizeHudPower(outputPower)
			)
		);
		runtimeHudRequestDeadlines.remove(targetKey);
		trimRuntimeHudCacheIfNeeded();
	}

	/**
	 * 懒加载获取“当前连接”快照：优先读缓存，过期后按节流规则发起网络请求。
	 *
	 * @param pairableNodeBlockEntity 当前命中的可配对节点
	 * @param dimensionKey 当前客户端维度键
	 * @param blockPosLong 当前命中方块坐标压缩值
	 * @return 可直接显示的当前连接快照；缓存未命中时返回空列表
	 */
	static List<Long> resolveCurrentLinksSnapshotWithLazyRequest(
		PairableNodeBlockEntity pairableNodeBlockEntity,
		String dimensionKey,
		long blockPosLong
	) {
		if (pairableNodeBlockEntity == null || dimensionKey == null || dimensionKey.isBlank()) {
			return List.of();
		}
		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		long sourceSerial = pairableNodeBlockEntity.getSerial();
		if (nodeType == null || sourceSerial <= 0L) {
			return List.of();
		}

		long now = System.currentTimeMillis();
		cleanupExpiredCurrentLinksCache(now);
		OverlayTargetKey targetKey = new OverlayTargetKey(dimensionKey, blockPosLong, nodeType, sourceSerial);
		CachedCurrentLinksSnapshot cachedSnapshot = currentLinksSnapshotCache.get(targetKey);
		if (cachedSnapshot != null && cachedSnapshot.expireAtMillis() >= now) {
			return cachedSnapshot.linkedTargets();
		}

		requestCurrentLinksSnapshotIfAllowed(targetKey, now);
		return cachedSnapshot == null ? List.of() : cachedSnapshot.linkedTargets();
	}

	/**
	 * 懒加载获取“最终 IO”快照：优先读缓存，并在命中节点期间按节流周期主动轮询。
	 *
	 * @param pairableNodeBlockEntity 当前命中的可配对节点
	 * @param dimensionKey 当前客户端维度键
	 * @param blockPosLong 当前命中方块坐标压缩值
	 * @return 当前可显示的运行态快照；无快照时返回空模型
	 */
	static CachedRuntimeHudSnapshot resolveRuntimeHudSnapshotWithLazyRequest(
		PairableNodeBlockEntity pairableNodeBlockEntity,
		String dimensionKey,
		long blockPosLong
	) {
		if (pairableNodeBlockEntity == null || dimensionKey == null || dimensionKey.isBlank()) {
			return CachedRuntimeHudSnapshot.empty();
		}
		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		long sourceSerial = pairableNodeBlockEntity.getSerial();
		if (nodeType == null || sourceSerial <= 0L) {
			return CachedRuntimeHudSnapshot.empty();
		}

		long now = System.currentTimeMillis();
		cleanupExpiredRuntimeHudCache(now);
		OverlayTargetKey targetKey = new OverlayTargetKey(dimensionKey, blockPosLong, nodeType, sourceSerial);
		CachedRuntimeHudSnapshot cachedSnapshot = runtimeHudSnapshotCache.get(targetKey);
		requestRuntimeHudSnapshotIfAllowed(targetKey, now);
		if (cachedSnapshot != null && cachedSnapshot.expireAtMillis() >= now) {
			return cachedSnapshot;
		}
		return CachedRuntimeHudSnapshot.empty();
	}

	/**
	 * 按节流规则请求服务端下发“当前连接”快照。
	 */
	private static void requestCurrentLinksSnapshotIfAllowed(OverlayTargetKey targetKey, long now) {
		Long nextAllowedMillis = currentLinksRequestDeadlines.get(targetKey);
		if (nextAllowedMillis != null && nextAllowedMillis > now) {
			return;
		}
		currentLinksRequestDeadlines.put(targetKey, now + CURRENT_LINKS_REQUEST_INTERVAL_MILLIS);
		ClientPlayNetworking.send(
			new PairingNetwork.RequestCurrentLinksPayload(
				targetKey.dimensionKey(),
				targetKey.blockPosLong(),
				LinkNodeSemantics.toSemanticName(targetKey.nodeType()),
				targetKey.sourceSerial()
			)
		);
	}

	/**
	 * 按节流规则请求服务端下发“最终 IO”快照。
	 */
	private static void requestRuntimeHudSnapshotIfAllowed(OverlayTargetKey targetKey, long now) {
		Long nextAllowedMillis = runtimeHudRequestDeadlines.get(targetKey);
		if (nextAllowedMillis != null && nextAllowedMillis > now) {
			return;
		}
		runtimeHudRequestDeadlines.put(targetKey, now + RUNTIME_HUD_REQUEST_INTERVAL_MILLIS);
		ClientPlayNetworking.send(
			new PairingNetwork.RequestRuntimeHudSnapshotPayload(
				targetKey.dimensionKey(),
				targetKey.blockPosLong(),
				LinkNodeSemantics.toSemanticName(targetKey.nodeType()),
				targetKey.sourceSerial()
			)
		);
	}

	/**
	 * 清理过期缓存与过期请求节流记录。
	 */
	private static void cleanupExpiredCurrentLinksCache(long now) {
		currentLinksSnapshotCache.entrySet().removeIf(entry -> entry.getValue().expireAtMillis() < now);
		currentLinksRequestDeadlines.entrySet().removeIf(entry -> entry.getValue() < now);
	}

	/**
	 * 清理过期“最终 IO”缓存与请求节流记录。
	 */
	private static void cleanupExpiredRuntimeHudCache(long now) {
		runtimeHudSnapshotCache.entrySet().removeIf(entry -> entry.getValue().expireAtMillis() < now);
		runtimeHudRequestDeadlines.entrySet().removeIf(entry -> entry.getValue() < now);
	}

	/**
	 * 当缓存条目过多时按最旧过期时间裁剪，避免无限增长。
	 */
	private static void trimCurrentLinksCacheIfNeeded() {
		if (currentLinksSnapshotCache.size() <= CURRENT_LINKS_CACHE_MAX_ENTRIES) {
			return;
		}
		OverlayTargetKey oldestKey = null;
		long oldestExpireAt = Long.MAX_VALUE;
		for (Map.Entry<OverlayTargetKey, CachedCurrentLinksSnapshot> entry : currentLinksSnapshotCache.entrySet()) {
			long expireAtMillis = entry.getValue().expireAtMillis();
			if (expireAtMillis < oldestExpireAt) {
				oldestExpireAt = expireAtMillis;
				oldestKey = entry.getKey();
			}
		}
		if (oldestKey != null) {
			currentLinksSnapshotCache.remove(oldestKey);
			currentLinksRequestDeadlines.remove(oldestKey);
		}
	}

	/**
	 * 当“最终 IO”缓存条目过多时按最旧过期时间裁剪。
	 */
	private static void trimRuntimeHudCacheIfNeeded() {
		if (runtimeHudSnapshotCache.size() <= RUNTIME_HUD_CACHE_MAX_ENTRIES) {
			return;
		}
		OverlayTargetKey oldestKey = null;
		long oldestExpireAt = Long.MAX_VALUE;
		for (Map.Entry<OverlayTargetKey, CachedRuntimeHudSnapshot> entry : runtimeHudSnapshotCache.entrySet()) {
			long expireAtMillis = entry.getValue().expireAtMillis();
			if (expireAtMillis < oldestExpireAt) {
				oldestExpireAt = expireAtMillis;
				oldestKey = entry.getKey();
			}
		}
		if (oldestKey != null) {
			runtimeHudSnapshotCache.remove(oldestKey);
			runtimeHudRequestDeadlines.remove(oldestKey);
		}
	}

	/**
	 * 规范化网络下发目标序号：过滤非法值、升序去重。
	 */
	private static List<Long> normalizeLinkedTargets(List<Long> linkedTargets) {
		return SerialCollectionFormatUtil.normalizePositiveDistinctSorted(linkedTargets);
	}

	/**
	 * 归一化 HUD 强度值，限制在红石强度范围内。
	 */
	private static int normalizeHudPower(int power) {
		return Math.max(0, Math.min(15, power));
	}

	/**
	 * “当前连接”缓存键（维度 + 坐标 + 来源类型 + 来源序号）。
	 */
	record OverlayTargetKey(
		String dimensionKey,
		long blockPosLong,
		LinkNodeType nodeType,
		long sourceSerial
	) {
	}

	/**
	 * “当前连接”缓存值。
	 *
	 * @param expireAtMillis 过期时间戳
	 * @param linkedTargets 可见连接快照
	 */
	record CachedCurrentLinksSnapshot(long expireAtMillis, List<Long> linkedTargets) {
	}

	/**
	 * “最终 IO”缓存值。
	 *
	 * @param expireAtMillis 过期时间戳
	 * @param available 当前是否有可读运行态
	 * @param inputPower 最终输入强度
	 * @param outputPower 最终输出强度
	 */
	record CachedRuntimeHudSnapshot(long expireAtMillis, boolean available, int inputPower, int outputPower) {
		private static final CachedRuntimeHudSnapshot EMPTY = new CachedRuntimeHudSnapshot(0L, false, 0, 0);

		static CachedRuntimeHudSnapshot empty() {
			return EMPTY;
		}
	}
}
