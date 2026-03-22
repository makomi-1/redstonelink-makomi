package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 内部 delta 事件到运行态的同步投影器。
 * <p>
 * 投影规则固定为两段：
 * 1. 目标在线且区块已加载：直接调用目标统一 delta 入口；
 * 2. 目标不在线或区块未加载：转跨区块队列，等待后续投递。
 * </p>
 */
public final class InternalDispatchDeltaProjector {
	private static boolean registered;

	/**
	 * 投影分支结果。
	 */
	enum ProjectionRoute {
		DIRECT,
		QUEUE,
		SKIP
	}

	private InternalDispatchDeltaProjector() {
	}

	/**
	 * 注册默认投影监听器。
	 * <p>
	 * 该方法幂等，可安全重复调用。
	 * </p>
	 */
	public static synchronized void register() {
		if (registered) {
			return;
		}
		InternalDispatchDeltaEvents.register(InternalDispatchDeltaProjector::project);
		registered = true;
	}

	/**
	 * 将内部 delta 事件同步投影到目标实体或跨区块队列。
	 */
	private static void project(InternalDispatchDeltaEvents.DispatchDeltaEvent event) {
		if (
			event == null
				|| event.sourceLevel() == null
				|| event.deltaKind() == null
				|| event.deltaAction() == null
				|| event.sourceType() == null
				|| event.targetType() == null
				|| event.sourceSerial() <= 0L
				|| event.targetSerial() <= 0L
		) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(event.sourceType(), LinkNodeSemantics.Role.SOURCE)) {
			return;
		}
		if (!LinkNodeSemantics.isAllowedForRole(event.targetType(), LinkNodeSemantics.Role.TARGET)) {
			return;
		}

		LinkSavedData savedData = LinkSavedData.get(event.sourceLevel());
		LinkSavedData.LinkNode targetNode = savedData.findNode(event.targetType(), event.targetSerial()).orElse(null);
		ServerLevel targetLevel = targetNode == null ? null : event.sourceLevel().getServer().getLevel(targetNode.dimension());
		boolean loaded = targetNode != null && targetLevel != null && targetLevel.isLoaded(targetNode.pos());
		ProjectionRoute route = resolveProjectionRoute(targetNode != null, loaded);
		if (route == ProjectionRoute.SKIP) {
			return;
		}
		if (route == ProjectionRoute.DIRECT) {
			projectToLoadedTarget(savedData, targetLevel, targetNode, event);
			return;
		}
		queueCrossChunk(targetNode, event);
	}

	/**
	 * 解析投影分支（用于保持 loaded/crosschunk 路径判定一致）。
	 */
	static ProjectionRoute resolveProjectionRoute(boolean hasTargetNode, boolean targetLoaded) {
		if (!hasTargetNode) {
			return ProjectionRoute.SKIP;
		}
		return targetLoaded ? ProjectionRoute.DIRECT : ProjectionRoute.QUEUE;
	}

	/**
	 * 投影到已加载目标。
	 */
	private static void projectToLoadedTarget(
		LinkSavedData savedData,
		ServerLevel targetLevel,
		LinkSavedData.LinkNode targetNode,
		InternalDispatchDeltaEvents.DispatchDeltaEvent event
	) {
		LevelChunk targetChunk = targetLevel.getChunkSource().getChunkNow(targetNode.pos().getX() >> 4, targetNode.pos().getZ() >> 4);
		if (targetChunk == null) {
			// 已判定 loaded 后仍可能在同 tick 进入卸载窗口；此时转队列重试，避免阻塞主线程。
			queueCrossChunk(targetNode, event);
			return;
		}
		BlockEntity blockEntity = targetChunk.getBlockEntity(targetNode.pos(), LevelChunk.EntityCreationType.CHECK);
		if (!(blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
			// 目标位置仍是方块实体位但实例暂不可读时，延后重试，避免误删有效节点。
			if (blockEntity == null && targetChunk.getBlockState(targetNode.pos()).hasBlockEntity()) {
				queueCrossChunk(targetNode, event);
				return;
			}
			savedData.removeNode(event.targetType(), event.targetSerial());
			return;
		}
		targetBlockEntity.applyDispatchDelta(
			event.deltaKind(),
			event.deltaAction(),
			event.sourceType(),
			event.sourceSerial(),
			event.activationMode(),
			event.syncSignalStrength(),
			event.eventMeta()
		);
	}

	/**
	 * 投影到跨区块队列。
	 */
	private static void queueCrossChunk(
		LinkSavedData.LinkNode targetNode,
		InternalDispatchDeltaEvents.DispatchDeltaEvent event
	) {
		long enqueueTick = event.eventMeta().timeKey().tick();
		int enqueueSlot = event.eventMeta().timeKey().slot();

		if (
			event.deltaKind() == ActivatableTargetBlockEntity.DeltaKind.SOURCE_INVALIDATION
				|| event.deltaKind() == ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION
		) {
			CrossChunkDispatchService.queueTriggerSourceChunkUnloadInvalidationRemove(
				event.sourceLevel(),
				targetNode,
				event.sourceType(),
				event.sourceSerial(),
				enqueueTick,
				enqueueSlot
			);
			return;
		}

		if (event.deltaKind() == ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION) {
			CrossChunkDispatchService.queueTriggerSourceInvalidationRemove(
				event.sourceLevel(),
				targetNode,
				event.sourceType(),
				event.sourceSerial(),
				enqueueTick,
				enqueueSlot
			);
			return;
		}

		if (event.deltaKind() == ActivatableTargetBlockEntity.DeltaKind.ACTIVATION) {
			if (event.deltaAction() == ActivatableTargetBlockEntity.DeltaAction.REMOVE) {
				// activation 已转为事件语义，离线队列不再回放 REMOVE。
				return;
			}
			CrossChunkDispatchService.queueActivation(
				event.sourceLevel(),
				targetNode,
				event.sourceType(),
				event.sourceSerial(),
				event.activationMode(),
				enqueueTick,
				enqueueSlot
			);
			return;
		}

		if (event.deltaAction() == ActivatableTargetBlockEntity.DeltaAction.REMOVE) {
			CrossChunkDispatchService.queueSyncSignalRemove(
				event.sourceLevel(),
				targetNode,
				event.sourceType(),
				event.sourceSerial(),
				enqueueTick,
				enqueueSlot
			);
			return;
		}
		CrossChunkDispatchService.queueSyncSignal(
			event.sourceLevel(),
			targetNode,
			event.sourceType(),
			event.sourceSerial(),
			event.syncSignalStrength(),
			enqueueTick,
			enqueueSlot
		);
	}
}
