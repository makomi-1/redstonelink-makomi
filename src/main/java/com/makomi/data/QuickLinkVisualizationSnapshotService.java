package com.makomi.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * quick-link 第三形态的可视化快照查询服务。
 * <p>
 * 统一把普通节点与转发器聚合对象折叠为“可画线对象 + 目标对象列表”，
 * 供网络层直接序列化下发，避免客户端自行猜测图真值。
 * </p>
 */
public final class QuickLinkVisualizationSnapshotService {
	private QuickLinkVisualizationSnapshotService() {
	}

	/**
	 * 查询指定显示对象的当前可视化快照。
	 *
	 * @param player 发起查询的玩家
	 * @param objectTypeToken 显示对象类型；支持 `triggerSource/core/link_repeater`
	 * @param serial 显示对象统一序号
	 * @return 可序列化快照；找不到目标时返回 {@code null}
	 */
	public static VisualizedObjectSnapshot query(ServerPlayer player, String objectTypeToken, long serial) {
		if (player == null || objectTypeToken == null || objectTypeToken.isBlank() || serial <= 0L) {
			return null;
		}
		if (LinkGuiDisplayContext.LINK_REPEATER.equals(objectTypeToken)) {
			return queryRepeater(player, serial);
		}
		LinkNodeType nodeType = LinkNodeSemantics.tryParseCanonicalType(objectTypeToken).orElse(null);
		if (nodeType == null) {
			return null;
		}
		return queryNode(player, nodeType, serial);
	}

	/**
	 * 查询普通节点的可视化快照。
	 */
	private static VisualizedObjectSnapshot queryNode(ServerPlayer player, LinkNodeType nodeType, long serial) {
		ServerLevel level = player.serverLevel();
		LinkSavedData savedData = LinkSavedData.get(level);
		VisualizedObjectRef source = buildNodeSourceRef(level, nodeType, serial);
		if (source == null || !source.hasPosition()) {
			return null;
		}

		NodeLinksSnapshot linksSnapshot = NodeSnapshotQueryService.queryLinks(player, nodeType, serial);
		LinkNodeType peerType = LinkNodeSemantics.resolveLinkedPeerType(nodeType);
		Map<VisualizedObjectKey, VisualizedObjectRef> visibleTargets = new LinkedHashMap<>();
		List<Long> targetSerials = linksSnapshot.visibleTargets();
		List<String> targetDisplayTexts = linksSnapshot.visibleTargetDisplayTexts();
		for (int index = 0; index < targetSerials.size(); index++) {
			long targetSerial = targetSerials.get(index);
			String targetDisplayText = index < targetDisplayTexts.size() ? targetDisplayTexts.get(index) : "";
			addTargetRef(
				visibleTargets,
				buildPeerTargetRef(level, savedData, peerType, targetSerial, targetDisplayText)
			);
		}
		return new VisualizedObjectSnapshot(
			source,
			List.copyOf(visibleTargets.values()),
			readNodeRevisionBaseline(savedData, nodeType, serial)
		);
	}

	/**
	 * 查询转发器聚合对象的可视化快照。
	 * <p>
	 * 转发器会同时展开：
	 * </p>
	 * <ul>
	 * <li>`core` 侧可见输入（来自 triggerSource）；</li>
	 * <li>`triggerSource` 侧可见输出（指向 core）。</li>
	 * </ul>
	 * 自隔离由图真值读取阶段保证，因此这里直接复用当前可见连接集合。
	 */
	private static VisualizedObjectSnapshot queryRepeater(ServerPlayer player, long serial) {
		ServerLevel level = player.serverLevel();
		LinkSavedData savedData = LinkSavedData.get(level);
		if (savedData == null || !savedData.isRepeaterSerial(serial)) {
			return null;
		}
		VisualizedObjectRef source = buildRepeaterSourceRef(level, serial);
		if (source == null || !source.hasPosition()) {
			return null;
		}

		Map<VisualizedObjectKey, VisualizedObjectRef> visibleTargets = new LinkedHashMap<>();
		NodeLinksSnapshot inputSnapshot = CurrentLinksPrivacyService.resolveVisibleLinksSnapshot(
			player,
			LinkNodeType.CORE,
			serial,
			savedData.getLinkedTriggerSourcesByCore(serial)
		);
		for (int index = 0; index < inputSnapshot.visibleTargets().size(); index++) {
			long targetSerial = inputSnapshot.visibleTargets().get(index);
			String targetDisplayText = index < inputSnapshot.visibleTargetDisplayTexts().size()
				? inputSnapshot.visibleTargetDisplayTexts().get(index)
				: "";
			addTargetRef(
				visibleTargets,
				buildPeerTargetRef(level, savedData, LinkNodeType.TRIGGER_SOURCE, targetSerial, targetDisplayText)
			);
		}

		NodeLinksSnapshot outputSnapshot = CurrentLinksPrivacyService.resolveVisibleLinksSnapshot(
			player,
			LinkNodeType.TRIGGER_SOURCE,
			serial,
			savedData.getLinkedCoresByTriggerSource(serial)
		);
		for (int index = 0; index < outputSnapshot.visibleTargets().size(); index++) {
			long targetSerial = outputSnapshot.visibleTargets().get(index);
			String targetDisplayText = index < outputSnapshot.visibleTargetDisplayTexts().size()
				? outputSnapshot.visibleTargetDisplayTexts().get(index)
				: "";
			addTargetRef(
				visibleTargets,
				buildPeerTargetRef(level, savedData, LinkNodeType.CORE, targetSerial, targetDisplayText)
			);
		}
		return new VisualizedObjectSnapshot(source, List.copyOf(visibleTargets.values()), readRepeaterRevisionBaseline(savedData, serial));
	}

