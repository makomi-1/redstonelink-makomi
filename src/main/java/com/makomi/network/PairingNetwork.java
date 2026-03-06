package com.makomi.network;

import com.makomi.RedstoneLink;
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
		PayloadTypeRegistry.playS2C().register(OpenButtonPairingPayload.TYPE, OpenButtonPairingPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(OpenCorePairingPayload.TYPE, OpenCorePairingPayload.CODEC);
	}

	/**
	 * 打开按钮侧配对界面。
	 */
	public static void openButtonPairing(ServerPlayer player, long buttonSerial) {
		List<Long> currentTargets = new ArrayList<>(LinkSavedData.get(player.serverLevel()).getLinkedCores(buttonSerial));
		currentTargets.sort(Comparator.naturalOrder());
		ServerPlayNetworking.send(player, new OpenButtonPairingPayload(buttonSerial, currentTargets));
	}

	/**
	 * 打开核心侧配对界面。
	 */
	public static void openCorePairing(ServerPlayer player, long coreSerial) {
		List<Long> currentTargets = new ArrayList<>(LinkSavedData.get(player.serverLevel()).getLinkedButtons(coreSerial));
		currentTargets.sort(Comparator.naturalOrder());
		ServerPlayNetworking.send(player, new OpenCorePairingPayload(coreSerial, currentTargets));
	}

	/**
	 * 按钮配对界面打开包：sourceSerial 为按钮序列号，targets 为当前关联核心序列号列表。
	 */
	public record OpenButtonPairingPayload(long sourceSerial, List<Long> targets) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<OpenButtonPairingPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "open_button_pairing")
		);
		public static final StreamCodec<FriendlyByteBuf, OpenButtonPairingPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
				buffer.writeVarLong(payload.sourceSerial);
				buffer.writeVarInt(payload.targets.size());
				for (long target : payload.targets) {
					buffer.writeVarLong(target);
				}
			},
			buffer -> {
				long sourceSerial = buffer.readVarLong();
				int size = buffer.readVarInt();
				List<Long> targets = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					targets.add(buffer.readVarLong());
				}
				return new OpenButtonPairingPayload(sourceSerial, targets);
			}
		);

		public OpenButtonPairingPayload {
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
			(payload, buffer) -> {
				buffer.writeVarLong(payload.sourceSerial);
				buffer.writeVarInt(payload.targets.size());
				for (long target : payload.targets) {
					buffer.writeVarLong(target);
				}
			},
			buffer -> {
				long sourceSerial = buffer.readVarLong();
				int size = buffer.readVarInt();
				List<Long> targets = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					targets.add(buffer.readVarLong());
				}
				return new OpenCorePairingPayload(sourceSerial, targets);
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
