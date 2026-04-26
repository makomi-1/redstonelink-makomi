package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.menu.SmartNodeContainerMenu;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

/**
 * 模组菜单类型注册表。
 */
public final class ModMenuTypes {
	public static final MenuType<SmartNodeContainerMenu> SMART_NODE_CONTAINER = Registry.register(
		BuiltInRegistries.MENU,
		id("smart_node_container"),
		new MenuType<>(SmartNodeContainerMenu::new, FeatureFlags.DEFAULT_FLAGS)
	);

	private ModMenuTypes() {
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
