package com.makomi.network;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 可配对节点网络请求的统一服务端定位校验支撑。
 * <p>
 * 统一收口以下边界：
 * </p>
 * <ul>
 * <li>维度一致；</li>
 * <li>区块已加载；</li>
 * <li>玩家距离足够近；</li>
 * <li>命中方块实体的类型与序号和客户端上报期望一致。</li>
 * </ul>
 */
final class PairableNodeRequestValidationSupport {
	static final int DEFAULT_MAX_INTERACTION_DISTANCE = 8;

	private PairableNodeRequestValidationSupport() {
	}

	/**
	 * 解析并校验客户端请求指向的可配对节点。
	 *
	 * @param player 请求玩家
	 * @param dimensionKey 客户端上报维度键
	 * @param blockPosLong 客户端上报方块坐标
	 * @param expectedType 客户端上报期望节点类型
	 * @param expectedSerial 客户端上报期望节点序号
	 * @param maxDistance 最大允许交互距离
	 * @return 校验通过的节点；失败时返回 {@code null}
	 */
	static PairableNodeBlockEntity resolveRequestedNode(
		ServerPlayer player,
		String dimensionKey,
		long blockPosLong,
		LinkNodeType expectedType,
		long expectedSerial,
		int maxDistance
	) {
		if (player == null || expectedType == null || expectedSerial <= 0L || maxDistance < 0) {
			return null;
		}
		ServerLevel serverLevel = player.serverLevel();
		if (serverLevel == null || !serverLevel.dimension().location().toString().equals(dimensionKey)) {
			return null;
		}

		BlockPos blockPos = BlockPos.of(blockPosLong);
		if (!serverLevel.isLoaded(blockPos)) {
			return null;
		}
		if (!isWithinInteractionDistance(player.getX(), player.getY(), player.getZ(), blockPos, maxDistance)) {
			return null;
		}

		BlockEntity blockEntity = serverLevel.getBlockEntity(blockPos);
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return null;
		}
		if (!pairableNodeBlockEntity.matchesNodeIdentity(expectedType, expectedSerial)) {
			return null;
		}
		return pairableNodeBlockEntity;
	}

	/**
	 * 判断玩家与目标方块是否仍处于允许交互的距离内。
	 *
	 * @param playerX 玩家 X 坐标
	 * @param playerY 玩家 Y 坐标
	 * @param playerZ 玩家 Z 坐标
	 * @param blockPos 目标方块坐标
	 * @param maxDistance 最大允许距离
	 * @return 是否允许继续按该目标处理
	 */
	static boolean isWithinInteractionDistance(double playerX, double playerY, double playerZ, BlockPos blockPos, int maxDistance) {
		if (blockPos == null || maxDistance < 0) {
			return false;
		}
		double centerX = blockPos.getX() + 0.5D;
		double centerY = blockPos.getY() + 0.5D;
		double centerZ = blockPos.getZ() + 0.5D;
		double maxDistanceSqr = (double) maxDistance * (double) maxDistance;
		double dx = playerX - centerX;
		double dy = playerY - centerY;
		double dz = playerZ - centerZ;
		return (dx * dx) + (dy * dy) + (dz * dz) <= maxDistanceSqr;
	}

	/**
	 * 判断实际命中节点是否与客户端上报的节点身份完全一致。
	 *
	 * @param actualType 实际节点类型
	 * @param actualSerial 实际节点序号
	 * @param expectedType 期望节点类型
	 * @param expectedSerial 期望节点序号
	 * @return 类型与序号是否同时匹配
	 */
	static boolean matchesExpectedNodeIdentity(
		LinkNodeType actualType,
		long actualSerial,
		LinkNodeType expectedType,
		long expectedSerial
	) {
		return actualType == expectedType && actualSerial > 0L && actualSerial == expectedSerial;
	}
}
