package com.makomi.item;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.network.PairingNetwork;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public class PairableBlockItem extends BlockItem implements PairableItem {
	private final LinkNodeType nodeType;

	public PairableBlockItem(Block block, Item.Properties properties, LinkNodeType nodeType) {
		super(block, properties);
		this.nodeType = Objects.requireNonNull(nodeType);
	}

	@Override
	public LinkNodeType getNodeType() {
		return nodeType;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack heldStack = player.getItemInHand(hand);
		ensureSerial(level, heldStack);
		if (!canOpenPairingUi(player, hand)) {
			return InteractionResultHolder.pass(heldStack);
		}

		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			long serial = LinkItemData.getSerial(heldStack);
			if (serial > 0L) {
				if (nodeType == LinkNodeType.BUTTON) {
					PairingNetwork.openButtonPairing(serverPlayer, serial);
				} else if (nodeType == LinkNodeType.CORE) {
					PairingNetwork.openCorePairing(serverPlayer, serial);
				}
			}
		}
		return InteractionResultHolder.sidedSuccess(heldStack, level.isClientSide);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		ItemStack heldStack = context.getItemInHand();
		ensureSerial(level, heldStack);

		Player player = context.getPlayer();
		if (player != null
			&& canOpenPairingUi(player, context.getHand())
			&& (nodeType == LinkNodeType.BUTTON || nodeType == LinkNodeType.CORE)) {
			if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
				long serial = LinkItemData.getSerial(heldStack);
				if (serial > 0L) {
					if (nodeType == LinkNodeType.BUTTON) {
						PairingNetwork.openButtonPairing(serverPlayer, serial);
					} else {
						PairingNetwork.openCorePairing(serverPlayer, serial);
					}
				}
			}
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		return super.useOn(context);
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
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.links",
				linkedText
			)
		);
		if (nodeType == LinkNodeType.BUTTON || nodeType == LinkNodeType.CORE) {
			tooltipComponents.add(Component.translatable("tooltip.redstonelink.open_pairing"));
		}
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	private void ensureSerial(Level level, ItemStack stack) {
		if (level instanceof ServerLevel serverLevel) {
			LinkItemData.ensureSerial(stack, serverLevel, nodeType);
		}
	}

	/**
	 * 判断当前是否允许打开配对界面：交由配置策略统一判定。
	 */
	private boolean canOpenPairingUi(Player player, InteractionHand hand) {
		return RedstoneLinkConfig.canOpenPairingByHeldItem(player, hand);
	}
}
