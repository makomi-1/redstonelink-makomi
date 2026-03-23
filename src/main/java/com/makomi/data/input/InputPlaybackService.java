package com.makomi.data.input;

import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.block.entity.LinkTriggerSourceBlockEntity;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 输入播放服务。
 * <p>
 * 维护内存态输入 job，并在服务端 tick 上将波形样本投递到：
 * 1. `triggerSource` 发射器输入
 * 2. `core` 的 `sync` 直输
 * </p>
 */
public final class InputPlaybackService {
	private static final long SIMULATED_SOURCE_SERIAL_START = Long.MAX_VALUE;
	private static final Map<MinecraftServer, InputState> INPUT_STATES = new IdentityHashMap<>();

	private InputPlaybackService() {
	}

	/**
	 * 注册服务端 tick 与生命周期钩子。
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register((server) -> process(server, state(server), currentTick(server), false));
		ServerLifecycleEvents.SERVER_STOPPING.register(InputPlaybackService::clearBeforeStop);
		ServerLifecycleEvents.SERVER_STOPPED.register(INPUT_STATES::remove);
	}

	/**
	 * 停服前先清空运行中 job，确保模拟输入不会以外显状态残留进存档。
	 */
	private static void clearBeforeStop(MinecraftServer server) {
		clear(server);
	}

	/**
	 * 启动输入 job。
	 */
	public static StartJobResult start(MinecraftServer server, InputJobSpec jobSpec) {
		if (server == null || jobSpec == null || jobSpec.targetSerials().isEmpty()) {
			return StartJobResult.invalid();
		}
		InputState inputState = state(server);
		ValidationResult validationResult = validateAndBuildLanes(server, inputState, jobSpec);
		if (!validationResult.valid()) {
			return new StartJobResult(false, null, validationResult.offlineSerials(), validationResult.unsupportedSerials());
		}

		long jobId = inputState.nextJobId++;
		long startTick = currentTick(server);
		JobState jobState = new JobState(jobId, jobSpec, startTick, validationResult.lanes());
		inputState.jobs.put(jobId, jobState);
		process(server, inputState, startTick, true);
		return new StartJobResult(true, jobState.toInfo(startTick), List.of(), List.of());
	}

	/**
	 * 停止指定 job。
	 */
	public static boolean stop(MinecraftServer server, long jobId) {
		if (server == null || jobId <= 0L) {
			return false;
		}
		InputState inputState = INPUT_STATES.get(server);
		if (inputState == null) {
			return false;
		}
		JobState jobState = inputState.jobs.remove(jobId);
		if (jobState == null) {
			return false;
		}
		long nowTick = currentTick(server);
		cleanupCoreSyncLanes(server, jobState, nowTick);
		process(server, inputState, nowTick, true);
		return true;
	}

	/**
	 * 清空当前服务器上的所有运行中 job。
	 */
	public static int clear(MinecraftServer server) {
		if (server == null) {
			return 0;
		}
		InputState inputState = INPUT_STATES.get(server);
		if (inputState == null || inputState.jobs.isEmpty()) {
			return 0;
		}
		long nowTick = currentTick(server);
		List<JobState> snapshot = List.copyOf(inputState.jobs.values());
		inputState.jobs.clear();
		for (JobState jobState : snapshot) {
			cleanupCoreSyncLanes(server, jobState, nowTick);
		}
		process(server, inputState, nowTick, true);
		return snapshot.size();
	}

	/**
	 * 列出当前运行中的 job。
	 */
	public static List<JobInfo> listJobs(MinecraftServer server) {
		if (server == null) {
			return List.of();
		}
		InputState inputState = INPUT_STATES.get(server);
		if (inputState == null || inputState.jobs.isEmpty()) {
			return List.of();
		}
		long nowTick = currentTick(server);
		List<JobInfo> jobs = new ArrayList<>();
		for (JobState jobState : inputState.jobs.values()) {
			jobs.add(jobState.toInfo(nowTick));
		}
		return List.copyOf(jobs);
	}

