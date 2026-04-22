package com.makomi.command.crosschunk;

import com.makomi.command.CommandNodeTypeParseUtil;
import com.makomi.command.CommandRateLimitService;
import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.command.semantic.SemanticCommandMessageAdapter;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.config.RedstoneLinkConfig.CrossChunkPreset;
import com.makomi.data.CrossChunkEffectiveWhitelistService;
import com.makomi.data.CrossChunkWhitelistSavedData;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.util.SerialCollectionFormatUtil;
import com.makomi.util.SerialParseUtil;
import com.makomi.util.ServerSerialValidationUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
	private static final String WHITELIST_LIST_SEPARATOR = "------------------------------";

	private CrossChunkCommandRegistry() {
	}

	/**
	 * 构建 `crosschunk` 命令树根节点。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("crosschunk")
			.requires(source -> RedstoneLinkConfig.crossChunk().commandEnabled()
				&& source.hasPermission(RedstoneLinkConfig.crossChunk().commandPermissionLevel()))
			.then(
				Commands
					.literal("whitelist")
						.then(
							Commands
								.literal("add")
								.then(
									Commands.argument("role", StringArgumentType.word()).then(
										Commands.argument("type", StringArgumentType.word()).then(
											Commands
												.argument("serial", LongArgumentType.longArg(1L))
												.executes(CrossChunkCommandRegistry::executeWhitelistAdd)
												.then(
													Commands.literal("resident").executes(
														CrossChunkCommandRegistry::executeWhitelistAddResident
													)
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
											Commands.argument("serials", SerialBatchArgumentType.serialBatch())
												.executes(context -> executeWhitelistSet(context, false, false))
												.then(
													Commands.literal("confirm")
														.executes(context -> executeWhitelistSet(context, false, true))
												)
												.then(
													Commands.literal("resident")
														.executes(context -> executeWhitelistSet(context, true, false))
														.then(
															Commands.literal("confirm")
																.executes(context -> executeWhitelistSet(context, true, true))
														)
												)
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
		return executeWhitelistAddInternal(context, false);
	}

	/**
	 * 执行白名单新增（常驻模式）。
	 */
	private static int executeWhitelistAddResident(CommandContext<CommandSourceStack> context) {
		return executeWhitelistAddInternal(context, true);
	}

	/**
	 * 执行白名单新增（可选常驻）。
	 *
	 * @param context Brigadier 命令上下文，提供 `role/type/serial` 参数
	 * @param resident 是否将新增项标记为 resident 白名单
	 * @return 成功时返回 `Command.SINGLE_SUCCESS`；任一校验失败时返回 `0`
	 */
	private static int executeWhitelistAddInternal(CommandContext<CommandSourceStack> context, boolean resident) {
		CommandSourceStack source = context.getSource();
		// 先做跨区块命令限流，避免批量白名单接口被频繁滥用。
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.CROSSCHUNK,
				1
			)
		) {
			return 0;
		}
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			// role/type 任一解析失败时，错误信息已由解析 helper 发送，这里直接终止。
			return 0;
		}

		long serial = LongArgumentType.getLong(context, "serial");
		ServerLevel level = source.getLevel();
		LinkSavedData linkSavedData = LinkSavedData.get(level);
		boolean serialValid = validateSerial(source, linkSavedData, parsed.role(), parsed.type(), serial);
		if (!serialValid) {
			// 序号必须处于“已分配且未退役”状态，否则不允许进入白名单。
			return 0;
		}
		boolean residentDeferred = resident && linkSavedData.findRuntimeOnlineNode(level, parsed.type(), serial).isEmpty();

		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(level);
		if (
			resident
				&& !ensureResidentCapacityForManualResidentChange(
					source,
					level,
					parsed.type(),
					parsed.role(),
					withSerial(whitelistSavedData.listResident(parsed.type(), parsed.role()), serial)
				)
		) {
			return 0;
		}
		var upsertResult = whitelistSavedData.upsert(parsed.type(), serial, parsed.role(), resident);
		if (!upsertResult.valid()) {
			// 数据层已拒绝本次写入，避免重复发送一层模糊错误。
			return 0;
		}
		if (!upsertResult.changed()) {
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.exists",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serial
			));
			return 0;
		}
		if (!upsertResult.created()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.crosschunk.whitelist.resident.updated",
					roleName(parsed.role()),
					LinkNodeSemantics.toSemanticName(parsed.type()),
					serial,
					residentModeName(resident)
				),
				true
			);
			if (residentDeferred) {
				source.sendSuccess(
					() -> Component.translatable(
						"message.redstonelink.crosschunk.whitelist.resident.offline_serial",
						roleName(parsed.role()),
						LinkNodeSemantics.toSemanticName(parsed.type()),
						serial
					),
					false
				);
			}
			return Command.SINGLE_SUCCESS;
		}
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.added",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serial,
				residentModeName(resident)
			),
			true
		);
		if (residentDeferred) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.crosschunk.whitelist.resident.offline_serial",
					roleName(parsed.role()),
					LinkNodeSemantics.toSemanticName(parsed.type()),
					serial
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行白名单移除。
	 *
	 * @param context Brigadier 命令上下文，提供 `role/type/serial` 参数
	 * @return 成功时返回 `Command.SINGLE_SUCCESS`；校验失败或未命中时返回 `0`
	 */
	private static int executeWhitelistRemove(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.CROSSCHUNK,
				1
			)
		) {
			return 0;
		}
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			// role/type 不合法时，解析阶段已发送错误文案。
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
	 *
	 * @param context Brigadier 命令上下文，提供 `role/type` 参数
	 * @return 查询命令固定返回 `Command.SINGLE_SUCCESS`；参数解析失败时返回 `0`
	 */
	private static int executeWhitelistList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.CROSSCHUNK,
				1
			)
		) {
			return 0;
		}
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			// role/type 不合法时，解析阶段已发送错误文案。
			return 0;
		}
		LinkSavedData linkSavedData = LinkSavedData.get(source.getLevel());
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(source.getLevel());
		Set<Long> serials = whitelistSavedData.list(parsed.type(), parsed.role());
		source.sendSuccess(() -> Component.literal(WHITELIST_LIST_SEPARATOR), false);
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.list",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				serials.size()
			),
			false
		);
		for (long serial : serials.stream().sorted().toList()) {
			boolean resident = whitelistSavedData.isResident(parsed.type(), serial, parsed.role());
			Optional<LinkSavedData.LinkNode> runtimeNode = linkSavedData.findRuntimeOnlineNode(
				source.getLevel(),
				parsed.type(),
				serial
			);
			boolean online = runtimeNode.isPresent();
			String dimension = runtimeNode.map(value -> value.dimension().location().toString()).orElse("-");
			String chunk = runtimeNode.map(value -> formatChunkPos(value.pos().getX(), value.pos().getZ())).orElse("-");
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.crosschunk.whitelist.list.entry",
					serial,
					Boolean.toString(online),
					residentModeName(resident),
					dimension,
					chunk
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 执行白名单清空。
	 *
	 * @param context Brigadier 命令上下文，提供 `role/type` 参数
	 * @return 成功时返回 `Command.SINGLE_SUCCESS`；参数解析失败时返回 `0`
	 */
	private static int executeWhitelistClear(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.CROSSCHUNK,
				1
			)
		) {
			return 0;
		}
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			// role/type 不合法时，解析阶段已发送错误文案。
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
	 *
	 * @param context Brigadier 命令上下文，提供 `role/type/serials` 参数
	 * @param resident 是否以 resident 模式覆盖白名单
	 * @param confirmed 是否已附带 `confirm` 二次确认后缀
	 * @return 成功时返回 `Command.SINGLE_SUCCESS`；任一前置校验失败时返回 `0`
	 */
	private static int executeWhitelistSet(
		CommandContext<CommandSourceStack> context,
		boolean resident,
		boolean confirmed
	) {
		CommandSourceStack source = context.getSource();
		ParsedRoleAndType parsed = parseRoleAndType(context, source);
		if (parsed == null) {
			// role/type 不合法时，解析阶段已发送错误文案。
			return 0;
		}

		String rawSerials = SerialBatchArgumentType.getSerialBatch(context, "serials");
		int maxWhitelistSetSerials = RedstoneLinkConfig.command().crossChunkWhitelistSetMaxSerials();
		SerialParseUtil.TargetParseResult parseResult = SerialParseUtil.parseTargets(rawSerials, maxWhitelistSetSerials);
		if (!parseResult.invalidEntries().isEmpty()) {
			// 只要存在非法 token，就拒绝整批 set，避免半成功覆盖让用户误判。
			source.sendFailure(Component.translatable(
				"message.redstonelink.invalid_target_tokens",
				String.join(", ", parseResult.invalidEntries())
			));
			return 0;
		}
		if (parseResult.exceedLimit()) {
			// 批量覆盖的上限走配置项控制，避免一次命令替换过大集合。
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.too_many",
				maxWhitelistSetSerials
			));
			return 0;
		}
		Set<Long> targetSerials = parseResult.targets();
		if (targetSerials.isEmpty()) {
			// 空集合没有可替换意义，也容易掩盖用户的命令拼写错误。
			source.sendFailure(Component.translatable("message.redstonelink.crosschunk.whitelist.set.empty"));
			return 0;
		}
		int commandCost = CommandRateLimitService.computeBatchCost(4, targetSerials.size(), 64);
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.CROSSCHUNK,
				commandCost
			)
		) {
			return 0;
		}
		if (!parseResult.duplicateEntries().isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.crosschunk.whitelist.set.duplicates_deduped",
					SerialCollectionFormatUtil.formatSortedCsv(parseResult.duplicateEntries())
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
			// 批量 set 要求所有序号都合法，避免只写入子集导致配置与用户预期不一致。
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.invalid_serials",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				SerialCollectionFormatUtil.formatSortedCsv(invalidSerials)
			));
			return 0;
		}
		if (
			resident
				&& !ensureResidentCapacityForManualResidentChange(
					source,
					source.getLevel(),
					parsed.type(),
					parsed.role(),
					targetSerials
				)
		) {
			return 0;
		}
		List<Long> offlineSerials = resident
			? collectOfflineSerials(source.getLevel(), savedData, parsed.type(), targetSerials)
			: List.of();

		if (!confirmed) {
			// `set` 属于整批覆盖语义，默认要求二次确认，防止误清空/误替换。
			String confirmCommand = "redstonelink crosschunk whitelist set "
				+ roleName(parsed.role())
				+ " "
				+ LinkNodeSemantics.toSemanticName(parsed.type())
				+ " "
				+ rawSerials
				+ (resident ? " resident" : "")
				+ " confirm";
			source.sendFailure(Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.confirm_required",
				targetSerials.size(),
				confirmCommand
			));
			return 0;
		}

		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(source.getLevel());
		CrossChunkWhitelistSavedData.ReplaceWhitelistResult replaceResult = whitelistSavedData.replace(
			parsed.type(),
			parsed.role(),
			targetSerials,
			resident
		);
		final int addedCount = replaceResult.addedCount();
		final int removedCount = replaceResult.removedCount();
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
		source.sendSuccess(
			() -> Component.translatable(
				"message.redstonelink.crosschunk.whitelist.set.mode",
				roleName(parsed.role()),
				LinkNodeSemantics.toSemanticName(parsed.type()),
				residentModeName(resident)
			),
			false
		);
		if (resident && !offlineSerials.isEmpty()) {
			source.sendSuccess(
				() -> Component.translatable(
					"message.redstonelink.crosschunk.whitelist.resident.offline_serials",
					roleName(parsed.role()),
					LinkNodeSemantics.toSemanticName(parsed.type()),
					SerialCollectionFormatUtil.formatSortedCsv(offlineSerials)
				),
				false
			);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 列出只读 preset 名称。
	 *
	 * @param context Brigadier 命令上下文，无额外参数
	 * @return 成功时返回 `Command.SINGLE_SUCCESS`；限流失败时返回 `0`
	 */
	private static int executePresetList(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.CROSSCHUNK,
				1
			)
		) {
			return 0;
		}
		List<String> presetNames = RedstoneLinkConfig.crossChunk().presetNames();
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
	 *
	 * @param context Brigadier 命令上下文，提供 `name` 参数
	 * @return 成功时返回 `Command.SINGLE_SUCCESS`；preset 不存在时返回 `0`
	 */
	private static int executePresetShow(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (
			!CommandRateLimitService.tryAcquireOrSendFailure(
				source,
				CommandRateLimitService.CommandGroup.CROSSCHUNK,
				1
			)
		) {
			return 0;
		}
		String presetName = StringArgumentType.getString(context, "name");
		Optional<CrossChunkPreset> preset = RedstoneLinkConfig.crossChunk().preset(presetName);
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
	 *
	 * @param context Brigadier 命令上下文，提供原始 `role/type` 文本
	 * @param source 当前命令来源，用于发送解析失败提示
	 * @return 成功时返回解析后的 role/type 组合；失败时返回 `null`
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
	 *
	 * @param source 当前命令来源，用于发送错误提示
	 * @param rawRole 用户输入的原始 role 文本
	 * @return 仅允许 `source/target`；不合法时返回 `null`
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
	 *
	 * @param source 当前命令来源，用于发送错误提示
	 * @param role 已解析出的语义角色，用于约束 type 方向
	 * @param rawType 用户输入的原始 type 文本
	 * @return 成功时返回合法 `LinkNodeType`；失败时返回 `null`
	 */
	private static LinkNodeType parseType(
		CommandSourceStack source,
		LinkNodeSemantics.Role role,
		String rawType
	) {
		LinkNodeType parsedType = CommandNodeTypeParseUtil.parseCanonicalTypeOrSendFailure(
			source,
			rawType,
			SemanticCommandMessageAdapter::invalidType
		);
		if (parsedType == null) {
			return null;
		}
		String semanticTypeName = LinkNodeSemantics.toSemanticName(parsedType);
		Set<LinkNodeType> allowedTypes = role == LinkNodeSemantics.Role.SOURCE
			? RedstoneLinkConfig.crossChunk().allowedSourceTypes()
			: RedstoneLinkConfig.crossChunk().allowedTargetTypes();
		var semanticResult = LinkNodeSemantics.resolveStrictTypeForRole(semanticTypeName, role, allowedTypes);
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
	 *
	 * @param source 当前命令来源，用于发送错误提示
	 * @param savedData 序号与节点数据视图
	 * @param role 当前白名单语义角色
	 * @param type 当前节点类型
	 * @param serial 待校验序号
	 * @return 合法时返回 `true`
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
	 * 收集批量 resident 设置中的离线序号。
	 *
	 * @param savedData 节点数据视图
	 * @param type 当前节点类型
	 * @param serials 本次批量 resident 序号集合
	 * @return 当前未在线节点集合，供结果文案提示“离线后生效”
	 */
	private static List<Long> collectOfflineSerials(
		ServerLevel contextLevel,
		LinkSavedData savedData,
		LinkNodeType type,
		Set<Long> serials
	) {
		List<Long> offlineSerials = new ArrayList<>();
		for (long serial : serials) {
			if (savedData.findRuntimeOnlineNode(contextLevel, type, serial).isEmpty()) {
				offlineSerials.add(serial);
			}
		}
		return offlineSerials;
	}

	/**
	 * 校验手动 resident 变更后的有效常驻总量是否仍在配置上限内。
	 */
	private static boolean ensureResidentCapacityForManualResidentChange(
		CommandSourceStack source,
		ServerLevel level,
		LinkNodeType type,
		LinkNodeSemantics.Role role,
		Set<Long> residentSerials
	) {
		if (source == null || level == null || type == null || role == null) {
			return false;
		}
		int effectiveResidents = CrossChunkEffectiveWhitelistService.countDistinctResidentsAfterManualResidentChange(
			level,
			type,
			role,
			residentSerials
		);
		int residentLimit = RedstoneLinkConfig.crossChunk().residentMaxEntries();
		if (effectiveResidents <= residentLimit) {
			return true;
		}
		source.sendFailure(Component.translatable(
			"message.redstonelink.crosschunk.whitelist.resident.limit_exceeded",
			effectiveResidents,
			residentLimit
		));
		return false;
	}

	/**
	 * 在原 resident 集合基础上追加单个序号，保持去重语义。
	 */
	private static Set<Long> withSerial(Set<Long> currentResidents, long serial) {
		Set<Long> nextResidents = new LinkedHashSet<>();
		if (currentResidents != null) {
			nextResidents.addAll(currentResidents);
		}
		if (serial > 0L) {
			nextResidents.add(serial);
		}
		return Set.copyOf(nextResidents);
	}

	/**
	 * 格式化区块坐标文本（chunkX,chunkZ）。
	 */
	private static String formatChunkPos(int blockX, int blockZ) {
		int chunkX = blockX >> 4;
		int chunkZ = blockZ >> 4;
		return chunkX + "," + chunkZ;
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
				String serialText = SerialCollectionFormatUtil.formatSortedCsv(entry.getValue());
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

	/**
	 * resident 模式展示文本。
	 */
	private static String residentModeName(boolean resident) {
		return resident ? "on" : "off";
	}

	private record ParsedRoleAndType(LinkNodeSemantics.Role role, LinkNodeType type) {}
}
