package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 配对界面网络通道。
 * <p>
 * 负责注册 S2C Payload，并在服务端打开配对界面时下发“源序列号 + 当前目标集合”。
 * </p>
 */
public final class PairingNetwork {
	private PairingNetwork() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(OpenTriggerSourcePairingPayload.TYPE, OpenTriggerSourcePairingPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(OpenCorePairingPayload.TYPE, OpenCorePairingPayload.CODEC);
	}

	/**
	 * 打开触发源侧配对界面。
	 */
	public static void openTriggerSourcePairing(ServerPlayer player, long sourceSerial) {
		openPairingBySourceType(player, LinkNodeType.BUTTON, sourceSerial);
	}

	/**
	 * 打开核心侧配对界面（core 语义入口）。
	 */
	public static void openCorePairing(ServerPlayer player, long coreSerial) {
		openPairingBySourceType(player, LinkNodeType.CORE, coreSerial);
	}

	/**
	 * 按来源类型打开配对界面（命名语义化入口）。
	 *
	 * @param player 服务端玩家
	 * @param sourceType 来源节点类型
	 * @param sourceSerial 来源序列号
	 */
	public static void openPairingBySourceType(ServerPlayer player, LinkNodeType sourceType, long sourceSerial) {
		if (sourceType == null) {
			return;
		}
		List<Long> currentTargets = new ArrayList<>(
			LinkSavedData.get(player.serverLevel()).getLinkedTargetsBySourceType(sourceType, sourceSerial)
		);
		currentTargets.sort(Comparator.naturalOrder());
		if (sourceType == LinkNodeType.BUTTON) {
			ServerPlayNetworking.send(player, new OpenTriggerSourcePairingPayload(sourceSerial, currentTargets));
			return;
		}
		ServerPlayNetworking.send(player, new OpenCorePairingPayload(sourceSerial, currentTargets));
	}

	/**
	 * 通用配对 Payload 编码。
	 */
	private static void encodePayload(FriendlyByteBuf buffer, long sourceSerial, List<Long> targets) {
		buffer.writeVarLong(sourceSerial);
		buffer.writeVarInt(targets.size());
		for (long target : targets) {
			buffer.writeVarLong(target);
		}
	}

	/**
	 * 通用配对 Payload 解码。
	 */
	private static DecodedPayload decodePayload(FriendlyByteBuf buffer) {
		long sourceSerial = buffer.readVarLong();
		int size = buffer.readVarInt();
		List<Long> targets = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			targets.add(buffer.readVarLong());
		}
		return new DecodedPayload(sourceSerial, targets);
	}

	/**
	 * 通用配对 Payload 解码结果。
	 */
	private record DecodedPayload(long sourceSerial, List<Long> targets) {}

	/**
	 * 触发源配对界面打开包：sourceSerial 为触发源序列号，targets 为当前关联核心序列号列表。
	 */
	public record OpenTriggerSourcePairingPayload(long sourceSerial, List<Long> targets) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenTriggerSourcePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_triggersource_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenTriggerSourcePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> encodePayload(buffer, payload.sourceSerial, payload.targets),
			buffer -> {
				DecodedPayload payload = decodePayload(buffer);
				return new OpenTriggerSourcePairingPayload(payload.sourceSerial(), payload.targets());
			}
		);

		public OpenTriggerSourcePairingPayload {
			targets = List.copyOf(targets);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 核心配对界面打开包：sourceSerial 为核心序列号，targets 为当前关联按钮序列号列表。
	 */
	public record OpenCorePairingPayload(long sourceSerial, List<Long> targets) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenCorePairingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_core_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenCorePairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> encodePayload(buffer, payload.sourceSerial, payload.targets),
			buffer -> {
				DecodedPayload payload = decodePayload(buffer);
				return new OpenCorePairingPayload(payload.sourceSerial(), payload.targets());
			}
		);

		public OpenCorePairingPayload {
			targets = List.copyOf(targets);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
