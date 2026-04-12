package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 节点别名服务端读取与展示同步支撑。
 */
public final class NodeAliasServerSupport {
	private NodeAliasServerSupport() {
	}

	/**
	 * 按服务端真值解析节点别名。
	 */
	public static Optional<String> resolveAlias(ServerLevel level, LinkNodeType type, long serial) {
		if (level == null || type == null || serial <= 0L) {
			return Optional.empty();
		}
		return NodeAliasSavedData.get(level).getAlias(type, serial);
	}

	/**
	 * 将节点别名与序号格式化为统一展示文本。
	 */
	public static String resolveDisplayText(ServerLevel level, LinkNodeType type, long serial) {
		return NodeAliasDisplayUtil.formatDisplayText(resolveAlias(level, type, serial).orElse(""), serial);
	}

	/**
	 * 在别名变更后，刷新当前在线节点外显与玩家物品缓存。
	 */
	public static void syncDisplaysAfterAliasChanged(ServerLevel level, LinkNodeType type, long serial) {
		if (level == null || type == null || serial <= 0L) {
			return;
		}
		syncOnlineNodeBlockEntity(level, type, serial);
		syncOnlinePlayerItemAliases(level.getServer(), type, serial);
	}

	/**
	 * 刷新当前在线节点方块实体的客户端别名外显。
	 */
	public static void syncOnlineNodeBlockEntity(ServerLevel level, LinkNodeType type, long serial) {
		if (level == null || type == null || serial <= 0L) {
			return;
		}
		LinkSavedData.LinkNode node = LinkSavedData.get(level).findNode(type, serial).orElse(null);
		if (node == null) {
			return;
		}
		ServerLevel nodeLevel = level.getServer().getLevel(node.dimension());
		if (nodeLevel == null) {
			return;
		}
		if (!(nodeLevel.getBlockEntity(node.pos()) instanceof PairableNodeBlockEntity pairableNode)) {
			return;
		}
		if (pairableNode.getLinkNodeType() != type || pairableNode.getSerial() != serial) {
			return;
		}
		pairableNode.forceSyncToClient();
	}

	/**
	 * 刷新所有在线玩家背包中命中该节点的单件物品别名缓存。
	 */
	public static void syncOnlinePlayerItemAliases(MinecraftServer server, LinkNodeType type, long serial) {
		if (server == null || type == null || serial <= 0L) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean changed = syncPlayerInventoryAliases(player, type, serial);
			if (changed) {
				player.containerMenu.broadcastChanges();
			}
		}
	}

	private static boolean syncPlayerInventoryAliases(ServerPlayer player, LinkNodeType type, long serial) {
		if (player == null || type == null || serial <= 0L) {
			return false;
		}
		boolean changed = false;
		Inventory inventory = player.getInventory();
		changed |= syncItemList(player, inventory.items, type, serial);
		changed |= syncItemList(player, inventory.offhand, type, serial);
		changed |= syncItemList(player, inventory.armor, type, serial);
		ItemStack carried = player.containerMenu.getCarried();
		if (matchesNodeItem(carried, type, serial)) {
			String beforeAlias = LinkItemData.getDisplayAlias(carried);
			LinkItemData.syncDisplayAliasIfSingle(carried, player.serverLevel());
			changed |= !beforeAlias.equals(LinkItemData.getDisplayAlias(carried));
		}
		return changed;
	}

	private static boolean syncItemList(ServerPlayer player, List<ItemStack> stacks, LinkNodeType type, long serial) {
		if (player == null || stacks == null || stacks.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (ItemStack stack : stacks) {
			if (!matchesNodeItem(stack, type, serial)) {
				continue;
			}
			String beforeAlias = LinkItemData.getDisplayAlias(stack);
			LinkItemData.syncDisplayAliasIfSingle(stack, player.serverLevel());
			changed |= !beforeAlias.equals(LinkItemData.getDisplayAlias(stack));
		}
		return changed;
	}

	private static boolean matchesNodeItem(ItemStack stack, LinkNodeType type, long serial) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (LinkItemData.getSerialCount(stack) != 1 || LinkItemData.getSerial(stack) != serial) {
			return false;
		}
		return LinkItemData.getNodeType(stack).orElse(null) == type;
	}
}
