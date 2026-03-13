package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkedTargetDispatchService;
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
		dispatchToLinkedTargets(player, DispatchMode.ACTIVATION, false);
	}

	/**
	 * 同步转发输入信号到已绑定目标。
	 * <p>
	 * 该路径只同步目标开关态，不执行 TOGGLE/PULSE 语义转换。
	 * </p>
	 */
	public void forwardLinkedSignal(Player player, boolean signalOn) {
		dispatchToLinkedTargets(player, DispatchMode.SYNC_SIGNAL, signalOn);
	}

	private void dispatchToLinkedTargets(Player player, DispatchMode dispatchMode, boolean signalOn) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		long sourceSerial = getSerial();
		if (sourceSerial <= 0L) {
			sendPlayerMessage(player, Component.translatable("message.redstonelink.target_not_set"));
			return;
		}

		LinkSavedData savedData = LinkSavedData.get(serverLevel);
		Set<Long> linkedTargets = savedData.getLinkedCores(sourceSerial);
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
				getTargetNodeType(),
				linkedTargets,
				signalOn
			);

		if (dispatchSummary.handledCount() == 0) {
			sendPlayerMessage(player, Component.translatable("message.redstonelink.no_reachable_targets"));
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
