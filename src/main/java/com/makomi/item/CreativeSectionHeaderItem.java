package com.makomi.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 创造模式物品栏分组头占位物品。
 * <p>
 * 该物品只用于在同一创造标签内做可见分组，
 * 不参与正常玩法、配方或放置逻辑。
 * </p>
 */
public class CreativeSectionHeaderItem extends Item {
	public CreativeSectionHeaderItem(Properties properties) {
		super(properties);
	}

	/**
	 * 统一给分组头加上发光外显，便于在创造页签里和普通物品区分。
	 */
	@Override
	public boolean isFoil(ItemStack itemStack) {
		return true;
	}

	/**
	 * 在悬停时明确说明当前物品仅用于创造模式分组。
	 */
	@Override
	public void appendHoverText(
		ItemStack itemStack,
		TooltipContext tooltipContext,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.creative_section_header").withStyle(ChatFormatting.GRAY));
		super.appendHoverText(itemStack, tooltipContext, tooltipComponents, tooltipFlag);
	}
}
