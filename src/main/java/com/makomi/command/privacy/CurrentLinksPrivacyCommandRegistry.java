package com.makomi.command.privacy;

import com.makomi.command.CommandSuffixParser;
import com.makomi.command.CommandNodeTypeParseUtil;
import com.makomi.data.CurrentLinksPrivacySavedData;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.util.SerialCollectionFormatUtil;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * `privacy current_links mask` 命令注册器。
 * <p>
 * 负责维护“当前连接信息”部分加密名单（triggerSource/core）。
 * </p>
 */
public final class CurrentLinksPrivacyCommandRegistry {
	private static final int MASK_SET_MAX_SERIALS = 1024;

	private CurrentLinksPrivacyCommandRegistry() {
	}

	/**
	 * 构建 `privacy` 命令树根节点。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("privacy")
			.then(
				Commands
					.literal("current_links")
					.then(
						Commands
							.literal("mask")
							.then(
								Commands
									.literal("add")
									.then(
										Commands.argument("type", StringArgumentType.word()).then(
											Commands.argument("serial", LongArgumentType.longArg(1L))
												.executes(CurrentLinksPrivacyCommandRegistry::executeMaskAdd)
										)
									)
							)
							.then(
								Commands
									.literal("remove")
									.then(
										Commands.argument("type", StringArgumentType.word()).then(
											Commands.argument("serial", LongArgumentType.longArg(1L))
												.executes(CurrentLinksPrivacyCommandRegistry::executeMaskRemove)
										)
									)
							)
							.then(
								Commands
									.literal("list")
									.then(
										Commands.argument("type", StringArgumentType.word())
											.executes(CurrentLinksPrivacyCommandRegistry::executeMaskList)
									)
							)
							.then(
								Commands
									.literal("set")
									.then(
										Commands.argument("type", StringArgumentType.word()).then(
											Commands.argument("serials", StringArgumentType.greedyString())
												.executes(CurrentLinksPrivacyCommandRegistry::executeMaskSet)
										)
									)
							)
					)
			);
	}

	/**
	 * 执行 mask add。
	 */
	private static int executeMaskAdd(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseType(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData linkSavedData = LinkSavedData.get(source.getLevel());
		if (!validateActiveSerial(source, linkSavedData, type, serial)) {
			return 0;
		}

		CurrentLinksPrivacySavedData privacySavedData = CurrentLinksPrivacySavedData.get(source.getLevel());
		boolean changed = privacySavedData.add(type, serial);
		if (!changed) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.privacy.current_links.mask.exists",
					LinkNodeSemantics.toSemanticName(type),
					serial
				)
			);
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.privacy.current_links.mask.added",
				LinkNodeSemantics.toSemanticName(type),
				serial
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行 mask remove。
	 */
	private static int executeMaskRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseType(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		CurrentLinksPrivacySavedData privacySavedData = CurrentLinksPrivacySavedData.get(source.getLevel());
		boolean changed = privacySavedData.remove(type, serial);
		if (!changed) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.privacy.current_links.mask.not_found",
					LinkNodeSemantics.toSemanticName(type),
					serial
				)
			);
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.privacy.current_links.mask.removed",
				LinkNodeSemantics.toSemanticName(type),
				serial
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行 mask list。
	 */
	private static int executeMaskList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseType(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		Set<Long> serials = CurrentLinksPrivacySavedData.get(source.getLevel()).list(type);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.privacy.current_links.mask.list",
				LinkNodeSemantics.toSemanticName(type),
				serials.size(),
				SerialCollectionFormatUtil.formatSortedCsv(serials)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行 mask set（批量覆盖，支持 confirm 二次确认）。
	 */
	private static int executeMaskSet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = parseType(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}

		CommandSuffixParser.ConfirmSuffixParseResult confirmSuffixParseResult = parseConfirmSuffix(
			StringArgumentType.getString(context, "serials")
		);
		String rawSerials = confirmSuffixParseResult.payload();
		boolean confirmed = confirmSuffixParseResult.confirmed();
		SerialParseUtil.TargetParseResult parseResult = SerialParseUtil.parseTargets(rawSerials, MASK_SET_MAX_SERIALS);
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
				Component.translatable("message.redstonelink.privacy.current_links.mask.set.too_many", MASK_SET_MAX_SERIALS)
			);
			return 0;
		}

		Set<Long> targetSerials = parseResult.targets();
		if (targetSerials.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.privacy.current_links.mask.set.empty"));
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

		LinkSavedData linkSavedData = LinkSavedData.get(source.getLevel());
		List<Long> invalidSerials = new ArrayList<>();
		for (long serial : targetSerials) {
			if (!linkSavedData.isSerialAllocated(type, serial) || linkSavedData.isSerialRetired(type, serial)) {
				invalidSerials.add(serial);
			}
		}
		if (!invalidSerials.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.privacy.current_links.mask.set.invalid_serials",
					LinkNodeSemantics.toSemanticName(type),
					SerialCollectionFormatUtil.formatSortedCsv(invalidSerials)
				)
			);
			return 0;
		}

		if (!confirmed) {
			String confirmCommand = "redstonelink privacy current_links mask set "
				+ LinkNodeSemantics.toSemanticName(type)
				+ " "
				+ rawSerials
				+ " confirm";
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.privacy.current_links.mask.set.confirm_required",
					targetSerials.size(),
					confirmCommand
				)
			);
			return 0;
		}

		CurrentLinksPrivacySavedData privacySavedData = CurrentLinksPrivacySavedData.get(source.getLevel());
		Set<Long> before = privacySavedData.list(type);
		int changed = privacySavedData.replace(type, targetSerials);
		int added = 0;
		int removed = 0;
		for (long serial : targetSerials) {
			if (!before.contains(serial)) {
				added++;
			}
		}
		for (long serial : before) {
			if (!targetSerials.contains(serial)) {
				removed++;
			}
		}
		final int finalAdded = added;
		final int finalRemoved = removed;
		final int finalChanged = changed;
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.privacy.current_links.mask.set.done",
				LinkNodeSemantics.toSemanticName(type),
				targetSerials.size(),
				finalAdded,
				finalRemoved,
				finalChanged
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析批量参数中的 confirm 后缀。
	 */
	private static CommandSuffixParser.ConfirmSuffixParseResult parseConfirmSuffix(String rawText) {
		return CommandSuffixParser.parseConfirmOnly(rawText, true);
	}

	/**
	 * 解析并校验类型参数（仅接受 triggerSource/core）。
	 */
	private static LinkNodeType parseType(CommandSourceStack source, String rawType) {
		return CommandNodeTypeParseUtil.parseCanonicalTypeOrSendDefaultFailure(source, rawType);
	}

	/**
	 * 校验序号是否处于激活状态（已分配且未退役）。
	 */
	private static boolean validateActiveSerial(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeType type,
		long serial
	) {
		if (savedData.isSerialAllocated(type, serial) && !savedData.isSerialRetired(type, serial)) {
			return true;
		}
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.privacy.current_links.mask.invalid_serial",
				LinkNodeSemantics.toSemanticName(type),
				serial
			)
		);
		return false;
	}

}
