package com.makomi.registry;

import com.makomi.RedstoneLink;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModItemGroups {
	public static final CreativeModeTab REDSTONELINK = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB,
		id("redstonelink"),
		FabricItemGroup
			.builder()
			.title(Component.translatable("itemGroup.redstonelink"))
			.icon(() -> new ItemStack(ModItems.LINK_REDSTONE_CORE))
			.displayItems((parameters, output) -> {
				output.accept(ModItems.LINK_REDSTONE_CORE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_REDSTONE_DUST_CORE, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
				output.accept(ModItems.LINK_TOGGLE_BUTTON, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
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
		// Trigger class load to complete registration.
	}
}
