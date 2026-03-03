package com.makomi.data;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 物品节点退役事件注册器。
 * <p>
 * 监听实体卸载事件：当可配对物品以“被击杀（KILLED）”原因消失时，
 * 将其序列号写入退役集合，避免序列号在后续被错误复用。
 * </p>
 */
public final class LinkNodeRetireEvents {
	private LinkNodeRetireEvents() {
	}

	/**
	 * 注册节点退役相关的服务器事件。
	 */
	public static void register() {
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (!(entity instanceof ItemEntity itemEntity)) {
				return;
			}
			// to review
			Entity.RemovalReason reason = entity.getRemovalReason();
			if (reason != Entity.RemovalReason.KILLED) {
				return;
			}

			ItemStack stack = itemEntity.getItem();
			if (stack.isEmpty() || !LinkItemData.isDestroyRetireCandidate(stack)) {
				return;
			}

			long serial = LinkItemData.getSerial(stack);
			if (serial <= 0L) {
				return;
			}

			LinkNodeType nodeType = LinkItemData.getNodeType(stack).orElse(null);
			if (nodeType == null) {
				return;
			}

			LinkSavedData.get(level).retireNode(nodeType, serial);
		});
	}
}
