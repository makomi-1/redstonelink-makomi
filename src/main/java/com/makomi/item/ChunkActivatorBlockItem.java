package com.makomi.item;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorItemData;
import com.makomi.data.ChunkActivatorMode;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.network.ChunkActivatorNetwork;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 区块激活器方块物品。
 */
public class ChunkActivatorBlockItem extends BlockItem {
	public ChunkActivatorBlockItem(Block block, Item.Properties properties) {
		super(block, properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		if (shouldOpenEditor(player, hand)) {
			openEditor(level, player, heldStack);
			return InteractionResultHolder.sidedSuccess(heldStack, level.isClientSide);
		}
		return InteractionResultHolder.pass(heldStack);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return super.useOn(context);
		}
		if (shouldOpenEditor(player, context.getHand())) {
			openEditor(context.getLevel(), player, context.getItemInHand());
			return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
		}
		return super.useOn(context);
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		ChunkActivatorConfigSnapshot snapshot = ChunkActivatorItemData.read(stack);
		String displayAlias = NodeAliasDisplayUtil.normalizeAlias(ChunkActivatorItemData.getDisplayAlias(stack));
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.chunk_activator.alias",
				displayAlias.isEmpty() ? "-" : displayAlias
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.chunk_activator.service_target",
				LinkNodeSemantics.toSemanticName(LinkNodeType.TRIGGER_SOURCE)
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.chunk_activator.mode",
				modeLabel(snapshot.mode())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.chunk_activator.serial_expression",
				ChunkActivatorItemData.buildTooltipSerialExpressionText(snapshot, TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS)
			)
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.chunk_activator.open_editor"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	private static boolean shouldOpenEditor(Player player, InteractionHand hand) {
		return RedstoneLinkConfig.canOpenPairingByHeldItem(player, hand);
	}

	private void openEditor(Level level, Player player, ItemStack stack) {
		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			ChunkActivatorNetwork.openHeldItemEditor(serverPlayer, stack);
		}
	}

	private static Component modeLabel(ChunkActivatorMode mode) {
		ChunkActivatorMode normalizedMode = mode == null ? ChunkActivatorMode.FORCE_LOAD : mode;
		return switch (normalizedMode) {
			case FORCE_LOAD -> Component.translatable("screen.redstonelink.chunk_activator.mode.force_load");
			case RESIDENT -> Component.translatable("screen.redstonelink.chunk_activator.mode.resident");
		};
	}
}
