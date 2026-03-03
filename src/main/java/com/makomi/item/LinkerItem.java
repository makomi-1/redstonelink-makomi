package com.makomi.item;

import com.makomi.api.v1.RedstoneLinkApi;
import com.makomi.api.v1.model.ActorContext;
import com.makomi.api.v1.model.ApiActivationMode;
import com.makomi.api.v1.model.ApiNodeType;
import com.makomi.api.v1.model.TriggerRequest;
import com.makomi.api.v1.model.TriggerResult;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.network.PairingNetwork;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 手持遥控器物品：支持按钮节点配对与远程触发。
 * <p>
 * 交互约束：
 * 1. 潜行 + 主手右键：打开配对界面；
 * 2. 站立 + 主手右键 + 副手空：触发已连接核心；
 * 3. 物品不可放置（继承 Item 而非 BlockItem）。
 * </p>
 */
public class LinkerItem extends Item implements PairableItem {
	private final ApiActivationMode activationMode;

	/**
	 * @param properties 物品属性
	 * @param activationMode 触发模式（TOGGLE/PULSE）
	 */
	public LinkerItem(Item.Properties properties, ApiActivationMode activationMode) {
		super(properties);
		this.activationMode = activationMode;
	}

	@Override
	public LinkNodeType getNodeType() {
		return LinkNodeType.BUTTON;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		ensureSerial(level, heldStack);

		if (shouldOpenPairingUi(player, hand)) {
			openPairingScreen(level, player, heldStack);
			return InteractionResultHolder.sidedSuccess(heldStack, level.isClientSide);
		}
		if (canTrigger(player, hand)) {
			triggerLinkedTargets(level, player, heldStack);
			return InteractionResultHolder.sidedSuccess(heldStack, level.isClientSide);
		}
		return InteractionResultHolder.pass(heldStack);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		ItemStack heldStack = context.getItemInHand();
		ensureSerial(level, heldStack);

		Player player = context.getPlayer();
		if (player != null && shouldOpenPairingUi(player, context.getHand())) {
			openPairingScreen(level, player, heldStack);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		if (player != null && canTrigger(player, context.getHand())) {
			triggerLinkedTargets(level, player, heldStack);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
		ensureSerial(level, stack);
		super.inventoryTick(stack, level, entity, slotId, isSelected);
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		long serial = LinkItemData.getSerial(stack);
		List<Long> linkedSerials = LinkItemData.getLinkedSerials(stack);

		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.serial",
				serial > 0L ? Long.toString(serial) : "-"
			)
		);
		String linkedText = linkedSerials.isEmpty()
			? "-"
			: linkedSerials.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.links", linkedText));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.open_pairing"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.trigger_linker"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 配对入口固定为潜行手势，避免与“站立触发”冲突。
	 */
	private static boolean shouldOpenPairingUi(Player player, InteractionHand hand) {
		return player.isShiftKeyDown() && RedstoneLinkConfig.canOpenPairingByHeldItem(player, hand);
	}

	/**
	 * 遥控器触发条件：主手 + 非潜行 + 副手为空。
	 */
	private static boolean canTrigger(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		if (player.isShiftKeyDown()) {
			return false;
		}
		return player.getOffhandItem().isEmpty();
	}

	private static void ensureSerial(Level level, ItemStack stack) {
		if (level instanceof ServerLevel serverLevel) {
			LinkItemData.ensureSerial(stack, serverLevel, LinkNodeType.BUTTON);
		}
	}

	private static void openPairingScreen(Level level, Player player, ItemStack stack) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return;
		}
		PairingNetwork.openButtonPairing(serverPlayer, serial);
		LinkItemData.setLinkedSerials(stack, LinkSavedData.get(serverLevel).getLinkedCores(serial));
	}

	/**
	 * 通过 API 触发链路，保持与方块触发器一致的数据与事件语义。
	 */
	private void triggerLinkedTargets(Level level, Player player, ItemStack stack) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		long serial = LinkItemData.getSerial(stack);
		if (serial <= 0L) {
			return;
		}

		TriggerResult triggerResult = RedstoneLinkApi
			.trigger()
			.emit(new TriggerRequest(serverLevel, ApiNodeType.BUTTON, serial, activationMode, ActorContext.fromPlayer(player)));

		LinkItemData.setLinkedSerials(stack, LinkSavedData.get(serverLevel).getLinkedCores(serial));

		if (!triggerResult.success() || triggerResult.totalTargets() == 0) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.target_not_set"));
			return;
		}
		if (triggerResult.triggeredCount() == 0) {
			serverPlayer.sendSystemMessage(Component.translatable("message.redstonelink.no_reachable_targets"));
		}
	}
}
