package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.recipe.WirelessConversionRecipe;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCookingSerializer;

/**
 * 模组配方序列化器注册表。
 */
public final class ModRecipeSerializers {
	public static final RecipeSerializer<WirelessConversionRecipe> WIRELESS_CONVERSION = Registry.register(
		BuiltInRegistries.RECIPE_SERIALIZER,
		id("wireless_conversion"),
		new SimpleCookingSerializer<>(WirelessConversionRecipe::new, 200)
	);

	private ModRecipeSerializers() {}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
