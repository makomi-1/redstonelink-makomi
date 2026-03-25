package com.makomi.network;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.NodeSnapshotQueryService;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * `PairingNetwork` 的服务端请求处理壳。
 * <p>
 * 该 helper 负责校验客户端请求上下文、查询服务端快照并回包，不承载 payload 编解码职责。
 * </p>
 */
final class PairingNetworkServerHandlerSupport {
	private static final int CURRENT_LINKS_REQUEST_MAX_DISTANCE = 8;

	private PairingNetworkServerHandlerSupport() {
	}

	/**
	 * 处理客户端“当前连接”查询请求。
	 *
	 * @param player 发起请求的服务端玩家
	 * @param payload 客户端上传的节点定位上下文
	 */
	static void handleRequestCurrentLinks(ServerPlayer player, PairingNetwork.RequestCurrentLinksPayload payload) {
		Optional<LinkNodeType> requestedType = LinkNodeSemantics.tryParseCanonicalType(payload.sourceType());
		if (requestedType.isEmpty() || payload.sourceSerial() <= 0L) {
			return;
		}

		List<Long> visibleTargets = List.of();
		PairableNodeBlockEntity pairableNode = resolveRequestedNode(
			player,
			payload.dimensionKey(),
			payload.blockPos(),
			requestedType.get(),
			payload.sourceSerial()
		);
		if (pairableNode != null) {
			visibleTargets = NodeSnapshotQueryService
				.queryLinks(player, pairableNode.getLinkNodeType(), pairableNode.getSerial())
				.visibleTargets();
		}

		sendCurrentLinksSnapshot(player, payload, visibleTargets);
	}

	/**
	 * 处理客户端近外显“最终 IO”查询请求。
	 *
	 * @param player 发起请求的服务端玩家
	 * @param payload 客户端上传的节点定位上下文
	 */
	static void handleRequestRuntimeHudSnapshot(ServerPlayer player, PairingNetwork.RequestRuntimeHudSnapshotPayload payload) {
		Optional<LinkNodeType> requestedType = LinkNodeSemantics.tryParseCanonicalType(payload.sourceType());
		if (requestedType.isEmpty() || payload.sourceSerial() <= 0L) {
			return;
		}

		ResolvedRuntimeHudSnapshot runtimeSnapshot = new ResolvedRuntimeHudSnapshot(false, 0, 0);
		PairableNodeBlockEntity pairableNode = resolveRequestedNode(
			player,
			payload.dimensionKey(),
			payload.blockPos(),
			requestedType.get(),
			payload.sourceSerial()
		);
		if (pairableNode != null) {
			NodeRuntimeSnapshot snapshot = NodeSnapshotQueryService
				.resolveRuntimeSnapshot(player.getServer(), pairableNode.getLinkNodeType(), pairableNode.getSerial())
				.orElse(null);
			if (snapshot != null) {
				runtimeSnapshot = new ResolvedRuntimeHudSnapshot(true, snapshot.inputPower(), snapshot.outputPower());
			}
		}

		sendRuntimeHudSnapshot(player, payload, runtimeSnapshot);
	}

	/**
	 * 解析并校验客户端请求指向的节点。
	 * <p>
	 * 只有维度一致、区块已加载、玩家距离足够近且方块实体的类型/序号完全匹配时，才允许读取运行态。
	 * </p>
	 *
	 * @param player 请求发起者
	 * @param dimensionKey 客户端上报维度键
	 * @param blockPosLong 客户端上报方块坐标
	 * @param requestedType 客户端上报语义类型
	 * @param requestedSerial 客户端上报序列号
	 * @return 通过校验的配对节点；不通过时返回 `null`
	 */
	private static PairableNodeBlockEntity resolveRequestedNode(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong,
		LinkNodeType requestedType,
		long requestedSerial
	) {
		ServerLevel serverLevel = player.serverLevel();
		if (!serverLevel.dimension().location().toString().equals(dimensionKey)) {
			return null;
		}

		BlockPos blockPos = BlockPos.of(blockPosLong);
		if (!serverLevel.isLoaded(blockPos)) {
			return null;
		}

		double centerX = blockPos.getX() + 0.5D;
		double centerY = blockPos.getY() + 0.5D;
		double centerZ = blockPos.getZ() + 0.5D;
		double maxDistanceSqr = (double) CURRENT_LINKS_REQUEST_MAX_DISTANCE * CURRENT_LINKS_REQUEST_MAX_DISTANCE;
		if (player.distanceToSqr(centerX, centerY, centerZ) > maxDistanceSqr) {
			return null;
		}

		BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return null;
		}
		if (pairableNodeBlockEntity.getLinkNodeType() != requestedType) {
			return null;
		}
		if (pairableNodeBlockEntity.getSerial() != requestedSerial) {
			return null;
		}
		return pairableNodeBlockEntity;
	}

	/**
	 * 回包“当前连接”快照，保持请求上下文与客户端命中节点一致。
	 *
	 * @param player 回包目标玩家
	 * @param payload 原始请求
	 * @param visibleTargets 服务端按权限筛出的可见目标
	 */
	private static void sendCurrentLinksSnapshot(
		ServerPlayer player,
		PairingNetwork.RequestCurrentLinksPayload payload,
		List<Long> visibleTargets
	) {
		ServerPlayNetworking.send(
			player,
			new PairingNetwork.CurrentLinksSnapshotPayload(
				payload.dimensionKey(),
				payload.blockPos(),
				payload.sourceType(),
				payload.sourceSerial(),
				visibleTargets
			)
		);
	}

	/**
	 * 回包“最终 IO”快照，保持请求上下文与客户端命中节点一致。
	 *
	 * @param player 回包目标玩家
	 * @param payload 原始请求
	 * @param runtimeSnapshot 服务端解析出的运行态快照
	 */
	private static void sendRuntimeHudSnapshot(
		ServerPlayer player,
		PairingNetwork.RequestRuntimeHudSnapshotPayload payload,
		ResolvedRuntimeHudSnapshot runtimeSnapshot
	) {
		ServerPlayNetworking.send(
			player,
			new PairingNetwork.RuntimeHudSnapshotPayload(
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
	 * 近外显最终 IO 的服务端读模型。
	 */
	private record ResolvedRuntimeHudSnapshot(boolean available, int inputPower, int outputPower) {}
}
