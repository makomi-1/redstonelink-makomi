package com.makomi.command.node;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.CommandTreeSupport;
import com.makomi.command.activate.ActivateCommandRegistry;
import com.makomi.command.retire.RetireBatchCommandRegistry;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkRetireCoordinator;
import com.makomi.data.LinkSavedData;
import com.makomi.data.NodeIdentitySnapshot;
import com.makomi.data.NodeRuntimeSnapshot;
import com.makomi.data.NodeSnapshotQueryService;
import com.makomi.util.ServerSerialValidationUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * `node` 命令注册器。
 * <p>
 * 负责节点基础信息查询、序列号列表、退役，以及子命令树装配。
 * </p>
 */
public final class NodeCommandRegistry {
	private static final int NODE_LIST_DEFAULT_LIMIT = 50;
	private static final int NODE_LIST_MAX_LIMIT = 1000;

	private NodeCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink node` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("node")
			.then(
				ActivateCommandRegistry.createRoot().requires(CommandTreeSupport::hasOtherCommandPermission)
			)
			.then(
				Commands
					.literal("retire")
					.requires(CommandTreeSupport::hasOtherCommandPermission)
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("serial", LongArgumentType.longArg(1L))
								.executes(NodeCommandRegistry::executeRetireRequireConfirmWithTypeArg)
								.then(
									Commands
										.literal("confirm")
										.executes(NodeCommandRegistry::executeRetireWithTypeArg)
								)
						)
					)
					.then(RetireBatchCommandRegistry.createBatchNode())
			)
			.then(
				Commands
					.literal("get")
					.requires(CommandTreeSupport::hasOtherCommandPermission)
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands
								.argument("serial", LongArgumentType.longArg(1L))
								.executes(NodeCommandRegistry::executeNodeGet)
						)
					)
			)
			.then(
				Commands
					.literal("list")
					.requires(CommandTreeSupport::hasOtherCommandPermission)
					.then(
						Commands.argument("type", StringArgumentType.word()).then(
							Commands.argument("scope", StringArgumentType.word())
								.executes(NodeCommandRegistry::executeNodeList)
								.then(
									Commands
										.argument("limit", IntegerArgumentType.integer(1, NODE_LIST_MAX_LIMIT))
										.executes(NodeCommandRegistry::executeNodeList)
										.then(
											Commands
												.argument("offset", IntegerArgumentType.integer(0))
												.executes(NodeCommandRegistry::executeNodeList)
										)
								)
						)
					)
			)
			.then(NodeTraceCommandRegistry.createRoot());
	}

	/**
	 * 查询单个节点详情。
	 */
	private static int executeNodeGet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}

		long serial = LongArgumentType.getLong(context, "serial");
		NodeSnapshotQueryService.NodeReadSnapshot readSnapshot = NodeSnapshotQueryService.query(
			source.getLevel(),
			type,
			serial,
			source.hasPermission(RedstoneLinkConfig.currentLinksPrivacyViewPermissionLevel())
		);
		NodeIdentitySnapshot identity = readSnapshot.identity();
		String dimensionText = identity.dimension() == null ? "-" : identity.dimension().location().toString();
		String posText = identity.pos() == null ? "-" : CommandTreeSupport.formatBlockPos(identity.pos());
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.get",
				CommandTreeSupport.typeCommandName(type),
				serial,
				Boolean.toString(identity.allocated()),
				Boolean.toString(identity.retired()),
				Boolean.toString(identity.online()),
				dimensionText,
				posText,
				readSnapshot.linksSnapshot().visibleTargetCount(),
				CommandTreeSupport.formatSerialList(readSnapshot.linksSnapshot().visibleTargets())
			),
			false
		);
		sendNodeRuntimeSnapshot(source, readSnapshot.runtimeSnapshot());
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 输出节点运行态快照。
	 */
	private static void sendNodeRuntimeSnapshot(CommandSourceStack source, NodeRuntimeSnapshot runtimeSnapshot) {
		if (source == null || runtimeSnapshot == null) {
			return;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.node.get.runtime",
				runtimeSnapshot.configuredMode(),
				runtimeSnapshot.effectiveMode(),
				Boolean.toString(runtimeSnapshot.active()),
				runtimeSnapshot.resolvedStrength(),
				runtimeSnapshot.outputPower(),
				CommandTreeSupport.formatSerialList(runtimeSnapshot.maxSourceSerials())
			),
			false
		);
	}

	/**
	 * 查询节点序列号列表（active/retired/online）。
	 */
	private static int executeNodeList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		NodeListScope scope = parseNodeListScopeArg(source, StringArgumentType.getString(context, "scope"));
		if (scope == null) {
			return 0;
		}

		int limit = CommandTreeSupport.getOptionalIntArg(context, "limit", NODE_LIST_DEFAULT_LIMIT);
		int offset = CommandTreeSupport.getOptionalIntArg(context, "offset", 0);
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
					CommandTreeSupport.typeCommandName(type),
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
				CommandTreeSupport.typeCommandName(type),
				scope.commandName(),
				total,
				page.size(),
				offset,
				CommandTreeSupport.formatSerialList(page)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * retire 第一阶段：仅提示确认，不执行退役。
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
				CommandTreeSupport.typeCommandName(type),
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
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
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
			source.sendFailure(
				Component.translatable(
					"message.redstonelink.retire.no_change",
					CommandTreeSupport.typeCommandName(type),
					serial
				)
			);
			return 0;
		}

		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.retire.done",
				CommandTreeSupport.typeCommandName(type),
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
		if (!CommandRateLimitService.tryAcquireOrSendFailure(source, CommandRateLimitService.CommandGroup.OTHER, 1)) {
			return 0;
		}
		LinkNodeType type = CommandTreeSupport.parseNodeTypeArg(source, StringArgumentType.getString(context, "type"));
		if (type == null) {
			return 0;
		}
		return executeRetire(context, type);
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
}
