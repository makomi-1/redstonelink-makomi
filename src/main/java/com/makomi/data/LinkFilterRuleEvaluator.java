package com.makomi.data;

import com.makomi.util.SignalStrengths;
import java.util.List;
import java.util.Set;

/**
 * 过滤器规则求值器。
 * <p>
 * 节点集合规则按“白名单并集 + 过滤对象并集”聚合；
 * 信号规则按“所有命中过滤器都必须通过”收口，避免区域过滤器之间互相绕过。
 * </p>
 */
public final class LinkFilterRuleEvaluator {
	private LinkFilterRuleEvaluator() {
	}

	/**
	 * 计算当前节点是否允许通过过滤。
	 *
	 * @param activeFilters 当前命中的已激活过滤器视图
	 * @param serial 当前节点序号
	 * @param signalStrength 当前派发强度
	 * @return `true` 表示允许通过
	 */
	public static boolean allows(List<FilterRuntimeView> activeFilters, long serial, int signalStrength) {
		if (activeFilters == null || activeFilters.isEmpty()) {
			return true;
		}
		int normalizedSignalStrength = SignalStrengths.clamp(signalStrength);
		boolean hasWhitelist = false;
		boolean whitelistMatched = false;
		for (FilterRuntimeView filter : activeFilters) {
			if (!isEnabled(filter)) {
				continue;
			}
			if (!passesNodeSet(filter, serial)) {
				return false;
			}
			if (filter.nodeSetMode() == LinkFilterNodeSetMode.WHITELIST) {
				hasWhitelist = true;
				if (filter.serials().contains(serial)) {
					whitelistMatched = true;
				}
			}
			if (!passesSignal(filter, normalizedSignalStrength)) {
				return false;
			}
		}
		return !hasWhitelist || whitelistMatched;
	}

	/**
	 * 判断过滤器当前是否处于启用态。
	 * <p>
	 * 邻居输入为 0 时，过滤器整体视为关闭，不再参与任何节点集或信号规则求值。
	 * </p>
	 */
	private static boolean isEnabled(FilterRuntimeView filter) {
		return filter != null && filter.neighborSignalStrength() > 0;
	}

	/**
	 * 判断节点集合规则是否放行当前序号。
	 */
	private static boolean passesNodeSet(FilterRuntimeView filter, long serial) {
		if (filter.nodeSetMode() == LinkFilterNodeSetMode.BLOCKLIST) {
			return !filter.serials().contains(serial);
		}
		return true;
	}

	/**
	 * 判断信号规则是否放行当前强度。
	 */
	private static boolean passesSignal(FilterRuntimeView filter, int signalStrength) {
		if (filter.signalMode() == LinkFilterSignalMode.DISABLED) {
			return true;
		}
		int effectiveThreshold = filter.signalThresholdSource() == LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT
			? SignalStrengths.clamp(filter.neighborSignalStrength())
			: SignalStrengths.clamp(filter.fixedSignalThreshold());
		return switch (filter.signalMode()) {
			case UPPER_BOUND -> signalStrength <= effectiveThreshold;
			case LOWER_BOUND -> signalStrength >= effectiveThreshold;
			case DISABLED -> true;
		};
	}

	/**
	 * 运行时求值所需的过滤器只读视图。
	 */
	public record FilterRuntimeView(
		LinkFilterNodeSetMode nodeSetMode,
		Set<Long> serials,
		LinkFilterSignalThresholdSource signalThresholdSource,
		int fixedSignalThreshold,
		LinkFilterSignalMode signalMode,
		int neighborSignalStrength
	) {
		public FilterRuntimeView {
			nodeSetMode = nodeSetMode == null ? LinkFilterNodeSetMode.DISABLED : nodeSetMode;
			serials = Set.copyOf(serials == null ? Set.of() : serials);
			signalThresholdSource = signalThresholdSource == null
				? LinkFilterSignalThresholdSource.FIXED_INPUT
				: signalThresholdSource;
			fixedSignalThreshold = SignalStrengths.clamp(fixedSignalThreshold);
			signalMode = signalMode == null ? LinkFilterSignalMode.DISABLED : signalMode;
			neighborSignalStrength = SignalStrengths.clamp(neighborSignalStrength);
		}
	}
}
