package com.makomi.compat;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * lithium 动态路径命中计数器。
 * <p>
 * 由 mixin 在运行时写入，供诊断与健康检查读取。
 * </p>
 */
public final class LithiumHitCounter {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final boolean ENABLE_LITHIUM_DEBUG_LOG = Boolean.getBoolean("redstonelink.debug.lithium.mixin");
	private static final AtomicInteger RECEIVED_POWER_IS_HITS = new AtomicInteger();
	private static final AtomicInteger POWER_FROM_SIDE_IS_HITS = new AtomicInteger();
	private static final AtomicInteger POWER_FROM_SIDE_VALUE_HITS = new AtomicInteger();
	private static final AtomicInteger STRONG_POWER_IS_HITS = new AtomicInteger();

	private LithiumHitCounter() {
	}

	/**
	 * 记录 getReceivedPower 的 state.is(this) 命中。
	 */
	public static void recordReceivedPowerIs() {
		recordHit("getReceivedPower#is", RECEIVED_POWER_IS_HITS);
	}

	/**
	 * 记录 getPowerFromSide 的 state.is(this) 命中。
	 */
	public static void recordPowerFromSideIs() {
		recordHit("getPowerFromSide#is", POWER_FROM_SIDE_IS_HITS);
	}

	/**
	 * 记录 getPowerFromSide 的 getValue(POWER) 命中。
	 */
	public static void recordPowerFromSideValue() {
		recordHit("getPowerFromSide#getValue", POWER_FROM_SIDE_VALUE_HITS);
	}

	/**
	 * 记录 getStrongPowerTo 的 state.is(this) 命中。
	 */
	public static void recordStrongPowerToIs() {
		recordHit("getStrongPowerTo#is", STRONG_POWER_IS_HITS);
	}

	/**
	 * 读取当前统计快照。
	 */
	public static LithiumHitStats snapshot() {
		return new LithiumHitStats(
			RECEIVED_POWER_IS_HITS.get(),
			POWER_FROM_SIDE_IS_HITS.get(),
			POWER_FROM_SIDE_VALUE_HITS.get(),
			STRONG_POWER_IS_HITS.get()
		);
	}

	/**
	 * 仅在显式开启调试参数时打印命中统计。
	 */
	private static void recordHit(String key, AtomicInteger counter) {
		int hits = counter.incrementAndGet();
		if (!ENABLE_LITHIUM_DEBUG_LOG) {
			return;
		}
		if (hits <= 8 || hits % 64 == 0) {
			LOGGER.info("[RedstoneLink/LithiumMixin] {} hit={}", key, hits);
		}
	}

	/**
	 * lithium 动态路径命中快照。
	 */
	public record LithiumHitStats(
		int receivedPowerIsHits,
		int powerFromSideIsHits,
		int powerFromSideValueHits,
		int strongPowerToIsHits
	) {
		/**
		 * @return 四类命中总数
		 */
		public int totalHits() {
			return receivedPowerIsHits + powerFromSideIsHits + powerFromSideValueHits + strongPowerToIsHits;
		}
	}
}