	private static void process(MinecraftServer server, InputState inputState, long nowTick, boolean force) {
		if (server == null || inputState == null) {
			return;
		}
		if (!force && inputState.lastProcessedTick == nowTick) {
			return;
		}

		Map<Long, Integer> desiredTriggerSourcePowers = new HashMap<>();
		List<Long> completedJobIds = new ArrayList<>();
		for (JobState jobState : inputState.jobs.values()) {
			if (jobState.completed(nowTick)) {
				cleanupCoreSyncLanes(server, jobState, nowTick);
				completedJobIds.add(jobState.jobId());
				continue;
			}
			int sampledPower = jobState.spec().waveform().sampleAt(jobState.elapsedTicks(nowTick));
			switch (jobState.spec().endpointKind()) {
				case TRIGGER_SOURCE_INPUT -> accumulateTriggerSourcePowers(jobState, sampledPower, desiredTriggerSourcePowers);
				case CORE_SYNC_DIRECT -> applyCoreSyncLanes(server, jobState, sampledPower, nowTick);
			}
		}

		if (!completedJobIds.isEmpty()) {
			for (Long jobId : completedJobIds) {
				inputState.jobs.remove(jobId);
			}
		}
		reconcileTriggerSourceInputs(server, inputState, desiredTriggerSourcePowers);
		inputState.lastProcessedTick = nowTick;
	}

	private static void accumulateTriggerSourcePowers(JobState jobState, int sampledPower, Map<Long, Integer> desiredPowers) {
		for (RuntimeLane runtimeLane : jobState.lanes()) {
			if (!(runtimeLane instanceof TriggerSourceLane triggerSourceLane)) {
				continue;
			}
			desiredPowers.merge(triggerSourceLane.targetSerial(), sampledPower, Math::max);
		}
	}

	private static void applyCoreSyncLanes(MinecraftServer server, JobState jobState, int sampledPower, long nowTick) {
		for (RuntimeLane runtimeLane : jobState.lanes()) {
			if (!(runtimeLane instanceof CoreSyncLane coreSyncLane)) {
				continue;
			}
			applyCoreSyncLane(server, coreSyncLane, sampledPower, nowTick);
		}
	}

	private static void applyCoreSyncLane(MinecraftServer server, CoreSyncLane coreSyncLane, int desiredPower, long nowTick) {
		ResolvedCoreTarget resolvedTarget = resolveCoreTarget(server, coreSyncLane.targetSerial()).orElse(null);
		if (resolvedTarget == null) {
			coreSyncLane.onlineApplied = false;
			return;
		}
		if (!coreSyncLane.onlineApplied || coreSyncLane.lastAppliedPower != desiredPower) {
			EventMeta eventMeta = EventMeta.of(nowTick, 0, coreSyncLane.simulatedSourceSerial());
			if (desiredPower > 0) {
				resolvedTarget.blockEntity().applyRuntimeSimulatedSyncSource(
					coreSyncLane.simulatedSourceSerial(),
					desiredPower,
					eventMeta
				);
			} else {
				resolvedTarget.blockEntity().removeRuntimeSimulatedSyncSource(
					coreSyncLane.simulatedSourceSerial(),
					eventMeta
				);
			}
			coreSyncLane.lastAppliedPower = desiredPower;
			coreSyncLane.onlineApplied = true;
		}
	}

	private static void cleanupCoreSyncLanes(MinecraftServer server, JobState jobState, long nowTick) {
		for (RuntimeLane runtimeLane : jobState.lanes()) {
			if (!(runtimeLane instanceof CoreSyncLane coreSyncLane)) {
				continue;
			}
			ResolvedCoreTarget resolvedTarget = resolveCoreTarget(server, coreSyncLane.targetSerial()).orElse(null);
			if (resolvedTarget != null) {
				resolvedTarget.blockEntity().removeRuntimeSimulatedSyncSource(
					coreSyncLane.simulatedSourceSerial(),
					EventMeta.of(nowTick, 0, coreSyncLane.simulatedSourceSerial())
				);
			}
			coreSyncLane.lastAppliedPower = 0;
			coreSyncLane.onlineApplied = false;
		}
	}

