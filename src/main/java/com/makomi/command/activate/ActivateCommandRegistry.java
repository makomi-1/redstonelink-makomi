package com.makomi.command.activate;

import com.makomi.advancement.RedstoneLinkAdvancementService;
import com.makomi.block.entity.ActivationMode;
import com.makomi.command.CommandNodeTypeParseUtil;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkedTargetDispatchService;
import com.makomi.util.SerialCollectionFormatUtil;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 批量激活命令注册器。
 * <p>
 * 提供命令端批量触发入口，复用统一派发链路，不依赖 API 层。
 * </p>
 */
public final class ActivateCommandRegistry {
	private ActivateCommandRegistry() {
	}

	/**
	 * 构建 `activate` 命令根节点。
	 * <p>
	 * 输入支持：
	 * 1. 结构化序号串：`1:100/901:1903`
	 * 2. 可选末尾模式：`toggle` / `pulse`
	 * </p>
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("activate")
			.then(
				Commands
					.argument("type", StringArgumentType.word())
					.then(
						Commands
							.argument("source_serials", SerialBatchArgumentType.serialBatch())
							.executes(context -> executeBatchActivateWithTypeArg(context, ActivationMode.TOGGLE))
							.then(
								Commands.literal("toggle")
									.executes(context -> executeBatchActivateWithTypeArg(context, ActivationMode.TOGGLE))
							)
							.then(
								Commands.literal("pulse")
									.executes(context -> executeBatchActivateWithTypeArg(context, ActivationMode.PULSE))
							)
					)
			);
	}

	/**
	 * 根据 type 参数执行批量激活入口。
	 */
	private static int executeBatchActivateWithTypeArg(
		CommandContext<CommandSourceStack> context,
		ActivationMode mode
	) {
		CommandSourceStack source = context.getSource();
		LinkNodeType sourceType = parseSourceTypeArg(source, StringArgumentType.getString(context, "type"));
		if (sourceType == null) {
			return 0;
		}
		return executeBatchActivate(
			context,
			SerialBatchArgumentType.getSerialBatch(context, "source_serials"),
			mode
		);
	}

	/**
	 * 执行批量激活。
	 *
	 * @param context 命令上下文
	 * @param rawSourceSerials 结构化来源序号文本
	 * @param mode 激活模式
	 * @return 命令执行结果
	 */
	private static int executeBatchActivate(
		CommandContext<CommandSourceStack> context,
		String rawSourceSerials,
		ActivationMode mode
	) {
		CommandSourceStack source = context.getSource();
		if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
			source.sendFailure(Component.translatable("message.redstonelink.player_only"));
			return 0;
		}
		int maxBatchSourceSerials = RedstoneLinkConfig.command().activateBatchMaxSerials();

		SerialParseUtil.TargetParseResult parseResult = SerialParseUtil.parseTargets(
			rawSourceSerials,
			maxBatchSourceSerials
		);
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
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.activate.batch.too_many_sources",
					maxBatchSourceSerials
				)
			);
			return 0;
		}

		Set<Long> sourceSerials = parseResult.targets();
		if (sourceSerials.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.activate.batch.empty"));
			return 0;
		}
		int commandCost = CommandRateLimitService.computeBatchCost(2, sourceSerials.size(), 64);
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.OTHER,
				commandCost
			)
		) {
			return 0;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.batch_serials_deduped",
					SerialCollectionFormatUtil.formatSortedCsv(parseResult.duplicateEntries())
				),
				false
			);
		}

		LinkSavedData savedData = LinkSavedData.get(serverLevel);
		List<Long> invalidSources = new ArrayList<>();
		for (long sourceSerial : sourceSerials) {
			boolean active = savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, sourceSerial)
				&& !savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, sourceSerial);
			if (!active) {
				invalidSources.add(sourceSerial);
			}
		}
		if (!invalidSources.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.activate.batch.invalid_sources",
					SerialCollectionFormatUtil.formatSortedCsv(invalidSources)
				)
			);
			return 0;
		}

		int sourcesWithLinks = 0;
		int sourcesHandled = 0;
		int handledTargets = 0;
		int crossChunkHandled = 0;
		ServerPlayer commandPlayer = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
		for (long sourceSerial : sourceSerials) {
			Set<Long> linkedTargets = savedData.getLinkedCoresByTriggerSource(sourceSerial);
			if (linkedTargets.isEmpty()) {
				continue;
			}
			sourcesWithLinks++;
			LinkedTargetDispatchService.DispatchSummary summary = LinkedTargetDispatchService.dispatchActivation(
				serverLevel,
				LinkNodeType.TRIGGER_SOURCE,
				sourceSerial,
				LinkNodeType.CORE,
				linkedTargets,
				mode
			);
			if (summary.handledCount() > 0) {
				sourcesHandled++;
			}
			if (commandPlayer != null) {
				RedstoneLinkAdvancementService.awardComeFindMeInTheEndIfMatched(
					commandPlayer,
					serverLevel.dimension(),
					summary
				);
			}
			handledTargets += summary.handledCount();
			crossChunkHandled += summary.crossChunkHandledCount();
		}

		if (handledTargets <= 0) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.activate.batch.no_reachable",
					sourceSerials.size(),
					sourcesWithLinks
				)
			);
			return 0;
		}

		final int totalSources = sourceSerials.size();
		final int finalSourcesWithLinks = sourcesWithLinks;
		final int finalSourcesHandled = sourcesHandled;
		final int finalHandledTargets = handledTargets;
		final int finalCrossChunkHandled = crossChunkHandled;
		final String modeName = mode == ActivationMode.PULSE ? "pulse" : "toggle";
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.activate.batch.summary",
				totalSources,
				finalSourcesWithLinks,
				finalSourcesHandled,
				finalHandledTargets,
				finalCrossChunkHandled,
				modeName
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析并校验 activate 命令的来源类型参数。
	 */
	private static LinkNodeType parseSourceTypeArg(CommandSourceStack source, String rawType) {
		LinkNodeType parsedType = CommandNodeTypeParseUtil.parseCanonicalTypeOrSendDefaultFailure(source, rawType);
		if (parsedType == null) {
			return null;
		}
		if (parsedType != LinkNodeType.TRIGGER_SOURCE) {
			source.sendFailure(Component.translatable("message.redstonelink.trigger_source_only"));
			return null;
		}
		return parsedType;
	}
}
