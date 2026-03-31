package com.makomi.item;

import com.makomi.data.StatePanelToolData;
import com.makomi.network.StatePanelNetwork;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 状态面板工具物品。
 * <p>
 * 右键打开状态面板，不参与可配对物品聚合逻辑。
 * </p>
 */
public class StatePanelToolItem extends Item {
	public StatePanelToolItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResultHolder.pass(heldStack);
		}
		openPanel(level, player, heldStack);
		return InteractionResultHolder.sidedSuccess(heldStack, level.isClientSide);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getHand() != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		openPanel(context.getLevel(), player, context.getItemInHand());
		return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		int count = StatePanelToolData.subscriptionCount(stack);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.state_panel.subscriptions", Integer.toString(count)));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.state_panel.open_panel"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.state_panel.validation"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.state_panel.auto_refresh"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 在服务端打开状态面板。
	 */
	private static void openPanel(Level level, Player player, ItemStack stack) {
		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			StatePanelNetwork.openPanel(serverPlayer, stack);
		}
	}
}