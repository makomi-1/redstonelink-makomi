package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.data.QuickLinkToolData;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 快速连接工具网络通道。
 */
public final class QuickLinkNetwork {
	private QuickLinkNetwork() {
	}

	/**
	 * 注册快速连接工具全部 payload 与接包器。
	 */
	public static void register() {
		QuickLinkNetworkRegistrationSupport.register();
	}

	/**
	 * 打开快速连接工具编辑器。
	 */
	public static void openEditor(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		ServerPlayNetworking.send(player, new OpenQuickLinkEditorPayload(QuickLinkToolData.read(stack)));
	}

	/**
	 * 服务端打开编辑器的 S2C 包。
	 */
	public record OpenQuickLinkEditorPayload(QuickLinkToolData.Snapshot snapshot) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenQuickLinkEditorPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_quick_link_editor")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenQuickLinkEditorPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeOpenEditorPayload(buffer, payload.snapshot()),
			buffer -> new OpenQuickLinkEditorPayload(QuickLinkNetworkPayloadSupport.decodeOpenEditorPayload(buffer))
		);

		public OpenQuickLinkEditorPayload {
			snapshot = QuickLinkToolData.normalize(snapshot);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端保存工具缓存的 C2S 请求。
	 */
	public record SaveQuickLinkPayload(
		String modeToken,
		String serialCacheTypeToken,
		String serialCacheExpression,
		String channelCache,
		String applyEditModeToken
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SaveQuickLinkPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "save_quick_link_payload")
		);
		public static final StreamCodec<FriendlyByteBuf, SaveQuickLinkPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeSavePayload(
				buffer,
				payload.modeToken(),
				payload.serialCacheTypeToken(),
				payload.serialCacheExpression(),
				payload.channelCache(),
				payload.applyEditModeToken()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedSavePayload decoded = QuickLinkNetworkPayloadSupport.decodeSavePayload(buffer);
				return new SaveQuickLinkPayload(
					decoded.modeToken(),
					decoded.serialCacheTypeToken(),
					decoded.serialCacheExpression(),
					decoded.channelCache(),
					decoded.applyEditModeToken()
				);
			}
		);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端左键采集缓存的 C2S 请求。
	 * <p>
	 * 该载荷只用于可采集节点，因此 `expectedNodeTypeToken` 固定为 `triggerSource/core`。
	 * </p>
	 */
	public record CollectQuickLinkPayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CollectQuickLinkPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "collect_quick_link_payload")
		);
		public static final StreamCodec<FriendlyByteBuf, CollectQuickLinkPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeBlockTargetPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.expectedNodeTypeToken(),
				payload.expectedNodeSerial()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedBlockTargetPayload decoded = QuickLinkNetworkPayloadSupport.decodeBlockTargetPayload(
					buffer
				);
				return new CollectQuickLinkPayload(
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.expectedNodeTypeToken(),
					decoded.expectedNodeSerial()
				);
			}
		);

		public CollectQuickLinkPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			expectedNodeTypeToken = expectedNodeTypeToken == null ? "" : expectedNodeTypeToken;
			expectedNodeSerial = Math.max(0L, expectedNodeSerial);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端请求频道缓存预览成员的 C2S 请求。
	 */
	public record RequestQuickLinkChannelPreviewPayload(String cacheTypeToken, long channel) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestQuickLinkChannelPreviewPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_quick_link_channel_preview")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestQuickLinkChannelPreviewPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeChannelPreviewRequestPayload(
				buffer,
				payload.cacheTypeToken(),
				payload.channel()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedChannelPreviewRequestPayload decoded =
					QuickLinkNetworkPayloadSupport.decodeChannelPreviewRequestPayload(buffer);
				return new RequestQuickLinkChannelPreviewPayload(decoded.cacheTypeToken(), decoded.channel());
			}
		);

		public RequestQuickLinkChannelPreviewPayload {
			cacheTypeToken = cacheTypeToken == null ? "" : cacheTypeToken;
			channel = Math.max(0L, channel);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端回传频道缓存预览成员的 S2C 回包。
	 */
	public record QuickLinkChannelPreviewPayload(String cacheTypeToken, long channel, List<Long> memberSerials)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<QuickLinkChannelPreviewPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "quick_link_channel_preview")
		);
		public static final StreamCodec<FriendlyByteBuf, QuickLinkChannelPreviewPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeChannelPreviewPayload(
				buffer,
				payload.cacheTypeToken(),
				payload.channel(),
				payload.memberSerials()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedChannelPreviewPayload decoded =
					QuickLinkNetworkPayloadSupport.decodeChannelPreviewPayload(buffer);
				return new QuickLinkChannelPreviewPayload(decoded.cacheTypeToken(), decoded.channel(), decoded.memberSerials());
			}
		);

		public QuickLinkChannelPreviewPayload {
			cacheTypeToken = cacheTypeToken == null ? "" : cacheTypeToken;
			channel = Math.max(0L, channel);
			memberSerials = List.copyOf(memberSerials == null ? List.of() : memberSerials);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端请求第三形态显示对象当前连接快照的 C2S 请求。
	 * <p>
	 * `expectedNodeTypeToken` 支持 `triggerSource/core/link_repeater`。
	 * </p>
	 */
	public record RequestQuickLinkVisualizeSnapshotPayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestQuickLinkVisualizeSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_quick_link_visualize_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestQuickLinkVisualizeSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeBlockTargetPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.expectedNodeTypeToken(),
				payload.expectedNodeSerial()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedBlockTargetPayload decoded = QuickLinkNetworkPayloadSupport.decodeBlockTargetPayload(
					buffer
				);
				return new RequestQuickLinkVisualizeSnapshotPayload(
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.expectedNodeTypeToken(),
					decoded.expectedNodeSerial()
				);
			}
		);

		public RequestQuickLinkVisualizeSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			expectedNodeTypeToken = expectedNodeTypeToken == null ? "" : expectedNodeTypeToken;
			expectedNodeSerial = Math.max(0L, expectedNodeSerial);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端回传第三形态显示对象快照的 S2C 包。
	 */
	public record QuickLinkVisualizeSnapshotPayload(
		String objectTypeToken,
		long objectSerial,
		String dimensionKey,
		long blockPosLong,
		String displayText,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		long runtimeNodeVersion,
		List<QuickLinkVisualizeTarget> targets
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<QuickLinkVisualizeSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "quick_link_visualize_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, QuickLinkVisualizeSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeVisualizeSnapshotPayload(
				buffer,
				payload.objectTypeToken(),
				payload.objectSerial(),
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.displayText(),
				payload.graphRevision(),
				payload.sourceRevision(),
				payload.coreRevision(),
				payload.runtimeNodeVersion(),
				payload.targets()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedVisualizeSnapshotPayload decoded =
					QuickLinkNetworkPayloadSupport.decodeVisualizeSnapshotPayload(buffer);
				return new QuickLinkVisualizeSnapshotPayload(
					decoded.objectTypeToken(),
					decoded.objectSerial(),
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.displayText(),
					decoded.graphRevision(),
					decoded.sourceRevision(),
					decoded.coreRevision(),
					decoded.runtimeNodeVersion(),
					decoded.targets()
				);
			}
		);

		public QuickLinkVisualizeSnapshotPayload {
			objectTypeToken = objectTypeToken == null ? "" : objectTypeToken;
			objectSerial = Math.max(0L, objectSerial);
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			displayText = displayText == null ? "" : displayText;
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
			runtimeNodeVersion = Math.max(0L, runtimeNodeVersion);
			targets = List.copyOf(targets == null ? List.of() : targets);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 单个第三形态显示对象目标项。
	 */
	public record QuickLinkVisualizeTarget(
		String objectTypeToken,
		long objectSerial,
		String dimensionKey,
		long blockPosLong,
		String displayText
	) {
		public QuickLinkVisualizeTarget {
			objectTypeToken = objectTypeToken == null ? "" : objectTypeToken;
			objectSerial = Math.max(0L, objectSerial);
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			displayText = displayText == null ? "" : displayText;
		}
	}

	/**
	 * 客户端批量请求第三形态显示对象增量刷新的 C2S 请求。
	 */
	public record RequestQuickLinkVisualizeRefreshPayload(
		long runtimeNodeVersion,
		List<QuickLinkVisualizeTrackedObject> trackedObjects
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestQuickLinkVisualizeRefreshPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_quick_link_visualize_refresh")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestQuickLinkVisualizeRefreshPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeVisualizeRefreshRequestPayload(
				buffer,
				payload.runtimeNodeVersion(),
				payload.trackedObjects()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedVisualizeRefreshRequestPayload decoded =
					QuickLinkNetworkPayloadSupport.decodeVisualizeRefreshRequestPayload(buffer);
				return new RequestQuickLinkVisualizeRefreshPayload(decoded.runtimeNodeVersion(), decoded.trackedObjects());
			}
		);

		public RequestQuickLinkVisualizeRefreshPayload {
			runtimeNodeVersion = Math.max(0L, runtimeNodeVersion);
			trackedObjects = List.copyOf(trackedObjects == null ? List.of() : trackedObjects);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端回传第三形态显示对象增量刷新的 S2C 回包。
	 */
	public record QuickLinkVisualizeRefreshPayload(
		long runtimeNodeVersion,
		List<QuickLinkVisualizeSnapshotPayload> upserts,
		List<QuickLinkVisualizeObjectKey> removals
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<QuickLinkVisualizeRefreshPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "quick_link_visualize_refresh")
		);
		public static final StreamCodec<FriendlyByteBuf, QuickLinkVisualizeRefreshPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeVisualizeRefreshPayload(
				buffer,
				payload.runtimeNodeVersion(),
				payload.upserts(),
				payload.removals()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedVisualizeRefreshPayload decoded =
					QuickLinkNetworkPayloadSupport.decodeVisualizeRefreshPayload(buffer);
				return new QuickLinkVisualizeRefreshPayload(decoded.runtimeNodeVersion(), decoded.upserts(), decoded.removals());
			}
		);

		public QuickLinkVisualizeRefreshPayload {
			runtimeNodeVersion = Math.max(0L, runtimeNodeVersion);
			upserts = List.copyOf(upserts == null ? List.of() : upserts);
			removals = List.copyOf(removals == null ? List.of() : removals);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 第三形态单个显示对象的本地 revision 基线。
	 */
	public record QuickLinkVisualizeTrackedObject(
		String objectTypeToken,
		long objectSerial,
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) {
		public QuickLinkVisualizeTrackedObject {
			objectTypeToken = objectTypeToken == null ? "" : objectTypeToken;
			objectSerial = Math.max(0L, objectSerial);
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
		}
	}

	/**
	 * 第三形态显示对象删除键。
	 */
	public record QuickLinkVisualizeObjectKey(String objectTypeToken, long objectSerial) {
		public QuickLinkVisualizeObjectKey {
			objectTypeToken = objectTypeToken == null ? "" : objectTypeToken;
			objectSerial = Math.max(0L, objectSerial);
		}
	}

	/**
	 * 客户端右键应用前请求 revision 基线的 C2S 请求。
	 * <p>
	 * `expectedNodeTypeToken/expectedNodeSerial` 既可表示节点身份，
	 * 也可表示过滤器目标（`send/receive + 0`）。
	 * </p>
	 */
	public record RequestApplyQuickLinkBaselinePayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestApplyQuickLinkBaselinePayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_apply_quick_link_baseline")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestApplyQuickLinkBaselinePayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeBlockTargetPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.expectedNodeTypeToken(),
				payload.expectedNodeSerial()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedBlockTargetPayload decoded = QuickLinkNetworkPayloadSupport.decodeBlockTargetPayload(
					buffer
				);
				return new RequestApplyQuickLinkBaselinePayload(
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.expectedNodeTypeToken(),
					decoded.expectedNodeSerial()
				);
			}
		);

		public RequestApplyQuickLinkBaselinePayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			expectedNodeTypeToken = expectedNodeTypeToken == null ? "" : expectedNodeTypeToken;
			expectedNodeSerial = Math.max(0L, expectedNodeSerial);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回给客户端的 quick-link apply revision 基线。
	 * <p>
	 * 过滤器目标不会参与 OCC，因此会回传全零 revision，仅用于复用现有 apply 往返。
	 * </p>
	 */
	public record ApplyQuickLinkBaselinePayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ApplyQuickLinkBaselinePayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "apply_quick_link_baseline")
		);
		public static final StreamCodec<FriendlyByteBuf, ApplyQuickLinkBaselinePayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeApplyBaselinePayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.expectedNodeTypeToken(),
				payload.expectedNodeSerial(),
				payload.graphRevision(),
				payload.sourceRevision(),
				payload.coreRevision()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedApplyBaselinePayload decoded = QuickLinkNetworkPayloadSupport.decodeApplyBaselinePayload(
					buffer
				);
				return new ApplyQuickLinkBaselinePayload(
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.expectedNodeTypeToken(),
					decoded.expectedNodeSerial(),
					decoded.graphRevision(),
					decoded.sourceRevision(),
					decoded.coreRevision()
				);
			}
		);

		public ApplyQuickLinkBaselinePayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			expectedNodeTypeToken = expectedNodeTypeToken == null ? "" : expectedNodeTypeToken;
			expectedNodeSerial = Math.max(0L, expectedNodeSerial);
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
			coreRevision = Math.max(0L, coreRevision);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端右键应用缓存的 C2S 请求。
	 * <p>
	 * 过滤器目标会沿用同一载荷结构，但服务端会跳过 OCC 并直接写入过滤器配置。
	 * </p>
	 */
	public record ApplyQuickLinkPayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long expectedCoreRevision,
		long expectedSourceRevision
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ApplyQuickLinkPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "apply_quick_link_payload")
		);
		public static final StreamCodec<FriendlyByteBuf, ApplyQuickLinkPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeApplyPayload(
				buffer,
				payload.dimensionKey(),
				payload.blockPosLong(),
				payload.expectedNodeTypeToken(),
				payload.expectedNodeSerial(),
				payload.expectedCoreRevision(),
				payload.expectedSourceRevision()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedApplyPayload decoded = QuickLinkNetworkPayloadSupport.decodeApplyPayload(
					buffer
				);
				return new ApplyQuickLinkPayload(
					decoded.dimensionKey(),
					decoded.blockPosLong(),
					decoded.expectedNodeTypeToken(),
					decoded.expectedNodeSerial(),
					decoded.expectedCoreRevision(),
					decoded.expectedSourceRevision()
				);
			}
		);

		public ApplyQuickLinkPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			expectedNodeTypeToken = expectedNodeTypeToken == null ? "" : expectedNodeTypeToken;
			expectedNodeSerial = Math.max(0L, expectedNodeSerial);
			expectedCoreRevision = Math.max(0L, expectedCoreRevision);
			expectedSourceRevision = Math.max(0L, expectedSourceRevision);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回给客户端的统一 quick-link 反馈回执。
	 */
	public record QuickLinkFeedbackPayload(boolean success, String messageKey, List<String> messageArgs)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<QuickLinkFeedbackPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "quick_link_feedback")
		);
		public static final StreamCodec<FriendlyByteBuf, QuickLinkFeedbackPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> QuickLinkNetworkPayloadSupport.encodeFeedbackPayload(
				buffer,
				payload.success(),
				payload.messageKey(),
				payload.messageArgs()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedFeedbackPayload decoded = QuickLinkNetworkPayloadSupport.decodeFeedbackPayload(
					buffer
				);
				return new QuickLinkFeedbackPayload(decoded.success(), decoded.messageKey(), decoded.messageArgs());
			}
		);

		public QuickLinkFeedbackPayload {
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