	private static void reconcileTriggerSourceInputs(
		MinecraftServer server,
		InputState inputState,
		Map<Long, Integer> desiredTriggerSourcePowers
	) {
		Map<Long, AppliedTriggerSourceState> previousStates = inputState.appliedTriggerSourceStates;
		List<Long> serialsToVisit = new ArrayList<>(previousStates.keySet());
		for (Long serial : desiredTriggerSourcePowers.keySet()) {
			if (!previousStates.containsKey(serial)) {
				serialsToVisit.add(serial);
			}
		}

		Map<Long, AppliedTriggerSourceState> nextStates = new HashMap<>();
		for (Long sourceSerial : serialsToVisit) {
			if (sourceSerial == null || sourceSerial <= 0L) {
				continue;
			}
			int desiredPower = desiredTriggerSourcePowers.getOrDefault(sourceSerial, 0);
			AppliedTriggerSourceState previousState = previousStates.getOrDefault(sourceSerial, new AppliedTriggerSourceState(0, false));
			ResolvedTriggerSource resolvedTriggerSource = resolveTriggerSource(server, sourceSerial).orElse(null);
			if (resolvedTriggerSource == null) {
				if (desiredPower > 0) {
					nextStates.put(sourceSerial, new AppliedTriggerSourceState(desiredPower, false));
				}
				continue;
			}
			if (!previousState.onlineApplied() || previousState.appliedPower() != desiredPower) {
				applyTriggerSourceInput(resolvedTriggerSource, desiredPower);
			}
			if (desiredPower > 0) {
				nextStates.put(sourceSerial, new AppliedTriggerSourceState(desiredPower, true));
			}
		}
		inputState.appliedTriggerSourceStates = nextStates;
	}

	/**
	 * 应用一次 triggerSource 输入器目标刷新。
	 * <p>
	 * 真实刷新阶段仍复用原有触发逻辑；仅对 sync replay 快照做“运行时/持久化”分层处理。
	 * </p>
	 */
	private static void applyTriggerSourceInput(ResolvedTriggerSource resolvedTriggerSource, int desiredPower) {
		LinkTriggerSourceBlockEntity blockEntity = resolvedTriggerSource.blockEntity();
		LinkSignalEmitterBlock block = resolvedTriggerSource.block();
		ServerLevel level = resolvedTriggerSource.level();
		if (blockEntity == null || block == null || level == null) {
			return;
		}
		blockEntity.beginRuntimeInputRefresh();
		try {
			blockEntity.setSimulatedInputPower(desiredPower);
			block.refreshPoweredStateFromCurrentInputs(level, blockEntity.getBlockPos(), level.getBlockState(blockEntity.getBlockPos()));
		} finally {
			blockEntity.endRuntimeInputRefresh();
		}
		if (desiredPower <= 0 && blockEntity instanceof SyncReplaySourceBlockEntity syncReplaySourceBlockEntity) {
			syncReplaySourceBlockEntity.clearRuntimeReplaySyncSnapshot();
			block.resyncPoweredStateFromCurrentInputsWithoutTrigger(
				level,
				blockEntity.getBlockPos(),
				level.getBlockState(blockEntity.getBlockPos())
			);
		}
	}

