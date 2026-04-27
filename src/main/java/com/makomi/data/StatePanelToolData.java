package com.makomi.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 状态面板工具数据读写工具。
 * <p>
 * 统一维护订阅项（type + serial）的持久化、去重与排序规则。
 * </p>
 */
public final class StatePanelToolData {
	private static final String KEY_SUBSCRIPTIONS = "rl_state_panel_subscriptions";
	private static final String KEY_TYPE = "type";
	private static final String KEY_SERIAL = "serial";
	private static final Comparator<SubscriptionEntry> SUBSCRIPTION_ORDER = Comparator
		.comparing((SubscriptionEntry entry) -> LinkNodeSemantics.toSemanticName(entry.nodeType()))
		.thenComparingLong(SubscriptionEntry::serial);

	private StatePanelToolData() {
	}

	/**
	 * 读取当前订阅列表。
	 */
	public static List<SubscriptionEntry> readSubscriptions(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_SUBSCRIPTIONS)) {
			return List.of();
		}
		ListTag listTag = tag.getListOrEmpty(KEY_SUBSCRIPTIONS);
		if (listTag.isEmpty()) {
			return List.of();
		}

		List<SubscriptionEntry> values = new ArrayList<>(listTag.size());
		for (Tag entryTag : listTag) {
			if (!(entryTag instanceof CompoundTag entryCompound)) {
				continue;
			}
			LinkNodeType nodeType = LinkNodeSemantics.tryParseCanonicalType(entryCompound.getStringOr(KEY_TYPE, ""))
				.orElse(null);
			long serial = entryCompound.getLongOr(KEY_SERIAL, 0L);
			if (nodeType == null || serial <= 0L) {
				continue;
			}
			values.add(new SubscriptionEntry(nodeType, serial));
		}
		return normalizeSubscriptions(values);
	}

	/**
	 * 覆盖写入订阅列表。
	 */
	public static void writeSubscriptions(ItemStack stack, Collection<SubscriptionEntry> subscriptions) {
		List<SubscriptionEntry> normalized = normalizeSubscriptions(subscriptions);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (normalized.isEmpty()) {
				tag.remove(KEY_SUBSCRIPTIONS);
				return;
			}
			ListTag listTag = new ListTag();
			for (SubscriptionEntry entry : normalized) {
				CompoundTag entryTag = new CompoundTag();
				entryTag.putString(KEY_TYPE, LinkNodeSemantics.toSemanticName(entry.nodeType()));
				entryTag.putLong(KEY_SERIAL, entry.serial());
				listTag.add(entryTag);
			}
			tag.put(KEY_SUBSCRIPTIONS, listTag);
		});
	}

	/**
	 * 合并“同一类型 + 多序号”到现有订阅列表。
	 */
	public static List<SubscriptionEntry> mergeSubscriptions(
		Collection<SubscriptionEntry> current,
		LinkNodeType nodeType,
		Collection<Long> serials
	) {
		List<SubscriptionEntry> merged = new ArrayList<>();
		if (current != null && !current.isEmpty()) {
			merged.addAll(current);
		}
		if (nodeType != null && serials != null && !serials.isEmpty()) {
			for (Long serial : serials) {
				if (serial != null && serial > 0L) {
					merged.add(new SubscriptionEntry(nodeType, serial));
				}
			}
		}
		return normalizeSubscriptions(merged);
	}

	/**
	 * 删除单条订阅。
	 */
	public static boolean removeSubscription(ItemStack stack, LinkNodeType nodeType, long serial) {
		if (nodeType == null || serial <= 0L) {
			return false;
		}
		List<SubscriptionEntry> current = readSubscriptions(stack);
		if (current.isEmpty()) {
			return false;
		}
		List<SubscriptionEntry> next = new ArrayList<>(current.size());
		for (SubscriptionEntry entry : current) {
			if (entry.nodeType() == nodeType && entry.serial() == serial) {
				continue;
			}
			next.add(entry);
		}
		if (next.size() == current.size()) {
			return false;
		}
		writeSubscriptions(stack, next);
		return true;
	}

	/**
	 * @return 当前订阅条目数
	 */
	public static int subscriptionCount(ItemStack stack) {
		return readSubscriptions(stack).size();
	}

	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}

	private static List<SubscriptionEntry> normalizeSubscriptions(Collection<SubscriptionEntry> subscriptions) {
		if (subscriptions == null || subscriptions.isEmpty()) {
			return List.of();
		}
		List<SubscriptionEntry> normalized = new ArrayList<>();
		for (SubscriptionEntry entry : subscriptions) {
			if (entry == null || entry.serial() <= 0L) {
				continue;
			}
			normalized.add(new SubscriptionEntry(entry.nodeType(), entry.serial()));
		}
		if (normalized.isEmpty()) {
			return List.of();
		}
		normalized = normalized.stream().sorted(SUBSCRIPTION_ORDER).distinct().toList();
		return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
	}

	/**
	 * 单条订阅项。
	 */
	public record SubscriptionEntry(LinkNodeType nodeType, long serial) {
		public SubscriptionEntry {
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
		}
	}
}