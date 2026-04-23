package com.makomi.data;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.item.ChunkActivatorBlockItem;
import com.makomi.item.LinkFilterBlockItem;
import com.makomi.util.SerialParseUtil;
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
		syncOnlineConfiguredNodeSetBlockEntities(level, type, serial);
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
		if (server == null || server.getPlayerList() == null || type == null || serial <= 0L) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean changed = syncPlayerInventoryAliases(player, type, serial);
			if (changed) {
				player.containerMenu.broadcastChanges();
			}
		}
	}

	/**
	 * 刷新当前已加载的过滤器与区块激活器节点集别名外显。
	 */
	private static void syncOnlineConfiguredNodeSetBlockEntities(ServerLevel level, LinkNodeType type, long serial) {
		if (level == null || type == null || serial <= 0L || level.getServer() == null) {
			return;
		}
		syncOnlineFilterNodeSetDisplays(level, type, serial);
		syncOnlineChunkActivatorNodeSetDisplays(level, type, serial);
	}

	private static void syncOnlineFilterNodeSetDisplays(ServerLevel level, LinkNodeType type, long serial) {
		for (PlacedLinkFilterSavedData.FilterEntry entry : PlacedLinkFilterSavedData.get(level).entriesSnapshot()) {
			if (entry == null || entry.filterKind().servicedNodeType() != type || !entry.serials().contains(serial)) {
				continue;
			}
			ServerLevel entryLevel = level.getServer().getLevel(entry.dimension());
			if (entryLevel == null || !entryLevel.isLoaded(entry.filterPos())) {
				continue;
			}
			if (!(entryLevel.getBlockEntity(entry.filterPos()) instanceof AbstractLinkFilterBlockEntity filterBlockEntity)) {
				continue;
			}
			if (filterBlockEntity.filterKind() == entry.filterKind()) {
				filterBlockEntity.forceSyncToClient();
			}
		}
	}

	private static void syncOnlineChunkActivatorNodeSetDisplays(ServerLevel level, LinkNodeType type, long serial) {
		for (PlacedChunkActivatorSavedData.ActivatorEntry entry : PlacedChunkActivatorSavedData.get(level).entriesSnapshot()) {
			if (entry == null || !entry.serialsFor(type).contains(serial)) {
				continue;
			}
			ServerLevel entryLevel = level.getServer().getLevel(entry.dimension());
			if (entryLevel == null || !entryLevel.isLoaded(entry.activatorPos())) {
				continue;
			}
			if (entryLevel.getBlockEntity(entry.activatorPos()) instanceof LinkChunkActivatorBlockEntity chunkActivatorBlockEntity) {
				chunkActivatorBlockEntity.forceSyncToClient();
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
		changed |= syncItemAliasOrLinkedDisplayText(player, carried, type, serial);
		return changed;
	}

	private static boolean syncItemList(ServerPlayer player, List<ItemStack> stacks, LinkNodeType type, long serial) {
		if (player == null || stacks == null || stacks.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (ItemStack stack : stacks) {
			changed |= syncItemAliasOrLinkedDisplayText(player, stack, type, serial);
		}
		return changed;
	}

	private static boolean syncItemAliasOrLinkedDisplayText(ServerPlayer player, ItemStack stack, LinkNodeType type, long serial) {
		if (player == null || stack == null || stack.isEmpty()) {
			return false;
		}
		boolean changed = false;
		if (LinkItemData.getSerialCount(stack) == 1) {
			if (matchesNodeItem(stack, type, serial)) {
				String beforeAlias = LinkItemData.getDisplayAlias(stack);
				LinkItemData.syncDisplayAliasIfSingle(stack, player.serverLevel());
				changed |= !beforeAlias.equals(LinkItemData.getDisplayAlias(stack));
			}
			if (linksToAliasedNode(stack, type, serial)) {
				List<String> beforeDisplayTexts = LinkItemData.getLinkedDisplayTexts(stack);
				LinkItemData.syncCurrentLinksSnapshotIfSingle(stack, player.serverLevel());
				changed |= !beforeDisplayTexts.equals(LinkItemData.getLinkedDisplayTexts(stack));
			}
		}
		changed |= syncFilterItemNodeSetDisplayTexts(player, stack, type, serial);
		changed |= syncChunkActivatorItemNodeSetDisplayTexts(player, stack, type, serial);
		return changed;
	}

	/**
	 * 别名变更后，刷新过滤器物品缓存的节点集展示文本。
	 */
	private static boolean syncFilterItemNodeSetDisplayTexts(ServerPlayer player, ItemStack stack, LinkNodeType type, long serial) {
		if (
			player == null ||
			stack == null ||
			stack.isEmpty() ||
			!(stack.getItem() instanceof LinkFilterBlockItem filterBlockItem) ||
			filterBlockItem.filterKind().servicedNodeType() != type
		) {
			return false;
		}
		LinkFilterConfigSnapshot snapshot = LinkFilterItemData.read(stack);
		if (!snapshot.usesSerialTarget() || !parseOrderedSerials(snapshot.serialExpression()).contains(serial)) {
			return false;
		}
		List<String> beforeDisplayTexts = LinkFilterItemData.getNodeSetDisplayTexts(stack);
		LinkFilterItemData.syncNodeSetDisplayTexts(stack, player.serverLevel(), type);
		return !beforeDisplayTexts.equals(LinkFilterItemData.getNodeSetDisplayTexts(stack));
	}

	/**
	 * 别名变更后，刷新区块激活器物品缓存的节点集展示文本。
	 */
	private static boolean syncChunkActivatorItemNodeSetDisplayTexts(ServerPlayer player, ItemStack stack, LinkNodeType type, long serial) {
		if (player == null || stack == null || stack.isEmpty() || !(stack.getItem() instanceof ChunkActivatorBlockItem)) {
			return false;
		}
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(type);
		ChunkActivatorConfigStateSnapshot snapshot = ChunkActivatorItemData.read(stack);
		if (!parseOrderedSerials(snapshot.configFor(normalizedType).serialExpression()).contains(serial)) {
			return false;
		}
		List<String> beforeDisplayTexts = ChunkActivatorItemData.getNodeSetDisplayTexts(stack, normalizedType);
		ChunkActivatorItemData.syncNodeSetDisplayTexts(stack, player.serverLevel());
		return !beforeDisplayTexts.equals(ChunkActivatorItemData.getNodeSetDisplayTexts(stack, normalizedType));
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

	private static boolean linksToAliasedNode(ItemStack stack, LinkNodeType type, long serial) {
		if (stack == null || stack.isEmpty() || type == null || serial <= 0L || LinkItemData.getSerialCount(stack) != 1) {
			return false;
		}
		LinkNodeType itemType = LinkItemData.getNodeType(stack).orElse(null);
		if (LinkNodeSemantics.resolveLinkedPeerType(itemType) != type) {
			return false;
		}
		return LinkItemData.getLinkedSerials(stack).contains(serial);
	}

	/**
	 * 解析有序序号列表，仅用于缓存命中判断。
	 */
	private static List<Long> parseOrderedSerials(String serialExpression) {
		return SerialParseUtil.parseTargetsOrdered(serialExpression, 0).orderedTargets();
	}
}
