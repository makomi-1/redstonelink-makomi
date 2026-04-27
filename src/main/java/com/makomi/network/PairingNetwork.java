package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.data.CrossChunkNodeIdentity;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeLinksSnapshot;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasServerSupport;
import com.makomi.data.NodeSnapshotQueryService;
import com.makomi.data.LinkSavedData;
import com.makomi.util.SignalStrengths;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 配对界面与近外显网络通道。
 * <p>
 * 主类只保留稳定公开入口与 payload 契约；payload 注册、服务端 handler、编解码适配分别下沉到 helper。
 * </p>
 */
public final class PairingNetwork {
	private PairingNetwork() {
	}

	/**
	 * 注册 `PairingNetwork` 的 payload 与服务端接包器。
	 */
	public static void register() {
		PairingNetworkRegistrationSupport.register();
	}

	/**
	 * 打开触发源侧配对界面。
	 */
	public static void openTriggerSourcePairing(ServerPlayer player, long sourceSerial) {
		openTriggerSourcePairing(player, sourceSerial, LinkGuiDisplayContext.TRIGGER_SOURCE);
	}

	/**
	 * 打开触发源侧配对界面，并显式携带显示上下文 token。
	 */
	public static void openTriggerSourcePairing(ServerPlayer player, long sourceSerial, String displayContextToken) {
		openPairingBySourceType(player, LinkNodeType.TRIGGER_SOURCE, sourceSerial, displayContextToken);
	}

	/**
	 * 打开核心侧配对界面（core 语义入口）。
	 */
	public static void openCorePairing(ServerPlayer player, long coreSerial) {
		openCorePairing(player, coreSerial, LinkGuiDisplayContext.CORE);
	}

	/**
	 * 打开核心侧配对界面，并显式携带显示上下文 token。
	 */
	public static void openCorePairing(ServerPlayer player, long coreSerial, String displayContextToken) {
		openPairingBySourceType(player, LinkNodeType.CORE, coreSerial, displayContextToken);
	}

	/**
	 * 按来源类型打开配对界面（命名语义化入口）。
	 *
	 * @param player 服务端玩家
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序列号
	 */
	public static void openPairingBySourceType(ServerPlayer player, LinkNodeType sourceType, long sourceSerial) {
		openPairingBySourceType(player, sourceType, sourceSerial, LinkGuiDisplayContext.fallbackPairingToken(sourceType));
	}

	/**
	 * 按来源类型打开配对界面，并下发具体显示上下文 token。
	 *
	 * @param player 服务端玩家
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序列号
	 * @param displayContextToken 供客户端标题/图标解析的稳定 token
	 */
	public static void openPairingBySourceType(
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		String displayContextToken
	) {
		if (sourceType == null || sourceSerial <= 0L) {
			return;
		}
		NodeLinksSnapshot linksSnapshot = NodeSnapshotQueryService.queryLinks(player, sourceType, sourceSerial);
		LinkSavedData savedData = LinkSavedData.get(player.level());
		LinkConnectionMode connectionMode = savedData.getConnectionMode(sourceType, sourceSerial);
		long channel = savedData.getChannel(sourceType, sourceSerial);
		String sourceAlias = NodeAliasServerSupport.resolveAlias(player.level(), sourceType, sourceSerial).orElse("");
		String sourceDisplayText = NodeAliasServerSupport.resolveDisplayText(player.level(), sourceType, sourceSerial);
		ServerPlayNetworking.send(
			player,
			buildPayloadForSourceType(
				sourceType,
				sourceSerial,
				linksSnapshot,
				connectionMode,
				channel,
				displayContextToken,
				sourceAlias,
				sourceDisplayText
			)
		);
	}

