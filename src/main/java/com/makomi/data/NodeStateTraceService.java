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
	}
}
