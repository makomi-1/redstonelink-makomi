package com.makomi.command.link;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkWriteProtectedSavedData;
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
 * `link write_control` 命令注册器。
 * <p>
 * 负责受控名单的增删改查与批量覆盖确认流程。
 * </p>
 */
public final class LinkWriteControlCommandRegistry {
	private LinkWriteControlCommandRegistry() {
	}

	/**
	 * 构建 `write_control` 命令树根节点。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("write_control")
			.requires(source -> source.hasPermission(RedstoneLinkConfig.linkWriteProtectedManagePermissionLevel()))
			.then(
				Commands
					.literal("protected")
					.then(
						Commands
							.literal("add")
							.then(
								Commands.argument("type", StringArgumentType.word()).then(
									Commands.argument("serial", LongArgumentType.longArg(1L)).executes(
										LinkWriteControlCommandRegistry::executeWriteProtectedAdd
									)
								)
							)
					)
					.then(
						Commands
							.literal("remove")
							.then(
								Commands.argument("type", StringArgumentType.word()).then(
									Commands.argument("serial", LongArgumentType.longArg(1L)).executes(
										LinkWriteControlCommandRegistry::executeWriteProtectedRemove
									)
								)
							)
					)
					.then(
						Commands
							.literal("list")
							.then(
								Commands.argument("type", StringArgumentType.word()).executes(
									LinkWriteControlCommandRegistry::executeWriteProtectedList
								)
							)
					)
					.then(
						Commands
							.literal("set")
							.then(
								Commands.argument("type", StringArgumentType.word()).then(
									Commands.argument("serials", SerialBatchArgumentType.serialBatch())
										.executes(context -> executeWriteProtectedSet(context, false))
										.then(
											Commands.literal("confirm").executes(context -> executeWriteProtectedSet(context, true))
										)
								)
							)
					)
			);
	}

	/**
	 * 执行 write_control protected add。
	 */
	private static int executeWriteProtectedAdd(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.LINK_RW, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData linkSavedData = LinkSavedData.get(source.getLevel());
		if (!LinkCommandSupport.validateWriteProtectedActiveSerial(source, linkSavedData, type, serial)) {
			return 0;
		}

		LinkWriteProtectedSavedData protectedSavedData = LinkWriteProtectedSavedData.get(source.getLevel());
		boolean changed = protectedSavedData.add(type, serial);
		if (!changed) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.write_control.protected.exists",
					CommandTreeSupport.typeCommandName(type),
					serial
				)
			);
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.write_control.protected.added",
				CommandTreeSupport.typeCommandName(type),
				serial
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行 write_control protected remove。
	 */
	private static int executeWriteProtectedRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.LINK_RW, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		LinkWriteProtectedSavedData protectedSavedData = LinkWriteProtectedSavedData.get(source.getLevel());
		boolean changed = protectedSavedData.remove(type, serial);
		if (!changed) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.write_control.protected.not_found",
					CommandTreeSupport.typeCommandName(type),
					serial
				)
			);
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.write_control.protected.removed",
				CommandTreeSupport.typeCommandName(type),
				serial
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行 write_control protected list。
	 */
	private static int executeWriteProtectedList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.LINK_RW, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		Set<Long> serials = LinkWriteProtectedSavedData.get(source.getLevel()).list(type);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.write_control.protected.list",
				CommandTreeSupport.typeCommandName(type),
				serials.size(),
				CommandTreeSupport.formatSerialCollection(serials)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行 write_control protected set（批量覆盖，支持 confirm 二次确认）。
	 */
	private static int executeWriteProtectedSet(
		CommandContext<CommandSourceStack> context,
		boolean confirmed
	) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}

		String rawSerials = SerialBatchArgumentType.getSerialBatch(context, "serials");
		int maxSetSerials = RedstoneLinkConfig.writeControlProtectedSetMaxSerials();
		LinkCommandSupport.TargetParseResult parseResult = LinkCommandSupport.parseTargetSerials(rawSerials, maxSetSerials);
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
					"message.redstonelink.write_control.protected.set.too_many",
					maxSetSerials
				)
			);
			return 0;
		}

		Set<Long> targetSerials = parseResult.targets();
		if (targetSerials.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.write_control.protected.set.empty"));
			return 0;
		}
		int commandCost = CommandRateLimitService.computeBatchCost(3, targetSerials.size(), 64);
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.LINK_RW,
				commandCost
			)
		) {
			return 0;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.batch_serials_deduped",
					CommandTreeSupport.formatSerialCollection(parseResult.duplicateEntries())
				),
				false
			);
		}

		LinkSavedData linkSavedData = LinkSavedData.get(source.getLevel());
		List<Long> invalidSerials = new ArrayList<>();
		for (long serial : targetSerials) {
			if (!LinkCommandSupport.isWriteProtectedActiveSerial(linkSavedData, type, serial)) {
				invalidSerials.add(serial);
			}
		}
		if (!invalidSerials.isEmpty()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.write_control.protected.set.invalid_serials",
					CommandTreeSupport.typeCommandName(type),
					CommandTreeSupport.formatSerialCollection(invalidSerials)
				)
			);
			return 0;
		}

		if (!confirmed) {
			String confirmCommand = "redstonelink link write_control protected set "
				+ CommandTreeSupport.typeCommandName(type)
				+ " "
				+ rawSerials
				+ " confirm";
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.write_control.protected.set.confirm_required",
					targetSerials.size(),
					confirmCommand
				)
			);
			return 0;
		}

		LinkWriteProtectedSavedData protectedSavedData = LinkWriteProtectedSavedData.get(source.getLevel());
		LinkWriteProtectedSavedData.ReplaceProtectedResult replaceResult = protectedSavedData.replace(type, targetSerials);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.write_control.protected.set.done",
				CommandTreeSupport.typeCommandName(type),
				targetSerials.size(),
				replaceResult.addedCount(),
				replaceResult.removedCount(),
				replaceResult.changedCount()
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}
}
