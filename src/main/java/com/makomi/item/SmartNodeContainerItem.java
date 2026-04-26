package com.makomi.item;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.LinkNodeType;
import com.makomi.data.RepeaterItemData;
import com.makomi.data.SmartNodeContainerData;
import com.makomi.data.SmartNodeContainerPlacementType;
import com.makomi.menu.SmartNodeContainerMenu;
import com.makomi.registry.ModItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 智能节点容器物品。
 * <p>
 * 物品职责：
 * </p>
 * <ul>
 *   <li>`B` 键打开箱子式库存；</li>
 *   <li>鼠标中键切换当前放置类型；</li>
 *   <li>主手右键从容器中按当前类型弹出一个节点并尝试放置。</li>
 * </ul>
 */
public class SmartNodeContainerItem extends Item {
	public SmartNodeContainerItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		return InteractionResultHolder.pass(player.getItemInHand(hand));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null || context.getHand() != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		ItemStack containerStack = context.getItemInHand();
		SmartNodeContainerData.Snapshot snapshot = SmartNodeContainerData.read(containerStack);
		if (!snapshot.hasItems()) {
			return InteractionResult.PASS;
		}
		if (context.getLevel().isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (!(context.getLevel() instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}

		NonNullList<ItemStack> contents = SmartNodeContainerData.readContents(containerStack, serverLevel.registryAccess());
		int selectedSlotIndex = SmartNodeContainerData.findFirstSlotForType(contents, snapshot.selectedType());
		if (selectedSlotIndex < 0) {
			return InteractionResult.PASS;
		}

		ItemStack nestedStack = contents.get(selectedSlotIndex).copy();
		if (!(nestedStack.getItem() instanceof BlockItem blockItem)) {
			return InteractionResult.PASS;
		}
		ensureNestedNodeSerial(serverLevel, nestedStack);
		UseOnContext nestedContext = new UseOnContext(
			context.getLevel(),
			player,
			context.getHand(),
			nestedStack,
			new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), false)
		);
		InteractionResult result = blockItem.place(new BlockPlaceContext(nestedContext));
		if (!result.consumesAction()) {
			return result;
		}

		contents.set(selectedSlotIndex, nestedStack.isEmpty() ? ItemStack.EMPTY : nestedStack);
		if (snapshot.autoSortEnabled()) {
			contents = SmartNodeContainerData.sortContents(contents);
		}
		SmartNodeContainerData.write(
			containerStack,
			serverLevel.registryAccess(),
			contents,
			snapshot.selectedType(),
			snapshot.autoSortEnabled()
		);
		player.containerMenu.broadcastChanges();
		return result;
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
		SmartNodeContainerData.syncModelState(stack);
		super.inventoryTick(stack, level, entity, slotId, isSelected);
	}

	@Override
	public void onDestroyed(ItemEntity itemEntity) {
		LinkNodeRetireEvents.markDamageDiscard(itemEntity);
		super.onDestroyed(itemEntity);
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		SmartNodeContainerData.Snapshot snapshot = SmartNodeContainerData.read(stack);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.smart_node_container.current_type",
				Component.translatable(snapshot.selectedType().translationKey())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.smart_node_container.item_count",
				Integer.toString(snapshot.itemCount())
			)
		);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.smart_node_container.auto_sort",
				Component.translatable(
					snapshot.autoSortEnabled()
						? "screen.redstonelink.smart_node_container.toggle.on"
						: "screen.redstonelink.smart_node_container.toggle.off"
				)
			)
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_node_container.open"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_node_container.cycle_type"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.smart_node_container.place"));
		tooltipComponents.add(
			Component.translatable("tooltip.redstonelink.smart_node_container.connection_sync_notice").withStyle(ChatFormatting.GRAY)
		);
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 打开主手持有的智能节点容器。
	 */
	public static void openHeldMenu(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ItemStack mainHandStack = player.getMainHandItem();
		if (mainHandStack.isEmpty() || mainHandStack.getItem() != ModItems.SMART_NODE_CONTAINER) {
			return;
		}
		player.openMenu(
			new SimpleMenuProvider(
				(containerId, inventory, ignoredPlayer) -> new SmartNodeContainerMenu(containerId, inventory, inventory.selected),
				Component.translatable("screen.redstonelink.smart_node_container.title")
			)
		);
	}

	private static void ensureNestedNodeSerial(ServerLevel level, ItemStack nestedStack) {
		if (level == null || nestedStack == null || nestedStack.isEmpty()) {
			return;
		}
		if (nestedStack.getItem() == ModItems.LINK_REPEATER) {
			RepeaterItemData.ensureSerial(nestedStack, level);
			return;
		}
		if (nestedStack.getItem() instanceof PairableItem pairableItem) {
			LinkNodeType nodeType = pairableItem.getNodeType();
			LinkItemData.ensureSerial(nestedStack, level, nodeType);
		}
	}
}
