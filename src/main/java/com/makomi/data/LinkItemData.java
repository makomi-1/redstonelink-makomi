package com.makomi.data;

import com.makomi.item.PairableItem;
import com.makomi.util.SignalStrengths;
import com.makomi.util.SerialNbtCodecUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.CustomData;

/**
 * 物品 NBT 元数据读写工具。
 * <p>
 * 负责管理可配对物品上的序列号、配对目标、链接集合与退役标记，
 * 供放置流程、交互流程与节点回收流程统一使用。
 * </p>
 */
public final class LinkItemData {
	/**
	 * 聚合栈最大承载序号数量。
	 * <p>
	 * 该上限只约束本模组自定义的“聚合序号组”视图，不改变原版真实
	 * `ItemStack#getCount()` 的单件语义。
	 * </p>
	 */
	public static final int AGGREGATE_STACK_LIMIT = 64;

	private static final String KEY_SERIAL = "rl_serial";
	private static final String KEY_SERIAL_GROUP = "rl_serial_group";
	private static final String KEY_DISPLAY_ALIAS = "rl_display_alias";
	private static final String KEY_PAIR = "rl_pair";
	private static final String KEY_LINKS = "rl_links";
	private static final String KEY_LINK_DISPLAY_TEXTS = "rl_link_display_texts";
	private static final String KEY_CHANNEL = "rl_channel";
	private static final String KEY_DESTROY_RETIRE = "rl_destroy_retire";
	private static final String KEY_SYNC_LINKER_SIGNAL = "rl_sync_linker_signal";
	private static final int SYNC_LINKER_SIGNAL_OFF = 0;
	private static final int SYNC_LINKER_MODEL_ACTIVE = 1;

	private LinkItemData() {
	}

	/**
	 * 确保物品拥有可用序列号。
	 * <p>
	 * 若序列号不存在则分配新号；若序列号已退役则重分配；
	 * 若序列号存在但未登记分配集合，则补登记。
	 * </p>
	 */
	public static long ensureSerial(ItemStack stack, ServerLevel level, LinkNodeType type) {
		List<Long> serialGroup = getSerialGroup(stack);
		if (serialGroup.size() > 1) {
			setDestroyRetireCandidate(stack, true);
			syncDisplayAliasIfSingle(stack, level);
			return serialGroup.get(0);
		}

		LinkSavedData savedData = LinkSavedData.get(level);
		long serial = getSerial(stack);
		if (serial > 0L) {
			if (savedData.isSerialRetired(type, serial)) {
				long reallocated = savedData.allocateSerial(type);
				setSerial(stack, reallocated);
				// 统一标记为“销毁可退役候选”，覆盖创造栏直接取出的物品场景。
				setDestroyRetireCandidate(stack, true);
				syncDisplayAliasIfSingle(stack, level);
				return reallocated;
			}
			if (!savedData.isSerialAllocated(type, serial)) {
				savedData.markSerialAllocated(type, serial);
			}
			// 统一标记为“销毁可退役候选”，覆盖创造栏直接取出的物品场景。
			setDestroyRetireCandidate(stack, true);
			syncDisplayAliasIfSingle(stack, level);
			return serial;
		}

		long allocated = savedData.allocateSerial(type);
		setSerial(stack, allocated);
		// 新分配序号后同步写入销毁退役候选标记，保证后续丢弃/销毁路径可识别。
		setDestroyRetireCandidate(stack, true);
		syncDisplayAliasIfSingle(stack, level);
		return allocated;
	}

	/**
	 * 解析放置时最终应使用的序列号。
	 * <p>
	 * 会结合物品当前序列号、世界维度与方块坐标进行冲突规避。
	 * </p>
	 */
	public static long resolvePlacementSerial(
		ItemStack stack,
		ServerLevel level,
		LinkNodeType type,
		BlockPos pos
	) {
		long preferredSerial = ensureSerial(stack, level, type);
		return LinkSavedData.get(level).resolvePlacementSerial(type, preferredSerial, level.dimension(), pos);
	}

	/**
	 * 读取物品序列号。
	 */
	public static long getSerial(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		List<Long> serialGroup = readSerialGroup(tag);
		if (!serialGroup.isEmpty()) {
			return serialGroup.get(0);
		}
		return readStoredSerial(tag);
	}

