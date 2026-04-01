package com.makomi.data;

import com.makomi.data.NodeRuntimeProbe.TraceNodeKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 节点状态历史采样服务。
 * <p>
 * 该服务按 `type + serial` 动态挂载采样点，在服务端 tick 上以固定间隔采样，
 * 并使用 ring buffer 保留最近 N 条样本。
 * </p>
 */
public final class NodeStateTraceService {
	private static final Map<MinecraftServer, TraceState> TRACE_STATES = new IdentityHashMap<>();

	private NodeStateTraceService() {
	}

	/**
	 * 注册服务端 tick 与生命周期钩子。
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(NodeStateTraceService::onEndServerTick);
		ServerLifecycleEvents.SERVER_STOPPED.register(NodeStateTraceService::clearServerState);
	}

	/**
	 * 挂载或更新节点采样器，并立即写入一条当前快照。
	 */
	public static MountResult mount(
		MinecraftServer server,
		LinkNodeType nodeType,
		long serial,
		TraceNodeKind traceKind,
		int everyTicks,
		int capacity
	) {
		TraceState traceState = state(server);
		TraceKey traceKey = new TraceKey(nodeType, serial);
		TraceMountState mountState = traceState.mounts.get(traceKey);
		boolean updated = mountState != null;
		if (mountState == null) {
			mountState = new TraceMountState(traceKey, traceKind, everyTicks, capacity);
			traceState.mounts.put(traceKey, mountState);
		} else {
			mountState.updateConfig(traceKind, everyTicks, capacity);
		}
		NodeRuntimeSnapshot snapshot = NodeRuntimeProbe.snapshot(server, nodeType, serial, traceKind);
		mountState.record(snapshot);
		return new MountResult(updated, mountState.toInfo(), snapshot);
	}

	/**
	 * 卸载节点采样器。
	 */
	public static boolean unmount(MinecraftServer server, LinkNodeType nodeType, long serial) {
		if (server == null || nodeType == null || serial <= 0L) {
			return false;
		}
		TraceState traceState = TRACE_STATES.get(server);
		if (traceState == null) {
			return false;
		}
		return traceState.mounts.remove(new TraceKey(nodeType, serial)) != null;
	}

	/**
	 * 读取指定节点的挂载信息。
	 */
	public static Optional<TraceMountInfo> getMountInfo(MinecraftServer server, LinkNodeType nodeType, long serial) {
		if (server == null || nodeType == null || serial <= 0L) {
			return Optional.empty();
		}
		TraceState traceState = TRACE_STATES.get(server);
		if (traceState == null) {
			return Optional.empty();
		}
		TraceMountState mountState = traceState.mounts.get(new TraceKey(nodeType, serial));
		return mountState == null ? Optional.empty() : Optional.of(mountState.toInfo());
	}

	/**
	 * 读取指定节点最近的历史样本（按最新优先返回）。
	 */
	public static List<NodeRuntimeSnapshot> readSamples(MinecraftServer server, LinkNodeType nodeType, long serial, int limit) {
		if (server == null || nodeType == null || serial <= 0L || limit <= 0) {
			return List.of();
		}
		TraceState traceState = TRACE_STATES.get(server);
		if (traceState == null) {
			return List.of();
		}
		TraceMountState mountState = traceState.mounts.get(new TraceKey(nodeType, serial));
		return mountState == null ? List.of() : mountState.buffer.readLatest(limit);
	}

	/**
	 * 读取指定节点最新的一条样本。
	 */
	public static Optional<NodeRuntimeSnapshot> latestSample(MinecraftServer server, LinkNodeType nodeType, long serial) {
		if (server == null || nodeType == null || serial <= 0L) {
			return Optional.empty();
		}
		TraceState traceState = TRACE_STATES.get(server);
		if (traceState == null) {
			return Optional.empty();
		}
		TraceMountState mountState = traceState.mounts.get(new TraceKey(nodeType, serial));
		return mountState == null ? Optional.empty() : mountState.buffer.latest();
	}

