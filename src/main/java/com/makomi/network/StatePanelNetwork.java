package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkNodeType;
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
			.map(entry -> new SubscriptionEntryPayload(entry.nodeType(), entry.serial()))
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
	 * 状态面板订阅项传输结构。
	 */
	public record SubscriptionEntryPayload(LinkNodeType nodeType, long serial) {
		public SubscriptionEntryPayload {
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
		}
	}

	/**
	 * 状态面板单条快照传输结构。
	 */
	public record StatePanelSnapshotEntry(
		LinkNodeType nodeType,
		long serial,
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
		}
	}
}
