package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 链接目标派发复用服务。
 * <p>
 * 统一封装“来源序号 -> 目标序号集合”的激活/同步派发逻辑，
 * 供触发源方块与遥控器共用，避免链路分叉与重复实现。
 * </p>
 */
public final class LinkedTargetDispatchService {
	private LinkedTargetDispatchService() {
	}

	/**
	 * 派发激活语义（TOGGLE/PULSE）到目标集合。
	 *
	 * @param sourceLevel 来源所在服务端维度
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序号
	 * @param targetType 目标节点类型
	 * @param targetSerials 目标序号集合
	 * @param activationMode 激活模式
	 * @return 派发统计
	 */
	public static DispatchSummary dispatchActivation(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		ActivationMode activationMode
	) {
		if (activationMode == null) {
			return DispatchSummary.empty(targetSerials);
		}
		return dispatchInternal(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerials,
			DispatchKind.ACTIVATION,
			activationMode,
			false
		);
	}

	/**
	 * 派发同步语义（SYNC）到目标集合。
	 *
	 * @param sourceLevel 来源所在服务端维度
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序号
	 * @param targetType 目标节点类型
	 * @param targetSerials 目标序号集合
	 * @param signalOn 同步输入状态
	 * @return 派发统计
	 */
	public static DispatchSummary dispatchSyncSignal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		boolean signalOn
	) {
		return dispatchInternal(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerials,
			DispatchKind.SYNC_SIGNAL,
			ActivationMode.TOGGLE,
			signalOn
		);
	}

	/**
	 * 统一执行目标遍历与派发。
	 */
	private static DispatchSummary dispatchInternal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		DispatchKind dispatchKind,
		ActivationMode activationMode,
		boolean syncSignalOn
	) {
		if (
			sourceLevel == null
				|| sourceType == null
				|| targetType == null
				|| dispatchKind == null
				|| sourceSerial <= 0L
				|| targetSerials == null
				|| targetSerials.isEmpty()
		) {
			return DispatchSummary.empty(targetSerials);
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return DispatchSummary.empty(targetSerials);
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return DispatchSummary.empty(targetSerials);
		}

		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		int handledCount = 0;
		for (long targetSerial : targetSerials) {
			if (targetSerial <= 0L) {
				continue;
			}
			LinkSavedData.LinkNode node = savedData.findNode(targetType, targetSerial).orElse(null);
			if (node == null) {
				continue;
			}

			ServerLevel targetLevel = sourceLevel.getServer().getLevel(node.dimension());
			if (targetLevel == null || !targetLevel.isLoaded(node.pos())) {
				boolean queued = dispatchKind == DispatchKind.ACTIVATION
					? CrossChunkDispatchService.queueActivation(
						sourceLevel,
						node,
						sourceType,
						sourceSerial,
						activationMode
					)
					: CrossChunkDispatchService.queueSyncSignal(
						sourceLevel,
						node,
						sourceType,
						sourceSerial,
						syncSignalOn
					);
				if (queued) {
					handledCount++;
				}
				continue;
			}

			BlockEntity blockEntity = targetLevel.getBlockEntity(node.pos());
			if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
				// 节点快照与实际方块实体不一致时，清理脏在线节点记录。
				savedData.removeNode(targetType, targetSerial);
				continue;
			}

			if (dispatchKind == DispatchKind.ACTIVATION) {
				targetBlockEntity.triggerBySource(sourceSerial, activationMode);
			} else {
				targetBlockEntity.syncBySource(sourceSerial, syncSignalOn);
			}
			handledCount++;
		}
		return new DispatchSummary(targetSerials.size(), handledCount);
	}

	private enum DispatchKind {
		ACTIVATION,
		SYNC_SIGNAL
	}

	/**
	 * 派发统计快照。
	 *
	 * @param totalTargets 输入目标数量
	 * @param handledCount 成功派发/入队数量
	 */
	public record DispatchSummary(int totalTargets, int handledCount) {
		private static DispatchSummary empty(Set<Long> targetSerials) {
			return new DispatchSummary(targetSerials == null ? 0 : targetSerials.size(), 0);
		}
	}
}
