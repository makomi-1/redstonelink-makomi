package com.makomi.item;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkedTargetDispatchService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 同步遥控器物品。
 * <p>
 * 交互手势与现有遥控器保持一致：
 * 1. 潜行 + 主手右键：打开配对界面；
 * 2. 站立 + 主手右键 + 副手空：将当前设定同步强度派发到已连接 core。
 * </p>
 */
public class SyncLinkerItem extends LinkerItem {
	/**
	 * @param properties 物品属性
	 */
	public SyncLinkerItem(Item.Properties properties) {
		super(properties, ActivationMode.TOGGLE);
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
		// 兼容旧栈与缺失镜像组件场景，确保贴图随持久化状态对齐。
		LinkItemData.syncSyncLinkerModelState(stack);
		super.inventoryTick(stack, level, entity, slotId, isSelected);
	}

	@Override
	protected Component buildPrimaryUseTooltip() {
		return Component.translatable("tooltip.redstonelink.sync_linker");
	}

	@Override
	protected Component buildSignalSemanticTooltip() {
		return Component.translatable("tooltip.redstonelink.trigger_source.signal_semantic.state").withStyle(ChatFormatting.GRAY);
	}

	@Override
	protected boolean canExecutePrimaryUse(Player player, InteractionHand hand) {
		return super.canExecutePrimaryUse(player, hand);
	}

	@Override
	protected void executePrimaryUse(Level level, Player player, ItemStack stack) {
		syncLinkedTargets(level, player, stack);
	}

	/**
	 * 执行同步遥控器主动作。
	 * <p>
	 * 每次右键都按当前缓存强度发送一次同步信号，并复用同步拉杆的派发链路。
	 * </p>
	 */
	private static void syncLinkedTargets(Level level, Player player, ItemStack stack) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return;
		}

		LinkSavedData savedData = LinkSavedData.get(serverLevel);
		if (!savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, serial)) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.source_serial_unallocated", serial));
			return;
		}
		if (savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, serial)) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.source_serial_retired", serial));
			return;
		}

		int currentSignalStrength = LinkItemData.getSyncLinkerSignalStrength(stack);
		// 将旧栈中的异常值回写为规范化后的 `0~15` 真值，同时保持贴图镜像同步。
		LinkItemData.setSyncLinkerSignalStrength(stack, currentSignalStrength);
		savedData.putTriggerSourceReplaySyncSnapshot(serial, EventMeta.now(level), currentSignalStrength);

		LinkItemData.syncCurrentLinksSnapshotIfSingle(stack, serverLevel);
		LinkedTargetDispatchService.DispatchSummary dispatchSummary = LinkedTargetDispatchService.dispatchSyncSignal(
			serverLevel,
			LinkNodeType.TRIGGER_SOURCE,
			serial,
			LinkNodeType.CORE,
			currentSignalStrength
		);
		if (dispatchSummary.totalTargets() == 0) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.target_not_set"));
			return;
		}
		if (dispatchSummary.handledCount() == 0) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.no_reachable_targets"));
			return;
		}
		if (RedstoneLinkConfig.crossChunk().notifyEnabled() && dispatchSummary.hasCrossChunkHandled()) {
			for (Component line : LinkedTargetDispatchService.buildCrossChunkNotifyMessages(dispatchSummary)) {
				serverPlayer.sendSystemMessage(line);
			}
		}
	}
}
