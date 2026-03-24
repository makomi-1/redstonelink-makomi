package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 单节点读模型查询服务。
 * <p>
 * 统一组装节点身份、当前连接视图与可用运行态快照，
 * 供命令读取与后续状态面板复用。
 * </p>
 */
public final class NodeSnapshotQueryService {
	private NodeSnapshotQueryService() {
	}

	/**
	 * 查询指定节点的单节点读模型。
	 */
	public static NodeReadSnapshot query(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial,
		boolean hasViewPermission
	) {
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, nodeType, serial);
		NodeLinksSnapshot linksSnapshot = queryLinks(level, nodeType, serial, hasViewPermission);
		NodeRuntimeSnapshot runtimeSnapshot = resolveRuntimeSnapshot(level == null ? null : level.getServer(), nodeType, serial)
			.orElse(null);
		return new NodeReadSnapshot(identity, linksSnapshot, runtimeSnapshot);
	}

	/**
	 * 按玩家视角查询当前连接可见视图。
	 */
	public static NodeLinksSnapshot queryLinks(
		ServerPlayer player,
		LinkNodeType nodeType,
		long serial
	) {
		ServerLevel level = player.serverLevel();
		boolean hasViewPermission = player.hasPermissions(RedstoneLinkConfig.privacy().viewPermissionLevel());
		return queryLinks(level, nodeType, serial, hasViewPermission);
	}

	/**
	 * 按命令/系统上下文查询当前连接可见视图。
	 */
	public static NodeLinksSnapshot queryLinks(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial,
		boolean hasViewPermission
	) {
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, nodeType, serial);
		Set<Long> rawTargets = readRawTargets(level, nodeType, serial);
		return CurrentLinksPrivacyService.resolveVisibleLinksSnapshot(
			level,
			identity,
			nodeType,
			serial,
			rawTargets,
			hasViewPermission
		);
	}

	/**
	 * 查询适合写入物品 NBT 的当前连接视图。
	 * <p>
	 * 物品快照默认不携带额外查看权限，因此统一按 `hasViewPermission=false` 处理。
	 * </p>
	 */
	public static NodeLinksSnapshot queryItemSnapshotLinks(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial
	) {
		return queryLinks(level, nodeType, serial, false);
	}

	/**
	 * 查询节点当前可用运行态快照。
	 */
	public static Optional<NodeRuntimeSnapshot> resolveRuntimeSnapshot(
		MinecraftServer server,
		LinkNodeType nodeType,
		long serial
	) {
		return NodeRuntimeProbe.resolveCurrent(server, nodeType, serial).map(NodeRuntimeProbe.ProbeResolution::snapshot);
	}

	private static Set<Long> readRawTargets(ServerLevel level, LinkNodeType nodeType, long serial) {
		if (level == null || nodeType == null || serial <= 0L) {
			return Set.of();
		}
		return LinkSavedData.get(level).getLinkedTargetsBySourceType(nodeType, serial);
	}

	/**
	 * 单节点查询结果。
	 */
	public record NodeReadSnapshot(
		NodeIdentitySnapshot identity,
		NodeLinksSnapshot linksSnapshot,
		NodeRuntimeSnapshot runtimeSnapshot
	) {
		public NodeReadSnapshot {
			identity = identity == null
				? new NodeIdentitySnapshot(LinkNodeType.CORE, 0L, false, false, false, null, null)
				: identity;
			linksSnapshot = linksSnapshot == null ? new NodeLinksSnapshot(identity, java.util.List.of(), false) : linksSnapshot;
		}

		/**
		 * 是否包含可读运行态快照。
		 */
		public boolean hasRuntimeSnapshot() {
			return runtimeSnapshot != null;
		}
	}
}
