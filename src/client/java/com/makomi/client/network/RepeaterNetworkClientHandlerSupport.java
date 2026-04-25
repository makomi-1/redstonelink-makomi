package com.makomi.client.network;

import com.makomi.client.screen.RepeaterEditorScreen;
import com.makomi.network.RepeaterNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 转发器网络客户端接包壳。
 */
public final class RepeaterNetworkClientHandlerSupport {
	private RepeaterNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部转发器客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(RepeaterNetwork.OpenRepeaterEditorPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openEditor(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(RepeaterNetwork.RepeaterFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> showFeedback(payload.messageKey(), payload.messageArgs()));
		});
	}

	private static void openEditor(RepeaterNetwork.OpenRepeaterEditorPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || payload == null) {
			return;
		}
		minecraft.setScreen(
			new RepeaterEditorScreen(
				payload.targetKind(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.selectedSlot(),
				payload.serial(),
				payload.displayAlias(),
				payload.configSnapshot(),
				payload.inputDisplayTexts(),
				payload.outputDisplayTexts(),
				payload.expectedCoreRevision(),
				payload.expectedSourceRevision()
			)
		);
	}

	private static void showFeedback(String messageKey, java.util.List<String> messageArgs) {
		if (messageKey == null || messageKey.isBlank()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.player.displayClientMessage(
			Component.translatable(messageKey, (messageArgs == null ? java.util.List.<String>of() : messageArgs).toArray()),
			false
		);
	}
}
