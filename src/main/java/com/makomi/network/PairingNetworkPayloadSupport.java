package com.makomi.network;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * `PairingNetwork` 的 payload 编解码与构造辅助。
 * <p>
 * 该 helper 只负责字节级编解码与 payload 形态适配，不承担注册或服务端查询职责。
 * </p>
 */
final class PairingNetworkPayloadSupport {
	static final int DIMENSION_KEY_MAX_LENGTH = 128;
	static final int NODE_TYPE_MAX_LENGTH = 32;
	private static final int FEEDBACK_MESSAGE_KEY_MAX_LENGTH = 256;
	private static final int FEEDBACK_MESSAGE_ARG_MAX_LENGTH = 512;

	private PairingNetworkPayloadSupport() {
	}

	/**
	 * 按来源类型构建打开配对界面的回包。
	 *
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序列号
	 * @param currentTargets 当前可见目标序号
	 * @return 对应来源类型的 payload
	 */
	static CustomPacketPayload buildPayloadForSourceType(LinkNodeType sourceType, long sourceSerial, List<Long> currentTargets) {
		if (sourceType == LinkNodeType.TRIGGER_SOURCE) {
			return new PairingNetwork.OpenTriggerSourcePairingPayload(sourceSerial, currentTargets);
		}
		return new PairingNetwork.OpenCorePairingPayload(sourceSerial, currentTargets);
	}

	/**
	 * 编码通用配对 payload。
	 */
	static void encodePairingPayload(FriendlyByteBuf buffer, long sourceSerial, List<Long> targets) {
		buffer.writeVarLong(sourceSerial);
		buffer.writeVarInt(targets.size());
		for (long target : targets) {
			buffer.writeVarLong(target);
		}
	}

	/**
	 * 解码触发源配对 payload。
	 */
	static PairingNetwork.OpenTriggerSourcePairingPayload decodeTriggerSourcePairingPayload(FriendlyByteBuf buffer) {
		DecodedPayload payload = decodePairingPayload(buffer);
		return new PairingNetwork.OpenTriggerSourcePairingPayload(payload.sourceSerial(), payload.targets());
	}

	/**
	 * 解码 core 配对 payload。
	 */
	static PairingNetwork.OpenCorePairingPayload decodeCorePairingPayload(FriendlyByteBuf buffer) {
		DecodedPayload payload = decodePairingPayload(buffer);
		return new PairingNetwork.OpenCorePairingPayload(payload.sourceSerial(), payload.targets());
	}

	/**
	 * 编码 triggerSource 配对提交包。
	 */
	static void encodeSubmitTriggerSourcePairingPayload(FriendlyByteBuf buffer, long sourceSerial, String targetsExpression) {
		buffer.writeVarLong(Math.max(0L, sourceSerial));
		buffer.writeUtf(targetsExpression == null ? "" : targetsExpression, RedstoneLinkConfig.command().linkSetMaxInputLength());
	}

	/**
	 * 解码 triggerSource 配对提交包。
	 */
	static PairingNetwork.SubmitTriggerSourcePairingPayload decodeSubmitTriggerSourcePairingPayload(FriendlyByteBuf buffer) {
		return new PairingNetwork.SubmitTriggerSourcePairingPayload(
			buffer.readVarLong(),
			buffer.readUtf(RedstoneLinkConfig.command().linkSetMaxInputLength())
		);
	}

	/**
	 * 编码配对反馈回执。
	 */
	static void encodeFeedbackPayload(FriendlyByteBuf buffer, boolean success, String messageKey, List<String> messageArgs) {
		buffer.writeBoolean(success);
		buffer.writeUtf(messageKey == null ? "" : messageKey, FEEDBACK_MESSAGE_KEY_MAX_LENGTH);
		List<String> args = messageArgs == null ? List.of() : List.copyOf(messageArgs);
		buffer.writeVarInt(args.size());
		for (String arg : args) {
			buffer.writeUtf(arg == null ? "" : arg, FEEDBACK_MESSAGE_ARG_MAX_LENGTH);
		}
	}

	/**
	 * 解码配对反馈回执。
	 */
	static PairingNetwork.PairingFeedbackPayload decodePairingFeedbackPayload(FriendlyByteBuf buffer) {
		DecodedFeedbackPayload payload = decodeFeedbackPayload(buffer);
		return new PairingNetwork.PairingFeedbackPayload(payload.success(), payload.messageKey(), payload.messageArgs());
	}

