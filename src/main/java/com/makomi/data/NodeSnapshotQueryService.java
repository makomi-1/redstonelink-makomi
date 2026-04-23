package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkCrossChunkConfig;
import com.makomi.util.SerialCollectionFormatUtil;
import java.util.ArrayList;
import java.util.Collection;
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
		LinkSavedData savedData = resolveSavedData(level);
		Set<Long> rawTargets = readRawTargets(savedData, nodeType, serial);
		NodeLinksSnapshot visibleSnapshot = CurrentLinksPrivacyService.resolveVisibleLinksSnapshot(
			level,
			identity,
			nodeType,
			serial,
			rawTargets,
			hasViewPermission
		);
		return withRevisions(visibleSnapshot, savedData, nodeType, serial);
	}

	/**
	 * 查询适合写入物品 NBT 的当前连接视图。
	 * <p>
	 * 物品快照写入面向“物品自身展示”，不走隐私读控裁剪；
	 * 仅保留当前源节点的真实连接快照，供 tooltip 与物品栏展示复用。
	 * </p>
	 */
	public static NodeLinksSnapshot queryItemSnapshotLinks(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial
	) {
		LinkSavedData savedData = resolveSavedData(level);
		return buildItemSnapshotLinks(level, nodeType, serial, readRawTargets(savedData, nodeType, serial), savedData);
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
	 * 解析节点在“持久化数据 + 配置”视角下的跨区块身份。
	 * <p>
	 * 该身份仅用于近外显展示，不读取实体运行态，也不要求当前票据已实际挂载。
	 * </p>
	 */
	public static CrossChunkNodeIdentity resolveCrossChunkNodeIdentity(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial
	) {
		if (level == null) {
			return CrossChunkNodeIdentity.NORMAL;
		}
		return resolveCrossChunkNodeIdentity(
			nodeType,
			serial,
			CrossChunkWhitelistSavedData.get(level),
			PlacedChunkActivatorSavedData.get(level),
			RedstoneLinkConfig.crossChunk()
		);
	}

	private static Set<Long> readRawTargets(LinkSavedData savedData, LinkNodeType nodeType, long serial) {
		if (savedData == null || nodeType == null || serial <= 0L) {
			return Set.of();
		}
		return savedData.getLinkedPeersByNodeType(nodeType, serial);
	}

	private static LinkSavedData resolveSavedData(ServerLevel level) {
		if (level == null) {
			return null;
		}
		return LinkSavedData.get(level);
	}

	/**
	 * 按物品快照口径构建未过滤的当前连接视图。
	 * <p>
	 * 该入口仅用于“回写物品 NBT 快照”，与命令/面板读取的隐私视图隔离。
	 * </p>
	 */
	static NodeLinksSnapshot buildItemSnapshotLinks(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial,
		Set<Long> rawTargets
	) {
		return buildItemSnapshotLinks(level, nodeType, serial, rawTargets, resolveSavedData(level));
	}

	/**
	 * 按物品快照口径构建未过滤的当前连接视图，并附带 revision 基线。
	 */
	static NodeLinksSnapshot buildItemSnapshotLinks(
		ServerLevel level,
		LinkNodeType nodeType,
		long serial,
		Set<Long> rawTargets,
		LinkSavedData savedData
	) {
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, nodeType, serial);
		return new NodeLinksSnapshot(
			identity,
			rawTargets == null ? java.util.List.of() : java.util.List.copyOf(rawTargets),
			resolveVisibleTargetDisplayTexts(level, nodeType, rawTargets),
			false,
			resolveGraphRevision(savedData),
			resolveSourceRevision(savedData, nodeType, serial),
			resolveCoreRevision(savedData, nodeType, serial)
		);
	}

	private static NodeLinksSnapshot withRevisions(
		NodeLinksSnapshot snapshot,
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial
	) {
		NodeLinksSnapshot normalizedSnapshot = snapshot == null
			? new NodeLinksSnapshot(null, java.util.List.of(), false)
			: snapshot;
		return new NodeLinksSnapshot(
			normalizedSnapshot.sourceIdentity(),
			normalizedSnapshot.visibleTargets(),
			normalizedSnapshot.masked(),
			resolveGraphRevision(savedData),
			resolveSourceRevision(savedData, nodeType, serial),
			resolveCoreRevision(savedData, nodeType, serial)
		);
	}

	private static long resolveGraphRevision(LinkSavedData savedData) {
		return savedData == null ? 0L : savedData.graphRevision();
	}

	private static long resolveSourceRevision(LinkSavedData savedData, LinkNodeType nodeType, long serial) {
		if (savedData == null) {
			return 0L;
		}
		return savedData.sourceRevision(nodeType, serial);
	}

	private static long resolveCoreRevision(LinkSavedData savedData, LinkNodeType nodeType, long serial) {
		if (savedData == null || nodeType != LinkNodeType.CORE) {
			return 0L;
		}
		return savedData.coreRevision(serial);
	}

	/**
	 * 按来源节点类型解析其当前连接目标的展示文本列表。
	 */
	static java.util.List<String> resolveVisibleTargetDisplayTexts(
		ServerLevel level,
		LinkNodeType sourceType,
		Collection<Long> visibleTargets
	) {
		java.util.List<Long> normalizedTargets = SerialCollectionFormatUtil.normalizePositiveDistinctSorted(visibleTargets);
		if (normalizedTargets.isEmpty()) {
			return java.util.List.of();
		}
		LinkNodeType targetType = LinkNodeSemantics.resolveLinkedPeerType(sourceType);
		java.util.List<String> displayTexts = new ArrayList<>(normalizedTargets.size());
		for (long targetSerial : normalizedTargets) {
			displayTexts.add(resolveTargetDisplayText(level, targetType, targetSerial));
		}
		return java.util.List.copyOf(displayTexts);
	}

	private static String resolveTargetDisplayText(ServerLevel level, LinkNodeType targetType, long targetSerial) {
		if (targetSerial <= 0L) {
			return NodeAliasDisplayUtil.formatDisplayText("", targetSerial);
		}
		if (level == null || targetType == null) {
			return NodeAliasDisplayUtil.formatDisplayText("", targetSerial);
		}
		return NodeAliasServerSupport.resolveDisplayText(level, targetType, targetSerial);
	}

	/**
	 * 按给定白名单与配置快照解析跨区块身份。
	 * <p>
	 * 该 helper 暴露给同包测试复用，避免测试环境依赖真实 `ServerLevel`。
	 * </p>
	 */
	static CrossChunkNodeIdentity resolveCrossChunkNodeIdentity(
		LinkNodeType nodeType,
		long serial,
		CrossChunkWhitelistSavedData whitelistSavedData,
		RedstoneLinkCrossChunkConfig crossChunkConfig
	) {
		return resolveCrossChunkNodeIdentity(nodeType, serial, whitelistSavedData, null, crossChunkConfig);
	}

	/**
	 * 按给定手动白名单、区块激活器真值与配置快照解析跨区块身份。
	 */
	static CrossChunkNodeIdentity resolveCrossChunkNodeIdentity(
		LinkNodeType nodeType,
		long serial,
		CrossChunkWhitelistSavedData whitelistSavedData,
		PlacedChunkActivatorSavedData chunkActivatorSavedData,
		RedstoneLinkCrossChunkConfig crossChunkConfig
	) {
		LinkNodeSemantics.Role role = resolveCrossChunkRole(nodeType);
		if (nodeType == null || serial <= 0L || role == null || whitelistSavedData == null || crossChunkConfig == null) {
			return CrossChunkNodeIdentity.NORMAL;
		}
		if (CrossChunkEffectiveWhitelistService.isResident(nodeType, serial, role, whitelistSavedData, chunkActivatorSavedData)) {
			return CrossChunkNodeIdentity.RESIDENT;
		}
		if (!crossChunkConfig.forceLoadEnabled() || !isConfigAllowedForRole(nodeType, role, crossChunkConfig)) {
			return CrossChunkNodeIdentity.NORMAL;
		}
		if (crossChunkConfig.forceLoadMode() == RedstoneLinkConfig.CrossChunkForceLoadMode.ALL) {
			return CrossChunkNodeIdentity.FORCE_LOAD;
		}
		if (
			CrossChunkEffectiveWhitelistService.containsWhitelist(
				nodeType,
				serial,
				role,
				whitelistSavedData,
				chunkActivatorSavedData
			)
				|| crossChunkConfig.presetContains(nodeType, serial, role)
		) {
			return CrossChunkNodeIdentity.FORCE_LOAD;
		}
		return CrossChunkNodeIdentity.NORMAL;
	}

	/**
	 * 将节点类型映射到“自身节点在跨区块语义中的角色”。
	 */
	private static LinkNodeSemantics.Role resolveCrossChunkRole(LinkNodeType nodeType) {
		if (nodeType == LinkNodeType.TRIGGER_SOURCE) {
			return LinkNodeSemantics.Role.SOURCE;
		}
		if (nodeType == LinkNodeType.CORE) {
			return LinkNodeSemantics.Role.TARGET;
		}
		return null;
	}

	/**
	 * 判断当前节点类型是否在对应角色的跨区块配置允许集中。
	 */
	private static boolean isConfigAllowedForRole(
		LinkNodeType nodeType,
		LinkNodeSemantics.Role role,
		RedstoneLinkCrossChunkConfig crossChunkConfig
	) {
		Set<LinkNodeType> allowedTypes = role == LinkNodeSemantics.Role.SOURCE
			? crossChunkConfig.allowedSourceTypes()
			: crossChunkConfig.allowedTargetTypes();
		return allowedTypes != null && allowedTypes.contains(nodeType);
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
