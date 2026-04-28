package com.makomi.network;

import com.makomi.data.HideDirectionalEditorToolData;
import com.makomi.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 定向面编辑器服务端接包处理壳。
 */
final class DirectionalFaceEditorNetworkServerHandlerSupport {
	private DirectionalFaceEditorNetworkServerHandlerSupport() {
	}

	/**
	 * 循环切换主手定向面编辑器模式，并同步即时提示。
	 */
	static void handleCycleMode(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ItemStack mainHandItem = player.getMainHandItem();
		if (mainHandItem.isEmpty() || mainHandItem.getItem() != ModItems.DIRECTIONAL_FACE_EDITOR) {
			return;
		}
		HideDirectionalEditorToolData.EditMode nextMode = HideDirectionalEditorToolData.cycleMode(mainHandItem);
		player.containerMenu.broadcastChanges();
		player.displayClientMessage(
			Component.translatable(
				"message.redstonelink.directional_face_editor.mode_switched",
				Component.translatable(nextMode.translationKey())
			),
			true
		);
	}
}
