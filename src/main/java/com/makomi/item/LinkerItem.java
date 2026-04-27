package com.makomi.item;

import com.makomi.advancement.RedstoneLinkAdvancementService;
import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkedTargetDispatchService;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.network.PairingNetwork;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 手持遥控器物品：支持 triggerSource 配对与远程触发。
 * <p>
 * 交互约束：
 * 1. 默认潜行 + 主手右键：打开配对界面（潜行门槛可由专用配置控制）；
 * 2. 站立 + 主手右键 + 副手空：触发已连接核心；
 * 3. 物品不可放置（继承 Item 而非 BlockItem）。
 * </p>
 */
public class LinkerItem extends Item implements PairableItem {
	/**
	 * 触发模式（TOGGLE/PULSE），由具体物品实例在注册时注入。
	 */
	private final ActivationMode activationMode;

	/**
	 * @param properties 物品属性
	 * @param activationMode 触发模式（TOGGLE/PULSE）
	 */
	public LinkerItem(Item.Properties properties, ActivationMode activationMode) {
		super(properties);
		this.activationMode = activationMode;
	}

	/**
	 * 获取该可配对物品在链路系统中的节点类型。
	 *
	 * @return 固定返回 triggerSource 节点类型
	 */
	@Override
	public LinkNodeType getNodeType() {
		return LinkNodeType.TRIGGER_SOURCE;
	}

	/**
	 * 空手对空气右键时的交互入口。
	 * <p>
	 * 行为优先级：先尝试打开配对界面，再尝试触发已连接核心。
	 * </p>
	 *
	 * @param level 当前世界
	 * @param player 操作玩家
	 * @param hand 交互手
	 * @return 本次交互结果与手持物
	 */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		ensureSerialAssigned(level, heldStack);

