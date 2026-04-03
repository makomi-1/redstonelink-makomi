package com.makomi.network;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 状态面板工具网络载荷编解码支撑。
 */
final class StatePanelNetworkPayloadSupport {
	private static final int NODE_TYPE_TOKEN_MAX_LENGTH = PairingNetworkPayloadSupport.NODE_TYPE_MAX_LENGTH;
	private static final int FEEDBACK_MESSAGE_KEY_MAX_LENGTH = 256;
	private static final int FEEDBACK_MESSAGE_ARG_MAX_LENGTH = 512;

	private StatePanelNetworkPayloadSupport() {
	}

	/**
	 * 编码打开面板包。
	 */
	static void encodeOpenPayload(FriendlyByteBuf buffer, List<StatePanelNetwork.SubscriptionEntryPayload> subscriptions) {
		List<StatePanelNetwork.SubscriptionEntryPayload> values = subscriptions == null ? List.of() : List.copyOf(subscriptions);
		buffer.writeVarInt(values.size());
		for (StatePanelNetwork.SubscriptionEntryPayload entry : values) {
			buffer.writeUtf(LinkNodeSemantics.toSemanticName(entry.nodeType()), NODE_TYPE_TOKEN_MAX_LENGTH);
			buffer.writeLong(entry.serial());
		}
	}

	/**
	 * 解码打开面板包。
	 */
	static List<StatePanelNetwork.SubscriptionEntryPayload> decodeOpenPayload(FriendlyByteBuf buffer) {
		int size = Math.max(0, buffer.readVarInt());
		List<StatePanelNetwork.SubscriptionEntryPayload> values = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			LinkNodeType nodeType = LinkNodeSemantics
				.tryParseCanonicalType(buffer.readUtf(NODE_TYPE_TOKEN_MAX_LENGTH))
				.orElse(LinkNodeType.CORE);
			long serial = buffer.readLong();
			values.add(new StatePanelNetwork.SubscriptionEntryPayload(nodeType, serial));
		}
		return List.copyOf(values);
	}

	/**
	 * 编码订阅请求。
	 */
	static void encodeSubscribePayload(FriendlyByteBuf buffer, String nodeTypeToken, String serialExpression) {
		buffer.writeUtf(nodeTypeToken == null ? "" : nodeTypeToken, NODE_TYPE_TOKEN_MAX_LENGTH);
		buffer.writeUtf(serialExpression == null ? "" : serialExpression, resolveMaxInputLength());
	}

	/**
	 * 解码订阅请求。
	 */
	static DecodedSubscribePayload decodeSubscribePayload(FriendlyByteBuf buffer) {
		return new DecodedSubscribePayload(
			buffer.readUtf(NODE_TYPE_TOKEN_MAX_LENGTH),
			buffer.readUtf(resolveMaxInputLength())
		);
	}

	/**
	 * 编码删除请求。
	 */
	static void encodeRemovePayload(FriendlyByteBuf buffer, String nodeTypeToken, long serial) {
		buffer.writeUtf(nodeTypeToken == null ? "" : nodeTypeToken, NODE_TYPE_TOKEN_MAX_LENGTH);
		buffer.writeLong(serial);
	}

	/**
	 * 解码删除请求。
	 */
	static DecodedRemovePayload decodeRemovePayload(FriendlyByteBuf buffer) {
		return new DecodedRemovePayload(buffer.readUtf(NODE_TYPE_TOKEN_MAX_LENGTH), buffer.readLong());
	}

	/**
	 * 编码状态快照回执。
	 */
	static void encodeSnapshotPayload(FriendlyByteBuf buffer, List<StatePanelNetwork.StatePanelSnapshotEntry> entries) {
		List<StatePanelNetwork.StatePanelSnapshotEntry> values = entries == null ? List.of() : List.copyOf(entries);
		buffer.writeVarInt(values.size());
		for (StatePanelNetwork.StatePanelSnapshotEntry entry : values) {
			buffer.writeUtf(LinkNodeSemantics.toSemanticName(entry.nodeType()), NODE_TYPE_TOKEN_MAX_LENGTH);
			buffer.writeLong(entry.serial());
			buffer.writeBoolean(entry.allocated());
			buffer.writeBoolean(entry.retired());
			buffer.writeBoolean(entry.online());
			buffer.writeBoolean(entry.active());
			buffer.writeVarInt(entry.inputPower());
			buffer.writeVarInt(entry.outputPower());
			buffer.writeBoolean(entry.readable());
		}
	}

	/**
	 * 解码状态快照回执。
	 */
	static List<StatePanelNetwork.StatePanelSnapshotEntry> decodeSnapshotPayload(FriendlyByteBuf buffer) {
		int size = Math.max(0, buffer.readVarInt());
		List<StatePanelNetwork.StatePanelSnapshotEntry> values = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			LinkNodeType nodeType = LinkNodeSemantics
				.tryParseCanonicalType(buffer.readUtf(NODE_TYPE_TOKEN_MAX_LENGTH))
				.orElse(LinkNodeType.CORE);
			long serial = buffer.readLong();
			boolean allocated = buffer.readBoolean();
			boolean retired = buffer.readBoolean();
			boolean online = buffer.readBoolean();
			boolean active = buffer.readBoolean();
			int inputPower = buffer.readVarInt();
			int outputPower = buffer.readVarInt();
			boolean readable = buffer.readBoolean();
			values.add(
				new StatePanelNetwork.StatePanelSnapshotEntry(
					nodeType,
					serial,
					allocated,
					retired,
					online,
					active,
					inputPower,
					outputPower,
					readable
				)
			);
		}
		return List.copyOf(values);
	}

	/**
	 * 编码状态面板反馈回执。
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
	 * 解码状态面板反馈回执。
	 */
	static DecodedFeedbackPayload decodeFeedbackPayload(FriendlyByteBuf buffer) {
		boolean success = buffer.readBoolean();
		String messageKey = buffer.readUtf(FEEDBACK_MESSAGE_KEY_MAX_LENGTH);
		int size = Math.max(0, buffer.readVarInt());
		List<String> messageArgs = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			messageArgs.add(buffer.readUtf(FEEDBACK_MESSAGE_ARG_MAX_LENGTH));
		}
		return new DecodedFeedbackPayload(success, messageKey, List.copyOf(messageArgs));
	}

	/**
	 * 读取状态面板批量输入沿用的统一长度上限。
	 */
	private static int resolveMaxInputLength() {
		return RedstoneLinkConfig.command().linkSetMaxInputLength();
	}

	/**
	 * 订阅请求解码结果。
	 */
	record DecodedSubscribePayload(String nodeTypeToken, String serialExpression) {
	}

	/**
	 * 删除请求解码结果。
	 */
	record DecodedRemovePayload(String nodeTypeToken, long serial) {
	}

	/**
	 * 反馈回执解码结果。
	 */
	record DecodedFeedbackPayload(boolean success, String messageKey, List<String> messageArgs) {
	}
}
