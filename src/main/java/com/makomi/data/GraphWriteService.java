package com.makomi.data;

import com.makomi.command.CommandRateLimitService;
import com.makomi.command.link.LinkChannelEditingService;
import com.makomi.command.link.LinkSetExecutionService;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.GraphWriteJsonSupport.GraphWriteOperation;
import com.makomi.data.GraphWriteJsonSupport.GraphWriteRequest;
import com.makomi.data.GraphWriteJsonSupport.ParseResult;
import com.makomi.data.GraphWriteJsonSupport.PreviewState;
import com.makomi.data.GraphWriteJsonSupport.RenameNodeAliasOperation;
import com.makomi.data.GraphWriteJsonSupport.ReplaceTriggerSourceTargetsOperation;
import com.makomi.data.GraphWriteJsonSupport.SetNodeChannelOperation;
import com.makomi.data.GraphWriteJsonSupport.UpdatedNodeState;
import com.makomi.data.LinkSavedDataChannelSupport.ChannelOverride;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * graph 显式保存应用服务。
 * <p>
 * 该服务将网页端结构化保存请求收口为：
 * </p>
 * <ul>
 * <li>请求解析与基础合法性校验；</li>
 * <li>权限、限流、写控与 OCC 校验；</li>
 * <li>别名修改与 `triggerSource -> core` 覆盖写入；</li>
 * <li>返回网页端所需的保存摘要，以及冲突场景下的最小节点真值。</li>
 * </ul>
 */
public final class GraphWriteService {
	private GraphWriteService() {
	}

	/**
	 * 提交一次 graph 保存请求，并返回网页端可直接消费的 JSON。
	 */
	public static String submit(ServerPlayer player, String rawRequestJson) {
		CommandSourceStack source = player == null ? null : player.createCommandSourceStack();
		return submitInternal(source, player, rawRequestJson);
	}

	/**
	 * 以通用命令源提交一次 graph 保存请求。
	 */
	public static String submit(CommandSourceStack source, String rawRequestJson) {
		return submitInternal(source, source == null ? null : source.getPlayer(), rawRequestJson);
	}

