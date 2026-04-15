package com.makomi.network;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.data.LinkFilterSignalMode;
import com.makomi.data.LinkFilterSignalThresholdSource;
import com.makomi.data.NodeAliasSavedData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * 过滤器网络 payload 编解码辅助。
 */
final class LinkFilterNetworkPayloadSupport {
	static final int DIMENSION_KEY_MAX_LENGTH = 128;
	private static final int TARGET_KIND_TOKEN_MAX_LENGTH = 32;
	private static final int FILTER_KIND_TOKEN_MAX_LENGTH = 16;
	private static final int MODE_TOKEN_MAX_LENGTH = 32;
	private static final int DISPLAY_ALIAS_MAX_LENGTH = NodeAliasSavedData.maxAliasLength();
	private static final int MESSAGE_KEY_MAX_LENGTH = 128;
	private static final int MESSAGE_ARG_MAX_LENGTH = 128;
	private static final int MAX_FEEDBACK_ARGS = 8;

	private LinkFilterNetworkPayloadSupport() {
	}

	/**
	 * 编码过滤器编辑器打开包。
	 */
	static void encodeOpenEditorPayload(
		FriendlyByteBuf buffer,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		LinkFilterKind filterKind,
		String displayAlias,
		LinkFilterConfigSnapshot configSnapshot
	) {
		buffer.writeUtf(targetKind == null ? "" : targetKind.token(), TARGET_KIND_TOKEN_MAX_LENGTH);
		buffer.writeUtf(dimensionKey == null ? "" : dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPosLong);
		buffer.writeInt(selectedSlot);
		buffer.writeUtf(filterKind == null ? "" : filterKind.token(), FILTER_KIND_TOKEN_MAX_LENGTH);
		buffer.writeUtf(displayAlias == null ? "" : displayAlias, DISPLAY_ALIAS_MAX_LENGTH);
		encodeConfigSnapshot(buffer, configSnapshot);
	}

