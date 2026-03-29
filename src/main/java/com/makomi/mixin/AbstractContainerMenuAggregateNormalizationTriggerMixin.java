package com.makomi.mixin;

import com.makomi.data.PairableItemAggregateMenuNormalizationService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第二层聚合归并触发 mixin。
 * <p>
 * 只负责识别原版菜单点击命中并在返回后触发“即时处理”，
 * 不改写原版点击处理分支。
 * </p>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuAggregateNormalizationTriggerMixin {
	/**
	 * 在原版点击前仅转发“点击开始”时机。
	 */
	@Inject(method = "clicked", at = @At("HEAD"))
	private void redstonelink$captureClickContext(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
		PairableItemAggregateMenuNormalizationService.beginMenuClick((AbstractContainerMenu) (Object) this, slotId, button, clickType);
	}

	/**
	 * 在原版点击完成后仅转发“点击结束”时机。
	 */
	@Inject(method = "clicked", at = @At("RETURN"))
	private void redstonelink$requestNormalizationAfterClicked(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
		PairableItemAggregateMenuNormalizationService.finishMenuClick(
			(AbstractContainerMenu) (Object) this,
			slotId,
			button,
			clickType,
			player
		);
	}
}
