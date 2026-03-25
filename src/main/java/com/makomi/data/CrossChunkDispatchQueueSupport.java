package com.makomi.data;

import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * 跨区块派发排队入口 helper。
 * <p>
 * 仅负责队列写入前的参数归一化、TTL 计算与单条/批量 upsert 组装，
 * 不承担服务端 tick 驱动与重试循环职责。
 * </p>
 */
final class CrossChunkDispatchQueueSupport {
	private CrossChunkDispatchQueueSupport() {
	}

	/**
	 * 解析 sync pending 的 TTL。
	 */
	static long resolveSyncTtlTicks(int normalizedStrength) {
		if (RedstoneLinkConfig.crossChunk().syncSignalPersistent()) {
			return Long.MAX_VALUE;
		}
		return normalizedStrength > 0
			? RedstoneLinkConfig.crossChunk().syncSignalTtlTicks()
			: RedstoneLinkConfig.crossChunk().queueDefaultTtlTicks();
	}

	/**
	 * 根据 activation 模式解析队列策略。
	 */
	static CrossChunkDispatchService.ActivationQueuePolicy resolveActivationQueuePolicy(ActivationMode activationMode) {
		ActivationMode normalizedMode = activationMode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE;
		if (normalizedMode == ActivationMode.PULSE) {
			return new CrossChunkDispatchService.ActivationQueuePolicy(
				CrossChunkDispatchQueueSavedData.DispatchKind.PULSE_EVENT,
				RedstoneLinkConfig.crossChunk().activationPulseRelayEnabled(),
				RedstoneLinkConfig.crossChunk().activationPulsePersistentExperimental(),
				RedstoneLinkConfig.crossChunk().activationPulseTtlTicks()
			);
		}
		return new CrossChunkDispatchService.ActivationQueuePolicy(
			CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT,
			RedstoneLinkConfig.crossChunk().activationToggleRelayEnabled(),
			RedstoneLinkConfig.crossChunk().activationTogglePersistentExperimental(),
			RedstoneLinkConfig.crossChunk().activationToggleTtlTicks()
		);
	}

	/**
	 * 解析 activation pending 的最终 TTL。
	 */
	static long resolveActivationTtlTicks(CrossChunkDispatchService.ActivationQueuePolicy queuePolicy) {
		if (queuePolicy == null) {
			return 0L;
		}
		return queuePolicy.persistentExperimental() ? Long.MAX_VALUE : queuePolicy.ttlTicks();
	}

	/**
	 * 计算 pending 到期 tick。
	 */
	static long resolveExpireTick(long gameTime, long ttlTicks) {
		if (ttlTicks == Long.MAX_VALUE) {
			return Long.MAX_VALUE;
		}
		return gameTime + Math.max(1L, ttlTicks);
	}

	/**
	 * 构造与输入等长的 rejected 结果。
	 */
	static List<CrossChunkDispatchService.QueueResult> queueRejectedResults(List<LinkSavedData.LinkNode> targetNodes) {
		if (targetNodes == null || targetNodes.isEmpty()) {
			return List.of();
		}
		List<CrossChunkDispatchService.QueueResult> queueResults = new ArrayList<>(targetNodes.size());
		for (int index = 0; index < targetNodes.size(); index++) {
			queueResults.add(new CrossChunkDispatchService.QueueResult(false, false));
		}
		return List.copyOf(queueResults);
	}

