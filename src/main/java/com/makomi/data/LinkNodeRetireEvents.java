package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.block.entity.TriggerSourceBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 节点退役事件注册器。
 */
public final class LinkNodeRetireEvents {
	private LinkNodeRetireEvents() {
	}

	/**
	 * 注册节点退役相关事件。
	 */
	public static void register() {
		// 当前实现：基于掉落物卸载回收节点（覆盖销毁、自然消失、命令击杀等路径）。
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (!(entity instanceof ItemEntity itemEntity)) {
				return;
			}

			Entity.RemovalReason reason = entity.getRemovalReason();
			if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
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

		// 完整自动清理：在当前实现基础上补齐“创造模式破坏方块即退役”场景。
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!RedstoneLinkConfig.fullAutoCleanupEnabled()) {
				return;
			}
			if (!(world instanceof ServerLevel serverLevel)) {
				return;
			}
			if (!player.getAbilities().instabuild) {
				return;
			}
			if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
				return;
			}

			long serial = pairableNodeBlockEntity.getSerial();
			if (serial <= 0L) {
				return;
			}

			LinkNodeType nodeType = resolveNodeType(pairableNodeBlockEntity);
			LinkSavedData.get(serverLevel).retireNode(nodeType, serial);
		});
	}

	/**
	 * 根据方块实体类型推断节点类型。
	 */
	private static LinkNodeType resolveNodeType(PairableNodeBlockEntity blockEntity) {
		if (blockEntity instanceof TriggerSourceBlockEntity) {
			return LinkNodeType.BUTTON;
		}
		return LinkNodeType.CORE;
	}
}
