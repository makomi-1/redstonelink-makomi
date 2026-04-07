package com.makomi.command.bench;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.link.LinkCommandSupport;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * bench/internal 结构化批量建链执行支撑。
 * <p>
 * 该支撑仅服务于 bench 自动化流程，固定使用 `triggerSource -> core` 方向，
 * 并复用现有共享覆盖写入闭环完成批量映射落地。
 * </p>
 */
final class BenchLinkMappingApplySupport {
	static final int MAX_BATCH_SERIALS = 4096;

	private BenchLinkMappingApplySupport() {
	}

	/**
	 * 执行一次结构化批量建链。
	 */
	static int executeApply(CommandContext<CommandSourceStack> context, MappingSpec mappingSpec) {
		CommandSourceStack source = context.getSource();
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.LINK_RW,
				1
			)
		) {
			return 0;
		}

		String rawSourceSerials = SerialBatchArgumentType.getSerialBatch(context, "source_serials");
		String rawTargetSerials = SerialBatchArgumentType.getSerialBatch(context, "target_serials");
		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (rawSourceSerials.length() > maxInputLength) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] source_serials input is too long; max allowed length is " + maxInputLength + "."
				)
			);
			return 0;
		}
		if (rawTargetSerials.length() > maxInputLength) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] target_serials input is too long; max allowed length is " + maxInputLength + "."
				)
			);
			return 0;
		}

		OrderedBatchParseResult sourceBatch = parseOrderedBatch(source, rawSourceSerials, "triggerSource");
		OrderedBatchParseResult targetBatch = parseOrderedBatch(source, rawTargetSerials, "core");
		if (sourceBatch == null || targetBatch == null) {
			return 0;
		}

		ServerLevel level = source.getLevel();
		ServerPlayer player = source.getPlayer();
		LinkSavedData savedData = LinkSavedData.get(level);
		if (!validateSourceSerialsActive(source, savedData, sourceBatch.serials())) {
			return 0;
		}

		TargetValidationResult targetValidation = validateTargetSerialsActive(source, savedData, targetBatch.serials());
		if (targetValidation == null) {
			return 0;
		}

		LinkedHashMap<Long, Set<Long>> targetsBySource;
		try {
			targetsBySource = buildTargetsBySource(
				sourceBatch.serials(),
				targetBatch.serials(),
				mappingSpec,
				RedstoneLinkConfig.general().maxTargetsPerSetLinks()
			);
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] " + exception.getMessage()));
			return 0;
		}

		LinkedHashMap<Long, SourceApplyPlan> applyPlans = new LinkedHashMap<>();
		for (long sourceSerial : sourceBatch.serials()) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedCoresByTriggerSource(sourceSerial));
			Set<Long> nextTargets = targetsBySource.getOrDefault(sourceSerial, Set.of());
			Set<Long> affectedTargets = new HashSet<>(previousTargets);
			affectedTargets.addAll(nextTargets);
			if (
				!LinkCommandSupport.checkLinkWriteAllowed(
					source,
					level,
					LinkNodeType.TRIGGER_SOURCE,
					sourceSerial,
					affectedTargets,
					nextTargets.size()
				)
			) {
				return 0;
			}
			applyPlans.put(sourceSerial, new SourceApplyPlan(sourceSerial, previousTargets, nextTargets));
		}

		int changedSourceCount = 0;
		int addedLinks = 0;
		int removedLinks = 0;
		int changedLinks = 0;
		for (SourceApplyPlan applyPlan : applyPlans.values()) {
			int addedCount = countSetDifference(applyPlan.nextTargets(), applyPlan.previousTargets());
			int removedCount = countSetDifference(applyPlan.previousTargets(), applyPlan.nextTargets());
			int changedCount = addedCount + removedCount;
			if (changedCount <= 0) {
				continue;
			}
			LinkSetExecutionService.applyPreparedReplace(
				LinkSetExecutionService.createPreparedReplaceOperation(
					level,
					player,
					LinkNodeType.TRIGGER_SOURCE,
					applyPlan.sourceSerial(),
					LinkNodeType.CORE,
					applyPlan.previousTargets(),
					applyPlan.nextTargets(),
					List.of(),
					1
				)
			);
			changedSourceCount++;
			addedLinks += addedCount;
			removedLinks += removedCount;
			changedLinks += changedCount;
		}

		String summary = String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] link_apply mapping=%s sources=%d targets=%d changedSources=%d added=%d removed=%d changed=%d",
			mappingSpec.describe(),
			sourceBatch.serials().size(),
			targetBatch.serials().size(),
			changedSourceCount,
			addedLinks,
			removedLinks,
			changedLinks
		);
		source.sendSuccess(() -> Component.literal(summary), false);
		if (targetValidation.allowOfflineBinding() && !targetValidation.offlineTargets().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.offline_targets_saved",
					CommandTreeSupport.formatSerialList(targetValidation.offlineTargets())
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析并保留批量序号顺序。
	 */
	private static OrderedBatchParseResult parseOrderedBatch(
		CommandSourceStack source,
		String rawSerials,
		String label
	) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawSerials, MAX_BATCH_SERIALS);
		if (!parseResult.invalidEntries().isEmpty()) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid " + label + " serial tokens: " + String.join(", ", parseResult.invalidEntries())
				)
			);
			return null;
		}
		if (parseResult.exceedLimit()) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Too many " + label + " serials; max is " + MAX_BATCH_SERIALS + "."
				)
			);
			return null;
		}
		if (parseResult.orderedTargets().isEmpty()) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] " + label + " serial batch is empty."));
			return null;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.batch_serials_deduped",
					CommandTreeSupport.formatSerialCollection(parseResult.duplicateEntries())
				),
				false
			);
		}
		return new OrderedBatchParseResult(parseResult.orderedTargets());
	}

	/**
	 * 校验 source batch 中的 triggerSource 序号均为已分配且未退役。
	 */
	private static boolean validateSourceSerialsActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		List<Long> sourceSerials
	) {
		List<Long> unallocatedSources = new ArrayList<>();
		List<Long> retiredSources = new ArrayList<>();
		for (long sourceSerial : sourceSerials) {
			if (!savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, sourceSerial)) {
				unallocatedSources.add(sourceSerial);
				continue;
			}
			if (savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, sourceSerial)) {
				retiredSources.add(sourceSerial);
			}
		}
		if (!unallocatedSources.isEmpty() || !retiredSources.isEmpty()) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Bench] Invalid triggerSource serials. unallocated="
						+ CommandTreeSupport.formatSerialList(unallocatedSources)
						+ ", retired="
						+ CommandTreeSupport.formatSerialList(retiredSources)
				)
			);
			return false;
		}
		return true;
	}

	/**
	 * 校验 target batch 中的 core 序号状态。
	 */
	private static TargetValidationResult validateTargetSerialsActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		List<Long> targetSerials
	) {
		List<Long> unallocatedTargets = new ArrayList<>();
		List<Long> retiredTargets = new ArrayList<>();
		List<Long> offlineTargets = new ArrayList<>();
		for (long targetSerial : targetSerials) {
			if (!savedData.isSerialAllocated(LinkNodeType.CORE, targetSerial)) {
				unallocatedTargets.add(targetSerial);
				continue;
			}
			if (savedData.isSerialRetired(LinkNodeType.CORE, targetSerial)) {
				retiredTargets.add(targetSerial);
				continue;
			}
			if (savedData.findNode(LinkNodeType.CORE, targetSerial).isEmpty()) {
				offlineTargets.add(targetSerial);
			}
		}
		if (!unallocatedTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_unallocated",
					CommandTreeSupport.formatSerialList(unallocatedTargets)
				)
			);
			return null;
		}
		if (!retiredTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_retired",
					CommandTreeSupport.formatSerialList(retiredTargets)
				)
			);
			return null;
		}
		boolean allowOfflineBinding = RedstoneLinkConfig.general().allowOfflineTargetBinding();
		if (!allowOfflineBinding && !offlineTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.offline_targets_blocked",
					CommandTreeSupport.formatSerialList(offlineTargets)
				)
			);
			return null;
		}
		return new TargetValidationResult(allowOfflineBinding, List.copyOf(offlineTargets));
	}

	/**
	 * 统计 `left - right` 的差集元素个数。
	 */
	private static int countSetDifference(Set<Long> left, Set<Long> right) {
		if (left == null || left.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (Long serial : left) {
			if (serial != null && (right == null || !right.contains(serial))) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 将结构化规则展开为 `source -> targets` 覆盖集合。
	 */
	static LinkedHashMap<Long, Set<Long>> buildTargetsBySource(
		List<Long> sourceSerials,
		List<Long> targetSerials,
		MappingSpec mappingSpec,
		int maxTargetsPerSource
	) {
		if (mappingSpec == null) {
			throw new IllegalArgumentException("Mapping spec is required.");
		}
		LinkedHashMap<Long, Set<Long>> targetsBySource = new LinkedHashMap<>();
		for (int sourceIndex = 0; sourceIndex < sourceSerials.size(); sourceIndex++) {
			long sourceSerial = sourceSerials.get(sourceIndex);
			List<Long> resolvedTargets = resolveTargetsForSourceIndex(targetSerials, sourceIndex, mappingSpec);
			if (resolvedTargets.size() > maxTargetsPerSource) {
				throw new IllegalArgumentException(
					"Resolved target count exceeds server.maxTargetsPerSetLinks for source="
						+ sourceSerial
						+ ", count="
						+ resolvedTargets.size()
						+ ", limit="
						+ maxTargetsPerSource
				);
			}
			targetsBySource.put(sourceSerial, Set.copyOf(resolvedTargets));
		}
		return targetsBySource;
	}

	/**
	 * 解析某个 source 索引对应的目标窗口。
	 */
	static List<Long> resolveTargetsForSourceIndex(
		List<Long> orderedTargets,
		int sourceIndex,
		MappingSpec mappingSpec
	) {
		if (orderedTargets == null || orderedTargets.isEmpty()) {
			return List.of();
		}
		return switch (mappingSpec.mode()) {
			case BROADCAST_ALL -> List.copyOf(orderedTargets);
			case FAN_IN_FIRST -> List.of(orderedTargets.get(0));
			case BANDED -> resolveBandedTargetsForSourceIndex(orderedTargets, sourceIndex, mappingSpec);
		};
	}

	/**
	 * 解析 banded 规则对应的目标窗口。
	 */
	private static List<Long> resolveBandedTargetsForSourceIndex(
		List<Long> orderedTargets,
		int sourceIndex,
		MappingSpec mappingSpec
	) {
		int fanout = mappingSpec.fanout();
		if (fanout <= 0) {
			throw new IllegalArgumentException("banded mapping requires fanout > 0.");
		}
		int targetCount = orderedTargets.size();
		int startIndex = mappingSpec.offset() + (sourceIndex * mappingSpec.stride());
		if (mappingSpec.wrap()) {
			startIndex = Math.floorMod(startIndex, targetCount);
		} else if (startIndex >= targetCount) {
			return List.of();
		}

		LinkedHashSet<Long> resolvedTargets = new LinkedHashSet<>();
		for (int windowOffset = 0; windowOffset < fanout; windowOffset++) {
			int targetIndex = startIndex + windowOffset;
			if (mappingSpec.wrap()) {
				targetIndex = Math.floorMod(targetIndex, targetCount);
			} else if (targetIndex >= targetCount) {
				break;
			}
			resolvedTargets.add(orderedTargets.get(targetIndex));
		}
		return List.copyOf(resolvedTargets);
	}

	/**
	 * 结构化规则描述。
	 */
	record MappingSpec(
		MappingMode mode,
		int fanout,
		int stride,
		int offset,
		boolean wrap
	) {
		static MappingSpec broadcastAll() {
			return new MappingSpec(MappingMode.BROADCAST_ALL, 0, 0, 0, true);
		}

		static MappingSpec fanInFirst() {
			return new MappingSpec(MappingMode.FAN_IN_FIRST, 0, 0, 0, true);
		}

		static MappingSpec banded(int fanout, int stride, int offset, boolean wrap) {
			return new MappingSpec(MappingMode.BANDED, fanout, stride, offset, wrap);
		}

		String describe() {
			if (mode == MappingMode.BANDED) {
				return "banded(fanout=%d,stride=%d,offset=%d,wrap=%s)".formatted(fanout, stride, offset, wrap);
			}
			return mode.serializedName();
		}
	}

	/**
	 * 结构化规则类型。
	 */
	enum MappingMode {
		BROADCAST_ALL("broadcast_all"),
		FAN_IN_FIRST("fan_in_first"),
		BANDED("banded");

		private final String serializedName;

		MappingMode(String serializedName) {
			this.serializedName = serializedName;
		}

		String serializedName() {
			return serializedName;
		}
	}

	/**
	 * 解析后的有序 batch。
	 */
	private record OrderedBatchParseResult(List<Long> serials) {}

	/**
	 * target 校验摘要。
	 */
	private record TargetValidationResult(boolean allowOfflineBinding, List<Long> offlineTargets) {}

	/**
	 * 单个 source 的覆盖写入计划。
	 */
	private record SourceApplyPlan(long sourceSerial, Set<Long> previousTargets, Set<Long> nextTargets) {}
}
