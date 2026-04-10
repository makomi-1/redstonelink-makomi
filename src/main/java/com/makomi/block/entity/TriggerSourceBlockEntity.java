package com.makomi.block.entity;

import com.makomi.advancement.RedstoneLinkAdvancementService;
import com.makomi.data.LinkDispatchFilterService;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkedTargetDispatchService;
import com.makomi.util.SignalStrengths;
import com.makomi.config.RedstoneLinkConfig;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 触发源节点基类。
 * <p>
 * 负责将按钮、拉杆等触发源的序列号映射到目标节点集合，并逐个下发触发。
 * </p>
 */
public abstract class TriggerSourceBlockEntity extends PairableNodeBlockEntity {
	protected TriggerSourceBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	protected abstract LinkNodeType getTargetNodeType();

	// 触发源默认采用切换语义；脉冲按钮会覆盖为 PULSE（先激活后熄灭）。
	protected ActivationMode getTriggerActivationMode() {
		return ActivationMode.TOGGLE;
	}

	/**
	 * 触发当前源节点已连接的所有目标。
	 * <p>
	 * 若目标节点失效（找不到方块实体类型），会从在线表中移除该节点快照。
	 * </p>
	 */
	public void triggerLinkedTargets(Player player) {
		dispatchToLinkedTargets(player, DispatchMode.ACTIVATION, 0);
	}

	/**
	 * 同步转发输入信号到已绑定目标。
	 * <p>
	 * 该路径只同步目标开关态，不执行 TOGGLE/PULSE 语义转换。
	 * </p>
	 */
	public void forwardLinkedSignal(Player player, boolean signalOn) {
		forwardLinkedSignal(player, signalOn ? 15 : 0);
	}

	/**
	 * 同步转发输入强度到已绑定目标。
	 *
	 * @param player 触发玩家（可空）
	 * @param signalStrength 输入强度（会被归一到 0~15）
	 */
	public void forwardLinkedSignal(Player player, int signalStrength) {
		int normalizedStrength = SignalStrengths.clamp(signalStrength);
		ActivatableTargetBlockEntity.EventMeta eventMeta = ActivatableTargetBlockEntity.EventMeta.now(level);
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot = this instanceof SyncReplaySourceBlockEntity syncReplaySourceBlockEntity
			? syncReplaySourceBlockEntity.replaySyncSnapshot().orElse(null)
			: null;
		dispatchToLinkedTargets(player, DispatchMode.SYNC_SIGNAL, normalizedStrength, previousSnapshot, eventMeta);
		if (!(this instanceof SyncReplaySourceBlockEntity syncReplaySourceBlockEntity)) {
			return;
		}
		if (this instanceof LinkTriggerSourceBlockEntity triggerSourceBlockEntity && triggerSourceBlockEntity.isRuntimeInputRefreshInProgress()) {
			syncReplaySourceBlockEntity.recordRuntimeReplaySyncSnapshot(normalizedStrength, eventMeta);
			return;
		}
		syncReplaySourceBlockEntity.recordReplaySyncSnapshot(normalizedStrength, eventMeta);
	}

	private void dispatchToLinkedTargets(Player player, DispatchMode dispatchMode, int signalStrength) {
		dispatchToLinkedTargets(player, dispatchMode, signalStrength, null, ActivatableTargetBlockEntity.EventMeta.now(level));
	}

	private void dispatchToLinkedTargets(
		Player player,
		DispatchMode dispatchMode,
		int signalStrength,
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSyncSnapshot,
		ActivatableTargetBlockEntity.EventMeta eventMeta
	) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		long sourceSerial = getSerial();
		if (sourceSerial <= 0L) {
			sendPlayerMessage(player, Component.translatable("message.redstonelink.target_not_set"));
			return;
		}
		int effectiveSignalStrength = dispatchMode == DispatchMode.ACTIVATION ? 15 : SignalStrengths.clamp(signalStrength);
		if (!LinkDispatchFilterService.allowsSend(serverLevel, worldPosition, sourceSerial, effectiveSignalStrength)) {
			if (dispatchMode == DispatchMode.SYNC_SIGNAL) {
				LinkDispatchFilterService.reconcileBlockedSyncDispatchBySendFilter(
					serverLevel,
					worldPosition,
					sourceSerial,
					previousSyncSnapshot,
					eventMeta
				);
			}
			return;
		}

		LinkSavedData savedData = LinkSavedData.get(serverLevel);
		Set<Long> linkedTargets = savedData.getLinkedCoresByTriggerSource(sourceSerial);
		if (linkedTargets.isEmpty()) {
			sendPlayerMessage(player, Component.translatable("message.redstonelink.target_not_set"));
			return;
		}

		LinkedTargetDispatchService.DispatchSummary dispatchSummary = dispatchMode == DispatchMode.ACTIVATION
			? LinkedTargetDispatchService.dispatchActivation(
				serverLevel,
				getLinkNodeType(),
				sourceSerial,
				getTargetNodeType(),
				linkedTargets,
				getTriggerActivationMode()
			)
			: LinkedTargetDispatchService.dispatchSyncSignal(
				serverLevel,
				getLinkNodeType(),
				sourceSerial,
				worldPosition,
				getTargetNodeType(),
				linkedTargets,
				signalStrength,
				previousSyncSnapshot,
				eventMeta
			);

		if (dispatchSummary.handledCount() == 0) {
			sendPlayerMessage(player, Component.translatable("message.redstonelink.no_reachable_targets"));
			return;
		}
		if (dispatchMode == DispatchMode.ACTIVATION && player instanceof ServerPlayer serverPlayer) {
			RedstoneLinkAdvancementService.awardComeFindMeInTheEndIfMatched(
				serverPlayer,
				serverLevel.dimension(),
				dispatchSummary
			);
		}
		if (RedstoneLinkConfig.crossChunk().notifyEnabled() && dispatchSummary.hasCrossChunkHandled()) {
			for (Component line : LinkedTargetDispatchService.buildCrossChunkNotifyMessages(dispatchSummary)) {
				sendPlayerMessage(player, line);
			}
		}
	}

	private static void sendPlayerMessage(Player player, Component message) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(message);
		}
	}

	private enum DispatchMode {
		ACTIVATION,
		SYNC_SIGNAL
	}
}
