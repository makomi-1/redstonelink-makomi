package com.makomi.data;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

public final class LinkNodeRetireEvents {
	private LinkNodeRetireEvents() {
	}

	public static void register() {
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (!(entity instanceof ItemEntity itemEntity)) {
				return;
			}

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
