package com.makomi.item;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.network.PairingNetwork;
import com.makomi.registry.ModItems;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
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
				PairingNetwork.openPairingBySourceType(
					serverPlayer,
					nodeType,
					serial,
					LinkGuiDisplayContext.resolvePairingContextToken(heldStack, nodeType)
				);
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
					PairingNetwork.openPairingBySourceType(
						serverPlayer,
						nodeType,
						serial,
						LinkGuiDisplayContext.resolvePairingContextToken(heldStack, nodeType)
					);
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
		if (nodeType == LinkNodeType.TRIGGER_SOURCE || nodeType == LinkNodeType.CORE) {
			long channel = LinkItemData.getChannel(stack);
			tooltipComponents.add(
				Component.translatable(
					"tooltip.redstonelink.channel",
					channel > 0L ? Long.toString(channel) : "-"
				)
			);
		}
		// 当前连接摘要优先压缩连续 `#序号` 区间，别名节点保持原样并在其处断开。
		String linkedText = TooltipTextTruncateUtil.buildTargetDisplayTextsText(
			linkedSerials,
			linkedDisplayTexts,
			TooltipTextTruncateUtil.DEFAULT_TOOLTIP_MAX_CHARS
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.links",
				linkedText
			)
		);
		if (nodeType == LinkNodeType.TRIGGER_SOURCE || nodeType == LinkNodeType.CORE) {
			tooltipComponents.add(
				Component.translatable(
					"tooltip.redstonelink.open_pairing"
				)
			);
		}
		appendTriggerSourceSignalSemanticTooltipIfNeeded(stack, tooltipComponents);
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
		appendHideNodeVisibilityTooltipIfNeeded(stack, tooltipComponents);
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

	/**
	 * 仅为 triggerSource 物品补充“事件信号 / 状态信号”语义说明。
	 */
	private void appendTriggerSourceSignalSemanticTooltipIfNeeded(ItemStack stack, List<Component> tooltipComponents) {
		if (nodeType != LinkNodeType.TRIGGER_SOURCE) {
			return;
		}
		tooltipComponents.add(buildTriggerSourceSignalSemanticTooltip(stack));
	}

	/**
	 * 根据当前 triggerSource 物品类型构建对应的信号语义说明。
	 */
	private static Component buildTriggerSourceSignalSemanticTooltip(ItemStack stack) {
		String translationKey = isSyncTriggerSourceItem(stack)
			? "tooltip.redstonelink.trigger_source.signal_semantic.state"
			: "tooltip.redstonelink.trigger_source.signal_semantic.event";
		return Component.translatable(translationKey).withStyle(ChatFormatting.GRAY);
	}

	/**
	 * 为隐藏节点物品补充“需佩戴智能眼镜才可见并可交互”的说明。
	 */
	private static void appendHideNodeVisibilityTooltipIfNeeded(ItemStack stack, List<Component> tooltipComponents) {
		if (!isHideNodeItem(stack)) {
			return;
		}
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.hide_node.smart_glasses_required").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 判断当前 triggerSource 是否属于 sync 状态信号类型。
	 */
	private static boolean isSyncTriggerSourceItem(ItemStack stack) {
		Item item = stack.getItem();
		return item == ModItems.LINK_SYNC_LEVER
			|| item == ModItems.LINK_SYNC_EMITTER
			|| item == ModItems.HIDE_SYNC_TRIGGER_SOURCE;
	}

	/**
	 * 判断当前物品是否属于隐藏节点。
	 */
	private static boolean isHideNodeItem(ItemStack stack) {
		Item item = stack.getItem();
		return item == ModItems.HIDE_CORE || item == ModItems.HIDE_SYNC_TRIGGER_SOURCE;
	}

}

