package com.makomi.item;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * 智能眼镜物品。
 * <p>
 * 复用头盔装备槽与原版头部外显链路，只承担“可佩戴显示入口”职责，
 * 不附带额外护甲属性，避免把展示型物品误做成真实防具。
 * </p>
 */
public class SmartGlassesItem extends ArmorItem {
	public SmartGlassesItem(
		net.minecraft.core.Holder<net.minecraft.world.item.ArmorMaterial> material,
		ArmorItem.Type type,
		Item.Properties properties
	) {
		super(material, type, properties);
	}

	@Override
	public ItemAttributeModifiers getDefaultAttributeModifiers() {
		return ItemAttributeModifiers.EMPTY;
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_glasses.overlay"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_glasses.visualize"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}
}
