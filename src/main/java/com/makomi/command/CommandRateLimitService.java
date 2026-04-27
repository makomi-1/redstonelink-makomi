package com.makomi.command;

import com.makomi.config.RedstoneLinkConfig;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 命令限流服务。
 * <p>
 * 采用“窗口计数 + 分层闸门”模式：
 * 1. 全局容量（Global）
 * 2. 权限层级容量（Tier）
 * 3. 来源个体容量（Actor）
 * 4. 来源+命令组容量（ActorGroup）
 * </p>
 * <p>
 * 该服务只负责“频率是否允许”，不参与业务权限判定。
 * </p>
 */
public final class CommandRateLimitService {
	private static final int CLEANUP_INTERVAL_TICKS = 200;
	private static final int STALE_WINDOW_MULTIPLIER = 8;

	private static final WindowCounter GLOBAL_COUNTER = new WindowCounter();
	private static final Map<Integer, WindowCounter> TIER_COUNTERS = new HashMap<>();
	private static final Map<ActorKey, WindowCounter> ACTOR_COUNTERS = new HashMap<>();
	private static final Map<ActorGroupKey, WindowCounter> ACTOR_GROUP_COUNTERS = new HashMap<>();
	private static long lastCleanupTick = Long.MIN_VALUE;

	private CommandRateLimitService() {
	}

	/**
	 * 命令分组（一期）。
	 */
	public enum CommandGroup {
		LINK_RW,
		GRAPH_WRITE,
		CROSSCHUNK,
		OTHER
	}

	/**
	 * 限流窗口只读预检结果。
	 *
	 * @param allowed 当前是否允许执行
	 * @param hardBlocked 当前成本是否已超过单窗口容量，上层应提示拆分而不是等待
	 * @param waitTicks 若为瞬时限流，距离再次满足条件还需等待的 tick 数
	 * @param cost 本次请求成本
	 */
	public record AcquirePreview(boolean allowed, boolean hardBlocked, long waitTicks, int cost) {
		public AcquirePreview {
			waitTicks = Math.max(0L, waitTicks);
			cost = Math.max(1, cost);
		}
	}

	/**
	 * 执行限流检查；失败时发送统一提示。
	 *
	 * @param source 命令源
	 * @param group 命令分组
	 * @param cost 本次消耗成本（>=1）
	 * @return true=允许执行；false=触发限流
	 */
	public static boolean tryAcquireOrSendFailure(CommandSourceStack source, CommandGroup group, int cost) {
		if (tryAcquire(source, group, cost)) {
			return true;
		}
		source.sendFailure(Component.translatable("message.redstonelink.command.rate_limit.exceeded"));
		return false;
	}

