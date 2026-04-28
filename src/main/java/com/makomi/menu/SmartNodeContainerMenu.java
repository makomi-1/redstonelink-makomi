package com.makomi.menu;

import com.makomi.data.SmartNodeContainerData;
import com.makomi.data.SmartNodeContainerPlacementType;
import com.makomi.registry.ModItems;
import com.makomi.registry.ModMenuTypes;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 智能节点容器菜单。
 * <p>
 * 使用 6 行箱子布局承载节点库存，并把当前放置类型与自动排序开关通过数据槽同步到客户端。
 * </p>
 */
public class SmartNodeContainerMenu extends AbstractContainerMenu {
	public static final int ROW_COUNT = 6;
	public static final int CONTAINER_SLOT_COUNT = SmartNodeContainerData.SLOT_COUNT;
	public static final int BUTTON_TOGGLE_AUTO_SORT = 0;
	public static final int BUTTON_TOGGLE_CREATIVE_AUTO_CONSUME = 1;

	private static final int DATA_SELECTED_TYPE = 0;
	private static final int DATA_AUTO_SORT = 1;
	private static final int DATA_CREATIVE_AUTO_CONSUME = 2;

	private final SmartNodeContainerInventory container;
	private final ContainerData containerData;
	private final int heldSlotIndex;
	private final Player owner;
	private boolean sortingContents;

	/**
	 * 客户端构造器：使用空容器等待服务端槽位同步。
	 */
	public SmartNodeContainerMenu(int containerId, Inventory playerInventory) {
		this(
			containerId,
			playerInventory,
			playerInventory.getSelectedSlot(),
			new SmartNodeContainerInventory(NonNullList.withSize(CONTAINER_SLOT_COUNT, ItemStack.EMPTY)),
			new SimpleContainerData(3)
		);
	}

	/**
	 * 服务端构造器：从主手智能节点容器中读取库存与控制状态。
	 */
	public SmartNodeContainerMenu(int containerId, Inventory playerInventory, int heldSlotIndex) {
		this(
			containerId,
			playerInventory,
			heldSlotIndex,
			new SmartNodeContainerInventory(
				SmartNodeContainerData.readContents(
					playerInventory.getItem(heldSlotIndex),
					playerInventory.player.level().registryAccess()
				)
			),
			createDataSlots(playerInventory.getItem(heldSlotIndex))
		);
	}

	private SmartNodeContainerMenu(
		int containerId,
		Inventory playerInventory,
		int heldSlotIndex,
		SmartNodeContainerInventory container,
		ContainerData containerData
	) {
		super(ModMenuTypes.SMART_NODE_CONTAINER, containerId);
		checkContainerSize(container, CONTAINER_SLOT_COUNT);
		checkContainerDataCount(containerData, 3);
		this.container = container;
		this.containerData = containerData;
		this.heldSlotIndex = heldSlotIndex;
		this.owner = playerInventory.player;
		container.setChangeCallback(this::handleContainerChanged);
		container.startOpen(playerInventory.player);
		addContainerSlots();
		addPlayerInventorySlots(playerInventory);
		addPlayerHotbarSlots(playerInventory);
		addDataSlots(containerData);
	}

	@Override
	public boolean stillValid(Player player) {
		if (player == null || heldSlotIndex < 0 || heldSlotIndex >= player.getInventory().getContainerSize()) {
			return false;
		}
		ItemStack heldStack = player.getInventory().getItem(heldSlotIndex);
		return !heldStack.isEmpty() && heldStack.getItem() == ModItems.SMART_NODE_CONTAINER;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		ItemStack empty = ItemStack.EMPTY;
		if (index < 0 || index >= slots.size()) {
			return empty;
		}
		Slot sourceSlot = slots.get(index);
		if (sourceSlot == null || !sourceSlot.hasItem()) {
			return empty;
		}

		ItemStack sourceStack = sourceSlot.getItem();
		ItemStack sourceCopy = sourceStack.copy();
		if (index < CONTAINER_SLOT_COUNT) {
			if (!moveItemStackTo(sourceStack, CONTAINER_SLOT_COUNT, slots.size(), true)) {
				return empty;
			}
		} else {
			if (!SmartNodeContainerData.isAllowedNodeItem(sourceStack)) {
				return empty;
			}
			if (!moveItemStackTo(sourceStack, 0, CONTAINER_SLOT_COUNT, false)) {
				return empty;
			}
		}

		if (sourceStack.isEmpty()) {
			sourceSlot.set(ItemStack.EMPTY);
		} else {
			sourceSlot.setChanged();
		}
		return sourceCopy;
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (id == BUTTON_TOGGLE_AUTO_SORT) {
			setAutoSortEnabled(!isAutoSortEnabled());
			return true;
		}
		if (id == BUTTON_TOGGLE_CREATIVE_AUTO_CONSUME) {
			if (!canToggleCreativeAutoConsume()) {
				return false;
			}
			setCreativeAutoConsumeEnabled(!isCreativeAutoConsumeEnabled());
			return true;
		}
		return super.clickMenuButton(player, id);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		container.stopOpen(player);
		if (!player.level().isClientSide()) {
			persistToHeldItem();
		}
	}

	/**
	 * @return 当前选定的放置类型
	 */
	public SmartNodeContainerPlacementType selectedType() {
		return SmartNodeContainerPlacementType.values()[Math.max(0, Math.min(containerData.get(DATA_SELECTED_TYPE), SmartNodeContainerPlacementType.values().length - 1))];
	}

	/**
	 * @return 自动排序开关当前是否开启
	 */
	public boolean isAutoSortEnabled() {
		return containerData.get(DATA_AUTO_SORT) != 0;
	}

