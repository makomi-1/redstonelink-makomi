package com.makomi.command;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.activate.ActivateCommandRegistry;
import com.makomi.command.crosschunk.CrossChunkCommandRegistry;
import com.makomi.command.retire.RetireBatchCommandRegistry;
import com.makomi.command.semantic.SemanticCommandMessageAdapter;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkRetireCoordinator;
import com.makomi.data.LinkSavedData;
import com.makomi.item.PairableItem;
import com.makomi.util.SerialParseUtil;
import com.makomi.util.ServerSerialValidationUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
	private static final int NODE_LIST_DEFAULT_LIMIT = 50;
	private static final int NODE_LIST_MAX_LIMIT = 1000;
	private static final int SERIAL_LIST_MAX_ITEMS = 50;
	private static final int SERIAL_LIST_MAX_CHARS = 300;
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
				.requires(source -> source.hasPermission(RedstoneLinkConfig.commandPermissionLevel()))
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
							Commands.argument("type", StringArgumentType.word()).then(
								Commands.argument("source_serial", LongArgumentType.longArg(1L)).then(
									Commands.argument("target_serial", LongArgumentType.longArg(0L)).executes(
										ModCommands::executePairByNodeWithTypeArg
									)
								)
							)
						)
				)
				.then(
						Commands
							.literal("set_links")
							.then(
								Commands.argument("type", StringArgumentType.word()).then(
									Commands.argument("source_serial", LongArgumentType.longArg(1L))
										.executes(context -> executeSetLinksWithTypeArg(context, false))
										.then(
											Commands.argument("targets", StringArgumentType.greedyString())
												.executes(context -> executeSetLinksWithTypeArg(context, true))
										)
								)
							)
					)
				.then(ActivateCommandRegistry.createRoot())
				.then(
					Commands
						.literal("node")
						.then(
							Commands
								.literal("get")
								.then(
									Commands.argument("type", StringArgumentType.word()).then(
										Commands
											.argument("serial", LongArgumentType.longArg(1L))
											.executes(ModCommands::executeNodeGet)
									)
								)
						)
						.then(
							Commands
								.literal("list")
								.then(
									Commands.argument("type", StringArgumentType.word()).then(
										Commands.argument("scope", StringArgumentType.word())
											.executes(ModCommands::executeNodeList)
											.then(
												Commands
													.argument("limit", IntegerArgumentType.integer(1, NODE_LIST_MAX_LIMIT))
													.executes(ModCommands::executeNodeList)
													.then(
														Commands
															.argument("offset", IntegerArgumentType.integer(0))
															.executes(ModCommands::executeNodeList)
													)
											)
									)
								)
						)
				)
				.then(
					Commands
						.literal("link")
						.then(
							Commands
								.literal("get")
								.then(
									Commands.argument("type", StringArgumentType.word()).then(
										Commands
											.argument("serial", LongArgumentType.longArg(1L))
											.executes(ModCommands::executeLinkGet)
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
											.then(Commands.literal("dry_run").executes(ModCommands::executePlaceSetBlockDryRun))
											.then(Commands.literal("force").executes(ModCommands::executePlaceSetBlockForce))
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
												.then(Commands.literal("dry_run").executes(ModCommands::executePlaceFillDryRun))
												.then(Commands.literal("force").executes(ModCommands::executePlaceFillForce))
										)
									)
								)
						)
						.then(Commands.literal("confirm").executes(ModCommands::executePlaceConfirm))
				)
				.then(
					Commands
						.literal("audit")
						.executes(ModCommands::executeAudit)
						.then(
							Commands
								.literal("summary")
								.executes(ModCommands::executeAuditSummaryText)
								.then(
									Commands
										.argument("format", StringArgumentType.word())
										.executes(ModCommands::executeAuditSummaryByFormat)
								)
						)
				)
				.then(
					Commands
						.literal("retire")
						.then(
							Commands.argument("type", StringArgumentType.word()).then(
								Commands.argument("serial", LongArgumentType.longArg(1L))
									.executes(ModCommands::executeRetireRequireConfirmWithTypeArg)
									.then(
										Commands
											.literal("confirm")
											.executes(ModCommands::executeRetireWithTypeArg)
									)
							)
						)
						.then(RetireBatchCommandRegistry.createBatchNode())
				)
				.then(CrossChunkCommandRegistry.createRoot())
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
		if (selfType != LinkNodeType.TRIGGER_SOURCE) {
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
		if (sourceType != LinkNodeType.TRIGGER_SOURCE) {
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
	 * 根据 type 参数执行节点单目标切换配对。
	 */
	private static int executePairByNodeWithTypeArg(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType sourceType = parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (sourceType == null) {
			return 0;
		}
		return executePairByNode(context, sourceType);
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
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
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

		LinkNodeType targetType = sourceType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.CORE : LinkNodeType.TRIGGER_SOURCE;
		if (!ServerSerialValidationUtil.validateTargetSerialActive(source, savedData, targetType, targetSerial)) {
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

		boolean linkedNow = savedData.toggleLinkBySourceType(sourceType, sourceSerial, targetSerial);

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
		return executePlaceSetBlockInternal(context, false, false);
	}

	/**
	 * 自定义 setblock 预检：仅输出替换影响，不执行放置。
	 */
	private static int executePlaceSetBlockDryRun(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, true, false);
	}

	/**
	 * 自定义 setblock 强制执行：跳过替换确认直接放置。
	 */
	private static int executePlaceSetBlockForce(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, false, true);
	}

	/**
	 * 自定义 setblock 执行入口：支持预检与强制执行。
	 */
	private static int executePlaceSetBlockInternal(
		CommandContext<CommandSourceStack> context,
		boolean dryRun,
		boolean force
	) throws CommandSyntaxException {
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
		if (dryRun) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.place.dry_run_setblock",
					replaceCount,
					Boolean.toString(replaceCount > 0)
				),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		PendingPlacement pendingPlacement = new PendingPlacement(
			PlacementMode.SETBLOCK,
			level.dimension(),
			pos,
			pos,
			input,
			replaceCount,
			System.currentTimeMillis()
		);
		if (replaceCount > 0 && !force) {
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
		return executePlaceFillInternal(context, false, false);
	}

	/**
	 * 自定义 fill 预检：仅输出体积与替换影响，不执行放置。
	 */
	private static int executePlaceFillDryRun(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, true, false);
	}

	/**
	 * 自定义 fill 强制执行：跳过替换确认直接放置。
	 */
	private static int executePlaceFillForce(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, false, true);
	}

	/**
	 * 自定义 fill 执行入口：支持预检与强制执行。
	 */
	private static int executePlaceFillInternal(
		CommandContext<CommandSourceStack> context,
		boolean dryRun,
		boolean force
	) throws CommandSyntaxException {
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
		if (dryRun) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.place.dry_run_fill",
					volume,
					replaceCount,
					Boolean.toString(replaceCount > 0)
				),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		PendingPlacement pendingPlacement = new PendingPlacement(
			PlacementMode.FILL,
			level.dimension(),
			min,
			max,
			input,
			replaceCount,
			System.currentTimeMillis()
		);
		if (replaceCount > 0 && !force) {
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

	/**
	 * 查询单个节点详情。
	 */
	private static int executeNodeGet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}

		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		boolean allocated = savedData.isSerialAllocated(type, serial);
		boolean retired = savedData.isSerialRetired(type, serial);
		var nodeOptional = savedData.findNode(type, serial);
		boolean online = nodeOptional.isPresent();
		String dimensionText = nodeOptional.map(node -> node.dimension().location().toString()).orElse("-");
		String posText = nodeOptional.map(node -> formatBlockPos(node.pos())).orElse("-");
		Set<Long> targets = readLinkedTargets(savedData, type, serial);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.get",
				typeCommandName(type),
				serial,
				Boolean.toString(allocated),
				Boolean.toString(retired),
				Boolean.toString(online),
				dimensionText,
				posText,
				targets.size(),
				formatSerialSet(targets)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 查询节点序列号列表（active/retired/online）。
	 */
	private static int executeNodeList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		NodeListScope scope = parseNodeListScopeArg(source, StringArgumentType.getString(context, "scope"));
		if (scope == null) {
			return 0;
		}

		int limit = getOptionalIntArg(context, "limit", NODE_LIST_DEFAULT_LIMIT);
		int offset = getOptionalIntArg(context, "offset", 0);
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		Set<Long> serialSet = switch (scope) {
			case ACTIVE -> savedData.getActiveSerials(type);
			case RETIRED -> savedData.getRetiredSerials(type);
			case ONLINE -> savedData.getOnlineSerials(type);
		};
		List<Long> sortedSerials = serialSet.stream().sorted().toList();
		int total = sortedSerials.size();
		if (offset >= total) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.node.list",
					typeCommandName(type),
					scope.commandName(),
					total,
					0,
					offset,
					"-"
				),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		int endExclusive = Math.min(total, offset + limit);
		List<Long> page = sortedSerials.subList(offset, endExclusive);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.list",
				typeCommandName(type),
				scope.commandName(),
				total,
				page.size(),
				offset,
				formatSerialList(page)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 查询单个源节点当前关联目标列表。
	 */
	private static int executeLinkGet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}

		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		Set<Long> targets = readLinkedTargets(savedData, type, serial);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.link.get",
				typeCommandName(type),
				serial,
				targets.size(),
				formatSerialSet(targets)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 兼容历史入口：audit 默认输出文本摘要。
	 */
	private static int executeAudit(CommandContext<CommandSourceStack> context) {
		return executeAuditSummary(context.getSource(), AuditOutputFormat.TEXT);
	}

	/**
	 * audit summary 默认文本格式。
	 */
	private static int executeAuditSummaryText(CommandContext<CommandSourceStack> context) {
		return executeAuditSummary(context.getSource(), AuditOutputFormat.TEXT);
	}

	/**
	 * audit summary 指定输出格式（text/csv）。
	 */
	private static int executeAuditSummaryByFormat(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AuditOutputFormat format = parseAuditOutputFormatArg(source, StringArgumentType.getString(context, "format"));
		if (format == null) {
			return 0;
		}
		return executeAuditSummary(source, format);
	}

	/**
	 * 审计摘要统一输出实现。
	 */
	private static int executeAuditSummary(CommandSourceStack source, AuditOutputFormat outputFormat) {
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		LinkSavedData.AuditSnapshot snapshot = savedData.createAuditSnapshot();
		if (outputFormat == AuditOutputFormat.CSV) {
			source.sendSuccess(
				() -> Component.literal(
					"[RedstoneLink] online_core_nodes,online_trigger_source_nodes,total_links,links_with_missing_endpoint,linked_trigger_source_serial_count,linked_core_serial_count,active_core_serials,retired_core_serials,active_trigger_source_serials,retired_trigger_source_serials"
				),
				false
			);
			source.sendSuccess(
				() -> Component.literal(
					"[RedstoneLink] "
						+ String.join(
							",",
							Integer.toString(snapshot.onlineCoreNodes()),
							Integer.toString(snapshot.onlineButtonNodes()),
							Integer.toString(snapshot.totalLinks()),
							Integer.toString(snapshot.linksWithMissingEndpoint()),
							Integer.toString(snapshot.linkedButtonSerialCount()),
							Integer.toString(snapshot.linkedCoreSerialCount()),
							formatSerialSetCsv(savedData.getActiveSerials(LinkNodeType.CORE)),
							formatSerialSetCsv(savedData.getRetiredSerials(LinkNodeType.CORE)),
							formatSerialSetCsv(savedData.getActiveSerials(LinkNodeType.TRIGGER_SOURCE)),
							formatSerialSetCsv(savedData.getRetiredSerials(LinkNodeType.TRIGGER_SOURCE))
						)
				),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

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
				formatSerialSet(savedData.getActiveSerials(LinkNodeType.TRIGGER_SOURCE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.button_retired_serials",
				formatSerialSet(savedData.getRetiredSerials(LinkNodeType.TRIGGER_SOURCE))
			),
			false
		);
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
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, type, serial)) {
			return 0;
		}
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
	 * 根据 type 参数执行 retire 第一阶段确认提示。
	 */
	private static int executeRetireRequireConfirmWithTypeArg(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		return executeRetireRequireConfirm(context, type);
	}

	/**
	 * retire confirm 实际执行入口。
	 */
	private static int executeRetire(CommandContext<CommandSourceStack> context, LinkNodeType type) {
		CommandSourceStack source = context.getSource();
		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, type, serial)) {
			return 0;
		}
		LinkSavedData.RetireResult result = LinkRetireCoordinator.retireAndSyncWhitelist(source.getLevel(), type, serial);

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

	/**
	 * 根据 type 参数执行 retire confirm。
	 */
	private static int executeRetireWithTypeArg(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		return executeRetire(context, type);
	}

	private static String typeCommandName(LinkNodeType type) {
		return LinkNodeSemantics.toSemanticName(type);
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
		if (!ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, sourceType, sourceSerial)) {
			return 0;
		}

		Set<Long> targets;
		String rawTargets = "";
		boolean confirmed = false;
		int maxTargets = RedstoneLinkConfig.maxTargetsPerSetLinks();
		if (!hasTargets) {
			targets = Set.of();
		} else {
			String rawInput = StringArgumentType.getString(context, "targets");
			CommandSuffixParser.ConfirmSuffixParseResult confirmSuffixParseResult = parseConfirmSuffix(rawInput);
			confirmed = confirmSuffixParseResult.confirmed();
			rawTargets = confirmSuffixParseResult.payload();
			TargetParseResult parseResult = parseTargetSerials(rawTargets, maxTargets);
			if (!parseResult.invalidEntries().isEmpty()) {
				source.sendFailure(
					Component.translatable(
						"message.redstonelink.invalid_target_tokens",
						String.join(", ", parseResult.invalidEntries())
					)
				);
				return 0;
			}
			if (parseResult.exceedLimit()) {
				source.sendFailure(Component.translatable("message.redstonelink.too_many_targets", maxTargets));
				return 0;
			}
			targets = parseResult.targets();
			if (!parseResult.duplicateEntries().isEmpty()) {
				source.sendSuccess(
					() -> Component.translatable(
						"message.redstonelink.duplicate_targets_deduped",
						formatSerialCollection(parseResult.duplicateEntries())
					),
					false
				);
			}
		}
		if (targets.size() > maxTargets) {
			source.sendFailure(Component.translatable("message.redstonelink.too_many_targets", maxTargets));
			return 0;
		}

		// 分阶段校验目标：未分配、已退役、离线，分别给出可定位的失败提示。
		LinkNodeType targetType = sourceType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.CORE : LinkNodeType.TRIGGER_SOURCE;
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
		if (hasTargets && targets.size() > 1 && !confirmed) {
			String confirmCommand = "redstonelink set_links "
				+ typeCommandName(sourceType)
				+ " "
				+ sourceSerial
				+ " "
				+ rawTargets
				+ " confirm";
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.set_links.confirm_required",
					targets.size(),
					confirmCommand
				)
			);
			return 0;
		}
		Set<Long> previousTargets = new HashSet<>(savedData.getLinkedTargetsBySourceType(sourceType, sourceSerial));
		savedData.clearLinksForNode(sourceType, sourceSerial);
		int added = 0;
		long lastTarget = 0L;
		for (long targetSerial : targets) {
			boolean addedNow = savedData.toggleLinkBySourceType(sourceType, sourceSerial, targetSerial);
			if (addedNow) {
				added++;
				lastTarget = targetSerial;
			}
		}

		updateNodeLastTargetSerial(player.serverLevel(), sourceType, sourceSerial, lastTarget);
		syncAffectedNodeLinkSnapshots(player.serverLevel(), targetType, previousTargets, targets);
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
	 * 根据 type 参数执行覆盖式 set_links。
	 */
	private static int executeSetLinksWithTypeArg(CommandContext<CommandSourceStack> context, boolean hasTargets) {
		CommandSourceStack source = context.getSource();
		LinkNodeType sourceType = parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (sourceType == null) {
			return 0;
		}
		return executeSetLinks(context, sourceType, hasTargets);
	}

	/**
	 * 解析批量序列参数末尾的二次确认后缀（` confirm`）。
	 *
	 * @param rawText 原始参数文本
	 * @return 去后缀后的文本与确认标记
	 */
	private static CommandSuffixParser.ConfirmSuffixParseResult parseConfirmSuffix(String rawText) {
		return CommandSuffixParser.parseConfirmOnly(rawText, false);
	}

	/**
	 * 二次确认后缀解析结果。
	 *
	 * @param payload 去后缀后的有效参数文本
	 * @param confirmed 是否携带确认后缀
	 */
	/**
	 * 同步玩家背包中同序列号物品的链接快照。
	 */
	private static void syncPlayerItemLinkSnapshot(
		ServerPlayer player,
		LinkSavedData savedData,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		Set<Long> linkedSerials = savedData.getLinkedTargetsBySourceType(sourceType, sourceSerial);

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
				// 即使最近目标未变化，也强制推送一次客户端快照，确保近外显链接信息及时刷新。
				pairableNodeBlockEntity.forceSyncToClient();
			}
		});
	}

	/**
	 * 同步受影响节点（来源 + 目标集合）的客户端链接快照。
	 */
	private static void syncAffectedNodeLinkSnapshots(
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
	 * 解析序列号列表文本（`/` 分隔，支持 `N` 与 `A:B`）。
	 */
	private static TargetParseResult parseTargetSerials(String rawText, int maxTargetCount) {
		SerialParseUtil.TargetParseResult parsed = SerialParseUtil.parseTargets(rawText, maxTargetCount);
		return new TargetParseResult(
			parsed.targets(),
			parsed.invalidEntries(),
			parsed.duplicateEntries(),
			parsed.exceedLimit()
		);
	}

	/**
	 * 校验源序列号处于已分配且未退役状态。
	 */

	/**
	 * 解析节点类型参数。
	 */
	private static LinkNodeType parseNodeTypeArg(CommandSourceStack source, String rawType) {
		var parsedType = LinkNodeSemantics.tryParseCanonicalType(rawType);
		if (parsedType.isPresent()) {
			return parsedType.get();
		}
		source.sendFailure(SemanticCommandMessageAdapter.invalidType(rawType));
		return null;
	}

	/**
	 * 解析节点列表范围参数。
	 */
	private static NodeListScope parseNodeListScopeArg(CommandSourceStack source, String rawScope) {
		String normalized = rawScope == null ? "" : rawScope.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "active" -> NodeListScope.ACTIVE;
			case "retired" -> NodeListScope.RETIRED;
			case "online" -> NodeListScope.ONLINE;
			default -> {
				source.sendFailure(Component.translatable("message.redstonelink.node.invalid_scope", rawScope));
				yield null;
			}
		};
	}

	/**
	 * 解析审计输出格式参数。
	 */
	private static AuditOutputFormat parseAuditOutputFormatArg(CommandSourceStack source, String rawFormat) {
		String normalized = rawFormat == null ? "" : rawFormat.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "text" -> AuditOutputFormat.TEXT;
			case "csv" -> AuditOutputFormat.CSV;
			default -> {
				source.sendFailure(Component.translatable("message.redstonelink.audit.invalid_format", rawFormat));
				yield null;
			}
		};
	}

	/**
	 * 读取可选整型参数，不存在时返回默认值。
	 */
	private static int getOptionalIntArg(
		CommandContext<CommandSourceStack> context,
		String argumentName,
		int defaultValue
	) {
		try {
			return IntegerArgumentType.getInteger(context, argumentName);
		} catch (IllegalArgumentException ignored) {
			return defaultValue;
		}
	}

	/**
	 * 读取源节点当前关联目标列表。
	 */
	private static Set<Long> readLinkedTargets(
		LinkSavedData savedData,
		LinkNodeType sourceType,
		long sourceSerial
	) {
		return savedData.getLinkedTargetsBySourceType(sourceType, sourceSerial);
	}

	/**
	 * 序列化方块坐标文本。
	 */
	private static String formatBlockPos(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	/**
	 * 以稳定升序格式化序列号列表。
	 */
	private static String formatSerialList(List<Long> serials) {
		return formatSerialCollection(serials);
	}

	/**
	 * 以“|”分隔格式化序列号集合，供 CSV 输出避免逗号冲突。
	 */
	private static String formatSerialSetCsv(Set<Long> serials) {
		if (serials.isEmpty()) {
			return "-";
		}
		return serials.stream()
			.sorted()
			.map(String::valueOf)
			.reduce((left, right) -> left + "|" + right)
			.orElse("-");
	}

	/**
	 * 以稳定升序格式化序列号集合。
	 */
	private static String formatSerialSet(Set<Long> serials) {
		return formatSerialCollection(serials);
	}

	/**
	 * 统一格式化序列号集合，限制输出数量与总字符长度，避免命令反馈过长。
	 */
	private static String formatSerialCollection(Collection<Long> serials) {
		if (serials.isEmpty()) {
			return "-";
		}

		List<Long> sortedSerials = serials.stream().sorted().toList();
		StringBuilder builder = new StringBuilder();
		int shownCount = 0;
		for (Long serial : sortedSerials) {
			if (shownCount >= SERIAL_LIST_MAX_ITEMS) {
				break;
			}
			String token = String.valueOf(serial);
			String separator = shownCount == 0 ? "" : ", ";
			int appendedLength = builder.length() + separator.length() + token.length();
			if (appendedLength > SERIAL_LIST_MAX_CHARS) {
				break;
			}
			builder.append(separator).append(token);
			shownCount++;
		}

		if (shownCount <= 0) {
			return "-";
		}
		int omittedCount = sortedSerials.size() - shownCount;
		if (omittedCount > 0) {
			builder.append(", ... (+").append(omittedCount).append(" more)");
		}
		return builder.toString();
	}

	private enum NodeListScope {
		ACTIVE("active"),
		RETIRED("retired"),
		ONLINE("online");

		private final String commandName;

		NodeListScope(String commandName) {
			this.commandName = commandName;
		}

		public String commandName() {
			return commandName;
		}
	}

	private enum AuditOutputFormat {
		TEXT,
		CSV,
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

	private record TargetParseResult(
		Set<Long> targets,
		List<String> invalidEntries,
		List<Long> duplicateEntries,
		boolean exceedLimit
	) {}
}
