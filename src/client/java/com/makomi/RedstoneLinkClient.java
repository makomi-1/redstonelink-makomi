package com.makomi;

import com.makomi.client.ClientHooks;
import com.makomi.client.screen.CorePairingScreen;
import com.makomi.client.screen.TriggerSourcePairingScreen;
import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import com.makomi.registry.ModBlocks;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;

/**
 * RedstoneLink 客户端入口。
 * <p>
 * 负责渲染层注册、本地 UI 打开钩子以及服务端配对包到界面的映射。
 * </p>
 */
public class RedstoneLinkClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		registerRenderLayers();
		registerPairingScreenOpeners();
		registerPairingPacketReceivers();
	}

	/**
	 * 注册需要透明/裁切渲染的方块层级。
	 */
	private static void registerRenderLayers() {
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE, RenderType.cutout());
	}

	/**
	 * 注册本地 GUI 打开入口。
	 */
	private static void registerPairingScreenOpeners() {
		ClientHooks.setPairingScreenOpener(hand -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null) {
				return;
			}
			minecraft.setScreen(new TriggerSourcePairingScreen(hand));
		});

		ClientHooks.setNodePairingScreenOpener((nodeType, nodeSerial, currentTargetSerial) -> {
			openPairingScreenBySourceType(nodeType, nodeSerial, List.of());
		});
	}

	/**
	 * 注册来自服务端的配对界面打开包。
	 */
	private static void registerPairingPacketReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.OpenTriggerSourcePairingPayload.TYPE, (payload, context) -> {
			// 网络线程切回客户端主线程后再操作 Screen。
			context.client().execute(() -> {
				openPairingScreenBySourceType(LinkNodeType.BUTTON, payload.sourceSerial(), payload.targets());
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.OpenCorePairingPayload.TYPE, (payload, context) -> {
			// 网络线程切回客户端主线程后再操作 Screen。
			context.client().execute(() -> {
				openPairingScreenBySourceType(LinkNodeType.CORE, payload.sourceSerial(), payload.targets());
			});
		});
	}

	/**
	 * 按来源类型打开配对界面。
	 * <p>
	 * triggerSource/core 语义映射保持不变：BUTTON 对应 triggerSource，CORE 对应 core。
	 * </p>
	 *
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序列号
	 * @param currentTargets 当前目标列表
	 */
	private static void openPairingScreenBySourceType(
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
}
