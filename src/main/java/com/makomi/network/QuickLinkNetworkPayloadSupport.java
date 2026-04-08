package com.makomi.network;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.QuickLinkToolData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 快速连接工具网络载荷编解码支撑。
 */
final class QuickLinkNetworkPayloadSupport {
	private static final int TOKEN_MAX_LENGTH = PairingNetworkPayloadSupport.NODE_TYPE_MAX_LENGTH;
	private static final int DIMENSION_KEY_MAX_LENGTH = PairingNetworkPayloadSupport.DIMENSION_KEY_MAX_LENGTH;
	private static final int FEEDBACK_MESSAGE_KEY_MAX_LENGTH = 256;
	private static final int FEEDBACK_MESSAGE_ARG_MAX_LENGTH = 512;

	private QuickLinkNetworkPayloadSupport() {
	}

	/**
	 * 编码编辑器打开包。
	 */
	static void encodeOpenEditorPayload(FriendlyByteBuf buffer, QuickLinkToolData.Snapshot snapshot) {
		int maxInputLength = resolveMaxInputLength();
		buffer.writeUtf(snapshot.mode().token(), TOKEN_MAX_LENGTH);
		buffer.writeUtf(LinkNodeSemantics.toSemanticName(snapshot.serialCacheType()), TOKEN_MAX_LENGTH);
		buffer.writeUtf(snapshot.serialCacheExpression(), maxInputLength);
		buffer.writeUtf(snapshot.channelCache(), maxInputLength);
	}

	/**
	 * 解码编辑器打开包。
	 */
	static QuickLinkToolData.Snapshot decodeOpenEditorPayload(FriendlyByteBuf buffer) {
		int maxInputLength = resolveMaxInputLength();
		return QuickLinkToolData.fromTokens(
			buffer.readUtf(TOKEN_MAX_LENGTH),
			buffer.readUtf(TOKEN_MAX_LENGTH),
			buffer.readUtf(maxInputLength),
			buffer.readUtf(maxInputLength)
		);
	}

	/**
	 * 编码保存请求。
	 */
	static void encodeSavePayload(
		FriendlyByteBuf buffer,
		String modeToken,
		String serialCacheTypeToken,
		String serialCacheExpression,
		String channelCache
	) {
		int maxInputLength = resolveMaxInputLength();
		buffer.writeUtf(modeToken == null ? "" : modeToken, TOKEN_MAX_LENGTH);
		buffer.writeUtf(serialCacheTypeToken == null ? "" : serialCacheTypeToken, TOKEN_MAX_LENGTH);
		buffer.writeUtf(serialCacheExpression == null ? "" : serialCacheExpression, maxInputLength);
		buffer.writeUtf(channelCache == null ? "" : channelCache, maxInputLength);
	}

	/**
	 * 解码保存请求。
	 */
	static DecodedSavePayload decodeSavePayload(FriendlyByteBuf buffer) {
		int maxInputLength = resolveMaxInputLength();
		return new DecodedSavePayload(
			buffer.readUtf(TOKEN_MAX_LENGTH),
			buffer.readUtf(TOKEN_MAX_LENGTH),
			buffer.readUtf(maxInputLength),
			buffer.readUtf(maxInputLength)
		);
	}

	/**
	 * 编码应用请求。
	 */
	static void encodeBlockTargetPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial
	) {
		buffer.writeUtf(dimensionKey == null ? "" : dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPosLong);
		buffer.writeUtf(expectedNodeTypeToken == null ? "" : expectedNodeTypeToken, TOKEN_MAX_LENGTH);
		buffer.writeVarLong(Math.max(0L, expectedNodeSerial));
	}

	/**
	 * 解码应用请求。
	 */
	static DecodedBlockTargetPayload decodeBlockTargetPayload(FriendlyByteBuf buffer) {
		return new DecodedBlockTargetPayload(
			buffer.readUtf(DIMENSION_KEY_MAX_LENGTH),
			buffer.readLong(),
			buffer.readUtf(TOKEN_MAX_LENGTH),
			buffer.readVarLong()
		);
	}

	/**
	 * 编码正式 apply 请求。
	 */
	static void encodeApplyPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		encodeBlockTargetPayload(buffer, dimensionKey, blockPosLong, expectedNodeTypeToken, expectedNodeSerial);
		buffer.writeVarLong(Math.max(0L, expectedCoreRevision));
		buffer.writeVarLong(Math.max(0L, expectedSourceRevision));
	}

	/**
	 * 解码正式 apply 请求。
	 */
	static DecodedApplyPayload decodeApplyPayload(FriendlyByteBuf buffer) {
		DecodedBlockTargetPayload decodedTarget = decodeBlockTargetPayload(buffer);
		return new DecodedApplyPayload(
			decodedTarget.dimensionKey(),
			decodedTarget.blockPosLong(),
			decodedTarget.expectedNodeTypeToken(),
			decodedTarget.expectedNodeSerial(),
			buffer.readVarLong(),
			buffer.readVarLong()
		);
	}

	/**
	 * 编码 apply revision 基线回包。
	 */
	static void encodeApplyBaselinePayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) {
		encodeBlockTargetPayload(buffer, dimensionKey, blockPosLong, expectedNodeTypeToken, expectedNodeSerial);
		buffer.writeVarLong(Math.max(0L, graphRevision));
		buffer.writeVarLong(Math.max(0L, sourceRevision));
		buffer.writeVarLong(Math.max(0L, coreRevision));
	}

	/**
	 * 解码 apply revision 基线回包。
	 */
	static DecodedApplyBaselinePayload decodeApplyBaselinePayload(FriendlyByteBuf buffer) {
		DecodedBlockTargetPayload decodedTarget = decodeBlockTargetPayload(buffer);
		return new DecodedApplyBaselinePayload(
			decodedTarget.dimensionKey(),
			decodedTarget.blockPosLong(),
			decodedTarget.expectedNodeTypeToken(),
			decodedTarget.expectedNodeSerial(),
			buffer.readVarLong(),
			buffer.readVarLong(),
			buffer.readVarLong()
		);
	}

	/**
	 * 编码应用结果回执。
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
	 * 解码应用结果回执。
	 */
	static DecodedFeedbackPayload decodeFeedbackPayload(FriendlyByteBuf buffer) {
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
	 * 读取基于 `link set` 的统一输入长度上限。
	 */
	private static int resolveMaxInputLength() {
		return RedstoneLinkConfig.command().linkSetMaxInputLength();
	}

	/**
	 * 保存请求解码结果。
	 */
	record DecodedSavePayload(
		String modeToken,
		String serialCacheTypeToken,
		String serialCacheExpression,
		String channelCache
	) {
	}

	/**
	 * 应用请求解码结果。
	 */
	record DecodedBlockTargetPayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial
	) {
	}

	/**
	 * 正式 apply 请求解码结果。
	 */
	record DecodedApplyPayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
	}

	/**
	 * apply revision 基线回包解码结果。
	 */
	record DecodedApplyBaselinePayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) {
	}

	/**
	 * 应用结果回执解码结果。
	 */
	record DecodedFeedbackPayload(boolean success, String messageKey, List<String> messageArgs) {
	}
}
