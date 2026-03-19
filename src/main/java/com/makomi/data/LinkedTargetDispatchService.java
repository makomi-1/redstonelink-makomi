package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 已连接目标派发复用服务。
 * <p>
 * 统一封装“来源序号 -> 目标序号集合”的激活/同步派发流程，
 * 并在跨区块接管时返回可用于提示的汇总信息。
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
	 * @param sourceSerial 来源节点序号
	 * @param targetType 目标节点类型
	 * @param targetSerials 目标序号集合
	 * @param activationMode 激活模式
	 * @return 派发汇总
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
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}
		return dispatchInternal(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerials,
			DispatchKind.ACTIVATION,
			activationMode,
			0
		);
	}

	/**
	 * 派发同步语义（SYNC）到目标集合。
	 *
	 * @param sourceLevel 来源所在服务端维度
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源节点序号
	 * @param targetType 目标节点类型
	 * @param targetSerials 目标序号集合
	 * @param signalStrength 同步输入强度（0~15）
	 * @return 派发汇总
	 */
	public static DispatchSummary dispatchSyncSignal(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		Set<Long> targetSerials,
		int signalStrength
	) {
		int normalizedStrength = Math.max(0, Math.min(15, signalStrength));
		return dispatchInternal(
			sourceLevel,
			sourceType,
			sourceSerial,
			targetType,
			targetSerials,
			DispatchKind.SYNC_SIGNAL,
			ActivationMode.TOGGLE,
			normalizedStrength
		);
	}

	/**
	 * 按当前配置构建跨区块接管提示消息列表。
	 *
	 * @param summary 派发汇总
	 * @return 需发送给玩家的多行提示
	 */
	public static List<Component> buildCrossChunkNotifyMessages(DispatchSummary summary) {
		if (summary == null || !summary.hasForceLoadHandled()) {
			return List.of();
		}
		int displayLimit = RedstoneLinkConfig.crossChunkNotifyMode() == RedstoneLinkConfig.CrossChunkNotifyMode.DETAILED
			? 50
			: 3;
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("message.redstonelink.crosschunk.notify.header", summary.forceLoadHandledCount()));
		lines.add(
			Component.translatable(
				"message.redstonelink.crosschunk.notify.source",
				LinkNodeSemantics.toSemanticName(summary.sourceType()),
				summary.sourceSerial()
			)
		);
		if (!summary.forceLoadTargetSerials().isEmpty()) {
			lines.add(
				Component.translatable(
					"message.redstonelink.crosschunk.notify.force_load_targets",
					formatNotifyTargets(summary.targetType(), summary.forceLoadTargetSerials(), displayLimit)
				)
			);
		}
		return List.copyOf(lines);
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
		int syncSignalStrength
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
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetType, LinkNodeSemantics.Role.TARGET)) {
			return DispatchSummary.empty(sourceType, sourceSerial, targetType, targetSerials);
		}

		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		int handledCount = 0;
		List<Long> forceLoadTargetSerials = new ArrayList<>();
		List<Long> relayTargetSerials = new ArrayList<>();
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
				CrossChunkDispatchService.QueueResult queueResult = dispatchKind == DispatchKind.ACTIVATION
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
						syncSignalStrength
					);
				if (queueResult.accepted()) {
					handledCount++;
					if (queueResult.forceLoadPlanned()) {
						forceLoadTargetSerials.add(targetSerial);
					} else {
						relayTargetSerials.add(targetSerial);
					}
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
				targetBlockEntity.syncBySource(sourceSerial, syncSignalStrength);
			}
			handledCount++;
		}
		return new DispatchSummary(
			sourceType,
			sourceSerial,
			targetType,
			targetSerials.size(),
			handledCount,
			immutableSortedSerials(forceLoadTargetSerials),
			immutableSortedSerials(relayTargetSerials)
		);
	}

	/**
	 * 格式化同类型目标列表文本，并在超限时追加 `(+n)`。
	 */
	private static String formatNotifyTargets(LinkNodeType targetType, List<Long> serials, int displayLimit) {
		if (serials.isEmpty()) {
			return "-";
		}
		int showCount = Math.min(Math.max(1, displayLimit), serials.size());
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < showCount; index++) {
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(LinkNodeSemantics.toSemanticName(targetType)).append(":").append(serials.get(index));
		}
		int remain = serials.size() - showCount;
		if (remain > 0) {
			builder.append(" (+").append(remain).append(")");
		}
		return builder.toString();
	}

	/**
	 * 将序号列表转为不可变升序快照。
	 */
	private static List<Long> immutableSortedSerials(List<Long> serials) {
		return serials.stream().sorted().toList();
	}

	private enum DispatchKind {
		ACTIVATION,
		SYNC_SIGNAL
	}

	/**
	 * 派发统计快照。
	 *
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序号
	 * @param targetType 目标类型
	 * @param totalTargets 输入目标数量
	 * @param handledCount 成功派发/入队数量
	 * @param forceLoadTargetSerials 强加载链路接管目标
	 * @param relayTargetSerials 中继缓冲链路接管目标
	 */
	public record DispatchSummary(
		LinkNodeType sourceType,
		long sourceSerial,
		LinkNodeType targetType,
		int totalTargets,
		int handledCount,
		List<Long> forceLoadTargetSerials,
		List<Long> relayTargetSerials
	) {
		/**
		 * @return 强加载接管数量
		 */
		public int forceLoadHandledCount() {
			return forceLoadTargetSerials.size();
		}

		/**
		 * @return 是否发生强加载接管
		 */
		public boolean hasForceLoadHandled() {
			return forceLoadHandledCount() > 0;
		}

		/**
		 * @return 跨区块接管总数量（强加载 + 中继缓冲）
		 */
		public int crossChunkHandledCount() {
			return forceLoadTargetSerials.size() + relayTargetSerials.size();
		}

		/**
		 * @return 是否发生跨区块接管
		 */
		public boolean hasCrossChunkHandled() {
			return crossChunkHandledCount() > 0;
		}

		private static DispatchSummary empty(
			LinkNodeType sourceType,
			long sourceSerial,
			LinkNodeType targetType,
			Set<Long> targetSerials
		) {
			return new DispatchSummary(
				sourceType,
				sourceSerial,
				targetType,
				targetSerials == null ? 0 : targetSerials.size(),
				0,
				List.of(),
				List.of()
			);
		}
	}
}
