package com.makomi.client.render;

import com.makomi.data.LinkItemData;
import net.minecraft.world.item.ItemStack;

/**
 * 聚合栈角标显示支持。
 * <p>
 * 不改真实 `ItemStack#getCount()`，只在原版 `renderItemDecorations(...)`
 * 调用前构造一个“显示用副本”，让原版数量角标继续按自身层级与时机绘制。
 * </p>
 */
public final class LinkItemAggregateDecorationDisplaySupport {
	private LinkItemAggregateDecorationDisplaySupport() {
	}

	/**
	 * 基于聚合序号数量生成角标显示用物品栈。
	 * <p>
	 * 返回值仅用于客户端角标渲染，不参与任何背包、同步或交互语义。
	 * </p>
	 *
	 * @param originalStack 原始物品栈
	 * @return 若为聚合栈则返回带显示数量的副本，否则返回原栈
	 */
	public static ItemStack createDecorationDisplayStack(ItemStack originalStack) {
		if (originalStack == null || originalStack.isEmpty()) {
			return originalStack;
		}
		int serialCount = LinkItemData.getSerialCount(originalStack);
		if (serialCount <= 1) {
			return originalStack;
		}
		int displayCount = Math.min(serialCount, LinkItemData.AGGREGATE_STACK_LIMIT);
		if (originalStack.getCount() == displayCount) {
			return originalStack;
		}
		return originalStack.copyWithCount(displayCount);
	}
}
