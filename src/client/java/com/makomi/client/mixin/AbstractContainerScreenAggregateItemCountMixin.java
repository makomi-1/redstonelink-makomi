package com.makomi.client.mixin;

import com.makomi.client.render.LinkItemAggregateDecorationDisplaySupport;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 原版容器槽位聚合数量角标 mixin。
 * <p>
 * 只在槽位角标/浮动物品角标调用点替换显示栈，避免外围补绘带来的图层错位。
 * </p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenAggregateItemCountMixin {
	/**
	 * 将普通槽位角标显示切换为聚合序号数量。
	 *
	 * @param stack 原版即将用于角标绘制的物品栈
	 * @return 保持真实语义不变的显示用物品栈
	 */
	@ModifyArg(
		method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"
		),
		index = 1
	)
	private ItemStack redstonelink$useAggregateCountForSlotDecoration(ItemStack stack) {
		return LinkItemAggregateDecorationDisplaySupport.createDecorationDisplayStack(stack);
	}

	/**
	 * 将鼠标手持浮动物品角标显示切换为聚合序号数量。
	 *
	 * @param stack 原版即将用于角标绘制的物品栈
	 * @return 保持真实语义不变的显示用物品栈
	 */
	@ModifyArg(
		method = "renderFloatingItem(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"
		),
		index = 1
	)
	private ItemStack redstonelink$useAggregateCountForFloatingDecoration(ItemStack stack) {
		return LinkItemAggregateDecorationDisplaySupport.createDecorationDisplayStack(stack);
	}
}
