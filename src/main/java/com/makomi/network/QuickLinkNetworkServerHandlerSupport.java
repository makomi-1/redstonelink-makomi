package com.makomi.network;

import com.makomi.data.QuickLinkApplyService;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 快速连接工具服务端接包处理壳。
 */
final class QuickLinkNetworkServerHandlerSupport {
	private QuickLinkNetworkServerHandlerSupport() {
	}

	/**
	 * 处理客户端缓存保存请求。
	 */
	static void handleSaveQuickLink(ServerPlayer player, QuickLinkNetwork.SaveQuickLinkPayload payload) {
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof QuickLinkToolItem)) {
			return;
		}
		QuickLinkToolData.write(
			mainHandItem,
			QuickLinkToolData.fromTokens(
				payload.modeToken(),
				payload.serialCacheTypeToken(),
				payload.serialCacheExpression(),
				payload.channelCache()
			)
		);
		player.containerMenu.broadcastChanges();
	}

	/**
	 * 处理客户端左键应用请求。
	 */
	static void handleApplyQuickLink(ServerPlayer player, QuickLinkNetwork.ApplyQuickLinkPayload payload) {
		ItemStack mainHandItem = player.getMainHandItem();
		if (!(mainHandItem.getItem() instanceof QuickLinkToolItem)) {
			return;
		}
		if (!player.serverLevel().dimension().location().toString().equals(payload.dimensionKey())) {
			sendApplyResult(player, QuickLinkApplyService.ApplyResult.failure("message.redstonelink.quick_link.apply.invalid_target"));
			return;
		}

		BlockPos blockPos = BlockPos.of(payload.blockPosLong());
		if (!player.serverLevel().isLoaded(blockPos)) {
			sendApplyResult(player, QuickLinkApplyService.ApplyResult.failure("message.redstonelink.quick_link.apply.invalid_target"));
			return;
		}

		sendApplyResult(player, QuickLinkApplyService.apply(player, player.serverLevel(), blockPos, mainHandItem));
	}

	/**
	 * 将应用结果回传给客户端 HUD。
	 */
	private static void sendApplyResult(ServerPlayer player, QuickLinkApplyService.ApplyResult result) {
		ServerPlayNetworking.send(
			player,
			new QuickLinkNetwork.QuickLinkApplyResultPayload(result.success(), result.messageKey(), result.messageArgs())
		);
	}
}
