package com.makomi.network;

import com.makomi.RedstoneLink;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 定向面编辑器网络通道。
 */
public final class DirectionalFaceEditorNetwork {
	private DirectionalFaceEditorNetwork() {
	}

	/**
	 * 注册定向面编辑器全部 payload 与接包器。
	 */
	public static void register() {
		DirectionalFaceEditorNetworkRegistrationSupport.register();
	}

	/**
	 * 客户端请求循环切换主手定向面编辑器模式。
	 */
	public record CycleDirectionalFaceEditorModePayload() implements CustomPacketPayload {
		public static final Type<CycleDirectionalFaceEditorModePayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "cycle_directional_face_editor_mode")
		);
		public static final StreamCodec<FriendlyByteBuf, CycleDirectionalFaceEditorModePayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
			},
			buffer -> new CycleDirectionalFaceEditorModePayload()
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
