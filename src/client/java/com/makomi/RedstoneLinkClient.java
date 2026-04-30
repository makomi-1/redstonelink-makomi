package com.makomi;

import com.makomi.client.ClientHooks;
import com.makomi.client.bench.BenchClientAutomationController;
import com.makomi.client.bench.BenchClientCommandBridge;
import com.makomi.client.network.BenchCommandNetworkClientHandlerSupport;
import com.makomi.client.network.ChunkActivatorNetworkClientHandlerSupport;
import com.makomi.client.network.LinkFilterNetworkClientHandlerSupport;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.client.network.PairingNetworkClientHandlerSupport;
import com.makomi.client.network.QuickLinkNetworkClientHandlerSupport;
import com.makomi.client.network.RepeaterNetworkClientHandlerSupport;
import com.makomi.client.network.StatePanelNetworkClientHandlerSupport;
import com.makomi.client.render.ChunkActivatorFarOverlayRenderer;
import com.makomi.client.render.DirectionalFaceVectorWorldOverlayRenderer;
import com.makomi.client.render.HideChunkActivatorGhostRenderer;
import com.makomi.client.render.HideFilterGhostRenderer;
import com.makomi.client.render.HideNodeGhostRenderer;
import com.makomi.client.render.LinkFilterAreaRenderer;
import com.makomi.client.render.LinkNodeFarOverlayRenderer;
import com.makomi.client.render.LinkSerialHudOverlayRenderer;
import com.makomi.client.render.QuickLinkWorldOverlayRenderer;
import com.makomi.client.screen.SmartNodeContainerScreen;
import com.makomi.client.screen.TriggerSourcePairingScreen;
import com.makomi.client.web.LocalWebAppBridgeService;
import com.makomi.data.LinkItemData;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.QuickLinkToolData;
import com.makomi.data.SmartGlassesAccessSupport;
import com.makomi.data.SmartNodeContainerData;
import com.makomi.data.SmartNodeContainerPlacementType;
import com.makomi.item.DirectionalFaceEditorItem;
import com.makomi.item.SyncLinkerItem;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.network.DirectionalFaceEditorNetwork;
import com.makomi.network.PairingNetwork;
import com.makomi.network.QuickLinkNetwork;
import com.makomi.network.SmartNodeContainerNetwork;
import com.makomi.network.StatePanelNetwork;
import com.makomi.registry.ModBlockEntities;
import com.makomi.registry.ModBlocks;
import com.makomi.registry.ModItems;
import com.makomi.registry.ModMenuTypes;
import com.makomi.util.SignalStrengths;
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
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;

/**
 * RedstoneLink 客户端入口。
 * <p>
 * 负责渲染层注册、本地 UI 打开钩子以及服务端配对包到界面的映射。
 * </p>
 */
public class RedstoneLinkClient implements ClientModInitializer {
	private static final String KEY_CATEGORY = "key.categories.redstonelink";
	private static final String KEY_TOGGLE_SERIAL_OVERLAY = "key.redstonelink.toggle_serial_overlay";
	private static final String KEY_TOGGLE_SMART_GLASSES_FACE_VECTORS =
		"key.redstonelink.toggle_smart_glasses_face_vectors";
	private static final String KEY_TOGGLE_QUICK_LINK_MODE = "key.redstonelink.toggle_quick_link_mode";
	private static final String CLIENT_DISPLAY_COMMAND_ROOT = "rlclient";
	private static KeyMapping toggleSerialOverlayKey;
	private static KeyMapping toggleSmartGlassesFaceVectorsKey;
	private static KeyMapping toggleQuickLinkModeKey;
	private static boolean pickItemKeyWasDown;
	private static long syncLinkerScrollHookWindowHandle;
	private static GLFWScrollCallback syncLinkerScrollCallback;
	private static GLFWScrollCallbackI previousSyncLinkerScrollCallback;

