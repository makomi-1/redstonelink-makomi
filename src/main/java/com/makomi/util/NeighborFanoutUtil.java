package com.makomi.util;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 邻居通知扇出工具。
 * <p>
 * 统一封装“中心 + 六方向”的二级邻居通知，
 * 避免各方块/方块实体分散实现导致逻辑重复与行为漂移。
 * </p>
 */
public final class NeighborFanoutUtil {
	private static final LongAdder FANOUT_REQUEST_COUNT = new LongAdder();
	private static final LongAdder CENTER_NOTIFY_SENT_COUNT = new LongAdder();
	private static final LongAdder NEIGHBOR_NOTIFY_ATTEMPT_COUNT = new LongAdder();
	private static final LongAdder NEIGHBOR_NOTIFY_SENT_COUNT = new LongAdder();
	private static final LongAdder CROSS_CHUNK_SKIP_COUNT = new LongAdder();
	private static final LongAdder FANOUT_DEDUP_HIT_COUNT = new LongAdder();

	private NeighborFanoutUtil() {
	}

	/**
	 * 扇出诊断快照。
	 *
	 * @param fanoutRequestCount 扇出入口请求次数
	 * @param centerNotifySentCount 中心位置实际发送次数
	 * @param neighborNotifyAttemptCount 邻居通知尝试次数（六方向候选）
	 * @param neighborNotifySentCount 邻居通知实际发送次数
	 * @param crossChunkSkipCount 跨 chunk 且未加载导致的跳过次数
	 * @param fanoutDedupHitCount 实体侧同时间粒度去重命中次数
	 */
	public record FanoutDiagnosticsSnapshot(
		long fanoutRequestCount,
		long centerNotifySentCount,
		long neighborNotifyAttemptCount,
		long neighborNotifySentCount,
		long crossChunkSkipCount,
		long fanoutDedupHitCount
	) {}

	/**
	 * 返回当前扇出诊断快照。
	 */
	public static FanoutDiagnosticsSnapshot diagnosticsSnapshot() {
		return new FanoutDiagnosticsSnapshot(
			FANOUT_REQUEST_COUNT.sum(),
			CENTER_NOTIFY_SENT_COUNT.sum(),
			NEIGHBOR_NOTIFY_ATTEMPT_COUNT.sum(),
			NEIGHBOR_NOTIFY_SENT_COUNT.sum(),
			CROSS_CHUNK_SKIP_COUNT.sum(),
			FANOUT_DEDUP_HIT_COUNT.sum()
		);
	}

	/**
	 * 重置扇出诊断计数。
	 * <p>
	 * 仅用于测试或临时诊断窗口切分，不影响运行语义。
	 * </p>
	 */
	public static void resetDiagnosticsCounters() {
		FANOUT_REQUEST_COUNT.reset();
		CENTER_NOTIFY_SENT_COUNT.reset();
		NEIGHBOR_NOTIFY_ATTEMPT_COUNT.reset();
		NEIGHBOR_NOTIFY_SENT_COUNT.reset();
		CROSS_CHUNK_SKIP_COUNT.reset();
		FANOUT_DEDUP_HIT_COUNT.reset();
	}

	/**
	 * 记录一次实体侧扇出去重命中。
	 */
	public static void recordFanoutDedupHit() {
		FANOUT_DEDUP_HIT_COUNT.increment();
	}

	/**
	 * 对给定位置执行“中心 + 六方向”邻居通知。
	 *
	 * @param level 维度上下文
	 * @param centerPos 中心位置
	 * @param sourceBlock 触发通知的方块类型
	 */
	public static void notifyCenterAndSixNeighbors(Level level, BlockPos centerPos, Block sourceBlock) {
		if (level == null || centerPos == null || sourceBlock == null) {
			return;
		}
		FANOUT_REQUEST_COUNT.increment();
		updateNeighborsAtCenter(level, centerPos, sourceBlock);
		int centerChunkX = centerPos.getX() >> 4;
		int centerChunkZ = centerPos.getZ() >> 4;
		for (Direction direction : Direction.values()) {
			updateNeighborsAtNeighbor(level, centerPos, direction, sourceBlock, centerChunkX, centerChunkZ);
		}
	}

	/**
	 * 对给定位置执行“中心 + 六方向”邻居通知（时间粒度去重入口）。
	 * <p>
	 * 同一时间粒度（tick+slot）下，若中心位置与输出值（active+power）未变化，则抑制重复扇出。
	 * </p>
	 *
	 * @param level 维度上下文
	 * @param centerPos 中心位置
	 * @param sourceBlock 触发通知的方块类型
	 * @param timeTick 时间粒度 tick
	 * @param timeSlot 时间粒度 slot
	 * @param active 当前解析激活态
	 * @param resolvedPower 当前解析输出功率
	 */
	public static void notifyCenterAndSixNeighbors(
		Level level,
		BlockPos centerPos,
		Block sourceBlock,
		long timeTick,
		int timeSlot,
		boolean active,
		int resolvedPower
	) {
		// 时间粒度参数由实体侧去重使用；工具层保持无状态，避免重复哈希/锁成本。
		notifyCenterAndSixNeighbors(level, centerPos, sourceBlock);
	}

	/**
	 * 中心位置邻居通知。
	 * <p>
	 * 中心位置本身由调用方保证有效，避免在热路径重复执行 hasChunkAt 检查。
	 * </p>
	 */
	private static void updateNeighborsAtCenter(Level level, BlockPos pos, Block sourceBlock) {
		if (level == null || pos == null || sourceBlock == null) {
			return;
		}
		if (!level.isInWorldBounds(pos)) {
			return;
		}
		CENTER_NOTIFY_SENT_COUNT.increment();
		level.updateNeighborsAt(pos, sourceBlock);
	}

	/**
	 * 邻居位置通知。
	 * <p>
	 * 邻居位使用“排除回流方向”通知，减少立即回打中心造成的重复扩散。
	 * 仅在跨 chunk 邻居上检查 chunk 已加载，降低热路径固定判断成本。
	 * </p>
	 */
	private static void updateNeighborsAtNeighbor(
		Level level,
		BlockPos centerPos,
		Direction direction,
		Block sourceBlock,
		int centerChunkX,
		int centerChunkZ
	) {
		if (level == null || centerPos == null || direction == null || sourceBlock == null) {
			return;
		}
		BlockPos neighborPos = centerPos.relative(direction);
		NEIGHBOR_NOTIFY_ATTEMPT_COUNT.increment();
		if (!level.isInWorldBounds(neighborPos)) {
			return;
		}
		int neighborChunkX = neighborPos.getX() >> 4;
		int neighborChunkZ = neighborPos.getZ() >> 4;
		boolean crossChunk = neighborChunkX != centerChunkX || neighborChunkZ != centerChunkZ;
		if (crossChunk && !level.hasChunk(neighborChunkX, neighborChunkZ)) {
			CROSS_CHUNK_SKIP_COUNT.increment();
			return;
		}
		NEIGHBOR_NOTIFY_SENT_COUNT.increment();
		level.updateNeighborsAtExceptFromFacing(neighborPos, sourceBlock, direction.getOpposite());
	}
}
