package com.makomi.data;

import com.mojang.serialization.Codec;
import com.makomi.item.PairableItem;
import com.makomi.registry.ModItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

/**
 * 智能节点容器物品数据读写工具。
 * <p>
 * 负责统一管理容器内部库存、当前放置类型、自动排序开关与物品模型状态。
 * </p>
 */
public final class SmartNodeContainerData {
	public static final int SLOT_COUNT = 54;

	private static final String KEY_ITEMS = "rl_smart_node_container_items";
	private static final String KEY_SELECTED_TYPE = "rl_smart_node_container_selected_type";
	private static final String KEY_AUTO_SORT = "rl_smart_node_container_auto_sort";
	private static final String KEY_CREATIVE_AUTO_CONSUME = "rl_smart_node_container_creative_auto_consume";
	private static final String KEY_ITEM_COUNT = "rl_smart_node_container_item_count";
	private static final Codec<List<ItemStack>> ITEM_LIST_CODEC = ItemStack.OPTIONAL_CODEC.listOf();

	private SmartNodeContainerData() {
	}

	/**
	 * 读取不含库存明细的轻量快照。
	 */
	public static Snapshot read(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return new Snapshot(
			readSelectedType(tag),
			tag.getBooleanOr(KEY_AUTO_SORT, false),
			!tag.contains(KEY_CREATIVE_AUTO_CONSUME) || tag.getBooleanOr(KEY_CREATIVE_AUTO_CONSUME, true),
			Math.max(0, tag.getIntOr(KEY_ITEM_COUNT, 0))
		);
	}

	/**
	 * 读取容器库存。
	 */
	public static NonNullList<ItemStack> readContents(ItemStack stack, HolderLookup.Provider provider) {
		NonNullList<ItemStack> contents = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
		if (stack == null || stack.isEmpty() || provider == null) {
			return contents;
		}
		CompoundTag rootTag = readTag(stack);
		if (!rootTag.contains(KEY_ITEMS)) {
			return contents;
		}
		List<ItemStack> storedItems = rootTag
			.read(KEY_ITEMS, ITEM_LIST_CODEC, RegistryOps.create(NbtOps.INSTANCE, provider))
			.orElse(List.of());
		for (int index = 0; index < Math.min(contents.size(), storedItems.size()); index++) {
			ItemStack storedStack = storedItems.get(index);
			contents.set(index, storedStack == null ? ItemStack.EMPTY : storedStack.copy());
		}
		return contents;
	}

