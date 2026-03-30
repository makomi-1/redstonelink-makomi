package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.link.LinkCommandSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SerialParseUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 快速连接工具服务端应用服务。
 * <p>
 * 该服务统一承接“左键把缓存应用到命中节点”的真实写入逻辑，
 * 严格保持 `triggerSource -> core` 单向写入约束。
 * </p>
 */
public final class QuickLinkApplyService {
	private static final int MAX_CACHED_TRIGGER_SOURCES = 4096;

	private QuickLinkApplyService() {
	}

	/**
	 * 将当前工具缓存应用到命中节点。
	 */
	public static ApplyResult apply(ServerPlayer player, ServerLevel level, BlockPos blockPos, ItemStack stack) {
		if (player == null || level == null || blockPos == null || stack == null || stack.isEmpty()) {
			return ApplyResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(stack);
		if (snapshot.mode() == QuickLinkToolData.Mode.CHANNEL) {
			return ApplyResult.failure("message.redstonelink.quick_link.apply.channel_unavailable");
		}
		if (snapshot.serialCacheExpression().isBlank()) {
			return ApplyResult.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}

		int maxInputLength = RedstoneLinkConfig.command().linkSetMaxInputLength();
		if (snapshot.serialCacheExpression().length() > maxInputLength) {
			return ApplyResult.failure("message.redstonelink.link.set.input_too_long", Integer.toString(maxInputLength));
		}

		BlockEntity blockEntity = level.getBlockEntity(blockPos);
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return ApplyResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		LinkNodeType targetNodeType = pairableNodeBlockEntity.getLinkNodeType();
		if (targetNodeType == null || pairableNodeBlockEntity.getSerial() <= 0L) {
			return ApplyResult.failure("message.redstonelink.quick_link.apply.invalid_target");
		}

		LinkNodeType cacheType = snapshot.serialCacheType();
		if (cacheType == targetNodeType) {
			return ApplyResult.failure(
				"message.redstonelink.quick_link.apply.invalid_target_type",
				LinkNodeSemantics.toSemanticName(cacheType),
				LinkNodeSemantics.toSemanticName(targetNodeType)
			);
		}

		return cacheType == LinkNodeType.CORE
			? applyCachedCoresToTriggerSource(player, level, pairableNodeBlockEntity.getSerial(), snapshot.serialCacheExpression())
			: applyCachedTriggerSourcesToCore(player, level, pairableNodeBlockEntity.getSerial(), snapshot.serialCacheExpression());
	}

	/**
	 * 将缓存的 core 集合覆盖写入当前 triggerSource。
	 */
	private static ApplyResult applyCachedCoresToTriggerSource(
		ServerPlayer player,
		ServerLevel level,
		long triggerSourceSerial,
		String rawExpression
	) {
		int maxTargets = RedstoneLinkConfig.general().maxTargetsPerSetLinks();
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawExpression, maxTargets);
		if (!parseResult.invalidEntries().isEmpty()) {
			return ApplyResult.failure(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return ApplyResult.failure("message.redstonelink.too_many_targets", Integer.toString(maxTargets));
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		for (long coreSerial : parseResult.orderedTargets()) {
			if (!savedData.isSerialAllocated(LinkNodeType.CORE, coreSerial)) {
				return ApplyResult.failure("message.redstonelink.target_serial_unallocated", Long.toString(coreSerial));
			}
			if (savedData.isSerialRetired(LinkNodeType.CORE, coreSerial)) {
				return ApplyResult.failure("message.redstonelink.target_serial_retired", Long.toString(coreSerial));
			}
			if (!RedstoneLinkConfig.general().allowOfflineTargetBinding() && savedData.findNode(LinkNodeType.CORE, coreSerial).isEmpty()) {
				return ApplyResult.failure("message.redstonelink.offline_targets_blocked", Long.toString(coreSerial));
			}
		}

		Set<Long> nextTargets = new HashSet<>(parseResult.orderedTargets());
		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
		Set<Long> affectedTargets = new HashSet<>(previousTargets);
		affectedTargets.addAll(nextTargets);

		LinkWriteControlService.WriteDecision writeDecision = LinkWriteControlService.evaluateForPlayer(
			player,
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			affectedTargets,
			nextTargets.size()
		);
		if (!writeDecision.allowed()) {
			return failureFromWriteDecision(writeDecision);
		}

		LinkSavedData.ReplaceLinksResult replaceResult = savedData.replaceLinksBySourceType(
			LinkNodeType.TRIGGER_SOURCE,
			triggerSourceSerial,
			nextTargets
		);
		publishAndSync(level, player, triggerSourceSerial, previousTargets, nextTargets);
		return ApplyResult.success(
			"message.redstonelink.quick_link.apply.done.trigger_source",
			Long.toString(triggerSourceSerial),
			Integer.toString(replaceResult.currentCount())
		);
	}

	/**
	 * 将缓存的 triggerSource 集合逐个覆盖为“仅连接当前 core”。
	 */
	private static ApplyResult applyCachedTriggerSourcesToCore(
		ServerPlayer player,
		ServerLevel level,
		long coreSerial,
		String rawExpression
	) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			rawExpression,
			MAX_CACHED_TRIGGER_SOURCES
		);
		if (!parseResult.invalidEntries().isEmpty()) {
			return ApplyResult.failure(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
		}
		if (parseResult.exceedLimit()) {
			return ApplyResult.failure(
				"message.redstonelink.quick_link.apply.too_many_sources",
				Integer.toString(MAX_CACHED_TRIGGER_SOURCES)
			);
		}
		if (parseResult.orderedTargets().isEmpty()) {
			return ApplyResult.failure("message.redstonelink.quick_link.apply.empty_serial_cache");
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			if (!savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)) {
				return ApplyResult.failure("message.redstonelink.source_serial_unallocated", Long.toString(triggerSourceSerial));
			}
			if (savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial)) {
				return ApplyResult.failure("message.redstonelink.source_serial_retired", Long.toString(triggerSourceSerial));
			}
		}

		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
			Set<Long> nextTargets = Set.of(coreSerial);
			Set<Long> affectedTargets = new HashSet<>(previousTargets);
			affectedTargets.add(coreSerial);
			LinkWriteControlService.WriteDecision writeDecision = LinkWriteControlService.evaluateForPlayer(
				player,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				affectedTargets,
				1
			);
			if (!writeDecision.allowed()) {
				return failureFromWriteDecision(writeDecision);
			}
		}

		int appliedSourceCount = 0;
		for (long triggerSourceSerial : parseResult.orderedTargets()) {
			Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
			Set<Long> nextTargets = Set.of(coreSerial);
			savedData.replaceLinksBySourceType(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial, nextTargets);
			publishAndSync(level, player, triggerSourceSerial, previousTargets, nextTargets);
			appliedSourceCount++;
		}

		return ApplyResult.success(
			"message.redstonelink.quick_link.apply.done.core",
			Integer.toString(appliedSourceCount),
			Long.toString(coreSerial)
		);
	}

	/**
	 * 复用现有 delta 发布与节点/物品快照同步闭环。
	 */
	private static void publishAndSync(
		ServerLevel level,
		ServerPlayer player,
		long triggerSourceSerial,
		Set<Long> previousTargets,
		Set<Long> nextTargets
	) {
		ActivatableTargetBlockEntity.EventMeta eventMeta = ActivatableTargetBlockEntity.EventMeta.of(level.getGameTime(), 0, 0L);

		Set<Long> addedTargets = new HashSet<>(nextTargets);
		addedTargets.removeAll(previousTargets);
		if (!addedTargets.isEmpty()) {
			InternalDispatchDeltaEvents.publishLinkAttached(
				level,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				addedTargets,
				eventMeta
			);
		}

		Set<Long> removedTargets = new HashSet<>(previousTargets);
		removedTargets.removeAll(nextTargets);
		if (!removedTargets.isEmpty()) {
			InternalDispatchDeltaEvents.publishLinkDetached(
				level,
				LinkNodeType.TRIGGER_SOURCE,
				triggerSourceSerial,
				removedTargets,
				eventMeta
			);
		}

		LinkCommandSupport.syncAffectedNodeLinkSnapshots(level, LinkNodeType.CORE, previousTargets, nextTargets);
		LinkCommandSupport.syncPlayerItemLinkSnapshot(player, LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial);
	}

	/**
	 * 将写控判定映射为统一前端提示。
	 */
	private static ApplyResult failureFromWriteDecision(LinkWriteControlService.WriteDecision writeDecision) {
		if (writeDecision.denyReason() == LinkWriteControlService.DenyReason.READONLY) {
			return ApplyResult.failure("message.redstonelink.write_control.deny.readonly");
		}
		return ApplyResult.failure("message.redstonelink.permission.insufficient");
	}

	/**
	 * 快速连接工具应用结果。
	 */
	public record ApplyResult(boolean success, String messageKey, List<String> messageArgs) {
		/**
		 * 构建失败结果。
		 */
		public static ApplyResult failure(String messageKey, String... messageArgs) {
			return new ApplyResult(false, messageKey, List.of(messageArgs));
		}

		/**
		 * 构建成功结果。
		 */
		public static ApplyResult success(String messageKey, String... messageArgs) {
			return new ApplyResult(true, messageKey, List.of(messageArgs));
		}
	}
}
