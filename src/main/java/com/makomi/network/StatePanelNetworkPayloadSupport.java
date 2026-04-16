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
	private static final int DISPLAY_TEXT_MAX_LENGTH = 96;
	private static final int FEEDBACK_MESSAGE_KEY_MAX_LENGTH = 256;
	private static final int FEEDBACK_MESSAGE_ARG_MAX_LENGTH = 512;
	private static final int RECORDING_TITLE_MAX_LENGTH = 96;
	private static final int RECORDING_NODE_KEY_MAX_LENGTH = 64;
	private static final int RECORDING_FILE_NAME_MAX_LENGTH = 160;
	private static final int RECORDING_EXPORT_CHUNK_MAX_BYTES = 32768;

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
			buffer.writeUtf(entry.displayText(), DISPLAY_TEXT_MAX_LENGTH);
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
			String displayText = buffer.readUtf(DISPLAY_TEXT_MAX_LENGTH);
			values.add(new StatePanelNetwork.SubscriptionEntryPayload(nodeType, serial, displayText));
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
			buffer.writeUtf(entry.displayText(), DISPLAY_TEXT_MAX_LENGTH);
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
			String displayText = buffer.readUtf(DISPLAY_TEXT_MAX_LENGTH);
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
					displayText,
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
	 * 编码录制开始请求。
	 */
	static void encodeRecordingStartPayload(
		FriendlyByteBuf buffer,
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		int durationTicks,
		boolean autoOpenWeb,
		List<String> selectedNodeKeys
	) {
		buffer.writeUtf(title == null ? "" : title, RECORDING_TITLE_MAX_LENGTH);
		buffer.writeVarInt(Math.max(0, sampleEveryTicks));
		buffer.writeVarInt(Math.max(0, capacityPerNode));
		buffer.writeVarInt(Math.max(0, durationTicks));
		buffer.writeBoolean(autoOpenWeb);
		List<String> normalizedNodeKeys = selectedNodeKeys == null ? List.of() : List.copyOf(selectedNodeKeys);
		buffer.writeVarInt(normalizedNodeKeys.size());
		for (String nodeKey : normalizedNodeKeys) {
			buffer.writeUtf(nodeKey == null ? "" : nodeKey, RECORDING_NODE_KEY_MAX_LENGTH);
		}
	}

	/**
	 * 解码录制开始请求。
	 */
	static DecodedRecordingStartPayload decodeRecordingStartPayload(FriendlyByteBuf buffer) {
		String title = buffer.readUtf(RECORDING_TITLE_MAX_LENGTH);
		int sampleEveryTicks = Math.max(0, buffer.readVarInt());
		int capacityPerNode = Math.max(0, buffer.readVarInt());
		int durationTicks = Math.max(0, buffer.readVarInt());
		boolean autoOpenWeb = buffer.readBoolean();
		int selectedNodeKeyCount = Math.max(0, buffer.readVarInt());
		List<String> selectedNodeKeys = new ArrayList<>(selectedNodeKeyCount);
		for (int index = 0; index < selectedNodeKeyCount; index++) {
			selectedNodeKeys.add(buffer.readUtf(RECORDING_NODE_KEY_MAX_LENGTH));
		}
		return new DecodedRecordingStartPayload(
			title,
			sampleEveryTicks,
			capacityPerNode,
			durationTicks,
			autoOpenWeb,
			List.copyOf(selectedNodeKeys)
		);
	}

	/**
	 * 编码录制会话状态回执。
	 */
	static void encodeRecordingSessionPayload(
		FriendlyByteBuf buffer,
		boolean active,
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		int durationTicks,
		boolean autoOpenWeb,
		int subscriptionCount,
		int mountedCount,
		List<String> selectedNodeKeys,
		long startedTick
	) {
		buffer.writeBoolean(active);
		buffer.writeUtf(title == null ? "" : title, RECORDING_TITLE_MAX_LENGTH);
		buffer.writeVarInt(Math.max(0, sampleEveryTicks));
		buffer.writeVarInt(Math.max(0, capacityPerNode));
		buffer.writeVarInt(Math.max(0, durationTicks));
		buffer.writeBoolean(autoOpenWeb);
		buffer.writeVarInt(Math.max(0, subscriptionCount));
		buffer.writeVarInt(Math.max(0, mountedCount));
		List<String> normalizedNodeKeys = selectedNodeKeys == null ? List.of() : List.copyOf(selectedNodeKeys);
		buffer.writeVarInt(normalizedNodeKeys.size());
		for (String nodeKey : normalizedNodeKeys) {
			buffer.writeUtf(nodeKey == null ? "" : nodeKey, RECORDING_NODE_KEY_MAX_LENGTH);
		}
		buffer.writeLong(Math.max(0L, startedTick));
	}

	/**
	 * 解码录制会话状态回执。
	 */
	static DecodedRecordingSessionPayload decodeRecordingSessionPayload(FriendlyByteBuf buffer) {
		boolean active = buffer.readBoolean();
		String title = buffer.readUtf(RECORDING_TITLE_MAX_LENGTH);
		int sampleEveryTicks = Math.max(0, buffer.readVarInt());
		int capacityPerNode = Math.max(0, buffer.readVarInt());
		int durationTicks = Math.max(0, buffer.readVarInt());
		boolean autoOpenWeb = buffer.readBoolean();
		int subscriptionCount = Math.max(0, buffer.readVarInt());
		int mountedCount = Math.max(0, buffer.readVarInt());
		int selectedNodeKeyCount = Math.max(0, buffer.readVarInt());
		List<String> selectedNodeKeys = new ArrayList<>(selectedNodeKeyCount);
		for (int index = 0; index < selectedNodeKeyCount; index++) {
			selectedNodeKeys.add(buffer.readUtf(RECORDING_NODE_KEY_MAX_LENGTH));
		}
		return new DecodedRecordingSessionPayload(
			active,
			title,
			sampleEveryTicks,
			capacityPerNode,
			durationTicks,
			autoOpenWeb,
			subscriptionCount,
			mountedCount,
			List.copyOf(selectedNodeKeys),
			Math.max(0L, buffer.readLong())
		);
	}

	/**
	 * 编码录制结果分块。
	 */
	static void encodeRecordingExportChunkPayload(
		FriendlyByteBuf buffer,
		String fileName,
		int chunkIndex,
		int totalChunks,
		boolean autoOpenWeb,
		byte[] chunkBytes
	) {
		byte[] normalizedChunkBytes = chunkBytes == null ? new byte[0] : chunkBytes;
		buffer.writeUtf(fileName == null ? "" : fileName, RECORDING_FILE_NAME_MAX_LENGTH);
		buffer.writeVarInt(Math.max(0, chunkIndex));
		buffer.writeVarInt(Math.max(0, totalChunks));
		buffer.writeBoolean(autoOpenWeb);
		buffer.writeByteArray(normalizedChunkBytes);
	}

	/**
	 * 解码录制结果分块。
	 */
	static DecodedRecordingExportChunkPayload decodeRecordingExportChunkPayload(FriendlyByteBuf buffer) {
		return new DecodedRecordingExportChunkPayload(
			buffer.readUtf(RECORDING_FILE_NAME_MAX_LENGTH),
			Math.max(0, buffer.readVarInt()),
			Math.max(0, buffer.readVarInt()),
			buffer.readBoolean(),
			buffer.readByteArray(RECORDING_EXPORT_CHUNK_MAX_BYTES)
		);
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

	/**
	 * 录制开始请求解码结果。
	 */
	record DecodedRecordingStartPayload(
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		int durationTicks,
		boolean autoOpenWeb,
		List<String> selectedNodeKeys
	) {
	}

	/**
	 * 录制会话状态解码结果。
	 */
	record DecodedRecordingSessionPayload(
		boolean active,
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		int durationTicks,
		boolean autoOpenWeb,
		int subscriptionCount,
		int mountedCount,
		List<String> selectedNodeKeys,
		long startedTick
	) {
	}

	/**
	 * 录制结果分块解码结果。
	 */
	record DecodedRecordingExportChunkPayload(
		String fileName,
		int chunkIndex,
		int totalChunks,
		boolean autoOpenWeb,
		byte[] chunkBytes
	) {
	}
}
