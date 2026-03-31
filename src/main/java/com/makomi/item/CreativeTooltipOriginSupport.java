package com.makomi.item;

import com.makomi.RedstoneLink;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 创造模式 tooltip 归属补齐工具。
 * <p>
 * 原版创造模式会在 tooltip 第二行展示物品所属创造标签，但该逻辑依赖
 * “物品类型 + 组件”精确匹配。若本模组物品在持有阶段写入运行时组件，
 * 原版就可能丢失这条归属行。
 * </p>
 * <p>
 * 本工具仅在“创造模式 + 当前栈组件已偏离默认实例”时补齐同格式蓝色归属，
 * 以避免与原版正常显示场景发生重复。
 * </p>
 */
public final class CreativeTooltipOriginSupport {
	private static final String ITEM_GROUP_TRANSLATION_KEY = "itemGroup.redstonelink";

	private CreativeTooltipOriginSupport() {
	}

	/**
	 * 在创造模式 tooltip 中按原版位置补齐 RedstoneLink 归属行。
	 *
	 * @param stack 当前物品栈
	 * @param tooltipComponents 当前 tooltip 文本列表
	 * @param tooltipFlag tooltip 标记
	 */
	public static void appendRedstoneLinkOriginLineIfNeeded(
		ItemStack stack,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		if (!shouldAppendOriginLine(stack, tooltipComponents, tooltipFlag)) {
			return;
		}
		appendOriginLineIfMissing(tooltipComponents);
	}

	/**
	 * 判断当前物品是否属于本模组命名空间。
	 */
	public static boolean isRedstoneLinkItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return RedstoneLink.MOD_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
	}

	/**
	 * 当前物品栈是否已偏离默认实例组件。
	 */
	public static boolean differsFromDefaultInstance(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		ItemStack defaultStack = stack.getItem().getDefaultInstance();
		return !ItemStack.isSameItemSameComponents(stack, defaultStack);
	}

	/**
	 * tooltip 中是否已经存在蓝色 RedstoneLink 归属行。
	 */
	public static boolean hasOriginLine(List<Component> tooltipComponents) {
		return tooltipComponents != null && tooltipComponents.contains(buildOriginLine());
	}

	/**
	 * 直接在 tooltip 第 2 行补齐蓝色归属行。
	 */
	public static void appendOriginLineIfMissing(List<Component> tooltipComponents) {
		if (tooltipComponents == null || hasOriginLine(tooltipComponents)) {
			return;
		}
		int insertIndex = Math.min(1, tooltipComponents.size());
		tooltipComponents.add(insertIndex, buildOriginLine());
	}

	/**
	 * 判断当前是否需要补齐蓝色归属行。
	 */
	private static boolean shouldAppendOriginLine(
		ItemStack stack,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		if (tooltipFlag == null || !tooltipFlag.isCreative()) {
			return false;
		}
		if (!isRedstoneLinkItem(stack) || !differsFromDefaultInstance(stack)) {
			return false;
		}
		return !hasOriginLine(tooltipComponents);
	}

	/**
	 * 构建与原版创造模式一致的蓝色归属行。
	 */
	private static Component buildOriginLine() {
		return Component.translatable(ITEM_GROUP_TRANSLATION_KEY).withStyle(ChatFormatting.BLUE);
	}
}
