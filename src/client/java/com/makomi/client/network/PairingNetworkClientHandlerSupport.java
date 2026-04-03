package com.makomi.client.network;

import com.makomi.client.render.LinkSerialHudOverlayRenderer;
import com.makomi.client.screen.CorePairingScreen;
import com.makomi.client.screen.TriggerSourcePairingScreen;
import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * `PairingNetwork` 的客户端接包与 UI 适配壳。
 * <p>
 * 该 helper 负责把服务端 payload 映射到本地 Screen/HUD 更新，避免客户端入口文件堆积网络细节。
 * </p>
 */
public final class PairingNetworkClientHandlerSupport {
	private PairingNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.OpenTriggerSourcePairingPayload.TYPE, (payload, context) -> {
			// 网络线程切回客户端主线程后再操作 Screen。
			context.client().execute(() -> openPairingScreenBySourceType(LinkNodeType.TRIGGER_SOURCE, payload.sourceSerial(), payload.targets()));
		});

		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.OpenCorePairingPayload.TYPE, (payload, context) -> {
			// 网络线程切回客户端主线程后再操作 Screen。
			context.client().execute(() -> openPairingScreenBySourceType(LinkNodeType.CORE, payload.sourceSerial(), payload.targets()));
		});

		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.PairingFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> showPairingFeedback(payload.messageKey(), payload.messageArgs()));
		});

		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.CurrentLinksSnapshotPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> LinkSerialHudOverlayRenderer.updateCurrentLinksSnapshot(
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				payload.targets()
			));
		});

		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.RuntimeHudSnapshotPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> LinkSerialHudOverlayRenderer.updateRuntimeHudSnapshot(
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				payload.available(),
				payload.inputPower(),
				payload.outputPower()
			));
		});
	}

	/**
	 * 按来源类型打开对应配对界面。
	 * <p>
	 * triggerSource/core 语义映射保持不变：`TRIGGER_SOURCE -> triggerSource`，`CORE -> core`。
	 * </p>
	 *
	 * @param sourceType 来源类型；为空时按 triggerSource 界面处理
	 * @param sourceSerial 来源序列号
	 * @param currentTargets 当前目标列表
	 */
	public static void openPairingScreenBySourceType(
		LinkNodeType sourceType,
		long sourceSerial,
		List<Long> currentTargets
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		if (sourceType == LinkNodeType.CORE) {
			minecraft.setScreen(new CorePairingScreen(sourceSerial, currentTargets));
			return;
		}
		minecraft.setScreen(new TriggerSourcePairingScreen(sourceSerial, currentTargets));
	}

	/**
	 * 将服务端配对反馈展示到客户端聊天栏。
	 */
	private static void showPairingFeedback(String messageKey, List<String> messageArgs) {
		if (messageKey == null || messageKey.isBlank()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		Object[] args = (messageArgs == null ? List.<String>of() : messageArgs).toArray();
		minecraft.player.displayClientMessage(Component.translatable(messageKey, args), false);
	}
}
