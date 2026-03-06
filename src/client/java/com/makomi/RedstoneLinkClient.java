package com.makomi;

import com.makomi.client.ClientHooks;
import com.makomi.client.screen.CorePairingScreen;
import com.makomi.client.screen.LinkPairingScreen;
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
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT, RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE, RenderType.cutout());

		ClientHooks.setPairingScreenOpener(hand -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null) {
				return;
			}
			minecraft.setScreen(new LinkPairingScreen(hand));
		});

		ClientHooks.setNodePairingScreenOpener((nodeType, nodeSerial, currentTargetSerial) -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null) {
				return;
			}
			if (nodeType == LinkNodeType.CORE) {
				minecraft.setScreen(new CorePairingScreen(nodeSerial, List.of()));
			} else {
				minecraft.setScreen(new LinkPairingScreen(nodeSerial, List.of()));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.OpenButtonPairingPayload.TYPE, (payload, context) -> {
			// 网络线程切回客户端主线程后再操作 Screen。
			context.client().execute(() -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.player != null) {
					minecraft.setScreen(new LinkPairingScreen(payload.sourceSerial(), payload.targets()));
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(PairingNetwork.OpenCorePairingPayload.TYPE, (payload, context) -> {
			// 网络线程切回客户端主线程后再操作 Screen。
			context.client().execute(() -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.player != null) {
					minecraft.setScreen(new CorePairingScreen(payload.sourceSerial(), payload.targets()));
				}
			});
		});
	}
}
