package com.makomi.api.v1.internal;

import com.makomi.api.v1.adapter.AdapterRegistryApi;
import com.makomi.api.v1.adapter.ExternalTargetAdapter;
import com.makomi.api.v1.adapter.ExternalTriggerAdapter;
import com.makomi.api.v1.event.LinkEvents;
import com.makomi.api.v1.model.ActorContext;
import com.makomi.api.v1.model.ApiActivationMode;
import com.makomi.api.v1.model.ApiDecision;
import com.makomi.api.v1.model.ApiNodeType;
import com.makomi.api.v1.model.AuditSnapshot;
import com.makomi.api.v1.model.LinkMutationResult;
import com.makomi.api.v1.model.NodeRetireResult;
import com.makomi.api.v1.model.NodeSnapshot;
import com.makomi.api.v1.model.TriggerRequest;
import com.makomi.api.v1.model.TriggerResult;
import com.makomi.api.v1.service.LinkGraphApi;
import com.makomi.api.v1.service.QueryApi;
import com.makomi.api.v1.service.TriggerApi;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * RedstoneLink v1 API 内部实现。
 */
public final class RedstoneLinkApiImpl implements LinkGraphApi, TriggerApi, QueryApi, AdapterRegistryApi {
	private static final String REASON_SOURCE_SERIAL_UNALLOCATED = "message.redstonelink.source_serial_unallocated";
	private static final String REASON_SOURCE_SERIAL_RETIRED = "message.redstonelink.source_serial_retired";
	private static final String REASON_TARGET_SERIAL_UNALLOCATED = "message.redstonelink.target_serial_unallocated";
	private static final String REASON_TARGET_SERIAL_RETIRED = "message.redstonelink.target_serial_retired";
	private static final String REASON_TOO_MANY_TARGETS = "message.redstonelink.too_many_targets";
	private static final String REASON_OFFLINE_TARGETS_BLOCKED = "message.redstonelink.offline_targets_blocked";
	private static final String REASON_TRIGGER_CANCELLED = "message.redstonelink.trigger_cancelled";
	private static final String REASON_LINK_CHANGE_CANCELLED = "message.redstonelink.link_change_cancelled";
	private static final String REASON_SOURCE_NOT_FOUND = "message.redstonelink.source_not_found";

	private final Map<ResourceLocation, ExternalTargetAdapter> targetAdapters = new LinkedHashMap<>();
	private final Map<ResourceLocation, ExternalTriggerAdapter> triggerAdapters = new LinkedHashMap<>();

