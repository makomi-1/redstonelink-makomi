package com.makomi.network;

import com.makomi.data.SmartNodeContainerData;
import com.makomi.item.SmartNodeContainerItem;
import com.makomi.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 智能节点容器服务端接包处理壳。
 */
final class SmartNodeContainerNetworkServerHandlerSupport {
	private SmartNodeContainerNetworkServerHandlerSupport() {
	}

	/**
	 * 打开主手智能节点容器菜单。
	 */
	static void handleOpen(ServerPlayer player) {
		if (player == null) {
			return;
		}
		if (player.getMainHandItem().getItem() != ModItems.SMART_NODE_CONTAINER) {
			return;
		}
		SmartNodeContainerItem.openHeldMenu(player);
	}

	/**
	 * 循环切换当前放置类型，并即时同步侧面外显状态。
	 */
	static void handleCycleSelectedType(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ItemStack mainHandItem = player.getMainHandItem();
		if (mainHandItem.isEmpty() || mainHandItem.getItem() != ModItems.SMART_NODE_CONTAINER) {
			return;
		}
		SmartNodeContainerData.Snapshot snapshot = SmartNodeContainerData.cycleSelectedType(mainHandItem);
		player.containerMenu.broadcastChanges();
		player.displayClientMessage(
			Component.translatable(
				"message.redstonelink.smart_node_container.selected_type_switched",
				Component.translatable(snapshot.selectedType().translationKey())
			),
			true
		);
	}
}
