package com.makomi.command.link;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.CommandTreeSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkWriteControlService;
import com.makomi.util.SerialParseUtil;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * `link` 命令共享支撑工具。
 * <p>
 * 仅承载 link 模块内部复用的命令层辅助能力，不扩散到全局 support。
 * </p>
 */
public final class LinkCommandSupport {
	private LinkCommandSupport() {
	}

	/**
	 * 解析序列号列表文本（`/` 分隔，支持 `N` 与 `A:B`）。
	 */
	static TargetParseResult parseTargetSerials(String rawText, int maxTargetCount) {
		SerialParseUtil.TargetParseResult parsed = SerialParseUtil.parseTargets(rawText, maxTargetCount);
		return new TargetParseResult(
			parsed.targets(),
			parsed.invalidEntries(),
			parsed.duplicateEntries(),
			parsed.exceedLimit()
		);
	}

	/**
	 * 统一执行写入控制判定并返回是否允许继续写入。
	 */
	public static boolean checkLinkWriteAllowed(
		CommandSourceStack source,
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> affectedTargets,
		int setSize
	) {
		return checkLinkWriteAllowed(source, level, sourceType, sourceSerial, affectedTargets, setSize, false);
	}

	/**
	 * 统一执行写入控制判定并返回是否允许继续写入。
	 * <p>
	 * 当 bypassLimitedSetSize=true 时，仅跳过 limited 模式的“最大设置量”限制，
	 * 仍保留 readonly 与 protected 两类控制。
	 * </p>
	 */
	public static boolean checkLinkWriteAllowed(
		CommandSourceStack source,
		ServerLevel level,
		LinkNodeType sourceType,
		long sourceSerial,
		Set<Long> affectedTargets,
		int setSize,
		boolean bypassLimitedSetSize
	) {
		LinkWriteControlService.WriteDecision decision = LinkWriteControlService.evaluate(
			level,
			sourceType,
			sourceSerial,
			affectedTargets,
			setSize,
			bypassLimitedSetSize
				|| com.makomi.command.CommandPermissionCompat.hasPermission(source, RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			com.makomi.command.CommandPermissionCompat.hasPermission(source, RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
		if (decision.allowed()) {
			return true;
		}
		LinkWriteControlService.DenyReason denyReason = decision.denyReason();
		if (denyReason == LinkWriteControlService.DenyReason.LIMITED_MAX_SET_SIZE) {
			source.sendFailure(Component.translatable("message.redstonelink.permission.insufficient"));
			return false;
		}
		if (denyReason == LinkWriteControlService.DenyReason.PROTECTED_SERIAL) {
			source.sendFailure(Component.translatable("message.redstonelink.permission.insufficient"));
			return false;
		}
		source.sendFailure(Component.translatable("message.redstonelink.write_control.deny.readonly"));
		return false;
	}

	/**
	 * 校验受控名单命令中的序号是否处于激活状态。
	 */
	static boolean validateWriteProtectedActiveSerial(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType type,
		long serial
	) {
		if (isWriteProtectedActiveSerial(savedData, type, serial)) {
			return true;
		}
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.write_control.protected.invalid_serial",
				CommandTreeSupport.typeCommandName(type),
				serial
			)
		);
		return false;
	}

	/**
	 * 判断受控名单命令输入的序号是否为已分配且未退役。
	 */
	static boolean isWriteProtectedActiveSerial(LinkSavedData savedData, LinkNodeType type, long serial) {
		return savedData != null
			&& type != null
			&& serial > 0L
			&& savedData.isSerialAllocated(type, serial)
			&& !savedData.isSerialRetired(type, serial);
	}

	/**
	 * 同步玩家背包中指定节点序号的物品链接快照。
	 */
	public static void syncPlayerItemLinkSnapshot(
		ServerPlayer player,
		LinkNodeType nodeType,
		long serial
	) {
		syncPlayerItemLinkSnapshots(player, nodeType, Set.of(serial));
	}

	/**
	 * 同步玩家背包中一组节点序号对应的物品链接快照。
	 * <p>
	 * 该入口统一覆盖主背包、副手、盔甲栏与当前拖拽物品，
	 * 并在命中任一变更后只广播一次容器更新，避免 tooltip 继续展示旧缓存。
	 * </p>
	 */
	public static void syncPlayerItemLinkSnapshots(
		ServerPlayer player,
		LinkNodeType nodeType,
		Set<Long> serials
	) {
		Set<Long> normalizedSerials = normalizePositiveSerials(serials);
		if (player == null || nodeType == null || normalizedSerials.isEmpty()) {
			return;
		}
		boolean changed = syncPlayerInventoryLinkSnapshots(player, nodeType, normalizedSerials);
		if (changed) {
			player.containerMenu.broadcastChanges();
		}
	}

	/**
	 * 同步某一批受影响节点序号对应的玩家物品链接快照。
	 */
	public static void syncAffectedPlayerItemLinkSnapshots(
		ServerPlayer player,
		LinkNodeType nodeType,
		Set<Long> previousSerials,
		Set<Long> currentSerials
	) {
		if (player == null || nodeType == null) {
			return;
		}
		Set<Long> affectedSerials = new HashSet<>();
		if (previousSerials != null) {
			affectedSerials.addAll(previousSerials);
		}
		if (currentSerials != null) {
			affectedSerials.addAll(currentSerials);
		}
		syncPlayerItemLinkSnapshots(player, nodeType, affectedSerials);
	}

	/**
	 * 刷新玩家各容器段中命中的节点物品快照。
	 */
	private static boolean syncPlayerInventoryLinkSnapshots(
		ServerPlayer player,
		LinkNodeType nodeType,
		Set<Long> serials
	) {
		if (player == null || nodeType == null || serials == null || serials.isEmpty()) {
			return false;
		}
		ServerLevel level = player.level();
		Inventory inventory = player.getInventory();
		boolean changed = false;
		changed |= syncInventoryLinkSnapshots(level, inventory, nodeType, serials);
		changed |= syncSingleItemLinkSnapshot(level, player.containerMenu.getCarried(), nodeType, serials);
		return changed;
	}

	private static boolean syncInventoryLinkSnapshots(
		ServerLevel level,
		Inventory inventory,
		LinkNodeType nodeType,
		Set<Long> serials
	) {
		if (level == null || inventory == null || nodeType == null || serials == null || serials.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (int slotIndex = 0; slotIndex < inventory.getContainerSize(); slotIndex++) {
			changed |= syncSingleItemLinkSnapshot(level, inventory.getItem(slotIndex), nodeType, serials);
		}
		return changed;
	}

	/**
	 * 刷新物品列表中命中的节点物品快照。
	 * <p>
	 * 该 helper 暴露给同包测试复用，避免测试环境依赖完整玩家实例。
	 * </p>
	 */
	static boolean syncItemListLinkSnapshots(
		ServerLevel level,
		List<ItemStack> stacks,
		LinkNodeType nodeType,
		Set<Long> serials
	) {
		if (level == null || stacks == null || stacks.isEmpty() || nodeType == null || serials == null || serials.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (ItemStack stack : stacks) {
			changed |= syncSingleItemLinkSnapshot(level, stack, nodeType, serials);
		}
		return changed;
	}

	/**
	 * 刷新单个命中物品的链接快照。
	 */
	static boolean syncSingleItemLinkSnapshot(
		ServerLevel level,
		ItemStack stack,
		LinkNodeType nodeType,
		Set<Long> serials
	) {
		if (!matchesNodeItem(stack, nodeType, serials)) {
			return false;
		}
		List<Long> beforeLinks = LinkItemData.getLinkedSerials(stack);
		String beforeAlias = LinkItemData.getDisplayAlias(stack);
		long beforeChannel = LinkItemData.getChannel(stack);
		LinkItemData.syncCurrentLinksSnapshotIfSingle(stack, level);
		return !beforeLinks.equals(LinkItemData.getLinkedSerials(stack))
			|| !beforeAlias.equals(LinkItemData.getDisplayAlias(stack))
			|| beforeChannel != LinkItemData.getChannel(stack);
	}

	/**
	 * 判断当前物品是否命中本次要刷新的节点集合。
	 */
	private static boolean matchesNodeItem(
		ItemStack stack,
		LinkNodeType nodeType,
		Set<Long> serials
	) {
		if (stack == null || stack.isEmpty() || nodeType == null || serials == null || serials.isEmpty()) {
			return false;
		}
		if (LinkItemData.getSerialCount(stack) != 1) {
			return false;
		}
		long serial = LinkItemData.getSerial(stack);
		if (!serials.contains(serial)) {
			return false;
		}
		return LinkItemData.getNodeType(stack).orElse(null) == nodeType;
	}

	/**
	 * 归一化序号集合：仅保留正数并去重。
	 */
	private static Set<Long> normalizePositiveSerials(Set<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return Set.of();
		}
		Set<Long> normalizedSerials = new HashSet<>();
		for (Long serial : serials) {
			if (serial != null && serial > 0L) {
				normalizedSerials.add(serial);
			}
		}
		return normalizedSerials.isEmpty() ? Set.of() : Set.copyOf(normalizedSerials);
	}

	/**
	 * 同步受影响节点（来源 + 目标集合）的客户端链接快照。
	 */
	public static void syncAffectedNodeLinkSnapshots(
		ServerLevel sourceLevel,
		LinkNodeType targetType,
		Set<Long> previousTargets,
		Set<Long> currentTargets
	) {
		Set<Long> affectedTargets = new HashSet<>();
		if (previousTargets != null) {
			affectedTargets.addAll(previousTargets);
		}
		if (currentTargets != null) {
			affectedTargets.addAll(currentTargets);
		}
		for (long targetSerial : affectedTargets) {
			syncNodeLinkSnapshot(sourceLevel, targetType, targetSerial);
		}
	}

	/**
	 * 批量写入后置同步收集器。
	 * <p>
	 * 用于把“逐条写入后的即时客户端同步”收敛成批后按唯一节点/唯一来源一次性 flush，
	 * 避免热点批量场景下对同一节点重复 `forceSyncToClient()`。
	 * </p>
	 */
	public static final class BatchLinkSnapshotSyncCollector {
		private final ServerLevel sourceLevel;
		private final Set<NodeSnapshotSyncRequest> nodeSnapshotSyncRequests = new HashSet<>();
		private final Set<PlayerItemSnapshotSyncRequest> playerItemSnapshotSyncRequests = new HashSet<>();

		public BatchLinkSnapshotSyncCollector(ServerLevel sourceLevel) {
			this.sourceLevel = sourceLevel;
		}

		/**
		 * 收集一次 prepared replace 产生的后置同步需求。
		 */
		public void collectPreparedReplace(LinkSetExecutionService.PreparedReplaceOperation operation) {
			if (operation == null) {
				return;
			}
			collectAffectedNodeLinkSnapshots(operation.targetType(), operation.previousTargets(), operation.targets());
			collectAffectedPlayerItemLinkSnapshots(
				operation.player(),
				operation.targetType(),
				operation.previousTargets(),
				operation.targets()
			);
			collectPlayerItemLinkSnapshot(operation.player(), operation.sourceType(), operation.sourceSerial());
		}

		/**
		 * 收集一次节点物品快照刷新需求。
		 */
		public void collectPlayerItemLinkSnapshot(
			ServerPlayer player,
			LinkNodeType nodeType,
			long serial
		) {
			if (player == null || nodeType == null || serial <= 0L) {
				return;
			}
			playerItemSnapshotSyncRequests.add(new PlayerItemSnapshotSyncRequest(player, nodeType, serial));
		}

		/**
		 * 收集一次受影响节点集合对应的物品快照刷新需求。
		 */
		public void collectAffectedPlayerItemLinkSnapshots(
			ServerPlayer player,
			LinkNodeType nodeType,
			Set<Long> previousSerials,
			Set<Long> currentSerials
		) {
			if (player == null || nodeType == null) {
				return;
			}
			collectItemSnapshotSerials(player, nodeType, previousSerials);
			collectItemSnapshotSerials(player, nodeType, currentSerials);
		}

		/**
		 * 收集一次受影响目标节点同步需求。
		 */
		public void collectAffectedNodeLinkSnapshots(
			LinkNodeType targetType,
			Set<Long> previousTargets,
			Set<Long> currentTargets
		) {
			if (targetType == null) {
				return;
			}
			collectTargetSerials(targetType, previousTargets);
			collectTargetSerials(targetType, currentTargets);
		}

		private void collectTargetSerials(LinkNodeType targetType, Set<Long> targetSerials) {
			if (targetSerials == null || targetSerials.isEmpty()) {
				return;
			}
			for (Long targetSerial : targetSerials) {
				if (targetSerial != null && targetSerial > 0L) {
					nodeSnapshotSyncRequests.add(new NodeSnapshotSyncRequest(targetType, targetSerial));
				}
			}
		}

		private void collectItemSnapshotSerials(ServerPlayer player, LinkNodeType nodeType, Set<Long> serials) {
			if (serials == null || serials.isEmpty()) {
				return;
			}
			for (Long serial : serials) {
				if (serial != null && serial > 0L) {
					playerItemSnapshotSyncRequests.add(new PlayerItemSnapshotSyncRequest(player, nodeType, serial));
				}
			}
		}

		/**
		 * 执行一次批量 flush，并在结束后自动清空收集器。
		 */
		public void flush() {
			if (sourceLevel != null) {
				for (NodeSnapshotSyncRequest request : nodeSnapshotSyncRequests) {
					syncNodeLinkSnapshot(sourceLevel, request.nodeType(), request.serial());
				}
			}
			Map<PlayerItemSnapshotSyncGroupKey, Set<Long>> serialsByGroup = new LinkedHashMap<>();
			for (PlayerItemSnapshotSyncRequest request : playerItemSnapshotSyncRequests) {
				PlayerItemSnapshotSyncGroupKey groupKey = new PlayerItemSnapshotSyncGroupKey(request.player(), request.nodeType());
				serialsByGroup.computeIfAbsent(groupKey, ignored -> new HashSet<>()).add(request.serial());
			}
			for (Map.Entry<PlayerItemSnapshotSyncGroupKey, Set<Long>> entry : serialsByGroup.entrySet()) {
				syncPlayerItemLinkSnapshots(entry.getKey().player(), entry.getKey().nodeType(), entry.getValue());
			}
			clear();
		}

		/**
		 * 清空当前已登记的同步请求。
		 */
		public void clear() {
			nodeSnapshotSyncRequests.clear();
			playerItemSnapshotSyncRequests.clear();
		}
	}

	/**
	 * 按类型+序号定位在线节点，并触发一次方块实体客户端同步。
	 */
	private static void syncNodeLinkSnapshot(ServerLevel sourceLevel, LinkNodeType nodeType, long serial) {
		if (sourceLevel == null || nodeType == null || serial <= 0L) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		savedData.findNode(nodeType, serial).ifPresent(node -> {
			ServerLevel nodeLevel = sourceLevel.getServer().getLevel(node.dimension());
			if (nodeLevel == null || !nodeLevel.isLoaded(node.pos())) {
				return;
			}
			BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
			if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
				pairableNodeBlockEntity.forceSyncToClient();
			}
		});
	}

	/**
	 * 唯一化后的节点同步请求。
	 */
	private record NodeSnapshotSyncRequest(LinkNodeType nodeType, long serial) {
	}

	/**
	 * 唯一化后的玩家物品快照刷新请求。
	 */
	private record PlayerItemSnapshotSyncRequest(ServerPlayer player, LinkNodeType nodeType, long serial) {
	}

	/**
	 * 玩家物品快照批量刷新的分组键。
	 */
	private record PlayerItemSnapshotSyncGroupKey(ServerPlayer player, LinkNodeType nodeType) {
	}

	/**
	 * link 命令的目标序列号解析结果。
	 */
	record TargetParseResult(
		Set<Long> targets,
		List<String> invalidEntries,
		List<Long> duplicateEntries,
		boolean exceedLimit
	) {}
}