	/**
	 * 列出当前服务器所有已挂载采样器。
	 */
	public static List<TraceMountInfo> listMounts(MinecraftServer server) {
		if (server == null) {
			return List.of();
		}
		TraceState traceState = TRACE_STATES.get(server);
		if (traceState == null || traceState.mounts.isEmpty()) {
			return List.of();
		}
		List<TraceMountInfo> mountInfos = new ArrayList<>();
		for (TraceMountState mountState : traceState.mounts.values()) {
			mountInfos.add(mountState.toInfo());
		}
		return List.copyOf(mountInfos);
	}

	/**
	 * 批量分析已挂载 trace 的 `SYNC` 延迟。
	 * <p>
	 * 该分析只依赖 ring buffer 中已经采集到的样本，不会额外触发节点查询，
	 * 用于 bench 在同一条 RCON 命令中收集大量 serial 的传播延迟。
	 * </p>
	 */
	public static TraceLatencyBatchResult analyzeTraceLatencyBatch(
		MinecraftServer server,
		LinkNodeType nodeType,
		List<Long> serials,
		long expectedStartTick,
		List<Integer> expectedPowers,
		Long latestExpectedStartTick
	) {
		LinkNodeType normalizedType = nodeType == null ? LinkNodeType.CORE : nodeType;
		List<Long> normalizedSerials = serials == null ? List.of() : List.copyOf(serials);
		List<Integer> normalizedExpectedPowers = normalizeExpectedPowers(expectedPowers);
		Long normalizedLatestExpectedStartTick = normalizeLatestExpectedStartTick(latestExpectedStartTick);
		if (server == null || normalizedSerials.isEmpty()) {
			return new TraceLatencyBatchResult(
				normalizedType,
				Math.max(0L, expectedStartTick),
				normalizedExpectedPowers.size(),
				normalizedSerials.size(),
				List.of()
			);
		}
		TraceState traceState = TRACE_STATES.get(server);
		if (traceState == null) {
			return new TraceLatencyBatchResult(
				normalizedType,
				Math.max(0L, expectedStartTick),
				normalizedExpectedPowers.size(),
				normalizedSerials.size(),
				buildNotMountedResults(normalizedType, normalizedSerials)
			);
		}
		List<TraceLatencySampleResult> sampleResults = new ArrayList<>(normalizedSerials.size());
		for (long serial : normalizedSerials) {
			TraceMountState mountState = traceState.mounts.get(new TraceKey(normalizedType, serial));
			List<NodeRuntimeSnapshot> chronologicalSamples = mountState == null ? List.of() : mountState.buffer.readChronological();
			int everyTicks = mountState == null ? 0 : mountState.everyTicks;
			sampleResults.add(
				analyzeLatencySamples(
					normalizedType,
					serial,
					everyTicks,
					chronologicalSamples,
					expectedStartTick,
					normalizedExpectedPowers,
					normalizedLatestExpectedStartTick
				)
			);
		}
		return new TraceLatencyBatchResult(
			normalizedType,
			Math.max(0L, expectedStartTick),
			normalizedExpectedPowers.size(),
			normalizedSerials.size(),
			List.copyOf(sampleResults)
		);
	}

