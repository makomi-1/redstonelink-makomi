package com.makomi.command.link;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.CommandTreeSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkWriteControlService;
import com.makomi.data.NodeSnapshotQueryService;
import com.makomi.util.SerialParseUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * `link` 命令共享支撑工具。
 * <p>
 * 仅承载 link 模块内部复用的命令层辅助能力，不扩散到全局 support。
 * </p>
 */
final class LinkCommandSupport {
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
	static boolean checkLinkWriteAllowed(
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
	static boolean checkLinkWriteAllowed(
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
			bypassLimitedSetSize || source.hasPermission(RedstoneLinkConfig.linkWriteLimitedPermissionLevel()),
			source.hasPermission(RedstoneLinkConfig.linkWriteProtectedPermissionLevel())
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
	 * 同步玩家背包中同序列号物品的链接快照。
	 */
	static void syncPlayerItemLinkSnapshot(
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		if (player == null || sourceType == null || sourceSerial <= 0L) {
			return;
		}
		Set<Long> snapshotTargets = NodeSnapshotQueryService
			.queryItemSnapshotLinks(player.serverLevel(), sourceType, sourceSerial)
			.visibleTargetSet();

		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (LinkItemData.getSerial(stack) != sourceSerial) {
				continue;
			}
			if (LinkItemData.getNodeType(stack).orElse(null) != sourceType) {
				continue;
			}
			LinkItemData.setLinkedSerials(stack, snapshotTargets);
		}
	}

	/**
	 * 同步受影响节点（来源 + 目标集合）的客户端链接快照。
	 */
	static void syncAffectedNodeLinkSnapshots(
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
	 * link 命令的目标序列号解析结果。
	 */
	record TargetParseResult(
		Set<Long> targets,
		List<String> invalidEntries,
		List<Long> duplicateEntries,
		boolean exceedLimit
	) {}
}
