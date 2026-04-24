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
 * 当前覆盖 `core` 与在线 `triggerSource`（含 emitter 与转发器虚拟输出态）。
 * </p>
 */
public record NodeRuntimeSnapshot(
	TraceNodeKind traceKind,
	NodeIdentitySnapshot identity,
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
		identity = identity == null
			? new NodeIdentitySnapshot(LinkNodeType.CORE, 0L, false, false, false, null, null)
			: identity;
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
	}

	/**
	 * 节点类型便捷访问器。
	 */
	public LinkNodeType nodeType() {
		return identity.nodeType();
	}

	/**
	 * 节点序号便捷访问器。
	 */
	public long serial() {
		return identity.serial();
	}

	/**
	 * 已分配状态便捷访问器。
	 */
	public boolean allocated() {
		return identity.allocated();
	}

	/**
	 * 已退役状态便捷访问器。
	 */
	public boolean retired() {
		return identity.retired();
	}

	/**
	 * 在线状态便捷访问器。
	 */
	public boolean online() {
		return identity.online();
	}

	/**
	 * 维度便捷访问器。
	 */
	public ResourceKey<Level> dimension() {
		return identity.dimension();
	}

	/**
	 * 坐标便捷访问器。
	 */
	public BlockPos pos() {
		return identity.pos();
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
