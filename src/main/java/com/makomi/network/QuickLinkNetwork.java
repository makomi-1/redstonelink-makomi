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
		String channelCache
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
				payload.channelCache()
			),
			buffer -> {
				QuickLinkNetworkPayloadSupport.DecodedSavePayload decoded = QuickLinkNetworkPayloadSupport.decodeSavePayload(buffer);
				return new SaveQuickLinkPayload(
					decoded.modeToken(),
					decoded.serialCacheTypeToken(),
					decoded.serialCacheExpression(),
					decoded.channelCache()
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
	 * 客户端右键应用前请求 revision 基线的 C2S 请求。
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
	 */
	public record ApplyQuickLinkBaselinePayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long graphRevision,
		long sourceRevision
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
				payload.sourceRevision()
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
					decoded.sourceRevision()
				);
			}
		);

		public ApplyQuickLinkBaselinePayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			expectedNodeTypeToken = expectedNodeTypeToken == null ? "" : expectedNodeTypeToken;
			expectedNodeSerial = Math.max(0L, expectedNodeSerial);
			graphRevision = Math.max(0L, graphRevision);
			sourceRevision = Math.max(0L, sourceRevision);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端右键应用缓存的 C2S 请求。
	 */
	public record ApplyQuickLinkPayload(
		String dimensionKey,
		long blockPosLong,
		String expectedNodeTypeToken,
		long expectedNodeSerial,
		long expectedGraphRevision,
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
				payload.expectedGraphRevision(),
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
					decoded.expectedGraphRevision(),
					decoded.expectedSourceRevision()
				);
			}
		);

		public ApplyQuickLinkPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			expectedNodeTypeToken = expectedNodeTypeToken == null ? "" : expectedNodeTypeToken;
			expectedNodeSerial = Math.max(0L, expectedNodeSerial);
			expectedGraphRevision = Math.max(0L, expectedGraphRevision);
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
