package com.makomi;

import com.makomi.client.ClientHooks;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.client.render.LinkCoreShortCodeRenderer;
import com.makomi.client.render.LinkSerialHudOverlayRenderer;
import com.makomi.client.screen.CorePairingScreen;
import com.makomi.client.screen.TriggerSourcePairingScreen;
import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import com.makomi.registry.ModBlockEntities;
import com.makomi.registry.ModBlocks;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;

/**
 * RedstoneLink 客户端入口。
 * <p>
 * 负责渲染层注册、本地 UI 打开钩子以及服务端配对包到界面的映射。
 * </p>
 */
public class RedstoneLinkClient implements ClientModInitializer {
	private static final String KEY_CATEGORY = "key.categories.redstonelink";
	private static final String KEY_TOGGLE_SERIAL_OVERLAY = "key.redstonelink.toggle_serial_overlay";
	private static KeyMapping toggleSerialOverlayKey;

	@Override
	public void onInitializeClient() {
		RedstoneLinkClientDisplayConfig.load();
		registerRenderLayers();
		registerBlockEntityRenderers();
		registerHudRenderers();
		registerClientKeyBindings();
		registerPairingScreenOpeners();
		registerPairingPacketReceivers();
		RedstoneLink.LOGGER.info("RedstoneLink client initialized");
	}

	/**
	 * 注册需要透明/裁切渲染的方块层级。
	 */
	private static void registerRenderLayers() {
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_CORE, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_TOGGLE_EMITTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_PULSE_EMITTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_SYNC_EMITTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE, RenderType.translucent());
	}

	/**
	 * 注册方块实体渲染器。
	 */
	private static void registerBlockEntityRenderers() {
		// 使用原版注册入口，避免依赖已废弃的 Fabric 渲染器注册 API。
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_CORE, LinkCoreShortCodeRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_CORE_TRANSPARENT, LinkCoreShortCodeRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_DUST_CORE, LinkCoreShortCodeRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_DUST_CORE_TRANSPARENT, LinkCoreShortCodeRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_TOGGLE_EMITTER, LinkCoreShortCodeRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_PULSE_EMITTER, LinkCoreShortCodeRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_SYNC_EMITTER, LinkCoreShortCodeRenderer::new);
	}

	/**
	 * 注册 HUD 序号外显渲染器。
	 */
	private static void registerHudRenderers() {
		HudRenderCallback.EVENT.register(LinkSerialHudOverlayRenderer::onHudRender);
	}

	/**
	 * 注册客户端按键：
	 * <p>
	 * `K` 键按“远 -> 近 -> 远+近 -> 关闭”切换序号外显模式，并将状态写回客户端配置。
	 * </p>
	 */
	private static void registerClientKeyBindings() {
		InputConstants.Key defaultToggleKey = RedstoneLinkClientDisplayConfig.serialOverlayToggleKey();
		toggleSerialOverlayKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping(
				KEY_TOGGLE_SERIAL_OVERLAY,
				defaultToggleKey.getType(),
				defaultToggleKey.getValue(),
				KEY_CATEGORY
			)
		);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleSerialOverlayKey.consumeClick()) {
				RedstoneLinkClientDisplayConfig.SerialOverlayMode mode = RedstoneLinkClientDisplayConfig.cycleSerialOverlayMode();
				if (client.player != null) {
					client.player.displayClientMessage(
						Component.translatable(mode.messageKey()),
						true
					);
				}
			}
		});
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
				openPairingScreenBySourceType(LinkNodeType.TRIGGER_SOURCE, payload.sourceSerial(), payload.targets());
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
	 * triggerSource/core 语义映射保持不变：TRIGGER_SOURCE 对应 triggerSource，CORE 对应 core。
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
