package com.makomi.data;

import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

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
		Set<Long> rawTargets = level == null || nodeType == null || serial <= 0L
			? Set.of()
			: LinkSavedData.get(level).getLinkedTargetsBySourceType(nodeType, serial);
		NodeLinksSnapshot linksSnapshot = CurrentLinksPrivacyService.resolveVisibleLinksSnapshot(
			level,
			nodeType,
			serial,
			rawTargets,
			hasViewPermission
		);
		NodeRuntimeSnapshot runtimeSnapshot = resolveRuntimeSnapshot(level == null ? null : level.getServer(), nodeType, serial)
			.orElse(null);
		return new NodeReadSnapshot(identity, linksSnapshot, runtimeSnapshot);
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
