package com.makomi.item;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterDelay;
import com.makomi.data.RepeaterItemData;
import com.makomi.network.RepeaterNetwork;
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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 转发器方块物品。
 * <p>
 * 物品层对外仍表现为一个统一节点，但内部序号始终落到同号 `core/triggerSource` 双身份。
 * </p>
 */
public class RepeaterBlockItem extends BlockItem implements PairableItem {
	public RepeaterBlockItem(Block block, Item.Properties properties) {
		super(block, properties);
	}

	@Override
	public LinkNodeType getNodeType() {
		return LinkNodeType.CORE;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		ensureSerial(level, stack);
		if (shouldOpenEditor(player, hand)) {
			openEditor(level, player, stack);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return super.useOn(context);
		}
		ensureSerial(context.getLevel(), context.getItemInHand());
		if (shouldOpenEditor(player, context.getHand())) {
			openEditor(context.getLevel(), player, context.getItemInHand());
			return InteractionResult.SUCCESS;
		}
		return super.useOn(context);
	}

	@Override
	public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level, Entity entity, net.minecraft.world.entity.EquipmentSlot slot) {
		ensureSerial(level, stack);
		super.inventoryTick(stack, level, entity, slot);
	}

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
		RepeaterConfigSnapshot snapshot = RepeaterItemData.read(stack);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.serial",
				com.makomi.data.NodeAliasDisplayUtil.formatDisplayText(LinkItemData.getDisplayAlias(stack), serial)
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.repeater.delay",
				delayLabel(snapshot.delay())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.repeater.input",
				RepeaterItemData.buildTooltipInputText(stack, TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS)
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.repeater.output",
				RepeaterItemData.buildTooltipOutputText(stack, TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS)
			)
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.repeater.open_editor"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.repeater.dual_identity").withStyle(ChatFormatting.GRAY));
		HideNodeItemTooltipSupport.appendIfNeeded(stack, tooltipComponents);
		tooltipComponents.forEach(tooltipAdder);
		super.appendHoverText(stack, context, tooltipDisplay, tooltipAdder, tooltipFlag);
	}

	@Override
	public void onDestroyed(ItemEntity itemEntity) {
		com.makomi.data.LinkNodeRetireEvents.markDamageDiscard(itemEntity);
		super.onDestroyed(itemEntity);
	}

	private static Component delayLabel(RepeaterDelay delay) {
		RepeaterDelay normalized = delay == null ? RepeaterDelay.ONE_TICK : delay;
		return Component.translatable(normalized.translationKey());
	}

	private static boolean shouldOpenEditor(Player player, InteractionHand hand) {
		return RedstoneLinkConfig.canOpenPairingByHeldItem(player, hand);
	}

	private static void openEditor(Level level, Player player, ItemStack stack) {
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
			RepeaterNetwork.openHeldItemEditor(serverPlayer, stack);
		}
	}

	private static void ensureSerial(Level level, ItemStack stack) {
		if (level instanceof ServerLevel serverLevel) {
			RepeaterItemData.ensureSerial(stack, serverLevel);
		}
	}
}