	/**
	 * 读取物品聚合序号组。
	 * <p>
	 * 若未进入聚合态，则回退为单序号视图，保持旧逻辑兼容。
	 * </p>
	 */
	public static List<Long> getSerialGroup(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		List<Long> serialGroup = readSerialGroup(tag);
		if (!serialGroup.isEmpty()) {
			return serialGroup;
		}
		long serial = readStoredSerial(tag);
		if (serial > 0L) {
			return List.of(serial);
		}
		return List.of();
	}

	/**
	 * @return 当前物品承载的序号数量
	 */
	public static int getSerialCount(ItemStack stack) {
		return getSerialGroup(stack).size();
	}

	/**
	 * @return 当前聚合栈剩余可并入容量
	 */
	public static int getRemainingAggregateCapacity(ItemStack stack) {
		return Math.max(0, AGGREGATE_STACK_LIMIT - getSerialCount(stack));
	}

	/**
	 * @return 当前物品是否处于聚合态
	 */
	public static boolean isAggregated(ItemStack stack) {
		return getSerialCount(stack) > 1;
	}

	/**
	 * 判断聚合视图中是否包含指定序号。
	 */
	public static boolean containsSerial(ItemStack stack, long serial) {
		if (serial <= 0L) {
			return false;
		}
		return getSerialGroup(stack).contains(serial);
	}

