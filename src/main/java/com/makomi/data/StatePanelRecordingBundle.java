package com.makomi.data;

import java.util.List;

/**
 * 状态面板录制结果 bundle。
 * <p>
 * 该结构用于承载 `P2` 导出的网页离线录制资产，
 * 只服务于“录制完成后导出查看”的链路，不属于世界真值。
 * </p>
 */
public record StatePanelRecordingBundle(
	Manifest manifest,
	List<RecordedNodeInfo> nodes,
	List<NodeSeries> series,
	List<RecordingMarker> markers
) {
	/** 当前 recording bundle 格式版本。 */
	public static final int FORMAT_VERSION = 1;

	public StatePanelRecordingBundle {
		nodes = List.copyOf(nodes == null ? List.of() : nodes);
		series = List.copyOf(series == null ? List.of() : series);
		markers = List.copyOf(markers == null ? List.of() : markers);
	}

	/**
	 * 录制结果清单头。
	 */
	public record Manifest(
		String recordingId,
		String title,
		long startedTick,
		long endedTick,
		int sampleEveryTicks,
		int nodeCount,
		int sampleCount,
		int formatVersion
	) {
		public Manifest {
			recordingId = normalizeText(recordingId, "recording");
			title = normalizeText(title, "State Panel Recording");
			startedTick = Math.max(0L, startedTick);
			endedTick = Math.max(startedTick, endedTick);
			sampleEveryTicks = Math.max(1, sampleEveryTicks);
			nodeCount = Math.max(0, nodeCount);
			sampleCount = Math.max(0, sampleCount);
			formatVersion = Math.max(1, formatVersion);
		}
	}

	/**
	 * 单节点录制信息。
	 */
	public record RecordedNodeInfo(
		String nodeKey,
		LinkNodeType nodeType,
		long serial,
		String displayText,
		String traceKind,
		boolean allocated,
		boolean retired,
		boolean online
	) {
		public RecordedNodeInfo {
			nodeKey = normalizeText(nodeKey, "core:0");
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
			displayText = normalizeText(displayText, "-");
			traceKind = normalizeText(traceKind, "-");
		}
	}

	/**
	 * 单节点录制样本序列。
	 */
	public record NodeSeries(String nodeKey, List<RecordedSample> samples) {
		public NodeSeries {
			nodeKey = normalizeText(nodeKey, "core:0");
			samples = List.copyOf(samples == null ? List.of() : samples);
		}
	}

	/**
	 * 单条采样点。
	 */
	public record RecordedSample(long tick, boolean online, boolean active, int inputPower, int outputPower) {
		public RecordedSample {
			tick = Math.max(0L, tick);
			inputPower = Math.max(0, Math.min(15, inputPower));
			outputPower = Math.max(0, Math.min(15, outputPower));
		}
	}

	/**
	 * 录制标记点。
	 */
	public record RecordingMarker(long tick, String label) {
		public RecordingMarker {
			tick = Math.max(0L, tick);
			label = normalizeText(label, "-");
		}
	}

	private static String normalizeText(String rawText, String fallback) {
		if (rawText == null) {
			return fallback;
		}
		String normalized = rawText.trim();
		return normalized.isEmpty() ? fallback : normalized;
	}
}
