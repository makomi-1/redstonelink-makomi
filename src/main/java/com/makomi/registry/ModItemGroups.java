package com.makomi.registry;

import com.makomi.RedstoneLink;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * 模组创造模式物品栏分组注册。
 */
public final class ModItemGroups {
	public static final CreativeModeTab REDSTONELINK = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB,
		id("redstonelink"),
		FabricItemGroup
			.builder()
			.title(Component.translatable("itemGroup.redstonelink"))
			.icon(() -> new ItemStack(ModItems.RL_ICON))
			.displayItems((parameters, output) -> {
				output.accept(ModItems.LINK_REDSTONE_CORE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_REDSTONE_CORE_TRANSPARENT, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_CORE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_REDSTONE_DUST_CORE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_REDSTONE_DUST_CORE_TRANSPARENT, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_TOGGLE_BUTTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_SYNC_LEVER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_PUSH_BUTTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_TOGGLE_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_TOGGLE_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_PULSE_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_PULSE_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_SYNC_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_SYNC_TRIGGER_SOURCE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_SEND_FILTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_SEND_FILTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_RECEIVE_FILTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_RECEIVE_FILTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_CHUNK_ACTIVATOR, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_CHUNK_ACTIVATOR, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_REPEATER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.HIDE_REPEATER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONELINK_TOGGLE_LINKER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONELINK_PULSE_LINKER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONELINK_SYNC_LINKER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.QUICK_LINK_TOOL, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.SMART_GLASSES, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.SMART_NODE_CONTAINER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.GRAPH_VISUAL_EDITOR, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.DIRECTIONAL_FACE_EDITOR, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONELINK_STATUS_PANEL, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONE_LINK_COMPONENT, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_CONVERTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_LEVER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_STONE_BUTTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_STONE_PRESSURE_PLATE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_PISTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_STICKY_PISTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_REDSTONE_LAMP, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_SEA_LANTERN, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.WIRELESS_OAK_DOOR, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
			})
			.build()
	);

	private ModItemGroups() {
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