	/**
	 * 编码只携带节点定位上下文的查询请求。
	 */
	static void encodeSnapshotRequestPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) {
		buffer.writeUtf(dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPos);
		buffer.writeUtf(sourceType, NODE_TYPE_MAX_LENGTH);
		buffer.writeVarLong(sourceSerial);
	}

	/**
	 * 解码“当前连接”查询请求。
	 */
	static PairingNetwork.RequestCurrentLinksPayload decodeRequestCurrentLinksPayload(FriendlyByteBuf buffer) {
		DecodedSnapshotRequestPayload payload = decodeSnapshotRequestPayload(buffer);
		return new PairingNetwork.RequestCurrentLinksPayload(
			payload.dimensionKey(),
			payload.blockPos(),
			payload.sourceType(),
			payload.sourceSerial()
		);
	}

	/**
	 * 解码“最终 IO”查询请求。
	 */
	static PairingNetwork.RequestRuntimeHudSnapshotPayload decodeRequestRuntimeHudSnapshotPayload(FriendlyByteBuf buffer) {
		DecodedSnapshotRequestPayload payload = decodeSnapshotRequestPayload(buffer);
		return new PairingNetwork.RequestRuntimeHudSnapshotPayload(
			payload.dimensionKey(),
			payload.blockPos(),
			payload.sourceType(),
			payload.sourceSerial()
		);
	}

	/**
	 * 编码“当前连接”回包。
	 */
	static void encodeCurrentLinksSnapshotPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		List<Long> targets
	) {
		buffer.writeUtf(dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPos);
		buffer.writeUtf(sourceType, NODE_TYPE_MAX_LENGTH);
		buffer.writeVarLong(sourceSerial);
		buffer.writeVarInt(targets.size());
		for (long target : targets) {
			buffer.writeVarLong(target);
		}
	}

	/**
	 * 解码“当前连接”回包。
	 */
	static PairingNetwork.CurrentLinksSnapshotPayload decodeCurrentLinksSnapshotPayload(FriendlyByteBuf buffer) {
		DecodedCurrentLinksPayload payload = decodeCurrentLinksPayload(buffer);
		return new PairingNetwork.CurrentLinksSnapshotPayload(
			payload.dimensionKey(),
			payload.blockPos(),
			payload.sourceType(),
			payload.sourceSerial(),
			payload.targets()
		);
	}

	/**
	 * 编码“最终 IO”回包。
	 */
	static void encodeRuntimeHudSnapshotPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		boolean available,
		int inputPower,
		int outputPower
	) {
		encodeSnapshotRequestPayload(buffer, dimensionKey, blockPos, sourceType, sourceSerial);
		buffer.writeBoolean(available);
		buffer.writeVarInt(inputPower);
		buffer.writeVarInt(outputPower);
	}

	/**
	 * 解码“最终 IO”回包。
	 */
	static PairingNetwork.RuntimeHudSnapshotPayload decodeRuntimeHudSnapshotPayload(FriendlyByteBuf buffer) {
		DecodedSnapshotRequestPayload requestPayload = decodeSnapshotRequestPayload(buffer);
		boolean available = buffer.readBoolean();
		int inputPower = buffer.readVarInt();
		int outputPower = buffer.readVarInt();
		return new PairingNetwork.RuntimeHudSnapshotPayload(
			requestPayload.dimensionKey(),
			requestPayload.blockPos(),
			requestPayload.sourceType(),
			requestPayload.sourceSerial(),
			available,
			inputPower,
			outputPower
		);
	}

	/**
	 * 归一化 HUD 红石强度。
	 *
	 * @param power 待归一化强度
	 * @return 限制在 `0..15` 的强度值
	 */
	static int clampHudPower(int power) {
		return Math.max(0, Math.min(15, power));
	}

	/**
	 * 解码通用配对 payload。
	 */
	private static DecodedPayload decodePairingPayload(FriendlyByteBuf buffer) {
		long sourceSerial = buffer.readVarLong();
		int size = buffer.readVarInt();
		List<Long> targets = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			targets.add(buffer.readVarLong());
		}
		return new DecodedPayload(sourceSerial, targets);
	}

	/**
	 * 解码只携带节点定位上下文的请求。
	 */
	private static DecodedSnapshotRequestPayload decodeSnapshotRequestPayload(FriendlyByteBuf buffer) {
		String dimensionKey = buffer.readUtf(DIMENSION_KEY_MAX_LENGTH);
		long blockPos = buffer.readLong();
		String sourceType = buffer.readUtf(NODE_TYPE_MAX_LENGTH);
		long sourceSerial = buffer.readVarLong();
		return new DecodedSnapshotRequestPayload(dimensionKey, blockPos, sourceType, sourceSerial);
	}

	/**
	 * 解码“当前连接”回包。
	 */
	private static DecodedCurrentLinksPayload decodeCurrentLinksPayload(FriendlyByteBuf buffer) {
		DecodedSnapshotRequestPayload payload = decodeSnapshotRequestPayload(buffer);
		int size = buffer.readVarInt();
		List<Long> targets = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			targets.add(buffer.readVarLong());
		}
		return new DecodedCurrentLinksPayload(
			payload.dimensionKey(),
			payload.blockPos(),
			payload.sourceType(),
			payload.sourceSerial(),
			targets
		);
	}

	/**
	 * 解码配对反馈回执。
	 */
	private static DecodedFeedbackPayload decodeFeedbackPayload(FriendlyByteBuf buffer) {
		boolean success = buffer.readBoolean();
		String messageKey = buffer.readUtf(FEEDBACK_MESSAGE_KEY_MAX_LENGTH);
		int size = buffer.readVarInt();
		List<String> messageArgs = new ArrayList<>(Math.max(size, 0));
		for (int index = 0; index < size; index++) {
			messageArgs.add(buffer.readUtf(FEEDBACK_MESSAGE_ARG_MAX_LENGTH));
		}
		return new DecodedFeedbackPayload(success, messageKey, List.copyOf(messageArgs));
	}

	/**
	 * 通用配对 payload 解码结果。
	 */
	private record DecodedPayload(long sourceSerial, List<Long> targets) {}

	/**
	 * 只携带节点定位上下文的请求解码结果。
	 */
	private record DecodedSnapshotRequestPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) {}

	/**
	 * “当前连接”回包解码结果。
	 */
	private record DecodedCurrentLinksPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		List<Long> targets
	) {}

	/**
	 * 配对反馈回执解码结果。
	 */
	private record DecodedFeedbackPayload(boolean success, String messageKey, List<String> messageArgs) {}
}
