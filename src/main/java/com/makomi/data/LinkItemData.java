package com.makomi.data;

import com.makomi.item.PairableItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class LinkItemData {
	private static final String KEY_SERIAL = "rl_serial";
	private static final String KEY_PAIR = "rl_pair";
	private static final String KEY_LINKS = "rl_links";
	private static final String KEY_DESTROY_RETIRE = "rl_destroy_retire";

	private LinkItemData() {
	}

	public static long ensureSerial(ItemStack stack, ServerLevel level, LinkNodeType type) {
		LinkSavedData savedData = LinkSavedData.get(level);
		long serial = getSerial(stack);
		if (serial > 0L) {
			if (savedData.isSerialRetired(type, serial)) {
				long reallocated = savedData.allocateSerial(type);
				setSerial(stack, reallocated);
				return reallocated;
			}
			if (!savedData.isSerialAllocated(type, serial)) {
				savedData.markSerialAllocated(type, serial);
			}
			return serial;
		}

		long allocated = savedData.allocateSerial(type);
		setSerial(stack, allocated);
		return allocated;
	}

	public static long resolvePlacementSerial(
		ItemStack stack,
		ServerLevel level,
		LinkNodeType type,
		BlockPos pos
	) {
		long preferredSerial = ensureSerial(stack, level, type);
		return LinkSavedData.get(level).resolvePlacementSerial(type, preferredSerial, level.dimension(), pos);
	}

	public static long getSerial(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (tag.contains(KEY_SERIAL, Tag.TAG_LONG)) {
			return tag.getLong(KEY_SERIAL);
		}
		return 0L;
	}

	public static void setSerial(ItemStack stack, long serial) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (serial > 0L) {
				tag.putLong(KEY_SERIAL, serial);
			} else {
				tag.remove(KEY_SERIAL);
			}
		});
	}

	public static long getPairSerial(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (tag.contains(KEY_PAIR, Tag.TAG_LONG)) {
			return tag.getLong(KEY_PAIR);
		}
		return 0L;
	}

	public static void setPairSerial(ItemStack stack, long pairSerial) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (pairSerial > 0L) {
				tag.putLong(KEY_PAIR, pairSerial);
			} else {
				tag.remove(KEY_PAIR);
			}
		});
	}

	public static List<Long> getLinkedSerials(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_LINKS, Tag.TAG_LONG_ARRAY)) {
			return List.of();
		}

		long[] values = tag.getLongArray(KEY_LINKS);
		if (values.length == 0) {
			return List.of();
		}

		List<Long> result = new ArrayList<>(values.length);
		for (long value : values) {
			if (value > 0L) {
				result.add(value);
			}
		}
		return List.copyOf(result);
	}

	public static void setLinkedSerials(ItemStack stack, Set<Long> linkedSerials) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (linkedSerials == null || linkedSerials.isEmpty()) {
				tag.remove(KEY_LINKS);
				return;
			}

			long[] values = linkedSerials.stream()
				.filter(value -> value != null && value > 0L)
				.mapToLong(Long::longValue)
				.sorted()
				.toArray();
			if (values.length == 0) {
				tag.remove(KEY_LINKS);
			} else {
				tag.putLongArray(KEY_LINKS, values);
			}
		});
	}

	public static void setDestroyRetireCandidate(ItemStack stack, boolean retireCandidate) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (retireCandidate) {
				tag.putBoolean(KEY_DESTROY_RETIRE, true);
			} else {
				tag.remove(KEY_DESTROY_RETIRE);
			}
		});
	}

	public static boolean isDestroyRetireCandidate(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return tag.getBoolean(KEY_DESTROY_RETIRE);
	}

	public static Optional<LinkNodeType> getNodeType(ItemStack stack) {
		if (stack.getItem() instanceof PairableItem pairableItem) {
			return Optional.of(pairableItem.getNodeType());
		}
		return Optional.empty();
	}

	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}
}