	/**
	 * 记录 toggle/pulse 事件语义的离线 pending。
	 */
	static CrossChunkDispatchService.QueueResult queueToggleActivation(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		ActivationMode activationMode,
		long enqueueGameTick,
		int enqueueGameSlot,
		CrossChunkDispatchService.ActivationQueuePolicy queuePolicy
	) {
		if (
			sourceLevel == null
				|| targetNode == null
				|| sourceType == null
				|| sourceSerial <= 0L
				|| targetNode.serial() <= 0L
				|| queuePolicy == null
		) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetNode.type(), LinkNodeSemantics.Role.TARGET)) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		if (!queuePolicy.ttlRelayEnabled() && !queuePolicy.persistentExperimental()) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}

		long normalizedEnqueueTick = Math.max(0L, enqueueGameTick);
		int normalizedEnqueueSlot = Math.max(0, enqueueGameSlot);
		long expireTick = resolveExpireTick(normalizedEnqueueTick, resolveActivationTtlTicks(queuePolicy));
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			sourceType,
			sourceSerial,
			targetNode.type(),
			targetNode.serial(),
			queuePolicy.dispatchKind()
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pendingPreview =
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				key,
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				targetNode.dimension(),
				targetNode.pos(),
				activationMode == ActivationMode.PULSE ? ActivationMode.PULSE : ActivationMode.TOGGLE,
				0,
				normalizedEnqueueTick,
				normalizedEnqueueSlot,
				expireTick,
				1L
			);
		boolean queueEnabled = RedstoneLinkConfig.crossChunk().queueEnabled();
		boolean canForceLoad = CrossChunkDispatchService.shouldForceLoad(sourceLevel, pendingPreview);
		if (!queueEnabled && !canForceLoad) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}

		CrossChunkDispatchQueueSavedData queueData = CrossChunkDispatchQueueSavedData.get(sourceLevel);
		if (queueData.pendingEntry(key).isPresent()) {
			queueData.removePending(key);
			return new CrossChunkDispatchService.QueueResult(true, false);
		}
		CrossChunkDispatchQueueSavedData.UpsertResult upsertResult = queueData.upsertPending(
			key,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			targetNode.dimension(),
			targetNode.pos(),
			ActivationMode.TOGGLE,
			0,
			normalizedEnqueueTick,
			normalizedEnqueueSlot,
			expireTick
		);
		if (!upsertResult.accepted() || upsertResult.entry() == null) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		if (canForceLoad) {
			CrossChunkDispatchService.DispatchState state = CrossChunkDispatchService.getOrCreateState(sourceLevel.getServer());
			CrossChunkDispatchService.tryForceLoad(sourceLevel.getServer(), state, upsertResult.entry(), sourceLevel.getGameTime());
		}
		return new CrossChunkDispatchService.QueueResult(true, canForceLoad);
	}

	/**
	 * 记录单条 pending。
	 */
	static CrossChunkDispatchService.QueueResult queueDispatch(
		ServerLevel sourceLevel,
		LinkSavedData.LinkNode targetNode,
		LinkNodeType sourceType,
		long sourceSerial,
		CrossChunkDispatchQueueSavedData.DispatchKind dispatchKind,
		CrossChunkDispatchQueueSavedData.DispatchAction dispatchAction,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long ttlTicks
	) {
		if (
			sourceLevel == null
				|| targetNode == null
				|| sourceType == null
				|| dispatchKind == null
				|| dispatchAction == null
				|| activationMode == null
		) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		if (sourceSerial <= 0L || targetNode.serial() <= 0L || ttlTicks <= 0L) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		if (!LinkNodeSemantics.isAllowedForRole(targetNode.type(), LinkNodeSemantics.Role.TARGET)) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}

		long normalizedEnqueueTick = Math.max(0L, enqueueGameTick);
		int normalizedEnqueueSlot = Math.max(0, enqueueGameSlot);
		long expireTick = resolveExpireTick(normalizedEnqueueTick, ttlTicks);
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			sourceType,
			sourceSerial,
			targetNode.type(),
			targetNode.serial(),
			dispatchKind
		);
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pendingPreview =
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				key,
				dispatchAction,
				targetNode.dimension(),
				targetNode.pos(),
				activationMode,
				SignalStrengths.clamp(syncSignalStrength),
				normalizedEnqueueTick,
				normalizedEnqueueSlot,
				expireTick,
				1L
			);
		boolean queueEnabled = RedstoneLinkConfig.crossChunk().queueEnabled();
		boolean canForceLoad = CrossChunkDispatchService.shouldForceLoad(sourceLevel, pendingPreview);
		if (!queueEnabled && !canForceLoad) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}

		CrossChunkDispatchQueueSavedData queueData = CrossChunkDispatchQueueSavedData.get(sourceLevel);
		CrossChunkDispatchQueueSavedData.UpsertResult upsertResult = queueData.upsertPending(
			key,
			dispatchAction,
			targetNode.dimension(),
			targetNode.pos(),
			activationMode,
			SignalStrengths.clamp(syncSignalStrength),
			normalizedEnqueueTick,
			normalizedEnqueueSlot,
			expireTick
		);
		if (!upsertResult.accepted()) {
			return new CrossChunkDispatchService.QueueResult(false, false);
		}
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry pendingDispatch = upsertResult.entry();
		CrossChunkDispatchService.DispatchState state = CrossChunkDispatchService.getOrCreateState(sourceLevel.getServer());
		if (canForceLoad) {
			CrossChunkDispatchService.tryForceLoad(sourceLevel.getServer(), state, pendingDispatch, sourceLevel.getGameTime());
		}
		return new CrossChunkDispatchService.QueueResult(true, canForceLoad);
	}

	/**
	 * 批量记录 pending。
	 */
	static List<CrossChunkDispatchService.QueueResult> queueDispatchBatch(
		ServerLevel sourceLevel,
		List<LinkSavedData.LinkNode> targetNodes,
		LinkNodeType sourceType,
		long sourceSerial,
		CrossChunkDispatchQueueSavedData.DispatchKind dispatchKind,
		CrossChunkDispatchQueueSavedData.DispatchAction dispatchAction,
		ActivationMode activationMode,
		int syncSignalStrength,
		long enqueueGameTick,
		int enqueueGameSlot,
		long ttlTicks
	) {
		if (
			sourceLevel == null
				|| sourceType == null
				|| dispatchKind == null
				|| dispatchAction == null
				|| activationMode == null
				|| sourceSerial <= 0L
				|| ttlTicks <= 0L
				|| targetNodes == null
				|| targetNodes.isEmpty()
		) {
			return queueRejectedResults(targetNodes);
		}
		if (!LinkNodeSemantics.isAllowedForRole(sourceType, LinkNodeSemantics.Role.SOURCE)) {
			return queueRejectedResults(targetNodes);
		}

		long gameTime = Math.max(0L, enqueueGameTick);
		int gameSlot = Math.max(0, enqueueGameSlot);
		long expireTick = resolveExpireTick(gameTime, ttlTicks);
		boolean queueEnabled = RedstoneLinkConfig.crossChunk().queueEnabled();
		List<CrossChunkDispatchService.QueueResult> queueResults = new ArrayList<>(targetNodes.size());
		List<CrossChunkDispatchService.BatchCandidate> candidates = new ArrayList<>(targetNodes.size());
		List<CrossChunkDispatchQueueSavedData.PendingUpsertRequest> requests = new ArrayList<>();

		for (LinkSavedData.LinkNode targetNode : targetNodes) {
			queueResults.add(new CrossChunkDispatchService.QueueResult(false, false));
			if (targetNode == null || targetNode.serial() <= 0L) {
				candidates.add(new CrossChunkDispatchService.BatchCandidate(-1, false));
				continue;
			}
			if (!LinkNodeSemantics.isAllowedForRole(targetNode.type(), LinkNodeSemantics.Role.TARGET)) {
				candidates.add(new CrossChunkDispatchService.BatchCandidate(-1, false));
				continue;
			}
			CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
				sourceType,
				sourceSerial,
				targetNode.type(),
				targetNode.serial(),
				dispatchKind
			);
			CrossChunkDispatchQueueSavedData.PendingDispatchEntry pendingPreview =
				new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
					key,
					dispatchAction,
					targetNode.dimension(),
					targetNode.pos(),
					activationMode,
					SignalStrengths.clamp(syncSignalStrength),
					gameTime,
					gameSlot,
					expireTick,
					1L
				);
			boolean canForceLoad = CrossChunkDispatchService.shouldForceLoad(sourceLevel, pendingPreview);
			if (!queueEnabled && !canForceLoad) {
				candidates.add(new CrossChunkDispatchService.BatchCandidate(-1, false));
				continue;
			}
			requests.add(
				new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
					key,
					dispatchAction,
					targetNode.dimension(),
					targetNode.pos(),
					activationMode,
					SignalStrengths.clamp(syncSignalStrength),
					gameTime,
					gameSlot,
					expireTick
				)
			);
			candidates.add(new CrossChunkDispatchService.BatchCandidate(requests.size() - 1, canForceLoad));
		}

		if (requests.isEmpty()) {
			return List.copyOf(queueResults);
		}

		CrossChunkDispatchQueueSavedData queueData = CrossChunkDispatchQueueSavedData.get(sourceLevel);
		List<CrossChunkDispatchQueueSavedData.UpsertResult> upsertResults = queueData.upsertPendingBatch(requests);
		CrossChunkDispatchService.DispatchState state = CrossChunkDispatchService.getOrCreateState(sourceLevel.getServer());
		for (int index = 0; index < candidates.size(); index++) {
			CrossChunkDispatchService.BatchCandidate candidate = candidates.get(index);
			if (candidate.requestIndex() < 0 || candidate.requestIndex() >= upsertResults.size()) {
				continue;
			}
			CrossChunkDispatchQueueSavedData.UpsertResult upsertResult = upsertResults.get(candidate.requestIndex());
			if (!upsertResult.accepted() || upsertResult.entry() == null) {
				continue;
			}
			if (candidate.forceLoadPlanned()) {
				CrossChunkDispatchService.tryForceLoad(sourceLevel.getServer(), state, upsertResult.entry(), gameTime);
			}
			queueResults.set(index, new CrossChunkDispatchService.QueueResult(true, candidate.forceLoadPlanned()));
		}
		return List.copyOf(queueResults);
	}
}