	/**
	 * 读取指定显示对象当前的 revision 基线。
	 */
	public static VisualizedObjectRevisionBaseline readRevisionBaseline(ServerLevel level, String objectTypeToken, long serial) {
		if (level == null || objectTypeToken == null || objectTypeToken.isBlank() || serial <= 0L) {
			return VisualizedObjectRevisionBaseline.ZERO;
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		if (savedData == null) {
			return VisualizedObjectRevisionBaseline.ZERO;
		}
		if (LinkGuiDisplayContext.LINK_REPEATER.equals(objectTypeToken)) {
			return readRepeaterRevisionBaseline(savedData, serial);
		}
		LinkNodeType nodeType = LinkNodeSemantics.tryParseCanonicalType(objectTypeToken).orElse(null);
		if (nodeType == null) {
			return VisualizedObjectRevisionBaseline.ZERO;
		}
		return readNodeRevisionBaseline(savedData, nodeType, serial);
	}

	/**
	 * 构造普通节点的显示对象引用。
	 */
	private static VisualizedObjectRef buildNodeSourceRef(ServerLevel level, LinkNodeType nodeType, long serial) {
		if (level == null || nodeType == null || serial <= 0L) {
			return null;
		}
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, nodeType, serial);
		String displayText = NodeAliasServerSupport.resolveDisplayText(level, nodeType, serial);
		return new VisualizedObjectRef(
			LinkNodeSemantics.toSemanticName(nodeType),
			serial,
			resolveDimensionKey(identity),
			resolveBlockPosLong(identity),
			normalizeDisplayText(displayText, serial)
		);
	}

