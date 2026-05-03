package com.makomi.menu;

import com.makomi.registry.ModMenuTypes;
import com.makomi.registry.ModItems;
import com.makomi.registry.ModRecipeTypes;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;

/**
 * 无线化转化器菜单。
 */
public class WirelessConverterMenu extends AbstractFurnaceMenu {
	public WirelessConverterMenu(int containerId, Inventory inventory) {
		super(ModMenuTypes.WIRELESS_CONVERTER, ModRecipeTypes.WIRELESS_CONVERSION, RecipeBookType.FURNACE, containerId, inventory);
	}

	public WirelessConverterMenu(int containerId, Inventory inventory, Container container, ContainerData data) {
		super(
			ModMenuTypes.WIRELESS_CONVERTER,
			ModRecipeTypes.WIRELESS_CONVERSION,
			RecipeBookType.FURNACE,
			containerId,
			inventory,
			container,
			data
		);
	}

	@Override
	protected boolean isFuel(ItemStack stack) {
		return stack.is(ModItems.REDSTONE_LINK_COMPONENT);
	}
}
