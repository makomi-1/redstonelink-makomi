package com.makomi.data;

import com.makomi.item.PairableItem;
import com.makomi.registry.ModItems;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 智能节点容器销毁退役支撑。
 * <p>
 * 当容器物品实体被真正销毁时，内部节点物品不会再掉出，而是统一递归走现有节点退役主流程，
 * 避免留下“已分配但无载体”的幽灵序号。
 * </p>
 */
public final class SmartNodeContainerRetireSupport {
	private SmartNodeContainerRetireSupport() {
	}

	/**
	 * 判断给定物品栈是否为智能节点容器。
	 */
	public static boolean isSmartNodeContainerStack(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() == ModItems.SMART_NODE_CONTAINER;
	}

	/**
	 * 递归退役智能节点容器内部节点。
	 *
	 * @return 本次实际产生可见退役变更的节点数量
	 */
	public static int retireContainedNodes(ServerLevel level, ItemStack containerStack) {
		if (level == null || !isSmartNodeContainerStack(containerStack)) {
			return 0;
		}
		return retireContainedNodeStacks(level, SmartNodeContainerData.readContents(containerStack, level.registryAccess()));
	}

	/**
	 * 基于一组已解析的内部节点栈执行递归退役。
	 * <p>
	 * 本方法保留包级可见，便于单测直接绕过容器 NBT 编码细节。
	 * </p>
	 */
	static int retireContainedNodeStacks(ServerLevel level, Iterable<ItemStack> containedStacks) {
		if (level == null || containedStacks == null) {
			return 0;
		}
		return retireTargets(level, collectRetireTargets(containedStacks));
	}

	/**
	 * 基于一组已解析的待退役目标执行递归退役。
	 * <p>
	 * 本方法保留包级可见，供单测直接验证“容器销毁退役”语义，
	 * 避免测试被物品注册表初始化时序耦合。
	 * </p>
	 */
	static int retireTargets(ServerLevel level, Iterable<RetireTarget> retireTargets) {
		if (level == null || retireTargets == null) {
			return 0;
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		int retiredCount = 0;
		for (RetireTarget retireTarget : retireTargets) {
			if (shouldSkipRetireTarget(savedData, retireTarget)) {
				continue;
			}
			LinkSavedData.RetireResult retireResult = LinkRetireCoordinator.retireAndSyncWhitelist(
				level,
				retireTarget.nodeType(),
				retireTarget.serial()
			);
			if (hasRetireChanges(retireResult)) {
				retiredCount++;
			}
		}
		return retiredCount;
	}

	/**
	 * 从容器内部物品中提取待退役目标集合，并按“节点类型 + 序号”去重。
	 */
	private static Set<RetireTarget> collectRetireTargets(Iterable<ItemStack> containedStacks) {
		Set<RetireTarget> retireTargets = new LinkedHashSet<>();
		for (ItemStack containedStack : containedStacks) {
			if (containedStack == null || containedStack.isEmpty()) {
				continue;
			}
			if (containedStack.getItem() == ModItems.LINK_REPEATER) {
				for (Long serial : LinkItemData.getSerialGroup(containedStack)) {
					if (serial != null && serial > 0L) {
						retireTargets.add(new RetireTarget(LinkNodeType.CORE, serial));
					}
				}
				continue;
			}
			if (!(containedStack.getItem() instanceof PairableItem pairableItem)) {
				continue;
			}
			for (Long serial : LinkItemData.getSerialGroup(containedStack)) {
				if (serial != null && serial > 0L) {
					retireTargets.add(new RetireTarget(pairableItem.getNodeType(), serial));
				}
			}
		}
		return retireTargets;
	}

	/**
	 * 判断当前目标是否应跳过递归退役。
	 * <p>
	 * 若同序号节点仍在线，则说明这是容器里的拷贝或历史脏数据，不应反向删掉已放置节点。
	 * </p>
	 */
	private static boolean shouldSkipRetireTarget(LinkSavedData savedData, RetireTarget retireTarget) {
		if (savedData == null || retireTarget == null || retireTarget.nodeType() == null || retireTarget.serial() <= 0L) {
			return true;
		}
		if (savedData.isRepeaterSerial(retireTarget.serial())) {
			return savedData.findNode(LinkNodeType.CORE, retireTarget.serial()).isPresent()
				|| savedData.findNode(LinkNodeType.TRIGGER_SOURCE, retireTarget.serial()).isPresent();
		}
		return savedData.findNode(retireTarget.nodeType(), retireTarget.serial()).isPresent();
	}

	/**
	 * 判断一次退役调用是否产生了可见状态变化。
	 */
	private static boolean hasRetireChanges(LinkSavedData.RetireResult retireResult) {
		return retireResult != null
			&& (retireResult.nodeRemoved() || retireResult.linksRemoved() > 0 || retireResult.retiredMarked());
	}

	/**
	 * 容器内部单个待退役目标。
	 */
	record RetireTarget(LinkNodeType nodeType, long serial) {
	}
}