	@Override
	public void onInitializeClient() {
		RedstoneLinkClientDisplayConfig.load();
		registerRenderLayers();
		registerBlockEntityRenderers();
		registerMenuScreens();
		registerHudRenderers();
		registerClientKeyBindings();
		registerClientCommands();
		registerClientLifecycleHooks();
		registerPairingScreenOpeners();
		registerPairingPacketReceivers();
		registerBenchCommandClientHooks();
		registerQuickLinkClientHooks();
		registerDirectionalFaceVectorClientHooks();
		registerStatePanelClientHooks();
		registerLinkFilterClientHooks();
		registerChunkActivatorClientHooks();
		registerRepeaterClientHooks();
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
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_CHUNK_ACTIVATOR, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REPEATER, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.LINK_REDSTONE_DUST_CORE, RenderType.translucent());
	}

	/**
	 * 注册方块实体渲染器。
	 */
	private static void registerBlockEntityRenderers() {
		// 使用原版注册入口，避免依赖已废弃的 Fabric 渲染器注册 API。
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_CORE, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_CORE_TRANSPARENT, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_CORE, HideNodeGhostRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_DUST_CORE, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REDSTONE_DUST_CORE_TRANSPARENT, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_TOGGLE_BUTTON, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_PUSH_BUTTON, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_SYNC_LEVER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_TOGGLE_EMITTER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_TOGGLE_EMITTER, HideNodeGhostRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_PULSE_EMITTER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_PULSE_EMITTER, HideNodeGhostRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_SYNC_EMITTER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_SYNC_TRIGGER_SOURCE, HideNodeGhostRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_SEND_FILTER, LinkFilterAreaRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_SEND_FILTER, HideFilterGhostRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_RECEIVE_FILTER, LinkFilterAreaRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_RECEIVE_FILTER, HideFilterGhostRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_CHUNK_ACTIVATOR, ChunkActivatorFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_CHUNK_ACTIVATOR, HideChunkActivatorGhostRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.LINK_REPEATER, LinkNodeFarOverlayRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.HIDE_REPEATER, HideNodeGhostRenderer::new);
	}

	/**
	 * 注册 HUD 序号外显渲染器。
	 */
	private static void registerHudRenderers() {
		HudRenderCallback.EVENT.register(LinkSerialHudOverlayRenderer::onHudRender);
	}

	/**
	 * 注册 handled screen。
	 */
	private static void registerMenuScreens() {
		MenuScreens.register(ModMenuTypes.SMART_NODE_CONTAINER, SmartNodeContainerScreen::new);
	}

	/**
	 * 注册客户端按键：
	 * <p>
	 * `K` 键按“远 -> 近 -> 远+近 -> 关闭”切换序号外显模式，并将状态写回客户端配置。
	 * </p>
	 */
	private static void registerClientKeyBindings() {
		InputConstants.Key defaultToggleKey = RedstoneLinkClientDisplayConfig.overlay().toggleKey();
		InputConstants.Key defaultSmartGlassesFaceVectorToggleKey =
			RedstoneLinkClientDisplayConfig.overlay().faceVectorToggleKey();
		InputConstants.Key defaultQuickLinkToggleKey = RedstoneLinkClientDisplayConfig.quickLink().modeToggleKey();
		toggleSerialOverlayKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping(
				KEY_TOGGLE_SERIAL_OVERLAY,
				defaultToggleKey.getType(),
				defaultToggleKey.getValue(),
				KEY_CATEGORY
			)
		);
		toggleSmartGlassesFaceVectorsKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping(
				KEY_TOGGLE_SMART_GLASSES_FACE_VECTORS,
				defaultSmartGlassesFaceVectorToggleKey.getType(),
				defaultSmartGlassesFaceVectorToggleKey.getValue(),
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
			ensureSyncLinkerScrollHookInstalled(client);
			while (toggleSerialOverlayKey.consumeClick()) {
				if (Screen.hasControlDown()) {
					continue;
				}
				RedstoneLinkClientDisplayConfig.SerialOverlayMode mode = RedstoneLinkClientDisplayConfig.cycleSerialOverlayMode();
				if (client.player != null) {
					client.player.displayClientMessage(
						Component.translatable(mode.messageKey()),
						true
					);
				}
			}

			while (toggleSmartGlassesFaceVectorsKey.consumeClick()) {
				if (!Screen.hasControlDown()) {
					continue;
				}
				handleSmartGlassesFaceVectorToggle(client);
			}

			while (toggleQuickLinkModeKey.consumeClick()) {
				handleQuickLinkModeKeyPress(client);
			}

			boolean pickItemKeyDown = client.options.keyPickItem.isDown();
			if (pickItemKeyDown && !pickItemKeyWasDown) {
				handlePickItemShortcut(client);
			}
			pickItemKeyWasDown = pickItemKeyDown;
		});
	}

	/**
	 * 处理智能眼镜定向方向箭头显示开关。
	 */
	private static void handleSmartGlassesFaceVectorToggle(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		boolean enabled = RedstoneLinkClientDisplayConfig.toggleSmartGlassesFaceVectorEnabled();
		client.player.displayClientMessage(
			Component.translatable(
				enabled
					? "message.redstonelink.smart_glasses.face_vectors.enabled"
					: "message.redstonelink.smart_glasses.face_vectors.disabled"
			),
			true
		);
	}

	/**
	 * 处理快速连接工具模式键。
	 */
	private static void handleQuickLinkModeKeyPress(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		if (isHoldingSmartNodeContainer(client.player)) {
			handleSmartNodeContainerOpen(client);
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
		if (SmartGlassesAccessSupport.canOperateQuickLinkVisualization(client.player)) {
			QuickLinkNetworkClientHandlerSupport.clearVisualizedObjects();
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
	 * 处理中键快捷键：始终按主手物品类型决定切换对象。
	 */
	private static void handlePickItemShortcut(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		if (client.screen != null) {
			return;
		}
		if (isHoldingSmartNodeContainer(client.player)) {
			handleSmartNodeContainerTypeCycle(client);
			return;
		}
		if (client.player.getMainHandItem().getItem() instanceof QuickLinkToolItem) {
			handleQuickLinkApplyEditModeToggle(client);
			return;
		}
		if (!(client.player.getMainHandItem().getItem() instanceof DirectionalFaceEditorItem)) {
			return;
		}
		handleDirectionalFaceEditorModeCycle(client);
	}

	/**
	 * 处理中键切换 quick-link 应用编辑模式。
	 */
	private static void handleQuickLinkApplyEditModeToggle(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		if (QuickLinkNetworkClientHandlerSupport.isVisualizeMode(client)) {
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
	 * 确保客户端窗口已安装同步遥控器滚轮快捷调节钩子。
	 */
	private static void ensureSyncLinkerScrollHookInstalled(Minecraft client) {
		if (client == null || client.getWindow() == null) {
			return;
		}
		long windowHandle = client.getWindow().getWindow();
		if (windowHandle == 0L || windowHandle == syncLinkerScrollHookWindowHandle) {
			return;
		}
		if (syncLinkerScrollCallback == null) {
			syncLinkerScrollCallback = GLFWScrollCallback.create((callbackWindow, horizontalAmount, verticalAmount) -> {
				if (
					!handleSyncLinkerMouseScroll(Minecraft.getInstance(), horizontalAmount, verticalAmount)
						&& previousSyncLinkerScrollCallback != null
				) {
					previousSyncLinkerScrollCallback.invoke(callbackWindow, horizontalAmount, verticalAmount);
				}
			});
		}
		previousSyncLinkerScrollCallback = GLFW.glfwSetScrollCallback(windowHandle, syncLinkerScrollCallback);
		syncLinkerScrollHookWindowHandle = windowHandle;
	}

	/**
	 * 处理同步遥控器的 `Ctrl + 鼠标滚轮` 快捷调强度。
	 */
	private static boolean handleSyncLinkerMouseScroll(Minecraft client, double horizontalAmount, double verticalAmount) {
		if (client == null || client.player == null || client.screen != null || !Screen.hasControlDown()) {
			return false;
		}
		if (verticalAmount == 0.0D) {
			return false;
		}
		if (isHoldingSmartNodeContainer(client.player)) {
			return handleSmartNodeContainerMouseScroll(client, verticalAmount);
		}
		ItemStack mainHandItem = client.player.getMainHandItem();
		if (mainHandItem.isEmpty() || !(mainHandItem.getItem() instanceof SyncLinkerItem)) {
			return false;
		}
		int delta = verticalAmount > 0.0D ? 1 : -1;
		int currentSignalStrength = LinkItemData.getSyncLinkerSignalStrength(mainHandItem);
		int nextSignalStrength = SignalStrengths.clamp(currentSignalStrength + delta);
		LinkItemData.setSyncLinkerSignalStrength(mainHandItem, nextSignalStrength);
		if (client.getConnection() != null) {
			ClientPlayNetworking.send(
				new PairingNetwork.SaveSyncLinkerSignalStrengthPayload(LinkItemData.getSerial(mainHandItem), nextSignalStrength)
			);
		}
		client.player.displayClientMessage(
			Component.translatable(
				"message.redstonelink.sync_linker.signal_strength_changed",
				Integer.toString(nextSignalStrength)
			),
			true
		);
		return true;
	}

	/**
	 * 处理智能节点容器的 `Ctrl + 鼠标滚轮` 一次性临时选取。
	 */
	private static boolean handleSmartNodeContainerMouseScroll(Minecraft client, double verticalAmount) {
		if (client == null || client.player == null) {
			return false;
		}
		ItemStack mainHandItem = client.player.getMainHandItem();
		if (mainHandItem.isEmpty() || mainHandItem.getItem() != ModItems.SMART_NODE_CONTAINER) {
			return false;
		}
		SmartNodeContainerData.Snapshot snapshot = SmartNodeContainerData.read(mainHandItem);
		if (!snapshot.hasItems()) {
			return false;
		}
		List<Integer> candidateSlotIndexes = SmartNodeContainerData.findSlotsForType(
			SmartNodeContainerData.readContents(mainHandItem, client.player.registryAccess()),
			snapshot.selectedType()
		);
		if (candidateSlotIndexes.isEmpty()) {
			return false;
		}
		int delta = verticalAmount > 0.0D ? 1 : -1;
		int selectedSlotIndex = SmartNodeContainerData.cycleTemporarySelectedSlot(
			SmartNodeContainerData.readContents(mainHandItem, client.player.registryAccess()),
			snapshot.selectedType(),
			snapshot.temporarySelectedSlotIndex(),
			delta
		);
		if (selectedSlotIndex < 0) {
			return false;
		}
		SmartNodeContainerData.writeTemporarySelectedSlot(mainHandItem, selectedSlotIndex);
		ClientPlayNetworking.send(new SmartNodeContainerNetwork.SelectSmartNodeContainerSlotPayload(selectedSlotIndex));

		ItemStack selectedStack = SmartNodeContainerData.readContents(mainHandItem, client.player.registryAccess()).get(selectedSlotIndex);
		client.player.displayClientMessage(
			Component.translatable(
				"message.redstonelink.smart_node_container.temporary_selected",
				NodeAliasDisplayUtil.formatDisplayText(LinkItemData.getDisplayAlias(selectedStack), LinkItemData.getSerial(selectedStack))
			),
			true
		);
		return true;
	}

	/**
	 * 注册客户端独立命令根：
	 * <p>
	 * `/rlclient display far_overlay occluded|see_through` 仅影响本地显示配置；
	 * `/rlclient web graph` 用于请求导出当前可见 serial 图快照，并支持打开 graph 页面。
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
								.literal("graph")
								.executes(RedstoneLinkClient::executeExportGraphSnapshot)
								.then(
									ClientCommandManager
										.literal("open")
										.executes(RedstoneLinkClient::executeOpenGraphPage)
								)
								.then(
									ClientCommandManager
										.literal("export")
										.executes(RedstoneLinkClient::executeExportGraphSnapshot)
								)
						)
				)
		));
	}

	/**
	 * 注册客户端生命周期钩子，用于在退出时关闭本地网页桥接服务。
	 */
	private static void registerClientLifecycleHooks() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			LocalWebAppBridgeService.stop();
			// 窗口关闭阶段会统一释放 GLFW callbacks，这里只清理 Java 侧引用，避免重复释放同一个 native 回调。
			syncLinkerScrollCallback = null;
			previousSyncLinkerScrollCallback = null;
			syncLinkerScrollHookWindowHandle = 0L;
		});
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
	 * 打开本地 graph 独立拓扑分析页。
	 */
	private static int executeOpenGraphPage(CommandContext<FabricClientCommandSource> context) {
		try {
			URI graphPageUri = LocalWebAppBridgeService.openGraphPage();
			context.getSource().sendFeedback(Component.translatable("message.redstonelink.web.opened", graphPageUri.toString()));
			return Command.SINGLE_SUCCESS;
		} catch (RuntimeException exception) {
			String reason = exception.getMessage() == null || exception.getMessage().isBlank()
				? exception.getClass().getSimpleName()
				: exception.getMessage();
			RedstoneLink.LOGGER.warn("打开本地图快照网页失败", exception);
			context.getSource().sendFeedback(Component.translatable("message.redstonelink.web.open_failed", reason));
			return 0;
		}
	}

	/**
	 * 请求服务端导出当前玩家可见的 serial 图快照。
	 */
	private static int executeExportGraphSnapshot(CommandContext<FabricClientCommandSource> context) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.getConnection() == null) {
			context.getSource().sendFeedback(Component.translatable("message.redstonelink.graph.export.no_connection"));
			return 0;
		}
		ClientPlayNetworking.send(new StatePanelNetwork.ExportStatePanelGraphPayload(false));
		context.getSource().sendFeedback(Component.translatable("message.redstonelink.graph.export.requested"));
		return Command.SINGLE_SUCCESS;
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
		QuickLinkWorldOverlayRenderer.register();
	}

	/**
	 * 注册定向面箭头的世界后置渲染钩子。
	 */
	private static void registerDirectionalFaceVectorClientHooks() {
		DirectionalFaceVectorWorldOverlayRenderer.register();
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

	/**
	 * 注册区块激活器编辑器客户端接包。
	 */
	private static void registerChunkActivatorClientHooks() {
		ChunkActivatorNetworkClientHandlerSupport.registerReceivers();
	}

	/**
	 * 注册转发器编辑器客户端接包。
	 */
	private static void registerRepeaterClientHooks() {
		RepeaterNetworkClientHandlerSupport.registerReceivers();
	}

	/**
	 * 当前玩家主手是否持有智能节点容器。
	 */
	private static boolean isHoldingSmartNodeContainer(net.minecraft.world.entity.player.Player player) {
		return player != null && player.getMainHandItem().getItem() == ModItems.SMART_NODE_CONTAINER;
	}

	/**
	 * 处理智能节点容器的 `B` 键开箱逻辑。
	 */
	private static void handleSmartNodeContainerOpen(Minecraft client) {
		if (client == null || client.player == null || client.screen != null) {
			return;
		}
		ClientPlayNetworking.send(new SmartNodeContainerNetwork.OpenSmartNodeContainerPayload());
	}

	/**
	 * 处理中键切换智能节点容器当前放置类型。
	 */
	private static void handleSmartNodeContainerTypeCycle(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		SmartNodeContainerData.Snapshot snapshot = SmartNodeContainerData.cycleSelectedType(client.player.getMainHandItem());
		SmartNodeContainerPlacementType selectedType = snapshot.selectedType();
		ClientPlayNetworking.send(new SmartNodeContainerNetwork.CycleSmartNodeContainerTypePayload());
		client.player.displayClientMessage(
			Component.translatable(
				"message.redstonelink.smart_node_container.selected_type_switched",
				Component.translatable(selectedType.translationKey())
			),
			true
		);
	}

	/**
	 * 处理中键切换主手定向面编辑器模式。
	 */
	private static void handleDirectionalFaceEditorModeCycle(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		ClientPlayNetworking.send(new DirectionalFaceEditorNetwork.CycleDirectionalFaceEditorModePayload());
	}
}