	/**
	 * 写入有序聚合序号组。
	 * <p>
	 * 该方法统一维护“升序、去重、顶部最小序号”约束，并在聚合变更后清理
	 * 与顶序号耦合的链接快照字段，避免 tooltip 继续展示旧顶序号缓存。
	 * </p>
	 */
	public static void setSerialGroup(ItemStack stack, Collection<Long> serials) {
		List<Long> normalized = clampToAggregateStackLimit(normalizePositiveSerials(serials));
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> applySerialGroup(tag, normalized));
		if (normalized.size() != 1) {
			clearSyncLinkerSignalState(stack);
		}
	}

	/**
	 * 移除并返回顶部序号（即最小序号）。
	 *
	 * @return 被移除的顶部序号；若当前无可移除序号则返回 0
	 */
	public static long removeTopSerial(ItemStack stack) {
		List<Long> serialGroup = getSerialGroup(stack);
		if (serialGroup.size() <= 1) {
			return 0L;
		}
		long removed = serialGroup.get(0);
		setSerialGroup(stack, serialGroup.subList(1, serialGroup.size()));
		return removed;
	}

	/**
	 * 从当前聚合栈中按有序中点拆出后半段。
	 * <p>
	 * 拆分后当前栈保留较小序号一侧，返回值为较大序号一侧，
	 * 以保持“顶部最小序号”规则稳定。
	 * </p>
	 */
	public static List<Long> splitUpperHalf(ItemStack stack) {
		List<Long> serialGroup = getSerialGroup(stack);
		if (serialGroup.size() <= 1) {
			return List.of();
		}
		int keepCount = serialGroup.size() / 2;
		List<Long> lowerHalf = List.copyOf(serialGroup.subList(0, keepCount));
		List<Long> upperHalf = List.copyOf(serialGroup.subList(keepCount, serialGroup.size()));
		setSerialGroup(stack, lowerHalf);
		return upperHalf;
	}

	/**
	 * 基于模板复制出仅承载单个序号的新物品栈。
	 */
	public static ItemStack copySingleSerialStack(ItemStack template, long serial) {
		if (template == null || template.isEmpty() || serial <= 0L) {
			return ItemStack.EMPTY;
		}
		ItemStack copy = template.copyWithCount(1);
		setSerialGroup(copy, List.of(serial));
		return copy;
	}

	/**
	 * 写入物品序列号。
	 *
	 * @param serial 大于 0 时写入，小于等于 0 时移除字段
	 */
	public static void setSerial(ItemStack stack, long serial) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (serial > 0L) {
				tag.putLong(KEY_SERIAL, serial);
			} else {
				tag.remove(KEY_SERIAL);
			}
			tag.remove(KEY_SERIAL_GROUP);
			tag.remove(KEY_DISPLAY_ALIAS);
			tag.remove(KEY_LINK_DISPLAY_TEXTS);
			tag.remove(KEY_CHANNEL);
		});
	}

	/**
	 * 读取物品缓存的展示别名。
	 */
	public static String getDisplayAlias(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_DISPLAY_ALIAS)) {
			return "";
		}
		return NodeAliasDisplayUtil.normalizeAlias(tag.getStringOr(KEY_DISPLAY_ALIAS, ""));
	}

	/**
	 * 写入物品缓存的展示别名。
	 */
	public static void setDisplayAlias(ItemStack stack, String alias) {
		String normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(alias);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (normalizedAlias.isEmpty()) {
				tag.remove(KEY_DISPLAY_ALIAS);
			} else {
				tag.putString(KEY_DISPLAY_ALIAS, normalizedAlias);
			}
		});
	}

	/**
	 * 读取配对核心序列号。
	 */
	public static long getPairSerial(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (tag.contains(KEY_PAIR)) {
			return tag.getLongOr(KEY_PAIR, 0L);
		}
		return 0L;
	}

	/**
	 * 写入配对核心序列号。
	 *
	 * @param pairSerial 大于 0 时写入，小于等于 0 时移除字段
	 */
	public static void setPairSerial(ItemStack stack, long pairSerial) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (pairSerial > 0L) {
				tag.putLong(KEY_PAIR, pairSerial);
			} else {
				tag.remove(KEY_PAIR);
			}
		});
	}

	/**
	 * 读取触发源关联的目标核心序列号列表。
	 */
	public static List<Long> getLinkedSerials(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_LINKS)) {
			return List.of();
		}

		long[] values = tag.getLongArray(KEY_LINKS).orElse(new long[0]);
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

	/**
	 * 读取当前连接目标展示文本快照。
	 * <p>
	 * 当旧栈未携带展示文本字段，或字段数量与序号数量不一致时，统一回退为 `#序号`。
	 * </p>
	 */
	public static List<String> getLinkedDisplayTexts(ItemStack stack) {
		List<Long> linkedSerials = getLinkedSerials(stack);
		if (linkedSerials.isEmpty()) {
			return List.of();
		}
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_LINK_DISPLAY_TEXTS)) {
			return NodeAliasDisplayUtil.normalizeDisplayTexts(linkedSerials, List.of());
		}
		ListTag listTag = tag.getListOrEmpty(KEY_LINK_DISPLAY_TEXTS);
		List<String> displayTexts = new ArrayList<>(listTag.size());
		for (int index = 0; index < listTag.size(); index++) {
			displayTexts.add(listTag.getStringOr(index, ""));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(linkedSerials, displayTexts);
	}

	/**
	 * 覆盖写入关联目标核心序列号集合。
	 */
	public static void setLinkedSerials(ItemStack stack, Set<Long> linkedSerials) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (linkedSerials == null || linkedSerials.isEmpty()) {
				tag.remove(KEY_LINKS);
				tag.remove(KEY_LINK_DISPLAY_TEXTS);
				return;
			}

			long[] values = linkedSerials.stream()
				.filter(value -> value != null && value > 0L)
				.mapToLong(Long::longValue)
				.sorted()
				.toArray();
			if (values.length == 0) {
				tag.remove(KEY_LINKS);
				tag.remove(KEY_LINK_DISPLAY_TEXTS);
			} else {
				tag.putLongArray(KEY_LINKS, values);
				tag.remove(KEY_LINK_DISPLAY_TEXTS);
			}
		});
	}

	/**
	 * 覆盖写入当前连接目标快照（序号 + 展示文本）。
	 */
	public static void setLinkedSnapshot(
		ItemStack stack,
		java.util.Collection<Long> linkedSerials,
		java.util.Collection<String> linkedDisplayTexts
	) {
		List<Long> normalizedSerials = normalizePositiveSerials(linkedSerials);
		List<String> normalizedDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(normalizedSerials, linkedDisplayTexts);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (normalizedSerials.isEmpty()) {
				tag.remove(KEY_LINKS);
				tag.remove(KEY_LINK_DISPLAY_TEXTS);
				return;
			}

			tag.putLongArray(KEY_LINKS, SerialNbtCodecUtil.toSortedLongArray(normalizedSerials));
			ListTag displayTextTag = new ListTag();
			for (String displayText : normalizedDisplayTexts) {
				displayTextTag.add(StringTag.valueOf(displayText));
			}
			if (displayTextTag.isEmpty()) {
				tag.remove(KEY_LINK_DISPLAY_TEXTS);
			} else {
				tag.put(KEY_LINK_DISPLAY_TEXTS, displayTextTag);
			}
		});
	}

	/**
	 * 读取物品缓存的频道快照。
	 */
	public static long getChannel(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_CHANNEL)) {
			return 0L;
		}
		return Math.max(0L, tag.getLongOr(KEY_CHANNEL, 0L));
	}

	/**
	 * 写入物品缓存的频道快照。
	 *
	 * @param channel 大于 0 时写入，小于等于 0 时移除字段
	 */
	public static void setChannel(ItemStack stack, long channel) {
		long normalizedChannel = Math.max(0L, channel);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (normalizedChannel > 0L) {
				tag.putLong(KEY_CHANNEL, normalizedChannel);
			} else {
				tag.remove(KEY_CHANNEL);
			}
		});
	}

	/**
	 * 为单件物品同步当前连接、别名与频道快照。
	 * <p>
	 * 仅当物品当前承载单个序号时才执行，避免把聚合态误解释为“顶部单件”的
	 * 当前连接视图。
	 * </p>
	 */
	public static void syncCurrentLinksSnapshotIfSingle(ItemStack stack, ServerLevel level) {
		if (stack == null || stack.isEmpty() || level == null || getSerialCount(stack) != 1) {
			return;
		}
		Optional<LinkNodeType> nodeType = getNodeType(stack);
		if (nodeType.isEmpty()) {
			return;
		}
		long serial = getSerial(stack);
		if (serial <= 0L) {
			return;
		}
		NodeLinksSnapshot linksSnapshot = NodeSnapshotQueryService.queryItemSnapshotLinks(level, nodeType.get(), serial);
		setLinkedSnapshot(stack, linksSnapshot.visibleTargets(), linksSnapshot.visibleTargetDisplayTexts());
		syncDisplayAliasIfSingle(stack, level);
		syncChannelIfSingle(stack, level);
	}

	/**
	 * 为单件物品同步当前节点别名缓存。
	 */
	public static void syncDisplayAliasIfSingle(ItemStack stack, ServerLevel level) {
		if (stack == null || stack.isEmpty() || level == null || getSerialCount(stack) != 1) {
			setDisplayAlias(stack, "");
			return;
		}
		Optional<LinkNodeType> nodeType = getNodeType(stack);
		if (nodeType.isEmpty()) {
			setDisplayAlias(stack, "");
			return;
		}
		long serial = getSerial(stack);
		if (serial <= 0L) {
			setDisplayAlias(stack, "");
			return;
		}
		setDisplayAlias(stack, NodeAliasServerSupport.resolveAlias(level, nodeType.get(), serial).orElse(""));
	}

	/**
	 * 为单件物品同步当前节点频道快照。
	 */
	public static void syncChannelIfSingle(ItemStack stack, ServerLevel level) {
		if (stack == null || stack.isEmpty() || level == null || getSerialCount(stack) != 1) {
			setChannel(stack, 0L);
			return;
		}
		Optional<LinkNodeType> nodeType = getNodeType(stack);
		if (nodeType.isEmpty()) {
			setChannel(stack, 0L);
			return;
		}
		long serial = getSerial(stack);
		if (serial <= 0L) {
			setChannel(stack, 0L);
			return;
		}
		setChannel(stack, LinkSavedData.get(level).getChannel(nodeType.get(), serial));
	}

	/**
	 * 设置“销毁即退役”标记。
	 */
	public static void setDestroyRetireCandidate(ItemStack stack, boolean retireCandidate) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (retireCandidate) {
				tag.putBoolean(KEY_DESTROY_RETIRE, true);
			} else {
				tag.remove(KEY_DESTROY_RETIRE);
			}
		});
	}

	/**
	 * 判断物品是否携带“销毁即退役”标记。
	 */
	public static boolean isDestroyRetireCandidate(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return tag.getBooleanOr(KEY_DESTROY_RETIRE, false);
	}

	/**
	 * 解析物品对应的节点类型。
	 */
	public static Optional<LinkNodeType> getNodeType(ItemStack stack) {
		if (stack.getItem() instanceof PairableItem pairableItem) {
			return Optional.of(pairableItem.getNodeType());
		}
		return Optional.empty();
	}

	/**
	 * 读取同步遥控器当前缓存的同步强度。
	 * <p>
	 * 返回值始终会被归一到原版红石有效范围 `0~15`。
	 * </p>
	 */
	public static int getSyncLinkerSignalStrength(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_SYNC_LINKER_SIGNAL)) {
			return SYNC_LINKER_SIGNAL_OFF;
		}
		return normalizeSyncLinkerSignalStrength(tag.getIntOr(KEY_SYNC_LINKER_SIGNAL, 0));
	}

	/**
	 * 写入同步遥控器当前同步强度，并同步镜像物品模型状态。
	 */
	public static void setSyncLinkerSignalStrength(ItemStack stack, int signalStrength) {
		int normalizedStrength = normalizeSyncLinkerSignalStrength(signalStrength);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (normalizedStrength > SYNC_LINKER_SIGNAL_OFF) {
				tag.putInt(KEY_SYNC_LINKER_SIGNAL, normalizedStrength);
			} else {
				tag.remove(KEY_SYNC_LINKER_SIGNAL);
			}
		});
		syncSyncLinkerModelState(stack);
	}

	/**
	 * 依据当前持久化状态补齐同步遥控器渲染态。
	 * <p>
	 * 该方法用于兼容旧栈或缺失镜像组件的场景，保证物品栏贴图与持久化状态一致。
	 * </p>
	 */
	public static void syncSyncLinkerModelState(ItemStack stack) {
		if (stack == null) {
			return;
		}
		if (stack.isEmpty() || getSerialCount(stack) != 1) {
			stack.remove(DataComponents.CUSTOM_MODEL_DATA);
			return;
		}
		if (getSyncLinkerSignalStrength(stack) > SYNC_LINKER_SIGNAL_OFF) {
			stack.set(
				DataComponents.CUSTOM_MODEL_DATA,
				new CustomModelData(List.of((float) SYNC_LINKER_MODEL_ACTIVE), List.of(), List.of(), List.of())
			);
			return;
		}
		stack.remove(DataComponents.CUSTOM_MODEL_DATA);
	}

	/**
	 * 清空同步遥控器状态与模型镜像。
	 */
	public static void clearSyncLinkerSignalState(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(KEY_SYNC_LINKER_SIGNAL));
		stack.remove(DataComponents.CUSTOM_MODEL_DATA);
	}

	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}

	/**
	 * 读取底层单序号字段，不做聚合回退。
	 */
	private static long readStoredSerial(CompoundTag tag) {
		if (tag.contains(KEY_SERIAL)) {
			return tag.getLongOr(KEY_SERIAL, 0L);
		}
		return 0L;
	}

	/**
	 * 读取并规范化聚合序号组。
	 */
	private static List<Long> readSerialGroup(CompoundTag tag) {
		if (!tag.contains(KEY_SERIAL_GROUP)) {
			return List.of();
		}
		long[] values = tag.getLongArray(KEY_SERIAL_GROUP).orElse(new long[0]);
		if (values.length == 0) {
			return List.of();
		}
		List<Long> serials = new ArrayList<>(values.length);
		for (long value : values) {
			if (value > 0L) {
				serials.add(value);
			}
		}
		return normalizePositiveSerials(serials);
	}

	/**
	 * 将聚合序号组回写到标签并同步镜像字段。
	 */
	private static void applySerialGroup(CompoundTag tag, List<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			tag.remove(KEY_SERIAL);
			tag.remove(KEY_SERIAL_GROUP);
			tag.remove(KEY_DISPLAY_ALIAS);
			tag.remove(KEY_PAIR);
			tag.remove(KEY_LINKS);
			tag.remove(KEY_LINK_DISPLAY_TEXTS);
			tag.remove(KEY_CHANNEL);
			return;
		}

		tag.putLong(KEY_SERIAL, serials.get(0));
		if (serials.size() > 1) {
			tag.putLongArray(KEY_SERIAL_GROUP, SerialNbtCodecUtil.toSortedLongArray(serials));
		} else {
			tag.remove(KEY_SERIAL_GROUP);
		}
		tag.remove(KEY_DISPLAY_ALIAS);
		tag.remove(KEY_PAIR);
		tag.remove(KEY_LINKS);
		tag.remove(KEY_LINK_DISPLAY_TEXTS);
		tag.remove(KEY_CHANNEL);
	}

	/**
	 * 过滤非正数、去重并按升序输出。
	 */
	private static List<Long> normalizePositiveSerials(Collection<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		return serials.stream()
			.filter(value -> value != null && value > 0L)
			.distinct()
			.sorted()
			.toList();
	}

	/**
	 * 将有序序号组裁剪到聚合上限内。
	 */
	private static List<Long> clampToAggregateStackLimit(List<Long> serials) {
		if (serials == null || serials.isEmpty() || serials.size() <= AGGREGATE_STACK_LIMIT) {
			return serials == null ? List.of() : serials;
		}
		return List.copyOf(serials.subList(0, AGGREGATE_STACK_LIMIT));
	}

	/**
	 * 统一将同步遥控器强度约束到原版红石有效范围。
	 */
	private static int normalizeSyncLinkerSignalStrength(int signalStrength) {
		return SignalStrengths.clamp(signalStrength);
	}
}
