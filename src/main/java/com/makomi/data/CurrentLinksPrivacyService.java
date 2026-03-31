package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkConfig.CurrentLinksPrivacyMode;
import com.makomi.util.SerialCollectionFormatUtil;
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
	 * 判定玩家是否可读取指定节点的状态面板读模型。
	 * <p>
	 * 当前状态面板与“当前连接”来源节点读权限保持一致，
	 * 因此直接复用同一套 `hidden/masked/plain` + 权限等级规则。
	 * </p>
	 *
	 * @param player 服务端玩家
	 * @param nodeType 节点类型（triggerSource/core）
	 * @param serial 节点序号
	 * @return true 表示允许读取；false 表示应走隐藏态
	 */
	public static boolean canReadNodeState(ServerPlayer player, LinkNodeType nodeType, long serial) {
		return canViewCurrentLinks(player, nodeType, serial);
	}

	/**
	 * 判定当前上下文是否可读取指定节点的状态面板读模型。
	 *
	 * @param level 服务端世界
	 * @param nodeType 节点类型（triggerSource/core）
	 * @param serial 节点序号
	 * @param hasViewPermission 是否具备查看受控连接权限
	 * @return true 表示允许读取；false 表示应走隐藏态
	 */
	public static boolean canReadNodeState(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial,
		boolean hasViewPermission
	) {
		return canViewCurrentLinks(level, nodeType, serial, hasViewPermission);
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
			player.hasPermissions(RedstoneLinkConfig.privacy().viewPermissionLevel())
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

		CurrentLinksPrivacyMode mode = RedstoneLinkConfig.privacy().mode();
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
	 * 解析“对当前玩家可见”的当前连接视图快照。
	 */
	public static NodeLinksSnapshot resolveVisibleLinksSnapshot(
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> linkedTargets
	) {
		ServerLevel level = player == null ? null : player.serverLevel();
		boolean hasViewPermission = player != null
			&& player.hasPermissions(RedstoneLinkConfig.privacy().viewPermissionLevel());
		return resolveVisibleLinksSnapshot(level, sourceType, sourceSerial, linkedTargets, hasViewPermission);
	}

	/**
	 * 解析“对当前上下文可见”的当前连接视图快照。
	 */
	public static NodeLinksSnapshot resolveVisibleLinksSnapshot(
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> linkedTargets,
		boolean hasViewPermission
	) {
		return resolveVisibleLinksSnapshot(
			level,
			NodeIdentitySnapshot.resolve(level, sourceType, sourceSerial),
			sourceType,
			sourceSerial,
			linkedTargets,
			hasViewPermission
		);
	}

	/**
	 * 按当前上下文组装当前连接视图快照。
	 */
	public static NodeLinksSnapshot resolveVisibleLinksSnapshot(
		ServerLevel level,
		NodeIdentitySnapshot sourceIdentity,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> linkedTargets,
		boolean hasViewPermission
	) {
		NodeIdentitySnapshot normalizedIdentity = sourceIdentity == null
			? NodeIdentitySnapshot.resolve(level, sourceType, sourceSerial)
			: sourceIdentity;
		List<Long> normalizedTargets = SerialCollectionFormatUtil.normalizePositiveDistinctSorted(linkedTargets);
		if (!canViewCurrentLinks(level, sourceType, sourceSerial, hasViewPermission)) {
			return new NodeLinksSnapshot(normalizedIdentity, List.of(), true);
		}
		List<Long> visibleTargets = filterVisibleTargetSerials(level, sourceType, linkedTargets, hasViewPermission);
		boolean masked = visibleTargets.size() != normalizedTargets.size();
		return new NodeLinksSnapshot(normalizedIdentity, visibleTargets, masked);
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
		CurrentLinksPrivacyMode mode = RedstoneLinkConfig.privacy().mode();
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
