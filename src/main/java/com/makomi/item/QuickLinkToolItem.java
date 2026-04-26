package com.makomi.item;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.QuickLinkToolData;
import com.makomi.network.QuickLinkNetwork;
import java.util.List;
import net.minecraft.network.chat.Component;
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
 * 快速连接工具物品。
 * <p>
 * 当前交互约束：
 * </p>
 * <br/>1) 潜行右键：打开缓存编辑 GUI；
 * <br/>2) 左键命中方块：由客户端专用回调发送采集请求；
 * <br/>3) 站立右键命中方块：由客户端专用回调按当前应用编辑模式发送应用请求；
 * <br/>4) 鼠标中键：由客户端专用回调循环切换应用编辑模式；
 * <br/>5) 模式切换与清空：由客户端可配置按键触发，潜行时清空当前模式数据。
 */
public class QuickLinkToolItem extends Item {
	public QuickLinkToolItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		if (shouldOpenEditor(player, hand, heldStack)) {
			openEditor(level, player, heldStack);
			return InteractionResultHolder.sidedSuccess(heldStack, level.isClientSide);
		}
		return InteractionResultHolder.pass(heldStack);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}

		ItemStack heldStack = context.getItemInHand();
		if (shouldOpenEditor(player, context.getHand(), heldStack)) {
			openEditor(level, player, heldStack);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
		QuickLinkToolData.syncQuickLinkToolModelState(stack);
		super.inventoryTick(stack, level, entity, slotId, isSelected);
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(stack);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.quick_link.mode",
				Component.translatable(snapshot.mode().translationKey())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.quick_link.serial_cache_type",
				LinkNodeSemantics.toSemanticName(snapshot.serialCacheType())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.quick_link.serial_cache_expression",
				truncateTooltipText(snapshot.serialCacheExpression())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.quick_link.apply_edit_mode",
				Component.translatable(snapshot.applyEditMode().translationKey())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.quick_link.channel_cache",
				truncateTooltipText(snapshot.channelCache())
			)
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.quick_link.open_editor"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.quick_link.toggle_mode"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.quick_link.clear_cache"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.quick_link.toggle_apply_edit_mode"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.quick_link.collect"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.quick_link.apply"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 判断当前手势是否应打开编辑器。
	 */
	private static boolean shouldOpenEditor(Player player, InteractionHand hand, ItemStack stack) {
		return RedstoneLinkConfig.canOpenPairingByLinker(player, hand);
	}

	/**
	 * 在服务端打开缓存编辑器。
	 */
	private static void openEditor(Level level, Player player, ItemStack stack) {
		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			QuickLinkNetwork.openEditor(serverPlayer, stack);
		}
	}

	/**
	 * 对 tooltip 中的缓存文本做轻量截断。
	 */
	private static String truncateTooltipText(String text) {
		String normalized = text == null || text.isBlank() ? "-" : text.trim();
		if (normalized.length() <= TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS) {
			return normalized;
		}
		return normalized.substring(0, TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS - 4) + "...";
	}
}