	@Override
	public Optional<NodeSnapshot> findNode(ServerLevel level, ApiNodeType nodeType, long serial) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(nodeType, "nodeType");
		if (serial <= 0L) {
			return Optional.empty();
		}
		LinkNodeType internalType = nodeType.toInternal();
		return LinkSavedData
			.get(level)
			.findNode(internalType, serial)
			.map(node -> new NodeSnapshot(serial, nodeType, node.dimension().location(), node.pos()));
	}

	@Override
	public Set<Long> getLinkedTargets(ServerLevel level, ApiNodeType sourceType, long sourceSerial) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(sourceType, "sourceType");
		if (sourceSerial <= 0L) {
			return Set.of();
		}
		return readLinkedTargets(LinkSavedData.get(level), sourceType.toInternal(), sourceSerial);
	}

	@Override
	public LinkMutationResult setLinks(
		ServerLevel level,
		ApiNodeType sourceType,
		long sourceSerial,
		Set<Long> targets,
		ActorContext actorContext
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(sourceType, "sourceType");
		Objects.requireNonNull(actorContext, "actorContext");

		LinkSavedData savedData = LinkSavedData.get(level);
		LinkNodeType sourceNodeType = sourceType.toInternal();
		String sourceReason = validateSourceSerial(savedData, sourceNodeType, sourceSerial);
		if (!sourceReason.isEmpty()) {
			return LinkMutationResult.failed(sourceReason);
		}

		Set<Long> normalizedTargets = normalizeTargets(targets);
		if (normalizedTargets.size() > RedstoneLinkConfig.maxTargetsPerSetLinks()) {
			return LinkMutationResult.failed(REASON_TOO_MANY_TARGETS);
		}

		ApiDecision decision = LinkEvents.BEFORE_SET_LINKS
			.invoker()
			.beforeSetLinks(level, sourceType, sourceSerial, normalizedTargets, actorContext);
		if (decision != null && !decision.allowed()) {
			return LinkMutationResult.cancelled(decisionReasonKey(decision, REASON_LINK_CHANGE_CANCELLED));
		}

		LinkNodeType targetNodeType = sourceType.targetTypeForLink().toInternal();
		boolean allowOfflineBinding = RedstoneLinkConfig.allowOfflineTargetBinding();
		Set<Long> validTargets = new LinkedHashSet<>();
		Set<Long> rejectedTargets = new LinkedHashSet<>();
		for (long targetSerial : normalizedTargets) {
			if (!isSerialAllocatedAndActive(savedData, targetNodeType, targetSerial)) {
				rejectedTargets.add(targetSerial);
				continue;
			}
			if (!allowOfflineBinding && !isTargetReachableNow(level, savedData, targetNodeType, targetSerial)) {
				rejectedTargets.add(targetSerial);
				continue;
			}
			validTargets.add(targetSerial);
		}

		Set<Long> currentTargets = new LinkedHashSet<>(readLinkedTargets(savedData, sourceNodeType, sourceSerial));
		Set<Long> removeTargets = new LinkedHashSet<>(currentTargets);
		removeTargets.removeAll(validTargets);
		Set<Long> addTargets = new LinkedHashSet<>(validTargets);
		addTargets.removeAll(currentTargets);

		int removedCount = 0;
		for (long targetSerial : removeTargets) {
			boolean linkedNow = toggleInternal(savedData, sourceNodeType, sourceSerial, targetSerial);
			if (!linkedNow) {
				removedCount++;
			} else {
				// 容错：发生竞态时，立即回滚到“移除后状态”。
				toggleInternal(savedData, sourceNodeType, sourceSerial, targetSerial);
			}
		}

		int addedCount = 0;
		for (long targetSerial : addTargets) {
			boolean linkedNow = toggleInternal(savedData, sourceNodeType, sourceSerial, targetSerial);
			if (linkedNow) {
				addedCount++;
			} else {
				// 容错：发生竞态时补一次添加。
				if (toggleInternal(savedData, sourceNodeType, sourceSerial, targetSerial)) {
					addedCount++;
				}
			}
		}

		Set<Long> appliedTargets = readLinkedTargets(savedData, sourceNodeType, sourceSerial);
		updateNodeLastTargetSerial(level, sourceNodeType, sourceSerial, findLastTarget(appliedTargets));
		LinkMutationResult result = LinkMutationResult.success(addedCount, removedCount, appliedTargets, rejectedTargets);

		LinkEvents.AFTER_SET_LINKS
			.invoker()
			.afterSetLinks(level, sourceType, sourceSerial, normalizedTargets, actorContext, result);
		return result;
	}

	@Override
	public LinkMutationResult toggleLink(
		ServerLevel level,
		ApiNodeType sourceType,
		long sourceSerial,
		long targetSerial,
		ActorContext actorContext
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(sourceType, "sourceType");
		Objects.requireNonNull(actorContext, "actorContext");

		LinkSavedData savedData = LinkSavedData.get(level);
		LinkNodeType sourceNodeType = sourceType.toInternal();
		String sourceReason = validateSourceSerial(savedData, sourceNodeType, sourceSerial);
		if (!sourceReason.isEmpty()) {
			return LinkMutationResult.failed(sourceReason);
		}

		LinkNodeType targetNodeType = sourceType.targetTypeForLink().toInternal();
		String targetReason = validateTargetSerial(savedData, targetNodeType, targetSerial);
		if (!targetReason.isEmpty()) {
			return LinkMutationResult.failed(targetReason);
		}
		if (!RedstoneLinkConfig.allowOfflineTargetBinding() && !isTargetReachableNow(level, savedData, targetNodeType, targetSerial)) {
			return LinkMutationResult.failed(REASON_OFFLINE_TARGETS_BLOCKED);
		}

		Set<Long> currentTargets = new LinkedHashSet<>(readLinkedTargets(savedData, sourceNodeType, sourceSerial));
		Set<Long> desiredTargets = new LinkedHashSet<>(currentTargets);
		boolean currentlyLinked = !desiredTargets.add(targetSerial);
		if (currentlyLinked) {
			desiredTargets.remove(targetSerial);
		}

		ApiDecision decision = LinkEvents.BEFORE_SET_LINKS
			.invoker()
			.beforeSetLinks(level, sourceType, sourceSerial, Set.copyOf(desiredTargets), actorContext);
		if (decision != null && !decision.allowed()) {
			return LinkMutationResult.cancelled(decisionReasonKey(decision, REASON_LINK_CHANGE_CANCELLED));
		}

		boolean linkedNow = toggleInternal(savedData, sourceNodeType, sourceSerial, targetSerial);
		int addedCount = 0;
		int removedCount = 0;
		if (currentlyLinked) {
			if (!linkedNow) {
				removedCount = 1;
			} else if (!toggleInternal(savedData, sourceNodeType, sourceSerial, targetSerial)) {
				removedCount = 1;
			}
		} else {
			if (linkedNow) {
				addedCount = 1;
			} else if (toggleInternal(savedData, sourceNodeType, sourceSerial, targetSerial)) {
				addedCount = 1;
			}
		}

		Set<Long> appliedTargets = readLinkedTargets(savedData, sourceNodeType, sourceSerial);
		updateNodeLastTargetSerial(level, sourceNodeType, sourceSerial, findLastTarget(appliedTargets));
		LinkMutationResult result = LinkMutationResult.success(addedCount, removedCount, appliedTargets, Set.of());

		LinkEvents.AFTER_SET_LINKS
			.invoker()
			.afterSetLinks(level, sourceType, sourceSerial, Set.copyOf(desiredTargets), actorContext, result);
		return result;
	}

	@Override
	public LinkMutationResult clearLinks(
		ServerLevel level,
		ApiNodeType sourceType,
		long sourceSerial,
		ActorContext actorContext
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(sourceType, "sourceType");
		Objects.requireNonNull(actorContext, "actorContext");

		LinkSavedData savedData = LinkSavedData.get(level);
		LinkNodeType sourceNodeType = sourceType.toInternal();
		String sourceReason = validateSourceSerial(savedData, sourceNodeType, sourceSerial);
		if (!sourceReason.isEmpty()) {
			return LinkMutationResult.failed(sourceReason);
		}

		ApiDecision decision = LinkEvents.BEFORE_SET_LINKS
			.invoker()
			.beforeSetLinks(level, sourceType, sourceSerial, Set.of(), actorContext);
		if (decision != null && !decision.allowed()) {
			return LinkMutationResult.cancelled(decisionReasonKey(decision, REASON_LINK_CHANGE_CANCELLED));
		}

		int removedCount = savedData.clearLinksForNode(sourceNodeType, sourceSerial);
		updateNodeLastTargetSerial(level, sourceNodeType, sourceSerial, 0L);
		LinkMutationResult result = LinkMutationResult.success(0, removedCount, Set.of(), Set.of());

		LinkEvents.AFTER_SET_LINKS.invoker().afterSetLinks(level, sourceType, sourceSerial, Set.of(), actorContext, result);
		return result;
	}

	@Override
	public NodeRetireResult retireNode(ServerLevel level, ApiNodeType nodeType, long serial, ActorContext actorContext) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(nodeType, "nodeType");
		Objects.requireNonNull(actorContext, "actorContext");
		if (serial <= 0L) {
			return new NodeRetireResult(false, 0, false);
		}

		LinkSavedData.RetireResult raw = LinkSavedData.get(level).retireNode(nodeType.toInternal(), serial);
		NodeRetireResult result = new NodeRetireResult(raw.nodeRemoved(), raw.linksRemoved(), raw.retiredMarked());
		if (result.nodeRemoved() || result.linksRemoved() > 0 || result.retiredMarked()) {
			LinkEvents.NODE_RETIRED.invoker().onNodeRetired(level, nodeType, serial, actorContext, result);
		}
		return result;
	}

	@Override
	public TriggerResult emit(TriggerRequest request) {
		Objects.requireNonNull(request, "request");
		LinkSavedData savedData = LinkSavedData.get(request.level());
		LinkNodeType sourceNodeType = request.sourceType().toInternal();
		String sourceReason = validateSourceSerial(savedData, sourceNodeType, request.sourceSerial());
		if (!sourceReason.isEmpty()) {
			return TriggerResult.failed(sourceReason);
		}

		Set<Long> targetSerials = readLinkedTargets(savedData, sourceNodeType, request.sourceSerial());
		int totalTargets = targetSerials.size();
		if (totalTargets == 0) {
			return TriggerResult.success(0, 0, 0, 0, 0);
		}

		ApiDecision decision = LinkEvents.BEFORE_TRIGGER.invoker().beforeTrigger(request, targetSerials);
		if (decision != null && !decision.allowed()) {
			return TriggerResult.cancelled(decisionReasonKey(decision, REASON_TRIGGER_CANCELLED), totalTargets);
		}

		LinkNodeType targetNodeType = request.sourceType().targetTypeForLink().toInternal();
		int triggeredCount = 0;
		int skippedMissingCount = 0;
		int skippedOfflineCount = 0;
		int skippedUnsupportedCount = 0;

		for (long targetSerial : targetSerials) {
			LinkSavedData.LinkNode node = savedData.findNode(targetNodeType, targetSerial).orElse(null);
			if (node == null) {
				skippedMissingCount++;
				continue;
			}

			ServerLevel targetLevel = request.level().getServer().getLevel(node.dimension());
			if (targetLevel == null || !targetLevel.isLoaded(node.pos())) {
				skippedOfflineCount++;
				continue;
			}

			BlockPos targetPos = node.pos();
			BlockEntity blockEntity = targetLevel.getBlockEntity(targetPos);
			if (blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity) {
				targetBlockEntity.triggerBySource(request.sourceSerial(), request.activationMode().toInternal());
				triggeredCount++;
				continue;
			}

			BlockState state = targetLevel.getBlockState(targetPos);
			if (triggerByExternalTargetAdapters(
				targetLevel,
				targetPos,
				state,
				blockEntity,
				request.sourceSerial(),
				request.sourceType(),
				request.activationMode()
			)) {
				triggeredCount++;
				continue;
			}

			skippedUnsupportedCount++;
			savedData.removeNode(targetNodeType, targetSerial);
		}

		TriggerResult result = TriggerResult.success(
			totalTargets,
			triggeredCount,
			skippedMissingCount,
			skippedOfflineCount,
			skippedUnsupportedCount
		);
		LinkEvents.AFTER_TRIGGER.invoker().afterTrigger(request, result);
		return result;
	}

	@Override
	public TriggerResult emitFromExternal(
		ServerLevel level,
		BlockPos sourcePos,
		ApiActivationMode activationMode,
		ActorContext actorContext
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(sourcePos, "sourcePos");
		Objects.requireNonNull(activationMode, "activationMode");
		Objects.requireNonNull(actorContext, "actorContext");

		BlockState state = level.getBlockState(sourcePos);
		BlockEntity blockEntity = level.getBlockEntity(sourcePos);
		for (ExternalTriggerAdapter adapter : snapshotTriggerAdapters()) {
			if (!adapter.supports(level, sourcePos, state, blockEntity)) {
				continue;
			}
			long sourceSerial = adapter.resolveSourceSerial(level, sourcePos, state, blockEntity);
			if (sourceSerial <= 0L) {
				continue;
			}
			return emit(new TriggerRequest(level, adapter.sourceType(), sourceSerial, activationMode, actorContext));
		}
		return TriggerResult.failed(REASON_SOURCE_NOT_FOUND);
	}

	@Override
	public AuditSnapshot getAuditSnapshot(ServerLevel level) {
		Objects.requireNonNull(level, "level");
		LinkSavedData.AuditSnapshot snapshot = LinkSavedData.get(level).createAuditSnapshot();
		return new AuditSnapshot(
			snapshot.onlineCoreNodes(),
			snapshot.onlineButtonNodes(),
			snapshot.totalLinks(),
			snapshot.linksWithMissingEndpoint(),
			snapshot.linkedButtonSerialCount(),
			snapshot.linkedCoreSerialCount()
		);
	}

	@Override
	public Set<Long> getActiveSerials(ServerLevel level, ApiNodeType nodeType) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(nodeType, "nodeType");
		return LinkSavedData.get(level).getActiveSerials(nodeType.toInternal());
	}

	@Override
	public Set<Long> getRetiredSerials(ServerLevel level, ApiNodeType nodeType) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(nodeType, "nodeType");
		return LinkSavedData.get(level).getRetiredSerials(nodeType.toInternal());
	}

	@Override
	public void registerTargetAdapter(ResourceLocation id, ExternalTargetAdapter adapter) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(adapter, "adapter");
		synchronized (targetAdapters) {
			targetAdapters.put(id, adapter);
		}
	}

	@Override
	public void unregisterTargetAdapter(ResourceLocation id) {
		Objects.requireNonNull(id, "id");
		synchronized (targetAdapters) {
			targetAdapters.remove(id);
		}
	}

	@Override
	public Optional<ExternalTargetAdapter> findTargetAdapter(ResourceLocation id) {
		Objects.requireNonNull(id, "id");
		synchronized (targetAdapters) {
			return Optional.ofNullable(targetAdapters.get(id));
		}
	}

	@Override
	public Set<ResourceLocation> getTargetAdapterIds() {
		synchronized (targetAdapters) {
			return Set.copyOf(targetAdapters.keySet());
		}
	}

	@Override
	public void registerTriggerAdapter(ResourceLocation id, ExternalTriggerAdapter adapter) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(adapter, "adapter");
		synchronized (triggerAdapters) {
			triggerAdapters.put(id, adapter);
		}
	}

	@Override
	public void unregisterTriggerAdapter(ResourceLocation id) {
		Objects.requireNonNull(id, "id");
		synchronized (triggerAdapters) {
			triggerAdapters.remove(id);
		}
	}

	@Override
	public Optional<ExternalTriggerAdapter> findTriggerAdapter(ResourceLocation id) {
		Objects.requireNonNull(id, "id");
		synchronized (triggerAdapters) {
			return Optional.ofNullable(triggerAdapters.get(id));
		}
	}

	@Override
	public Set<ResourceLocation> getTriggerAdapterIds() {
		synchronized (triggerAdapters) {
			return Set.copyOf(triggerAdapters.keySet());
		}
	}

	private boolean triggerByExternalTargetAdapters(
		ServerLevel level,
		BlockPos targetPos,
		BlockState state,
		BlockEntity blockEntity,
		long sourceSerial,
		ApiNodeType sourceType,
		ApiActivationMode activationMode
	) {
		for (ExternalTargetAdapter adapter : snapshotTargetAdapters()) {
			if (!adapter.supports(level, targetPos, state, blockEntity)) {
				continue;
			}
			if (adapter.trigger(level, targetPos, state, blockEntity, sourceSerial, sourceType, activationMode)) {
				return true;
			}
		}
		return false;
	}

	private static Set<Long> normalizeTargets(Set<Long> targets) {
		if (targets == null || targets.isEmpty()) {
			return Set.of();
		}
		Set<Long> normalized = new LinkedHashSet<>();
		for (Long serial : targets) {
			if (serial != null && serial > 0L) {
				normalized.add(serial);
			}
		}
		return Set.copyOf(normalized);
	}

	private static Set<Long> readLinkedTargets(LinkSavedData savedData, LinkNodeType sourceType, long sourceSerial) {
		return sourceType == LinkNodeType.BUTTON ? savedData.getLinkedCores(sourceSerial) : savedData.getLinkedButtons(sourceSerial);
	}

	private static boolean toggleInternal(LinkSavedData savedData, LinkNodeType sourceType, long sourceSerial, long targetSerial) {
		return sourceType == LinkNodeType.BUTTON
			? savedData.toggleLink(sourceSerial, targetSerial)
			: savedData.toggleLink(targetSerial, sourceSerial);
	}

	private static long findLastTarget(Set<Long> targets) {
		long result = 0L;
		for (long target : targets) {
			result = Math.max(result, target);
		}
		return result;
	}

	private static String validateSourceSerial(LinkSavedData savedData, LinkNodeType sourceType, long sourceSerial) {
		if (sourceSerial <= 0L || !savedData.isSerialAllocated(sourceType, sourceSerial)) {
			return REASON_SOURCE_SERIAL_UNALLOCATED;
		}
		if (savedData.isSerialRetired(sourceType, sourceSerial)) {
			return REASON_SOURCE_SERIAL_RETIRED;
		}
		return "";
	}

	private static String validateTargetSerial(LinkSavedData savedData, LinkNodeType targetType, long targetSerial) {
		if (targetSerial <= 0L || !savedData.isSerialAllocated(targetType, targetSerial)) {
			return REASON_TARGET_SERIAL_UNALLOCATED;
		}
		if (savedData.isSerialRetired(targetType, targetSerial)) {
			return REASON_TARGET_SERIAL_RETIRED;
		}
		return "";
	}

	private static boolean isSerialAllocatedAndActive(LinkSavedData savedData, LinkNodeType type, long serial) {
		return serial > 0L && savedData.isSerialAllocated(type, serial) && !savedData.isSerialRetired(type, serial);
	}

	private static String decisionReasonKey(ApiDecision decision, String fallbackReason) {
		if (decision == null) {
			return fallbackReason;
		}
		String reason = decision.reasonKey();
		return reason == null || reason.isBlank() ? fallbackReason : reason;
	}

	private static boolean isTargetReachableNow(
		ServerLevel sourceLevel,
		LinkSavedData savedData,
		LinkNodeType targetType,
		long targetSerial
	) {
		LinkSavedData.LinkNode node = savedData.findNode(targetType, targetSerial).orElse(null);
		if (node == null) {
			return false;
		}
		ServerLevel targetLevel = sourceLevel.getServer().getLevel(node.dimension());
		return targetLevel != null && targetLevel.isLoaded(node.pos());
	}

	private static void updateNodeLastTargetSerial(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial
	) {
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		savedData.findNode(sourceType, sourceSerial).ifPresent(node -> {
			ServerLevel nodeLevel = sourceLevel.getServer().getLevel(node.dimension());
			if (nodeLevel == null || !nodeLevel.isLoaded(node.pos())) {
				return;
			}
			BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
			if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
				pairableNodeBlockEntity.setLastTargetSerial(targetSerial);
			}
		});
	}

	private List<ExternalTriggerAdapter> snapshotTriggerAdapters() {
		synchronized (triggerAdapters) {
			return List.copyOf(new ArrayList<>(triggerAdapters.values()));
		}
	}

	private List<ExternalTargetAdapter> snapshotTargetAdapters() {
		synchronized (targetAdapters) {
			return List.copyOf(new ArrayList<>(targetAdapters.values()));
		}
	}
}