		if (shouldOpenPairingUi(player, hand)) {
			openPairingUi(level, player, heldStack);
			return InteractionResult.sidedSuccess(level.isClientSide());
		}
		if (canExecutePrimaryUse(player, hand)) {
			executePrimaryUse(level, player, heldStack);
			return InteractionResult.sidedSuccess(level.isClientSide());
		}
		return InteractionResult.PASS;
	}

	/**
	 * 右键方块时的交互入口。
	 * <p>
	 * 与 {@link #use(Level, Player, InteractionHand)} 保持相同手势语义，避免两套规则不一致。
	 * </p>
	 *
	 * @param context 使用上下文
	 * @return 本次交互结果
	 */
	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		ItemStack heldStack = context.getItemInHand();
		ensureSerialAssigned(level, heldStack);

		Player player = context.getPlayer();
		if (player != null
			&& shouldOpenPairingUi(player, context.getHand())) {
			openPairingUi(level, player, heldStack);
			return InteractionResult.sidedSuccess(level.isClientSide());
		}
		if (player != null
			&& canExecutePrimaryUse(player, context.getHand())) {
			executePrimaryUse(level, player, heldStack);
			return InteractionResult.sidedSuccess(level.isClientSide());
		}
		return InteractionResult.PASS;
	}

	/**
	 * 物品在背包中的每 tick 回调。
	 * <p>
	 * 这里统一补齐序号，确保遥控器在任何获得路径下都具备稳定身份。
	 * </p>
	 *
	 * @param stack 物品栈
	 * @param level 当前世界
	 * @param entity 持有实体
	 * @param slotId 槽位索引
	 * @param isSelected 是否被选中
	 */
	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
		ensureSerial(level, stack);
		super.inventoryTick(stack, level, entity, slotId, isSelected);
	}

	/**
	 * 追加物品提示文本。
	 * <p>
	 * 展示序号、已连接核心列表以及操作手势说明，便于玩家在物品栏中直接确认状态。
	 * </p>
	 *
	 * @param stack 物品栈
	 * @param context 提示上下文
	 * @param tooltipComponents 提示文本容器
	 * @param tooltipFlag 提示标记
	 */
	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
		java.util.function.Consumer<Component> tooltipAdder,
		TooltipFlag tooltipFlag
	) {
		List<Component> tooltipComponents = new java.util.ArrayList<>();
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		long serial = LinkItemData.getSerial(stack);
		List<Long> linkedSerials = LinkItemData.getLinkedSerials(stack);
		List<String> linkedDisplayTexts = LinkItemData.getLinkedDisplayTexts(stack);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.serial",
				NodeAliasDisplayUtil.formatDisplayText(LinkItemData.getDisplayAlias(stack), serial)
			)
		);
		// 当前连接摘要优先压缩连续 `#序号` 区间，别名节点保持原样并在其处断开。
		String linkedText = TooltipTextTruncateUtil.buildTargetDisplayTextsText(
			linkedSerials,
			linkedDisplayTexts,
			TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.links", linkedText));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.open_pairing"));
		tooltipComponents.add(buildPrimaryUseTooltip());
		appendGrayFooterTooltips(stack, context, tooltipComponents, tooltipFlag);
		tooltipComponents.forEach(tooltipAdder);
		super.appendHoverText(stack, context, tooltipDisplay, tooltipAdder, tooltipFlag);
	}

	/**
	 * 记录“遥控器物品实体因伤害销毁”的标记，供卸载退役事件区分销毁与拾取路径。
	 */
	@Override
	public void onDestroyed(ItemEntity itemEntity) {
		LinkNodeRetireEvents.markDamageDiscard(itemEntity);
		super.onDestroyed(itemEntity);
	}

	/**
	 * 判断是否允许通过遥控器手势打开配对界面。
	 */
	protected boolean shouldOpenPairingUi(Player player, InteractionHand hand) {
		return RedstoneLinkConfig.canOpenPairingByLinker(player, hand);
	}

	/**
	 * 遥控器触发条件：主手 + 非潜行 + 副手为空。
	 */
	protected boolean canExecutePrimaryUse(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (player.isShiftKeyDown()) {
			return false;
		}
		// 副手必须为空，防止与副手物品交互语义冲突。
		return player.getOffhandItem().isEmpty();
	}

	/**
	 * 构建当前 triggerSource 工具的信号语义说明。
	 */
	protected Component buildSignalSemanticTooltip() {
		return Component.translatable("tooltip.redstonelink.trigger_source.signal_semantic.event").withStyle(ChatFormatting.GRAY);
	}

	/**
	 * 构建遥控器的跨区块能力边界说明。
	 * <p>
	 * 遥控器可参与强加载接管链路，但自身不是可常驻的在线节点对象。
	 * </p>
	 */
	static Component crossChunkCapabilityTooltip() {
		return Component.translatable("tooltip.redstonelink.linker.crosschunk.force_load_only").withStyle(ChatFormatting.GRAY);
	}

	/**
	 * 构建遥控器的跨区块能力边界说明。
	 */
	protected Component buildCrossChunkCapabilityTooltip() {
		return crossChunkCapabilityTooltip();
	}

	/**
	 * 追加本轮主动作提示。
	 * <p>
	 * 默认遥控器展示“触发已连接核心”；同步遥控器会覆盖为同步语义提示。
	 * </p>
	 */
	protected Component buildPrimaryUseTooltip() {
		return Component.translatable("tooltip.redstonelink.trigger_linker");
	}

	/**
	 * 统一在 tooltip 末尾追加灰色补充说明。
	 * <p>
	 * 子类若需补充额外灰色尾注，应覆盖本方法并先调用父类实现，
	 * 以保证全部灰色说明稳定落在 tooltip 最底部。
	 * </p>
	 */
	protected void appendGrayFooterTooltips(
		ItemStack stack,
		Item.TooltipContext context,
		net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
		java.util.function.Consumer<Component> tooltipAdder,
		TooltipFlag tooltipFlag
	) {
		tooltipComponents.add(buildSignalSemanticTooltip());
		tooltipComponents.add(buildCrossChunkCapabilityTooltip());
	}

	/**
	 * 执行站立右键主动作。
	 * <p>
	 * 默认行为为 TOGGLE/PULSE 触发；子类可覆盖为同步派发。
	 * </p>
	 */
	protected void executePrimaryUse(Level level, Player player, ItemStack stack) {
		triggerLinkedTargets(level, player, stack);
	}

	/**
	 * 供子类复用的序号补齐入口。
	 */
	protected final void ensureSerialAssigned(Level level, ItemStack stack) {
		ensureSerial(level, stack);
	}

	/**
	 * 供子类复用的配对界面打开入口。
	 */
	protected final void openPairingUi(Level level, Player player, ItemStack stack) {
		openPairingScreen(level, player, stack);
	}

	/**
	 * 确保物品具备唯一序号。
	 * <p>
	 * 仅在服务端写入，避免客户端预测写入导致状态分叉。
	 * </p>
	 *
	 * @param level 当前世界
	 * @param stack 物品栈
	 */
	private static void ensureSerial(Level level, ItemStack stack) {
		if (level instanceof ServerLevel serverLevel) {
			LinkItemData.ensureSerial(stack, serverLevel, LinkNodeType.TRIGGER_SOURCE);
		}
	}

	/**
	 * 打开遥控器配对界面并同步当前已连接核心列表。
	 *
	 * @param level 当前世界
	 * @param player 操作玩家
	 * @param stack 物品栈
	 */
	private static void openPairingScreen(Level level, Player player, ItemStack stack) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return;
		}
		PairingNetwork.openTriggerSourcePairing(
			serverPlayer,
			serial,
			LinkGuiDisplayContext.resolvePairingContextToken(stack, LinkNodeType.TRIGGER_SOURCE)
		);
		LinkItemData.syncCurrentLinksSnapshotIfSingle(stack, serverLevel);
	}

	/**
	 * 触发已连接目标并复用触发源同链路派发实现。
	 */
	private void triggerLinkedTargets(Level level, Player player, ItemStack stack) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return;
		}

		LinkSavedData savedData = LinkSavedData.get(serverLevel);
		if (!savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, serial)) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.source_serial_unallocated", serial));
			return;
		}
		if (savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, serial)) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.source_serial_retired", serial));
			return;
		}

		// 每次触发后都回写最新连接列表，确保物品提示信息与存档状态一致。
		LinkItemData.syncCurrentLinksSnapshotIfSingle(stack, serverLevel);
		LinkedTargetDispatchService.DispatchSummary dispatchSummary = LinkedTargetDispatchService.dispatchActivation(
			serverLevel,
			LinkNodeType.TRIGGER_SOURCE,
			serial,
			LinkNodeType.CORE,
			activationMode
		);
		if (dispatchSummary.totalTargets() == 0) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.target_not_set"));
			return;
		}
		if (dispatchSummary.handledCount() == 0) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.no_reachable_targets"));
			return;
		}
		RedstoneLinkAdvancementService.awardComeFindMeInTheEndIfMatched(
			serverPlayer,
			serverLevel.dimension(),
			dispatchSummary
		);
		if (RedstoneLinkConfig.crossChunk().notifyEnabled() && dispatchSummary.hasCrossChunkHandled()) {
			for (Component line : LinkedTargetDispatchService.buildCrossChunkNotifyMessages(dispatchSummary)) {
				serverPlayer.sendSystemMessage(line);
			}
		}
	}
}
