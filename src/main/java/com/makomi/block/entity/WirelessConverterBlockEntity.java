package com.makomi.block.entity;

import com.makomi.menu.WirelessConverterMenu;
import com.makomi.recipe.WirelessConversionRecipe;
import com.makomi.registry.ModBlockEntities;
import com.makomi.registry.ModItems;
import com.makomi.registry.ModMenuTypes;
import com.makomi.registry.ModRecipeTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * 无线化转化器方块实体。
 * <p>
 * 仅允许使用红石连接原件作为燃料，
 * 并匹配自定义的无线化转化配方。
 * </p>
 */
public class WirelessConverterBlockEntity extends AbstractFurnaceBlockEntity {
	private static final Component CONTAINER_NAME = Component.translatable("container.redstonelink.wireless_converter");

	public WirelessConverterBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(ModBlockEntities.WIRELESS_CONVERTER, blockPos, blockState, ModRecipeTypes.WIRELESS_CONVERSION);
	}

	@Override
	protected Component getDefaultName() {
		return CONTAINER_NAME;
	}

	@Override
	protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
		return new WirelessConverterMenu(containerId, inventory, this, dataAccess);
	}

	@Override
	protected int getBurnDuration(ItemStack stack) {
		return stack.is(ModItems.REDSTONE_LINK_COMPONENT) ? 200 : 0;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (slot == 1) {
			return stack.is(ModItems.REDSTONE_LINK_COMPONENT);
		}
		return super.canPlaceItem(slot, stack);
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction direction) {
		if (slot == 1) {
			return stack.is(ModItems.REDSTONE_LINK_COMPONENT);
		}
		return super.canPlaceItemThroughFace(slot, stack, direction);
	}
}
