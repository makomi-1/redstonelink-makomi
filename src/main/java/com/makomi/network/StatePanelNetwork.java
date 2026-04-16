package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.data.CurrentLinksPrivacyService;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasServerSupport;
import com.makomi.data.StatePanelToolData;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 状态面板工具网络通道。
 */
public final class StatePanelNetwork {
	private StatePanelNetwork() {
	}

	/**
	 * 注册状态面板工具全部 payload 与接包器。
	 */
	public static void register() {
		StatePanelNetworkRegistrationSupport.register();
	}

	/**
	 * 打开状态面板并下发当前订阅列表。
	 */
	public static void openPanel(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		List<SubscriptionEntryPayload> subscriptions = StatePanelToolData
			.readSubscriptions(stack)
			.stream()
			.map(entry -> new SubscriptionEntryPayload(
				entry.nodeType(),
				entry.serial(),
				resolveSubscriptionDisplayText(player, entry.nodeType(), entry.serial())
			))
			.toList();
		ServerPlayNetworking.send(player, new OpenStatePanelPayload(subscriptions));
	}

	/**
	 * 服务端打开状态面板的 S2C 包。
	 */
	public record OpenStatePanelPayload(List<SubscriptionEntryPayload> subscriptions) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenStatePanelPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_state_panel")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenStatePanelPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeOpenPayload(buffer, payload.subscriptions()),
			buffer -> new OpenStatePanelPayload(StatePanelNetworkPayloadSupport.decodeOpenPayload(buffer))
		);

		public OpenStatePanelPayload {
			subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端提交订阅表达式的 C2S 请求。
	 */
	public record SubscribeStatePanelPayload(String nodeTypeToken, String serialExpression) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SubscribeStatePanelPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "subscribe_state_panel")
		);
		public static final StreamCodec<FriendlyByteBuf, SubscribeStatePanelPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeSubscribePayload(
				buffer,
				payload.nodeTypeToken(),
				payload.serialExpression()
			),
			buffer -> {
				StatePanelNetworkPayloadSupport.DecodedSubscribePayload decoded = StatePanelNetworkPayloadSupport.decodeSubscribePayload(
					buffer
				);
				return new SubscribeStatePanelPayload(decoded.nodeTypeToken(), decoded.serialExpression());
			}
		);

		public SubscribeStatePanelPayload {
			nodeTypeToken = nodeTypeToken == null ? "" : nodeTypeToken;
			serialExpression = serialExpression == null ? "" : serialExpression;
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端请求刷新状态快照的 C2S 请求。
	 */
	public record RefreshStatePanelPayload() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RefreshStatePanelPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "refresh_state_panel")
		);
		public static final StreamCodec<FriendlyByteBuf, RefreshStatePanelPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new RefreshStatePanelPayload()
		);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端查询当前录制会话状态的 C2S 请求。
	 */
	public record QueryStatePanelRecordingPayload() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<QueryStatePanelRecordingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "query_state_panel_recording")
		);
		public static final StreamCodec<FriendlyByteBuf, QueryStatePanelRecordingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new QueryStatePanelRecordingPayload()
		);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端开始录制的 C2S 请求。
	 */
	public record StartStatePanelRecordingPayload(
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		boolean autoOpenWeb
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<StartStatePanelRecordingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "start_state_panel_recording")
		);
		public static final StreamCodec<FriendlyByteBuf, StartStatePanelRecordingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeRecordingStartPayload(
				buffer,
				payload.title(),
				payload.sampleEveryTicks(),
				payload.capacityPerNode(),
				payload.autoOpenWeb()
			),
			buffer -> {
				StatePanelNetworkPayloadSupport.DecodedRecordingStartPayload decoded = StatePanelNetworkPayloadSupport.decodeRecordingStartPayload(
					buffer
				);
				return new StartStatePanelRecordingPayload(
					decoded.title(),
					decoded.sampleEveryTicks(),
					decoded.capacityPerNode(),
					decoded.autoOpenWeb()
				);
			}
		);

		public StartStatePanelRecordingPayload {
			title = title == null ? "" : title;
			sampleEveryTicks = Math.max(0, sampleEveryTicks);
			capacityPerNode = Math.max(0, capacityPerNode);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端结束录制的 C2S 请求。
	 */
	public record StopStatePanelRecordingPayload() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<StopStatePanelRecordingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "stop_state_panel_recording")
		);
		public static final StreamCodec<FriendlyByteBuf, StopStatePanelRecordingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new StopStatePanelRecordingPayload()
		);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端删除单条订阅的 C2S 请求。
	 */
	public record RemoveStatePanelSerialPayload(String nodeTypeToken, long serial) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RemoveStatePanelSerialPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "remove_state_panel_serial")
		);
		public static final StreamCodec<FriendlyByteBuf, RemoveStatePanelSerialPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeRemovePayload(buffer, payload.nodeTypeToken(), payload.serial()),
			buffer -> {
				StatePanelNetworkPayloadSupport.DecodedRemovePayload decoded = StatePanelNetworkPayloadSupport.decodeRemovePayload(buffer);
				return new RemoveStatePanelSerialPayload(decoded.nodeTypeToken(), decoded.serial());
			}
		);

		public RemoveStatePanelSerialPayload {
			nodeTypeToken = nodeTypeToken == null ? "" : nodeTypeToken;
			serial = Math.max(0L, serial);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端点击录制按钮的 C2S 请求（当前仅预留入口）。
	 */
	public record RecordStatePanelPayload() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RecordStatePanelPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "record_state_panel")
		);
		public static final StreamCodec<FriendlyByteBuf, RecordStatePanelPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new RecordStatePanelPayload()
		);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端清空全部订阅的 C2S 请求。
	 */
	public record CleanAllStatePanelPayload() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CleanAllStatePanelPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "clean_all_state_panel")
		);
		public static final StreamCodec<FriendlyByteBuf, CleanAllStatePanelPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new CleanAllStatePanelPayload()
		);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回的状态快照回执。
	 */
	public record StatePanelSnapshotPayload(List<StatePanelSnapshotEntry> entries) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<StatePanelSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "state_panel_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, StatePanelSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeSnapshotPayload(buffer, payload.entries()),
			buffer -> new StatePanelSnapshotPayload(StatePanelNetworkPayloadSupport.decodeSnapshotPayload(buffer))
		);

		public StatePanelSnapshotPayload {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回的状态面板反馈回执。
	 */
	public record StatePanelFeedbackPayload(boolean success, String messageKey, List<String> messageArgs)
		implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<StatePanelFeedbackPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "state_panel_feedback")
		);
		public static final StreamCodec<FriendlyByteBuf, StatePanelFeedbackPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeFeedbackPayload(
				buffer,
				payload.success(),
				payload.messageKey(),
				payload.messageArgs()
			),
			buffer -> {
				StatePanelNetworkPayloadSupport.DecodedFeedbackPayload decoded = StatePanelNetworkPayloadSupport.decodeFeedbackPayload(buffer);
				return new StatePanelFeedbackPayload(decoded.success(), decoded.messageKey(), decoded.messageArgs());
			}
		);

		public StatePanelFeedbackPayload {
			messageKey = messageKey == null ? "" : messageKey;
			messageArgs = List.copyOf(messageArgs == null ? List.of() : messageArgs);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回的录制会话状态回执。
	 */
	public record StatePanelRecordingSessionPayload(
		boolean active,
		String title,
		int sampleEveryTicks,
		int capacityPerNode,
		boolean autoOpenWeb,
		int subscriptionCount,
		int mountedCount,
		long startedTick
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<StatePanelRecordingSessionPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "state_panel_recording_session")
		);
		public static final StreamCodec<FriendlyByteBuf, StatePanelRecordingSessionPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeRecordingSessionPayload(
				buffer,
				payload.active(),
				payload.title(),
				payload.sampleEveryTicks(),
				payload.capacityPerNode(),
				payload.autoOpenWeb(),
				payload.subscriptionCount(),
				payload.mountedCount(),
				payload.startedTick()
			),
			buffer -> {
				StatePanelNetworkPayloadSupport.DecodedRecordingSessionPayload decoded = StatePanelNetworkPayloadSupport.decodeRecordingSessionPayload(
					buffer
				);
				return new StatePanelRecordingSessionPayload(
					decoded.active(),
					decoded.title(),
					decoded.sampleEveryTicks(),
					decoded.capacityPerNode(),
					decoded.autoOpenWeb(),
					decoded.subscriptionCount(),
					decoded.mountedCount(),
					decoded.startedTick()
				);
			}
		);

		public StatePanelRecordingSessionPayload {
			title = title == null ? "" : title;
			sampleEveryTicks = Math.max(0, sampleEveryTicks);
			capacityPerNode = Math.max(0, capacityPerNode);
			subscriptionCount = Math.max(0, subscriptionCount);
			mountedCount = Math.max(0, mountedCount);
			startedTick = Math.max(0L, startedTick);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端返回的录制结果分块。
	 */
	public record StatePanelRecordingExportChunkPayload(
		String fileName,
		int chunkIndex,
		int totalChunks,
		boolean autoOpenWeb,
		byte[] chunkBytes
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<StatePanelRecordingExportChunkPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "state_panel_recording_export_chunk")
		);
		public static final StreamCodec<FriendlyByteBuf, StatePanelRecordingExportChunkPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> StatePanelNetworkPayloadSupport.encodeRecordingExportChunkPayload(
				buffer,
				payload.fileName(),
				payload.chunkIndex(),
				payload.totalChunks(),
				payload.autoOpenWeb(),
				payload.chunkBytes()
			),
			buffer -> {
				StatePanelNetworkPayloadSupport.DecodedRecordingExportChunkPayload decoded = StatePanelNetworkPayloadSupport.decodeRecordingExportChunkPayload(
					buffer
				);
				return new StatePanelRecordingExportChunkPayload(
					decoded.fileName(),
					decoded.chunkIndex(),
					decoded.totalChunks(),
					decoded.autoOpenWeb(),
					decoded.chunkBytes()
				);
			}
		);

		public StatePanelRecordingExportChunkPayload {
			fileName = fileName == null ? "" : fileName;
			chunkIndex = Math.max(0, chunkIndex);
			totalChunks = Math.max(0, totalChunks);
			chunkBytes = chunkBytes == null ? new byte[0] : chunkBytes.clone();
		}

		@Override
		public byte[] chunkBytes() {
			return chunkBytes.clone();
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 状态面板订阅项传输结构。
	 */
	public record SubscriptionEntryPayload(LinkNodeType nodeType, long serial, String displayText) {
		public SubscriptionEntryPayload {
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
			displayText = normalizeDisplayText(serial, displayText);
		}
	}

	/**
	 * 状态面板单条快照传输结构。
	 */
	public record StatePanelSnapshotEntry(
		LinkNodeType nodeType,
		long serial,
		String displayText,
		boolean allocated,
		boolean retired,
		boolean online,
		boolean active,
		int inputPower,
		int outputPower,
		boolean readable
	) {
		public StatePanelSnapshotEntry {
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
			displayText = normalizeDisplayText(serial, displayText);
		}
	}

	private static String resolveSubscriptionDisplayText(ServerPlayer player, LinkNodeType nodeType, long serial) {
		if (player == null || nodeType == null || serial <= 0L) {
			return normalizeDisplayText(serial, "");
		}
		if (!CurrentLinksPrivacyService.canReadNodeState(player, nodeType, serial)) {
			return normalizeDisplayText(serial, "");
		}
		return normalizeDisplayText(serial, NodeAliasServerSupport.resolveDisplayText(player.serverLevel(), nodeType, serial));
	}

	private static String normalizeDisplayText(long serial, String displayText) {
		String normalizedDisplayText = NodeAliasDisplayUtil.normalizeAlias(displayText);
		return normalizedDisplayText.isEmpty()
			? NodeAliasDisplayUtil.formatDisplayText("", serial)
			: normalizedDisplayText;
	}
}
