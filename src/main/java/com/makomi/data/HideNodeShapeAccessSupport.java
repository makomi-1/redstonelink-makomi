package com.makomi.data;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 隐藏节点命中/轮廓形状访问支撑。
 * <p>
 * 统一回答当前交互上下文是否允许把 `hide` 节点当作可命中的真实方块。
 * </p>
 */
public final class HideNodeShapeAccessSupport {
	private HideNodeShapeAccessSupport() {
	}

	/**
	 * 根据当前碰撞上下文解析 `hide` 节点的交互形状。
	 */
	public static VoxelShape resolveInteractionShape(CollisionContext context) {
		return canAccessHideNode(context) ? Shapes.block() : Shapes.empty();
	}

	/**
	 * 判断当前上下文是否允许访问 `hide` 节点。
	 */
	public static boolean canAccessHideNode(CollisionContext context) {
		Entity entity = resolveContextEntity(context);
		return entity instanceof Player player && SmartGlassesAccessSupport.canRenderSerialOverlay(player);
	}

	/**
	 * 从碰撞上下文中提取实体。
	 */
	private static Entity resolveContextEntity(CollisionContext context) {
		if (context instanceof EntityCollisionContext entityCollisionContext) {
			return entityCollisionContext.getEntity();
		}
		return null;
	}
}
