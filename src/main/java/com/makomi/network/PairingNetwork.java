package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeLinksSnapshot;
import com.makomi.data.NodeSnapshotQueryService;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
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
		openPairingBySourceType(player, LinkNodeType.TRIGGER_SOURCE, sourceSerial);
	}

	/**
	 * 打开核心侧配对界面（core 语义入口）。
	 */
	public static void openCorePairing(ServerPlayer player, long coreSerial) {
		openPairingBySourceType(player, LinkNodeType.CORE, coreSerial);
	}

	/**
	 * 按来源类型打开配对界面（命名语义化入口）。
	 *
	 * @param player 服务端玩家
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序列号
	 */
	public static void openPairingBySourceType(ServerPlayer player, LinkNodeType sourceType, long sourceSerial) {
		if (sourceType == null || sourceSerial <= 0L) {
			return;
		}
		NodeLinksSnapshot linksSnapshot = NodeSnapshotQueryService.queryLinks(player, sourceType, sourceSerial);
		ServerPlayNetworking.send(player, buildPayloadForSourceType(sourceType, sourceSerial, linksSnapshot.visibleTargets()));
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
		List<Long> currentTargets
	) {
		return PairingNetworkPayloadSupport.buildPayloadForSourceType(sourceType, sourceSerial, currentTargets);
	}

	/**
	 * 触发源配对界面打开包：`sourceSerial` 为触发源序列号，`targets` 为当前关联 core 序列号列表。
	 */
	public record OpenTriggerSourcePairingPayload(long sourceSerial, List<Long> targets) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenTriggerSourcePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_triggersource_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenTriggerSourcePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodePairingPayload(buffer, payload.sourceSerial(), payload.targets()),
			PairingNetworkPayloadSupport::decodeTriggerSourcePairingPayload
		);

		public OpenTriggerSourcePairingPayload {
			targets = List.copyOf(targets);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * core 配对界面打开包：`sourceSerial` 为 core 序列号，`targets` 为当前关联 triggerSource 序列号列表。
	 */
	public record OpenCorePairingPayload(long sourceSerial, List<Long> targets) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenCorePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_core_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenCorePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodePairingPayload(buffer, payload.sourceSerial(), payload.targets()),
			PairingNetworkPayloadSupport::decodeCorePairingPayload
		);

		public OpenCorePairingPayload {
			targets = List.copyOf(targets);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * triggerSource 配对界面提交包：`sourceSerial` 为 triggerSource 序列号，`targetsExpression` 为目标 core 表达式。
	 */
	public record SubmitTriggerSourcePairingPayload(long sourceSerial, String targetsExpression) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SubmitTriggerSourcePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "submit_triggersource_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, SubmitTriggerSourcePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSubmitTriggerSourcePairingPayload(
				buffer,
				payload.sourceSerial(),
				payload.targetsExpression()
			),
			PairingNetworkPayloadSupport::decodeSubmitTriggerSourcePairingPayload
		);

		public SubmitTriggerSourcePairingPayload {
			targetsExpression = targetsExpression == null ? "" : targetsExpression.trim();
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
	 * </p>
	 */
	public record SubmitCorePairingPayload(long coreSerial, String triggerSourceExpression) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SubmitCorePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "submit_core_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, SubmitCorePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeSubmitCorePairingPayload(
				buffer,
				payload.coreSerial(),
				payload.triggerSourceExpression()
			),
			PairingNetworkPayloadSupport::decodeSubmitCorePairingPayload
		);

		public SubmitCorePairingPayload {
			triggerSourceExpression = triggerSourceExpression == null ? "" : triggerSourceExpression.trim();
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
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "pairing_feedback")
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
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_current_links_snapshot")
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
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_runtime_hud_snapshot")
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
	 */
	public record CurrentLinksSnapshotPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		List<Long> targets
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CurrentLinksSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "current_links_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, CurrentLinksSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> PairingNetworkPayloadSupport.encodeCurrentLinksSnapshotPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				payload.targets()
			),
			PairingNetworkPayloadSupport::decodeCurrentLinksSnapshotPayload
		);

		public CurrentLinksSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
			targets = List.copyOf(targets);
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
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "runtime_hud_snapshot")
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
}
