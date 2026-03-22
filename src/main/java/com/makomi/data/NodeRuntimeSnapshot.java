package com.makomi.data;

import com.makomi.data.NodeRuntimeProbe.TraceNodeKind;
import com.makomi.util.SignalStrengths;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 节点运行态快照。
 * <p>
 * 该 DTO 统一承载命令读取、历史采样和后续状态面板所需的节点状态字段。
 * 首版覆盖 `core` 与 `sync triggerSource`。
 * </p>
 */
public record NodeRuntimeSnapshot(
	TraceNodeKind traceKind,
	LinkNodeType nodeType,
	long serial,
	boolean allocated,
	boolean retired,
	boolean online,
	ResourceKey<Level> dimension,
	BlockPos pos,
	long sampleTick,
	int sampleSlot,
	boolean active,
	int inputPower,
	int outputPower,
	String configuredMode,
	String effectiveMode,
	int resolvedStrength,
	List<Long> maxSourceSerials,
	int lastObservedInputPower,
	int lastDispatchedPower
) {
	public NodeRuntimeSnapshot {
		traceKind = traceKind == null ? TraceNodeKind.CORE : traceKind;
		nodeType = nodeType == null ? LinkNodeType.CORE : nodeType;
		serial = Math.max(0L, serial);
		sampleTick = Math.max(0L, sampleTick);
		sampleSlot = Math.max(0, sampleSlot);
		inputPower = SignalStrengths.clamp(inputPower);
		outputPower = SignalStrengths.clamp(outputPower);
		resolvedStrength = SignalStrengths.clamp(resolvedStrength);
		lastObservedInputPower = SignalStrengths.clamp(lastObservedInputPower);
		lastDispatchedPower = SignalStrengths.clamp(lastDispatchedPower);
		configuredMode = normalizeText(configuredMode);
		effectiveMode = normalizeText(effectiveMode);
		maxSourceSerials = maxSourceSerials == null ? List.of() : List.copyOf(maxSourceSerials);
		pos = pos == null ? null : pos.immutable();
	}

	/**
	 * 最大来源数量快照。
	 */
	public int maxSourceCount() {
		return maxSourceSerials.size();
	}

	private static String normalizeText(String rawText) {
		if (rawText == null) {
			return "-";
		}
		String normalized = rawText.trim();
		return normalized.isEmpty() ? "-" : normalized;
	}
}