	private static ValidationResult validateAndBuildLanes(
		MinecraftServer server,
		InputState inputState,
		InputJobSpec jobSpec
	) {
		List<RuntimeLane> lanes = new ArrayList<>();
		List<Long> offlineSerials = new ArrayList<>();
		List<Long> unsupportedSerials = new ArrayList<>();
		for (Long targetSerial : jobSpec.targetSerials()) {
			if (targetSerial == null || targetSerial <= 0L) {
				continue;
			}
			switch (jobSpec.endpointKind()) {
				case TRIGGER_SOURCE_INPUT -> {
					ResolvedNode resolvedNode = resolveNode(server, LinkNodeType.TRIGGER_SOURCE, targetSerial).orElse(null);
					if (resolvedNode == null) {
						offlineSerials.add(targetSerial);
						continue;
					}
					if (
						!(resolvedNode.blockEntity() instanceof LinkTriggerSourceBlockEntity triggerSourceBlockEntity)
							|| !(triggerSourceBlockEntity.getBlockState().getBlock() instanceof LinkSignalEmitterBlock)
					) {
						unsupportedSerials.add(targetSerial);
						continue;
					}
					lanes.add(new TriggerSourceLane(targetSerial));
				}
				case CORE_SYNC_DIRECT -> {
					ResolvedNode resolvedNode = resolveNode(server, LinkNodeType.CORE, targetSerial).orElse(null);
					if (resolvedNode == null) {
						offlineSerials.add(targetSerial);
						continue;
					}
					if (!(resolvedNode.blockEntity() instanceof ActivatableTargetBlockEntity)) {
						unsupportedSerials.add(targetSerial);
						continue;
					}
					long simulatedSourceSerial = inputState.nextSimulatedSourceSerial--;
					if (simulatedSourceSerial <= 0L) {
						unsupportedSerials.add(targetSerial);
						continue;
					}
					lanes.add(new CoreSyncLane(targetSerial, simulatedSourceSerial));
				}
			}
		}
		boolean valid = !lanes.isEmpty() && offlineSerials.isEmpty() && unsupportedSerials.isEmpty();
		return new ValidationResult(valid, List.copyOf(lanes), List.copyOf(offlineSerials), List.copyOf(unsupportedSerials));
	}

	private static Optional<ResolvedTriggerSource> resolveTriggerSource(MinecraftServer server, long sourceSerial) {
		if (server == null || sourceSerial <= 0L) {
			return Optional.empty();
		}
		ResolvedNode resolvedNode = resolveNode(server, LinkNodeType.TRIGGER_SOURCE, sourceSerial).orElse(null);
		if (resolvedNode == null) {
			return Optional.empty();
		}
		if (!(resolvedNode.blockEntity() instanceof LinkTriggerSourceBlockEntity triggerSourceBlockEntity)) {
			return Optional.empty();
		}
		BlockState blockState = triggerSourceBlockEntity.getBlockState();
		if (!(blockState.getBlock() instanceof LinkSignalEmitterBlock signalEmitterBlock)) {
			return Optional.empty();
		}
		return Optional.of(new ResolvedTriggerSource(resolvedNode.level(), triggerSourceBlockEntity, signalEmitterBlock));
	}

	private static Optional<ResolvedCoreTarget> resolveCoreTarget(MinecraftServer server, long coreSerial) {
		if (server == null || coreSerial <= 0L) {
			return Optional.empty();
		}
		ResolvedNode resolvedNode = resolveNode(server, LinkNodeType.CORE, coreSerial).orElse(null);
		if (resolvedNode == null || !(resolvedNode.blockEntity() instanceof ActivatableTargetBlockEntity targetBlockEntity)) {
			return Optional.empty();
		}
		return Optional.of(new ResolvedCoreTarget(resolvedNode.level(), targetBlockEntity));
	}

	private static Optional<ResolvedNode> resolveNode(MinecraftServer server, LinkNodeType nodeType, long serial) {
		if (server == null || nodeType == null || serial <= 0L) {
			return Optional.empty();
		}
		ServerLevel overworld = server.overworld();
		if (overworld == null) {
			return Optional.empty();
		}
		LinkSavedData.LinkNode node = LinkSavedData.get(overworld).findNode(nodeType, serial).orElse(null);
		if (node == null) {
			return Optional.empty();
		}
		ServerLevel level = server.getLevel(node.dimension());
		if (level == null || !level.isLoaded(node.pos())) {
			return Optional.empty();
		}
		BlockEntity blockEntity = level.getBlockEntity(node.pos());
		if (blockEntity == null) {
			return Optional.empty();
		}
		return Optional.of(new ResolvedNode(level, blockEntity));
	}

