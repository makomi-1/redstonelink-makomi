package com.makomi.command.node;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.NodeAliasServerSupport;
import com.makomi.data.NodeIdentitySnapshot;
import com.makomi.data.RepeaterAliasMirrorSupport;
import com.makomi.data.LinkSavedData;
import com.makomi.util.ServerSerialValidationUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * `node alias` 命令注册器。
 * <p>
 * 第一版仅负责别名维护与反查，不改变任何写入命令的序号输入语义。
 * </p>
 */
public final class NodeAliasCommandRegistry {
	private NodeAliasCommandRegistry() {
	}

	/**
	 * 构建 `node alias` 子命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("alias")
			.requires(CommandTreeSupport::hasOtherCommandPermission)
			.then(
				Commands.literal("set").then(
					Commands.argument("type", StringArgumentType.word()).then(
						Commands.argument("serial", LongArgumentType.longArg(1L)).then(
							Commands.argument("alias", StringArgumentType.greedyString()).executes(NodeAliasCommandRegistry::executeSet)
						)
					)
				)
			)
			.then(
				Commands.literal("remove").then(
					Commands.argument("type", StringArgumentType.word()).then(
						Commands.argument("serial", LongArgumentType.longArg(1L)).executes(NodeAliasCommandRegistry::executeRemove)
					)
				)
			)
			.then(
				Commands.literal("resolve").then(
					Commands.argument("alias", StringArgumentType.greedyString()).executes(NodeAliasCommandRegistry::executeResolve)
				)
			)
			.then(
				Commands.literal("list")
					.executes(NodeAliasCommandRegistry::executeListAll)
					.then(Commands.argument("type", StringArgumentType.word()).executes(NodeAliasCommandRegistry::executeListByType))
			);
	}

	private static int executeSet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		boolean valid = type == LinkNodeType.TRIGGER_SOURCE
			? ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, type, serial)
			: ServerSerialValidationUtil.validateTargetSerialActive(source, savedData, type, serial);
		if (!valid) {
			return 0;
		}

		String rawAlias = StringArgumentType.getString(context, "alias");
		NodeAliasSavedData.UpsertResult result = RepeaterAliasMirrorSupport.upsert(source.getLevel(), type, serial, rawAlias);
		if (!result.valid()) {
			sendAliasValidationFailure(source, rawAlias, result.validation());
			return 0;
		}
		if (result.conflict()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.node.alias.conflict",
					result.alias(),
					LinkNodeSemantics.toSemanticName(type),
					NodeAliasDisplayUtil.formatSerialToken(result.conflictSerial())
				)
			);
			return 0;
		}
		if (!result.changed()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.node.alias.unchanged",
					LinkNodeSemantics.toSemanticName(type),
					NodeAliasDisplayUtil.formatDisplayText(result.alias(), serial)
				),
				false
			);
			return Command.SINGLE_SUCCESS;
		}

		RepeaterAliasMirrorSupport.syncDisplaysAfterAliasChanged(source.getLevel(), type, serial);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.alias.set",
				LinkNodeSemantics.toSemanticName(type),
				NodeAliasDisplayUtil.formatDisplayText(result.alias(), serial),
				result.previousAlias().isEmpty() ? "-" : result.previousAlias()
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int executeRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		NodeAliasSavedData.RemoveResult result = RepeaterAliasMirrorSupport.remove(source.getLevel(), type, serial);
		if (!result.removed()) {
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.node.alias.not_found",
					LinkNodeSemantics.toSemanticName(type),
					NodeAliasDisplayUtil.formatSerialToken(serial)
				)
			);
			return 0;
		}

		RepeaterAliasMirrorSupport.syncDisplaysAfterAliasChanged(source.getLevel(), type, serial);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.alias.removed",
				LinkNodeSemantics.toSemanticName(type),
				NodeAliasDisplayUtil.formatSerialToken(serial),
				result.alias()
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int executeResolve(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		String alias = StringArgumentType.getString(context, "alias");
		NodeAliasSavedData.ValidationResult validation = NodeAliasSavedData.validateAlias(alias);
		if (!validation.valid()) {
			sendAliasValidationFailure(source, alias, validation);
			return 0;
		}

		List<NodeAliasSavedData.Entry> entries = NodeAliasSavedData.get(source.getLevel()).resolveAllByAlias(alias);
		if (entries.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.node.alias.resolve.not_found", validation.normalizedAlias()));
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.alias.resolve.header",
				validation.normalizedAlias(),
				entries.size()
			),
			false
		);
		for (NodeAliasSavedData.Entry entry : entries) {
			NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(source.getLevel(), entry.nodeType(), entry.serial());
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.node.alias.resolve.entry",
					LinkNodeSemantics.toSemanticName(entry.nodeType()),
					NodeAliasDisplayUtil.formatDisplayText(entry.alias(), entry.serial()),
					Boolean.toString(identity.allocated()),
					Boolean.toString(identity.retired()),
					Boolean.toString(identity.online())
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int executeListAll(CommandContext<CommandSourceStack> context) {
		return executeList(context.getSource(), null);
	}

	private static int executeListByType(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		return executeList(source, type);
	}

	private static int executeList(CommandSourceStack source, LinkNodeType type) {
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		List<NodeAliasSavedData.Entry> entries = NodeAliasSavedData.get(source.getLevel()).list(type);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.alias.list.header",
				type == null
					? Component.translatable("message.redstonelink.node.alias.scope.all")
					: Component.literal(LinkNodeSemantics.toSemanticName(type)),
				entries.size()
			),
			false
		);
		if (entries.isEmpty()) {
			return Command.SINGLE_SUCCESS;
		}
		for (NodeAliasSavedData.Entry entry : entries) {
			NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(source.getLevel(), entry.nodeType(), entry.serial());
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.node.alias.list.entry",
					LinkNodeSemantics.toSemanticName(entry.nodeType()),
					NodeAliasDisplayUtil.formatDisplayText(entry.alias(), entry.serial()),
					Boolean.toString(identity.retired()),
					Boolean.toString(identity.online())
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static void sendAliasValidationFailure(
		CommandSourceStack source,
		String rawAlias,
		NodeAliasSavedData.ValidationResult validation
	) {
		String reason = validation == null ? "" : validation.reason();
		source.sendFailure(
			Component.translatable(
				"message.redstonelink.node.alias.invalid",
				rawAlias == null ? "" : rawAlias,
				resolveValidationReasonComponent(reason),
				Integer.toString(NodeAliasSavedData.maxAliasLength())
			)
		);
	}

	private static Component resolveValidationReasonComponent(String reason) {
		if (reason == null || reason.isBlank()) {
			return Component.translatable("message.redstonelink.node.alias.reason.unknown");
		}
		return Component.translatable("message.redstonelink.node.alias.reason." + reason);
	}
}
