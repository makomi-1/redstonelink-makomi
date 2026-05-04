package com.makomi.block;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.block.entity.WirelessSyncTriggerSourceBlockEntity;
import com.makomi.data.LinkNodeRetireEvents;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 无线节点活塞搬运支撑。
 * <p>
 * 目的：
 * 1. 区分“被无线化活塞搬运”与“真实物理销毁”；
 * 2. 在移动落地后把原 serial 与节点运行态恢复到新位置；
 * 3. 避免搬运过程误入待退役链路。
 * </p>
 */
public final class WirelessPistonNodeMoveSupport {
	private static final Map<MinecraftServer, MoveState> STATE_BY_SERVER = new IdentityHashMap<>();

	private WirelessPistonNodeMoveSupport() {
	}

	/**
	 * 注册服务端 tick 钩子，用于在移动方块落地后补做节点恢复。
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(WirelessPistonNodeMoveSupport::onEndServerTick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			synchronized (STATE_BY_SERVER) {
				STATE_BY_SERVER.remove(server);
			}
		});
	}

	/**
	 * 判断指定位置当前是否处于“无线节点活塞搬运中”。
	 */
	public static boolean isMoveInProgress(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel serverLevel) || pos == null) {
			return false;
		}
		MoveState state = getState(serverLevel.getServer(), false);
		return state != null && state.movingSources.containsKey(PositionKey.of(serverLevel.dimension(), pos));
	}

	/**
	 * 记录一个即将被活塞搬运的无线节点。
	 * <p>
	 * 返回值表示是否真的登记了节点搬运上下文。
	 * </p>
	 */
	public static boolean captureMovingNode(Level level, BlockPos sourcePos, BlockPos targetPos) {
		if (!(level instanceof ServerLevel serverLevel) || sourcePos == null || targetPos == null) {
			return false;
		}
		BlockEntity blockEntity = level.getBlockEntity(sourcePos);
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return false;
		}
		CompoundTag snapshot = pairableNodeBlockEntity.saveWithoutMetadata(resolveProvider(blockEntity));
		MoveSnapshot moveSnapshot = new MoveSnapshot(
			PositionKey.of(serverLevel.dimension(), sourcePos),
			PositionKey.of(serverLevel.dimension(), targetPos),
			snapshot
		);
		MoveState state = getState(serverLevel.getServer(), true);
		state.movingSources.put(moveSnapshot.sourceKey(), moveSnapshot);
		state.pendingTargets.put(moveSnapshot.targetKey(), moveSnapshot);
		return true;
	}

	/**
	 * 移动完成后移除源位置标记。
	 * <p>
	 * 目标恢复仍保留，直到服务端 tick 确认新实体已落地。
	 * </p>
	 */
	public static void finishSourceMove(Level level, BlockPos sourcePos) {
		if (!(level instanceof ServerLevel serverLevel) || sourcePos == null) {
			return;
		}
		MoveState state = getState(serverLevel.getServer(), false);
		if (state == null) {
			return;
		}
		state.movingSources.remove(PositionKey.of(serverLevel.dimension(), sourcePos));
	}

	/**
	 * 当目标位置最终没有形成无线节点实体时，撤销本次恢复计划。
	 */
	public static void discardPendingTarget(Level level, BlockPos targetPos) {
		if (!(level instanceof ServerLevel serverLevel) || targetPos == null) {
			return;
		}
		MoveState state = getState(serverLevel.getServer(), false);
		if (state == null) {
			return;
		}
		state.pendingTargets.remove(PositionKey.of(serverLevel.dimension(), targetPos));
	}

	private static void onEndServerTick(MinecraftServer server) {
		MoveState state = getState(server, false);
		if (state == null || state.pendingTargets.isEmpty()) {
			return;
		}

		List<PositionKey> completedTargets = new ArrayList<>();
		for (Map.Entry<PositionKey, MoveSnapshot> entry : state.pendingTargets.entrySet()) {
			PositionKey targetKey = entry.getKey();
			MoveSnapshot snapshot = entry.getValue();
			ServerLevel level = server.getLevel(targetKey.dimension());
			if (level == null) {
				continue;
			}
			BlockEntity blockEntity = level.getBlockEntity(targetKey.pos());
			if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
				continue;
			}
			restoreMovedNode(level, pairableNodeBlockEntity, snapshot);
			completedTargets.add(targetKey);
		}

		for (PositionKey targetKey : completedTargets) {
			MoveSnapshot snapshot = state.pendingTargets.remove(targetKey);
			if (snapshot != null) {
				state.movingSources.remove(snapshot.sourceKey());
			}
		}
	}

	/**
	 * 把旧节点快照恢复到落地后的新实体。
	 */
	private static void restoreMovedNode(ServerLevel level, PairableNodeBlockEntity blockEntity, MoveSnapshot snapshot) {
		CompoundTag tag = snapshot.snapshot().copy();
		blockEntity.loadWithComponents(tag, resolveProvider(blockEntity));

		// 重新补一次显式 serial 刷新，确保在线节点表与生命周期 attach 一致。
		if (blockEntity.getSerial() > 0L) {
			blockEntity.setLinkData(blockEntity.getSerial());
			LinkNodeRetireEvents.cancelPendingRetire(level, blockEntity.getLinkNodeType(), blockEntity.getSerial());
		}

		// 恢复后按当前节点派生态重新校准方块外显。
		if (blockEntity instanceof ActivatableTargetBlockEntity activatableTargetBlockEntity) {
			activatableTargetBlockEntity.consumePendingLoadBlockStateSync();
		}
		if (blockEntity instanceof WirelessSyncTriggerSourceBlockEntity wirelessSyncTriggerSourceBlockEntity) {
			wirelessSyncTriggerSourceBlockEntity.clearPendingLoadInputStateResync();
		}
	}

	private static net.minecraft.core.HolderLookup.Provider resolveProvider(BlockEntity blockEntity) {
		return Objects.requireNonNull(blockEntity.getLevel(), "无线节点活塞搬运恢复时 level 不应为空").registryAccess();
	}

	private static MoveState getState(MinecraftServer server, boolean create) {
		if (server == null) {
			return null;
		}
		synchronized (STATE_BY_SERVER) {
			if (!create) {
				return STATE_BY_SERVER.get(server);
			}
			return STATE_BY_SERVER.computeIfAbsent(server, ignored -> new MoveState());
		}
	}

	private record PositionKey(ResourceKey<Level> dimension, BlockPos pos) {
		private static PositionKey of(ResourceKey<Level> dimension, BlockPos pos) {
			return new PositionKey(dimension, pos.immutable());
		}
	}

	private record MoveSnapshot(
		PositionKey sourceKey,
		PositionKey targetKey,
		CompoundTag snapshot
	) {
	}

	private static final class MoveState {
		private final Map<PositionKey, MoveSnapshot> movingSources = new java.util.HashMap<>();
		private final Map<PositionKey, MoveSnapshot> pendingTargets = new java.util.HashMap<>();
	}
}
