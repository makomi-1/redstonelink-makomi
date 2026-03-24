package com.makomi.command.place;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.data.LinkSavedData;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
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
								.then(Commands.literal("force").executes(PlaceCommandRegistry::executePlaceSetBlockForce))
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
									.then(Commands.literal("force").executes(PlaceCommandRegistry::executePlaceFillForce))
									.then(Commands.literal("confirm").executes(PlaceCommandRegistry::executePlaceFillConfirm))
							)
						)
					)
			);
	}

	/**
	 * 自定义 setblock：放置可配对节点。
	 */
	private static int executePlaceSetBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, false, false);
	}

	/**
	 * 自定义 setblock 预检：仅输出影响，不执行放置。
	 */
	private static int executePlaceSetBlockDryRun(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceSetBlockInternal(context, true, false);
	}

	/**
	 * 自定义 setblock 强制执行：跳过确认直接放置。
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
		return executePlacementNow(source, level, pendingPlacement);
	}

	/**
	 * 自定义 fill：批量放置可配对节点，非 force 执行要求 confirm。
	 */
	private static int executePlaceFill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, false, false);
	}

	/**
	 * 自定义 fill 强制执行：跳过 confirm 拦截直接放置。
	 */
	private static int executePlaceFillForce(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, true, false);
	}

	/**
	 * 自定义 fill 二次确认执行：在原命令末尾追加 confirm 后执行。
	 */
	private static int executePlaceFillConfirm(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return executePlaceFillInternal(context, false, true);
	}

	/**
	 * 自定义 fill 执行入口：支持强制执行与确认执行。
	 */
	private static int executePlaceFillInternal(
		CommandContext<CommandSourceStack> context,
		boolean force,
		boolean confirmed
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
		int limit = level.getGameRules().getInt(GameRules.RULE_COMMAND_MODIFICATION_BLOCK_LIMIT);
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
		return executePlacementNow(source, level, pendingPlacement);
	}

	/**
	 * 实际执行放置并在命令路径内补齐缺失序号。
	 */
	private static int executePlacementNow(CommandSourceStack source, ServerLevel level, PendingPlacement pending) {
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
	 * place 命令仅接受可配对节点方块。
	 */
	private static boolean validatePairablePlacementInput(CommandSourceStack source, BlockInput blockInput, BlockPos samplePos) {
		BlockState state = blockInput.getState();
		if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
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
}
