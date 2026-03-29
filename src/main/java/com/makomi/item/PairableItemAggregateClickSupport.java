package com.makomi.item;

import com.makomi.data.LinkItemData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 可配对物品聚合点击 helper。
 * <p>
 * 仅负责第一层“物品级”聚合交互，不处理双击归并、shift 快移或拖拽分发。
 * </p>
 */
public final class PairableItemAggregateClickSupport {
	private PairableItemAggregateClickSupport() {
	}

	/**
	 * 处理“光标拿着本模组物品并点击其他槽位”的聚合语义。
	 */
	public static boolean overrideStackedOnOther(
		ItemStack carriedStack,
		Slot slot,
		ClickAction action,
		Player player
	) {
		if (carriedStack == null || carriedStack.isEmpty() || slot == null || action == null) {
			return false;
		}
		return switch (action) {
			case PRIMARY -> tryMergeIntoSlot(carriedStack, slot);
			case SECONDARY -> tryPlaceOneFromCarried(carriedStack, slot);
		};
	}

	/**
	 * 处理“槽位里放着本模组物品，被光标点击”的聚合语义。
	 */
	public static boolean overrideOtherStackedOnMe(
		ItemStack slotStack,
		ItemStack carriedStack,
		Slot slot,
		ClickAction action,
		Player player,
		SlotAccess carriedAccess
	) {
		if (slotStack == null || slotStack.isEmpty() || slot == null || action == null || carriedAccess == null) {
			return false;
		}
		if (action == ClickAction.SECONDARY && (carriedStack == null || carriedStack.isEmpty())) {
			return trySplitSlotIntoCursor(slotStack, slot, player, carriedAccess);
		}
		if (carriedStack == null || carriedStack.isEmpty()) {
			return false;
		}
		return switch (action) {
			case PRIMARY -> tryMergeIntoSlotWithCursorAccess(slotStack, carriedStack, slot, carriedAccess);
			case SECONDARY -> tryPlaceOneFromCarriedWithCursorAccess(slotStack, carriedStack, slot, carriedAccess);
		};
	}

	/**
	 * @return 当前物品是否处于聚合态，并因此需要阻断右键使用语义
	 */
	public static boolean blocksDirectUse(ItemStack stack) {
		return LinkItemData.isAggregated(stack);
	}

	/**
	 * 左键同类槽位时，将光标内全部序号并入目标槽位。
	 */
	private static boolean tryMergeIntoSlot(ItemStack carriedStack, Slot slot) {
		ItemStack slotStack = slot.getItem();
		if (slotStack.isEmpty() || !canAggregateTogether(carriedStack, slotStack) || !slot.mayPlace(carriedStack)) {
			return false;
		}
		if (!moveOrderedPrefixIntoStack(slotStack, carriedStack, LinkItemData.getSerialCount(carriedStack))) {
			return false;
		}
		slot.setChanged();
		return true;
	}

	/**
	 * 带光标访问器版本的整组并入，避免服务端光标引用不同步。
	 */
	private static boolean tryMergeIntoSlotWithCursorAccess(
		ItemStack slotStack,
		ItemStack carriedStack,
		Slot slot,
		SlotAccess carriedAccess
	) {
		if (!canAggregateTogether(carriedStack, slotStack) || !slot.mayPlace(carriedStack)) {
			return false;
		}
		if (!moveOrderedPrefixIntoStack(slotStack, carriedStack, LinkItemData.getSerialCount(carriedStack))) {
			return false;
		}
		slot.setChanged();
		carriedAccess.set(carriedStack.isEmpty() ? ItemStack.EMPTY : carriedStack);
		return true;
	}

	/**
	 * 右键时从光标栈弹出顶部一个序号，放入空槽或同类槽位。
	 */
	private static boolean tryPlaceOneFromCarried(ItemStack carriedStack, Slot slot) {
		ItemStack slotStack = slot.getItem();
		if (slotStack.isEmpty()) {
			if (LinkItemData.getSerialCount(carriedStack) <= 1 || !slot.mayPlace(carriedStack)) {
				return false;
			}
			ItemStack template = carriedStack.copyWithCount(1);
			long movedSerial = consumeTopSerial(carriedStack);
			if (movedSerial <= 0L) {
				return false;
			}
			ItemStack placed = LinkItemData.copySingleSerialStack(template, movedSerial);
			if (placed.isEmpty()) {
				return false;
			}
			slot.set(placed);
			slot.setChanged();
			return true;
		}

		if (!canAggregateTogether(carriedStack, slotStack) || !slot.mayPlace(carriedStack)) {
			return false;
		}
		if (!moveOrderedPrefixIntoStack(slotStack, carriedStack, 1)) {
			return false;
		}
		slot.setChanged();
		return true;
	}

	/**
	 * 带光标访问器版本的“放一个”。
	 */
	private static boolean tryPlaceOneFromCarriedWithCursorAccess(
		ItemStack slotStack,
		ItemStack carriedStack,
		Slot slot,
		SlotAccess carriedAccess
	) {
		if (!canAggregateTogether(carriedStack, slotStack) || !slot.mayPlace(carriedStack)) {
			return false;
		}
		if (!moveOrderedPrefixIntoStack(slotStack, carriedStack, 1)) {
			return false;
		}
		slot.setChanged();
		carriedAccess.set(carriedStack.isEmpty() ? ItemStack.EMPTY : carriedStack);
		return true;
	}

	/**
	 * 右键空手点聚合槽位时，按有序中点拆出后半段到光标。
	 */
	private static boolean trySplitSlotIntoCursor(
		ItemStack slotStack,
		Slot slot,
		Player player,
		SlotAccess carriedAccess
	) {
		if (player == null || !slot.mayPickup(player) || LinkItemData.getSerialCount(slotStack) <= 1) {
			return false;
		}

		ItemStack template = slotStack.copyWithCount(1);
		List<Long> upperHalf = LinkItemData.splitUpperHalf(slotStack);
		if (upperHalf.isEmpty()) {
			return false;
		}
		ItemStack carriedStack = template.copyWithCount(1);
		LinkItemData.setSerialGroup(carriedStack, upperHalf);
		slot.setChanged();
		carriedAccess.set(carriedStack);
		return true;
	}

	/**
	 * 判断两件物品是否允许聚合。
	 * <p>
	 * 第一层仅要求物品类型一致；序号、链接快照等动态字段在聚合时统一重算。
	 * </p>
	 */
	private static boolean canAggregateTogether(ItemStack left, ItemStack right) {
		if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
			return false;
		}
		if (!ItemStack.isSameItem(left, right)) {
			return false;
		}
		return LinkItemData.getSerial(left) > 0L && LinkItemData.getSerial(right) > 0L;
	}

	/**
	 * 从光标栈消费顶部一个序号。
	 */
	private static long consumeTopSerial(ItemStack stack) {
		int serialCount = LinkItemData.getSerialCount(stack);
		long topSerial = LinkItemData.getSerial(stack);
		if (serialCount <= 0 || topSerial <= 0L) {
			return 0L;
		}
		if (serialCount == 1) {
			stack.shrink(1);
			return topSerial;
		}
		return LinkItemData.removeTopSerial(stack);
	}

	/**
	 * 将来源栈顶部若干序号按序并入目标聚合栈，并遵守聚合上限。
	 * <p>
	 * 当目标剩余容量不足时，只移动来源栈前缀部分，余项继续保留在来源栈中。
	 * </p>
	 */
	private static boolean moveOrderedPrefixIntoStack(
		ItemStack targetStack,
		ItemStack sourceStack,
		int requestedSerialCount
	) {
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
}