	/**
	 * 按批量大小计算命令成本。
	 *
	 * @param baseCost 基础成本
	 * @param itemCount 目标数量
	 * @param stepSize 每增加 stepSize 个目标增加 1 成本
	 * @return 计算后的成本，最小为 1
	 */
	public static int computeBatchCost(int baseCost, int itemCount, int stepSize) {
		int safeBaseCost = Math.max(1, baseCost);
		if (itemCount <= 0) {
			return safeBaseCost;
		}
		int safeStepSize = Math.max(1, stepSize);
		long extraCost = (itemCount - 1L) / safeStepSize;
		long resolved = (long) safeBaseCost + extraCost;
		return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, resolved));
	}

	/**
	 * 只读预检本次请求在当前窗口下是否可执行，不消费额度。
	 */
	public static AcquirePreview previewAcquire(CommandSourceStack source, CommandGroup group, int cost) {
		int normalizedCost = Math.max(1, cost);
		if (source == null || group == null || !RedstoneLinkConfig.rateLimit().enabled()) {
			return new AcquirePreview(true, false, 0L, normalizedCost);
		}

		int windowTicks = RedstoneLinkConfig.rateLimit().windowTicks();
		long nowTick = source.getLevel().getGameTime();
		int permissionLevel = resolvePermissionLevel(source);
		ActorKey actorKey = resolveActorKey(source, permissionLevel);
		ActorGroupKey actorGroupKey = new ActorGroupKey(actorKey, group);

		int globalCapacity = RedstoneLinkConfig.rateLimit().globalCapacity();
		WindowCounter globalCounter = GLOBAL_COUNTER;
		int tierCapacity = RedstoneLinkConfig.rateLimit().tierCapacity(permissionLevel);
		WindowCounter tierCounter = TIER_COUNTERS.get(permissionLevel);
		int actorCapacity = RedstoneLinkConfig.rateLimit().actorCapacity(permissionLevel);
		WindowCounter actorCounter = ACTOR_COUNTERS.get(actorKey);
		int actorGroupCapacity = resolveActorGroupCapacity(permissionLevel, group);
		WindowCounter actorGroupCounter = ACTOR_GROUP_COUNTERS.get(actorGroupKey);

		if (
			normalizedCost > globalCapacity ||
			normalizedCost > tierCapacity ||
			normalizedCost > actorCapacity ||
			normalizedCost > actorGroupCapacity
		) {
			return new AcquirePreview(false, true, 0L, normalizedCost);
		}

		long waitTicks = 0L;
		boolean allowed = true;
		waitTicks = Math.max(waitTicks, previewCounterWaitTicks(globalCounter, nowTick, windowTicks, globalCapacity, normalizedCost));
		waitTicks = Math.max(waitTicks, previewCounterWaitTicks(tierCounter, nowTick, windowTicks, tierCapacity, normalizedCost));
		waitTicks = Math.max(waitTicks, previewCounterWaitTicks(actorCounter, nowTick, windowTicks, actorCapacity, normalizedCost));
		waitTicks = Math.max(
			waitTicks,
			previewCounterWaitTicks(actorGroupCounter, nowTick, windowTicks, actorGroupCapacity, normalizedCost)
		);
		if (waitTicks > 0L) {
			allowed = false;
		}
		return new AcquirePreview(allowed, false, waitTicks, normalizedCost);
	}

	/**
	 * 执行限流检查（不发送提示）。
	 *
	 * @param source 命令源
	 * @param group 命令分组
	 * @param cost 本次消耗成本（>=1）
	 * @return true=允许执行；false=触发限流
	 */
	public static boolean tryAcquire(CommandSourceStack source, CommandGroup group, int cost) {
		if (source == null || group == null || !RedstoneLinkConfig.rateLimit().enabled()) {
			return true;
		}

		int normalizedCost = Math.max(1, cost);
		int windowTicks = RedstoneLinkConfig.rateLimit().windowTicks();
		long nowTick = source.getLevel().getGameTime();
		int permissionLevel = resolvePermissionLevel(source);
		ActorKey actorKey = resolveActorKey(source, permissionLevel);
		ActorGroupKey actorGroupKey = new ActorGroupKey(actorKey, group);

		cleanupIfNeeded(nowTick, windowTicks);

		int globalCapacity = RedstoneLinkConfig.rateLimit().globalCapacity();
		if (!GLOBAL_COUNTER.canConsume(nowTick, windowTicks, globalCapacity, normalizedCost)) {
			return false;
		}

		int tierCapacity = RedstoneLinkConfig.rateLimit().tierCapacity(permissionLevel);
		WindowCounter tierCounter = TIER_COUNTERS.get(permissionLevel);
		if (tierCounter == null) {
			tierCounter = new WindowCounter();
		}
		if (!tierCounter.canConsume(nowTick, windowTicks, tierCapacity, normalizedCost)) {
			return false;
		}

		int actorCapacity = RedstoneLinkConfig.rateLimit().actorCapacity(permissionLevel);
		WindowCounter actorCounter = ACTOR_COUNTERS.get(actorKey);
		if (actorCounter == null) {
			actorCounter = new WindowCounter();
		}
		if (!actorCounter.canConsume(nowTick, windowTicks, actorCapacity, normalizedCost)) {
			return false;
		}

		int actorGroupCapacity = resolveActorGroupCapacity(permissionLevel, group);
		WindowCounter actorGroupCounter = ACTOR_GROUP_COUNTERS.get(actorGroupKey);
		if (actorGroupCounter == null) {
			actorGroupCounter = new WindowCounter();
		}
		if (!actorGroupCounter.canConsume(nowTick, windowTicks, actorGroupCapacity, normalizedCost)) {
			return false;
		}

		GLOBAL_COUNTER.consume(nowTick, windowTicks, normalizedCost);
		tierCounter.consume(nowTick, windowTicks, normalizedCost);
		actorCounter.consume(nowTick, windowTicks, normalizedCost);
		actorGroupCounter.consume(nowTick, windowTicks, normalizedCost);

		TIER_COUNTERS.put(permissionLevel, tierCounter);
		ACTOR_COUNTERS.put(actorKey, actorCounter);
		ACTOR_GROUP_COUNTERS.put(actorGroupKey, actorGroupCounter);
		return true;
	}

	/**
	 * 按分组读取来源+命令组容量。
	 */
	private static int resolveActorGroupCapacity(int permissionLevel, CommandGroup group) {
		return switch (group) {
			case LINK_RW -> RedstoneLinkConfig.rateLimit().actorLinkRwCapacity(permissionLevel);
			case GRAPH_WRITE -> RedstoneLinkConfig.rateLimit().actorGraphWriteCapacity(permissionLevel);
			case CROSSCHUNK -> RedstoneLinkConfig.rateLimit().actorCrossChunkCapacity(permissionLevel);
			case OTHER -> RedstoneLinkConfig.rateLimit().actorOtherCapacity(permissionLevel);
		};
	}

	private static long previewCounterWaitTicks(
		WindowCounter counter,
		long nowTick,
		int windowTicks,
		int capacity,
		int cost
	) {
		if (counter == null || counter.canConsume(nowTick, windowTicks, capacity, cost)) {
			return 0L;
		}
		return counter.waitTicksUntilAvailable(nowTick, windowTicks);
	}

	/**
	 * 解析命令源的最高权限等级（0~4）。
	 */
	private static int resolvePermissionLevel(CommandSourceStack source) {
		for (int level = 4; level >= 0; level--) {
			if (CommandPermissionCompat.hasPermission(source, level)) {
				return level;
			}
		}
		return 0;
	}

	/**
	 * 生成命令来源身份键，避免“只按权限等级分桶”导致同级玩家互相抢占容量。
	 */
	private static ActorKey resolveActorKey(CommandSourceStack source, int permissionLevel) {
		Entity entity = source.getEntity();
		if (entity instanceof ServerPlayer player) {
			return new ActorKey(SourceKind.PLAYER, player.getUUID().toString(), permissionLevel);
		}
		if (entity != null) {
			return new ActorKey(SourceKind.ENTITY, entity.getStringUUID(), permissionLevel);
		}

		BlockPos blockPos = BlockPos.containing(source.getPosition());
		String dimension = source.getLevel().dimension().identifier().toString();
		String principalId = dimension
			+ ":"
			+ blockPos.getX()
			+ ","
			+ blockPos.getY()
			+ ","
			+ blockPos.getZ()
			+ ":"
			+ source.getTextName();
		return new ActorKey(SourceKind.SYSTEM, principalId, permissionLevel);
	}

	/**
	 * 定期清理长时间未触达的窗口状态，避免无界增长。
	 */
	private static void cleanupIfNeeded(long nowTick, int windowTicks) {
		if (lastCleanupTick != Long.MIN_VALUE && nowTick - lastCleanupTick < CLEANUP_INTERVAL_TICKS) {
			return;
		}
		lastCleanupTick = nowTick;

		long staleTicks = Math.max((long) windowTicks * STALE_WINDOW_MULTIPLIER, CLEANUP_INTERVAL_TICKS * 2L);
		cleanupMap(ACTOR_COUNTERS, nowTick, staleTicks);
		cleanupMap(ACTOR_GROUP_COUNTERS, nowTick, staleTicks);
	}

	/**
	 * 清理单个计数器映射中的过期项。
	 */
	private static <T> void cleanupMap(Map<T, WindowCounter> counters, long nowTick, long staleTicks) {
		Iterator<Map.Entry<T, WindowCounter>> iterator = counters.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<T, WindowCounter> entry = iterator.next();
			if (entry.getValue().isStale(nowTick, staleTicks)) {
				iterator.remove();
			}
		}
	}

	/**
	 * 命令来源类型。
	 */
	private enum SourceKind {
		PLAYER,
		ENTITY,
		SYSTEM
	}

	/**
	 * 来源个体键。
	 *
	 * @param sourceKind 来源类型
	 * @param principalId 主体标识
	 * @param permissionLevel 权限等级
	 */
	private record ActorKey(SourceKind sourceKind, String principalId, int permissionLevel) {}

	/**
	 * 来源+命令组键。
	 *
	 * @param actorKey 来源个体键
	 * @param group 命令分组
	 */
	private record ActorGroupKey(ActorKey actorKey, CommandGroup group) {}

	/**
	 * 单窗口计数器。
	 */
	private static final class WindowCounter {
		private long windowStartTick = Long.MIN_VALUE;
		private int usedInWindow;
		private long lastTouchedTick = Long.MIN_VALUE;

		/**
		 * 判断本窗口是否还能消费。
		 */
		private boolean canConsume(long nowTick, int windowTicks, int capacity, int cost) {
			int used = isInWindow(nowTick, windowTicks) ? usedInWindow : 0;
			return (long) used + cost <= capacity;
		}

		/**
		 * 扣减本窗口容量。
		 */
		private void consume(long nowTick, int windowTicks, int cost) {
			if (!isInWindow(nowTick, windowTicks)) {
				windowStartTick = nowTick;
				usedInWindow = 0;
			}
			usedInWindow += cost;
			lastTouchedTick = nowTick;
		}

		/**
		 * 判断计数器是否过期。
		 */
		private boolean isStale(long nowTick, long staleTicks) {
			if (lastTouchedTick == Long.MIN_VALUE) {
				return true;
			}
			return nowTick - lastTouchedTick > staleTicks;
		}

		/**
		 * 判断当前 tick 是否仍处于同一个窗口。
		 */
		private boolean isInWindow(long nowTick, int windowTicks) {
			return windowStartTick != Long.MIN_VALUE && nowTick - windowStartTick < windowTicks;
		}

		/**
		 * 当前窗口若已满，返回距离窗口自然重置还需等待的 tick 数。
		 */
		private long waitTicksUntilAvailable(long nowTick, int windowTicks) {
			if (!isInWindow(nowTick, windowTicks)) {
				return 0L;
			}
			long waitTicks = windowStartTick + windowTicks - nowTick;
			return Math.max(0L, waitTicks);
		}
	}
}
