package com.makomi.data;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 节点身份快照。
 * <p>
 * 统一承载单节点读模型中的基础身份字段，供运行态、当前连接视图和后续状态面板复用。
 * </p>
 */
public record NodeIdentitySnapshot(
	LinkNodeType nodeType,
	long serial,
	boolean allocated,
	boolean retired,
	boolean online,
	ResourceKey<Level> dimension,
	BlockPos pos
) {
	public NodeIdentitySnapshot {
		nodeType = nodeType == null ? LinkNodeType.CORE : nodeType;
		serial = Math.max(0L, serial);
		pos = pos == null ? null : pos.immutable();
	}

	/**
	 * 按当前服务端已知状态解析节点身份快照。
	 * <p>
	 * 位置字段保留“最近一次已登记坐标”，以支撑离线目标追踪；
	 * `online` 则以当前运行态是否真实可达为准：
	 * 目标维度存在、区块已加载，且该位置仍是同 `type+serial` 的节点。
	 * </p>
	 */
	public static NodeIdentitySnapshot resolve(ServerLevel level, LinkNodeType nodeType, long serial) {
		if (level == null || nodeType == null || serial <= 0L) {
			return new NodeIdentitySnapshot(nodeType, serial, false, false, false, null, null);
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		boolean allocated = savedData.isSerialAllocated(nodeType, serial);
		boolean retired = savedData.isSerialRetired(nodeType, serial);
		LinkSavedData.LinkNode node = savedData.findNode(nodeType, serial).orElse(null);
		boolean online = savedData.findRuntimeOnlineNode(level, nodeType, serial).isPresent();
		return new NodeIdentitySnapshot(
			nodeType,
			serial,
			allocated,
			retired,
			online,
			node == null ? null : node.dimension(),
			node == null ? null : node.pos()
		);
	}

	/**
	 * 复制当前身份快照，并覆写在线态。
	 */
	public NodeIdentitySnapshot withOnline(boolean online) {
		return new NodeIdentitySnapshot(nodeType, serial, allocated, retired, online, dimension, pos);
	}
}