	/**
	 * @return 创造模式下当前是否自动消耗容器内节点
	 */
	public boolean isCreativeAutoConsumeEnabled() {
		return containerData.get(DATA_CREATIVE_AUTO_CONSUME) != 0;
	}

	/**
	 * @return 当前玩家是否允许切换创造自动消耗开关
	 */
	public boolean canToggleCreativeAutoConsume() {
		return owner != null && owner.getAbilities().instabuild;
	}

	private void addContainerSlots() {
		for (int row = 0; row < ROW_COUNT; row++) {
			for (int column = 0; column < 9; column++) {
				int slotIndex = column + row * 9;
				addSlot(
					new Slot(container, slotIndex, 8 + column * 18, 18 + row * 18) {
						@Override
						public boolean mayPlace(ItemStack stack) {
							return SmartNodeContainerData.isAllowedNodeItem(stack);
						}
					}
				);
			}
		}
	}

	private void addPlayerInventorySlots(Inventory playerInventory) {
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				int inventoryIndex = column + row * 9 + 9;
				addSlot(createPlayerSlot(playerInventory, inventoryIndex, 8 + column * 18, 140 + row * 18));
			}
		}
	}

	private void addPlayerHotbarSlots(Inventory playerInventory) {
		for (int column = 0; column < 9; column++) {
			addSlot(createPlayerSlot(playerInventory, column, 8 + column * 18, 198));
		}
	}

	private Slot createPlayerSlot(Inventory playerInventory, int inventoryIndex, int x, int y) {
		if (inventoryIndex != heldSlotIndex) {
			return new Slot(playerInventory, inventoryIndex, x, y);
		}
		return new Slot(playerInventory, inventoryIndex, x, y) {
			@Override
			public boolean mayPickup(Player player) {
				return false;
			}

			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		};
	}

	private void handleContainerChanged() {
		if (owner == null || owner.level().isClientSide()) {
			return;
		}
		if (sortingContents) {
			persistToHeldItem();
			return;
		}
		if (isAutoSortEnabled()) {
			applySortedContents();
			return;
		}
		persistToHeldItem();
	}

	private void setAutoSortEnabled(boolean autoSortEnabled) {
		containerData.set(DATA_AUTO_SORT, autoSortEnabled ? 1 : 0);
		if (autoSortEnabled) {
			applySortedContents();
			return;
		}
		persistToHeldItem();
	}

	private void setCreativeAutoConsumeEnabled(boolean creativeAutoConsumeEnabled) {
		containerData.set(DATA_CREATIVE_AUTO_CONSUME, creativeAutoConsumeEnabled ? 1 : 0);
		persistToHeldItem();
	}

	private void applySortedContents() {
		sortingContents = true;
		try {
			container.replaceContents(SmartNodeContainerData.sortContents(container.copyContents()));
		} finally {
			sortingContents = false;
		}
		persistToHeldItem();
	}

	private void persistToHeldItem() {
		if (owner == null || owner.level().isClientSide()) {
			return;
		}
		if (heldSlotIndex < 0 || heldSlotIndex >= owner.getInventory().getContainerSize()) {
			return;
		}
		ItemStack heldStack = owner.getInventory().getItem(heldSlotIndex);
		if (heldStack.isEmpty() || heldStack.getItem() != ModItems.SMART_NODE_CONTAINER) {
			return;
		}
		SmartNodeContainerData.write(
			heldStack,
			owner.level().registryAccess(),
			container.copyContents(),
			selectedType(),
			isAutoSortEnabled(),
			isCreativeAutoConsumeEnabled()
		);
		owner.containerMenu.broadcastChanges();
	}

	private static ContainerData createDataSlots(ItemStack containerStack) {
		SmartNodeContainerData.Snapshot snapshot = SmartNodeContainerData.read(containerStack);
		SimpleContainerData data = new SimpleContainerData(3);
		data.set(DATA_SELECTED_TYPE, snapshot.selectedType().ordinal());
		data.set(DATA_AUTO_SORT, snapshot.autoSortEnabled() ? 1 : 0);
		data.set(DATA_CREATIVE_AUTO_CONSUME, snapshot.creativeAutoConsumeEnabled() ? 1 : 0);
		return data;
	}

	/**
	 * 菜单内部使用的可回调容器实现。
	 */
	private static final class SmartNodeContainerInventory extends SimpleContainer {
		private Runnable changeCallback = () -> {
		};
		private boolean suppressCallback;

		private SmartNodeContainerInventory(NonNullList<ItemStack> contents) {
			super(CONTAINER_SLOT_COUNT);
			replaceContents(contents);
		}

		private void setChangeCallback(Runnable changeCallback) {
			this.changeCallback = changeCallback == null ? () -> {
			} : changeCallback;
		}

		private void replaceContents(NonNullList<ItemStack> contents) {
			suppressCallback = true;
			try {
				for (int index = 0; index < CONTAINER_SLOT_COUNT; index++) {
					ItemStack stack = contents == null || index >= contents.size() ? ItemStack.EMPTY : contents.get(index);
					super.setItem(index, stack == null ? ItemStack.EMPTY : stack.copy());
				}
			} finally {
				suppressCallback = false;
			}
			super.setChanged();
			changeCallback.run();
		}

		private NonNullList<ItemStack> copyContents() {
			NonNullList<ItemStack> copy = NonNullList.withSize(CONTAINER_SLOT_COUNT, ItemStack.EMPTY);
			for (int index = 0; index < CONTAINER_SLOT_COUNT; index++) {
				copy.set(index, getItem(index).copy());
			}
			return copy;
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return SmartNodeContainerData.isAllowedNodeItem(stack);
		}

		@Override
		public void setChanged() {
			super.setChanged();
			if (!suppressCallback) {
				changeCallback.run();
			}
		}
	}
}
