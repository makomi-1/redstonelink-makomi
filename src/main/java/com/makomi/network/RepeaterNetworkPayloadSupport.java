package com.makomi.network;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterDelay;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * 转发器网络 payload 编解码辅助。
 */
final class RepeaterNetworkPayloadSupport {
	private static final int DIMENSION_KEY_MAX_LENGTH = 128;
	private static final int TARGET_KIND_TOKEN_MAX_LENGTH = 32;
	private static final int DISPLAY_ALIAS_MAX_LENGTH = NodeAliasSavedData.maxAliasLength();
	private static final int DELAY_TOKEN_MAX_LENGTH = 32;
	private static final int MESSAGE_KEY_MAX_LENGTH = 128;
	private static final int MESSAGE_ARG_MAX_LENGTH = 128;
	private static final int SIDE_TOKEN_MAX_LENGTH = 16;
	private static final int MAX_FEEDBACK_ARGS = 8;

	private RepeaterNetworkPayloadSupport() {
	}

	static void encodeOpenEditorPayload(
		FriendlyByteBuf buffer,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String displayAlias,
		RepeaterConfigSnapshot configSnapshot,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		buffer.writeUtf(targetKind == null ? "" : targetKind.token(), TARGET_KIND_TOKEN_MAX_LENGTH);
		buffer.writeUtf(dimensionKey == null ? "" : dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPosLong);
		buffer.writeInt(selectedSlot);
		buffer.writeVarLong(Math.max(0L, serial));
		buffer.writeUtf(displayAlias == null ? "" : displayAlias, DISPLAY_ALIAS_MAX_LENGTH);
		encodeConfigSnapshot(buffer, configSnapshot);
		buffer.writeVarLong(Math.max(0L, expectedCoreRevision));
		buffer.writeVarLong(Math.max(0L, expectedSourceRevision));
	}

	static DecodedOpenEditorPayload decodeOpenEditorPayload(FriendlyByteBuf buffer) {
		LinkFilterEditorTargetKind targetKind = LinkFilterEditorTargetKind
			.tryParseToken(buffer.readUtf(TARGET_KIND_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown repeater editor target kind"));
		String dimensionKey = buffer.readUtf(DIMENSION_KEY_MAX_LENGTH);
		long blockPosLong = buffer.readLong();
		int selectedSlot = buffer.readInt();
		long serial = Math.max(0L, buffer.readVarLong());
		String displayAlias = buffer.readUtf(DISPLAY_ALIAS_MAX_LENGTH);
		RepeaterConfigSnapshot configSnapshot = decodeConfigSnapshot(buffer);
		long expectedCoreRevision = Math.max(0L, buffer.readVarLong());
		long expectedSourceRevision = Math.max(0L, buffer.readVarLong());
		return new DecodedOpenEditorPayload(
			targetKind,
			dimensionKey,
			blockPosLong,
			selectedSlot,
			serial,
			displayAlias,
			configSnapshot,
			expectedCoreRevision,
			expectedSourceRevision
		);
	}

	static void encodeSavePayload(
		FriendlyByteBuf buffer,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String displayAlias,
		RepeaterConfigSnapshot configSnapshot,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		encodeOpenEditorPayload(
			buffer,
			targetKind,
			dimensionKey,
			blockPosLong,
			selectedSlot,
			serial,
			displayAlias,
			configSnapshot,
			expectedCoreRevision,
			expectedSourceRevision
		);
	}

	static DecodedSavePayload decodeSavePayload(FriendlyByteBuf buffer) {
		DecodedOpenEditorPayload decoded = decodeOpenEditorPayload(buffer);
		return new DecodedSavePayload(
			decoded.targetKind(),
			decoded.dimensionKey(),
			decoded.blockPosLong(),
			decoded.selectedSlot(),
			decoded.serial(),
			decoded.displayAlias(),
			decoded.configSnapshot(),
			decoded.expectedCoreRevision(),
			decoded.expectedSourceRevision()
		);
	}

	static void encodeOpenPairingPayload(
		FriendlyByteBuf buffer,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String sideToken
	) {
		buffer.writeUtf(targetKind == null ? "" : targetKind.token(), TARGET_KIND_TOKEN_MAX_LENGTH);
		buffer.writeUtf(dimensionKey == null ? "" : dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPosLong);
		buffer.writeInt(selectedSlot);
		buffer.writeVarLong(Math.max(0L, serial));
		buffer.writeUtf(sideToken == null ? "" : sideToken, SIDE_TOKEN_MAX_LENGTH);
	}

	static DecodedOpenPairingPayload decodeOpenPairingPayload(FriendlyByteBuf buffer) {
		LinkFilterEditorTargetKind targetKind = LinkFilterEditorTargetKind
			.tryParseToken(buffer.readUtf(TARGET_KIND_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown repeater editor target kind"));
		String dimensionKey = buffer.readUtf(DIMENSION_KEY_MAX_LENGTH);
		long blockPosLong = buffer.readLong();
		int selectedSlot = buffer.readInt();
		long serial = Math.max(0L, buffer.readVarLong());
		String sideToken = buffer.readUtf(SIDE_TOKEN_MAX_LENGTH);
		return new DecodedOpenPairingPayload(targetKind, dimensionKey, blockPosLong, selectedSlot, serial, sideToken);
	}

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

	private static void encodeConfigSnapshot(FriendlyByteBuf buffer, RepeaterConfigSnapshot configSnapshot) {
		RepeaterConfigSnapshot normalized = configSnapshot == null ? RepeaterConfigSnapshot.empty() : configSnapshot;
		buffer.writeUtf(normalized.inputSerialExpression(), RedstoneLinkConfig.command().linkSetMaxInputLength());
		buffer.writeUtf(normalized.outputSerialExpression(), RedstoneLinkConfig.command().linkSetMaxInputLength());
		buffer.writeUtf(normalized.delay().token(), DELAY_TOKEN_MAX_LENGTH);
	}

	private static RepeaterConfigSnapshot decodeConfigSnapshot(FriendlyByteBuf buffer) {
		String inputSerialExpression = buffer.readUtf(RedstoneLinkConfig.command().linkSetMaxInputLength());
		String outputSerialExpression = buffer.readUtf(RedstoneLinkConfig.command().linkSetMaxInputLength());
		RepeaterDelay delay = RepeaterDelay
			.tryParseToken(buffer.readUtf(DELAY_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown repeater delay"));
		return new RepeaterConfigSnapshot(inputSerialExpression, outputSerialExpression, delay);
	}

	record DecodedOpenEditorPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String displayAlias,
		RepeaterConfigSnapshot configSnapshot,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
	}

	record DecodedSavePayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String displayAlias,
		RepeaterConfigSnapshot configSnapshot,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
	}

	record DecodedOpenPairingPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String sideToken
	) {
	}

	record DecodedFeedbackPayload(boolean success, String messageKey, List<String> messageArgs) {
	}
}