	/**
	 * 按来源类型构建对应的配对 payload。
	 * <p>
	 * 该私有方法继续保留在主类中，维持现有测试对反射入口的稳定依赖。
	 * </p>
	 *
	 * @param sourceType 来源类型
	 * @param sourceSerial 来源序列号
	 * @param currentTargets 当前可见目标
	 * @return 对应来源类型的配对 payload
	 */
	private static CustomPacketPayload buildPayloadForSourceType(
		LinkNodeType sourceType,
		long sourceSerial,
		NodeLinksSnapshot linksSnapshot,
		LinkConnectionMode connectionMode,
		long channel,
		String displayContextToken,
		String sourceAlias,
		String sourceDisplayText
	) {
		return PairingNetworkPayloadSupport.buildPayloadForSourceType(
			sourceType,
			sourceSerial,
			linksSnapshot,
			connectionMode,
			channel,
			displayContextToken,
			sourceAlias,
			sourceDisplayText
		);
	}

	/**
	 * 兼容旧测试入口：未指定显示上下文时按节点类型回退到通用 token。
	 */
	@SuppressWarnings("unused")
	private static CustomPacketPayload buildPayloadForSourceType(
		LinkNodeType sourceType,
		long sourceSerial,
		NodeLinksSnapshot linksSnapshot
	) {
		return buildPayloadForSourceType(
			sourceType,
			sourceSerial,
			linksSnapshot,
			LinkConnectionMode.SERIAL,
			0L,
			LinkGuiDisplayContext.fallbackPairingToken(sourceType),
			"",
			normalizeSourceDisplayText(sourceSerial, "")
		);
	}

	/**
	 * 兼容仅传目标列表的旧测试入口。
	 * <p>
	 * 该重载仅用于维持现有反射测试稳定，不作为新的业务读取入口。
	 * </p>
	 */
	@SuppressWarnings("unused")
	private static CustomPacketPayload buildPayloadForSourceType(
		LinkNodeType sourceType,
		long sourceSerial,
		List<Long> currentTargets
	) {
		return buildPayloadForSourceType(
			sourceType,
			sourceSerial,
			new NodeLinksSnapshot(null, currentTargets, false),
			LinkConnectionMode.SERIAL,
			0L,
			LinkGuiDisplayContext.fallbackPairingToken(sourceType),
			"",
			normalizeSourceDisplayText(sourceSerial, "")
		);
	}

	/**
	 * 触发源配对界面打开包：`sourceSerial` 为触发源序列号，`targets` 为当前关联 core 序列号列表，
	 * `graphRevision/sourceRevision/coreRevision` 为打开界面时的乐观并发版本基线。
	 */
	public record OpenTriggerSourcePairingPayload(
		long sourceSerial,
		List<Long> targets,
		List<String> targetDisplayTexts,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		String connectionModeToken,
		long channel,
		String displayContextToken,
		String sourceAlias,
		String sourceDisplayText
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenTriggerSourcePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_triggersource_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenTriggerSourcePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodePairingPayload(
				buffer,
				payload.sourceSerial(),
				payload.targets(),
				payload.targetDisplayTexts(),
				payload.graphRevision(),
				payload.sourceRevision(),
				payload.coreRevision(),
				payload.connectionModeToken(),
				payload.channel(),
				payload.displayContextToken(),
				payload.sourceAlias(),
				payload.sourceDisplayText()
			),
			PairingNetworkPayloadSupport::decodeTriggerSourcePairingPayload
		);

		public OpenTriggerSourcePairingPayload {
			targets = List.copyOf(targets);
			targetDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(targets, targetDisplayTexts);
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
			connectionModeToken = LinkConnectionMode.fromToken(connectionModeToken).token();
			channel = Math.max(0L, channel);
			displayContextToken = LinkGuiDisplayContext.normalizePairingContextToken(displayContextToken, LinkNodeType.TRIGGER_SOURCE);
			sourceAlias = normalizeSourceAlias(sourceAlias);
			sourceDisplayText = normalizeSourceDisplayText(sourceSerial, sourceAlias, sourceDisplayText);
		}