	/**
	 * 解码过滤器编辑器打开包。
	 */
	static DecodedOpenEditorPayload decodeOpenEditorPayload(FriendlyByteBuf buffer) {
		LinkFilterEditorTargetKind targetKind = LinkFilterEditorTargetKind
			.tryParseToken(buffer.readUtf(TARGET_KIND_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown editor target kind"));
		String dimensionKey = buffer.readUtf(DIMENSION_KEY_MAX_LENGTH);
		long blockPosLong = buffer.readLong();
		int selectedSlot = buffer.readInt();
		LinkFilterKind filterKind = LinkFilterKind
			.tryParseToken(buffer.readUtf(FILTER_KIND_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown filter kind"));
		String displayAlias = buffer.readUtf(DISPLAY_ALIAS_MAX_LENGTH);
		return new DecodedOpenEditorPayload(
			targetKind,
			dimensionKey,
			blockPosLong,
			selectedSlot,
			filterKind,
			displayAlias,
			decodeConfigSnapshot(buffer)
		);
	}

	/**
	 * 编码过滤器保存请求。
	 */
	static void encodeSavePayload(
		FriendlyByteBuf buffer,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		LinkFilterKind filterKind,
		String displayAlias,
		LinkFilterConfigSnapshot configSnapshot
	) {
		encodeOpenEditorPayload(
			buffer,
			targetKind,
			dimensionKey,
			blockPosLong,
			selectedSlot,
			filterKind,
			displayAlias,
			configSnapshot
		);
	}

	/**
	 * 解码过滤器保存请求。
	 */
	static DecodedSavePayload decodeSavePayload(FriendlyByteBuf buffer) {
		DecodedOpenEditorPayload decoded = decodeOpenEditorPayload(buffer);
		return new DecodedSavePayload(
			decoded.targetKind(),
			decoded.dimensionKey(),
			decoded.blockPosLong(),
			decoded.selectedSlot(),
			decoded.filterKind(),
			decoded.displayAlias(),
			decoded.configSnapshot()
		);
	}

	/**
	 * 编码统一反馈回执。
	 */
	static void encodeFeedbackPayload(
		FriendlyByteBuf buffer,
		boolean success,
		String messageKey,
		List<String> messageArgs
	) {
		buffer.writeBoolean(success);
		buffer.writeUtf(messageKey == null ? "" : messageKey, MESSAGE_KEY_MAX_LENGTH);
		List<String> normalizedArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		buffer.writeVarInt(Math.min(MAX_FEEDBACK_ARGS, normalizedArgs.size()));
		for (int index = 0; index < Math.min(MAX_FEEDBACK_ARGS, normalizedArgs.size()); index++) {
			buffer.writeUtf(normalizedArgs.get(index), MESSAGE_ARG_MAX_LENGTH);
		}
	}

	/**
	 * 解码统一反馈回执。
	 */
	static DecodedFeedbackPayload decodeFeedbackPayload(FriendlyByteBuf buffer) {
		boolean success = buffer.readBoolean();
		String messageKey = buffer.readUtf(MESSAGE_KEY_MAX_LENGTH);
		int argCount = Mth.clamp(buffer.readVarInt(), 0, MAX_FEEDBACK_ARGS);
		List<String> messageArgs = new ArrayList<>(argCount);
		for (int index = 0; index < argCount; index++) {
			messageArgs.add(buffer.readUtf(MESSAGE_ARG_MAX_LENGTH));
		}
		return new DecodedFeedbackPayload(success, messageKey, List.copyOf(messageArgs));
	}

	/**
	 * 编码过滤器配置快照。
	 */
	private static void encodeConfigSnapshot(FriendlyByteBuf buffer, LinkFilterConfigSnapshot configSnapshot) {
		LinkFilterConfigSnapshot normalized = configSnapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : configSnapshot;
		buffer.writeUtf(
			normalized.serialExpression(),
			RedstoneLinkConfig.command().linkSetMaxInputLength()
		);
		buffer.writeUtf(normalized.nodeSetMode().token(), MODE_TOKEN_MAX_LENGTH);
		buffer.writeUtf(normalized.signalThresholdSource().token(), MODE_TOKEN_MAX_LENGTH);
		buffer.writeVarInt(normalized.fixedSignalThreshold());
		buffer.writeUtf(normalized.signalMode().token(), MODE_TOKEN_MAX_LENGTH);
	}

	/**
	 * 解码过滤器配置快照。
	 */
	private static LinkFilterConfigSnapshot decodeConfigSnapshot(FriendlyByteBuf buffer) {
		String serialExpression = buffer.readUtf(RedstoneLinkConfig.command().linkSetMaxInputLength());
		LinkFilterNodeSetMode nodeSetMode = LinkFilterNodeSetMode
			.tryParseToken(buffer.readUtf(MODE_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown node set mode"));
		LinkFilterSignalThresholdSource signalThresholdSource = LinkFilterSignalThresholdSource
			.tryParseToken(buffer.readUtf(MODE_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown signal threshold source"));
		int fixedSignalThreshold = Mth.clamp(buffer.readVarInt(), 0, 15);
		LinkFilterSignalMode signalMode = LinkFilterSignalMode
			.tryParseToken(buffer.readUtf(MODE_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown signal mode"));
		return new LinkFilterConfigSnapshot(
			serialExpression,
			nodeSetMode,
			signalThresholdSource,
			fixedSignalThreshold,
			signalMode
		);
	}

	/**
	 * 打开包解码结果。
	 */
	record DecodedOpenEditorPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		LinkFilterKind filterKind,
		String displayAlias,
		LinkFilterConfigSnapshot configSnapshot
	) {
	}

	/**
	 * 保存包解码结果。
	 */
	record DecodedSavePayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		LinkFilterKind filterKind,
		String displayAlias,
		LinkFilterConfigSnapshot configSnapshot
	) {
	}

	/**
	 * 反馈包解码结果。
	 */
	record DecodedFeedbackPayload(boolean success, String messageKey, List<String> messageArgs) {
	}
}
