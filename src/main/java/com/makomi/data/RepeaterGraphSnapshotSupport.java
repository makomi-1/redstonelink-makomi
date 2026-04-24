package com.makomi.data;

import com.makomi.item.RepeaterBlockItem;
import com.makomi.util.SerialDisplayFormatUtil;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 转发器图快照与物品缓存同步支撑。
 * <p>
 * 转发器的输入/输出摘要需要始终以服务端当前图真值为准，
 * 避免因为编辑器缓存、掉落物 NBT 或手持物品旧快照而出现漂移。
 * </p>
 */
public final class RepeaterGraphSnapshotSupport {
	private RepeaterGraphSnapshotSupport() {
	}

	/**
	 * 按当前图真值解析转发器配置快照；表达式为空时回退到给定快照中的旧值。
	 */
	public static RepeaterConfigSnapshot resolve(
		ServerLevel level,
		long serial,
		RepeaterConfigSnapshot fallbackSnapshot
	) {
		RepeaterConfigSnapshot fallback = fallbackSnapshot == null ? RepeaterConfigSnapshot.empty() : fallbackSnapshot;
		if (level == null || serial <= 0L) {
			return fallback;
		}
		return resolve(LinkSavedData.get(level), serial, fallback);
	}

	/**
	 * 按当前图真值解析转发器配置快照；只保留 fallback 中的延迟档位。
	 */
	public static RepeaterConfigSnapshot resolve(
		LinkSavedData savedData,
		long serial,
		RepeaterConfigSnapshot fallbackSnapshot
	) {
		RepeaterConfigSnapshot fallback = fallbackSnapshot == null ? RepeaterConfigSnapshot.empty() : fallbackSnapshot;
		if (savedData == null || serial <= 0L) {
			return fallback;
		}
		String inputExpression = buildExpression(savedData.getLinkedTriggerSourcesByCore(serial));
		String outputExpression = buildExpression(savedData.getLinkedCoresByTriggerSource(serial));
		return new RepeaterConfigSnapshot(inputExpression, outputExpression, fallback.delay());
	}

	/**
	 * 解析转发器统一别名；优先读取 core 侧，缺失时回退到 triggerSource 侧。
	 */
	public static String resolveAlias(ServerLevel level, long serial, String fallbackAlias) {
		if (level == null || serial <= 0L) {
			return NodeAliasDisplayUtil.normalizeAlias(fallbackAlias);
		}
		String coreAlias = NodeAliasServerSupport.resolveAlias(level, LinkNodeType.CORE, serial).orElse("");
		if (!coreAlias.isEmpty()) {
			return coreAlias;
		}
		String triggerSourceAlias = NodeAliasServerSupport
			.resolveAlias(level, LinkNodeType.TRIGGER_SOURCE, serial)
			.orElse("");
		if (!triggerSourceAlias.isEmpty()) {
			return triggerSourceAlias;
		}
		return NodeAliasDisplayUtil.normalizeAlias(fallbackAlias);
	}

	/**
	 * 将手持/背包中的转发器物品快照刷新为图真值。
	 */
	public static void syncItemSnapshot(ItemStack stack, ServerLevel level) {
		if (stack == null || stack.isEmpty() || level == null) {
			return;
		}
		if (!(stack.getItem() instanceof RepeaterBlockItem) || LinkItemData.getSerialCount(stack) != 1) {
			return;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return;
		}
		RepeaterItemData.write(stack, resolve(level, serial, RepeaterItemData.read(stack)));
		LinkItemData.syncDisplayAliasIfSingle(stack, level);
		RepeaterItemData.syncNodeSetDisplayTexts(stack, level);
	}

	/**
	 * 在图变更后，刷新所有在线玩家背包中的转发器物品快照。
	 */
	public static void syncOnlineRepeaterItems(MinecraftServer server, long serial) {
		if (server == null || serial <= 0L || server.getPlayerList() == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean changed = syncPlayerRepeaterItems(player, serial);
			if (changed) {
				player.containerMenu.broadcastChanges();
			}
		}
	}

	private static boolean syncPlayerRepeaterItems(ServerPlayer player, long serial) {
		if (player == null || serial <= 0L) {
			return false;
		}
		boolean changed = false;
		Inventory inventory = player.getInventory();
		changed |= syncRepeaterItemList(inventory.items, player.serverLevel(), serial);
		changed |= syncRepeaterItemList(inventory.offhand, player.serverLevel(), serial);
		changed |= syncRepeaterItemList(inventory.armor, player.serverLevel(), serial);
		changed |= syncRepeaterStack(player.containerMenu.getCarried(), player.serverLevel(), serial);
		return changed;
	}

	private static boolean syncRepeaterItemList(List<ItemStack> stacks, ServerLevel level, long serial) {
		if (stacks == null || stacks.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (ItemStack stack : stacks) {
			changed |= syncRepeaterStack(stack, level, serial);
		}
		return changed;
	}

	private static boolean syncRepeaterStack(ItemStack stack, ServerLevel level, long serial) {
		if (stack == null || stack.isEmpty() || level == null) {
			return false;
		}
		if (!(stack.getItem() instanceof RepeaterBlockItem) || LinkItemData.getSerialCount(stack) != 1) {
			return false;
		}
		if (LinkItemData.getSerial(stack) != serial) {
			return false;
		}
		syncItemSnapshot(stack, level);
		return true;
	}

	private static String buildExpression(Collection<Long> serials) {
		SerialDisplayFormatUtil.StructuredExpression expression = SerialDisplayFormatUtil.buildExpression(serials);
		return expression.isEmpty() ? "" : expression.joinAll();
	}
}
