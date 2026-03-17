package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkConfig.CurrentLinksPrivacyMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * “当前连接”可见性与快照脱敏服务。
 */
public final class CurrentLinksPrivacyService {
	private CurrentLinksPrivacyService() {
	}

	/**
	 * 判定玩家是否可查看指定节点的“当前连接”明文。
	 *
	 * @param player 服务端玩家
	 * @param sourceType 来源节点类型（triggerSource/core）
	 * @param sourceSerial 来源序号
	 * @return true 表示可查看明文；false 表示应返回空值（"-"）
	 */
	public static boolean canViewCurrentLinks(ServerPlayer player, LinkNodeType sourceType, long sourceSerial) {
		if (player == null || sourceType == null || sourceSerial <= 0L) {
			return false;
		}

		CurrentLinksPrivacyMode mode = RedstoneLinkConfig.currentLinksPrivacyMode();
		if (mode == CurrentLinksPrivacyMode.HIDDEN) {
			return false;
		}
		if (mode == CurrentLinksPrivacyMode.PLAIN) {
			return true;
		}

		CurrentLinksPrivacySavedData privacySavedData = CurrentLinksPrivacySavedData.get(player.serverLevel());
		if (!privacySavedData.contains(sourceType, sourceSerial)) {
			return true;
		}
		return player.hasPermissions(RedstoneLinkConfig.currentLinksPrivacyViewPermissionLevel());
	}

	/**
	 * 解析“对当前玩家可见”的当前连接快照（升序去重）。
	 * <p>
	 * 用于配对 GUI 与近外显统一脱敏路径。
	 * </p>
	 */
	public static List<Long> resolveVisibleCurrentLinksSnapshot(
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> linkedTargets
	) {
		if (!canViewCurrentLinks(player, sourceType, sourceSerial)) {
			return List.of();
		}
		return normalizeAsSortedList(linkedTargets);
	}

	/**
	 * 解析可写入“物品 NBT 快照”的当前连接集合（升序去重）。
	 * <p>
	 * 物品 NBT 会被持有者客户端完整接收，因此对“全局不可见”的场景必须在服务端写入前脱敏。
	 * </p>
	 */
	public static Set<Long> resolveItemSnapshotTargets(
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> linkedTargets
	) {
		if (isCurrentLinksMaskedForItemSnapshot(level, sourceType, sourceSerial)) {
			return Set.of();
		}
		return normalizeAsSortedSet(linkedTargets);
	}

	/**
	 * 判定某节点“当前连接”是否应在物品快照中统一隐藏。
	 */
	private static boolean isCurrentLinksMaskedForItemSnapshot(ServerLevel level, LinkNodeType sourceType, long sourceSerial) {
		if (level == null || sourceType == null || sourceSerial <= 0L) {
			return true;
		}
		CurrentLinksPrivacyMode mode = RedstoneLinkConfig.currentLinksPrivacyMode();
		if (mode == CurrentLinksPrivacyMode.HIDDEN) {
			return true;
		}
		if (mode == CurrentLinksPrivacyMode.PLAIN) {
			return false;
		}
		return CurrentLinksPrivacySavedData.get(level).contains(sourceType, sourceSerial);
	}

	/**
	 * 归一化为升序列表：过滤非正数并去重。
	 */
	private static List<Long> normalizeAsSortedList(Set<Long> linkedTargets) {
		if (linkedTargets == null || linkedTargets.isEmpty()) {
			return List.of();
		}
		List<Long> normalized = new ArrayList<>(linkedTargets.size());
		for (Long value : linkedTargets) {
			if (value != null && value > 0L) {
				normalized.add(value);
			}
		}
		if (normalized.isEmpty()) {
			return List.of();
		}
		normalized.sort(Long::compareTo);
		long previous = Long.MIN_VALUE;
		List<Long> deduplicated = new ArrayList<>(normalized.size());
		for (long value : normalized) {
			if (value == previous) {
				continue;
			}
			deduplicated.add(value);
			previous = value;
		}
		return deduplicated.isEmpty() ? List.of() : List.copyOf(deduplicated);
	}

	/**
	 * 归一化为升序集合：过滤非正数并去重。
	 */
	private static Set<Long> normalizeAsSortedSet(Set<Long> linkedTargets) {
		List<Long> normalized = normalizeAsSortedList(linkedTargets);
		if (normalized.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(normalized));
	}
}