	private static long currentTick(MinecraftServer server) {
		ServerLevel overworld = server == null ? null : server.overworld();
		return overworld == null ? 0L : Math.max(0L, overworld.getGameTime());
	}

	private static InputState state(MinecraftServer server) {
		return INPUT_STATES.computeIfAbsent(server, unused -> new InputState());
	}

	/**
	 * 启动结果。
	 */
	public record StartJobResult(
		boolean started,
		JobInfo jobInfo,
		List<Long> offlineSerials,
		List<Long> unsupportedSerials
	) {
		private static StartJobResult invalid() {
			return new StartJobResult(false, null, List.of(), List.of());
		}
	}

	/**
	 * 对外展示的 job 摘要。
	 */
	public record JobInfo(
		long jobId,
		InputEndpointKind endpointKind,
		String waveformSummary,
		List<Long> targetSerials,
		int targetCount,
		int totalTicks,
		long startTick,
		long elapsedTicks
	) {}

	private record ValidationResult(
		boolean valid,
		List<RuntimeLane> lanes,
		List<Long> offlineSerials,
		List<Long> unsupportedSerials
	) {}

	private record ResolvedNode(ServerLevel level, BlockEntity blockEntity) {}

	private record ResolvedTriggerSource(
		ServerLevel level,
		LinkTriggerSourceBlockEntity blockEntity,
		LinkSignalEmitterBlock block
	) {}

	private record ResolvedCoreTarget(ServerLevel level, ActivatableTargetBlockEntity blockEntity) {}

	private static final class InputState {
		private final Map<Long, JobState> jobs = new LinkedHashMap<>();
		private Map<Long, AppliedTriggerSourceState> appliedTriggerSourceStates = new HashMap<>();
		private long nextJobId = 1L;
		private long nextSimulatedSourceSerial = SIMULATED_SOURCE_SERIAL_START;
		private long lastProcessedTick = Long.MIN_VALUE;
	}

	private static final class JobState {
		private final long jobId;
		private final InputJobSpec spec;
		private final long startTick;
		private final List<RuntimeLane> lanes;

		private JobState(long jobId, InputJobSpec spec, long startTick, List<RuntimeLane> lanes) {
			this.jobId = jobId;
			this.spec = spec;
			this.startTick = Math.max(0L, startTick);
			this.lanes = List.copyOf(lanes);
		}

		private long jobId() {
			return jobId;
		}

		private InputJobSpec spec() {
			return spec;
		}

		private List<RuntimeLane> lanes() {
			return lanes;
		}

		private long elapsedTicks(long nowTick) {
			return Math.max(0L, nowTick - startTick);
		}

		private boolean completed(long nowTick) {
			return spec.finiteDuration() && elapsedTicks(nowTick) >= spec.totalTicks();
		}

		private JobInfo toInfo(long nowTick) {
			return new JobInfo(
				jobId,
				spec.endpointKind(),
				spec.waveform().describe(),
				spec.targetSerials(),
				spec.targetSerials().size(),
				spec.totalTicks(),
				startTick,
				elapsedTicks(nowTick)
			);
		}
	}

	private sealed interface RuntimeLane permits TriggerSourceLane, CoreSyncLane {}

	private record TriggerSourceLane(long targetSerial) implements RuntimeLane {}

	private static final class CoreSyncLane implements RuntimeLane {
		private final long targetSerial;
		private final long simulatedSourceSerial;
		private int lastAppliedPower;
		private boolean onlineApplied;

		private CoreSyncLane(long targetSerial, long simulatedSourceSerial) {
			this.targetSerial = targetSerial;
			this.simulatedSourceSerial = simulatedSourceSerial;
		}

		private long targetSerial() {
			return targetSerial;
		}

		private long simulatedSourceSerial() {
			return simulatedSourceSerial;
		}
	}

	private record AppliedTriggerSourceState(int appliedPower, boolean onlineApplied) {}
}
