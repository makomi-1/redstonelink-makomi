package com.makomi.command.activate;

import com.makomi.block.entity.ActivationMode;
import com.makomi.command.CommandNodeTypeParseUtil;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
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
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

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
							.argument("source_serials", StringArgumentType.greedyString())
							.executes(ActivateCommandRegistry::executeBatchActivateWithTypeArg)
					)
			);
	}

	/**
	 * 根据 type 参数执行批量激活入口。
	 */
	private static int executeBatchActivateWithTypeArg(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType sourceType = parseSourceTypeArg(source, StringArgumentType.getString(context, "type"));
		if (sourceType == null) {
			return 0;
		}
		return executeBatchActivate(context);
	}

	/**
	 * 执行批量激活入口（支持在序号参数末尾追加模式）。
	 *
	 * @param context 命令上下文
	 * @return 命令执行结果
	 */
	private static int executeBatchActivate(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		SerialsAndModeParseResult parseResult = parseSerialsAndMode(
			source,
			StringArgumentType.getString(context, "source_serials")
		);
		if (parseResult == null) {
			return 0;
		}
		return executeBatchActivate(context, parseResult.serialsText(), parseResult.mode());
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
		int maxBatchSourceSerials = RedstoneLinkConfig.activateBatchMaxSerials();

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
		for (long sourceSerial : sourceSerials) {
			Set<Long> linkedTargets = savedData.getLinkedTargetsBySourceType(LinkNodeType.TRIGGER_SOURCE, sourceSerial);
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
		final String modeName = mode.name().toLowerCase(Locale.ROOT);
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
	 * 从序号输入中解析可选模式后缀。
	 * <p>
	 * 规则：
	 * 1. 默认模式为 `toggle`。
	 * 2. 若最后一个 token 为纯字母，则按模式解析并从序号文本中剥离。
	 * 3. 模式非法时返回错误。
	 * 4. 剥离模式后序号为空时返回“空输入”错误。
	 * </p>
	 *
	 * @param source 命令源
	 * @param rawInput 原始输入文本
	 * @return 解析结果；失败时返回 null
	 */
	private static SerialsAndModeParseResult parseSerialsAndMode(CommandSourceStack source, String rawInput) {
		String normalized = rawInput == null ? "" : rawInput.trim();
		if (normalized.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.activate.batch.empty"));
			return null;
		}

		int lastSpaceIndex = normalized.lastIndexOf(' ');
		if (lastSpaceIndex < 0) {
			return new SerialsAndModeParseResult(normalized, ActivationMode.TOGGLE);
		}

		String tailToken = normalized.substring(lastSpaceIndex + 1).trim();
		if (tailToken.isEmpty()) {
			return new SerialsAndModeParseResult(normalized, ActivationMode.TOGGLE);
		}

		boolean looksLikeModeToken = tailToken.chars().allMatch(Character::isLetter);
		if (!looksLikeModeToken) {
			return new SerialsAndModeParseResult(normalized, ActivationMode.TOGGLE);
		}

		ActivationMode mode = parseActivationMode(source, tailToken);
		if (mode == null) {
			return null;
		}

		String serialsText = normalized.substring(0, lastSpaceIndex).trim();
		if (serialsText.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.activate.batch.empty"));
			return null;
		}
		return new SerialsAndModeParseResult(serialsText, mode);
	}

	/**
	 * 解析激活模式参数。
	 *
	 * @param source 命令源
	 * @param rawMode 模式文本
	 * @return 模式；非法时返回 null
	 */
	private static ActivationMode parseActivationMode(CommandSourceStack source, String rawMode) {
		if (rawMode == null) {
			source.sendFailure(Component.translatable("message.redstonelink.activate.batch.invalid_mode", "null"));
			return null;
		}
		String normalized = rawMode.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "toggle" -> ActivationMode.TOGGLE;
			case "pulse" -> ActivationMode.PULSE;
			default -> {
				source.sendFailure(
					Component.translatable("message.redstonelink.activate.batch.invalid_mode", rawMode)
				);
				yield null;
			}
		};
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
			source.sendFailure(Component.translatable("message.redstonelink.button_source_only"));
			return null;
		}
		return parsedType;
	}

	/**
	 * 序号文本与模式解析结果。
	 *
	 * @param serialsText 序号表达式文本
	 * @param mode 激活模式
	 */
	private record SerialsAndModeParseResult(String serialsText, ActivationMode mode) {
	}
}
