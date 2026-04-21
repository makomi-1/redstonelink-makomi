package com.makomi.command.bench;

import com.makomi.command.argument.SerialBatchArgumentType;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.data.StatePanelRecordingSessionService;
import com.makomi.data.StatePanelToolData;
import com.makomi.registry.ModItems;
import com.makomi.util.SerialParseUtil;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * bench/internal recording 回归命令注册器。
 * <p>
 * 该入口只服务 automated functional case：
 * </p>
 * <ul>
 * <li>准备状态面板订阅数据；</li>
 * <li>启动录制会话并校验权限门；</li>
 * <li>停止录制并输出稳定导出摘要。</li>
 * </ul>
 */
public final class BenchRecordingCommandRegistry {
	private BenchRecordingCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink bench recording` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("recording")
			.then(
				Commands
					.literal("prepare")
					.then(
						Commands
							.literal("triggerSource")
							.then(
								Commands.argument("serial", LongArgumentType.longArg(1L)).then(
									Commands
										.literal("core")
										.then(
											Commands
												.argument("target_serials", SerialBatchArgumentType.serialBatch())
												.executes(BenchRecordingCommandRegistry::executePrepare)
										)
								)
							)
					)
			)
			.then(Commands.literal("start").executes(BenchRecordingCommandRegistry::executeStart))
			.then(Commands.literal("stop").executes(BenchRecordingCommandRegistry::executeStop));
	}

	/**
	 * 为当前玩家主手准备一份 bench 专用状态面板，并写入订阅列表。
	 */
	private static int executePrepare(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] recording_prepare outcome=rejected reason=player_only"));
			return 0;
		}

		long triggerSourceSerial = LongArgumentType.getLong(context, "serial");
		String rawTargetSerials = SerialBatchArgumentType.getSerialBatch(context, "target_serials");
		List<Long> targetCoreSerials = parseCoreSerials(source, rawTargetSerials);
		if (targetCoreSerials == null) {
			return 0;
		}

		List<StatePanelToolData.SubscriptionEntry> subscriptions = new ArrayList<>(1 + targetCoreSerials.size());
		subscriptions.add(new StatePanelToolData.SubscriptionEntry(LinkNodeType.TRIGGER_SOURCE, triggerSourceSerial));
		for (Long targetCoreSerialValue : targetCoreSerials) {
			long targetCoreSerial = targetCoreSerialValue == null ? 0L : targetCoreSerialValue;
			if (targetCoreSerial > 0L) {
				subscriptions.add(new StatePanelToolData.SubscriptionEntry(LinkNodeType.CORE, targetCoreSerial));
			}
		}

		ItemStack statePanelItem = new ItemStack(ModItems.REDSTONELINK_STATUS_PANEL);
		StatePanelToolData.writeSubscriptions(statePanelItem, subscriptions);
		player.setItemInHand(InteractionHand.MAIN_HAND, statePanelItem);

		source.sendSuccess(
			() -> Component.literal(buildPrepareSummary(triggerSourceSerial, targetCoreSerials.size(), subscriptions.size())),
			false
		);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * 启动 bench 录制会话，并输出稳定摘要供 functional case 断言。
	 */
	private static int executeStart(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] recording_start outcome=rejected reason=player_only"));
			return 0;
		}

		StatePanelRecordingSessionService.StartResult startResult = StatePanelRecordingSessionService.start(
			player,
			new StatePanelRecordingSessionService.StartRequest(
				"Bench Recording",
				1,
				32,
				0,
				false,
				collectSelectedNodeKeys(player.getMainHandItem())
			)
		);
		String summary = buildStartSummary(startResult);
		if (startResult.success()) {
			source.sendSuccess(() -> Component.literal(summary), false);
			return Command.SINGLE_SUCCESS;
		}
		source.sendFailure(Component.literal(summary));
		return 0;
	}

	/**
	 * 停止当前 bench 录制会话，并输出稳定导出摘要。
	 */
	private static int executeStop(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("[RedstoneLink/Bench] recording_stop outcome=rejected reason=player_only"));
			return 0;
		}

		StatePanelRecordingSessionService.StopResult stopResult = StatePanelRecordingSessionService.stop(player);
		String summary = buildStopSummary(stopResult);
		if (stopResult.success()) {
			source.sendSuccess(() -> Component.literal(summary), false);
			return Command.SINGLE_SUCCESS;
		}
		source.sendFailure(Component.literal(summary));
		return 0;
	}

	/**
	 * 解析目标 core 序号批次，并复用状态面板订阅上限作为 bench 前置约束。
	 */
	private static List<Long> parseCoreSerials(CommandSourceStack source, String rawTargetSerials) {
		int maxCoreTargets = Math.max(1, RedstoneLinkConfig.general().statePanelMaxSubscriptions() - 1);
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			rawTargetSerials,
			maxCoreTargets
		);
		if (parseResult.exceedLimit()) {
			source.sendFailure(
				Component.literal("[RedstoneLink/Bench] recording_prepare outcome=rejected reason=subscription_limit_exceeded")
			);
			return null;
		}
		if (!parseResult.invalidEntries().isEmpty() || parseResult.orderedTargets().isEmpty()) {
			source.sendFailure(
				Component.literal("[RedstoneLink/Bench] recording_prepare outcome=rejected reason=invalid_target_serials")
			);
			return null;
		}
		return parseResult.orderedTargets();
	}

	/**
	 * bench 启动录制时默认选中当前状态面板中的全部订阅节点，
	 * 以匹配网页/GUI “先准备订阅，再开始录制”的主流程。
	 */
	private static List<String> collectSelectedNodeKeys(ItemStack statePanelItem) {
		List<StatePanelToolData.SubscriptionEntry> subscriptions = StatePanelToolData.readSubscriptions(statePanelItem);
		if (subscriptions.isEmpty()) {
			return List.of();
		}
		List<String> selectedNodeKeys = new ArrayList<>(subscriptions.size());
		for (StatePanelToolData.SubscriptionEntry subscription : subscriptions) {
			if (subscription == null || subscription.serial() <= 0L) {
				continue;
			}
			selectedNodeKeys.add(LinkNodeSemantics.toSemanticName(subscription.nodeType()) + ":" + subscription.serial());
		}
		return selectedNodeKeys.isEmpty() ? List.of() : List.copyOf(selectedNodeKeys);
	}

	/**
	 * 构造准备状态面板订阅后的稳定摘要。
	 */
	static String buildPrepareSummary(long triggerSourceSerial, int coreCount, int subscriptionCount) {
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] recording_prepare outcome=prepared triggerSourceSerial=%d coreCount=%d subscriptionCount=%d",
			Math.max(0L, triggerSourceSerial),
			Math.max(0, coreCount),
			Math.max(0, subscriptionCount)
		);
	}

	/**
	 * 构造开始录制摘要。
	 */
	static String buildStartSummary(StatePanelRecordingSessionService.StartResult startResult) {
		StatePanelRecordingSessionService.StartResult normalizedResult = startResult == null
			? new StatePanelRecordingSessionService.StartResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.tool_missing"),
				StatePanelRecordingSessionService.SessionSnapshot.inactive(0)
			)
			: startResult;
		StatePanelRecordingSessionService.SessionSnapshot snapshot = normalizedResult.sessionSnapshot();
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] recording_start outcome=%s reason=%s active=%s subscriptionCount=%d mountedCount=%d sampleEveryTicks=%d autoOpenWeb=%s",
			normalizedResult.success() ? "started" : "rejected",
			normalizedResult.success() ? "started" : resolveFeedbackReason(normalizedResult.feedback()),
			snapshot.active(),
			snapshot.subscriptionCount(),
			snapshot.mountedCount(),
			snapshot.sampleEveryTicks(),
			snapshot.autoOpenWeb()
		);
	}

	/**
	 * 构造停止录制摘要。
	 */
	static String buildStopSummary(StatePanelRecordingSessionService.StopResult stopResult) {
		StatePanelRecordingSessionService.StopResult normalizedResult = stopResult == null
			? new StatePanelRecordingSessionService.StopResult(
				false,
				QuickLinkOperationFeedback.failure("message.redstonelink.state_panel.recording.stop.not_active"),
				StatePanelRecordingSessionService.SessionSnapshot.inactive(0),
				null
			)
			: stopResult;
		StatePanelRecordingSessionService.SessionSnapshot snapshot = normalizedResult.sessionSnapshot();
		StatePanelRecordingSessionService.ExportBundle exportBundle = normalizedResult.exportBundle();
		return String.format(
			Locale.ROOT,
			"[RedstoneLink/Bench] recording_stop outcome=%s reason=%s active=%s subscriptionCount=%d mountedCount=%d exportBytes=%d fileName=%s autoOpenWeb=%s",
			normalizedResult.success() ? "exported" : "rejected",
			normalizedResult.success() ? "exported" : resolveFeedbackReason(normalizedResult.feedback()),
			snapshot.active(),
			snapshot.subscriptionCount(),
			snapshot.mountedCount(),
			exportBundle == null ? 0 : exportBundle.compressedBytes().length,
			exportBundle == null || exportBundle.fileName().isBlank() ? "-" : exportBundle.fileName(),
			exportBundle != null && exportBundle.autoOpenWeb()
		);
	}

	/**
	 * 把服务端反馈 key 折算为 functional case 可稳定断言的 reason。
	 */
	private static String resolveFeedbackReason(QuickLinkOperationFeedback feedback) {
		String messageKey = feedback == null ? "" : feedback.messageKey();
		if ("message.redstonelink.permission.insufficient".equals(messageKey)) {
			return "permission_insufficient";
		}
		if ("message.redstonelink.state_panel.recording.already_active".equals(messageKey)) {
			return "already_active";
		}
		if ("message.redstonelink.state_panel.recording.tool_missing".equals(messageKey)) {
			return "tool_missing";
		}
		if ("message.redstonelink.state_panel.recording.no_subscriptions".equals(messageKey)) {
			return "no_subscriptions";
		}
		if ("message.redstonelink.state_panel.recording.no_selection".equals(messageKey)) {
			return "no_selection";
		}
		if ("message.redstonelink.state_panel.recording.no_recordable_nodes".equals(messageKey)) {
			return "no_recordable_nodes";
		}
		if ("message.redstonelink.state_panel.recording.stop.not_active".equals(messageKey)) {
			return "not_active";
		}
		if ("message.redstonelink.state_panel.recording.stop.export_failed".equals(messageKey)) {
			return "export_failed";
		}
		return "unknown";
	}
}
