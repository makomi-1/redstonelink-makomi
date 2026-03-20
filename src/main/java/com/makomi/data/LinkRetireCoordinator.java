package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.PairableNodeBlockEntity;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 节点退役统一协调入口。
 * <p>
 * 负责将“退役主流程”与“白名单强同步清理”收口到单一入口，
 * 避免调用方遗漏副作用处理。
 * </p>
 */
public final class LinkRetireCoordinator {
	// 工具类，防止实例化用法
	private LinkRetireCoordinator() {
	}

	/**
	 * 执行节点退役，并强同步清理跨区块白名单（含 resident）中的对应条目。
	 *
	 * @param level 服务端维度上下文
	 * @param type 节点类型
	 * @param serial 节点序号
	 * @return 退役结果
	 */
	public static LinkSavedData.RetireResult retireAndSyncWhitelist(
		ServerLevel level,
		LinkNodeType type,
		long serial
	) {
		if (level == null || type == null || serial <= 0L) {
			return new LinkSavedData.RetireResult(false, 0, false);
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		// 退役前仅抓取退役源节点在线快照，避免向受影响目标集合逐个强同步。
		LinkSavedData.LinkNode sourceNodeSnapshot = savedData.findNode(type, serial).orElse(null);
		Set<Long> detachedSerials = new HashSet<>(savedData.getLinkedTargetsBySourceType(type, serial));
		LinkSavedData.RetireResult retireResult = savedData.retireNode(type, serial);
		CrossChunkWhitelistSavedData.get(level).removeFromAllRoles(type, serial);
		CurrentLinksPrivacySavedData.get(level).remove(type, serial);
		LinkWriteProtectedSavedData.get(level).remove(type, serial);
		if (hasRetireChanges(retireResult)) {
			InternalDispatchDeltaEvents.publishLinkDetached(
				level,
				type,
				serial,
				detachedSerials,
				EventMeta.of(level.getGameTime(), 0, 0L)
			);
			syncNodeSnapshot(level, sourceNodeSnapshot);
		}
		return retireResult;
	}

	/**
	 * 判断本次退役是否产生可见变更。
	 */
	private static boolean hasRetireChanges(LinkSavedData.RetireResult retireResult) {
		return retireResult != null
			&& (retireResult.nodeRemoved() || retireResult.linksRemoved() > 0 || retireResult.retiredMarked());
	}

	/**
	 * 按节点在线快照同步方块实体到客户端。
	 */
	private static void syncNodeSnapshot(ServerLevel level, LinkSavedData.LinkNode node) {
		if (level == null || node == null || node.dimension() == null || node.pos() == null) {
			return;
		}
		ServerLevel nodeLevel = level.getServer().getLevel(node.dimension());
		if (nodeLevel == null || !nodeLevel.isLoaded(node.pos())) {
			return;
		}
		BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
		if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
			pairableNodeBlockEntity.forceSyncToClient();
		}
	}
}
