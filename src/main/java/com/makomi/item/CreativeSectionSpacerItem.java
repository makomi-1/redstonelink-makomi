package com.makomi.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 创造模式分组隔离带空白占位物。
 * <p>
 * 该物品仅用于在同一创造标签内制造“空格”效果，
 * 不参与搜索、玩法、配方或任何交互语义。
 * </p>
 */
public class CreativeSectionSpacerItem extends Item {
	public CreativeSectionSpacerItem(Properties properties) {
		super(properties);
	}

	/**
	 * 返回空白名称，避免在悬停时干扰分组视觉。
	 */
	@Override
	public Component getName(ItemStack itemStack) {
		return Component.empty();
	}
}
