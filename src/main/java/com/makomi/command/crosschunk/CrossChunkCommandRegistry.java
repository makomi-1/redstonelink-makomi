package com.makomi.command.crosschunk;

import com.makomi.command.semantic.SemanticCommandMessageAdapter;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkConfig.CrossChunkPreset;
import com.makomi.data.CrossChunkWhitelistSavedData;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.util.SerialParseUtil;
import com.makomi.util.ServerSerialValidationUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * 跨区块命令注册器。
 * <p>
 * 该模块负责动态白名单增删查清与只读 preset 展示。
 * </p>
 */
public final class CrossChunkCommandRegistry {
	private static final int WHITELIST_SET_MAX_SERIALS = 1024;

	private CrossChunkCommandRegistry() {
	}

	/**
	 * 构建 `crosschunk` 命令树根节点。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("crosschunk")
			.requires(source -> RedstoneLinkConfig.crossChunkCommandEnabled()
				&& source.hasPermission(RedstoneLinkConfig.crossChunkCommandPermissionLevel()))
			.then(
				Commands
					.literal("whitelist")
					.then(
						Commands
							.literal("add")
							.then(
								Commands.argument("role", StringArgumentType.word()).then(
									Commands.argument("type", StringArgumentType.word()).then(
										Commands.argument("serial", LongArgumentType.longArg(1L)).executes(
											CrossChunkCommandRegistry::executeWhitelistAdd
										)
									)
								)
							)
					)
					.then(
						Commands
							.literal("remove")
							.then(
								Commands.argument("role", StringArgumentType.word()).then(
									Commands.argument("type", StringArgumentType.word()).then(
										Commands.argument("serial", LongArgumentType.longArg(1L)).executes(
											CrossChunkCommandRegistry::executeWhitelistRemove
										)
									)
								)
							)
					)
					.then(
						Commands
							.literal("list")
							.then(
								Commands.argument("role", StringArgumentType.word()).then(
									Commands.argument("type", StringArgumentType.word()).executes(
										CrossChunkCommandRegistry::executeWhitelistList
									)
								)
							)
					)
					.then(
						Commands
							.literal("clear")
							.then(
								Commands.argument("role", StringArgumentType.word()).then(
									Commands.argument("type", StringArgumentType.word()).executes(
										CrossChunkCommandRegistry::executeWhitelistClear
									)
								)
							)
					)
						.then(
							Commands
								.literal("set")
								.then(
									Commands.argument("role", StringArgumentType.word()).then(
										Commands.argument("type", StringArgumentType.word()).then(
											Commands.argument("serials", StringArgumentType.greedyString())
												.executes(CrossChunkCommandRegistry::executeWhitelistSet)
										)
									)
								)
						)
			)
			.then(
				Commands
					.literal("preset")
					.then(Commands.literal("list").executes(CrossChunkCommandRegistry::executePresetList))
					.then(
						Commands
							.literal("show")
							.then(Commands.argument("name", StringArgumentType.word()).executes(
								CrossChunkCommandRegistry::executePresetShow
							))
					)
			);
	}

	/**
	 * 执行白名单新增。
	 */
	private static int executeWhitelistAdd(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			return 0;
		}

