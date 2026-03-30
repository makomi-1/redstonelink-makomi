package com.makomi.client.network;

import com.makomi.client.render.QuickLinkFeedbackOverlayRenderer;
import com.makomi.client.screen.QuickLinkToolScreen;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.network.QuickLinkNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 快速连接工具客户端接包与交互壳。
 */
public final class QuickLinkNetworkClientHandlerSupport {
	private static boolean collectTriggeredForCurrentAttack;
	private static boolean applyTriggeredForCurrentUse;

	private QuickLinkNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.OpenQuickLinkEditorPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openEditor(payload.snapshot()));
		});
		ClientPlayNetworking.registerGlobalReceiver(QuickLinkNetwork.QuickLinkFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() ->
				QuickLinkFeedbackOverlayRenderer.showFeedback(payload.success(), payload.messageKey(), payload.messageArgs())
			);
		});
	}

	/**
	 * 注册客户端 quick-link 交互回调。
	 */
	public static void registerInteractionCallbacks() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client == null || client.options == null) {
				collectTriggeredForCurrentAttack = false;
				applyTriggeredForCurrentUse = false;
				return;
			}
			if (!client.options.keyAttack.isDown()) {
				collectTriggeredForCurrentAttack = false;
			}
			if (!client.options.keyUse.isDown()) {
				applyTriggeredForCurrentUse = false;
			}
		});

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClientSide && shouldSendCollectRequest(player == null ? null : Minecraft.getInstance(), hand, pos)) {
				if (collectTriggeredForCurrentAttack) {
					return InteractionResult.FAIL;
				}
				ClientPlayNetworking.send(
					new QuickLinkNetwork.CollectQuickLinkPayload(
						world.dimension().location().toString(),
						pos.asLong()
					)
				);
				collectTriggeredForCurrentAttack = true;
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClientSide && shouldSendApplyRequest(player == null ? null : Minecraft.getInstance(), hand, hitResult.getBlockPos())) {
				if (applyTriggeredForCurrentUse) {
					return InteractionResult.FAIL;
				}
				ClientPlayNetworking.send(
					new QuickLinkNetwork.ApplyQuickLinkPayload(
						world.dimension().location().toString(),
						hitResult.getBlockPos().asLong()
					)
				);
				applyTriggeredForCurrentUse = true;
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
	 * 判断本次左键是否应发送“快速采集”请求。
	 */
	private static boolean shouldSendCollectRequest(Minecraft minecraft, InteractionHand hand, BlockPos blockPos) {
		return isQuickLinkTarget(minecraft, hand, blockPos, false);
	}

	/**
	 * 判断本次右键是否应发送“快速应用”请求。
	 */
	private static boolean shouldSendApplyRequest(Minecraft minecraft, InteractionHand hand, BlockPos blockPos) {
		return isQuickLinkTarget(minecraft, hand, blockPos, true);
	}

	/**
	 * 判断当前命中对象是否应交给 quick-link 交互链路处理。
	 */
	private static boolean isQuickLinkTarget(
		Minecraft minecraft,
		InteractionHand hand,
		BlockPos blockPos,
		boolean requireStanding
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null) {
			return false;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return false;
		}
		if (requireStanding && minecraft.player.isShiftKeyDown()) {
			return false;
		}
		BlockEntity blockEntity = minecraft.level.getBlockEntity(blockPos);
		return blockEntity instanceof com.makomi.block.entity.PairableNodeBlockEntity;
	}
}
