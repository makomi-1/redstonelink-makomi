package com.makomi.data;

import com.makomi.item.SmartGlassesItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 智能眼镜穿戴与交互门槛判定工具。
 * <p>
 * 本类只负责回答“玩家当前是否满足智能眼镜相关显示/交互条件”，
 * 供客户端渲染、输入入口与服务端 quick-link 验权共用。
 * </p>
 */
public final class SmartGlassesAccessSupport {
	private SmartGlassesAccessSupport() {
	}

	/**
	 * 读取头部装备槽中的智能眼镜物品。
	 */
	public static ItemStack getWornSmartGlasses(LivingEntity entity) {
		if (entity == null) {
			return ItemStack.EMPTY;
		}
		ItemStack headStack = entity.getItemBySlot(EquipmentSlot.HEAD);
		return isSmartGlasses(headStack) ? headStack : ItemStack.EMPTY;
	}

	/**
	 * 判断当前实体是否已穿戴智能眼镜。
	 */
	public static boolean isWearingSmartGlasses(LivingEntity entity) {
		return !getWornSmartGlasses(entity).isEmpty();
	}

	/**
	 * 判断玩家当前是否满足“主手为空”。
	 */
	public static boolean hasEmptyMainHand(Player player) {
		return player != null && player.getMainHandItem().isEmpty();
	}

	/**
	 * 判断是否允许连线可视化相关“显示”。
	 * <p>
	 * 规则：只要求已穿戴智能眼镜；主手是否为空不影响显示。
	 * </p>
	 */
	public static boolean canRenderQuickLinkVisualization(Player player) {
		return isWearingSmartGlasses(player);
	}

	/**
	 * 判断是否允许连线可视化相关“操作”。
	 * <p>
	 * 规则：必须已穿戴智能眼镜，且主手为空。
	 * 该门槛用于 third-person visualize 的广义操作语境，
	 * 例如模式成立判断与潜行清空显示对象。
	 * </p>
	 */
	public static boolean canOperateQuickLinkVisualization(Player player) {
		return isWearingSmartGlasses(player) && hasEmptyMainHand(player);
	}

	/**
	 * 判断是否允许修改连线可视化显示对象。
	 * <p>
	 * 规则：必须已穿戴智能眼镜、主手为空，且当前处于站立状态。
	 * 该门槛只用于“添加/移除显示对象”这类需要避免与潜行手势重叠的操作。
	 * </p>
	 */
	public static boolean canModifyQuickLinkVisualizationObjects(Player player) {
		return canOperateQuickLinkVisualization(player) && !player.isShiftKeyDown();
	}

	/**
	 * 判断是否允许远/近外显显示。
	 * <p>
	 * 规则：只要求已穿戴智能眼镜。
	 * </p>
	 */
	public static boolean canRenderSerialOverlay(Player player) {
		return isWearingSmartGlasses(player);
	}

	/**
	 * 判断指定物品栈是否为智能眼镜。
	 */
	public static boolean isSmartGlasses(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof SmartGlassesItem;
	}
}
