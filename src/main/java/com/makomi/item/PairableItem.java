package com.makomi.item;

import com.makomi.data.LinkNodeType;

/**
 * 可配对物品能力抽象。
 * <p>
 * 用于统一“可参与序号配对”的物品类型，避免业务代码依赖具体实现类。
 * </p>
 */
public interface PairableItem {
	/**
	 * 返回当前物品对应的节点类型。
	 */
	LinkNodeType getNodeType();
}
