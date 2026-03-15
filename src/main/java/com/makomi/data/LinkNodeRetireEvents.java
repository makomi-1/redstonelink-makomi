package com.makomi.data;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.block.entity.TriggerSourceBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 节点退役事件注册器。
 * <p>
 * 负责将“物品销毁退役”“创造破坏退役”“待确认退役补偿”三条路径统一收口到
 * {@link LinkSavedData#retireNode(LinkNodeType, long)}。
 * </p>
 */
public final class LinkNodeRetireEvents {
	/**
	 * 节点下线后进入待确认队列的宽限时长（tick）。
	 */
	private static final int PENDING_RETIRE_GRACE_TICKS = 40;

	/**
	 * 在节点移除点附近扫描掉落物的半径。
	 */
	private static final double DROP_MATCH_SCAN_RADIUS = 2.5D;

	/**
	 * 无到期任务时使用的占位 tick。
	 */
	private static final long NO_DUE_TICK = Long.MAX_VALUE;

	/**
	 * 物品自然消失的默认年龄阈值（tick）。
	 */
	private static final int ITEM_NATURAL_DESPAWN_AGE = 6000;

	/**
	 * 按服务器实例隔离的待退役状态。
	 */
	private static final Map<MinecraftServer, PendingRetireState> PENDING_RETIRES = new IdentityHashMap<>();

	private LinkNodeRetireEvents() {
	}

	/**
	 * 注册节点退役相关事件。
	 * <p>
	 * 当前实现挂接以下事件：
	 * </p>
	 * <ul>
	 * <li>{@code ENTITY_UNLOAD}：物品实体以 KILLED/DISCARDED 卸载时立即退役；</li>
	 * <li>{@code ENTITY_LOAD}：发现匹配物品掉落实体时取消待退役；</li>
	 * <li>{@code PlayerBlockBreakEvents.AFTER}：创造模式破坏已放置节点时立即退役；</li>
	 * <li>{@code END_SERVER_TICK}：处理到期待退役任务；</li>
	 * <li>{@code SERVER_STOPPED}：清理该服务器的待退役状态。</li>
	 * </ul>
	 */
	public static void register() {
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (!(entity instanceof ItemEntity itemEntity)) {
				return;
			}
			MinecraftServer server = level.getServer();
			UUID entityId = itemEntity.getUUID();
			Entity.RemovalReason reason = entity.getRemovalReason();
			if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
				clearEntityKey(server, entityId);
				clearDamageDiscardMark(server, entityId);
				return;
			}

			PendingKey key = resolveUnloadKey(server, itemEntity);
			boolean discardedByDamage = consumeDamageDiscardMark(server, entityId);
			clearEntityKey(server, entityId);
 			if (key == null) {
				return;
			}
			if (reason == Entity.RemovalReason.DISCARDED
				&& !discardedByDamage
				&& itemEntity.getAge() < ITEM_NATURAL_DESPAWN_AGE) {
				// 低年龄 DISCARDED 且非伤害销毁，按“被拾取/主动丢弃”处理，不做退役。
				return;
			}
			LinkSavedData savedData = LinkSavedData.get(level);
			// 若该序号仍存在已放置节点，则不应被“物品实体销毁”路径退役。
			if (savedData.findNode(key.nodeType(), key.serial()).isPresent()) {
				return;
			}
			LinkRetireCoordinator.retireAndSyncWhitelist(level, key.nodeType(), key.serial());
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!(entity instanceof ItemEntity itemEntity)) {
				return;
			}

			PendingKey key = pendingKeyFromStack(itemEntity.getItem(), true);
			if (key == null) {
				clearEntityKey(level.getServer(), itemEntity.getUUID());
				return;
			}
			rememberEntityKey(level.getServer(), itemEntity.getUUID(), key);
			cancelPending(level.getServer(), key);
		});

		// 创造模式下破坏已放置节点时，直接退役并取消可能存在的待退役记录。
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerLevel serverLevel)) {
				return;
			}
			if (!player.getAbilities().instabuild) {
				return;
			}
			if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
				return;
			}

			long serial = pairableNodeBlockEntity.getSerial();
			if (serial <= 0L) {
				return;
			}
			LinkNodeType nodeType = resolveNodeType(pairableNodeBlockEntity);
			PendingKey key = new PendingKey(nodeType, serial);
			cancelPending(serverLevel.getServer(), key);
			LinkRetireCoordinator.retireAndSyncWhitelist(serverLevel, nodeType, serial);
		});

		ServerTickEvents.END_SERVER_TICK.register(LinkNodeRetireEvents::processPendingRetires);
		ServerLifecycleEvents.SERVER_STOPPED.register(PENDING_RETIRES::remove);
	}

	/**
	 * 标记“该物品实体即将因伤害导致 DISCARDED”，用于与“被拾取触发 DISCARDED”区分。
	 *
	 * @param itemEntity 物品实体
	 */
	public static void markDamageDiscard(ItemEntity itemEntity) {
		if (!(itemEntity.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		PendingKey key = pendingKeyFromStack(itemEntity.getItem(), true);
		if (key == null) {
			return;
		}

		MinecraftServer server = serverLevel.getServer();
		UUID entityId = itemEntity.getUUID();
		rememberEntityKey(server, entityId, key);
		markDamageDiscard(server, entityId);
	}

	/**
	 * 在节点下线时登记待确认退役。
	 * <p>
	 * 若宽限期内检测到同序号掉落物/玩家背包内物品，则取消退役；否则到期后执行退役。
	 * </p>
	 *
	 * @param level 服务端维度
	 * @param nodeType 节点类型
	 * @param serial 节点序号
	 * @param pos 节点移除位置
	 */
	public static void enqueuePendingRetire(ServerLevel level, LinkNodeType nodeType, long serial, BlockPos pos) {
		if (serial <= 0L) {
			return;
		}

		// 仅对“已分配且未退役”的有效序号入桶，避免无效数据膨胀待处理队列。
		LinkSavedData savedData = LinkSavedData.get(level);
		if (!savedData.isSerialActive(nodeType, serial)) {
			return;
		}

		PendingKey key = new PendingKey(nodeType, serial);
		MinecraftServer server = level.getServer();

		// 同一节点已在待退役队列中时不重复入桶。
		if (isPending(server, key)) {
			return;
		}

		// 如果移除点附近已经出现匹配掉落物，说明走的是正常掉落链路，不需要挂起。
		if (hasMatchingDropEntity(level, key, pos)) {
			return;
		}

		long expireTick = (long) server.getTickCount() + PENDING_RETIRE_GRACE_TICKS;
		upsertPending(server, new PendingEntry(key, level.dimension(), pos.immutable(), expireTick));
	}

	/**
	 * 判断指定节点是否已在待退役队列中。
	 */
	private static boolean isPending(MinecraftServer server, PendingKey key) {
		PendingRetireState state = PENDING_RETIRES.get(server);
		return state != null && state.pendingByKey.containsKey(key);
	}

	/**
	 * 处理当前 tick 到期的待退役任务。
	 */
	private static void processPendingRetires(MinecraftServer server) {
		PendingRetireState state = PENDING_RETIRES.get(server);
		if (state == null || state.pendingByKey.isEmpty()) {
			return;
		}

		long now = server.getTickCount();
		if (now < state.nextDueTick) {
			return;
		}

		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(overworld);

		while (state.nextDueTick != NO_DUE_TICK && state.nextDueTick <= now) {
			long dueTick = state.nextDueTick;
			List<PendingKey> dueKeys = state.bucketByExpireTick.remove(dueTick);
			if (dueKeys != null) {
				for (PendingKey key : dueKeys) {
					PendingEntry entry = state.pendingByKey.get(key);
					if (entry == null || entry.expireTick() != dueTick) {
						continue;
					}
					state.pendingByKey.remove(key);
					if (shouldSkipPendingRetire(server, savedData, entry)) {
						continue;
					}
					LinkRetireCoordinator.retireAndSyncWhitelist(overworld, key.nodeType(), key.serial());
				}
			}
			recalculateNextDueTick(state);
		}
	}

	/**
	 * 判断当前待退役是否应跳过。
	 * <p>
	 * 跳过条件：序号已不存在、附近有匹配掉落物、或在线玩家背包中存在匹配物品。
	 * </p>
	 */
	private static boolean shouldSkipPendingRetire(MinecraftServer server, LinkSavedData savedData, PendingEntry entry) {
		PendingKey key = entry.key();
		if (savedData.findNode(key.nodeType(), key.serial()).isPresent()) {
			return true;
		}

		ServerLevel level = server.getLevel(entry.dimension());
		if (level != null && hasMatchingDropEntity(level, key, entry.pos())) {
			return true;
		}

		return hasMatchingStackInOnlinePlayers(server, key);
	}

	/**
	 * 检查在线玩家背包中是否存在与待退役匹配的物品栈。
	 */
	private static boolean hasMatchingStackInOnlinePlayers(MinecraftServer server, PendingKey key) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// 创造模式物品可由系统无限供给，不应作为“保留该序号”的判定依据。
			if (player.getAbilities().instabuild) {
				continue;
			}
			for (ItemStack stack : player.getInventory().items) {
				if (isMatchingStack(stack, key, false)) {
					return true;
				}
			}
			for (ItemStack stack : player.getInventory().offhand) {
				if (isMatchingStack(stack, key, false)) {
					return true;
				}
			}
			for (ItemStack stack : player.getInventory().armor) {
				if (isMatchingStack(stack, key, false)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 扫描指定位置附近是否存在匹配的掉落物实体。
	 */
	private static boolean hasMatchingDropEntity(ServerLevel level, PendingKey key, BlockPos aroundPos) {
		AABB scanBox = new AABB(aroundPos).inflate(DROP_MATCH_SCAN_RADIUS);
		return !level.getEntitiesOfClass(
			ItemEntity.class,
			scanBox,
			itemEntity -> isMatchingStack(itemEntity.getItem(), key, true)
		).isEmpty();
	}

	/**
	 * 从物品栈解析待退役键。
	 */
	private static PendingKey pendingKeyFromStack(ItemStack stack, boolean requireDestroyCandidate) {
		if (stack.isEmpty()) {
			return null;
		}
		if (requireDestroyCandidate && !LinkItemData.isDestroyRetireCandidate(stack)) {
			return null;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return null;
		}
		LinkNodeType nodeType = LinkItemData.getNodeType(stack).orElse(null);
		if (nodeType == null) {
			return null;
		}
		return new PendingKey(nodeType, serial);
	}

	/**
	 * 在实体卸载时解析待退役键：优先取当前实体栈，失败时回退到 UUID 缓存。
	 */
	private static PendingKey resolveUnloadKey(MinecraftServer server, ItemEntity itemEntity) {
		PendingKey key = pendingKeyFromStack(itemEntity.getItem(), true);
		if (key != null) {
			return key;
		}
		return getRememberedEntityKey(server, itemEntity.getUUID());
	}

	/**
	 * 判断物品栈是否与待退役键匹配。
	 */
	private static boolean isMatchingStack(ItemStack stack, PendingKey key, boolean requireDestroyCandidate) {
		PendingKey resolved = pendingKeyFromStack(stack, requireDestroyCandidate);
		return resolved != null && resolved.equals(key);
	}

	/**
	 * 新增或更新待退役记录，并将其放入到期桶。
	 */
	private static void upsertPending(MinecraftServer server, PendingEntry newEntry) {
		PendingRetireState state = PENDING_RETIRES.computeIfAbsent(server, ignored -> new PendingRetireState());

		PendingEntry previous = state.pendingByKey.put(newEntry.key(), newEntry);
		if (previous != null) {
			removeFromBucket(state, previous.expireTick(), newEntry.key());
		}

		state.bucketByExpireTick.computeIfAbsent(newEntry.expireTick(), ignored -> new ArrayList<>()).add(newEntry.key());
		if (newEntry.expireTick() < state.nextDueTick) {
			state.nextDueTick = newEntry.expireTick();
		}
	}

	/**
	 * 取消指定待退役记录。
	 */
	private static void cancelPending(MinecraftServer server, PendingKey key) {
		PendingRetireState state = PENDING_RETIRES.get(server);
		if (state == null) {
			return;
		}
		PendingEntry removed = state.pendingByKey.remove(key);
		if (removed == null) {
			return;
		}

		removeFromBucket(state, removed.expireTick(), key);
		if (state.pendingByKey.isEmpty()) {
			state.nextDueTick = NO_DUE_TICK;
			return;
		}
		if (removed.expireTick() == state.nextDueTick && !state.bucketByExpireTick.containsKey(state.nextDueTick)) {
			recalculateNextDueTick(state);
		}
	}

	/**
	 * 从到期桶中移除一个待退役键。
	 */
	private static void removeFromBucket(PendingRetireState state, long expireTick, PendingKey key) {
		List<PendingKey> bucket = state.bucketByExpireTick.get(expireTick);
		if (bucket == null) {
			return;
		}
		bucket.removeIf(existing -> existing.equals(key));
		if (bucket.isEmpty()) {
			state.bucketByExpireTick.remove(expireTick);
		}
	}

	/**
	 * 重新计算下一次到期 tick。
	 */
	private static void recalculateNextDueTick(PendingRetireState state) {
		long next = NO_DUE_TICK;
		for (Long dueTick : state.bucketByExpireTick.keySet()) {
			if (dueTick < next) {
				next = dueTick;
			}
		}
		state.nextDueTick = next;
	}

	/**
	 * 记录实体 UUID 与待退役键的对应关系，用于卸载阶段兜底解析。
	 */
	private static void rememberEntityKey(MinecraftServer server, UUID entityId, PendingKey key) {
		getOrCreateState(server).rememberedEntityKeys.put(entityId, key);
	}

	/**
	 * 读取实体 UUID 对应的待退役键，不做移除。
	 */
	private static PendingKey getRememberedEntityKey(MinecraftServer server, UUID entityId) {
		PendingRetireState state = PENDING_RETIRES.get(server);
		if (state == null) {
			return null;
		}
		return state.rememberedEntityKeys.get(entityId);
	}

	/**
	 * 清理实体 UUID 对应的待退役键缓存。
	 */
	private static void clearEntityKey(MinecraftServer server, UUID entityId) {
		PendingRetireState state = PENDING_RETIRES.get(server);
		if (state == null) {
			return;
		}
		state.rememberedEntityKeys.remove(entityId);
	}

	/**
	 * 记录“该实体由伤害触发 DISCARDED”标记。
	 */
	private static void markDamageDiscard(MinecraftServer server, UUID entityId) {
		getOrCreateState(server).damageDiscardedEntityIds.add(entityId);
	}

	/**
	 * 读取并移除“伤害触发 DISCARDED”标记。
	 */
	private static boolean consumeDamageDiscardMark(MinecraftServer server, UUID entityId) {
		PendingRetireState state = PENDING_RETIRES.get(server);
		if (state == null) {
			return false;
		}
		return state.damageDiscardedEntityIds.remove(entityId);
	}

	/**
	 * 清理“伤害触发 DISCARDED”标记。
	 */
	private static void clearDamageDiscardMark(MinecraftServer server, UUID entityId) {
		PendingRetireState state = PENDING_RETIRES.get(server);
		if (state == null) {
			return;
		}
		state.damageDiscardedEntityIds.remove(entityId);
	}

	/**
	 * 获取或创建该服务器的待退役状态。
	 */
	private static PendingRetireState getOrCreateState(MinecraftServer server) {
		return PENDING_RETIRES.computeIfAbsent(server, ignored -> new PendingRetireState());
	}

	/**
	 * 根据方块实体类型推断节点类型。
	 */
	private static LinkNodeType resolveNodeType(PairableNodeBlockEntity blockEntity) {
		if (blockEntity instanceof TriggerSourceBlockEntity) {
			return LinkNodeType.BUTTON;
		}
		return LinkNodeType.CORE;
	}

	/**
	 * 待退役键：节点类型 + 节点序号。
	 */
	private record PendingKey(LinkNodeType nodeType, long serial) {}

	/**
	 * 待退役实体：保存定位与过期信息。
	 */
	private record PendingEntry(
		PendingKey key,
		ResourceKey<Level> dimension,
		BlockPos pos,
		long expireTick
	) {}

	/**
	 * 单服务器的待退役内存状态。
	 */
	private static final class PendingRetireState {
		private final Map<PendingKey, PendingEntry> pendingByKey = new HashMap<>();
		private final Map<Long, List<PendingKey>> bucketByExpireTick = new HashMap<>();
		private final Map<UUID, PendingKey> rememberedEntityKeys = new HashMap<>();
		private final Set<UUID> damageDiscardedEntityIds = new HashSet<>();
		private long nextDueTick = NO_DUE_TICK;
	}
}
