package com.makomi.client.mixin;

import com.makomi.client.render.LinkItemAggregateDecorationDisplaySupport;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 热键栏/副手槽聚合数量角标 mixin。
 * <p>
 * 仅替换传给原版 `renderItemDecorations(...)` 的显示栈，保留原版文字绘制层级、
 * 深度和偏移计算。
 * </p>
 */
@Mixin(Gui.class)
public abstract class GuiAggregateItemCountMixin {
	/**
	 * 将热键栏物品角标显示切换为聚合序号数量。
	 *
	 * @param stack 原版即将用于角标绘制的物品栈
	 * @return 保持真实语义不变的显示用物品栈
	 */
	@ModifyArg(
		method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V"
		),
		index = 1
	)
	private ItemStack redstonelink$useAggregateCountForHotbarDecoration(ItemStack stack) {
		return LinkItemAggregateDecorationDisplaySupport.createDecorationDisplayStack(stack);
	}
}
