package com.makomi;

import com.makomi.client.ClientHooks;
import com.makomi.client.bench.BenchClientAutomationController;
import com.makomi.client.bench.BenchClientCommandBridge;
import com.makomi.client.network.BenchCommandNetworkClientHandlerSupport;
import com.makomi.client.network.LinkFilterNetworkClientHandlerSupport;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.client.network.PairingNetworkClientHandlerSupport;
import com.makomi.client.network.QuickLinkNetworkClientHandlerSupport;
import com.makomi.client.network.StatePanelNetworkClientHandlerSupport;
import com.makomi.client.render.LinkFilterAreaRenderer;
import com.makomi.client.render.LinkNodeFarOverlayRenderer;
import com.makomi.client.render.LinkSerialHudOverlayRenderer;
import com.makomi.client.render.QuickLinkOutlineRenderer;
import com.makomi.client.screen.TriggerSourcePairingScreen;
import com.makomi.client.web.LocalWebAppBridgeService;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.network.QuickLinkNetwork;
import com.makomi.registry.ModBlockEntities;
import com.makomi.registry.ModBlocks;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
	private static final String KEY_TOGGLE_QUICK_LINK_MODE = "key.redstonelink.toggle_quick_link_mode";
	private static final String CLIENT_DISPLAY_COMMAND_ROOT = "rlclient";
	private static KeyMapping toggleSerialOverlayKey;
	private static KeyMapping toggleQuickLinkModeKey;
	private static boolean quickLinkClearKeyWasDown;

	@Override
	public void onInitializeClient() {
		RedstoneLinkClientDisplayConfig.load();
		registerRenderLayers();
		registerBlockEntityRenderers();
		registerHudRenderers();
		registerClientKeyBindings();
		registerClientCommands();
		registerClientLifecycleHooks();
		registerPairingScreenOpeners();
		registerPairingPacketReceivers();
		registerBenchCommandClientHooks();
		registerQuickLinkClientHooks();
		registerStatePanelClientHooks();
		registerLinkFilterClientHooks();
		BenchClientAutomationController.initialize();
		RedstoneLink.LOGGER.info("RedstoneLink client initialized");
	}

	/**
	 * 注册需要透明/裁切渲染的方块层级。
	 */
	private static void registerRenderLayers() {
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_CORE, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_TOGGLE_BUTTON, RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_PUSH_BUTTON, RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_SYNC_LEVER, RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_TOGGLE_EMITTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_PULSE_EMITTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_SYNC_EMITTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_SEND_FILTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_RECEIVE_FILTER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE, RenderType.translucent());
	}

	/**
	 * 注册方块实体渲染器。
	 */
	private static void registerBlockEntityRenderers() {
		// 使用原版注册入口，避免依赖已废弃的 Fabric 渲染器注册 API。
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_CORE, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_CORE_TRANSPARENT, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_DUST_CORE, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_DUST_CORE_TRANSPARENT, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_TOGGLE_BUTTON, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_PUSH_BUTTON, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_SYNC_LEVER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_TOGGLE_EMITTER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_PULSE_EMITTER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_SYNC_EMITTER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_SEND_FILTER, LinkFilterAreaRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_RECEIVE_FILTER, LinkFilterAreaRenderer::new);
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
		InputConstants.Key defaultToggleKey = RedstoneLinkClientDisplayConfig.overlay().toggleKey();
		InputConstants.Key defaultQuickLinkToggleKey = RedstoneLinkClientDisplayConfig.quickLink().modeToggleKey();
		toggleSerialOverlayKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping(
				KEY_TOGGLE_SERIAL_OVERLAY,
				defaultToggleKey.getType(),
				defaultToggleKey.getValue(),
				KEY_CATEGORY
			)
		);
		toggleQuickLinkModeKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping(
				KEY_TOGGLE_QUICK_LINK_MODE,
				defaultQuickLinkToggleKey.getType(),
				defaultQuickLinkToggleKey.getValue(),
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

			while (toggleQuickLinkModeKey.consumeClick()) {
				handleQuickLinkModeKeyPress(client);
			}

			boolean quickLinkClearKeyDown = client.options.keyPickItem.isDown();
			if (quickLinkClearKeyDown && !quickLinkClearKeyWasDown) {
				handleQuickLinkApplyEditModeToggle(client);
			}
			quickLinkClearKeyWasDown = quickLinkClearKeyDown;
		});
	}

	/**
	 * 处理快速连接工具模式键。
	 */
	private static void handleQuickLinkModeKeyPress(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		if (client.player.isShiftKeyDown()) {
			handleQuickLinkClear(client);
			return;
		}
		handleQuickLinkModeToggle(client);
	}

	/**
	 * 处理快速连接工具模式切换按键。
	 */
	private static void handleQuickLinkModeToggle(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		if (client.screen != null) {
			return;
		}
		if (!(client.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return;
		}

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(client.player.getMainHandItem());
		QuickLinkToolData.Mode nextMode = snapshot.mode().next();

		ClientPlayNetworking.send(
			new QuickLinkNetwork.SaveQuickLinkPayload(
				nextMode.token(),
				com.makomi.data.LinkNodeSemantics.toSemanticName(snapshot.serialCacheType()),
				snapshot.serialCacheExpression(),
				snapshot.channelCache(),
				snapshot.applyEditMode().token()
			)
		);
		client.player.displayClientMessage(
			Component.translatable(
				"message.redstonelink.quick_link.mode_switched",
				Component.translatable(nextMode.translationKey())
			),
			true
		);
	}

	/**
	 * 处理潜行模式键触发的 quick-link 缓存清空。
	 */
	private static void handleQuickLinkClear(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		if (client.screen != null) {
			return;
		}
		if (!(client.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return;
		}

		QuickLinkToolData.Snapshot cleared = QuickLinkToolData.clearCaches(client.player.getMainHandItem());
		ClientPlayNetworking.send(
			new QuickLinkNetwork.SaveQuickLinkPayload(
				cleared.mode().token(),
				com.makomi.data.LinkNodeSemantics.toSemanticName(cleared.serialCacheType()),
				cleared.serialCacheExpression(),
				cleared.channelCache(),
				cleared.applyEditMode().token()
			)
		);
		client.player.displayClientMessage(Component.translatable("message.redstonelink.quick_link.cache_cleared"), true);
	}

	/**
	 * 处理中键切换 quick-link 应用编辑模式。
	 */
	private static void handleQuickLinkApplyEditModeToggle(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		if (client.screen != null) {
			return;
		}
		if (!(client.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return;
		}

		QuickLinkToolData.Snapshot nextSnapshot = QuickLinkToolData.cycleApplyEditMode(client.player.getMainHandItem());
		ClientPlayNetworking.send(
			new QuickLinkNetwork.SaveQuickLinkPayload(
				nextSnapshot.mode().token(),
				com.makomi.data.LinkNodeSemantics.toSemanticName(nextSnapshot.serialCacheType()),
				nextSnapshot.serialCacheExpression(),
				nextSnapshot.channelCache(),
				nextSnapshot.applyEditMode().token()
			)
		);
		client.player.displayClientMessage(
			Component.translatable(
				"message.redstonelink.quick_link.apply_edit_mode_switched",
				Component.translatable(nextSnapshot.applyEditMode().translationKey())
			),
			true
		);
	}

	/**
	 * 注册客户端独立命令根：
	 * <p>
	 * `/rlclient display far_overlay occluded|see_through` 仅影响本地显示配置，`/rlclient web open`
	 * 则用于打开本地离线网页工具，两者都不依赖服务端命令树。
	 * </p>
	 */
	private static void registerClientCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager
				.literal(CLIENT_DISPLAY_COMMAND_ROOT)
				.then(
					ClientCommandManager
						.literal("display")
						.then(
							ClientCommandManager
								.literal("far_overlay")
								.then(
									ClientCommandManager
										.literal("occluded")
										.executes(context -> executeSetFarOverlayDisplayMode(context, false))
								)
								.then(
									ClientCommandManager
										.literal("see_through")
										.executes(context -> executeSetFarOverlayDisplayMode(context, true))
								)
						)
				)
				.then(
					ClientCommandManager
						.literal("web")
						.then(
							ClientCommandManager
								.literal("open")
								.executes(RedstoneLinkClient::executeOpenWebApp)
						)
				)
		));
	}

	/**
	 * 注册客户端生命周期钩子，用于在退出时关闭本地网页桥接服务。
	 */
	private static void registerClientLifecycleHooks() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LocalWebAppBridgeService.stop());
	}

	/**
	 * 执行客户端远外显模式切换并即时反馈。
	 */
	private static int executeSetFarOverlayDisplayMode(
		CommandContext<FabricClientCommandSource> context,
		boolean seeThrough
	) {
		RedstoneLinkClientDisplayConfig.setFarOverlaySeeThroughEnabled(seeThrough);
		Component modeLabel = Component.translatable(
			seeThrough
				? "message.redstonelink.display.far_overlay.mode.see_through"
				: "message.redstonelink.display.far_overlay.mode.occluded"
		);
		context.getSource().sendFeedback(Component.translatable("message.redstonelink.display.far_overlay.updated", modeLabel));
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 打开本地离线网页工具首页。
	 */
	private static int executeOpenWebApp(CommandContext<FabricClientCommandSource> context) {
		try {
			URI homePageUri = LocalWebAppBridgeService.openHomePage();
			context.getSource().sendFeedback(Component.translatable("message.redstonelink.web.opened", homePageUri.toString()));
			return Command.SINGLE_SUCCESS;
		} catch (RuntimeException exception) {
			String reason = exception.getMessage() == null || exception.getMessage().isBlank()
				? exception.getClass().getSimpleName()
				: exception.getMessage();
			RedstoneLink.LOGGER.warn("打开本地网页工具失败", exception);
			context.getSource().sendFeedback(Component.translatable("message.redstonelink.web.open_failed", reason));
			return 0;
		}
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
			PairingNetworkClientHandlerSupport.openPairingScreenBySourceType(nodeType, nodeSerial, List.of());
		});
	}

	/**
	 * 注册来自服务端的配对界面打开包。
	 */
	private static void registerPairingPacketReceivers() {
		PairingNetworkClientHandlerSupport.registerReceivers();
	}

	/**
	 * 注册 bench 命令桥客户端接包器。
	 */
	private static void registerBenchCommandClientHooks() {
		BenchCommandNetworkClientHandlerSupport.registerReceivers();
		BenchClientCommandBridge.registerMessageHooks();
	}

	/**
	 * 注册快速连接工具客户端接包与渲染钩子。
	 */
	private static void registerQuickLinkClientHooks() {
		QuickLinkNetworkClientHandlerSupport.registerReceivers();
		QuickLinkNetworkClientHandlerSupport.registerInteractionCallbacks();
		QuickLinkOutlineRenderer.register();
	}

	/**
	 * 注册状态面板工具客户端接包。
	 */
	private static void registerStatePanelClientHooks() {
		StatePanelNetworkClientHandlerSupport.registerReceivers();
	}

	/**
	 * 注册过滤器编辑器客户端接包。
	 */
	private static void registerLinkFilterClientHooks() {
		LinkFilterNetworkClientHandlerSupport.registerReceivers();
	}
}
