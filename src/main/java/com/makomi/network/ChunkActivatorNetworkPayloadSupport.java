package com.makomi.network;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorConfigStateSnapshot;
import com.makomi.data.ChunkActivatorMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasSavedData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * 区块激活器网络 payload 编解码辅助。
 */
final class ChunkActivatorNetworkPayloadSupport {
	private static final int DIMENSION_KEY_MAX_LENGTH = 128;
	private static final int TARGET_KIND_TOKEN_MAX_LENGTH = 32;
	private static final int ACTIVE_TYPE_TOKEN_MAX_LENGTH = 32;
	private static final int MODE_TOKEN_MAX_LENGTH = 32;
	private static final int DISPLAY_ALIAS_MAX_LENGTH = NodeAliasSavedData.maxAliasLength();
	private static final int MESSAGE_KEY_MAX_LENGTH = 128;
	private static final int MESSAGE_ARG_MAX_LENGTH = 128;
	private static final int MAX_FEEDBACK_ARGS = 8;

	private ChunkActivatorNetworkPayloadSupport() {
	}

	static void encodeOpenEditorPayload(
		FriendlyByteBuf buffer,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String displayAlias,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) {
		buffer.writeUtf(targetKind == null ? "" : targetKind.token(), TARGET_KIND_TOKEN_MAX_LENGTH);
		buffer.writeUtf(dimensionKey == null ? "" : dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPosLong);
		buffer.writeInt(selectedSlot);
		buffer.writeUtf(displayAlias == null ? "" : displayAlias, DISPLAY_ALIAS_MAX_LENGTH);
		encodeConfigStateSnapshot(buffer, configStateSnapshot);
	}

	static DecodedOpenEditorPayload decodeOpenEditorPayload(FriendlyByteBuf buffer) {
		LinkFilterEditorTargetKind targetKind = LinkFilterEditorTargetKind
			.tryParseToken(buffer.readUtf(TARGET_KIND_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown editor target kind"));
		String dimensionKey = buffer.readUtf(DIMENSION_KEY_MAX_LENGTH);
		long blockPosLong = buffer.readLong();
		int selectedSlot = buffer.readInt();
		String displayAlias = buffer.readUtf(DISPLAY_ALIAS_MAX_LENGTH);
		return new DecodedOpenEditorPayload(
			targetKind,
			dimensionKey,
			blockPosLong,
			selectedSlot,
			displayAlias,
			decodeConfigStateSnapshot(buffer)
		);
	}

	static void encodeSavePayload(
		FriendlyByteBuf buffer,
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String displayAlias,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) {
		encodeOpenEditorPayload(buffer, targetKind, dimensionKey, blockPosLong, selectedSlot, displayAlias, configStateSnapshot);
	}

	static DecodedSavePayload decodeSavePayload(FriendlyByteBuf buffer) {
		DecodedOpenEditorPayload decoded = decodeOpenEditorPayload(buffer);
		return new DecodedSavePayload(
			decoded.targetKind(),
			decoded.dimensionKey(),
			decoded.blockPosLong(),
			decoded.selectedSlot(),
			decoded.displayAlias(),
			decoded.configStateSnapshot()
		);
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

	private static void encodeConfigStateSnapshot(FriendlyByteBuf buffer, ChunkActivatorConfigStateSnapshot configStateSnapshot) {
		ChunkActivatorConfigStateSnapshot normalized = configStateSnapshot == null
			? new ChunkActivatorConfigStateSnapshot(null, null, null)
			: configStateSnapshot;
		buffer.writeUtf(ChunkActivatorConfigStateSnapshot.toTypeToken(normalized.activeType()), ACTIVE_TYPE_TOKEN_MAX_LENGTH);
		encodeSingleConfigSnapshot(buffer, normalized.triggerSourceConfig());
		encodeSingleConfigSnapshot(buffer, normalized.coreConfig());
	}

	private static ChunkActivatorConfigStateSnapshot decodeConfigStateSnapshot(FriendlyByteBuf buffer) {
		LinkNodeType activeType =
			ChunkActivatorConfigStateSnapshot
				.tryParseTypeToken(buffer.readUtf(ACTIVE_TYPE_TOKEN_MAX_LENGTH))
				.orElseThrow(() -> new IllegalArgumentException("Unknown chunk activator active type"));
		return new ChunkActivatorConfigStateSnapshot(
			activeType,
			decodeSingleConfigSnapshot(buffer),
			decodeSingleConfigSnapshot(buffer)
		);
	}

	private static void encodeSingleConfigSnapshot(FriendlyByteBuf buffer, ChunkActivatorConfigSnapshot configSnapshot) {
		ChunkActivatorConfigSnapshot normalized = configSnapshot == null
			? new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			: configSnapshot;
		buffer.writeUtf(normalized.serialExpression(), RedstoneLinkConfig.command().linkSetMaxInputLength());
		buffer.writeUtf(normalized.mode().token(), MODE_TOKEN_MAX_LENGTH);
	}

	private static ChunkActivatorConfigSnapshot decodeSingleConfigSnapshot(FriendlyByteBuf buffer) {
		String serialExpression = buffer.readUtf(RedstoneLinkConfig.command().linkSetMaxInputLength());
		ChunkActivatorMode mode = ChunkActivatorMode
			.tryParseToken(buffer.readUtf(MODE_TOKEN_MAX_LENGTH))
			.orElseThrow(() -> new IllegalArgumentException("Unknown chunk activator mode"));
		return new ChunkActivatorConfigSnapshot(serialExpression, mode);
	}

	record DecodedOpenEditorPayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String displayAlias,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) {
	}

	record DecodedSavePayload(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String displayAlias,
		ChunkActivatorConfigStateSnapshot configStateSnapshot
	) {
	}

	record DecodedFeedbackPayload(boolean success, String messageKey, List<String> messageArgs) {
	}
}
