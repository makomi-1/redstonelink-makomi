package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class TriggerSourceBlockEntity extends PairableNodeBlockEntity {
	protected TriggerSourceBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	protected abstract LinkNodeType getTargetNodeType();

	public void triggerLinkedTargets(Player player) {
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

		int triggeredCount = 0;

		for (long targetSerial : linkedTargets) {
			LinkSavedData.LinkNode node = savedData.findNode(getTargetNodeType(), targetSerial).orElse(null);
			if (node == null) {
				continue;
			}

			ServerLevel targetLevel = serverLevel.getServer().getLevel(node.dimension());
			if (targetLevel == null || !targetLevel.hasChunkAt(node.pos())) {
				continue;
			}

			BlockEntity blockEntity = targetLevel.getBlockEntity(node.pos());
			if (blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity) {
				targetBlockEntity.triggerBySource(sourceSerial);
				triggeredCount++;
			} else {
				savedData.removeNode(getTargetNodeType(), targetSerial);
			}
		}

		if (triggeredCount == 0) {
			sendPlayerMessage(player, Component.translatable("message.redstonelink.no_reachable_targets"));
		}
	}

	private static void sendPlayerMessage(Player player, Component message) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(message);
		}
	}
}