		public OpenTriggerSourcePairingPayload(
			long sourceSerial,
			List<Long> targets,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String connectionModeToken,
			long channel,
			String displayContextToken,
			String sourceAlias,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				List.of(),
				graphRevision,
				sourceRevision,
				coreRevision,
				connectionModeToken,
				channel,
				displayContextToken,
				sourceAlias,
				sourceDisplayText
			);
		}

		public OpenTriggerSourcePairingPayload(
			long sourceSerial,
			List<Long> targets,
			List<String> targetDisplayTexts,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String connectionModeToken,
			long channel,
			String displayContextToken,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				targetDisplayTexts,
				graphRevision,
				sourceRevision,
				coreRevision,
				connectionModeToken,
				channel,
				displayContextToken,
				"",
				sourceDisplayText
			);
		}

		public OpenTriggerSourcePairingPayload(
			long sourceSerial,
			List<Long> targets,
			List<String> targetDisplayTexts,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String displayContextToken,
			String sourceAlias,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				targetDisplayTexts,
				graphRevision,
				sourceRevision,
				coreRevision,
				LinkConnectionMode.SERIAL.token(),
				0L,
				displayContextToken,
				sourceAlias,
				sourceDisplayText
			);
		}

		public OpenTriggerSourcePairingPayload(
			long sourceSerial,
			List<Long> targets,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String displayContextToken,
			String sourceAlias,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				List.of(),
				graphRevision,
				sourceRevision,
				coreRevision,
				LinkConnectionMode.SERIAL.token(),
				0L,
				displayContextToken,
				sourceAlias,
				sourceDisplayText
			);
		}

		public OpenTriggerSourcePairingPayload(
			long sourceSerial,
			List<Long> targets,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String displayContextToken,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				graphRevision,
				sourceRevision,
				coreRevision,
				LinkConnectionMode.SERIAL.token(),
				0L,
				displayContextToken,
				"",
				sourceDisplayText
			);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * core 配对界面打开包：`sourceSerial` 为 core 序列号，`targets` 为当前关联 triggerSource 序列号列表，
	 * `graphRevision/sourceRevision/coreRevision` 为打开界面时的 revision 基线，其中 `coreRevision`
	 * 才是 core 视角 OCC 的主比较值。
	 */
	public record OpenCorePairingPayload(
		long sourceSerial,
		List<Long> targets,
		List<String> targetDisplayTexts,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		String connectionModeToken,
		long channel,
		String displayContextToken,
		String sourceAlias,
		String sourceDisplayText
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenCorePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_core_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenCorePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodePairingPayload(
				buffer,
				payload.sourceSerial(),
				payload.targets(),
				payload.targetDisplayTexts(),
				payload.graphRevision(),
				payload.sourceRevision(),
				payload.coreRevision(),
				payload.connectionModeToken(),
				payload.channel(),
				payload.displayContextToken(),
				payload.sourceAlias(),
				payload.sourceDisplayText()
			),
			PairingNetworkPayloadSupport::decodeCorePairingPayload
		);

		public OpenCorePairingPayload {
			targets = List.copyOf(targets);
			targetDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(targets, targetDisplayTexts);
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
			connectionModeToken = LinkConnectionMode.fromToken(connectionModeToken).token();
			channel = Math.max(0L, channel);
			displayContextToken = LinkGuiDisplayContext.normalizePairingContextToken(displayContextToken, LinkNodeType.CORE);
			sourceAlias = normalizeSourceAlias(sourceAlias);
			sourceDisplayText = normalizeSourceDisplayText(sourceSerial, sourceAlias, sourceDisplayText);
		}

		public OpenCorePairingPayload(
			long sourceSerial,
			List<Long> targets,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String connectionModeToken,
			long channel,
			String displayContextToken,
			String sourceAlias,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				List.of(),
				graphRevision,
				sourceRevision,
				coreRevision,
				connectionModeToken,
				channel,
				displayContextToken,
				sourceAlias,
				sourceDisplayText
			);
		}

		public OpenCorePairingPayload(
			long sourceSerial,
			List<Long> targets,
			List<String> targetDisplayTexts,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String connectionModeToken,
			long channel,
			String displayContextToken,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				targetDisplayTexts,
				graphRevision,
				sourceRevision,
				coreRevision,
				connectionModeToken,
				channel,
				displayContextToken,
				"",
				sourceDisplayText
			);
		}

		public OpenCorePairingPayload(
			long sourceSerial,
			List<Long> targets,
			List<String> targetDisplayTexts,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String displayContextToken,
			String sourceAlias,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				targetDisplayTexts,
				graphRevision,
				sourceRevision,
				coreRevision,
				LinkConnectionMode.SERIAL.token(),
				0L,
				displayContextToken,
				sourceAlias,
				sourceDisplayText
			);
		}

		public OpenCorePairingPayload(
			long sourceSerial,
			List<Long> targets,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String displayContextToken,
			String sourceAlias,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				List.of(),
				graphRevision,
				sourceRevision,
				coreRevision,
				LinkConnectionMode.SERIAL.token(),
				0L,
				displayContextToken,
				sourceAlias,
				sourceDisplayText
			);
		}

		public OpenCorePairingPayload(
			long sourceSerial,
			List<Long> targets,
			long graphRevision,
			long sourceRevision,
			long coreRevision,
			String displayContextToken,
			String sourceDisplayText
		) {
			this(
				sourceSerial,
				targets,
				graphRevision,
				sourceRevision,
				coreRevision,
				LinkConnectionMode.SERIAL.token(),
				0L,
				displayContextToken,
				"",
				sourceDisplayText
			);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * triggerSource 配对界面提交包：`sourceSerial` 为 triggerSource 序列号，`targetsExpression` 为目标 core 表达式，
	 * `expectedSourceRevision` 为客户端打开界面时记录的来源版本。
	 */
	public record SubmitTriggerSourcePairingPayload(
		long sourceSerial,
		String connectionModeToken,
		String targetsExpression,
		long channel,
		long expectedSourceRevision
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SubmitTriggerSourcePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "submit_triggersource_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, SubmitTriggerSourcePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSubmitTriggerSourcePairingPayload(
				buffer,
				payload.sourceSerial(),
				payload.connectionModeToken(),
				payload.targetsExpression(),
				payload.channel(),
				payload.expectedSourceRevision()
			),
			PairingNetworkPayloadSupport::decodeSubmitTriggerSourcePairingPayload
		);

		public SubmitTriggerSourcePairingPayload {
			connectionModeToken = LinkConnectionMode.fromToken(connectionModeToken).token();
			targetsExpression = targetsExpression == null ? "" : targetsExpression.trim();
			channel = Math.max(0L, channel);
			expectedSourceRevision = Math.max(0L, expectedSourceRevision);
		}

		public SubmitTriggerSourcePairingPayload(long sourceSerial, String targetsExpression, long expectedSourceRevision) {
			this(sourceSerial, LinkConnectionMode.SERIAL.token(), targetsExpression, 0L, expectedSourceRevision);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * core 配对界面提交包：`coreSerial` 为编辑目标 core 序列号，`triggerSourceExpression` 为期望关联的 triggerSource 表达式。
	 * <p>
	 * 该 payload 只承载“core 视角编辑请求”；服务端落地时仍统一拆成 `triggerSource -> core` 正向写入。
	 * `expectedCoreRevision` 用于拦截界面打开后当前 core 成员集合已变化的隐藏覆盖。
	 * </p>
	 */
	public record SubmitCorePairingPayload(
		long coreSerial,
		String connectionModeToken,
		String triggerSourceExpression,
		long channel,
		long expectedCoreRevision
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SubmitCorePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "submit_core_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, SubmitCorePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSubmitCorePairingPayload(
				buffer,
				payload.coreSerial(),
				payload.connectionModeToken(),
				payload.triggerSourceExpression(),
				payload.channel(),
				payload.expectedCoreRevision()
			),
			PairingNetworkPayloadSupport::decodeSubmitCorePairingPayload
		);

		public SubmitCorePairingPayload {
			connectionModeToken = LinkConnectionMode.fromToken(connectionModeToken).token();
			triggerSourceExpression = triggerSourceExpression == null ? "" : triggerSourceExpression.trim();
			channel = Math.max(0L, channel);
			expectedCoreRevision = Math.max(0L, expectedCoreRevision);
		}

		public SubmitCorePairingPayload(long coreSerial, String triggerSourceExpression, long expectedCoreRevision) {
			this(coreSerial, LinkConnectionMode.SERIAL.token(), triggerSourceExpression, 0L, expectedCoreRevision);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * pairing GUI 别名保存提交包。
	 */
	public record SubmitPairingAliasPayload(String sourceType, long sourceSerial, String sourceAlias) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SubmitPairingAliasPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "submit_pairing_alias")
		);
		public static final StreamCodec<FriendlyByteBuf, SubmitPairingAliasPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSubmitPairingAliasPayload(
				buffer,
				payload.sourceType(),
				payload.sourceSerial(),
				payload.sourceAlias()
			),
			PairingNetworkPayloadSupport::decodeSubmitPairingAliasPayload
		);

		public SubmitPairingAliasPayload {
			sourceType = sourceType == null ? "" : sourceType;
			sourceAlias = normalizeSourceAlias(sourceAlias);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 同步遥控器强度保存提交包。
	 */
	public record SaveSyncLinkerSignalStrengthPayload(long expectedSerial, int signalStrength) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SaveSyncLinkerSignalStrengthPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "save_sync_linker_signal_strength")
		);
		public static final StreamCodec<FriendlyByteBuf, SaveSyncLinkerSignalStrengthPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSaveSyncLinkerSignalStrengthPayload(
				buffer,
				payload.expectedSerial(),
				payload.signalStrength()
			),
			PairingNetworkPayloadSupport::decodeSaveSyncLinkerSignalStrengthPayload
		);

		public SaveSyncLinkerSignalStrengthPayload {
			expectedSerial = Math.max(0L, expectedSerial);
			signalStrength = SignalStrengths.clamp(signalStrength);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回给配对界面的结构化反馈回执。
	 */
	public record PairingFeedbackPayload(boolean success, String messageKey, List<String> messageArgs)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<PairingFeedbackPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "pairing_feedback")
		);
		public static final StreamCodec<FriendlyByteBuf, PairingFeedbackPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeFeedbackPayload(
				buffer,
				payload.success(),
				payload.messageKey(),
				payload.messageArgs()
			),
			PairingNetworkPayloadSupport::decodePairingFeedbackPayload
		);

		public PairingFeedbackPayload {
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回给配对界面的最新 alias 真值快照。
	 */
	public record PairingAliasStatePayload(
		String sourceType,
		long sourceSerial,
		String sourceAlias,
		String sourceDisplayText
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<PairingAliasStatePayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "pairing_alias_state")
		);
		public static final StreamCodec<FriendlyByteBuf, PairingAliasStatePayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodePairingAliasStatePayload(
				buffer,
				payload.sourceType(),
				payload.sourceSerial(),
				payload.sourceAlias(),
				payload.sourceDisplayText()
			),
			PairingNetworkPayloadSupport::decodePairingAliasStatePayload
		);

		public PairingAliasStatePayload {
			sourceType = sourceType == null ? "" : sourceType;
			sourceAlias = normalizeSourceAlias(sourceAlias);
			sourceDisplayText = normalizeSourceDisplayText(sourceSerial, sourceAlias, sourceDisplayText);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端近外显“当前连接”查询请求。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 */
	public record RequestCurrentLinksPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestCurrentLinksPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_current_links_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestCurrentLinksPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSnapshotRequestPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial()
			),
			PairingNetworkPayloadSupport::decodeRequestCurrentLinksPayload
		);

		public RequestCurrentLinksPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端近外显“最终 IO”查询请求。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 */
	public record RequestRuntimeHudSnapshotPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestRuntimeHudSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_runtime_hud_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestRuntimeHudSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSnapshotRequestPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial()
			),
			PairingNetworkPayloadSupport::decodeRequestRuntimeHudSnapshotPayload
		);

		public RequestRuntimeHudSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端下发给客户端的“当前连接”快照（已按权限脱敏）。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 * @param targets 可见目标序号集合（不可见时为空）
	 * @param connectionModeToken 命中节点当前连接模式
	 * @param channel 命中节点当前频道号；serial 模式下固定为 `0`
	 * @param crossChunkIdentity 命中节点的跨区块身份
	 */
	public record CurrentLinksSnapshotPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		List<Long> targets,
		List<String> targetDisplayTexts,
		String connectionModeToken,
		long channel,
		CrossChunkNodeIdentity crossChunkIdentity
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CurrentLinksSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "current_links_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, CurrentLinksSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeCurrentLinksSnapshotPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				payload.targets(),
				payload.targetDisplayTexts(),
				payload.connectionModeToken(),
				payload.channel(),
				payload.crossChunkIdentity()
			),
			PairingNetworkPayloadSupport::decodeCurrentLinksSnapshotPayload
		);

		public CurrentLinksSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
			targets = List.copyOf(targets);
			targetDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(targets, targetDisplayTexts);
			connectionModeToken = LinkConnectionMode.fromToken(connectionModeToken).token();
			channel = Math.max(0L, channel);
			crossChunkIdentity = crossChunkIdentity == null ? CrossChunkNodeIdentity.NORMAL : crossChunkIdentity;
		}

		public CurrentLinksSnapshotPayload(
			String dimensionKey,
			long blockPos,
			String sourceType,
			long sourceSerial,
			List<Long> targets,
			String connectionModeToken,
			long channel,
			CrossChunkNodeIdentity crossChunkIdentity
		) {
			this(
				dimensionKey,
				blockPos,
				sourceType,
				sourceSerial,
				targets,
				List.of(),
				connectionModeToken,
				channel,
				crossChunkIdentity
			);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端下发给客户端的近外显“最终 IO”快照。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 * @param available 当前是否有可读运行态
	 * @param inputPower 最终输入强度
	 * @param outputPower 最终输出强度
	 */
	public record RuntimeHudSnapshotPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		boolean available,
		int inputPower,
		int outputPower
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RuntimeHudSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "runtime_hud_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, RuntimeHudSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeRuntimeHudSnapshotPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				payload.available(),
				payload.inputPower(),
				payload.outputPower()
			),
			PairingNetworkPayloadSupport::decodeRuntimeHudSnapshotPayload
		);

		public RuntimeHudSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
			inputPower = PairingNetworkPayloadSupport.clampHudPower(inputPower);
			outputPower = PairingNetworkPayloadSupport.clampHudPower(outputPower);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static String normalizeSourceAlias(String sourceAlias) {
		return NodeAliasDisplayUtil.normalizeAlias(sourceAlias);
	}

	private static String normalizeSourceDisplayText(long sourceSerial, String sourceAlias, String sourceDisplayText) {
		String normalizedDisplayText = NodeAliasDisplayUtil.normalizeAlias(sourceDisplayText);
		return normalizedDisplayText.isEmpty()
			? NodeAliasDisplayUtil.formatDisplayText(sourceAlias, sourceSerial)
			: normalizedDisplayText;
	}

	private static String normalizeSourceDisplayText(long sourceSerial, String sourceDisplayText) {
		return normalizeSourceDisplayText(sourceSerial, "", sourceDisplayText);
	}
}
