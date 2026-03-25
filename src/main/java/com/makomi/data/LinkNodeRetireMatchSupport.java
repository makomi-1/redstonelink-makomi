package com.makomi.data;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * 节点待退役匹配 helper。
 * <p>
 * 负责从物品栈解析待退役键，以及掉落物/玩家背包的保留判定。
 * </p>
 */
final class LinkNodeRetireMatchSupport {
	private LinkNodeRetireMatchSupport() {
	}

	/**
	 * 判断当前待退役是否应跳过。
	 */
	static boolean shouldSkipPendingRetire(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		LinkSavedData savedData,
		LinkNodeRetireEvents.PendingEntry entry,
		double dropMatchScanRadius
	) {
		LinkNodeRetireEvents.PendingKey key = entry.key();
		if (savedData.findNode(key.nodeType(), key.serial()).isPresent()) {
			return true;
		}

		ServerLevel level = server.getLevel(entry.dimension());
		if (level != null && hasMatchingDropEntity(level, key, entry.pos(), dropMatchScanRadius)) {
			return true;
		}

		return hasMatchingStackInOnlinePlayers(server, key);
	}

	/**
	 * 检查在线玩家背包中是否存在匹配物品。
	 */
	static boolean hasMatchingStackInOnlinePlayers(MinecraftServer server, LinkNodeRetireEvents.PendingKey key) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.getAbilities().instabuild) {
				continue;
			}
			for (ItemStack stack : player.getInventory().items) {
				if (isMatchingStack(stack, key, false)) {
					return true;
				}
			}
			for (ItemStack stack : player.getInventory().offhand) {
				if (isMatchingStack(stack, key, false)) {
					return true;
				}
			}
			for (ItemStack stack : player.getInventory().armor) {
				if (isMatchingStack(stack, key, false)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 检查指定位置附近是否存在匹配掉落物。
	 */
	static boolean hasMatchingDropEntity(
		ServerLevel level,
		LinkNodeRetireEvents.PendingKey key,
		BlockPos aroundPos,
		double dropMatchScanRadius
	) {
		AABB scanBox = new AABB(aroundPos).inflate(dropMatchScanRadius);
		return !level.getEntitiesOfClass(
			ItemEntity.class,
			scanBox,
			itemEntity -> isMatchingStack(itemEntity.getItem(), key, true)
		).isEmpty();
	}

	/**
	 * 从物品栈解析待退役键。
	 */
	static LinkNodeRetireEvents.PendingKey pendingKeyFromStack(ItemStack stack, boolean requireDestroyCandidate) {
		if (stack.isEmpty()) {
			return null;
		}
		if (requireDestroyCandidate && !LinkItemData.isDestroyRetireCandidate(stack)) {
			return null;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return null;
		}
		LinkNodeType nodeType = LinkItemData.getNodeType(stack).orElse(null);
		if (nodeType == null) {
			return null;
		}
		return new LinkNodeRetireEvents.PendingKey(nodeType, serial);
	}

	/**
	 * 在实体卸载时解析待退役键；失败时回退到 UUID 记忆。
	 */
	static LinkNodeRetireEvents.PendingKey resolveUnloadKey(
		Map<MinecraftServer, LinkNodeRetireEvents.PendingRetireState> states,
		MinecraftServer server,
		ItemEntity itemEntity
	) {
		LinkNodeRetireEvents.PendingKey key = pendingKeyFromStack(itemEntity.getItem(), true);
		if (key != null) {
			return key;
		}
		return LinkNodeRetireStateSupport.getRememberedEntityKey(states, server, itemEntity.getUUID());
	}

	/**
	 * 判断物品栈是否与待退役键匹配。
	 */
	static boolean isMatchingStack(
		ItemStack stack,
		LinkNodeRetireEvents.PendingKey key,
		boolean requireDestroyCandidate
	) {
		LinkNodeRetireEvents.PendingKey resolved = pendingKeyFromStack(stack, requireDestroyCandidate);
		return resolved != null && resolved.equals(key);
	}
}
