package com.makomi.client.mixin;

import com.makomi.item.CreativeTooltipOriginSupport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 创造模式 tooltip 归属补齐 mixin。
 * <p>
 * 在原版创造界面完成 tooltip 构建后，再对“组件已偏离默认实例”的
 * RedstoneLink 物品补齐蓝色归属行，覆盖序号分配等运行时写组件场景。
 * </p>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenOriginTooltipMixin {
	/**
	 * 在创造模式 tooltip 最终返回前补齐缺失的 RedstoneLink 归属行。
	 *
	 * @param stack 当前悬停物品栈
	 * @param cir 返回值回调
	 */
	@Inject(
		method = "getTooltipFromContainerItem(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
		at = @At("RETURN"),
		cancellable = true
	)
	private void redstonelink$appendOriginLineAfterTooltipBuilt(
		ItemStack stack,
		CallbackInfoReturnable<List<Component>> cir
	) {
		List<Component> tooltipComponents = cir.getReturnValue();
		if (!CreativeTooltipOriginSupport.isRedstoneLinkItem(stack)) {
			return;
		}
		if (!CreativeTooltipOriginSupport.differsFromDefaultInstance(stack)) {
			return;
		}
		if (CreativeTooltipOriginSupport.hasOriginLine(tooltipComponents)) {
			return;
		}
		List<Component> patchedTooltip = new ArrayList<>(tooltipComponents);
		CreativeTooltipOriginSupport.appendOriginLineIfMissing(patchedTooltip);
		cir.setReturnValue(patchedTooltip);
	}
}
