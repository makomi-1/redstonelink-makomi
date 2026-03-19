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
				output.accept(ModItems.LINK_REDSTONE_DUST_CORE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_REDSTONE_DUST_CORE_TRANSPARENT, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_TOGGLE_BUTTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_SYNC_LEVER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_PUSH_BUTTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_TOGGLE_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_PULSE_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_SYNC_EMITTER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONELINK_TOGGLE_LINKER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONELINK_PULSE_LINKER, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.REDSTONE_LINK_COMPONENT, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
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
