package com.makomi.command.place;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.data.LinkSavedData;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Locale;
import net.minecraft.commands.CommandBuildContext;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * `place` 命令注册器。
 * <p>
 * 负责可配对节点方块的命令放置与批量填充。
 * </p>
 */
public final class PlaceCommandRegistry {
	private static final int PLACE_BLOCK_FLAGS = 2;
	private static final String BENCH_PLACE_ORDER = "yzx";
	private static final String BENCH_PLACE_SUMMARY_PREFIX = "[RedstoneLink/Bench] place_summary";

	private PlaceCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink place` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot(CommandBuildContext registryAccess) {
		return Commands
			.literal("place")
			.requires(CommandTreeSupport::hasOtherCommandPermission)
			.then(
				Commands
					.literal("setblock")
					.then(
						Commands.argument("pos", BlockPosArgument.blockPos()).then(
							Commands
								.argument("block", BlockStateArgument.block(registryAccess))
								.executes(PlaceCommandRegistry::executePlaceSetBlock)
								.then(Commands.literal("dry_run").executes(PlaceCommandRegistry::executePlaceSetBlockDryRun))
								.then(
									Commands
										.literal("force")
										.executes(PlaceCommandRegistry::executePlaceSetBlockForce)
										.then(Commands.literal("bench").executes(PlaceCommandRegistry::executePlaceSetBlockForceBench))
								)
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
									.executes(PlaceCommandRegistry::executePlaceFill)
									.then(
										Commands
											.literal("force")
											.executes(PlaceCommandRegistry::executePlaceFillForce)
											.then(Commands.literal("bench").executes(PlaceCommandRegistry::executePlaceFillForceBench))
									)
									.then(
										Commands
											.literal("confirm")
											.executes(PlaceCommandRegistry::executePlaceFillConfirm)
											.then(Commands.literal("bench").executes(PlaceCommandRegistry::executePlaceFillConfirmBench))
									)
							)
						)
					)
			);
	}

	/**
	 * 自定义 setblock：放置可配对节点。
	 */
	private static int executePlaceSetBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, false, false, false);
	}

	/**
	 * 自定义 setblock 预检：仅输出影响，不执行放置。
	 */
	private static int executePlaceSetBlockDryRun(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, true, false, false);
	}

	/**
	 * 自定义 setblock 强制执行：跳过确认直接放置。
	 */
	private static int executePlaceSetBlockForce(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, false, true, false);
	}

	/**
	 * bench 专用 setblock 强制执行：返回机器可解析的放置摘要。
	 */
	private static int executePlaceSetBlockForceBench(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, false, true, true);
	}

	/**
	 * 自定义 setblock 执行入口：支持预检与强制执行。
	 */
	private static int executePlaceSetBlockInternal(
		CommandContext<CommandSourceStack> context,
		boolean dryRun,
		boolean force,
		boolean benchSummary
	) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		if (!CommandTreeSupport.allowPlayerSourceOrBenchmarkMode(source)) {
			return 0;
		}
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}

		ServerLevel level = source.getLevel();
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
		BlockInput input = BlockStateArgument.getBlock(context, "block");
		if (!validatePairablePlacementInput(source, input, pos)) {
			return 0;
		}
		if (dryRun) {
			source.sendSuccess(() -> Component.translatable("message.redstonelink.place.dry_run_setblock"), false);
			return Command.SINGLE_SUCCESS;
		}

		PendingPlacement pendingPlacement = new PendingPlacement(
			PlacementMode.SETBLOCK,
			level.dimension(),
			pos,
			pos,
			input
		);
		if (!force) {
			// setblock 仍保留 force 子命令形态，但当前逻辑统一直接执行。
		}
		return executePlacementNow(source, level, pendingPlacement, benchSummary);
	}

	/**
	 * 自定义 fill：批量放置可配对节点，非 force 执行要求 confirm。
	 */
	private static int executePlaceFill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, false, false, false);
	}

	/**
	 * 自定义 fill 强制执行：跳过 confirm 拦截直接放置。
	 */
	private static int executePlaceFillForce(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, true, false, false);
	}

	/**
	 * bench 专用 fill 强制执行：返回机器可解析的放置摘要。
	 */
	private static int executePlaceFillForceBench(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, true, false, true);
	}

	/**
	 * 自定义 fill 二次确认执行：在原命令末尾追加 confirm 后执行。
	 */
	private static int executePlaceFillConfirm(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, false, true, false);
	}

	/**
	 * bench 专用 fill confirm 执行：返回机器可解析的放置摘要。
	 */
	private static int executePlaceFillConfirmBench(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, false, true, true);
	}

	/**
	 * 自定义 fill 执行入口：支持强制执行与确认执行。
	 */
	private static int executePlaceFillInternal(
		CommandContext<CommandSourceStack> context,
		boolean force,
		boolean confirmed,
		boolean benchSummary
	) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		if (!CommandTreeSupport.allowPlayerSourceOrBenchmarkMode(source)) {
			return 0;
		}

		ServerLevel level = source.getLevel();
		BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
		BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");
		BlockPos min = minPos(from, to);
		BlockPos max = maxPos(from, to);
		BlockInput input = BlockStateArgument.getBlock(context, "block");
		if (!validatePairablePlacementInput(source, input, min)) {
			return 0;
		}

		long volume = blockVolume(min, max);
		int limit = level.getGameRules().get(GameRules.MAX_BLOCK_MODIFICATIONS);
		if (volume > limit) {
			source.sendFailure(Component.translatable("message.redstonelink.place.volume_exceeded", volume, limit));
			return 0;
		}
		if (!isPlacementAreaChunksLoaded(level, min, max)) {
			source.sendFailure(Component.translatable("message.redstonelink.place.chunks_not_loaded"));
			return 0;
		}
		int boundedVolume = volume > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) volume;
		int commandCost = CommandRateLimitService.computeBatchCost(3, boundedVolume, 256);
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.OTHER,
				commandCost
			)
		) {
			return 0;
		}

		if (!force && !confirmed) {
			source.sendFailure(Component.translatable("message.redstonelink.place.fill.confirm_required"));
			return 0;
		}
		PendingPlacement pendingPlacement = new PendingPlacement(
			PlacementMode.FILL,
			level.dimension(),
			min,
			max,
			input
		);
		return executePlacementNow(source, level, pendingPlacement, benchSummary);
	}

	/**
	 * 实际执行放置并在命令路径内补齐缺失序号。
	 */
	private static int executePlacementNow(
		CommandSourceStack source,
		ServerLevel level,
		PendingPlacement pending,
		boolean benchSummary
	) {
		if (!pending.dimension().equals(level.dimension())) {
			source.sendFailure(Component.translatable("message.redstonelink.place.dimension_mismatch"));
			return 0;
		}

		PairableNodeBlockEntity sampleNode = createPairableNodeSample(pending.blockInput(), pending.from());
		String nodeTypeName = sampleNode == null
			? "unknown"
			: CommandTreeSupport.typeCommandName(sampleNode.getLinkNodeType());
		int changedCount = 0;
		long firstSerial = 0L;
		long lastSerial = 0L;
		if (pending.mode() == PlacementMode.SETBLOCK) {
			long assignedSerial = placeOne(level, pending.blockInput(), pending.from());
			if (assignedSerial > 0L) {
				changedCount = 1;
				firstSerial = assignedSerial;
				lastSerial = assignedSerial;
			}
		} else {
			for (BlockPos pos : BlockPos.betweenClosed(pending.from(), pending.to())) {
				long assignedSerial = placeOne(level, pending.blockInput(), pos.immutable());
				if (assignedSerial > 0L) {
					if (changedCount == 0) {
						firstSerial = assignedSerial;
					}
					changedCount++;
					lastSerial = assignedSerial;
				}
			}
		}

		if (changedCount <= 0) {
			source.sendFailure(Component.translatable("message.redstonelink.place.no_block_changed"));
			return 0;
		}
		PlacementExecutionSummary summary = new PlacementExecutionSummary(nodeTypeName, changedCount, firstSerial, lastSerial);
		if (benchSummary) {
			// bench 摘要需要稳定返回给 RCON；这里显式允许管理员广播，避免 execute as 玩家时出现空响应。
			source.sendSuccess(() -> Component.literal(buildBenchPlacementSummary(summary)), true);
		} else {
			final int updatedCount = changedCount;
			source.sendSuccess(() -> Component.translatable("message.redstonelink.place.done", updatedCount), true);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行单点放置并补齐序号。
	 */
	private static long placeOne(ServerLevel level, BlockInput blockInput, BlockPos pos) {
		boolean changed = blockInput.place(level, pos, PLACE_BLOCK_FLAGS);
		if (!changed) {
			return 0L;
		}
		return initializeSerialForPlacedNode(level, pos);
	}

	/**
	 * 命令放置路径下，为缺失序号的节点补齐序号。
	 */
	private static long initializeSerialForPlacedNode(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof PairableNodeBlockEntity nodeBlockEntity)) {
			return 0L;
		}
		if (nodeBlockEntity.getSerial() > 0L) {
			return nodeBlockEntity.getSerial();
		}
		long serial = LinkSavedData.get(level)
			.resolvePlacementSerial(nodeBlockEntity.getLinkNodeType(), 0L, level.dimension(), pos);
		if (serial > 0L) {
			nodeBlockEntity.setLinkData(serial);
		}
		return nodeBlockEntity.getSerial();
	}

	/**
	 * place 命令仅接受可配对节点方块。
	 */
	private static boolean validatePairablePlacementInput(CommandSourceStack source, BlockInput blockInput, BlockPos samplePos) {
		if (createPairableNodeSample(blockInput, samplePos) == null) {
			source.sendFailure(Component.translatable("message.redstonelink.place.only_pairable_node"));
			return false;
		}
		return true;
	}

	/**
	 * 创建命令放置的示例节点实体，用于读取节点类型与合法性。
	 */
	private static PairableNodeBlockEntity createPairableNodeSample(BlockInput blockInput, BlockPos samplePos) {
		BlockState state = blockInput.getState();
		if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
			return null;
		}
		BlockEntity blockEntity = entityBlock.newBlockEntity(samplePos, state);
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return null;
		}
		return pairableNodeBlockEntity;
	}

	/**
	 * 生成 bench 可解析的放置摘要。
	 */
	private static String buildBenchPlacementSummary(PlacementExecutionSummary summary) {
		return String.format(
			Locale.ROOT,
			"%s type=%s order=%s count=%d startSerial=%d endSerial=%d",
			BENCH_PLACE_SUMMARY_PREFIX,
			summary.nodeTypeName(),
			BENCH_PLACE_ORDER,
			summary.changedCount(),
			summary.firstSerial(),
			summary.lastSerial()
		);
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
	 * 判断目标包围盒内区块是否全部已加载。
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

	private enum PlacementMode {
		SETBLOCK,
		FILL,
	}

	private record PendingPlacement(
		PlacementMode mode,
		ResourceKey<Level> dimension,
		BlockPos from,
		BlockPos to,
		BlockInput blockInput
	) {}

	private record PlacementExecutionSummary(
		String nodeTypeName,
		int changedCount,
		long firstSerial,
		long lastSerial
	) {}
}