	/**
	 * 完整写回容器库存与控制状态。
	 */
	public static Snapshot write(
		ItemStack stack,
		HolderLookup.Provider provider,
		NonNullList<ItemStack> contents,
		SmartNodeContainerPlacementType selectedType,
		boolean autoSortEnabled,
		boolean creativeAutoConsumeEnabled
	) {
		NonNullList<ItemStack> normalizedContents = normalizeContents(contents);
		SmartNodeContainerPlacementType normalizedType = normalizeSelectedType(selectedType);
		int itemCount = countNonEmptySlots(normalizedContents);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (provider == null || itemCount <= 0) {
				tag.remove(KEY_ITEMS);
			} else {
				tag.store(
					KEY_ITEMS,
					ITEM_LIST_CODEC,
					RegistryOps.create(NbtOps.INSTANCE, provider),
					copyContentsForStorage(normalizedContents)
				);
			}
			writeControlState(tag, normalizedType, autoSortEnabled, creativeAutoConsumeEnabled, itemCount);
		});
		syncModelState(stack, itemCount, normalizedType);
		return new Snapshot(normalizedType, autoSortEnabled, creativeAutoConsumeEnabled, itemCount);
	}

	/**
	 * 仅写回当前放置类型、自动排序开关与汇总数量，不触碰库存明细。
	 */
	public static Snapshot writeControlState(
		ItemStack stack,
		SmartNodeContainerPlacementType selectedType,
		boolean autoSortEnabled,
		boolean creativeAutoConsumeEnabled,
		int itemCount
	) {
		SmartNodeContainerPlacementType normalizedType = normalizeSelectedType(selectedType);
		int normalizedCount = Math.max(0, itemCount);
		CustomData.update(
			DataComponents.CUSTOM_DATA,
			stack,
			tag -> writeControlState(tag, normalizedType, autoSortEnabled, creativeAutoConsumeEnabled, normalizedCount)
		);
		syncModelState(stack, normalizedCount, normalizedType);
		return new Snapshot(normalizedType, autoSortEnabled, creativeAutoConsumeEnabled, normalizedCount);
	}

	/**
	 * 按当前存量循环到下一个放置类型，并即时同步模型状态。
	 */
	public static Snapshot cycleSelectedType(ItemStack stack) {
		Snapshot currentSnapshot = read(stack);
		return writeControlState(
			stack,
			currentSnapshot.selectedType().next(),
			currentSnapshot.autoSortEnabled(),
			currentSnapshot.creativeAutoConsumeEnabled(),
			currentSnapshot.itemCount()
		);
	}

	/**
	 * 按当前持久化状态循环切换“创造自动消耗”。
	 */
	public static Snapshot toggleCreativeAutoConsume(ItemStack stack) {
		Snapshot currentSnapshot = read(stack);
		return writeControlState(
			stack,
			currentSnapshot.selectedType(),
			currentSnapshot.autoSortEnabled(),
			!currentSnapshot.creativeAutoConsumeEnabled(),
			currentSnapshot.itemCount()
		);
	}

	/**
	 * 基于当前持久化汇总信息补齐模型状态。
	 */
	public static void syncModelState(ItemStack stack) {
		Snapshot snapshot = read(stack);
		syncModelState(stack, snapshot.itemCount(), snapshot.selectedType());
	}

	/**
	 * 当前物品是否允许放入智能节点容器。
	 */
	public static boolean isAllowedNodeItem(ItemStack stack) {
		if (stack == null || stack.isEmpty() || stack.getCount() != 1 || LinkItemData.isAggregated(stack)) {
			return false;
		}
		return classify(stack) != null;
	}

	/**
	 * 把一个节点物品归类到智能节点容器的三类之一。
	 */
	public static SmartNodeContainerPlacementType classify(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		if (stack.getItem() == ModItems.LINK_REPEATER) {
			return SmartNodeContainerPlacementType.REPEATER;
		}
		if (!(stack.getItem() instanceof PairableItem pairableItem)) {
			return null;
		}
		if (pairableItem.getNodeType() == LinkNodeType.CORE) {
			return SmartNodeContainerPlacementType.CORE;
		}
		if (pairableItem.getNodeType() == LinkNodeType.TRIGGER_SOURCE) {
			return SmartNodeContainerPlacementType.TRIGGER_SOURCE;
		}
		return null;
	}

	/**
	 * 在当前库存中查找首个匹配所选类型的槽位。
	 */
	public static int findFirstSlotForType(NonNullList<ItemStack> contents, SmartNodeContainerPlacementType selectedType) {
		if (contents == null || contents.isEmpty()) {
			return -1;
		}
		SmartNodeContainerPlacementType normalizedType = normalizeSelectedType(selectedType);
		for (int index = 0; index < contents.size(); index++) {
			if (classify(contents.get(index)) == normalizedType) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * 对容器内容执行稳定排序：
	 * core 在前，triggerSource 其次，repeater 最后；类内按序号升序。
	 */
	public static NonNullList<ItemStack> sortContents(NonNullList<ItemStack> contents) {
		NonNullList<ItemStack> normalizedContents = normalizeContents(contents);
		List<ItemStack> sortedItems = new ArrayList<>();
		for (ItemStack stack : normalizedContents) {
			if (!stack.isEmpty()) {
				sortedItems.add(stack.copy());
			}
		}
		sortedItems.sort(
			Comparator
				.comparingInt((ItemStack stack) -> classify(stack) == null ? Integer.MAX_VALUE : classify(stack).sortOrder())
				.thenComparingLong(LinkItemData::getSerial)
		);
		NonNullList<ItemStack> result = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
		for (int index = 0; index < Math.min(result.size(), sortedItems.size()); index++) {
			result.set(index, sortedItems.get(index));
		}
		return result;
	}

	/**
	 * 统计容器内非空槽位数。
	 */
	public static int countNonEmptySlots(NonNullList<ItemStack> contents) {
		if (contents == null || contents.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (ItemStack stack : contents) {
			if (stack != null && !stack.isEmpty()) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 复制一份可独立修改的容器内容快照。
	 */
	public static NonNullList<ItemStack> copyContents(NonNullList<ItemStack> contents) {
		return normalizeContents(contents);
	}

	/**
	 * 查找首个空槽位。
	 */
	public static int findFirstEmptySlot(NonNullList<ItemStack> contents) {
		if (contents == null || contents.isEmpty()) {
			return -1;
		}
		for (int index = 0; index < Math.min(SLOT_COUNT, contents.size()); index++) {
			ItemStack stack = contents.get(index);
			if (stack == null || stack.isEmpty()) {
				return index;
			}
		}
		return -1;
	}

	/**
	 * 尝试把一个节点物品写入首个空槽位。
	 *
	 * @return 写入成功返回 true；空间不足或物品不合法返回 false
	 */
	public static boolean tryInsertNodeItem(NonNullList<ItemStack> contents, ItemStack stack) {
		if (contents == null || !isAllowedNodeItem(stack)) {
			return false;
		}
		int emptySlot = findFirstEmptySlot(contents);
		if (emptySlot < 0) {
			return false;
		}
		contents.set(emptySlot, stack.copyWithCount(1));
		return true;
	}

	private static NonNullList<ItemStack> normalizeContents(NonNullList<ItemStack> contents) {
		NonNullList<ItemStack> normalizedContents = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
		if (contents == null) {
			return normalizedContents;
		}
		for (int index = 0; index < Math.min(SLOT_COUNT, contents.size()); index++) {
			ItemStack stack = contents.get(index);
			normalizedContents.set(index, stack == null ? ItemStack.EMPTY : stack.copy());
		}
		return normalizedContents;
	}

	private static SmartNodeContainerPlacementType normalizeSelectedType(SmartNodeContainerPlacementType selectedType) {
		return selectedType == null ? SmartNodeContainerPlacementType.CORE : selectedType;
	}

	private static void writeControlState(
		CompoundTag tag,
		SmartNodeContainerPlacementType selectedType,
		boolean autoSortEnabled,
		boolean creativeAutoConsumeEnabled,
		int itemCount
	) {
		tag.putString(KEY_SELECTED_TYPE, normalizeSelectedType(selectedType).token());
		if (autoSortEnabled) {
			tag.putBoolean(KEY_AUTO_SORT, true);
		} else {
			tag.remove(KEY_AUTO_SORT);
		}
		tag.putBoolean(KEY_CREATIVE_AUTO_CONSUME, creativeAutoConsumeEnabled);
		if (itemCount > 0) {
			tag.putInt(KEY_ITEM_COUNT, itemCount);
		} else {
			tag.remove(KEY_ITEM_COUNT);
		}
	}

	private static SmartNodeContainerPlacementType readSelectedType(CompoundTag tag) {
		if (tag == null || !tag.contains(KEY_SELECTED_TYPE)) {
			return SmartNodeContainerPlacementType.CORE;
		}
		return SmartNodeContainerPlacementType.parse(tag.getStringOr(KEY_SELECTED_TYPE, ""));
	}

	private static void syncModelState(
		ItemStack stack,
		int itemCount,
		SmartNodeContainerPlacementType selectedType
	) {
		if (stack == null || stack.isEmpty() || itemCount <= 0) {
			stack.remove(DataComponents.CUSTOM_MODEL_DATA);
			return;
		}
		stack.set(
			DataComponents.CUSTOM_MODEL_DATA,
			new CustomModelData(
				List.of((float) normalizeSelectedType(selectedType).modelDataValue()),
				List.of(),
				List.of(),
				List.of()
			)
		);
	}

	private static List<ItemStack> copyContentsForStorage(NonNullList<ItemStack> contents) {
		List<ItemStack> storedItems = new ArrayList<>(contents.size());
		for (ItemStack stack : contents) {
			storedItems.add(stack == null ? ItemStack.EMPTY : stack.copy());
		}
		return storedItems;
	}

	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}

	/**
	 * 智能节点容器轻量持久化快照。
	 */
	public record Snapshot(
		SmartNodeContainerPlacementType selectedType,
		boolean autoSortEnabled,
		boolean creativeAutoConsumeEnabled,
		int itemCount
	) {
		public Snapshot {
			selectedType = normalizeSelectedType(selectedType);
			itemCount = Math.max(0, itemCount);
		}

		/**
		 * @return 当前是否至少存有一个节点
		 */
		public boolean hasItems() {
			return itemCount > 0;
		}
	}
}