	/**
	 * 对单个已采样 serial 的样本序列执行延迟匹配。
	 * <p>
	 * 保持 package-private，便于单测直接构造样本做契约验证。
	 * </p>
	 */
	static TraceLatencySampleResult analyzeLatencySamples(
		LinkNodeType nodeType,
		long serial,
		int everyTicks,
		List<NodeRuntimeSnapshot> chronologicalSamples,
		long expectedStartTick,
		List<Integer> expectedPowers,
		Long latestExpectedStartTick
	) {
		LinkNodeType normalizedType = nodeType == null ? LinkNodeType.CORE : nodeType;
		List<NodeRuntimeSnapshot> samples = chronologicalSamples == null ? List.of() : List.copyOf(chronologicalSamples);
		List<Integer> normalizedExpectedPowers = normalizeExpectedPowers(expectedPowers);
		Long normalizedLatestExpectedStartTick = normalizeLatestExpectedStartTick(latestExpectedStartTick);
		if (samples.isEmpty()) {
			return new TraceLatencySampleResult(
				normalizedType,
				serial,
				false,
				false,
				-1L,
				Math.max(0L, expectedStartTick),
				null,
				null,
				-1L,
				0,
				"not_mounted"
			);
		}
		long mountTick = Math.max(0L, samples.getFirst().sampleTick());
		long latestSampleTick = Math.max(mountTick, samples.getLast().sampleTick());
		long searchStartTick = Math.max(Math.max(0L, expectedStartTick), mountTick + 1L);
		int eligibleSampleCount = 0;
		Map<Long, NodeRuntimeSnapshot> samplesByTick = new LinkedHashMap<>();
		for (NodeRuntimeSnapshot sample : samples) {
			if (sample == null) {
				continue;
			}
			samplesByTick.putIfAbsent(sample.sampleTick(), sample);
			if (sample.sampleTick() >= searchStartTick) {
				eligibleSampleCount++;
			}
		}
		if (everyTicks != 1) {
			return new TraceLatencySampleResult(
				normalizedType,
				serial,
				true,
				false,
				mountTick,
				searchStartTick,
				null,
				null,
				latestSampleTick,
				eligibleSampleCount,
				"unsupported_sampling_interval"
			);
		}
		if (normalizedExpectedPowers.isEmpty()) {
			return new TraceLatencySampleResult(
				normalizedType,
				serial,
				true,
				true,
				mountTick,
				searchStartTick,
				searchStartTick,
				Math.max(0L, searchStartTick - Math.max(0L, expectedStartTick)),
				latestSampleTick,
				eligibleSampleCount,
				"matched"
			);
		}
		long latestCandidateStartTick = latestSampleTick - normalizedExpectedPowers.size() + 1L;
		if (eligibleSampleCount < normalizedExpectedPowers.size() || latestCandidateStartTick < searchStartTick) {
			return new TraceLatencySampleResult(
				normalizedType,
				serial,
				true,
				false,
				mountTick,
				searchStartTick,
				null,
				null,
				latestSampleTick,
				eligibleSampleCount,
				"insufficient_samples"
			);
		}
		long searchEndTick = latestCandidateStartTick;
		if (normalizedLatestExpectedStartTick != null) {
			searchEndTick = Math.min(searchEndTick, normalizedLatestExpectedStartTick);
		}
		if (searchEndTick < searchStartTick) {
			return new TraceLatencySampleResult(
				normalizedType,
				serial,
				true,
				false,
				mountTick,
				searchStartTick,
				null,
				null,
				latestSampleTick,
				eligibleSampleCount,
				"search_window_exhausted"
			);
		}
		for (long candidateStartTick = searchStartTick; candidateStartTick <= searchEndTick; candidateStartTick++) {
			if (matchesExpectedWindow(samplesByTick, candidateStartTick, normalizedExpectedPowers)) {
				return new TraceLatencySampleResult(
					normalizedType,
					serial,
					true,
					true,
					mountTick,
					searchStartTick,
					candidateStartTick,
					Math.max(0L, candidateStartTick - Math.max(0L, expectedStartTick)),
					latestSampleTick,
					eligibleSampleCount,
					"matched"
				);
			}
		}
		return new TraceLatencySampleResult(
			normalizedType,
			serial,
			true,
			false,
			mountTick,
			searchStartTick,
			null,
			null,
			latestSampleTick,
			eligibleSampleCount,
			"no_match"
		);
	}

	private static void onEndServerTick(MinecraftServer server) {
		TraceState traceState = TRACE_STATES.get(server);
		if (traceState == null || traceState.mounts.isEmpty()) {
			return;
		}
		long nowTick = Math.max(0L, server.overworld().getGameTime());
		for (TraceMountState mountState : traceState.mounts.values()) {
			if (nowTick < mountState.nextSampleTick) {
				continue;
			}
			NodeRuntimeSnapshot snapshot = NodeRuntimeProbe.snapshot(
				server,
				mountState.traceKey.nodeType(),
				mountState.traceKey.serial(),
				mountState.traceKind
			);
			mountState.record(snapshot);
		}
	}

	private static void clearServerState(MinecraftServer server) {
		TRACE_STATES.remove(server);
	}

	private static TraceState state(MinecraftServer server) {
		return TRACE_STATES.computeIfAbsent(server, unused -> new TraceState());
	}