		long serial = LongArgumentType.getLong(context, "serial");
		ServerLevel level = source.getLevel();
		LinkSavedData linkSavedData = LinkSavedData.get(level);
		boolean serialValid = validateSerial(source, linkSavedData, parsed.role(), parsed.type(), serial);
		if (!serialValid) {
			return 0;
		}

		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(level);
		boolean changed = whitelistSavedData.add(parsed.type(), serial, parsed.role());
		if (!changed) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.exists",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serial
			));
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.added",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serial
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行白名单移除。
	 */
	private static int executeWhitelistRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			return 0;
		}
		long serial = LongArgumentType.getLong(context, "serial");
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(source.getLevel());
		boolean changed = whitelistSavedData.remove(parsed.type(), serial, parsed.role());
		if (!changed) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.not_found",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serial
			));
			return 0;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.removed",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serial
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行白名单列表查询。
	 */
	private static int executeWhitelistList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			return 0;
		}
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(source.getLevel());
		Set<Long> serials = whitelistSavedData.list(parsed.type(), parsed.role());
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.list",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serials.size(),
				formatSerialSet(serials)
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行白名单清空。
	 */
	private static int executeWhitelistClear(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			return 0;
		}
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(source.getLevel());
		int removed = whitelistSavedData.clear(parsed.type(), parsed.role());
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.cleared",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				removed
			),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行白名单批量覆盖（set）。
	 */
	private static int executeWhitelistSet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			return 0;
		}

		ConfirmSuffixParseResult confirmSuffixParseResult = parseConfirmSuffix(
			StringArgumentType.getString(context, "serials")
		);
		String rawSerials = confirmSuffixParseResult.payload();
		boolean confirmed = confirmSuffixParseResult.confirmed();
		SerialParseUtil.TargetParseResult parseResult = SerialParseUtil.parseTargets(rawSerials, WHITELIST_SET_MAX_SERIALS);
		if (!parseResult.invalidEntries().isEmpty()) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			));
			return 0;
		}
		if (parseResult.exceedLimit()) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.too_many",
				WHITELIST_SET_MAX_SERIALS
			));
			return 0;
		}
		Set<Long> targetSerials = parseResult.targets();
		if (targetSerials.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.crosschunk.whitelist.set.empty"));
			return 0;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.crosschunk.whitelist.set.duplicates_deduped",
					formatSerialCollection(parseResult.duplicateEntries())
				),
				false
			);
		}

		LinkSavedData savedData = LinkSavedData.get(source.getLevel());
		List<Long> invalidSerials = new ArrayList<>();
		for (long serial : targetSerials) {
			boolean active = savedData.isSerialAllocated(parsed.type(), serial) && !savedData.isSerialRetired(parsed.type(), serial);
			if (!active) {
				invalidSerials.add(serial);
			}
		}
		if (!invalidSerials.isEmpty()) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.invalid_serials",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				formatSerialCollection(invalidSerials)
			));
			return 0;
		}

		if (!confirmed) {
			String confirmCommand = "redstonelink crosschunk whitelist set "
				+ roleName(parsed.role())
				+ " "
				+ LinkNodeSemantics.toSemanticName(parsed.type())
				+ " "
				+ rawSerials
				+ " confirm";
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.confirm_required",
				targetSerials.size(),
				confirmCommand
			));
			return 0;
		}

		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(source.getLevel());
		int removed = whitelistSavedData.clear(parsed.type(), parsed.role());
		int added = 0;
		for (long serial : targetSerials) {
			if (whitelistSavedData.add(parsed.type(), serial, parsed.role())) {
				added++;
			}
		}
		final int addedCount = added;
		final int removedCount = removed;
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.done",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				addedCount,
				removedCount
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
	 * 列出只读 preset 名称。
	 */
	private static int executePresetList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<String> presetNames = RedstoneLinkConfig.crossChunkPresetNames();
		if (presetNames.isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable("message.redstonelink.crosschunk.preset.none"),
				false
			);
			return Command.SINGLE_SUCCESS;
		}
		source.sendSuccess(
			() -> Component.translatable("message.redstonelink.crosschunk.preset.list", String.join(", ", presetNames)),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 展示指定只读 preset 详情。
	 */
	private static int executePresetShow(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String presetName = StringArgumentType.getString(context, "name");
		Optional<CrossChunkPreset> preset = RedstoneLinkConfig.crossChunkPreset(presetName);
		if (preset.isEmpty()) {
			source.sendFailure(Component.translatable("message.redstonelink.crosschunk.preset.not_found", presetName));
			return 0;
		}

		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.preset.show",
				presetName,
				formatPresetBucket(preset.get().sources()),
				formatPresetBucket(preset.get().targets())
			),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 解析并校验 role + type 参数。
	 */
	private static ParsedRoleAndType parseRoleAndType(
		CommandContext<CommandSourceStack> context,
		CommandSourceStack source
	) {
		String roleArg = StringArgumentType.getString(context, "role");
		LinkNodeSemantics.Role role = parseRole(source, roleArg);
		if (role == null) {
			return null;
		}
		String typeArg = StringArgumentType.getString(context, "type");
		LinkNodeType type = parseType(source, role, typeArg);
		if (type == null) {
			return null;
		}
		return new ParsedRoleAndType(role, type);
	}

	/**
	 * 解析 role 参数。
	 */
	private static LinkNodeSemantics.Role parseRole(CommandSourceStack source, String rawRole) {
		if (rawRole == null) {
			source.sendFailure(Component.translatable("message.redstonelink.crosschunk.invalid_role", "null"));
			return null;
		}
		String normalized = rawRole.trim();
		if ("source".equalsIgnoreCase(normalized)) {
			return LinkNodeSemantics.Role.SOURCE;
		}
		if ("target".equalsIgnoreCase(normalized)) {
			return LinkNodeSemantics.Role.TARGET;
		}
		source.sendFailure(Component.translatable("message.redstonelink.crosschunk.invalid_role", rawRole));
		return null;
	}

	/**
	 * 解析 type 参数并执行语义与配置校验。
	 */
	private static LinkNodeType parseType(
		CommandSourceStack source,
		LinkNodeSemantics.Role role,
		String rawType
	) {
		var parsedType = LinkNodeSemantics.tryParseCanonicalType(rawType);
		if (parsedType.isEmpty()) {
			source.sendFailure(SemanticCommandMessageAdapter.invalidType(rawType));
			return null;
		}
		String semanticTypeName = LinkNodeSemantics.toSemanticName(parsedType.get());
		Set<LinkNodeType> allowedTypes = role == LinkNodeSemantics.Role.SOURCE
			? RedstoneLinkConfig.crossChunkAllowedSourceTypes()
			: RedstoneLinkConfig.crossChunkAllowedTargetTypes();
		var semanticResult = LinkNodeSemantics.resolveTypeForRole(semanticTypeName, role, allowedTypes);
		if (semanticResult.isSuccess()) {
			return semanticResult.value();
		}
		source.sendFailure(
			SemanticCommandMessageAdapter.resolveTypeFailure(
				semanticResult.error(),
				semanticTypeName,
				role,
				allowedTypes
			)
		);
		return null;
	}

	/**
	 * 校验序号是否处于“已分配且未退役”状态。
	 */
	private static boolean validateSerial(
		CommandSourceStack source,
		LinkSavedData savedData,
		LinkNodeSemantics.Role role,
		LinkNodeType type,
		long serial
	) {
		return role == LinkNodeSemantics.Role.SOURCE
			? ServerSerialValidationUtil.validateSourceSerialActive(source, savedData, type, serial)
			: ServerSerialValidationUtil.validateTargetSerialActive(source, savedData, type, serial);
	}

	/**
	 * 格式化序号集合。
	 */
	private static String formatSerialSet(Set<Long> serials) {
		if (serials.isEmpty()) {
			return "-";
		}
		return serials.stream()
			.sorted()
			.map(String::valueOf)
			.reduce((left, right) -> left + ", " + right)
			.orElse("-");
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

	/**
	 * 格式化 preset 单侧桶内容。
	 */
	private static String formatPresetBucket(Map<LinkNodeType, Set<Long>> bucket) {
		if (bucket.isEmpty()) {
			return "-";
		}
		List<String> lines = new ArrayList<>();
		bucket.entrySet()
			.stream()
			.sorted(Comparator.comparing(entry -> entry.getKey().name()))
			.forEach(entry -> {
				String serialText = formatSerialSet(entry.getValue());
				lines.add(LinkNodeSemantics.toSemanticName(entry.getKey()) + ":" + serialText);
			});
		return String.join(" | ", lines);
	}

	/**
	 * 语义角色展示名。
	 */
	private static String roleName(LinkNodeSemantics.Role role) {
		return role == LinkNodeSemantics.Role.SOURCE ? "source" : "target";
	}

	private record ParsedRoleAndType(LinkNodeSemantics.Role role, LinkNodeType type) {}
}