	private static String submitInternal(CommandSourceStack source, ServerPlayer player, String rawRequestJson) {
		try {
			ResolvedRequestContext requestContext = resolveRequestContext(source, player, rawRequestJson);
			PreparedPlan preparedPlan = preparePlan(requestContext);
			if (!preparedPlan.successful()) {
				return preparedPlan.failureResponseJson();
			}

			acquireRateLimitsOrThrow(requestContext.rateLimitSource(), requestContext.savedData(), preparedPlan);
			Set<String> changedNodeKeys = new LinkedHashSet<>();
			int appliedAliasCount = 0;
			int appliedReplaceCount = 0;
			int appliedChannelCount = 0;
			boolean refreshRequired = false;

			for (ValidatedAliasOperation validatedAliasOperation : preparedPlan.validatedAliasOperations()) {
				if (applyAliasOperation(requestContext.level(), validatedAliasOperation)) {
					appliedAliasCount++;
					changedNodeKeys.add(buildNodeKey(validatedAliasOperation.nodeType(), validatedAliasOperation.serial()));
				}
			}

			ValidatedChannelBatchOperation validatedChannelBatchOperation = preparedPlan.validatedChannelBatchOperation();
			if (
				validatedChannelBatchOperation != null &&
				validatedChannelBatchOperation.plan() != null &&
				validatedChannelBatchOperation.plan().hasChanges()
			) {
				refreshRequired = true;
				LinkChannelEditingService.applyPreparedBatchSetChannel(validatedChannelBatchOperation.plan());
				appliedChannelCount = validatedChannelBatchOperation.plan().changedChannelNodeCount();
				for (ChannelOverride override : validatedChannelBatchOperation.plan().overrides()) {
					changedNodeKeys.add(buildNodeKey(override.nodeType(), override.serial()));
				}
				for (Long triggerSourceSerialValue : validatedChannelBatchOperation.affectedTriggerSourceSerials()) {
					long triggerSourceSerial = triggerSourceSerialValue == null ? 0L : triggerSourceSerialValue;
					if (triggerSourceSerial <= 0L) {
						continue;
					}
					changedNodeKeys.add(buildNodeKey(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
				}
				for (Long coreSerialValue : validatedChannelBatchOperation.affectedCoreSerials()) {
					long coreSerial = coreSerialValue == null ? 0L : coreSerialValue;
					if (coreSerial <= 0L) {
						continue;
					}
					changedNodeKeys.add(buildNodeKey(LinkNodeType.CORE, coreSerial));
				}
			}

			for (ValidatedReplaceOperation validatedReplaceOperation : preparedPlan.validatedReplaceOperations()) {
				if (!validatedReplaceOperation.actualGraphWrite()) {
					continue;
				}
				refreshRequired = true;
				LinkSetExecutionService.applyPreparedReplace(validatedReplaceOperation.operation());
				appliedReplaceCount++;
				changedNodeKeys.add(buildNodeKey(LinkNodeType.TRIGGER_SOURCE, validatedReplaceOperation.operation().sourceSerial()));
				for (Long targetSerialValue : validatedReplaceOperation.affectedCoreSerials()) {
					long targetSerial = targetSerialValue == null ? 0L : targetSerialValue;
					if (targetSerial <= 0L) {
						continue;
					}
					changedNodeKeys.add(buildNodeKey(LinkNodeType.CORE, targetSerial));
				}
			}

			String message = buildAppliedMessage(appliedAliasCount, appliedReplaceCount, appliedChannelCount);
			return GraphWriteJsonSupport.buildAppliedResponse(
				message,
				requestContext.savedData().graphRevision(),
				changedNodeKeys.size(),
				refreshRequired
			);
		} catch (GraphWriteRejectedException exception) {
			return exception.responseJson();
		}
	}

	/**
	 * 预检一次 graph 保存请求，并返回当前窗口下的保存可行性反馈。
	 */
	public static String preview(ServerPlayer player, String rawRequestJson) {
		CommandSourceStack source = player == null ? null : player.createCommandSourceStack();
		return previewInternal(source, player, rawRequestJson);
	}

	/**
	 * 以通用命令源预检一次 graph 保存请求。
	 */
	public static String preview(CommandSourceStack source, String rawRequestJson) {
		return previewInternal(source, source == null ? null : source.getPlayer(), rawRequestJson);
	}

	private static String previewInternal(CommandSourceStack source, ServerPlayer player, String rawRequestJson) {
		try {
			ResolvedRequestContext requestContext = resolveRequestContext(source, player, rawRequestJson);
			PreparedPlan preparedPlan = preparePlan(requestContext);
			if (!preparedPlan.successful()) {
				return preparedPlan.failureResponseJson();
			}

			PreviewState previewState = buildPreviewState(requestContext.rateLimitSource(), preparedPlan);
			return GraphWriteJsonSupport.buildPreviewResponse(
				buildPreviewMessage(preparedPlan.costSummary(), previewState),
				requestContext.savedData().graphRevision(),
				previewState
			);
		} catch (GraphWriteRejectedException exception) {
			return exception.responseJson();
		}
	}

	/**
	 * 统一解析并校验请求入口，确保 `submit` 与 `preview` 共用同一口径。
	 */
	private static ResolvedRequestContext resolveRequestContext(
		CommandSourceStack source,
		ServerPlayer player,
		String rawRequestJson
	) {
		ParseResult parseResult = GraphWriteJsonSupport.parseRequest(rawRequestJson);
		if (!parseResult.successful()) {
			throw new GraphWriteRejectedException(parseResult.failureResponseJson());
		}
		ServerLevel level = player != null ? player.serverLevel() : (source == null ? null : source.getLevel());
		CommandSourceStack rateLimitSource = source != null ? source : (player == null ? null : player.createCommandSourceStack());
		if (level == null) {
			throw new GraphWriteRejectedException(
				GraphWriteJsonSupport.buildRejectedResponse("no_player", "当前没有可用的玩家或命令源上下文。", 0L, List.of())
			);
		}

		GraphWriteRequest request = parseResult.request();
		if (!"serial".equals(request.mode()) && !"channel".equals(request.mode())) {
			throw new GraphWriteRejectedException(
				GraphWriteJsonSupport.buildRejectedResponse(
					"unsupported_mode",
					"当前仅支持 serial / channel 模式网页编辑保存。",
					0L,
					List.of()
				)
			);
		}

		return new ResolvedRequestContext(
			request,
			level,
			LinkSavedData.get(level),
			player,
			rateLimitSource,
			source == null ? WebFeaturePermissionService.canUseGraphFeature(player) : WebFeaturePermissionService.canUseGraphFeature(source),
			hasPermission(source, player, RedstoneLinkConfig.command().permissionLevel()),
			hasPermission(source, player, RedstoneLinkConfig.command().otherPermissionLevel()),
			hasPermission(source, player, RedstoneLinkConfig.writeControl().limitedPermissionLevel()),
			hasPermission(source, player, RedstoneLinkConfig.writeControl().protectedPermissionLevel())
		);
	}

	private static PreparedPlan preparePlan(ResolvedRequestContext requestContext) {
		ServerPlayer player = requestContext.player();
		ServerLevel level = requestContext.level();
		LinkSavedData savedData = requestContext.savedData();
		GraphWriteRequest request = requestContext.request();
		List<RenameNodeAliasOperation> aliasOperations = new ArrayList<>();
		List<ReplaceTriggerSourceTargetsOperation> replaceOperations = new ArrayList<>();
		List<SetNodeChannelOperation> channelOperations = new ArrayList<>();
		for (GraphWriteOperation operation : request.operations()) {
			if (operation instanceof RenameNodeAliasOperation renameNodeAliasOperation) {
				aliasOperations.add(renameNodeAliasOperation);
				continue;
			}
			if (operation instanceof ReplaceTriggerSourceTargetsOperation replaceTriggerSourceTargetsOperation) {
				replaceOperations.add(replaceTriggerSourceTargetsOperation);
				continue;
			}
			if (operation instanceof SetNodeChannelOperation setNodeChannelOperation) {
				channelOperations.add(setNodeChannelOperation);
			}
		}
		if (aliasOperations.isEmpty() && replaceOperations.isEmpty() && channelOperations.isEmpty()) {
			return PreparedPlan.failure(
				GraphWriteJsonSupport.buildRejectedResponse("empty_operations", "当前没有可保存的修改。", savedData.graphRevision(), List.of())
			);
		}
		if ((!aliasOperations.isEmpty() || !replaceOperations.isEmpty() || !channelOperations.isEmpty()) &&
			!requestContext.hasGraphFeaturePermission()) {
			return PreparedPlan.failure(
				GraphWriteJsonSupport.buildRejectedResponse("permission_denied", "当前没有使用图编辑网页的权限。", savedData.graphRevision(), List.of())
			);
		}
		if ((!replaceOperations.isEmpty() || !channelOperations.isEmpty()) && !requestContext.hasGraphEditPermission()) {
			return PreparedPlan.failure(
				GraphWriteJsonSupport.buildRejectedResponse("permission_denied", "当前没有保存图编辑的权限。", savedData.graphRevision(), List.of())
			);
		}
		if (!aliasOperations.isEmpty() && !requestContext.hasAliasEditPermission()) {
			return PreparedPlan.failure(
				GraphWriteJsonSupport.buildRejectedResponse("permission_denied", "当前没有保存节点别名的权限。", savedData.graphRevision(), List.of())
			);
		}

		List<ValidatedAliasOperation> validatedAliasOperations = validateAliasOperations(savedData, aliasOperations);
		if (validatedAliasOperations == null) {
			return PreparedPlan.failure(
				GraphWriteJsonSupport.buildRejectedResponse("alias_invalid", "当前别名修改不合法，请检查节点类型、序号与别名内容。", savedData.graphRevision(), List.of())
			);
		}
		String aliasFailureResponseJson = validateAliasBatchConflicts(level, savedData, validatedAliasOperations);
		if (!aliasFailureResponseJson.isEmpty()) {
			return PreparedPlan.failure(aliasFailureResponseJson);
		}

		ValidatedChannelBatchOperation validatedChannelBatchOperation = validateChannelOperations(
			level,
			player,
			savedData,
			channelOperations,
			requestContext.hasLimitedBypassPermission(),
			requestContext.hasProtectedBypassPermission()
		);
		if (validatedChannelBatchOperation == null && channelOperations != null && !channelOperations.isEmpty()) {
			return PreparedPlan.failure(
				GraphWriteJsonSupport.buildRejectedResponse("channel_invalid", "当前频道修改不合法，请检查节点类型、序号与频道号。", savedData.graphRevision(), List.of())
			);
		}
		if (
			validatedChannelBatchOperation != null &&
			validatedChannelBatchOperation.failureResponseJson() != null &&
			!validatedChannelBatchOperation.failureResponseJson().isBlank()
		) {
			return PreparedPlan.failure(validatedChannelBatchOperation.failureResponseJson());
		}
		Set<Long> channelTargetsReturningToSerial = collectChannelTargetsReturningToSerial(
			savedData,
			validatedChannelBatchOperation
		);
		List<ValidatedReplaceOperation> validatedReplaceOperations = validateReplaceOperations(
			level,
			player,
			savedData,
			replaceOperations,
			requestContext.hasLimitedBypassPermission(),
			requestContext.hasProtectedBypassPermission(),
			channelTargetsReturningToSerial
		);
		if (validatedReplaceOperations == null) {
			return PreparedPlan.failure(
				GraphWriteJsonSupport.buildRejectedResponse("replace_invalid", "当前拓扑修改不合法，请检查 triggerSource 与目标 core 集合。", savedData.graphRevision(), List.of())
			);
		}
		for (ValidatedReplaceOperation validatedReplaceOperation : validatedReplaceOperations) {
			if (validatedReplaceOperation.failureResponseJson() != null && !validatedReplaceOperation.failureResponseJson().isBlank()) {
				return PreparedPlan.failure(validatedReplaceOperation.failureResponseJson());
			}
		}
		validatedChannelBatchOperation = trimChannelBatchOperationForExplicitReplace(
			validatedChannelBatchOperation,
			validatedReplaceOperations
		);

		return PreparedPlan.success(
			validatedAliasOperations,
			validatedReplaceOperations,
			validatedChannelBatchOperation,
			buildCostSummary(validatedAliasOperations, validatedReplaceOperations, validatedChannelBatchOperation)
		);
	}

	private static List<ValidatedAliasOperation> validateAliasOperations(
		LinkSavedData savedData,
		List<RenameNodeAliasOperation> aliasOperations
	) {
		if (aliasOperations == null || aliasOperations.isEmpty()) {
			return List.of();
		}
		Map<String, ValidatedAliasOperation> validatedByNodeKey = new LinkedHashMap<>();
		for (RenameNodeAliasOperation aliasOperation : aliasOperations) {
			if (aliasOperation == null || aliasOperation.nodeType() == null || aliasOperation.serial() <= 0L) {
				return null;
			}
			if (!savedData.isSerialAllocated(aliasOperation.nodeType(), aliasOperation.serial())) {
				return null;
			}
			if (savedData.isSerialRetired(aliasOperation.nodeType(), aliasOperation.serial())) {
				return null;
			}
			String normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(aliasOperation.alias());
			if (!normalizedAlias.isEmpty()) {
				NodeAliasSavedData.ValidationResult validationResult = NodeAliasSavedData.validateAlias(normalizedAlias);
				if (!validationResult.valid()) {
					return null;
				}
			}
			validatedByNodeKey.put(
				aliasOperation.nodeKey(),
				new ValidatedAliasOperation(aliasOperation.nodeType(), aliasOperation.serial(), normalizedAlias)
			);
		}
		return List.copyOf(validatedByNodeKey.values());
	}

	private static String validateAliasBatchConflicts(
		ServerLevel level,
		LinkSavedData savedData,
		List<ValidatedAliasOperation> validatedAliasOperations
	) {
		if (validatedAliasOperations == null || validatedAliasOperations.isEmpty()) {
			return "";
		}
		NodeAliasSavedData aliasSavedData = NodeAliasSavedData.get(level);
		Map<String, Long> occupiedAliasOwners = new LinkedHashMap<>();
		for (ValidatedAliasOperation validatedAliasOperation : validatedAliasOperations) {
			if (validatedAliasOperation.alias().isEmpty()) {
				continue;
			}
			String aliasKey = LinkNodeSemantics.toSemanticName(validatedAliasOperation.nodeType()) + ":" + validatedAliasOperation.alias();
			Long requestOwner = occupiedAliasOwners.putIfAbsent(aliasKey, validatedAliasOperation.serial());
			if (requestOwner != null && requestOwner.longValue() != validatedAliasOperation.serial()) {
				return GraphWriteJsonSupport.buildRejectedResponse(
					"alias_conflict",
					"保存失败：同一次保存请求中存在重复占用的别名 `%s`。".formatted(validatedAliasOperation.alias()),
					savedData.graphRevision(),
					List.of()
				);
			}
			long existingSerial = aliasSavedData.resolveSerial(validatedAliasOperation.nodeType(), validatedAliasOperation.alias()).orElse(0L);
			if (existingSerial > 0L && existingSerial != validatedAliasOperation.serial()) {
				return GraphWriteJsonSupport.buildRejectedResponse(
					"alias_conflict",
					"保存失败：别名 `%s` 已被 %s %s 占用。".formatted(
						validatedAliasOperation.alias(),
						LinkNodeSemantics.toSemanticName(validatedAliasOperation.nodeType()),
						NodeAliasDisplayUtil.formatSerialToken(existingSerial)
					),
					savedData.graphRevision(),
					List.of()
				);
			}
		}
		return "";
	}

	private static List<ValidatedReplaceOperation> validateReplaceOperations(
		ServerLevel level,
		ServerPlayer player,
		LinkSavedData savedData,
		List<ReplaceTriggerSourceTargetsOperation> replaceOperations,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission,
		Set<Long> channelTargetsReturningToSerial
	) {
		if (replaceOperations == null || replaceOperations.isEmpty()) {
			return List.of();
		}
		Map<Long, ReplaceTriggerSourceTargetsOperation> uniqueOperations = new LinkedHashMap<>();
		for (ReplaceTriggerSourceTargetsOperation replaceOperation : replaceOperations) {
			if (replaceOperation == null || replaceOperation.triggerSourceSerial() <= 0L) {
				return null;
			}
			uniqueOperations.put(replaceOperation.triggerSourceSerial(), replaceOperation);
		}
		List<ValidatedReplaceOperation> validatedReplaceOperations = new ArrayList<>(uniqueOperations.size());
		for (ReplaceTriggerSourceTargetsOperation replaceOperation : uniqueOperations.values()) {
			long currentSourceRevision = savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, replaceOperation.triggerSourceSerial());
			if (LinkOccSupport.isRevisionMismatch(replaceOperation.expectedSourceRevision(), currentSourceRevision)) {
				validatedReplaceOperations.add(
					ValidatedReplaceOperation.failure(
						GraphWriteJsonSupport.buildConflictResponse(
							"source_revision_conflict",
							"保存冲突：triggerSource %s 的连接已被其他操作更新，请重新导出 graph 文件后再试。".formatted(
								NodeAliasDisplayUtil.formatSerialToken(replaceOperation.triggerSourceSerial())
							),
							savedData.graphRevision(),
							List.of(
								buildUpdatedNodeState(
									level,
									savedData,
									LinkNodeType.TRIGGER_SOURCE,
									replaceOperation.triggerSourceSerial()
								)
							)
						)
					)
				);
				continue;
			}

			Set<Long> nextTargets = new LinkedHashSet<>(replaceOperation.targetCoreSerials());
			LinkSetExecutionService.PreparationResult preparationResult = LinkSetExecutionService.prepareConfirmedReplace(
				level,
				player,
				LinkNodeType.TRIGGER_SOURCE,
				replaceOperation.triggerSourceSerial(),
				nextTargets,
				hasLimitedBypassPermission,
				hasProtectedBypassPermission,
				channelTargetsReturningToSerial
			);
			if (!preparationResult.successful()) {
				validatedReplaceOperations.add(
					ValidatedReplaceOperation.failure(
						GraphWriteJsonSupport.buildRejectedResponse(
							"replace_rejected",
							formatOperationFeedback(preparationResult.feedbacks()),
							savedData.graphRevision(),
							List.of()
						)
					)
				);
				continue;
			}
			Set<Long> affectedCoreSerials = new LinkedHashSet<>(preparationResult.operation().previousTargets());
			affectedCoreSerials.addAll(preparationResult.operation().targets());
			boolean actualGraphWrite =
				!preparationResult.operation().previousTargets().equals(preparationResult.operation().targets()) ||
				savedData.getConnectionMode(LinkNodeType.TRIGGER_SOURCE, replaceOperation.triggerSourceSerial()) != LinkConnectionMode.SERIAL;
			validatedReplaceOperations.add(
				ValidatedReplaceOperation.success(
					preparationResult.operation(),
					List.copyOf(affectedCoreSerials),
					actualGraphWrite
				)
			);
		}
		return List.copyOf(validatedReplaceOperations);
	}

	private static ValidatedChannelBatchOperation validateChannelOperations(
		ServerLevel level,
		ServerPlayer player,
		LinkSavedData savedData,
		List<SetNodeChannelOperation> channelOperations,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {
		if (channelOperations == null || channelOperations.isEmpty()) {
			return null;
		}
		Map<String, SetNodeChannelOperation> uniqueOperations = new LinkedHashMap<>();
		for (SetNodeChannelOperation channelOperation : channelOperations) {
			if (channelOperation == null || channelOperation.nodeType() == null || channelOperation.serial() <= 0L) {
				return null;
			}
			uniqueOperations.put(channelOperation.nodeKey(), channelOperation);
		}
		List<ChannelOverride> overrides = new ArrayList<>(uniqueOperations.size());
		for (SetNodeChannelOperation channelOperation : uniqueOperations.values()) {
			long expectedRevision = channelOperation.nodeType() == LinkNodeType.TRIGGER_SOURCE
				? channelOperation.expectedSourceRevision()
				: channelOperation.expectedCoreRevision();
			long currentRevision = channelOperation.nodeType() == LinkNodeType.TRIGGER_SOURCE
				? savedData.sourceRevision(LinkNodeType.TRIGGER_SOURCE, channelOperation.serial())
				: savedData.coreRevision(channelOperation.serial());
			if (LinkOccSupport.isRevisionMismatch(expectedRevision, currentRevision)) {
				String conflictReason = channelOperation.nodeType() == LinkNodeType.TRIGGER_SOURCE
					? "source_revision_conflict"
					: "core_revision_conflict";
				String conflictMessage = channelOperation.nodeType() == LinkNodeType.TRIGGER_SOURCE
					? "保存冲突：triggerSource %s 的连接已被其他操作更新，请重新导出 graph 文件后再试。"
					: "保存冲突：core %s 的成员集合已被其他操作更新，请重新导出 graph 文件后再试。";
				return ValidatedChannelBatchOperation.failure(
					GraphWriteJsonSupport.buildConflictResponse(
						conflictReason,
						conflictMessage.formatted(NodeAliasDisplayUtil.formatSerialToken(channelOperation.serial())),
						savedData.graphRevision(),
						List.of(
							buildUpdatedNodeState(
								level,
								savedData,
								channelOperation.nodeType(),
								channelOperation.serial()
							)
						)
					)
				);
			}
			overrides.add(new ChannelOverride(channelOperation.nodeType(), channelOperation.serial(), channelOperation.channel()));
		}
		LinkChannelEditingService.BatchPreparationResult preparationResult = LinkChannelEditingService.prepareConfirmedBatchSetChannel(
			level,
			player,
			overrides,
			hasLimitedBypassPermission,
			hasProtectedBypassPermission
		);
		if (!preparationResult.successful()) {
			return ValidatedChannelBatchOperation.failure(
				GraphWriteJsonSupport.buildRejectedResponse(
					"channel_rejected",
					formatChannelOperationFeedback(preparationResult.feedbacks()),
					savedData.graphRevision(),
					List.of()
				)
			);
		}
		return buildValidatedChannelBatchOperation(preparationResult.plan());
	}

	/**
	 * 将频道批量计划转成 GraphWriteService 内部统一使用的受影响节点摘要。
	 */
	private static ValidatedChannelBatchOperation buildValidatedChannelBatchOperation(
		LinkChannelEditingService.PreparedChannelBatchUpdate plan
	) {
		if (plan == null) {
			return null;
		}
		Set<Long> affectedTriggerSourceSerials = new LinkedHashSet<>();
		Set<Long> affectedCoreSerials = new LinkedHashSet<>();
		for (ChannelOverride override : plan.overrides()) {
			if (override.nodeType() == LinkNodeType.TRIGGER_SOURCE) {
				affectedTriggerSourceSerials.add(override.serial());
			} else {
				affectedCoreSerials.add(override.serial());
			}
		}
		for (LinkSetExecutionService.PreparedReplaceOperation preparedOperation : plan.preparedOperations()) {
			affectedTriggerSourceSerials.add(preparedOperation.sourceSerial());
			affectedCoreSerials.addAll(preparedOperation.previousTargets());
			affectedCoreSerials.addAll(preparedOperation.targets());
		}
		return ValidatedChannelBatchOperation.success(
			plan,
			List.copyOf(affectedTriggerSourceSerials),
			List.copyOf(affectedCoreSerials)
		);
	}

	/**
	 * 收集同一批请求里会被切回 serial 的 core，供显式 replace 校验阶段放宽频道目标限制。
	 */
	private static Set<Long> collectChannelTargetsReturningToSerial(
		LinkSavedData savedData,
		ValidatedChannelBatchOperation validatedChannelBatchOperation
	) {
		if (savedData == null || validatedChannelBatchOperation == null || validatedChannelBatchOperation.plan() == null) {
			return Set.of();
		}
		Set<Long> targetCoreSerials = new LinkedHashSet<>();
		for (ChannelOverride override : validatedChannelBatchOperation.plan().overrides()) {
			if (
				override.nodeType() == LinkNodeType.CORE &&
				override.channel() <= 0L &&
				savedData.getConnectionMode(LinkNodeType.CORE, override.serial()) == LinkConnectionMode.CHANNEL
			) {
				targetCoreSerials.add(override.serial());
			}
		}
		return targetCoreSerials.isEmpty() ? Set.of() : Set.copyOf(targetCoreSerials);
	}

	/**
	 * 同一来源若已存在显式 replace，则频道派生 replace 让位给显式结果，避免同批请求互相覆盖。
	 */
	private static ValidatedChannelBatchOperation trimChannelBatchOperationForExplicitReplace(
		ValidatedChannelBatchOperation validatedChannelBatchOperation,
		List<ValidatedReplaceOperation> validatedReplaceOperations
	) {
		if (validatedChannelBatchOperation == null || validatedChannelBatchOperation.plan() == null) {
			return validatedChannelBatchOperation;
		}
		Set<Long> explicitReplaceSourceSerials = new LinkedHashSet<>();
		if (validatedReplaceOperations != null) {
			for (ValidatedReplaceOperation validatedReplaceOperation : validatedReplaceOperations) {
				if (
					validatedReplaceOperation == null ||
					!validatedReplaceOperation.actualGraphWrite() ||
					validatedReplaceOperation.operation() == null
				) {
					continue;
				}
				explicitReplaceSourceSerials.add(validatedReplaceOperation.operation().sourceSerial());
			}
		}
		if (explicitReplaceSourceSerials.isEmpty()) {
			return validatedChannelBatchOperation;
		}
		List<LinkSetExecutionService.PreparedReplaceOperation> retainedOperations = validatedChannelBatchOperation
			.plan()
			.preparedOperations()
			.stream()
			.filter((preparedOperation) -> !explicitReplaceSourceSerials.contains(preparedOperation.sourceSerial()))
			.toList();
		if (retainedOperations.size() == validatedChannelBatchOperation.plan().preparedOperations().size()) {
			return validatedChannelBatchOperation;
		}
		int totalCommandCost = 0;
		for (LinkSetExecutionService.PreparedReplaceOperation retainedOperation : retainedOperations) {
			totalCommandCost = saturatingAdd(totalCommandCost, retainedOperation.commandCost());
		}
		if (!validatedChannelBatchOperation.plan().changedOverrides().isEmpty() && totalCommandCost <= 0) {
			totalCommandCost = 1;
		}
		LinkChannelEditingService.PreparedChannelBatchUpdate trimmedPlan =
			new LinkChannelEditingService.PreparedChannelBatchUpdate(
				validatedChannelBatchOperation.plan().level(),
				validatedChannelBatchOperation.plan().player(),
				validatedChannelBatchOperation.plan().overrides(),
				validatedChannelBatchOperation.plan().changedOverrides(),
				retainedOperations,
				totalCommandCost
			);
		return buildValidatedChannelBatchOperation(trimmedPlan);
	}

	private static void acquireRateLimitsOrThrow(
		CommandSourceStack rateLimitSource,
		LinkSavedData savedData,
		PreparedPlan preparedPlan
	) {
		if (rateLimitSource == null || preparedPlan == null) {
			return;
		}
		PreparedCostSummary costSummary = preparedPlan.costSummary();
		RateLimitWindowState aliasRateLimitState = previewRateLimitState(
			rateLimitSource,
			CommandRateLimitService.CommandGroup.OTHER,
			costSummary.aliasCost()
		);
		RateLimitWindowState graphRateLimitState = previewRateLimitState(
			rateLimitSource,
			CommandRateLimitService.CommandGroup.GRAPH_WRITE,
			costSummary.graphCost()
		);
		if (!aliasRateLimitState.allowed() || !graphRateLimitState.allowed()) {
			throwRateLimitRejected(savedData, costSummary, aliasRateLimitState, graphRateLimitState);
		}

		if (
			costSummary.aliasCost() > 0 &&
			!CommandRateLimitService.tryAcquire(
				rateLimitSource,
				CommandRateLimitService.CommandGroup.OTHER,
				costSummary.aliasCost()
			)
		) {
			throwRateLimitRejected(
				savedData,
				costSummary,
				previewRateLimitState(rateLimitSource, CommandRateLimitService.CommandGroup.OTHER, costSummary.aliasCost()),
				previewRateLimitState(
					rateLimitSource,
					CommandRateLimitService.CommandGroup.GRAPH_WRITE,
					costSummary.graphCost()
				)
			);
		}
		if (
			costSummary.graphCost() > 0 &&
			!CommandRateLimitService.tryAcquire(
				rateLimitSource,
				CommandRateLimitService.CommandGroup.GRAPH_WRITE,
				costSummary.graphCost()
			)
		) {
			throwRateLimitRejected(
				savedData,
				costSummary,
				previewRateLimitState(rateLimitSource, CommandRateLimitService.CommandGroup.OTHER, costSummary.aliasCost()),
				previewRateLimitState(
					rateLimitSource,
					CommandRateLimitService.CommandGroup.GRAPH_WRITE,
					costSummary.graphCost()
				)
			);
		}
	}

	private static boolean applyAliasOperation(ServerLevel level, ValidatedAliasOperation validatedAliasOperation) {
		if (level == null || validatedAliasOperation == null || validatedAliasOperation.nodeType() == null || validatedAliasOperation.serial() <= 0L) {
			return false;
		}
		if (validatedAliasOperation.alias().isEmpty()) {
			NodeAliasSavedData.RemoveResult removeResult = RepeaterAliasMirrorSupport.remove(
				level,
				validatedAliasOperation.nodeType(),
				validatedAliasOperation.serial()
			);
			if (removeResult.removed()) {
				RepeaterAliasMirrorSupport.syncDisplaysAfterAliasChanged(
					level,
					validatedAliasOperation.nodeType(),
					validatedAliasOperation.serial()
				);
			}
			return removeResult.removed();
		}
		NodeAliasSavedData.UpsertResult upsertResult = RepeaterAliasMirrorSupport.upsert(
			level,
			validatedAliasOperation.nodeType(),
			validatedAliasOperation.serial(),
			validatedAliasOperation.alias()
		);
		if (upsertResult.changed()) {
			RepeaterAliasMirrorSupport.syncDisplaysAfterAliasChanged(
				level,
				validatedAliasOperation.nodeType(),
				validatedAliasOperation.serial()
			);
		}
		return upsertResult.changed();
	}

	private static UpdatedNodeState buildUpdatedNodeState(
		ServerLevel level,
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial
	) {
		String alias = NodeAliasServerSupport.resolveAlias(level, nodeType, serial).orElse("");
		return new UpdatedNodeState(
			LinkNodeSemantics.toSemanticName(nodeType) + ":" + Math.max(0L, serial),
			nodeType,
			serial,
			alias,
			NodeAliasDisplayUtil.formatDisplayText(alias, serial),
			savedData.getConnectionMode(nodeType, serial).token(),
			savedData.getChannel(nodeType, serial),
			savedData.sourceRevision(nodeType, serial),
			nodeType == LinkNodeType.CORE ? savedData.coreRevision(serial) : 0L
		);
	}

	/**
	 * 统一构造 `triggerSource/core` 语义节点键，供成功保存摘要统计变更节点数量。
	 */
	private static String buildNodeKey(LinkNodeType nodeType, long serial) {
		return LinkNodeSemantics.toSemanticName(nodeType) + ":" + Math.max(0L, serial);
	}

	/**
	 * 统一收口 graph 保存的专用计费摘要。
	 */
	private static PreparedCostSummary buildCostSummary(
		List<ValidatedAliasOperation> validatedAliasOperations,
		List<ValidatedReplaceOperation> validatedReplaceOperations,
		ValidatedChannelBatchOperation validatedChannelBatchOperation
	) {
		int aliasCost = validatedAliasOperations == null ? 0 : Math.max(0, validatedAliasOperations.size());
		int changedReplaceCount = 0;
		if (validatedReplaceOperations != null) {
			for (ValidatedReplaceOperation validatedReplaceOperation : validatedReplaceOperations) {
				if (validatedReplaceOperation != null && validatedReplaceOperation.actualGraphWrite()) {
					changedReplaceCount++;
				}
			}
		}
		int changedTriggerSourceCount = 0;
		int changedChannelNodeCount = 0;
		if (validatedChannelBatchOperation != null && validatedChannelBatchOperation.plan() != null) {
			changedTriggerSourceCount = validatedChannelBatchOperation.plan().changedTriggerSourceCount();
			changedChannelNodeCount = validatedChannelBatchOperation.plan().changedChannelNodeCount();
		}
		int graphWriteUnitCount = saturatingAdd(
			changedReplaceCount,
			saturatingAdd(changedTriggerSourceCount, changedChannelNodeCount)
		);
		int graphCost = graphWriteUnitCount > 0
			? CommandRateLimitService.computeBatchCost(1, graphWriteUnitCount, 16)
			: 0;
		return new PreparedCostSummary(aliasCost, graphWriteUnitCount, graphCost, changedReplaceCount, changedChannelNodeCount);
	}

	private static int totalLinkCommandCost(
		List<ValidatedReplaceOperation> validatedReplaceOperations,
		ValidatedChannelBatchOperation validatedChannelBatchOperation
	) {
		int totalCost = 0;
		if (validatedReplaceOperations != null) {
			for (ValidatedReplaceOperation validatedReplaceOperation : validatedReplaceOperations) {
				if (validatedReplaceOperation == null || validatedReplaceOperation.operation() == null) {
					continue;
				}
				totalCost = saturatingAdd(totalCost, validatedReplaceOperation.operation().commandCost());
			}
		}
		if (validatedChannelBatchOperation != null && validatedChannelBatchOperation.plan() != null) {
			totalCost = saturatingAdd(totalCost, validatedChannelBatchOperation.plan().totalCommandCost());
		}
		return totalCost;
	}

	/**
	 * 预检当前草稿在别名限流组与 graph 专用限流组上的即时状态。
	 */
	private static PreviewState buildPreviewState(CommandSourceStack rateLimitSource, PreparedPlan preparedPlan) {
		PreparedCostSummary costSummary = preparedPlan.costSummary();
		RateLimitWindowState aliasRateLimitState = previewRateLimitState(
			rateLimitSource,
			CommandRateLimitService.CommandGroup.OTHER,
			costSummary.aliasCost()
		);
		RateLimitWindowState graphRateLimitState = previewRateLimitState(
			rateLimitSource,
			CommandRateLimitService.CommandGroup.GRAPH_WRITE,
			costSummary.graphCost()
		);
		return new PreviewState(
			costSummary.aliasCost(),
			costSummary.graphCost(),
			costSummary.graphWriteUnitCount(),
			aliasRateLimitState.allowed(),
			graphRateLimitState.allowed(),
			aliasRateLimitState.hardBlocked(),
			graphRateLimitState.hardBlocked(),
			aliasRateLimitState.waitTicks(),
			graphRateLimitState.waitTicks(),
			aliasRateLimitState.allowed() && graphRateLimitState.allowed()
		);
	}

	/**
	 * 将 0 成本写入视为“天然允许”，其余场景走统一限流预检。
	 */
	private static RateLimitWindowState previewRateLimitState(
		CommandSourceStack source,
		CommandRateLimitService.CommandGroup commandGroup,
		int cost
	) {
		if (source == null || commandGroup == null || cost <= 0) {
			return new RateLimitWindowState(true, false, 0L, Math.max(0, cost));
		}
		CommandRateLimitService.AcquirePreview preview = CommandRateLimitService.previewAcquire(
			source,
			commandGroup,
			cost
		);
		return new RateLimitWindowState(preview.allowed(), preview.hardBlocked(), preview.waitTicks(), cost);
	}

	private static void throwRateLimitRejected(
		LinkSavedData savedData,
		PreparedCostSummary costSummary,
		RateLimitWindowState aliasRateLimitState,
		RateLimitWindowState graphRateLimitState
	) {
		throw new GraphWriteRejectedException(
			GraphWriteJsonSupport.buildRejectedResponse(
				"rate_limit_exceeded",
				buildRateLimitMessage(costSummary, aliasRateLimitState, graphRateLimitState),
				savedData.graphRevision(),
				List.of()
			)
		);
	}

	/**
	 * 保存预检与正式保存共用同一套提示口径，避免前后端出现“能预检但不能理解失败原因”的漂移。
	 */
	private static String buildRateLimitMessage(
		PreparedCostSummary costSummary,
		RateLimitWindowState aliasRateLimitState,
		RateLimitWindowState graphRateLimitState
	) {
		if (graphRateLimitState.hardBlocked()) {
			return "当前 graph 保存批量过大：graph 成本 %d，写单元 %d，已超出单窗口容量，请拆分后再保存。".formatted(
				graphRateLimitState.cost(),
				costSummary.graphWriteUnitCount()
			);
		}
		if (aliasRateLimitState.hardBlocked()) {
			return "当前别名保存批量过大：别名成本 %d，已超出单窗口容量，请拆分后再保存。".formatted(aliasRateLimitState.cost());
		}
		long waitTicks = Math.max(aliasRateLimitState.waitTicks(), graphRateLimitState.waitTicks());
		if (waitTicks > 0L) {
			return "保存过于频繁，预计还需等待 %d tick 后再试。当前别名成本 %d，graph 成本 %d（写单元 %d）。".formatted(
				waitTicks,
				costSummary.aliasCost(),
				costSummary.graphCost(),
				costSummary.graphWriteUnitCount()
			);
		}
		return "保存过于频繁，请稍后再试。";
	}

	private static String buildPreviewMessage(PreparedCostSummary costSummary, PreviewState previewState) {
		if (previewState == null) {
			return "当前无法完成保存预检。";
		}
		if (previewState.canSave()) {
			if (costSummary.aliasCost() <= 0 && costSummary.graphCost() <= 0) {
				return "当前草稿没有实际写入成本，可直接保存。";
			}
			return "当前可保存：别名成本 %d，graph 成本 %d（写单元 %d）。".formatted(
				costSummary.aliasCost(),
				costSummary.graphCost(),
				costSummary.graphWriteUnitCount()
			);
		}
		return buildRateLimitMessage(
			costSummary,
			new RateLimitWindowState(
				previewState.aliasAllowed(),
				previewState.aliasHardBlocked(),
				previewState.aliasWaitTicks(),
				costSummary.aliasCost()
			),
			new RateLimitWindowState(
				previewState.graphAllowed(),
				previewState.graphHardBlocked(),
				previewState.graphWaitTicks(),
				costSummary.graphCost()
			)
		);
	}

	private static boolean hasPermission(CommandSourceStack source, ServerPlayer player, int permissionLevel) {
		if (source != null) {
			return source.hasPermission(permissionLevel);
		}
		return player != null && player.hasPermissions(permissionLevel);
	}

	private static int saturatingAdd(int left, int right) {
		long sum = (long) left + (long) right;
		return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, (int) sum);
	}

	private static String formatOperationFeedback(List<LinkSetExecutionService.OperationFeedback> feedbacks) {
		if (feedbacks == null || feedbacks.isEmpty()) {
			return "保存失败：当前拓扑修改被服务端拒绝。";
		}
		String messageKey = feedbacks.get(0).messageKey();
		return switch (messageKey) {
			case "message.redstonelink.source_serial_unallocated" -> "保存失败：目标 triggerSource 未分配。";
			case "message.redstonelink.source_serial_retired" -> "保存失败：目标 triggerSource 已退役。";
			case "message.redstonelink.too_many_targets" -> "保存失败：目标 core 数量超出当前上限。";
			case "message.redstonelink.invalid_target_unallocated" -> "保存失败：所选目标 core 中包含未分配节点。";
			case "message.redstonelink.invalid_target_retired" -> "保存失败：所选目标 core 中包含已退役节点。";
			case "message.redstonelink.invalid_target_channel_mode" -> "保存失败：网页编辑当前只支持 serial 模式目标 core。";
			case "message.redstonelink.offline_targets_blocked" -> "保存失败：当前配置不允许绑定离线目标 core。";
			case "message.redstonelink.write_control.deny.readonly" -> "保存失败：当前写控模式为只读。";
			default -> "保存失败：当前拓扑修改被服务端拒绝。";
		};
	}

	private static String formatChannelOperationFeedback(List<LinkSetExecutionService.OperationFeedback> feedbacks) {
		if (feedbacks == null || feedbacks.isEmpty()) {
			return "保存失败：当前频道修改被服务端拒绝。";
		}
		String messageKey = feedbacks.get(0).messageKey();
		return switch (messageKey) {
			case "message.redstonelink.invalid_channel" -> "保存失败：频道号不合法。";
			case "message.redstonelink.source_serial_unallocated" -> "保存失败：目标 triggerSource 未分配。";
			case "message.redstonelink.source_serial_retired" -> "保存失败：目标 triggerSource 已退役。";
			case "message.redstonelink.target_serial_unallocated" -> "保存失败：目标 core 未分配。";
			case "message.redstonelink.target_serial_retired" -> "保存失败：目标 core 已退役。";
			case "message.redstonelink.write_control.deny.readonly" -> "保存失败：当前写控模式为只读。";
			default -> "保存失败：当前频道修改被服务端拒绝。";
		};
	}

	private static String buildAppliedMessage(int appliedAliasCount, int appliedReplaceCount, int appliedChannelCount) {
		List<String> segments = new ArrayList<>(2);
		if (appliedAliasCount > 0) {
			segments.add("别名修改 %d 项".formatted(appliedAliasCount));
		}
		if (appliedReplaceCount > 0) {
			segments.add("拓扑修改 %d 项".formatted(appliedReplaceCount));
		}
		if (appliedChannelCount > 0) {
			segments.add("频道修改 %d 项".formatted(appliedChannelCount));
		}
		if (segments.isEmpty()) {
			return "保存完成，但当前没有产生实际变更。";
		}
		return "已保存：%s。".formatted(String.join("，", segments));
	}

	/**
	 * 已校验的别名修改操作。
	 */
	private record ValidatedAliasOperation(LinkNodeType nodeType, long serial, String alias) {}

	/**
	 * 已校验的拓扑覆盖写入操作。
	 */
	private record ValidatedReplaceOperation(
		LinkSetExecutionService.PreparedReplaceOperation operation,
		List<Long> affectedCoreSerials,
		boolean actualGraphWrite,
		String failureResponseJson
	) {
		private static ValidatedReplaceOperation success(
			LinkSetExecutionService.PreparedReplaceOperation operation,
			List<Long> affectedCoreSerials,
			boolean actualGraphWrite
		) {
			return new ValidatedReplaceOperation(
				operation,
				List.copyOf(affectedCoreSerials == null ? List.of() : affectedCoreSerials),
				actualGraphWrite,
				""
			);
		}

		private static ValidatedReplaceOperation failure(String failureResponseJson) {
			return new ValidatedReplaceOperation(null, List.of(), false, failureResponseJson == null ? "" : failureResponseJson);
		}
	}

	/**
	 * 已校验的频道覆盖写入操作。
	 */
	private record ValidatedChannelBatchOperation(
		LinkChannelEditingService.PreparedChannelBatchUpdate plan,
		List<Long> affectedTriggerSourceSerials,
		List<Long> affectedCoreSerials,
		String failureResponseJson
	) {
		private static ValidatedChannelBatchOperation success(
			LinkChannelEditingService.PreparedChannelBatchUpdate plan,
			List<Long> affectedTriggerSourceSerials,
			List<Long> affectedCoreSerials
		) {
			return new ValidatedChannelBatchOperation(
				plan,
				List.copyOf(affectedTriggerSourceSerials == null ? List.of() : affectedTriggerSourceSerials),
				List.copyOf(affectedCoreSerials == null ? List.of() : affectedCoreSerials),
				""
			);
		}

		private static ValidatedChannelBatchOperation failure(String failureResponseJson) {
			return new ValidatedChannelBatchOperation(null, List.of(), List.of(), failureResponseJson == null ? "" : failureResponseJson);
		}
	}

	/**
	 * 预校验完成后的整体计划。
	 */
	private record PreparedPlan(
		List<ValidatedAliasOperation> validatedAliasOperations,
		List<ValidatedReplaceOperation> validatedReplaceOperations,
		ValidatedChannelBatchOperation validatedChannelBatchOperation,
		PreparedCostSummary costSummary,
		String failureResponseJson
	) {
		private static PreparedPlan success(
			List<ValidatedAliasOperation> validatedAliasOperations,
			List<ValidatedReplaceOperation> validatedReplaceOperations,
			ValidatedChannelBatchOperation validatedChannelBatchOperation,
			PreparedCostSummary costSummary
		) {
			return new PreparedPlan(
				List.copyOf(validatedAliasOperations == null ? List.of() : validatedAliasOperations),
				List.copyOf(validatedReplaceOperations == null ? List.of() : validatedReplaceOperations),
				validatedChannelBatchOperation,
				costSummary == null ? new PreparedCostSummary(0, 0, 0, 0, 0) : costSummary,
				""
			);
		}

		private static PreparedPlan failure(String failureResponseJson) {
			return new PreparedPlan(
				List.of(),
				List.of(),
				null,
				new PreparedCostSummary(0, 0, 0, 0, 0),
				failureResponseJson == null ? "" : failureResponseJson
			);
		}

		private boolean successful() {
			return failureResponseJson == null || failureResponseJson.isBlank();
		}
	}

	/**
	 * graph 保存专用成本摘要。
	 */
	private record PreparedCostSummary(
		int aliasCost,
		int graphWriteUnitCount,
		int graphCost,
		int changedReplaceCount,
		int changedChannelNodeCount
	) {
		private PreparedCostSummary {
			aliasCost = Math.max(0, aliasCost);
			graphWriteUnitCount = Math.max(0, graphWriteUnitCount);
			graphCost = Math.max(0, graphCost);
			changedReplaceCount = Math.max(0, changedReplaceCount);
			changedChannelNodeCount = Math.max(0, changedChannelNodeCount);
		}
	}

	/**
	 * 限流窗口的本地归一化快照，允许 0 成本场景无损表达。
	 */
	private record RateLimitWindowState(boolean allowed, boolean hardBlocked, long waitTicks, int cost) {
		private RateLimitWindowState {
			waitTicks = Math.max(0L, waitTicks);
			cost = Math.max(0, cost);
		}
	}

	/**
	 * 入口解析完成后的共享上下文。
	 */
	private record ResolvedRequestContext(
		GraphWriteRequest request,
		ServerLevel level,
		LinkSavedData savedData,
		ServerPlayer player,
		CommandSourceStack rateLimitSource,
		boolean hasGraphFeaturePermission,
		boolean hasGraphEditPermission,
		boolean hasAliasEditPermission,
		boolean hasLimitedBypassPermission,
		boolean hasProtectedBypassPermission
	) {}

	/**
	 * graph 保存拒绝异常，用于在统一入口中断并返回结构化 JSON。
	 */
	private static final class GraphWriteRejectedException extends RuntimeException {
		private final String responseJson;

		private GraphWriteRejectedException(String responseJson) {
			super(responseJson);
			this.responseJson = responseJson == null ? "" : responseJson;
		}

		private String responseJson() {
			return responseJson;
		}
	}
}
