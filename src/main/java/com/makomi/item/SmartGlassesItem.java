package com.makomi.item;

import com.makomi.RedstoneLink;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 智能眼镜物品。
 * <p>
 * 复用头盔装备槽与原版头部外显链路，只承担“可佩戴显示入口”职责，
 * 不附带额外护甲属性，避免把展示型物品误做成真实防具。
 * </p>
 */
public class SmartGlassesItem extends ArmorItem {
	/**
	 * 智能眼镜专用护甲材质。
	 * <p>
	 * 这里只承担“把头盔渲染链路指向模组自定义 layer_1 贴图”的职责，
	 * 不复用铁头盔材质名，避免穿戴外显继续落回原版贴图。
	 * </p>
	 */
	private static final Holder<ArmorMaterial> SMART_GLASSES_MATERIAL = Holder.direct(
		new ArmorMaterial(
			createZeroDefenseMap(),
			0,
			SoundEvents.ARMOR_EQUIP_IRON,
			() -> Ingredient.EMPTY,
			List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "smart_glasses"))),
			0.0F,
			0.0F
		)
	);

	public SmartGlassesItem(ArmorItem.Type type, Item.Properties properties) {
		super(SMART_GLASSES_MATERIAL, type, properties);
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
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_glasses.face_vectors"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 为展示型头戴物品构造全零防御映射，避免材质层空表触发读取空值。
	 */
	private static EnumMap<ArmorItem.Type, Integer> createZeroDefenseMap() {
		EnumMap<ArmorItem.Type, Integer> defenseMap = new EnumMap<>(ArmorItem.Type.class);
		for (ArmorItem.Type armorType : ArmorItem.Type.values()) {
			defenseMap.put(armorType, 0);
		}
		return defenseMap;
	}
}