	/**
	 * 挂载结果。
	 */
	public record MountResult(boolean updated, TraceMountInfo mountInfo, NodeRuntimeSnapshot latestSnapshot) {}

	/**
	 * 批量延迟分析结果。
	 */
	public record TraceLatencyBatchResult(
		LinkNodeType nodeType,
		long expectedStartTick,
		int expectedTickCount,
		int requestedCount,
		List<TraceLatencySampleResult> sampleResults
	) {
		public TraceLatencyBatchResult {
			nodeType = nodeType == null ? LinkNodeType.CORE : nodeType;
			expectedStartTick = Math.max(0L, expectedStartTick);
			expectedTickCount = Math.max(0, expectedTickCount);
			requestedCount = Math.max(0, requestedCount);
			sampleResults = sampleResults == null ? List.of() : List.copyOf(sampleResults);
		}

		/**
		 * 已返回样本条数。
		 */
		public int analyzedCount() {
			return sampleResults.size();
		}

		/**
		 * 已挂载 serial 数。
		 */
		public int mountedCount() {
			int mounted = 0;
			for (TraceLatencySampleResult sampleResult : sampleResults) {
				if (sampleResult != null && sampleResult.mounted()) {
					mounted++;
				}
			}
			return mounted;
		}

		/**
		 * 成功匹配 serial 数。
		 */
		public int matchedCount() {
			int matched = 0;
			for (TraceLatencySampleResult sampleResult : sampleResults) {
				if (sampleResult != null && sampleResult.matched()) {
					matched++;
				}
			}
			return matched;
		}

		/**
		 * 未匹配 serial 数。
		 */
		public int unmatchedCount() {
			return Math.max(0, analyzedCount() - matchedCount());
		}
	}

	/**
	 * 单个 serial 的延迟分析结果。
	 */
	public record TraceLatencySampleResult(
		LinkNodeType nodeType,
		long serial,
		boolean mounted,
		boolean matched,
		long mountTick,
		long searchStartTick,
		Long actualStartTick,
		Long inputDelayTicks,
		long latestSampleTick,
		int eligibleSampleCount,
		String reason
	) {
		public TraceLatencySampleResult {
			nodeType = nodeType == null ? LinkNodeType.CORE : nodeType;
			mountTick = Math.max(-1L, mountTick);
			searchStartTick = Math.max(0L, searchStartTick);
			latestSampleTick = Math.max(-1L, latestSampleTick);
			eligibleSampleCount = Math.max(0, eligibleSampleCount);
			reason = normalizeReason(reason);
		}
	}

	/**
	 * 采样器挂载信息。
	 */
	public record TraceMountInfo(
		LinkNodeType nodeType,
		long serial,
		TraceNodeKind traceKind,
		int everyTicks,
		int capacity,
		int sampleCount,
		long lastSampleTick
	) {}

	private static final class TraceState {
		private final Map<TraceKey, TraceMountState> mounts = new LinkedHashMap<>();
	}

	private record TraceKey(LinkNodeType nodeType, long serial) {}

	private static final class TraceMountState {
		private final TraceKey traceKey;
		private TraceNodeKind traceKind;
		private int everyTicks;
		private final TraceBuffer buffer;
		private long nextSampleTick;

		private TraceMountState(TraceKey traceKey, TraceNodeKind traceKind, int everyTicks, int capacity) {
			this.traceKey = traceKey;
			this.traceKind = traceKind;
			this.everyTicks = Math.max(1, everyTicks);
			this.buffer = new TraceBuffer(Math.max(1, capacity));
			this.nextSampleTick = 0L;
		}

		private void updateConfig(TraceNodeKind traceKind, int everyTicks, int capacity) {
			this.traceKind = traceKind == null ? this.traceKind : traceKind;
			this.everyTicks = Math.max(1, everyTicks);
			this.buffer.setCapacity(Math.max(1, capacity));
			this.nextSampleTick = buffer.latest().map(snapshot -> snapshot.sampleTick() + this.everyTicks).orElse(0L);
		}

		private void record(NodeRuntimeSnapshot snapshot) {
			buffer.append(snapshot);
			nextSampleTick = Math.max(0L, snapshot.sampleTick()) + everyTicks;
		}

