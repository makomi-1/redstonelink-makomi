package com.makomi.item;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeRetireEvents;
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
 * 可配对方块物品基类。
 * <p>
 * 统一处理序列号分配、手持配对界面打开与物品销毁退役标记协作。
 * </p>
 */
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
				PairingNetwork.openPairingBySourceType(serverPlayer, nodeType, serial);
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
			&& (nodeType == LinkNodeType.TRIGGER_SOURCE || nodeType == LinkNodeType.CORE)) {
			if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
				long serial = LinkItemData.getSerial(heldStack);
				if (serial > 0L) {
					PairingNetwork.openPairingBySourceType(serverPlayer, nodeType, serial);
				}
			}
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		return super.useOn(context);
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
		// 兜底确保任何获取路径下都能补齐节点序列号。
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
		// 约定无连接时显示 -，超长时按字符数截断并补充 …(+N)。
		String linkedText = TooltipTextTruncateUtil.buildTargetsText(
			linkedSerials,
			TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.links",
				linkedText
			)
		);
		if (nodeType == LinkNodeType.TRIGGER_SOURCE || nodeType == LinkNodeType.CORE) {
			tooltipComponents.add(Component.translatable("tooltip.redstonelink.open_pairing"));
		}
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 记录“物品实体由伤害销毁”的标记，供退役事件在卸载阶段区分销毁与拾取路径。
	 */
	@Override
	public void onDestroyed(ItemEntity itemEntity) {
		LinkNodeRetireEvents.markDamageDiscard(itemEntity);
		super.onDestroyed(itemEntity);
	}

	/**
	 * 仅在服务端写入序列号，避免客户端状态分叉。
	 */
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

