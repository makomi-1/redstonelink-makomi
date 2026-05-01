package com.makomi.item;

import com.makomi.RedstoneLink;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;

/**
 * 智能眼镜物品。
 * <p>
 * 复用头盔装备槽与原版头部外显链路，只承担“可佩戴显示入口”职责，
 * 不附带额外护甲属性，避免把展示型物品误做成真实防具。
 * </p>
 */
public class SmartGlassesItem extends Item {
	private static final TagKey<Item> SMART_GLASSES_REPAIR_TAG = TagKey.create(
		Registries.ITEM,
		Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "repairs_smart_glasses")
	);
	private static final net.minecraft.resources.ResourceKey<EquipmentAsset> SMART_GLASSES_ASSET = EquipmentAssets.createId(
		"smart_glasses"
	);
	/**
	 * 智能眼镜专用护甲材质。
	 * <p>
	 * 这里只承担“把头盔渲染链路指向模组自定义装备资产”的职责，
	 * 不复用铁头盔材质名，避免穿戴外显继续落回原版贴图。
	 * </p>
	 */
	private static final ArmorMaterial SMART_GLASSES_MATERIAL = new ArmorMaterial(
		0,
		java.util.Map.of(ArmorType.HELMET, 0),
		// 1.21.11 起 humanoidArmor(...) 会在构造期要求正附魔值；这里保留最小正数，避免改变“展示型装备”定位。
		1,
		SoundEvents.ARMOR_EQUIP_IRON,
		0.0F,
		0.0F,
		SMART_GLASSES_REPAIR_TAG,
		SMART_GLASSES_ASSET
	);

	public SmartGlassesItem(Item.Properties properties) {
		super(properties.humanoidArmor(SMART_GLASSES_MATERIAL, ArmorType.HELMET));
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
		java.util.function.Consumer<Component> tooltipAdder,
		TooltipFlag tooltipFlag
	) {
		List<Component> tooltipComponents = new java.util.ArrayList<>();
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_glasses.overlay"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_glasses.visualize"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_glasses.face_vectors"));
		tooltipComponents.forEach(tooltipAdder);
		super.appendHoverText(stack, context, tooltipDisplay, tooltipAdder, tooltipFlag);
	}
}
