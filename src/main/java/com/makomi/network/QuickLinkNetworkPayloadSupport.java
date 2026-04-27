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
	private static final int DISPLAY_TEXT_MAX_LENGTH = 96;
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
		buffer.writeUtf(snapshot.applyEditMode().token(), TOKEN_MAX_LENGTH);
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
			buffer.readUtf(maxInputLength),
			buffer.readUtf(TOKEN_MAX_LENGTH)
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
		String channelCache,
		String applyEditModeToken
	) {
		int maxInputLength = resolveMaxInputLength();
		buffer.writeUtf(modeToken == null ? "" : modeToken, TOKEN_MAX_LENGTH);
		buffer.writeUtf(serialCacheTypeToken == null ? "" : serialCacheTypeToken, TOKEN_MAX_LENGTH);
		buffer.writeUtf(serialCacheExpression == null ? "" : serialCacheExpression, maxInputLength);
		buffer.writeUtf(channelCache == null ? "" : channelCache, maxInputLength);
		buffer.writeUtf(applyEditModeToken == null ? "" : applyEditModeToken, TOKEN_MAX_LENGTH);
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
			buffer.readUtf(maxInputLength),
			buffer.readUtf(TOKEN_MAX_LENGTH)
		);
	}

	/**
	 * 编码 quick-link 命中目标。
	 * <p>
	 * 节点目标使用 `triggerSource/core + serial>0`，过滤器目标使用 `send/receive + serial=0`。
	 * </p>
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
	 * 解码 quick-link 命中目标。
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
	 * 编码第三形态“添加显示对象”请求。
	 */
	static void encodeVisualizeSnapshotRequestPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		boolean controlKeyDown
	) {
		encodeBlockTargetPayload(buffer, dimensionKey, blockPosLong, expectedNodeTypeToken, expectedNodeSerial);
		buffer.writeBoolean(controlKeyDown);
	}

	/**
	 * 解码第三形态“添加显示对象”请求。
	 */
	static DecodedVisualizeSnapshotRequestPayload decodeVisualizeSnapshotRequestPayload(FriendlyByteBuf buffer) {
		DecodedBlockTargetPayload decodedTarget = decodeBlockTargetPayload(buffer);
		return new DecodedVisualizeSnapshotRequestPayload(
			decodedTarget.dimensionKey(),
			decodedTarget.blockPosLong(),
			decodedTarget.expectedNodeTypeToken(),
			decodedTarget.expectedNodeSerial(),
			buffer.readBoolean()
		);
	}

	/**
	 * 编码频道预览请求。
	 */
	static void encodeChannelPreviewRequestPayload(FriendlyByteBuf buffer, String cacheTypeToken, long channel) {
		buffer.writeUtf(cacheTypeToken == null ? "" : cacheTypeToken, TOKEN_MAX_LENGTH);
		buffer.writeVarLong(Math.max(0L, channel));
	}

	/**
	 * 解码频道预览请求。
	 */
	static DecodedChannelPreviewRequestPayload decodeChannelPreviewRequestPayload(FriendlyByteBuf buffer) {
		return new DecodedChannelPreviewRequestPayload(buffer.readUtf(TOKEN_MAX_LENGTH), buffer.readVarLong());
	}

	/**
	 * 编码频道预览回包。
	 */
	static void encodeChannelPreviewPayload(
		FriendlyByteBuf buffer,
		String cacheTypeToken,
		long channel,
		List<Long> memberSerials
	) {
		buffer.writeUtf(cacheTypeToken == null ? "" : cacheTypeToken, TOKEN_MAX_LENGTH);
		buffer.writeVarLong(Math.max(0L, channel));
		List<Long> normalizedSerials = memberSerials == null ? List.of() : List.copyOf(memberSerials);
		buffer.writeVarInt(normalizedSerials.size());
		for (Long memberSerial : normalizedSerials) {
			buffer.writeVarLong(memberSerial == null ? 0L : Math.max(0L, memberSerial));
		}
	}

	/**
	 * 解码频道预览回包。
	 */
	static DecodedChannelPreviewPayload decodeChannelPreviewPayload(FriendlyByteBuf buffer) {
		String cacheTypeToken = buffer.readUtf(TOKEN_MAX_LENGTH);
		long channel = buffer.readVarLong();
		int size = buffer.readVarInt();
		List<Long> memberSerials = new ArrayList<>(Math.max(size, 0));
		for (int index = 0; index < size; index++) {
			memberSerials.add(buffer.readVarLong());
		}
		return new DecodedChannelPreviewPayload(cacheTypeToken, channel, List.copyOf(memberSerials));
	}

	/**
	 * 编码第三形态显示对象快照。
	 */
	static void encodeVisualizeSnapshotPayload(
		FriendlyByteBuf buffer,
		String objectTypeToken,
		long objectSerial,
		String dimensionKey,
		long blockPosLong,
		String displayText,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		long runtimeNodeVersion,
		List<QuickLinkNetwork.QuickLinkVisualizeTarget> targets
	) {
		encodeBlockTargetPayload(buffer, dimensionKey, blockPosLong, objectTypeToken, objectSerial);
		buffer.writeUtf(displayText == null ? "" : displayText, DISPLAY_TEXT_MAX_LENGTH);
		buffer.writeVarLong(Math.max(0L, graphRevision));
		buffer.writeVarLong(Math.max(0L, sourceRevision));
		buffer.writeVarLong(Math.max(0L, coreRevision));
		buffer.writeVarLong(Math.max(0L, runtimeNodeVersion));
		List<QuickLinkNetwork.QuickLinkVisualizeTarget> normalizedTargets = targets == null ? List.of() : List.copyOf(targets);
		buffer.writeVarInt(normalizedTargets.size());
		for (QuickLinkNetwork.QuickLinkVisualizeTarget target : normalizedTargets) {
			encodeBlockTargetPayload(
				buffer,
				target == null ? "" : target.dimensionKey(),
				target == null ? 0L : target.blockPosLong(),
				target == null ? "" : target.objectTypeToken(),
				target == null ? 0L : target.objectSerial()
			);
			buffer.writeUtf(target == null || target.displayText() == null ? "" : target.displayText(), DISPLAY_TEXT_MAX_LENGTH);
		}
	}

	/**
	 * 解码第三形态显示对象快照。
	 */
	static DecodedVisualizeSnapshotPayload decodeVisualizeSnapshotPayload(FriendlyByteBuf buffer) {
		DecodedBlockTargetPayload source = decodeBlockTargetPayload(buffer);
		String displayText = buffer.readUtf(DISPLAY_TEXT_MAX_LENGTH);
		long graphRevision = buffer.readVarLong();
		long sourceRevision = buffer.readVarLong();
		long coreRevision = buffer.readVarLong();
		long runtimeNodeVersion = buffer.readVarLong();
		int size = buffer.readVarInt();
		List<QuickLinkNetwork.QuickLinkVisualizeTarget> targets = new ArrayList<>(Math.max(size, 0));
		for (int index = 0; index < size; index++) {
			DecodedBlockTargetPayload target = decodeBlockTargetPayload(buffer);
			targets.add(
				new QuickLinkNetwork.QuickLinkVisualizeTarget(
					target.expectedNodeTypeToken(),
					target.expectedNodeSerial(),
					target.dimensionKey(),
					target.blockPosLong(),
					buffer.readUtf(DISPLAY_TEXT_MAX_LENGTH)
				)
			);
		}
		return new DecodedVisualizeSnapshotPayload(
			source.expectedNodeTypeToken(),
			source.expectedNodeSerial(),
			source.dimensionKey(),
			source.blockPosLong(),
			displayText,
			graphRevision,
			sourceRevision,
			coreRevision,
			runtimeNodeVersion,
			List.copyOf(targets)
		);
	}

	/**
	 * 编码第三形态本地追踪对象基线。
	 */
	static void encodeVisualizeTrackedObject(
		FriendlyByteBuf buffer,
		QuickLinkNetwork.QuickLinkVisualizeTrackedObject trackedObject
	) {
		buffer.writeUtf(trackedObject == null ? "" : trackedObject.objectTypeToken(), TOKEN_MAX_LENGTH);
		buffer.writeVarLong(trackedObject == null ? 0L : trackedObject.objectSerial());
		buffer.writeVarLong(trackedObject == null ? 0L : trackedObject.graphRevision());
		buffer.writeVarLong(trackedObject == null ? 0L : trackedObject.sourceRevision());
		buffer.writeVarLong(trackedObject == null ? 0L : trackedObject.coreRevision());
	}

	/**
	 * 解码第三形态本地追踪对象基线。
	 */
	static QuickLinkNetwork.QuickLinkVisualizeTrackedObject decodeVisualizeTrackedObject(FriendlyByteBuf buffer) {
		return new QuickLinkNetwork.QuickLinkVisualizeTrackedObject(
			buffer.readUtf(TOKEN_MAX_LENGTH),
			buffer.readVarLong(),
			buffer.readVarLong(),
			buffer.readVarLong(),
			buffer.readVarLong()
		);
	}

	/**
	 * 编码第三形态删除键。
	 */
	static void encodeVisualizeObjectKey(FriendlyByteBuf buffer, QuickLinkNetwork.QuickLinkVisualizeObjectKey objectKey) {
		buffer.writeUtf(objectKey == null ? "" : objectKey.objectTypeToken(), TOKEN_MAX_LENGTH);
		buffer.writeVarLong(objectKey == null ? 0L : objectKey.objectSerial());
	}

	/**
	 * 解码第三形态删除键。
	 */
	static QuickLinkNetwork.QuickLinkVisualizeObjectKey decodeVisualizeObjectKey(FriendlyByteBuf buffer) {
		return new QuickLinkNetwork.QuickLinkVisualizeObjectKey(buffer.readUtf(TOKEN_MAX_LENGTH), buffer.readVarLong());
	}

	/**
	 * 编码第三形态增量刷新请求。
	 */
	static void encodeVisualizeRefreshRequestPayload(
		FriendlyByteBuf buffer,
		long runtimeNodeVersion,
		List<QuickLinkNetwork.QuickLinkVisualizeTrackedObject> trackedObjects
	) {
		buffer.writeVarLong(Math.max(0L, runtimeNodeVersion));
		List<QuickLinkNetwork.QuickLinkVisualizeTrackedObject> normalizedTrackedObjects = trackedObjects == null
			? List.of()
			: List.copyOf(trackedObjects);
		buffer.writeVarInt(normalizedTrackedObjects.size());
		for (QuickLinkNetwork.QuickLinkVisualizeTrackedObject trackedObject : normalizedTrackedObjects) {
			encodeVisualizeTrackedObject(buffer, trackedObject);
		}
	}

	/**
	 * 解码第三形态增量刷新请求。
	 */
	static DecodedVisualizeRefreshRequestPayload decodeVisualizeRefreshRequestPayload(FriendlyByteBuf buffer) {
		long runtimeNodeVersion = buffer.readVarLong();
		int size = buffer.readVarInt();
		List<QuickLinkNetwork.QuickLinkVisualizeTrackedObject> trackedObjects = new ArrayList<>(Math.max(size, 0));
		for (int index = 0; index < size; index++) {
			trackedObjects.add(decodeVisualizeTrackedObject(buffer));
		}
		return new DecodedVisualizeRefreshRequestPayload(runtimeNodeVersion, List.copyOf(trackedObjects));
	}

	/**
	 * 编码第三形态增量刷新回包。
	 */
	static void encodeVisualizeRefreshPayload(
		FriendlyByteBuf buffer,
		long runtimeNodeVersion,
		List<QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload> upserts,
		List<QuickLinkNetwork.QuickLinkVisualizeObjectKey> removals
	) {
		buffer.writeVarLong(Math.max(0L, runtimeNodeVersion));
		List<QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload> normalizedUpserts = upserts == null ? List.of() : List.copyOf(upserts);
		buffer.writeVarInt(normalizedUpserts.size());
		for (QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload upsert : normalizedUpserts) {
			encodeVisualizeSnapshotPayload(
				buffer,
				upsert == null ? "" : upsert.objectTypeToken(),
				upsert == null ? 0L : upsert.objectSerial(),
				upsert == null ? "" : upsert.dimensionKey(),
				upsert == null ? 0L : upsert.blockPosLong(),
				upsert == null ? "" : upsert.displayText(),
				upsert == null ? 0L : upsert.graphRevision(),
				upsert == null ? 0L : upsert.sourceRevision(),
				upsert == null ? 0L : upsert.coreRevision(),
				upsert == null ? 0L : upsert.runtimeNodeVersion(),
				upsert == null ? List.of() : upsert.targets()
			);
		}
		List<QuickLinkNetwork.QuickLinkVisualizeObjectKey> normalizedRemovals = removals == null ? List.of() : List.copyOf(removals);
		buffer.writeVarInt(normalizedRemovals.size());
		for (QuickLinkNetwork.QuickLinkVisualizeObjectKey removal : normalizedRemovals) {
			encodeVisualizeObjectKey(buffer, removal);
		}
	}

	/**
	 * 解码第三形态增量刷新回包。
	 */
	static DecodedVisualizeRefreshPayload decodeVisualizeRefreshPayload(FriendlyByteBuf buffer) {
		long runtimeNodeVersion = buffer.readVarLong();
		int upsertSize = buffer.readVarInt();
		List<QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload> upserts = new ArrayList<>(Math.max(upsertSize, 0));
		for (int index = 0; index < upsertSize; index++) {
			DecodedVisualizeSnapshotPayload upsert = decodeVisualizeSnapshotPayload(buffer);
			upserts.add(
				new QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload(
					upsert.objectTypeToken(),
					upsert.objectSerial(),
					upsert.dimensionKey(),
					upsert.blockPosLong(),
					upsert.displayText(),
					upsert.graphRevision(),
					upsert.sourceRevision(),
					upsert.coreRevision(),
					upsert.runtimeNodeVersion(),
					upsert.targets()
				)
			);
		}
		int removalSize = buffer.readVarInt();
		List<QuickLinkNetwork.QuickLinkVisualizeObjectKey> removals = new ArrayList<>(Math.max(removalSize, 0));
		for (int index = 0; index < removalSize; index++) {
			removals.add(decodeVisualizeObjectKey(buffer));
		}
		return new DecodedVisualizeRefreshPayload(runtimeNodeVersion, List.copyOf(upserts), List.copyOf(removals));
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
		String channelCache,
		String applyEditModeToken
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
	 * 第三形态“添加显示对象”请求解码结果。
	 */
	record DecodedVisualizeSnapshotRequestPayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		boolean controlKeyDown
	) {
	}

	/**
	 * 频道预览请求解码结果。
	 */
	record DecodedChannelPreviewRequestPayload(String cacheTypeToken, long channel) {
	}

	/**
	 * 频道预览回包解码结果。
	 */
	record DecodedChannelPreviewPayload(String cacheTypeToken, long channel, List<Long> memberSerials) {
	}

	/**
	 * 第三形态显示对象快照解码结果。
	 */
	record DecodedVisualizeSnapshotPayload(
		String objectTypeToken,
		long objectSerial,
		String dimensionKey,
		long blockPosLong,
		String displayText,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		long runtimeNodeVersion,
		List<QuickLinkNetwork.QuickLinkVisualizeTarget> targets
	) {
	}

	/**
	 * 第三形态增量刷新请求解码结果。
	 */
	record DecodedVisualizeRefreshRequestPayload(
		long runtimeNodeVersion,
		List<QuickLinkNetwork.QuickLinkVisualizeTrackedObject> trackedObjects
	) {
	}

	/**
	 * 第三形态增量刷新回包解码结果。
	 */
	record DecodedVisualizeRefreshPayload(
		long runtimeNodeVersion,
		List<QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload> upserts,
		List<QuickLinkNetwork.QuickLinkVisualizeObjectKey> removals
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
