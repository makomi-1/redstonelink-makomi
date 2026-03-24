package com.makomi.network;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeLinksSnapshot;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.NodeSnapshotQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 配对界面与近外显网络通道。
 * <p>
 * 负责注册配对界面 S2C 包，以及“当前连接”按玩家定向查询的 C2S/S2C 包。
 * </p>
 */
public final class PairingNetwork {
	private static final int DIMENSION_KEY_MAX_LENGTH = 128;
	private static final int NODE_TYPE_MAX_LENGTH = 32;
	private static final int CURRENT_LINKS_REQUEST_MAX_DISTANCE = 8;

	private PairingNetwork() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(OpenTriggerSourcePairingPayload.TYPE, OpenTriggerSourcePairingPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(OpenCorePairingPayload.TYPE, OpenCorePairingPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestCurrentLinksPayload.TYPE, RequestCurrentLinksPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CurrentLinksSnapshotPayload.TYPE, CurrentLinksSnapshotPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestRuntimeHudSnapshotPayload.TYPE, RequestRuntimeHudSnapshotPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(RuntimeHudSnapshotPayload.TYPE, RuntimeHudSnapshotPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RequestCurrentLinksPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> handleRequestCurrentLinks(player, payload));
		});
		ServerPlayNetworking.registerGlobalReceiver(RequestRuntimeHudSnapshotPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player == null) {
				return;
			}
			player.server.execute(() -> handleRequestRuntimeHudSnapshot(player, payload));
		});
	}

	/**
	 * 打开触发源侧配对界面。
	 */
	public static void openTriggerSourcePairing(ServerPlayer player, long sourceSerial) {
		openPairingBySourceType(player, LinkNodeType.TRIGGER_SOURCE, sourceSerial);
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
		if (sourceType == null || sourceSerial <= 0L) {
			return;
		}
		NodeLinksSnapshot linksSnapshot = NodeSnapshotQueryService.queryLinks(player, sourceType, sourceSerial);
		ServerPlayNetworking.send(player, buildPayloadForSourceType(sourceType, sourceSerial, linksSnapshot.visibleTargets()));
	}

	/**
	 * 处理客户端“当前连接”查询请求，并按权限定向回包。
	 */
	private static void handleRequestCurrentLinks(ServerPlayer player, RequestCurrentLinksPayload payload) {
		Optional<LinkNodeType> requestedType = LinkNodeSemantics.tryParseCanonicalType(payload.sourceType());
		if (requestedType.isEmpty() || payload.sourceSerial() <= 0L) {
			return;
		}

		List<Long> visibleTargets = List.of();
		ServerLevel serverLevel = player.serverLevel();
		if (serverLevel.dimension().location().toString().equals(payload.dimensionKey())) {
			BlockPos blockPos = BlockPos.of(payload.blockPos());
			if (serverLevel.isLoaded(blockPos)) {
				double centerX = blockPos.getX() + 0.5D;
				double centerY = blockPos.getY() + 0.5D;
				double centerZ = blockPos.getZ() + 0.5D;
				double maxDistanceSqr = (double) CURRENT_LINKS_REQUEST_MAX_DISTANCE * CURRENT_LINKS_REQUEST_MAX_DISTANCE;
				if (player.distanceToSqr(centerX, centerY, centerZ) > maxDistanceSqr) {
					sendCurrentLinksSnapshot(player, payload, visibleTargets);
					return;
				}
				BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);
				if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
					LinkNodeType sourceType = pairableNodeBlockEntity.getLinkNodeType();
					long sourceSerial = pairableNodeBlockEntity.getSerial();
					if (sourceType == requestedType.get() && sourceSerial == payload.sourceSerial()) {
						visibleTargets = NodeSnapshotQueryService.queryLinks(player, sourceType, sourceSerial).visibleTargets();
					}
				}
			}
		}

		sendCurrentLinksSnapshot(player, payload, visibleTargets);
	}

	/**
	 * 处理客户端近外显“最终 IO”查询请求。
	 */
	private static void handleRequestRuntimeHudSnapshot(ServerPlayer player, RequestRuntimeHudSnapshotPayload payload) {
		Optional<LinkNodeType> requestedType = LinkNodeSemantics.tryParseCanonicalType(payload.sourceType());
		if (requestedType.isEmpty() || payload.sourceSerial() <= 0L) {
			return;
		}

		ResolvedRuntimeHudSnapshot runtimeSnapshot = new ResolvedRuntimeHudSnapshot(false, 0, 0);
		ServerLevel serverLevel = player.serverLevel();
		if (serverLevel.dimension().location().toString().equals(payload.dimensionKey())) {
			BlockPos blockPos = BlockPos.of(payload.blockPos());
			if (serverLevel.isLoaded(blockPos)) {
				double centerX = blockPos.getX() + 0.5D;
				double centerY = blockPos.getY() + 0.5D;
				double centerZ = blockPos.getZ() + 0.5D;
				double maxDistanceSqr = (double) CURRENT_LINKS_REQUEST_MAX_DISTANCE * CURRENT_LINKS_REQUEST_MAX_DISTANCE;
				if (player.distanceToSqr(centerX, centerY, centerZ) <= maxDistanceSqr) {
					BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);
					if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
						LinkNodeType sourceType = pairableNodeBlockEntity.getLinkNodeType();
						long sourceSerial = pairableNodeBlockEntity.getSerial();
						if (sourceType == requestedType.get() && sourceSerial == payload.sourceSerial()) {
							NodeRuntimeSnapshot snapshot = NodeSnapshotQueryService.resolveRuntimeSnapshot(
								player.getServer(),
								sourceType,
								sourceSerial
							).orElse(null);
							if (snapshot != null) {
								runtimeSnapshot = new ResolvedRuntimeHudSnapshot(true, snapshot.inputPower(), snapshot.outputPower());
							}
						}
					}
				}
			}
		}

		sendRuntimeHudSnapshot(player, payload, runtimeSnapshot);
	}

	/**
	 * 回包“当前连接”快照，保持请求上下文一致。
	 */
	private static void sendCurrentLinksSnapshot(
		ServerPlayer player,
		RequestCurrentLinksPayload payload,
		List<Long> visibleTargets
	) {
		ServerPlayNetworking.send(
			player,
			new CurrentLinksSnapshotPayload(
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				visibleTargets
			)
		);
	}

	/**
	 * 回包近外显“最终 IO”快照，保持请求上下文一致。
	 */
	private static void sendRuntimeHudSnapshot(
		ServerPlayer player,
		RequestRuntimeHudSnapshotPayload payload,
		ResolvedRuntimeHudSnapshot runtimeSnapshot
	) {
		ServerPlayNetworking.send(
			player,
			new RuntimeHudSnapshotPayload(
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				runtimeSnapshot.available(),
				runtimeSnapshot.inputPower(),
				runtimeSnapshot.outputPower()
			)
		);
	}

	/**
	 * 按来源类型构建对应的配对 Payload。
	 */
	private static CustomPacketPayload buildPayloadForSourceType(
		LinkNodeType sourceType,
		long sourceSerial,
		List<Long> currentTargets
	) {
		if (sourceType == LinkNodeType.TRIGGER_SOURCE) {
			return new OpenTriggerSourcePairingPayload(sourceSerial, currentTargets);
		}
		return new OpenCorePairingPayload(sourceSerial, currentTargets);
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
	 * “当前连接”查询 Payload 编码。
	 */
	private static void encodeCurrentLinksPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		List<Long> targets
	) {
		buffer.writeUtf(dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPos);
		buffer.writeUtf(sourceType, NODE_TYPE_MAX_LENGTH);
		buffer.writeVarLong(sourceSerial);
		buffer.writeVarInt(targets.size());
		for (long target : targets) {
			buffer.writeVarLong(target);
		}
	}

	/**
	 * 近外显查询请求 Payload 编码。
	 */
	private static void encodeSnapshotRequestPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) {
		buffer.writeUtf(dimensionKey, DIMENSION_KEY_MAX_LENGTH);
		buffer.writeLong(blockPos);
		buffer.writeUtf(sourceType, NODE_TYPE_MAX_LENGTH);
		buffer.writeVarLong(sourceSerial);
	}

	/**
	 * “当前连接”查询 Payload 解码。
	 */
	private static DecodedCurrentLinksPayload decodeCurrentLinksPayload(FriendlyByteBuf buffer) {
		String dimensionKey = buffer.readUtf(DIMENSION_KEY_MAX_LENGTH);
		long blockPos = buffer.readLong();
		String sourceType = buffer.readUtf(NODE_TYPE_MAX_LENGTH);
		long sourceSerial = buffer.readVarLong();
		int size = buffer.readVarInt();
		List<Long> targets = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			targets.add(buffer.readVarLong());
		}
		return new DecodedCurrentLinksPayload(dimensionKey, blockPos, sourceType, sourceSerial, targets);
	}

	/**
	 * 近外显查询请求 Payload 解码。
	 */
	private static DecodedSnapshotRequestPayload decodeSnapshotRequestPayload(FriendlyByteBuf buffer) {
		String dimensionKey = buffer.readUtf(DIMENSION_KEY_MAX_LENGTH);
		long blockPos = buffer.readLong();
		String sourceType = buffer.readUtf(NODE_TYPE_MAX_LENGTH);
		long sourceSerial = buffer.readVarLong();
		return new DecodedSnapshotRequestPayload(dimensionKey, blockPos, sourceType, sourceSerial);
	}

	/**
	 * 近外显运行态快照 Payload 编码。
	 */
	private static void encodeRuntimeHudPayload(
		FriendlyByteBuf buffer,
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		boolean available,
		int inputPower,
		int outputPower
	) {
		encodeSnapshotRequestPayload(buffer, dimensionKey, blockPos, sourceType, sourceSerial);
		buffer.writeBoolean(available);
		buffer.writeVarInt(inputPower);
		buffer.writeVarInt(outputPower);
	}

	/**
	 * 近外显运行态快照 Payload 解码。
	 */
	private static DecodedRuntimeHudPayload decodeRuntimeHudPayload(FriendlyByteBuf buffer) {
		DecodedSnapshotRequestPayload requestPayload = decodeSnapshotRequestPayload(buffer);
		boolean available = buffer.readBoolean();
		int inputPower = buffer.readVarInt();
		int outputPower = buffer.readVarInt();
		return new DecodedRuntimeHudPayload(
			requestPayload.dimensionKey(),
			requestPayload.blockPos(),
			requestPayload.sourceType(),
			requestPayload.sourceSerial(),
			available,
			inputPower,
			outputPower
		);
	}

	/**
	 * 通用配对 Payload 解码结果。
	 */
	private record DecodedPayload(long sourceSerial, List<Long> targets) {}

	/**
	 * “当前连接”Payload 解码结果。
	 */
	private record DecodedCurrentLinksPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		List<Long> targets
	) {}

	/**
	 * 近外显查询请求 Payload 解码结果。
	 */
	private record DecodedSnapshotRequestPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) {}

	/**
	 * 近外显运行态快照 Payload 解码结果。
	 */
	private record DecodedRuntimeHudPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		boolean available,
		int inputPower,
		int outputPower
	) {}

	/**
	 * 近外显最终 IO 读模型。
	 */
	private record ResolvedRuntimeHudSnapshot(boolean available, int inputPower, int outputPower) {}

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

	/**
	 * 客户端近外显“当前连接”查询请求。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 */
	public record RequestCurrentLinksPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestCurrentLinksPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_current_links_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestCurrentLinksPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> encodeSnapshotRequestPayload(buffer, payload.dimensionKey, payload.blockPos, payload.sourceType, payload.sourceSerial),
			buffer -> {
				DecodedSnapshotRequestPayload payload = decodeSnapshotRequestPayload(buffer);
				return new RequestCurrentLinksPayload(
					payload.dimensionKey(),
					payload.blockPos(),
					payload.sourceType(),
					payload.sourceSerial()
				);
			}
		);

		public RequestCurrentLinksPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端近外显“最终 IO”查询请求。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 */
	public record RequestRuntimeHudSnapshotPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RequestRuntimeHudSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "request_runtime_hud_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, RequestRuntimeHudSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> encodeSnapshotRequestPayload(buffer, payload.dimensionKey, payload.blockPos, payload.sourceType, payload.sourceSerial),
			buffer -> {
				DecodedSnapshotRequestPayload payload = decodeSnapshotRequestPayload(buffer);
				return new RequestRuntimeHudSnapshotPayload(
					payload.dimensionKey(),
					payload.blockPos(),
					payload.sourceType(),
					payload.sourceSerial()
				);
			}
		);

		public RequestRuntimeHudSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端下发给客户端的“当前连接”快照（已按权限脱敏）。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 * @param targets 可见目标序号集合（不可见时为空）
	 */
	public record CurrentLinksSnapshotPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		List<Long> targets
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CurrentLinksSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "current_links_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, CurrentLinksSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> encodeCurrentLinksPayload(
				buffer,
				payload.dimensionKey,
				payload.blockPos,
				payload.sourceType,
				payload.sourceSerial,
				payload.targets
			),
			buffer -> {
				DecodedCurrentLinksPayload payload = decodeCurrentLinksPayload(buffer);
				return new CurrentLinksSnapshotPayload(
					payload.dimensionKey(),
					payload.blockPos(),
					payload.sourceType(),
					payload.sourceSerial(),
					payload.targets()
				);
			}
		);

		public CurrentLinksSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
			targets = List.copyOf(targets);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端下发给客户端的近外显“最终 IO”快照。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPos 方块坐标压缩值
	 * @param sourceType 来源类型（triggerSource/core）
	 * @param sourceSerial 来源序列号
	 * @param available 当前是否有可读运行态
	 * @param inputPower 最终输入强度
	 * @param outputPower 最终输出强度
	 */
	public record RuntimeHudSnapshotPayload(
		String dimensionKey,
		long blockPos,
		String sourceType,
		long sourceSerial,
		boolean available,
		int inputPower,
		int outputPower
	) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<RuntimeHudSnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, "runtime_hud_snapshot")
		);
		public static final StreamCodec<FriendlyByteBuf, RuntimeHudSnapshotPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> encodeRuntimeHudPayload(
				buffer,
				payload.dimensionKey,
				payload.blockPos,
				payload.sourceType,
				payload.sourceSerial,
				payload.available,
				payload.inputPower,
				payload.outputPower
			),
			buffer -> {
				DecodedRuntimeHudPayload payload = decodeRuntimeHudPayload(buffer);
				return new RuntimeHudSnapshotPayload(
					payload.dimensionKey(),
					payload.blockPos(),
					payload.sourceType(),
					payload.sourceSerial(),
					payload.available(),
					payload.inputPower(),
					payload.outputPower()
				);
			}
		);

		public RuntimeHudSnapshotPayload {
			dimensionKey = dimensionKey == null ? "" : dimensionKey;
			sourceType = sourceType == null ? "" : sourceType;
			inputPower = clampHudPower(inputPower);
			outputPower = clampHudPower(outputPower);
		}

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static int clampHudPower(int power) {
		return Math.max(0, Math.min(15, power));
	}
}
