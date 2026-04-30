package com.makomi.network;

import com.makomi.RedstoneLink;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 智能节点容器网络通道。
 */
public final class SmartNodeContainerNetwork {
	private SmartNodeContainerNetwork() {
	}

	/**
	 * 注册智能节点容器全部 payload 与接包器。
	 */
	public static void register() {
		SmartNodeContainerNetworkRegistrationSupport.register();
	}

	/**
	 * 客户端请求打开主手智能节点容器。
	 */
	public record OpenSmartNodeContainerPayload() implements CustomPacketPayload {
		public static final Type<OpenSmartNodeContainerPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_smart_node_container")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenSmartNodeContainerPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new OpenSmartNodeContainerPayload()
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端请求循环切换当前放置类型。
	 */
	public record CycleSmartNodeContainerTypePayload() implements CustomPacketPayload {
		public static final Type<CycleSmartNodeContainerTypePayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "cycle_smart_node_container_type")
		);
		public static final StreamCodec<FriendlyByteBuf, CycleSmartNodeContainerTypePayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new CycleSmartNodeContainerTypePayload()
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端请求更新当前一次性临时槽位选择。
	 */
	public record SelectSmartNodeContainerSlotPayload(int slotIndex) implements CustomPacketPayload {
		public static final Type<SelectSmartNodeContainerSlotPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "select_smart_node_container_slot")
		);
		public static final StreamCodec<FriendlyByteBuf, SelectSmartNodeContainerSlotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> buffer.writeVarInt(payload.slotIndex()),
			buffer -> new SelectSmartNodeContainerSlotPayload(buffer.readVarInt())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
