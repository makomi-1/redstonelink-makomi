package com.makomi.item;

import com.makomi.data.HideNodeSupport;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 隐藏节点物品 tooltip 支撑。
 * <p>
 * 统一在 tooltip 底部补充隐藏节点的灰色说明，
 * 避免不同 hide 物品类各自复制同一段文案。
 * </p>
 */
public final class HideNodeItemTooltipSupport {
	private HideNodeItemTooltipSupport() {
	}

	/**
	 * 若当前物品属于 hide 节点，则在 tooltip 底部追加统一灰色提示。
	 */
	public static void appendIfNeeded(ItemStack stack, List<Component> tooltipComponents) {
		if (stack == null || stack.isEmpty() || tooltipComponents == null) {
			return;
		}
		if (!HideNodeSupport.isHideNodeItem(stack.getItem())) {
			return;
		}
		tooltipComponents.add(
			Component.translatable("tooltip.redstonelink.hide_node.smart_glasses_required").withStyle(ChatFormatting.GRAY)
		);
		tooltipComponents.add(
			Component.translatable("tooltip.redstonelink.hide_node.directional_editor_required")
				.withStyle(ChatFormatting.GRAY)
		);
	}
}
