package com.makomi.command;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.compat.LithiumCompatHealth;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.item.PairableItem;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * RedstoneLink 服务端命令入口。
 * <p>
 * 提供手持/节点配对、批量覆盖链接、审计信息与节点退役等运维能力。
 * </p>
 */
public final class ModCommands {
	private static final int PLACE_BLOCK_FLAGS = 2;
	private static final long PLACE_CONFIRM_TIMEOUT_MILLIS = 30_000L;
	private static final Map<UUID, PendingPlacement> PENDING_PLACE_BY_PLAYER = new HashMap<>();

	private ModCommands() {
	}

	/**
	 * 注册 /redstonelink 命令树。
	 */
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands
				.literal("redstonelink")
				.then(
					Commands
						.literal("pair")
						.then(
							Commands.literal("main").then(
								Commands.argument("serial", LongArgumentType.longArg(0L)).executes(
									context -> executePairByHand(context, InteractionHand.MAIN_HAND)
								)
							)
						)
						.then(
							Commands.literal("off").then(
								Commands.argument("serial", LongArgumentType.longArg(0L)).executes(
									context -> executePairByHand(context, InteractionHand.OFF_HAND)
								)
							)
						)
				)
				.then(
					Commands
						.literal("pair_node")
						.then(
							Commands.literal("button").then(
								Commands.argument("source_serial", LongArgumentType.longArg(1L)).then(
									Commands.argument("target_serial", LongArgumentType.longArg(0L)).executes(
										context -> executePairByNode(context, LinkNodeType.BUTTON)
									)
								)
							)
						)
				)
				.then(
					Commands
						.literal("set_links")
						.then(
							Commands.literal("button").then(
								Commands.argument("source_serial", LongArgumentType.longArg(1L))
									.executes(context -> executeSetLinks(context, LinkNodeType.BUTTON, false))
									.then(
										Commands.argument("targets", StringArgumentType.greedyString()).executes(
											context -> executeSetLinks(context, LinkNodeType.BUTTON, true)
										)
									)
							)
						)
						.then(
							Commands.literal("core").then(
								Commands.argument("source_serial", LongArgumentType.longArg(1L))
									.executes(context -> executeSetLinks(context, LinkNodeType.CORE, false))
									.then(
										Commands.argument("targets", StringArgumentType.greedyString()).executes(
											context -> executeSetLinks(context, LinkNodeType.CORE, true)
										)
									)
							)
						)
				)
				.then(
					Commands
						.literal("place")
						.then(
							Commands
								.literal("setblock")
								.then(
									Commands.argument("pos", BlockPosArgument.blockPos()).then(
										Commands
											.argument("block", BlockStateArgument.block(registryAccess))
											.executes(ModCommands::executePlaceSetBlock)
									)
								)
						)
						.then(
							Commands
								.literal("fill")
								.then(
									Commands.argument("from", BlockPosArgument.blockPos()).then(
										Commands.argument("to", BlockPosArgument.blockPos()).then(
											Commands
												.argument("block", BlockStateArgument.block(registryAccess))
												.executes(ModCommands::executePlaceFill)
										)
									)
								)
						)
						.then(Commands.literal("confirm").executes(ModCommands::executePlaceConfirm))
				)
				.then(Commands.literal("audit").executes(ModCommands::executeAudit))
				.then(
					Commands
						.literal("diag")
						.then(Commands.literal("lithium").executes(ModCommands::executeLithiumDiag))
				)
				.then(
					Commands
						.literal("retire")
						.then(
							Commands.literal("core").then(
								Commands.argument("serial", LongArgumentType.longArg(1L))
									.executes(context -> executeRetireRequireConfirm(context, LinkNodeType.CORE))
									.then(
										Commands
											.literal("confirm")
											.executes(context -> executeRetire(context, LinkNodeType.CORE))
									)
							)
						)
						.then(
							Commands.literal("button").then(
								Commands.argument("serial", LongArgumentType.longArg(1L))
									.executes(context -> executeRetireRequireConfirm(context, LinkNodeType.BUTTON))
									.then(
										Commands
											.literal("confirm")
											.executes(context -> executeRetire(context, LinkNodeType.BUTTON))
									)
							)
						)
				)
		));
	}

	/**
	 * 使用手持可配对物执行单目标切换配对。
	 */
	private static int executePairByHand(CommandContext<CommandSourceStack> context, InteractionHand hand) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		ItemStack heldStack = player.getItemInHand(hand);
		if (!(heldStack.getItem() instanceof PairableItem pairableItem)) {
			source.sendFailure(Component.translatable("message.redstonelink.not_pairable"));
			return 0;
		}

		LinkNodeType selfType = pairableItem.getNodeType();
		if (selfType != LinkNodeType.BUTTON) {
			source.sendFailure(Component.translatable("message.redstonelink.button_source_only"));
			return 0;
		}

		long selfSerial = LinkItemData.ensureSerial(heldStack, player.serverLevel(), selfType);
		long targetSerial = LongArgumentType.getLong(context, "serial");

		int result = executeLinkUpdate(source, player, selfType, selfSerial, targetSerial, false, false);
		if (result == Command.SINGLE_SUCCESS) {
			LinkItemData.setPairSerial(heldStack, targetSerial);
		}
		return result;
	}

	/**
	 * 以节点序列号为源执行单目标切换配对。
	 */
	private static int executePairByNode(CommandContext<CommandSourceStack> context, LinkNodeType sourceType) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}
		if (sourceType != LinkNodeType.BUTTON) {
			source.sendFailure(Component.translatable("message.redstonelink.button_source_only"));
			return 0;
		}

		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		long targetSerial = LongArgumentType.getLong(context, "target_serial");
		int result = executeLinkUpdate(source, player, sourceType, sourceSerial, targetSerial, true, true);
		if (result == Command.SINGLE_SUCCESS) {
			updateNodeLastTargetSerial(player.serverLevel(), sourceType, sourceSerial, targetSerial);
		}
		return result;
	}

	/**
	 * 单目标链接更新核心流程（添加/移除/清空）。
	 */
	private static int executeLinkUpdate(
		CommandSourceStack source,
		ServerPlayer player,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial,
		boolean requireSourceExists,
		boolean requireTargetExists
	) {
		LinkSavedData savedData = LinkSavedData.get(player.serverLevel());
		if (!validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}

		if (requireSourceExists && savedData.findNode(sourceType, sourceSerial).isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.source_not_found", sourceSerial));
			return 0;
		}

		if (targetSerial <= 0L) {
			int removed = savedData.clearLinksForNode(sourceType, sourceSerial);
			syncPlayerItemLinkSnapshot(player, savedData, sourceType, sourceSerial);
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.links_cleared", removed),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		LinkNodeType targetType = sourceType == LinkNodeType.BUTTON ? LinkNodeType.CORE : LinkNodeType.BUTTON;
		if (!validateTargetSerialActive(source, savedData, targetType, targetSerial)) {
			return 0;
		}
		boolean targetOffline = savedData.findNode(targetType, targetSerial).isEmpty();
		if (requireTargetExists && targetOffline) {
			source.sendFailure(Component.translatable("message.redstonelink.target_not_found", targetSerial));
			return 0;
		}
		if (!requireTargetExists && targetOffline && !RedstoneLinkConfig.allowOfflineTargetBinding()) {
			source.sendFailure(Component.translatable("message.redstonelink.offline_targets_blocked", Long.toString(targetSerial)));
			return 0;
		}

		boolean linkedNow;
		if (sourceType == LinkNodeType.BUTTON) {
			linkedNow = savedData.toggleLink(sourceSerial, targetSerial);
		} else {
			linkedNow = savedData.toggleLink(targetSerial, sourceSerial);
		}

		if (linkedNow) {
			source.sendSuccess(() -> Component.translatable("message.redstonelink.link_added"), false);
		} else {
			source.sendSuccess(() -> Component.translatable("message.redstonelink.link_removed"), false);
		}
		syncPlayerItemLinkSnapshot(player, savedData, sourceType, sourceSerial);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 输出当前链路审计信息到命令反馈。
	 */
	/**
	 * 自定义 setblock：放置可配对节点，必要时触发非空气替换确认。
	 */
	private static int executePlaceSetBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		ServerLevel level = player.serverLevel();
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
		BlockInput input = BlockStateArgument.getBlock(context, "block");
		if (!validatePairablePlacementInput(source, input, pos)) {
			return 0;
		}

		int replaceCount = countPotentialReplaceNonAir(level, pos, pos, input);
		PendingPlacement pendingPlacement = new PendingPlacement(
			PlacementMode.SETBLOCK,
			level.dimension(),
			pos,
			pos,
			input,
			replaceCount,
			System.currentTimeMillis()
		);
		if (replaceCount > 0) {
			PENDING_PLACE_BY_PLAYER.put(player.getUUID(), pendingPlacement);
			source.sendFailure(Component.translatable("message.redstonelink.place.replace_confirm", replaceCount));
			return 0;
		}

		return executePlacementNow(source, player, pendingPlacement);
	}

	/**
	 * 自定义 fill：批量放置可配对节点，替换非空气时要求二次确认。
	 */
	private static int executePlaceFill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		ServerLevel level = player.serverLevel();
		BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
		BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");
		BlockPos min = minPos(from, to);
		BlockPos max = maxPos(from, to);
		BlockInput input = BlockStateArgument.getBlock(context, "block");
		if (!validatePairablePlacementInput(source, input, min)) {
			return 0;
		}

		long volume = blockVolume(min, max);
		int limit = level.getGameRules().getInt(GameRules.RULE_COMMAND_MODIFICATION_BLOCK_LIMIT);
		if (volume > limit) {
			source.sendFailure(Component.translatable("message.redstonelink.place.volume_exceeded", volume, limit));
			return 0;
		}
		if (!isPlacementAreaChunksLoaded(level, min, max)) {
			source.sendFailure(Component.translatable("message.redstonelink.place.chunks_not_loaded"));
			return 0;
		}

		int replaceCount = countPotentialReplaceNonAir(level, min, max, input);
		PendingPlacement pendingPlacement = new PendingPlacement(
			PlacementMode.FILL,
			level.dimension(),
			min,
			max,
			input,
			replaceCount,
			System.currentTimeMillis()
		);
		if (replaceCount > 0) {
			PENDING_PLACE_BY_PLAYER.put(player.getUUID(), pendingPlacement);
			source.sendFailure(Component.translatable("message.redstonelink.place.replace_confirm", replaceCount));
			return 0;
		}

		return executePlacementNow(source, player, pendingPlacement);
	}

	/**
	 * place confirm：确认并执行上一次待确认的 setblock/fill。
	 */
	private static int executePlaceConfirm(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		PendingPlacement pending = PENDING_PLACE_BY_PLAYER.get(player.getUUID());
		if (pending == null) {
			source.sendFailure(Component.translatable("message.redstonelink.place.no_pending"));
			return 0;
		}
		long elapsed = System.currentTimeMillis() - pending.createdAtMillis();
		if (elapsed > PLACE_CONFIRM_TIMEOUT_MILLIS) {
			PENDING_PLACE_BY_PLAYER.remove(player.getUUID());
			source.sendFailure(Component.translatable("message.redstonelink.place.pending_expired"));
			return 0;
		}
		if (!pending.dimension().equals(player.serverLevel().dimension())) {
			PENDING_PLACE_BY_PLAYER.remove(player.getUUID());
			source.sendFailure(Component.translatable("message.redstonelink.place.pending_dimension_changed"));
			return 0;
		}

		PENDING_PLACE_BY_PLAYER.remove(player.getUUID());
		return executePlacementNow(source, player, pending);
	}

	/**
	 * 实际执行放置并在命令路径内补齐缺失序号。
	 */
	private static int executePlacementNow(CommandSourceStack source, ServerPlayer player, PendingPlacement pending) {
		ServerLevel level = player.serverLevel();
		if (!pending.dimension().equals(level.dimension())) {
			source.sendFailure(Component.translatable("message.redstonelink.place.dimension_mismatch"));
			return 0;
		}

		int changedCount = 0;
		if (pending.mode() == PlacementMode.SETBLOCK) {
			if (placeOne(level, pending.blockInput(), pending.from())) {
				changedCount = 1;
			}
		} else {
			for (BlockPos pos : BlockPos.betweenClosed(pending.from(), pending.to())) {
				if (placeOne(level, pending.blockInput(), pos.immutable())) {
					changedCount++;
				}
			}
		}

		if (changedCount <= 0) {
			source.sendFailure(Component.translatable("message.redstonelink.place.no_block_changed"));
			return 0;
		}
		final int updatedCount = changedCount;
		source.sendSuccess(() -> Component.translatable("message.redstonelink.place.done", updatedCount), true);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行单点放置并补齐序号。
	 */
	private static boolean placeOne(ServerLevel level, BlockInput blockInput, BlockPos pos) {
		boolean changed = blockInput.place(level, pos, PLACE_BLOCK_FLAGS);
		if (!changed) {
			return false;
		}
		initializeSerialForPlacedNode(level, pos);
		return true;
	}

	/**
	 * 命令放置路径下，为缺失序号的节点补齐序号。
	 */
	private static void initializeSerialForPlacedNode(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof PairableNodeBlockEntity nodeBlockEntity)) {
			return;
		}
		if (nodeBlockEntity.getSerial() > 0L) {
			return;
		}
		long serial = LinkSavedData.get(level)
			.resolvePlacementSerial(nodeBlockEntity.getLinkNodeType(), 0L, level.dimension(), pos);
		if (serial > 0L) {
			nodeBlockEntity.setLinkData(serial);
		}
	}

	/**
	 * 统计目标区域中会被替换的非空气方块数量。
	 */
	private static int countPotentialReplaceNonAir(ServerLevel level, BlockPos from, BlockPos to, BlockInput blockInput) {
		BlockState targetState = blockInput.getState();
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			BlockState currentState = level.getBlockState(pos);
			if (currentState.isAir()) {
				continue;
			}
			if (!currentState.equals(targetState)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 限定 place 命令只接受可配对节点方块。
	 */
	private static boolean validatePairablePlacementInput(CommandSourceStack source, BlockInput blockInput, BlockPos samplePos) {
		BlockState state = blockInput.getState();
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) {
			source.sendFailure(Component.translatable("message.redstonelink.place.only_pairable_node"));
			return false;
		}
		BlockEntity blockEntity = entityBlock.newBlockEntity(samplePos, state);
		if (!(blockEntity instanceof PairableNodeBlockEntity)) {
			source.sendFailure(Component.translatable("message.redstonelink.place.only_pairable_node"));
			return false;
		}
		return true;
	}

	/**
	 * 计算包围盒最小坐标。
	 */
	private static BlockPos minPos(BlockPos first, BlockPos second) {
		return new BlockPos(
			Math.min(first.getX(), second.getX()),
			Math.min(first.getY(), second.getY()),
			Math.min(first.getZ(), second.getZ())
		);
	}

	/**
	 * 计算包围盒最大坐标。
	 */
	private static BlockPos maxPos(BlockPos first, BlockPos second) {
		return new BlockPos(
			Math.max(first.getX(), second.getX()),
			Math.max(first.getY(), second.getY()),
			Math.max(first.getZ(), second.getZ())
		);
	}

	/**
	 * 计算包围盒内方块体积。
	 */
	private static long blockVolume(BlockPos min, BlockPos max) {
		long sizeX = (long) max.getX() - min.getX() + 1L;
		long sizeY = (long) max.getY() - min.getY() + 1L;
		long sizeZ = (long) max.getZ() - min.getZ() + 1L;
		return sizeX * sizeY * sizeZ;
	}

	/**
	 * 使用区块源判断目标包围盒内区块是否全部已加载，避免使用弃用的 hasChunksAt。
	 */
	private static boolean isPlacementAreaChunksLoaded(ServerLevel level, BlockPos min, BlockPos max) {
		int minChunkX = SectionPos.blockToSectionCoord(min.getX());
		int maxChunkX = SectionPos.blockToSectionCoord(max.getX());
		int minChunkZ = SectionPos.blockToSectionCoord(min.getZ());
		int maxChunkZ = SectionPos.blockToSectionCoord(max.getZ());
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
					return false;
				}
			}
		}
		return true;
	}

	private static int executeAudit(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		LinkSavedData.AuditSnapshot snapshot = savedData.createAuditSnapshot();
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.summary",
				snapshot.onlineCoreNodes(),
				snapshot.onlineButtonNodes(),
				snapshot.totalLinks(),
				snapshot.linksWithMissingEndpoint()
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.linked_serials",
				snapshot.linkedButtonSerialCount(),
				snapshot.linkedCoreSerialCount()
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.core_active_serials",
				formatSerialSet(savedData.getActiveSerials(LinkNodeType.CORE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.core_retired_serials",
				formatSerialSet(savedData.getRetiredSerials(LinkNodeType.CORE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.button_active_serials",
				formatSerialSet(savedData.getActiveSerials(LinkNodeType.BUTTON))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.button_retired_serials",
				formatSerialSet(savedData.getRetiredSerials(LinkNodeType.BUTTON))
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 输出 lithium 兼容诊断快照与异常码。
	 */
	private static int executeLithiumDiag(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LithiumCompatHealth.LithiumDiagSnapshot snapshot = LithiumCompatHealth.snapshot();
		source.sendSuccess(() -> Component.literal("[RedstoneLink/Diag] " + snapshot.toSummaryLine()), false);
		if (snapshot.anomalyCode() != null) {
			source.sendFailure(
				Component.literal(
					"[RedstoneLink/Diag]["
						+ snapshot.anomalyCode()
						+ "] "
						+ snapshot.anomalyMessage()
				)
			);
			return 0;
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * retire 命令第一阶段：仅提示确认，不执行退役。
	 */
	private static int executeRetireRequireConfirm(
		CommandContext<CommandSourceStack> context,
		LinkNodeType type
	) {
		CommandSourceStack source = context.getSource();
		long serial = LongArgumentType.getLong(context, "serial");
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.retire.confirm",
				typeCommandName(type),
				serial
			)
		);
		return 0;
	}

	/**
	 * retire confirm 实际执行入口。
	 */
	private static int executeRetire(CommandContext<CommandSourceStack> context, LinkNodeType type) {
		CommandSourceStack source = context.getSource();
		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData.RetireResult result = LinkSavedData.get(source.getLevel()).retireNode(type, serial);

		if (!result.nodeRemoved() && result.linksRemoved() == 0 && !result.retiredMarked()) {
			source.sendFailure(Component.translatable("message.redstonelink.retire.no_change", typeCommandName(type), serial));
			return 0;
		}

		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.retire.done",
				typeCommandName(type),
				serial,
				Boolean.toString(result.nodeRemoved()),
				result.linksRemoved()
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static String typeCommandName(LinkNodeType type) {
		return type == LinkNodeType.BUTTON ? "button" : "core";
	}

	/**
	 * 批量覆盖设置链接集合。
	 */
	private static int executeSetLinks(
		CommandContext<CommandSourceStack> context,
		LinkNodeType sourceType,
		boolean hasTargets
	) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}

		long sourceSerial = LongArgumentType.getLong(context, "source_serial");
		LinkSavedData savedData = LinkSavedData.get(player.serverLevel());
		if (!validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}

		Set<Long> targets;
		if (!hasTargets) {
			targets = Set.of();
		} else {
			String raw = StringArgumentType.getString(context, "targets");
			TargetParseResult parseResult = parseTargetSerials(raw);
			if (!parseResult.invalidEntries().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.invalid_target_tokens",
						String.join(", ", parseResult.invalidEntries())
					)
				);
				return 0;
			}
			targets = parseResult.targets();
		}
		int maxTargets = RedstoneLinkConfig.maxTargetsPerSetLinks();
		if (targets.size() > maxTargets) {
			source.sendFailure(Component.translatable("message.redstonelink.too_many_targets", maxTargets));
			return 0;
		}

		// 分阶段校验目标：未分配、已退役、离线，分别给出可定位的失败提示。
		LinkNodeType targetType = sourceType == LinkNodeType.BUTTON ? LinkNodeType.CORE : LinkNodeType.BUTTON;
		List<Long> unallocatedTargets = new ArrayList<>();
		List<Long> retiredTargets = new ArrayList<>();
		List<Long> offlineTargets = new ArrayList<>();
		for (long targetSerial : targets) {
			if (!savedData.isSerialAllocated(targetType, targetSerial)) {
				unallocatedTargets.add(targetSerial);
				continue;
			}
			if (savedData.isSerialRetired(targetType, targetSerial)) {
				retiredTargets.add(targetSerial);
				continue;
			}
			if (savedData.findNode(targetType, targetSerial).isEmpty()) {
				offlineTargets.add(targetSerial);
			}
		}
		if (!unallocatedTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_unallocated",
					formatSerialList(unallocatedTargets)
				)
			);
			return 0;
		}
		if (!retiredTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.invalid_target_retired",
					formatSerialList(retiredTargets)
				)
			);
			return 0;
		}
		boolean allowOfflineBinding = RedstoneLinkConfig.allowOfflineTargetBinding();
		if (!allowOfflineBinding && !offlineTargets.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.offline_targets_blocked",
					formatSerialList(offlineTargets)
				)
			);
			return 0;
		}

		// set_links 语义为“覆盖集合”，因此先清空旧关系再写入新关系。
		savedData.clearLinksForNode(sourceType, sourceSerial);
		int added = 0;
		long lastTarget = 0L;
		for (long targetSerial : targets) {
			boolean addedNow = sourceType == LinkNodeType.BUTTON
				? savedData.toggleLink(sourceSerial, targetSerial)
				: savedData.toggleLink(targetSerial, sourceSerial);
			if (addedNow) {
				added++;
				lastTarget = targetSerial;
			}
		}

		updateNodeLastTargetSerial(player.serverLevel(), sourceType, sourceSerial, lastTarget);
		syncPlayerItemLinkSnapshot(player, savedData, sourceType, sourceSerial);
		final int addedCount = added;
		source.sendSuccess(() -> Component.translatable("message.redstonelink.set_links_done", addedCount), false);
		if (allowOfflineBinding && !offlineTargets.isEmpty()) {
			String offline = offlineTargets.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("-");
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.offline_targets_saved", offline),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 同步玩家背包中同序列号物品的链接快照。
	 */
	private static void syncPlayerItemLinkSnapshot(
		ServerPlayer player,
		LinkSavedData savedData,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		Set<Long> linkedSerials = sourceType == LinkNodeType.BUTTON
			? savedData.getLinkedCores(sourceSerial)
			: savedData.getLinkedButtons(sourceSerial);

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
			LinkItemData.setLinkedSerials(stack, linkedSerials);
		}
	}

	/**
	 * 回写在线节点方块实体的最近目标序列号。
	 */
	private static void updateNodeLastTargetSerial(
		ServerLevel sourceLevel,
		LinkNodeType sourceType,
		long sourceSerial,
		long targetSerial
	) {
		LinkSavedData savedData = LinkSavedData.get(sourceLevel);
		savedData.findNode(sourceType, sourceSerial).ifPresent(node -> {
			ServerLevel nodeLevel = sourceLevel.getServer().getLevel(node.dimension());
			if (nodeLevel == null || !nodeLevel.isLoaded(node.pos())) {
				return;
			}

			BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
			if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
				pairableNodeBlockEntity.setLastTargetSerial(targetSerial);
			}
		});
	}

	/**
	 * 解析序列号列表文本（逗号/分号/空白分隔）。
	 */
	private static TargetParseResult parseTargetSerials(String rawText) {
		if (rawText == null || rawText.isBlank()) {
			return new TargetParseResult(Set.of(), List.of());
		}

		// 支持逗号、分号和空白混输，便于命令行快速粘贴序列号列表。
		String[] tokens = rawText.trim().split("[,;\\s]+");
		LinkedHashSet<Long> result = new LinkedHashSet<>();
		List<String> invalidEntries = new ArrayList<>();
		for (String token : tokens) {
			if (token.isBlank()) {
				continue;
			}
			if (!token.matches("[0-9]+")) {
				invalidEntries.add(token);
				continue;
			}

			long serial;
			try {
				serial = Long.parseLong(token);
			} catch (NumberFormatException ignored) {
				invalidEntries.add(token);
				continue;
			}
			if (serial <= 0L) {
				invalidEntries.add(token);
				continue;
			}
			result.add(serial);
		}
		return new TargetParseResult(Set.copyOf(result), List.copyOf(invalidEntries));
	}

	/**
	 * 校验源序列号处于已分配且未退役状态。
	 */
	private static boolean validateSourceSerialActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		if (sourceSerial <= 0L || !savedData.isSerialAllocated(sourceType, sourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.source_serial_unallocated", sourceSerial));
			return false;
		}
		if (savedData.isSerialRetired(sourceType, sourceSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.source_serial_retired", sourceSerial));
			return false;
		}
		return true;
	}

	/**
	 * 校验目标序列号处于已分配且未退役状态。
	 */
	private static boolean validateTargetSerialActive(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType targetType,
		long targetSerial
	) {
		if (targetSerial <= 0L || !savedData.isSerialAllocated(targetType, targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.target_serial_unallocated", targetSerial));
			return false;
		}
		if (savedData.isSerialRetired(targetType, targetSerial)) {
			source.sendFailure(Component.translatable("message.redstonelink.target_serial_retired", targetSerial));
			return false;
		}
		return true;
	}

	/**
	 * 以稳定升序格式化序列号列表。
	 */
	private static String formatSerialList(List<Long> serials) {
		if (serials.isEmpty()) {
			return "-";
		}
		return serials.stream()
			.sorted()
			.map(String::valueOf)
			.reduce((left, right) -> left + ", " + right)
			.orElse("-");
	}

	/**
	 * 以稳定升序格式化序列号集合。
	 */
	private static String formatSerialSet(Set<Long> serials) {
		if (serials.isEmpty()) {
			return "-";
		}
		return serials.stream()
			.sorted()
			.map(String::valueOf)
			.reduce((left, right) -> left + ", " + right)
			.orElse("-");
	}

	private enum PlacementMode {
		SETBLOCK,
		FILL,
	}

	private record PendingPlacement(
		PlacementMode mode,
		ResourceKey<Level> dimension,
		BlockPos from,
		BlockPos to,
		BlockInput blockInput,
		int replaceCount,
		long createdAtMillis
	) {}

	private record TargetParseResult(Set<Long> targets, List<String> invalidEntries) {}
}
