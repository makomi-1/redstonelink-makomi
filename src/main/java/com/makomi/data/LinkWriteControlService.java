package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkConfig.LinkWriteControlMode;
import com.makomi.util.SerialCollectionFormatUtil;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 链接写入控制统一判定服务。
 * <p>
 * 统一收口以下策略：
 * <br/>1) 写入模式（full/limited/readonly）；
 * <br/>2) limited 模式下“最大设置量”限制；
 * <br/>3) 受控名单命中后的权限校验。
 * </p>
 */
public final class LinkWriteControlService {
	private LinkWriteControlService() {
	}

	/**
	 * 对玩家写入请求进行判定。
	 *
	 * @param player 玩家
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param affectedTargets 受影响目标集合（用于受控名单判定）
	 * @param setSize 本次“设置量”（用于 limited 模式阈值判定）
	 * @return 判定结果
	 */
	public static WriteDecision evaluateForPlayer(
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> affectedTargets,
		int setSize
	) {
		if (player == null) {
			return WriteDecision.denyReadonly(0);
		}
		return evaluate(
			player.serverLevel(),
			sourceType,
			sourceSerial,
			affectedTargets,
			setSize,
			player.hasPermissions(RedstoneLinkConfig.linkWriteLimitedPermissionLevel()),
			player.hasPermissions(RedstoneLinkConfig.linkWriteProtectedPermissionLevel())
		);
	}

	/**
	 * 对任意上下文写入请求进行判定。
	 *
	 * @param level 服务端世界
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param affectedTargets 受影响目标集合（用于受控名单判定）
	 * @param setSize 本次“设置量”（用于 limited 模式阈值判定）
	 * @param hasLimitedBypassPermission 是否具备 limited 模式越权能力
	 * @param hasProtectedBypassPermission 是否具备受控名单越权能力
	 * @return 判定结果
	 */
	public static WriteDecision evaluate(
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> affectedTargets,
		int setSize,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {
		LinkWriteControlMode mode = RedstoneLinkConfig.linkWriteControlMode();
		if (mode == LinkWriteControlMode.READONLY) {
			return WriteDecision.denyReadonly(RedstoneLinkConfig.commandPermissionLevel());
		}
		// full 模式语义：不受受控名单约束，直接放行。
		if (mode == LinkWriteControlMode.FULL) {
			return WriteDecision.allow();
		}

		int normalizedSetSize = Math.max(0, setSize);
		int limitedMaxSetSize = RedstoneLinkConfig.linkWriteLimitedMaxSetSize();
		if (mode == LinkWriteControlMode.LIMITED
			&& normalizedSetSize > limitedMaxSetSize
			&& !hasLimitedBypassPermission) {
			return WriteDecision.denyLimited(
				normalizedSetSize,
				limitedMaxSetSize,
				RedstoneLinkConfig.linkWriteLimitedPermissionLevel()
			);
		}

		if (level == null || sourceType == null || sourceSerial <= 0L) {
			return WriteDecision.allow();
		}

		LinkWriteProtectedSavedData protectedSavedData = LinkWriteProtectedSavedData.get(level);
		int requiredProtectedPermission = RedstoneLinkConfig.linkWriteProtectedPermissionLevel();
		if (protectedSavedData.contains(sourceType, sourceSerial) && !hasProtectedBypassPermission) {
			return WriteDecision.denyProtected(sourceType, sourceSerial, requiredProtectedPermission);
		}

		LinkNodeType targetType = LinkNodeSemantics.resolveTargetTypeForSource(sourceType);
		if (targetType == null) {
			return WriteDecision.allow();
		}

		List<Long> normalizedTargets = SerialCollectionFormatUtil.normalizePositiveDistinctSorted(affectedTargets);
		for (long targetSerial : normalizedTargets) {
			if (protectedSavedData.contains(targetType, targetSerial) && !hasProtectedBypassPermission) {
				return WriteDecision.denyProtected(targetType, targetSerial, requiredProtectedPermission);
			}
		}
		return WriteDecision.allow();
	}

	/**
	 * 写入拒绝原因。
	 */
	public enum DenyReason {
		READONLY,
		LIMITED_MAX_SET_SIZE,
		PROTECTED_SERIAL
	}

	/**
	 * 写入控制判定结果。
	 */
	public record WriteDecision(
		boolean allowed,
		DenyReason denyReason,
		int requiredPermissionLevel,
		int attemptedSetSize,
		int maxAllowedSetSize,
		LinkNodeType blockedType,
		long blockedSerial
	) {
		/**
		 * 返回“允许写入”结果。
		 */
		public static WriteDecision allow() {
			return new WriteDecision(true, null, 0, 0, 0, null, 0L);
		}

		/**
		 * 返回“只读模式拒绝”结果。
		 */
		public static WriteDecision denyReadonly(int requiredPermissionLevel) {
			return new WriteDecision(false, DenyReason.READONLY, requiredPermissionLevel, 0, 0, null, 0L);
		}

		/**
		 * 返回“limited 设置量超限”拒绝结果。
		 */
		public static WriteDecision denyLimited(int attemptedSetSize, int maxAllowedSetSize, int requiredPermissionLevel) {
			return new WriteDecision(
				false,
				DenyReason.LIMITED_MAX_SET_SIZE,
				requiredPermissionLevel,
				attemptedSetSize,
				maxAllowedSetSize,
				null,
				0L
			);
		}

		/**
		 * 返回“受控名单拒绝”结果。
		 */
		public static WriteDecision denyProtected(
			LinkNodeType blockedType,
			long blockedSerial,
			int requiredPermissionLevel
		) {
			return new WriteDecision(
				false,
				DenyReason.PROTECTED_SERIAL,
				requiredPermissionLevel,
				0,
				0,
				blockedType,
				blockedSerial
			);
		}
	}
}
