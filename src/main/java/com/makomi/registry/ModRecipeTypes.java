package com.makomi.registry;

import com.makomi.RedstoneLink;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * 模组配方类型注册表。
 */
public final class ModRecipeTypes {
	public static final RecipeType<com.makomi.recipe.WirelessConversionRecipe> WIRELESS_CONVERSION = Registry.register(
		BuiltInRegistries.RECIPE_TYPE,
		id("wireless_conversion"),
		new RecipeType<>() {
			@Override
			public String toString() {
				return RedstoneLink.MOD_ID + ":wireless_conversion";
			}
		}
	);

	private ModRecipeTypes() {}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
