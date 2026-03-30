package com.makomi.client.network;

import com.makomi.client.render.QuickLinkFeedbackOverlayRenderer;
import com.makomi.client.screen.QuickLinkToolScreen;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.network.QuickLinkNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 快速连接工具客户端接包与交互壳。
 */
public final class QuickLinkNetworkClientHandlerSupport {
	private QuickLinkNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.OpenQuickLinkEditorPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openEditor(payload.snapshot()));
		});
		ClientPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.QuickLinkApplyResultPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> QuickLinkFeedbackOverlayRenderer.showFeedback(
				payload.success(),
				payload.messageKey(),
				payload.messageArgs()
			));
		});
	}

	/**
	 * 注册客户端左键应用回调。
	 */
	public static void registerAttackCallback() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClientSide && shouldSendApplyRequest(player == null ? null : Minecraft.getInstance(), hand, pos)) {
				ClientPlayNetworking.send(
					new QuickLinkNetwork.ApplyQuickLinkPayload(
						world.dimension().location().toString(),
						pos.asLong()
					)
				);
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
	}

	/**
	 * 打开快速连接工具编辑界面。
	 */
	public static void openEditor(QuickLinkToolData.Snapshot snapshot) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.setScreen(new QuickLinkToolScreen(snapshot));
	}

	/**
	 * 判断本次左键是否应发送“快速应用”请求。
	 */
	private static boolean shouldSendApplyRequest(Minecraft minecraft, InteractionHand hand, net.minecraft.core.BlockPos blockPos) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null) {
			return false;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return false;
		}
		BlockEntity blockEntity = minecraft.level.getBlockEntity(blockPos);
		return blockEntity instanceof com.makomi.block.entity.PairableNodeBlockEntity;
	}
}
