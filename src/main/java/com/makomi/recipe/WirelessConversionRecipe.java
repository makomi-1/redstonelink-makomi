package com.makomi.recipe;

import com.makomi.registry.ModRecipeSerializers;
import com.makomi.registry.ModRecipeTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * 无线化转化器专用配方。
 */
public class WirelessConversionRecipe extends AbstractCookingRecipe {
	public WirelessConversionRecipe(
		String group,
		CookingBookCategory category,
		Ingredient ingredient,
		ItemStack result,
		float experience,
		int cookingTime
	) {
		super(ModRecipeTypes.WIRELESS_CONVERSION, group, category, ingredient, result, experience, cookingTime);
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return ModRecipeSerializers.WIRELESS_CONVERSION;
	}

	@Override
	public RecipeType<?> getType() {
		return ModRecipeTypes.WIRELESS_CONVERSION;
	}
}