	/**
	 * 构造转发器聚合对象的显示对象引用。
	 */
	private static VisualizedObjectRef buildRepeaterSourceRef(ServerLevel level, long serial) {
		if (level == null || serial <= 0L) {
			return null;
		}
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, LinkNodeType.CORE, serial);
		String alias = RepeaterGraphSnapshotSupport.resolveAlias(level, serial, "");
		return new VisualizedObjectRef(
			LinkGuiDisplayContext.LINK_REPEATER,
			serial,
			resolveDimensionKey(identity),
			resolveBlockPosLong(identity),
			NodeAliasDisplayUtil.formatDisplayText(alias, serial)
		);
	}

	/**
	 * 将逻辑对侧节点折叠为“显示对象”引用。
	 * <p>
	 * 若目标序号属于转发器统一序号，则始终折叠为 `link_repeater` 聚合对象；
	 * 否则保留原始 `triggerSource/core` 身份。
	 * </p>
	 */
	private static VisualizedObjectRef buildPeerTargetRef(
		ServerLevel level,
		LinkSavedData savedData,
		LinkNodeType logicalType,
		long serial,
		String fallbackDisplayText
	) {
		if (level == null || savedData == null || logicalType == null || serial <= 0L) {
			return null;
		}
		if (savedData.isRepeaterSerial(serial)) {
			return buildRepeaterSourceRef(level, serial);
		}
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(level, logicalType, serial);
		return new VisualizedObjectRef(
			LinkNodeSemantics.toSemanticName(logicalType),
			serial,
			resolveDimensionKey(identity),
			resolveBlockPosLong(identity),
			normalizeDisplayText(fallbackDisplayText, serial)
		);
	}

	/**
	 * 将目标对象写入去重表。
	 */
	private static void addTargetRef(
		Map<VisualizedObjectKey, VisualizedObjectRef> visibleTargets,
		VisualizedObjectRef targetRef
	) {
		if (visibleTargets == null || targetRef == null) {
			return;
		}
		visibleTargets.putIfAbsent(new VisualizedObjectKey(targetRef.objectTypeToken(), targetRef.serial()), targetRef);
	}

	/**
	 * 读取普通节点的 revision 基线。
	 */
	private static VisualizedObjectRevisionBaseline readNodeRevisionBaseline(
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial
	) {
		if (savedData == null || nodeType == null || serial <= 0L) {
			return VisualizedObjectRevisionBaseline.ZERO;
		}
		return new VisualizedObjectRevisionBaseline(
			savedData.graphRevision(),
			nodeType == LinkNodeType.TRIGGER_SOURCE ? savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, serial) : 0L,
			nodeType == LinkNodeType.CORE ? savedData.coreRevision(serial) : 0L
		);
	}

	/**
	 * 读取转发器聚合对象的 revision 基线。
	 */
	private static VisualizedObjectRevisionBaseline readRepeaterRevisionBaseline(LinkSavedData savedData, long serial) {
		if (savedData == null || serial <= 0L) {
			return VisualizedObjectRevisionBaseline.ZERO;
		}
		return new VisualizedObjectRevisionBaseline(
			savedData.graphRevision(),
			savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, serial),
			savedData.coreRevision(serial)
		);
	}

	/**
	 * 将身份快照转换为网络可传输的维度键。
	 */
	private static String resolveDimensionKey(NodeIdentitySnapshot identity) {
		if (identity == null || identity.dimension() == null || identity.pos() == null) {
			return "";
		}
		return identity.dimension().identifier().toString();
	}

	/**
	 * 将身份快照转换为网络可传输的方块坐标。
	 */
	private static long resolveBlockPosLong(NodeIdentitySnapshot identity) {
		if (identity == null || identity.dimension() == null || identity.pos() == null) {
			return 0L;
		}
		return identity.pos().asLong();
	}

	/**
	 * 归一化显示文本，缺失时回退到序号展示。
	 */
	private static String normalizeDisplayText(String displayText, long serial) {
		String normalizedDisplayText = displayText == null ? "" : displayText.trim();
		if (!normalizedDisplayText.isEmpty()) {
			return normalizedDisplayText;
		}
		return NodeAliasDisplayUtil.formatDisplayText("", serial);
	}

	/**
	 * 单个显示对象的可序列化快照。
	 */
	public record VisualizedObjectSnapshot(
		VisualizedObjectRef source,
		List<VisualizedObjectRef> targets,
		VisualizedObjectRevisionBaseline revisionBaseline
	) {
		public VisualizedObjectSnapshot {
			targets = List.copyOf(targets == null ? List.of() : targets);
			revisionBaseline = revisionBaseline == null ? VisualizedObjectRevisionBaseline.ZERO : revisionBaseline;
		}
	}

	/**
	 * 第三形态单个显示对象的 revision 基线。
	 */
	public record VisualizedObjectRevisionBaseline(
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) {
		public static final VisualizedObjectRevisionBaseline ZERO = new VisualizedObjectRevisionBaseline(0L, 0L, 0L);

		public VisualizedObjectRevisionBaseline {
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
		}
	}

	/**
	 * 客户端可直接用于绘制的对象引用。
	 */
	public record VisualizedObjectRef(
		String objectTypeToken,
		long serial,
		String dimensionKey,
		long blockPosLong,
		String displayText
	) {
		public VisualizedObjectRef {
			objectTypeToken = objectTypeToken == null ? "" : objectTypeToken;
			serial = Math.max(0L, serial);
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			displayText = displayText == null ? "" : displayText;
		}

		/**
		 * 是否具备可画线的世界定位。
		 */
		public boolean hasPosition() {
			return !dimensionKey.isBlank();
		}
	}

	/**
	 * 显示对象去重键。
	 */
	private record VisualizedObjectKey(String objectTypeToken, long serial) {
	}
}
