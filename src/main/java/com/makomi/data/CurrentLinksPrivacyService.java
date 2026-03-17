package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkConfig.CurrentLinksPrivacyMode;
import com.makomi.util.SerialCollectionFormatUtil;
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
		if (player == null) {
			return false;
		}
		return canViewCurrentLinks(
			player.serverLevel(),
			sourceType,
			sourceSerial,
			player.hasPermissions(RedstoneLinkConfig.currentLinksPrivacyViewPermissionLevel())
		);
	}

	/**
	 * 判定命令或系统上下文是否可查看指定节点“当前连接”明文。
	 *
	 * @param level 服务端世界
	 * @param sourceType 来源节点类型（triggerSource/core）
	 * @param sourceSerial 来源序号
	 * @param hasViewPermission 是否具备查看受控连接权限
	 * @return true 表示可查看明文；false 表示应返回空值（"-"）
	 */
	public static boolean canViewCurrentLinks(
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		boolean hasViewPermission
	) {
		if (level == null || sourceType == null || sourceSerial <= 0L) {
			return false;
		}

		CurrentLinksPrivacyMode mode = RedstoneLinkConfig.currentLinksPrivacyMode();
		if (mode == CurrentLinksPrivacyMode.HIDDEN) {
			return false;
		}
		if (mode == CurrentLinksPrivacyMode.PLAIN) {
			return true;
		}

		CurrentLinksPrivacySavedData privacySavedData = CurrentLinksPrivacySavedData.get(level);
		if (!privacySavedData.contains(sourceType, sourceSerial)) {
			return true;
		}
		return hasViewPermission;
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
		if (player == null) {
			return List.of();
		}
		return resolveVisibleCurrentLinksSnapshot(
			player.serverLevel(),
			sourceType,
			sourceSerial,
			linkedTargets,
			player.hasPermissions(RedstoneLinkConfig.currentLinksPrivacyViewPermissionLevel())
		);
	}

	/**
	 * 解析“对当前上下文可见”的当前连接快照（升序去重）。
	 * <p>
	 * 用于命令读取等非玩家上下文，统一复用 GUI/近外显同一脱敏规则。
	 * </p>
	 *
	 * @param level 服务端世界
	 * @param sourceType 来源节点类型（triggerSource/core）
	 * @param sourceSerial 来源序号
	 * @param linkedTargets 原始目标集合
	 * @param hasViewPermission 是否具备查看受控连接权限
	 * @return 过滤后的可见目标序号列表
	 */
	public static List<Long> resolveVisibleCurrentLinksSnapshot(
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> linkedTargets,
		boolean hasViewPermission
	) {
		if (!canViewCurrentLinks(level, sourceType, sourceSerial, hasViewPermission)) {
			return List.of();
		}
		return filterVisibleTargetSerials(
			level,
			sourceType,
			linkedTargets,
			hasViewPermission
		);
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
		// 物品快照默认按“无查看权限”策略脱敏，避免高权限玩家写入后外流。
		List<Long> visibleTargets = filterVisibleTargetSerials(level, sourceType, linkedTargets, false);
		if (visibleTargets.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(visibleTargets));
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
	 * 过滤“当前连接”目标序号：
	 * <p>
	 * 1) 统一先做正数+去重+升序归一化；<br/>
	 * 2) 在 masked 模式下按目标节点加密名单逐项剔除；<br/>
	 * 3) 拥有查看权限时不过滤目标级受控项。
	 * </p>
	 *
	 * @param level 服务端世界
	 * @param sourceType 来源类型（用于推导目标类型）
	 * @param linkedTargets 原始目标集合
	 * @param allowMaskedTargets 是否允许显示目标级受控项
	 * @return 过滤后的不可变升序列表
	 */
	private static List<Long> filterVisibleTargetSerials(
		ServerLevel level,
		LinkNodeType sourceType,
		Set<Long> linkedTargets,
		boolean allowMaskedTargets
	) {
		List<Long> normalizedTargets = SerialCollectionFormatUtil.normalizePositiveDistinctSorted(linkedTargets);
		if (normalizedTargets.isEmpty()) {
			return List.of();
		}
		CurrentLinksPrivacyMode mode = RedstoneLinkConfig.currentLinksPrivacyMode();
		if (mode == CurrentLinksPrivacyMode.PLAIN) {
			return normalizedTargets;
		}
		if (mode == CurrentLinksPrivacyMode.HIDDEN) {
			return List.of();
		}
		if (allowMaskedTargets || level == null || sourceType == null) {
			return normalizedTargets;
		}

		LinkNodeType targetType = LinkNodeSemantics.resolveTargetTypeForSource(sourceType);
		if (targetType == null) {
			return List.of();
		}

		CurrentLinksPrivacySavedData privacySavedData = CurrentLinksPrivacySavedData.get(level);
		List<Long> filtered = new java.util.ArrayList<>(normalizedTargets.size());
		for (long targetSerial : normalizedTargets) {
			if (!privacySavedData.contains(targetType, targetSerial)) {
				filtered.add(targetSerial);
			}
		}
		return filtered.isEmpty() ? List.of() : List.copyOf(filtered);
	}

}
