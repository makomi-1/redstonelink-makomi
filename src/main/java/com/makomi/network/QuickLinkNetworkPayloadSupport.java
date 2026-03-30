package com.makomi.network;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.QuickLinkToolData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 快速连接工具网络载荷编解码支撑。
 */
final class QuickLinkNetworkPayloadSupport {
	private QuickLinkNetworkPayloadSupport() {
	}

	/**
	 * 编码编辑器打开包。
	 */
	static void encodeOpenEditorPayload(FriendlyByteBuf buffer, QuickLinkToolData.Snapshot snapshot) {
		buffer.writeUtf(snapshot.mode().token());
		buffer.writeUtf(LinkNodeSemantics.toSemanticName(snapshot.serialCacheType()));
		buffer.writeUtf(snapshot.serialCacheExpression());
		buffer.writeUtf(snapshot.channelCache());
	}

	/**
	 * 解码编辑器打开包。
	 */
	static QuickLinkToolData.Snapshot decodeOpenEditorPayload(FriendlyByteBuf buffer) {
		return QuickLinkToolData.fromTokens(
			buffer.readUtf(),
			buffer.readUtf(),
			buffer.readUtf(),
			buffer.readUtf()
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
		buffer.writeUtf(modeToken == null ? "" : modeToken);
		buffer.writeUtf(serialCacheTypeToken == null ? "" : serialCacheTypeToken);
		buffer.writeUtf(serialCacheExpression == null ? "" : serialCacheExpression);
		buffer.writeUtf(channelCache == null ? "" : channelCache);
	}

	/**
	 * 解码保存请求。
	 */
	static DecodedSavePayload decodeSavePayload(FriendlyByteBuf buffer) {
		return new DecodedSavePayload(
			buffer.readUtf(),
			buffer.readUtf(),
			buffer.readUtf(),
			buffer.readUtf()
		);
	}

	/**
	 * 编码应用请求。
	 */
	static void encodeBlockTargetPayload(FriendlyByteBuf buffer, String dimensionKey, long blockPosLong) {
		buffer.writeUtf(dimensionKey == null ? "" : dimensionKey);
		buffer.writeLong(blockPosLong);
	}

	/**
	 * 解码应用请求。
	 */
	static DecodedBlockTargetPayload decodeBlockTargetPayload(FriendlyByteBuf buffer) {
		return new DecodedBlockTargetPayload(buffer.readUtf(), buffer.readLong());
	}

	/**
	 * 编码应用结果回执。
	 */
	static void encodeFeedbackPayload(FriendlyByteBuf buffer, boolean success, String messageKey, List<String> messageArgs) {
		buffer.writeBoolean(success);
		buffer.writeUtf(messageKey == null ? "" : messageKey);
		List<String> args = messageArgs == null ? List.of() : List.copyOf(messageArgs);
		buffer.writeVarInt(args.size());
		for (String arg : args) {
			buffer.writeUtf(arg == null ? "" : arg);
		}
	}

	/**
	 * 解码应用结果回执。
	 */
	static DecodedFeedbackPayload decodeFeedbackPayload(FriendlyByteBuf buffer) {
		boolean success = buffer.readBoolean();
		String messageKey = buffer.readUtf();
		int size = buffer.readVarInt();
		List<String> messageArgs = new ArrayList<>(Math.max(size, 0));
		for (int index = 0; index < size; index++) {
			messageArgs.add(buffer.readUtf());
		}
		return new DecodedFeedbackPayload(success, messageKey, List.copyOf(messageArgs));
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
	record DecodedBlockTargetPayload(String dimensionKey, long blockPosLong) {
	}

	/**
	 * 应用结果回执解码结果。
	 */
	record DecodedFeedbackPayload(boolean success, String messageKey, List<String> messageArgs) {
	}
}