		private TraceMountInfo toInfo() {
			long lastSampleTick = buffer.latest().map(NodeRuntimeSnapshot::sampleTick).orElse(-1L);
			return new TraceMountInfo(
				traceKey.nodeType(),
				traceKey.serial(),
				traceKind,
				everyTicks,
				buffer.capacity(),
				buffer.size(),
				lastSampleTick
			);
		}
	}

	/**
	 * 采样 ring buffer。
	 * <p>
	 * 保持最近 N 条样本，读取时按最新优先返回。
	 * </p>
	 */
	static final class TraceBuffer {
		private final ArrayDeque<NodeRuntimeSnapshot> samples = new ArrayDeque<>();
		private int capacity;

		TraceBuffer(int capacity) {
			this.capacity = Math.max(1, capacity);
		}

		void append(NodeRuntimeSnapshot snapshot) {
			if (snapshot == null) {
				return;
			}
			while (samples.size() >= capacity) {
				samples.removeFirst();
			}
			samples.addLast(snapshot);
		}

		void setCapacity(int capacity) {
			this.capacity = Math.max(1, capacity);
			while (samples.size() > this.capacity) {
				samples.removeFirst();
			}
		}

		int capacity() {
			return capacity;
		}

		int size() {
			return samples.size();
		}

		Optional<NodeRuntimeSnapshot> latest() {
			return Optional.ofNullable(samples.peekLast());
		}

		List<NodeRuntimeSnapshot> readLatest(int limit) {
			if (limit <= 0 || samples.isEmpty()) {
				return List.of();
			}
			List<NodeRuntimeSnapshot> results = new ArrayList<>();
			int remaining = limit;
			for (var iterator = samples.descendingIterator(); iterator.hasNext() && remaining > 0; remaining--) {
				results.add(iterator.next());
			}
			return List.copyOf(results);
		}

		List<NodeRuntimeSnapshot> readChronological() {
			if (samples.isEmpty()) {
				return List.of();
			}
			return List.copyOf(samples);
		}
	}

	private static List<Integer> normalizeExpectedPowers(List<Integer> expectedPowers) {
		if (expectedPowers == null || expectedPowers.isEmpty()) {
			return List.of();
		}
		List<Integer> normalized = new ArrayList<>(expectedPowers.size());
		for (Integer power : expectedPowers) {
			int value = power == null ? 0 : power;
			normalized.add(Math.max(0, Math.min(15, value)));
		}
		return List.copyOf(normalized);
	}

	/**
	 * 规范化批量匹配窗口的可选结束 tick。
	 */
	private static Long normalizeLatestExpectedStartTick(Long latestExpectedStartTick) {
		if (latestExpectedStartTick == null) {
			return null;
		}
		return Math.max(0L, latestExpectedStartTick);
	}

	private static boolean matchesExpectedWindow(
		Map<Long, NodeRuntimeSnapshot> samplesByTick,
		long candidateStartTick,
		List<Integer> expectedPowers
	) {
		for (int offset = 0; offset < expectedPowers.size(); offset++) {
			NodeRuntimeSnapshot sample = samplesByTick.get(candidateStartTick + offset);
			if (!matchesExpectedPower(sample, expectedPowers.get(offset))) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesExpectedPower(NodeRuntimeSnapshot sample, int expectedPower) {
		if (sample == null) {
			return false;
		}
		boolean expectedActive = expectedPower > 0;
		return sample.outputPower() == expectedPower && sample.active() == expectedActive;
	}

	private static List<TraceLatencySampleResult> buildNotMountedResults(LinkNodeType nodeType, List<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		List<TraceLatencySampleResult> results = new ArrayList<>(serials.size());
		for (long serial : serials) {
			results.add(
				new TraceLatencySampleResult(
					nodeType,
					serial,
					false,
					false,
					-1L,
					0L,
					null,
					null,
					-1L,
					0,
					"not_mounted"
				)
			);
		}
		return List.copyOf(results);
	}

	private static String normalizeReason(String reason) {
		if (reason == null) {
			return "-";
		}
		String normalized = reason.trim();
		return normalized.isEmpty() ? "-" : normalized;
	}
}
