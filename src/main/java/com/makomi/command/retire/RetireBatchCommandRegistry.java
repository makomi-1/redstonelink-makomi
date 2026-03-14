package com.makomi.command.retire;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 批量退役命令注册器。
 */
public final class RetireBatchCommandRegistry {
	private static final int MAX_BATCH_RETIRE_SERIALS = 1024;

	private RetireBatchCommandRegistry() {
	}

	/**
	 * 构建 `retire batch` 子节点。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createBatchNode() {
		return Commands
			.literal("batch")
			.then(
				Commands.argument("type", StringArgumentType.word()).then(
					Commands.argument("serials", StringArgumentType.greedyString())
						.executes(RetireBatchCommandRegistry::executeBatchRetire)
				)
			);
	}

	/**
	 * 执行批量退役。
	 */
	private static int executeBatchRetire(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseNodeType(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}

		ConfirmSuffixParseResult confirmSuffixParseResult = parseConfirmSuffix(
			StringArgumentType.getString(context, "serials")
		);
		String rawSerials = confirmSuffixParseResult.payload();
		boolean confirmed = confirmSuffixParseResult.confirmed();
		SerialParseUtil.TargetParseResult parseResult = SerialParseUtil.parseTargets(rawSerials, MAX_BATCH_RETIRE_SERIALS);
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
					"message.redstonelink.retire.batch.too_many",
					MAX_BATCH_RETIRE_SERIALS
				)
			);
			return 0;
		}
		Set<Long> targetSerials = parseResult.targets();
		if (targetSerials.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.retire.batch.empty"));
			return 0;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.batch_serials_deduped",
					formatSerialCollection(parseResult.duplicateEntries())
				),
				false
			);
		}

		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		List<Long> inactiveSerials = new ArrayList<>();
		for (long serial : targetSerials) {
			boolean active = savedData.isSerialAllocated(type, serial) && !savedData.isSerialRetired(type, serial);
			if (!active) {
				inactiveSerials.add(serial);
			}
		}
		if (!inactiveSerials.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.retire.batch.invalid_serials",
					typeCommandName(type),
					formatSerialCollection(inactiveSerials)
				)
			);
			return 0;
		}

		if (!confirmed) {
			String confirmCommand = "redstonelink retire batch "
				+ typeCommandName(type)
				+ " "
				+ rawSerials
				+ " confirm";
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.retire.batch.confirm_required",
					typeCommandName(type),
					targetSerials.size(),
					confirmCommand
				)
			);
			return 0;
		}

		int changedCount = 0;
		int nodeRemovedCount = 0;
		int linksRemoved = 0;
		for (long serial : targetSerials) {
			LinkSavedData.RetireResult retireResult = savedData.retireNode(type, serial);
			boolean changed = retireResult.nodeRemoved() || retireResult.linksRemoved() > 0 || retireResult.retiredMarked();
			if (changed) {
				changedCount++;
			}
			if (retireResult.nodeRemoved()) {
				nodeRemovedCount++;
			}
			linksRemoved += retireResult.linksRemoved();
		}
		final int totalInput = targetSerials.size();
		final int finalChangedCount = changedCount;
		final int finalNodeRemovedCount = nodeRemovedCount;
		final int finalLinksRemoved = linksRemoved;
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.retire.batch.done",
				typeCommandName(type),
				totalInput,
				finalChangedCount,
				finalNodeRemovedCount,
				finalLinksRemoved
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析批量序列参数末尾的二次确认后缀（` confirm`）。
	 *
	 * @param rawText 原始参数文本
	 * @return 去后缀后的文本与确认标记
	 */
	private static ConfirmSuffixParseResult parseConfirmSuffix(String rawText) {
		String normalized = rawText == null ? "" : rawText.trim();
		String suffix = " confirm";
		if (normalized.endsWith(suffix)) {
			String payload = normalized.substring(0, normalized.length() - suffix.length()).trim();
			if (!payload.isEmpty()) {
				return new ConfirmSuffixParseResult(payload, true);
			}
		}
		return new ConfirmSuffixParseResult(normalized, false);
	}

	/**
	 * 二次确认后缀解析结果。
	 *
	 * @param payload 去后缀后的有效参数文本
	 * @param confirmed 是否携带确认后缀
	 */
	private record ConfirmSuffixParseResult(String payload, boolean confirmed) {
	}

	/**
	 * 解析退役目标类型参数。
	 */
	private static LinkNodeType parseNodeType(CommandSourceStack source, String rawType) {
		return LinkNodeSemantics.tryParseType(rawType).orElseGet(() -> {
			source.sendFailure(Component.translatable("message.redstonelink.node.invalid_type", rawType));
			return null;
		});
	}

	/**
	 * 命令参数中的类型名称。
	 */
	private static String typeCommandName(LinkNodeType type) {
		return type == LinkNodeType.BUTTON ? "button" : "core";
	}

	/**
	 * 格式化序号集合（稳定升序、逗号分隔）。
	 */
	private static String formatSerialCollection(Collection<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return "-";
		}
		return serials.stream()
			.sorted()
			.map(String::valueOf)
			.reduce((left, right) -> left + ", " + right)
			.orElse("-");
	}
}
