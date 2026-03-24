package com.makomi.command.audit;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * `audit` 命令注册器。
 * <p>
 * 负责链接存量审计摘要的文本与 CSV 输出。
 * </p>
 */
public final class AuditCommandRegistry {
	private AuditCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink audit` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("audit")
			.requires(CommandTreeSupport::hasOtherCommandPermission)
			.executes(AuditCommandRegistry::executeAudit)
			.then(
				Commands
					.literal("summary")
					.executes(AuditCommandRegistry::executeAuditSummaryText)
					.then(
						Commands
							.argument("format", StringArgumentType.word())
							.executes(AuditCommandRegistry::executeAuditSummaryByFormat)
					)
			);
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
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
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
				CommandTreeSupport.formatSerialSet(savedData.getActiveSerials(LinkNodeType.CORE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.core_retired_serials",
				CommandTreeSupport.formatSerialSet(savedData.getRetiredSerials(LinkNodeType.CORE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.button_active_serials",
				CommandTreeSupport.formatSerialSet(savedData.getActiveSerials(LinkNodeType.TRIGGER_SOURCE))
			),
			false
		);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.audit.button_retired_serials",
				CommandTreeSupport.formatSerialSet(savedData.getRetiredSerials(LinkNodeType.TRIGGER_SOURCE))
			),
			false
		);
		return Command.SINGLE_SUCCESS;
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
	 * 以“|”分隔格式化序列号集合，避免 CSV 与逗号冲突。
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

	private enum AuditOutputFormat {
		TEXT,
		CSV,
	}
}
