package com.makomi.item;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkedTargetDispatchService;
import com.makomi.data.NodeSnapshotQueryService;
import java.util.Set;
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
 * 2. 站立 + 主手右键 + 副手空：在 15/0 间切换并将当前同步强度派发到已连接 core。
 * </p>
 */
public class SyncLinkerItem extends LinkerItem {
	private static final int SIGNAL_OFF = 0;
	private static final int SIGNAL_ON = 15;

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
	 * 每次右键都在 `15/0` 两态之间切换，并复用同步拉杆的派发链路。
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

		int nextSignalStrength = LinkItemData.getSyncLinkerSignalStrength(stack) > SIGNAL_OFF ? SIGNAL_OFF : SIGNAL_ON;
		LinkItemData.setSyncLinkerSignalStrength(stack, nextSignalStrength);
		savedData.putTriggerSourceReplaySyncSnapshot(serial, EventMeta.now(level), nextSignalStrength);

		Set<Long> linkedTargets = savedData.getLinkedCores(serial);
		LinkItemData.setLinkedSerials(
			stack,
			NodeSnapshotQueryService.queryItemSnapshotLinks(serverLevel, LinkNodeType.TRIGGER_SOURCE, serial).visibleTargetSet()
		);
		if (linkedTargets.isEmpty()) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.target_not_set"));
			return;
		}

		LinkedTargetDispatchService.DispatchSummary dispatchSummary = LinkedTargetDispatchService.dispatchSyncSignal(
			serverLevel,
			LinkNodeType.TRIGGER_SOURCE,
			serial,
			LinkNodeType.CORE,
			linkedTargets,
			nextSignalStrength
		);
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
