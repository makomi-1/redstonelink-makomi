package com.makomi.data;

import com.makomi.item.PairableItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 第二层聚合栈菜单即时处理服务。
 * <p>
 * 该服务负责接住原版点击时机，并在本地完成第二层聚合逻辑：
 * </p>
 * <ul>
 *   <li>`QUICK_MOVE`：按原版 `quickMoveStack + moveItemStackTo` 的目标槽段语义做补序号和归并；</li>
 *   <li>`PICKUP_ALL`：参考原版扫描顺序，把同类可配对物品即时并入光标聚合栈。</li>
 * </ul>
 * <p>
 * mixin 只负责转发 `HEAD/RETURN` 时机，所有具体上下文和业务逻辑都在本类内维护。
 * </p>
 */
public final class PairableItemAggregateMenuNormalizationService {
	private static boolean registered;
	private static final Map<AbstractContainerMenu, ClickContext> CLICK_CONTEXTS = new WeakHashMap<>();

	private PairableItemAggregateMenuNormalizationService() {
	}

	/**
	 * 保留初始化入口，便于主入口维持稳定调用链。
	 */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
	}

	/**
	 * 在点击前记录菜单上下文。
	 */
	public static void beginMenuClick(AbstractContainerMenu menu, int slotId, int button, ClickType clickType) {
		if (menu == null || !isSecondLayerClickType(clickType)) {
			return;
		}
		CLICK_CONTEXTS.put(menu, ClickContext.capture(menu, slotId, button, clickType));
	}

	/**
	 * 在点击后执行第二层即时处理。
	 */
	public static boolean finishMenuClick(
		AbstractContainerMenu menu,
		int slotId,
		int button,
		ClickType clickType,
		Player player
	) {
		if (menu == null || !isSecondLayerClickType(clickType)) {
			return false;
		}
		ClickContext context = CLICK_CONTEXTS.remove(menu);
		if (context == null) {
			return false;
		}
		if (context.slotId != slotId || context.button != button || context.clickType != clickType) {
			return false;
		}
		return switch (clickType) {
			case QUICK_MOVE -> handleQuickMove(menu, slotId, player, context.clickedStackBefore);
			case PICKUP_ALL -> handlePickupAll(menu, button, player, resolvePickupAllReferenceStack(menu, context));
			default -> false;
		};
	}

	/**
	 * 仅供测试清理上下文。
	 */
	static void clearClickContextsForTest() {
		CLICK_CONTEXTS.clear();
	}

	/**
	 * `QUICK_MOVE` 后处理：按目标槽段补序号并归并。
	 */
	static boolean handleQuickMove(AbstractContainerMenu menu, int slotId, Player player, ItemStack clickedStackBefore) {
		if (menu == null || clickedStackBefore == null || clickedStackBefore.isEmpty()) {
			return false;
		}
		QuickMoveRoutePlan routePlan = resolveQuickMoveRoutePlan(menu, slotId, player, clickedStackBefore);
		if (routePlan == null || routePlan.isEmpty()) {
			return false;
		}
		if (!isQuickMoveRelevant(menu, routePlan, clickedStackBefore)) {
			return false;
		}

		Item item = clickedStackBefore.getItem();
		boolean changed = false;
		for (QuickMoveRouteSegment segment : routePlan.segments()) {
			changed |= ensureSerialsInRouteSegment(menu, segment, item, player);
			changed |= normalizeRouteSegment(menu, segment, item);
		}
		if (changed) {
			menu.broadcastChanges();
		}
		return changed;
	}

	/**
	 * `PICKUP_ALL` 后处理：按原版双轮扫描并入光标聚合栈。
	 */
	static boolean handlePickupAll(AbstractContainerMenu menu, int button, Player player, ItemStack referenceStack) {
		if (menu == null || !isSecondLayerReferenceStack(referenceStack)) {
			return false;
		}
		ItemStack carriedStack = menu.getCarried();
		if (!canAggregateTogether(carriedStack, referenceStack)) {
			return false;
		}

		int start = button == 0 ? 0 : menu.slots.size() - 1;
		int end = button == 0 ? menu.slots.size() : -1;
		int step = button == 0 ? 1 : -1;
		boolean changed = false;

		for (int pass = 0; pass < 2 && LinkItemData.getRemainingAggregateCapacity(carriedStack) > 0; pass++) {
			for (int index = start; index != end && LinkItemData.getRemainingAggregateCapacity(carriedStack) > 0; index += step) {
				Slot slot = menu.slots.get(index);
				if (!isPickupAllCandidate(slot, carriedStack, player, pass)) {
					continue;
				}
				changed |= mergeSlotIntoCarried(slot, carriedStack, player);
			}
		}
		if (changed) {
			menu.broadcastChanges();
		}
		return changed;
	}

	/**
	 * 解析 `PICKUP_ALL` 的参考栈，优先点击前光标。
	 */
	private static ItemStack resolvePickupAllReferenceStack(AbstractContainerMenu menu, ClickContext context) {
		if (context == null) {
			return menu.getCarried().copy();
		}
		if (!context.carriedStackBefore.isEmpty()) {
			return context.carriedStackBefore;
		}
		if (!context.clickedStackBefore.isEmpty()) {
			return context.clickedStackBefore;
		}
		return menu.getCarried().copy();
	}

	/**
	 * 解析本次 `QUICK_MOVE` 的原版目标槽段。
	 */
	private static QuickMoveRoutePlan resolveQuickMoveRoutePlan(
		AbstractContainerMenu menu,
		int slotId,
		Player player,
		ItemStack clickedStackBefore
	) {
		if (menu == null || slotId < 0 || slotId >= menu.slots.size()) {
			return null;
		}
		if (menu instanceof InventoryMenu) {
			return resolveInventoryMenuRoutePlan(menu, slotId, player, clickedStackBefore);
		}
		if (menu instanceof CraftingMenu) {
			return resolveCraftingMenuRoutePlan(slotId);
		}
		if (isAbstractFurnaceMenu(menu)) {
			return resolveAbstractFurnaceRoutePlan(
				slotId,
				canPlaceInSlot(menu, 0, clickedStackBefore),
				canPlaceInSlot(menu, 1, clickedStackBefore)
			);
		}
		if (menu instanceof HopperMenu) {
			return resolveHopperRoutePlan(menu, slotId);
		}
		return resolveGenericContainerRoutePlan(menu, slotId);
	}

	/**
	 * 解析 `InventoryMenu` 路由，供测试直接调用。
	 */
	static QuickMoveRoutePlan resolveInventoryMenuRoutePlan(int slotId) {
		return resolveInventoryMenuRoutePlan(null, slotId, null, ItemStack.EMPTY);
	}

	/**
	 * 解析 `InventoryMenu` 路由，含护甲/副手优先。
	 */
	private static QuickMoveRoutePlan resolveInventoryMenuRoutePlan(
		AbstractContainerMenu menu,
		int slotId,
		Player player,
		ItemStack clickedStackBefore
	) {
		if (slotId == 0) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(9, 45, true));
		}
		if (slotId >= 1 && slotId < 9) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(9, 45, false));
		}
		if (slotId >= 9 && slotId < 45) {
			QuickMoveRoutePlan equipTarget = resolveInventoryEquipTarget(menu, player, clickedStackBefore);
			if (equipTarget != null) {
				return equipTarget;
			}
		}
		if (slotId >= 9 && slotId < 36) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(36, 45, false));
		}
		if (slotId >= 36 && slotId < 45) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(9, 36, false));
		}
		if (slotId == 45) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(9, 45, false));
		}
		return null;
	}

	/**
	 * `InventoryMenu` 中护甲/副手的优先落点。
	 */
	private static QuickMoveRoutePlan resolveInventoryEquipTarget(
		AbstractContainerMenu menu,
		Player player,
		ItemStack clickedStackBefore
	) {
		if (menu == null || player == null || clickedStackBefore == null || clickedStackBefore.isEmpty()) {
			return null;
		}
		EquipmentSlot equipmentSlot = player.getEquipmentSlotForItem(clickedStackBefore);
		if (equipmentSlot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
			int armorSlotIndex = 8 - equipmentSlot.getIndex();
			if (isSlotEmpty(menu, armorSlotIndex)) {
				return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(armorSlotIndex, armorSlotIndex + 1, false));
			}
		}
		if (equipmentSlot == EquipmentSlot.OFFHAND && isSlotEmpty(menu, 45)) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(45, 46, false));
		}
		return null;
	}

	/**
	 * 解析 `CraftingMenu` 路由。
	 */
	static QuickMoveRoutePlan resolveCraftingMenuRoutePlan(int slotId) {
		if (slotId == 0) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(10, 46, true));
		}
		if (slotId >= 1 && slotId < 10) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(10, 46, false));
		}
		if (slotId >= 10 && slotId < 37) {
			return QuickMoveRoutePlan.of(
				new QuickMoveRouteSegment(1, 10, false),
				new QuickMoveRouteSegment(37, 46, false)
			);
		}
		if (slotId >= 37 && slotId < 46) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(10, 37, false));
		}
		return null;
	}

	/**
	 * 解析炉子类菜单路由。
	 */
	static QuickMoveRoutePlan resolveAbstractFurnaceRoutePlan(int slotId, boolean canPlaceIngredient, boolean canPlaceFuel) {
		if (slotId == 2) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(3, 39, true));
		}
		if (slotId == 0 || slotId == 1) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(3, 39, false));
		}
		if (canPlaceIngredient) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(0, 1, false));
		}
		if (canPlaceFuel) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(1, 2, false));
		}
		if (slotId >= 3 && slotId < 30) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(30, 39, false));
		}
		if (slotId >= 30 && slotId < 39) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(3, 30, false));
		}
		return null;
	}

	/**
	 * 解析漏斗菜单路由。
	 */
	private static QuickMoveRoutePlan resolveHopperRoutePlan(AbstractContainerMenu menu, int slotId) {
		int playerInventoryStart = findPlayerInventoryStart(menu, 36);
		if (playerInventoryStart <= 0) {
			return null;
		}
		if (slotId < playerInventoryStart) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(playerInventoryStart, menu.slots.size(), true));
		}
		return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(0, playerInventoryStart, false));
	}

	/**
	 * 双区通用容器兜底路由。
	 */
	private static QuickMoveRoutePlan resolveGenericContainerRoutePlan(AbstractContainerMenu menu, int slotId) {
		int playerInventoryStart = findPlayerInventoryStart(menu, 36);
		if (playerInventoryStart <= 0) {
			return null;
		}
		if (slotId < playerInventoryStart) {
			return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(playerInventoryStart, menu.slots.size(), true));
		}
		return QuickMoveRoutePlan.of(new QuickMoveRouteSegment(0, playerInventoryStart, false));
	}

	/**
	 * 查找后缀式玩家背包区起始索引。
	 */
	private static int findPlayerInventoryStart(AbstractContainerMenu menu, int expectedPlayerSlotCount) {
		if (menu == null || menu.slots.isEmpty()) {
			return -1;
		}
		int size = menu.slots.size();
		if (expectedPlayerSlotCount <= 0 || size <= expectedPlayerSlotCount) {
			return -1;
		}
		int candidateStart = size - expectedPlayerSlotCount;
		for (int index = candidateStart; index < size; index++) {
			if (!isPlayerInventorySlot(menu.slots.get(index))) {
				return -1;
			}
		}
		return candidateStart;
	}

	/**
	 * 是否炉子抽象菜单。
	 */
	private static boolean isAbstractFurnaceMenu(AbstractContainerMenu menu) {
		if (menu == null) {
			return false;
		}
		Class<?> current = menu.getClass();
		while (current != null) {
			if ("net.minecraft.world.inventory.AbstractFurnaceMenu".equals(current.getName())) {
				return true;
			}
			current = current.getSuperclass();
		}
		return false;
	}

	/**
	 * 目标槽是否可放入指定物品。
	 */
	private static boolean canPlaceInSlot(AbstractContainerMenu menu, int slotIndex, ItemStack stack) {
		if (menu == null || stack == null || stack.isEmpty() || slotIndex < 0 || slotIndex >= menu.slots.size()) {
			return false;
		}
		Slot slot = menu.slots.get(slotIndex);
		return slot != null && slot.mayPlace(stack);
	}

	/**
	 * 目标槽是否为空。
	 */
	private static boolean isSlotEmpty(AbstractContainerMenu menu, int slotIndex) {
		if (menu == null || slotIndex < 0 || slotIndex >= menu.slots.size()) {
			return false;
		}
		Slot slot = menu.slots.get(slotIndex);
		return slot != null && !slot.hasItem();
	}

	/**
	 * 在目标槽段内补齐缺失序号。
	 */
	private static boolean ensureSerialsInRouteSegment(
		AbstractContainerMenu menu,
		QuickMoveRouteSegment segment,
		Item item,
		Player player
	) {
		if (menu == null || segment == null || item == null || !(player instanceof ServerPlayer serverPlayer)) {
			return false;
		}
		if (!(item instanceof PairableItem pairableItem)) {
			return false;
		}
		final boolean[] changed = { false };
		segment.forEachIndex(index -> {
			Slot slot = menu.slots.get(index);
			if (slot == null || !slot.hasItem()) {
				return;
			}
			ItemStack stack = slot.getItem();
			if (stack.getItem() != item || LinkItemData.getSerial(stack) > 0L) {
				return;
			}
			LinkItemData.ensureSerial(stack, serverPlayer.serverLevel(), pairableItem.getNodeType());
			slot.setChanged();
			changed[0] = true;
		});
		return changed[0];
	}

	/**
	 * 判断当前 quick move 是否需要进入第二层处理。
	 */
	private static boolean isQuickMoveRelevant(AbstractContainerMenu menu, QuickMoveRoutePlan routePlan, ItemStack clickedStackBefore) {
		if (menu == null || routePlan == null || clickedStackBefore == null || clickedStackBefore.isEmpty()) {
			return false;
		}
		if (clickedStackBefore.getItem() instanceof PairableItem) {
			return true;
		}
		return hasAnyProcessableStack(menu, routePlan, clickedStackBefore.getItem());
	}

	/**
	 * 目标槽段是否已有同类可处理栈。
	 */
	private static boolean hasAnyProcessableStack(AbstractContainerMenu menu, QuickMoveRoutePlan routePlan, Item item) {
		if (menu == null || routePlan == null || item == null) {
			return false;
		}
		for (QuickMoveRouteSegment segment : routePlan.segments()) {
			for (int index = segment.startInclusive(); index < segment.endExclusive(); index++) {
				Slot slot = menu.slots.get(index);
				if (slot == null || !slot.hasItem()) {
					continue;
				}
				ItemStack stack = slot.getItem();
				if (stack.getItem() == item && hasProcessableSerials(stack)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 在单个槽段内归并指定物品。
	 */
	private static boolean normalizeRouteSegment(AbstractContainerMenu menu, QuickMoveRouteSegment segment, Item item) {
		if (menu == null || segment == null || item == null) {
			return false;
		}
		Map<SlotPartitionKey, List<Slot>> partitions = collectRouteSegmentPartitions(menu.slots, segment, item);
		boolean changed = false;
		for (List<Slot> samePartitionSlots : partitions.values()) {
			changed |= normalizeSameItemSlots(samePartitionSlots);
		}
		return changed;
	}

	/**
	 * 按扫描方向收集槽段分区。
	 */
	private static Map<SlotPartitionKey, List<Slot>> collectRouteSegmentPartitions(
		List<Slot> slots,
		QuickMoveRouteSegment segment,
		Item item
	) {
		Map<SlotPartitionKey, List<Slot>> partitions = new LinkedHashMap<>();
		if (slots == null || slots.isEmpty() || segment == null || item == null) {
			return partitions;
		}
		segment.forEachIndex(index -> {
			Slot slot = slots.get(index);
			if (!isNormalizationCandidate(slot, item)) {
				return;
			}
			SlotPartitionKey key = new SlotPartitionKey(slot.container, slot.getClass());
			partitions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(slot);
		});
		return partitions;
	}

	/**
	 * 槽位是否参与归并。
	 */
	private static boolean isNormalizationCandidate(Slot slot, Item item) {
		if (slot == null || slot.container == null || !slot.hasItem()) {
			return false;
		}
		ItemStack stack = slot.getItem();
		if (stack.getItem() != item || !hasProcessableSerials(stack)) {
			return false;
		}
		return slot.mayPlace(stack);
	}

	/**
	 * 同分区同物品归并回填。
	 */
	private static boolean normalizeSameItemSlots(List<Slot> sameItemSlots) {
		if (sameItemSlots == null || sameItemSlots.size() <= 1) {
			return false;
		}
		List<Long> normalizedSerials = collectNormalizedSerials(sameItemSlots);
		if (normalizedSerials.isEmpty()) {
			return false;
		}

		int requiredSlots = (normalizedSerials.size() + LinkItemData.AGGREGATE_STACK_LIMIT - 1) / LinkItemData.AGGREGATE_STACK_LIMIT;
		boolean changed = false;
		for (int slotIndex = 0; slotIndex < sameItemSlots.size(); slotIndex++) {
			Slot slot = sameItemSlots.get(slotIndex);
			if (slotIndex < requiredSlots) {
				int startIndex = slotIndex * LinkItemData.AGGREGATE_STACK_LIMIT;
				int endIndex = Math.min(startIndex + LinkItemData.AGGREGATE_STACK_LIMIT, normalizedSerials.size());
				changed |= applyDesiredSerialGroup(slot, normalizedSerials.subList(startIndex, endIndex));
			} else {
				changed |= clearSlot(slot);
			}
		}
		return changed;
	}

	/**
	 * 收集去重升序序号。
	 */
	private static List<Long> collectNormalizedSerials(List<Slot> sameItemSlots) {
		TreeSet<Long> serials = new TreeSet<>();
		for (Slot slot : sameItemSlots) {
			for (Long serial : LinkItemData.getSerialGroup(slot.getItem())) {
				if (serial != null && serial > 0L) {
					serials.add(serial);
				}
			}
		}
		return List.copyOf(serials);
	}

	/**
	 * 把槽位改写为期望序号组。
	 */
	private static boolean applyDesiredSerialGroup(Slot slot, List<Long> desiredSerialGroup) {
		if (slot == null || desiredSerialGroup == null || desiredSerialGroup.isEmpty()) {
			return false;
		}
		ItemStack currentStack = slot.getItem();
		if (currentStack.isEmpty()) {
			return false;
		}
		List<Long> currentSerialGroup = LinkItemData.getSerialGroup(currentStack);
		if (currentStack.getCount() == 1 && currentSerialGroup.equals(desiredSerialGroup)) {
			return false;
		}
		ItemStack rewrittenStack = currentStack.copyWithCount(1);
		LinkItemData.setSerialGroup(rewrittenStack, desiredSerialGroup);
		slot.set(rewrittenStack);
		slot.setChanged();
		syncCurrentLinksSnapshotIfSingle(resolveSlotOwner(slot), rewrittenStack);
		return true;
	}

	/**
	 * 清空槽位。
	 */
	private static boolean clearSlot(Slot slot) {
		if (slot == null || !slot.hasItem()) {
			return false;
		}
		slot.set(ItemStack.EMPTY);
		slot.setChanged();
		return true;
	}

	/**
	 * 判断 `PICKUP_ALL` 扫描候选。
	 */
	private static boolean isPickupAllCandidate(Slot slot, ItemStack carriedStack, Player player, int pass) {
		if (slot == null || !slot.hasItem() || carriedStack == null || carriedStack.isEmpty()) {
			return false;
		}
		ItemStack slotStack = slot.getItem();
		if (!canAggregateTogether(carriedStack, slotStack) || !slot.mayPickup(player)) {
			return false;
		}
		boolean isFullAggregateStack = LinkItemData.getSerialCount(slotStack) >= LinkItemData.AGGREGATE_STACK_LIMIT;
		return pass != 0 || !isFullAggregateStack;
	}

	/**
	 * 把槽位并入光标栈。
	 */
	private static boolean mergeSlotIntoCarried(Slot slot, ItemStack carriedStack, Player player) {
		if (slot == null || carriedStack == null || carriedStack.isEmpty()) {
			return false;
		}
		ItemStack slotStack = slot.getItem();
		if (!canAggregateTogether(carriedStack, slotStack)) {
			return false;
		}
		if (!moveOrderedPrefixIntoStack(carriedStack, slotStack, LinkItemData.getSerialCount(slotStack))) {
			return false;
		}
		if (slotStack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		}
		slot.setChanged();
		syncCurrentLinksSnapshotIfSingle(resolveSlotOwner(slot), slotStack);
		syncCurrentLinksSnapshotIfSingle(player, carriedStack);
		return true;
	}

	/**
	 * 两栈是否允许聚合。
	 */
	private static boolean canAggregateTogether(ItemStack left, ItemStack right) {
		if (!hasProcessableSerials(left) || !hasProcessableSerials(right)) {
			return false;
		}
		return ItemStack.isSameItem(left, right);
	}

	/**
	 * 把来源前缀序号并入目标聚合栈。
	 */
	private static boolean moveOrderedPrefixIntoStack(ItemStack targetStack, ItemStack sourceStack, int requestedSerialCount) {
		if (targetStack == null || targetStack.isEmpty() || sourceStack == null || sourceStack.isEmpty() || requestedSerialCount <= 0) {
			return false;
		}
		int remainingCapacity = LinkItemData.getRemainingAggregateCapacity(targetStack);
		if (remainingCapacity <= 0) {
			return false;
		}
		List<Long> sourceSerials = LinkItemData.getSerialGroup(sourceStack);
		if (sourceSerials.isEmpty()) {
			return false;
		}
		int movedCount = Math.min(Math.min(requestedSerialCount, remainingCapacity), sourceSerials.size());
		if (movedCount <= 0) {
			return false;
		}

		List<Long> merged = new ArrayList<>(LinkItemData.getSerialGroup(targetStack));
		merged.addAll(sourceSerials.subList(0, movedCount));
		LinkItemData.setSerialGroup(targetStack, merged);
		if (movedCount >= sourceSerials.size()) {
			sourceStack.shrink(1);
			return true;
		}
		LinkItemData.setSerialGroup(sourceStack, sourceSerials.subList(movedCount, sourceSerials.size()));
		return true;
	}

	/**
	 * 可处理栈定义：有有效序号。
	 */
	private static boolean hasProcessableSerials(ItemStack stack) {
		return stack != null && !stack.isEmpty() && LinkItemData.getSerial(stack) > 0L;
	}

	/**
	 * 第二层参考栈定义：可配对类型或已有序号。
	 */
	private static boolean isSecondLayerReferenceStack(ItemStack stack) {
		return stack != null && !stack.isEmpty() && (stack.getItem() instanceof PairableItem || hasProcessableSerials(stack));
	}

	/**
	 * 是否玩家背包槽位。
	 */
	private static boolean isPlayerInventorySlot(Slot slot) {
		return slot != null && slot.container instanceof Inventory;
	}

	/**
	 * 同步单件当前连接快照。
	 */
	private static void syncCurrentLinksSnapshotIfSingle(Player player, ItemStack stack) {
		if (!(player instanceof ServerPlayer serverPlayer) || stack == null || stack.isEmpty()) {
			return;
		}
		LinkItemData.syncCurrentLinksSnapshotIfSingle(stack, serverPlayer.serverLevel());
	}

	/**
	 * 槽位所属玩家，仅玩家背包可反解。
	 */
	private static Player resolveSlotOwner(Slot slot) {
		if (slot == null) {
			return null;
		}
		if (slot.container instanceof Inventory inventory) {
			return inventory.player;
		}
		return null;
	}

	/**
	 * 第二层处理的点击类型。
	 */
	private static boolean isSecondLayerClickType(ClickType clickType) {
		return clickType == ClickType.QUICK_MOVE || clickType == ClickType.PICKUP_ALL;
	}

	/**
	 * 点击上下文快照。
	 */
	private static final class ClickContext {
		private final int slotId;
		private final int button;
		private final ClickType clickType;
		private final ItemStack clickedStackBefore;
		private final ItemStack carriedStackBefore;

		private ClickContext(
			int slotId,
			int button,
			ClickType clickType,
			ItemStack clickedStackBefore,
			ItemStack carriedStackBefore
		) {
			this.slotId = slotId;
			this.button = button;
			this.clickType = clickType;
			this.clickedStackBefore = clickedStackBefore;
			this.carriedStackBefore = carriedStackBefore;
		}

		private static ClickContext capture(AbstractContainerMenu menu, int slotId, int button, ClickType clickType) {
			return new ClickContext(slotId, button, clickType, copySlotStack(menu, slotId), menu.getCarried().copy());
		}

		private static ItemStack copySlotStack(AbstractContainerMenu menu, int slotId) {
			if (menu == null || slotId < 0 || slotId >= menu.slots.size()) {
				return ItemStack.EMPTY;
			}
			Slot slot = menu.slots.get(slotId);
			if (slot == null || !slot.hasItem()) {
				return ItemStack.EMPTY;
			}
			return slot.getItem().copy();
		}
	}

	/**
	 * quick move 路由计划。
	 */
	static final class QuickMoveRoutePlan {
		private final List<QuickMoveRouteSegment> segments;

		private QuickMoveRoutePlan(List<QuickMoveRouteSegment> segments) {
			this.segments = segments;
		}

		private static QuickMoveRoutePlan of(QuickMoveRouteSegment... segments) {
			List<QuickMoveRouteSegment> values = new ArrayList<>();
			if (segments != null) {
				for (QuickMoveRouteSegment segment : segments) {
					if (segment != null) {
						values.add(segment);
					}
				}
			}
			return new QuickMoveRoutePlan(List.copyOf(values));
		}

		List<QuickMoveRouteSegment> segments() {
			return segments;
		}

		boolean isEmpty() {
			return segments.isEmpty();
		}
	}

	/**
	 * quick move 单段槽位定义。
	 */
	static final class QuickMoveRouteSegment {
		private final int startInclusive;
		private final int endExclusive;
		private final boolean reverse;

		private QuickMoveRouteSegment(int startInclusive, int endExclusive, boolean reverse) {
			this.startInclusive = startInclusive;
			this.endExclusive = endExclusive;
			this.reverse = reverse;
		}

		int startInclusive() {
			return startInclusive;
		}

		int endExclusive() {
			return endExclusive;
		}

		boolean reverse() {
			return reverse;
		}

		void forEachIndex(IntConsumer consumer) {
			if (consumer == null || startInclusive >= endExclusive) {
				return;
			}
			if (!reverse) {
				for (int index = startInclusive; index < endExclusive; index++) {
					consumer.accept(index);
				}
			} else {
				for (int index = endExclusive - 1; index >= startInclusive; index--) {
					consumer.accept(index);
				}
			}
		}
	}

	/**
	 * 归并分区键：容器身份 + 槽位类。
	 */
	private static final class SlotPartitionKey {
		private final Container container;
		private final Class<? extends Slot> slotClass;

		private SlotPartitionKey(Container container, Class<? extends Slot> slotClass) {
			this.container = container;
			this.slotClass = slotClass;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof SlotPartitionKey that)) {
				return false;
			}
			return container == that.container && slotClass == that.slotClass;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(container) * 31 + slotClass.hashCode();
		}
	}
}
