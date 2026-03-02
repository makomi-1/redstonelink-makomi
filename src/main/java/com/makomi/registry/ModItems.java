package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.item.PairableBlockItem;
import com.makomi.data.LinkNodeType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public final class ModItems {
	public static final Item REDSTONE_LINK_COMPONENT = register(
		"redstone_link_component",
		new Item(new Item.Properties())
	);

	public static final Item LINK_REDSTONE_CORE = register(
		"link_redstone_core",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_CORE, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_CORE_TRANSPARENT = register(
		"link_redstone_core_transparent",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_DUST_CORE, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		new PairableBlockItem(ModBlocks.LINK_TOGGLE_BUTTON, new Item.Properties().stacksTo(1), LinkNodeType.BUTTON)
	);

	public static final Item LINK_TOGGLE_LEVER = register(
		"link_toggle_lever",
		new PairableBlockItem(ModBlocks.LINK_TOGGLE_LEVER, new Item.Properties().stacksTo(1), LinkNodeType.BUTTON)
	);

	public static final Item LINK_PUSH_BUTTON = register(
		"link_push_button",
		new PairableBlockItem(ModBlocks.LINK_PUSH_BUTTON, new Item.Properties().stacksTo(1), LinkNodeType.BUTTON)
	);

	private ModItems() {
	}

	private static <T extends Item> T register(String path, T item) {
		return Registry.register(BuiltInRegistries.ITEM, id(path), item);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成注册。
	}
}
