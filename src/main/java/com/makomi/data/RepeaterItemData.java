package com.makomi.data;

import com.makomi.util.DisplayTextListFormatUtil;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 转发器物品配置读写工具。
 */
public final class RepeaterItemData {
	private static final String KEY_INPUT_SERIAL_EXPRESSION = "rl_repeater_input_serial_expression";
	private static final String KEY_OUTPUT_SERIAL_EXPRESSION = "rl_repeater_output_serial_expression";
	private static final String KEY_DELAY = "rl_repeater_delay";
	private static final String KEY_INPUT_DISPLAY_TEXTS = "rl_repeater_input_display_texts";
	private static final String KEY_OUTPUT_DISPLAY_TEXTS = "rl_repeater_output_display_texts";

	private RepeaterItemData() {
	}

	/**
	 * 读取转发器物品配置。
	 */
	public static RepeaterConfigSnapshot read(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return new RepeaterConfigSnapshot(
			readString(tag, KEY_INPUT_SERIAL_EXPRESSION),
			readString(tag, KEY_OUTPUT_SERIAL_EXPRESSION),
			RepeaterDelay.fromToken(readString(tag, KEY_DELAY))
		);
	}

	/**
	 * 写入转发器物品配置。
	 */
	public static void write(ItemStack stack, RepeaterConfigSnapshot snapshot) {
		RepeaterConfigSnapshot normalized = snapshot == null ? RepeaterConfigSnapshot.empty() : snapshot;
		CustomData.update(
			DataComponents.CUSTOM_DATA,
			stack,
			tag -> {
				writeStringOrRemove(tag, KEY_INPUT_SERIAL_EXPRESSION, normalized.inputSerialExpression());
				writeStringOrRemove(tag, KEY_OUTPUT_SERIAL_EXPRESSION, normalized.outputSerialExpression());
				tag.putString(KEY_DELAY, normalized.delay().token());
				tag.remove(KEY_INPUT_DISPLAY_TEXTS);
				tag.remove(KEY_OUTPUT_DISPLAY_TEXTS);
			}
		);
	}

	/**
	 * 确保转发器物品拥有合法统一序号。
	 */
	public static long ensureSerial(ItemStack stack, ServerLevel level) {
		if (stack == null || stack.isEmpty() || level == null) {
			return 0L;
		}
		LinkSavedData savedData = LinkSavedData.get(level);
		RepeaterConfigSnapshot currentSnapshot = read(stack);
		long serial = LinkItemData.getSerial(stack);
		if (
			serial <= 0L
				|| savedData.isSerialRetired(LinkNodeType.CORE, serial)
				|| savedData.isSerialRetired(LinkNodeType.TRIGGER_SOURCE, serial)
		) {
			long allocated = savedData.allocateRepeaterSerial();
			LinkItemData.setSerial(stack, allocated);
			LinkItemData.setDestroyRetireCandidate(stack, true);
			write(stack, clearConnectionSnapshotPreservingDelay(currentSnapshot));
			LinkItemData.setDisplayAlias(stack, RepeaterGraphSnapshotSupport.resolveAlias(level, allocated, LinkItemData.getDisplayAlias(stack)));
			return allocated;
		}
		if (!savedData.isSerialAllocated(LinkNodeType.CORE, serial)) {
			savedData.markSerialAllocated(LinkNodeType.CORE, serial);
		}
		if (!savedData.isSerialAllocated(LinkNodeType.TRIGGER_SOURCE, serial)) {
			savedData.markSerialAllocated(LinkNodeType.TRIGGER_SOURCE, serial);
		}
		if (!savedData.isRepeaterSerial(serial)) {
			savedData.markRepeaterSerial(serial);
		}
		LinkItemData.setDestroyRetireCandidate(stack, true);
		LinkItemData.setDisplayAlias(stack, RepeaterGraphSnapshotSupport.resolveAlias(level, serial, LinkItemData.getDisplayAlias(stack)));
		return serial;
	}

	/**
	 * 清空转发器物品中的连接关系快照，但保留自身延迟配置。
	 */
	static RepeaterConfigSnapshot clearConnectionSnapshotPreservingDelay(RepeaterConfigSnapshot snapshot) {
		RepeaterConfigSnapshot normalized = snapshot == null ? RepeaterConfigSnapshot.empty() : snapshot;
		return new RepeaterConfigSnapshot("", "", normalized.delay());
	}

	/**
	 * 解析转发器放置时最终应使用的统一序号。
	 */
	public static long resolvePlacementSerial(ItemStack stack, ServerLevel level, net.minecraft.core.BlockPos pos) {
		if (stack == null || stack.isEmpty() || level == null || pos == null) {
			return 0L;
		}
		long preferredSerial = ensureSerial(stack, level);
		return LinkSavedData.get(level).resolveRepeaterPlacementSerial(preferredSerial, level.dimension(), pos);
	}

	/**
	 * 读取输入侧展示文本缓存。
	 */
	public static List<String> getInputDisplayTexts(ItemStack stack) {
		return readDisplayTexts(stack, KEY_INPUT_DISPLAY_TEXTS, parseOrderedSerials(read(stack).inputSerialExpression()));
	}

	/**
	 * 读取输出侧展示文本缓存。
	 */
	public static List<String> getOutputDisplayTexts(ItemStack stack) {
		return readDisplayTexts(stack, KEY_OUTPUT_DISPLAY_TEXTS, parseOrderedSerials(read(stack).outputSerialExpression()));
	}

	/**
	 * 按服务端真值刷新输入/输出两侧展示文本缓存。
	 */
	public static void syncNodeSetDisplayTexts(ItemStack stack, ServerLevel level) {
		if (stack == null || stack.isEmpty() || level == null) {
			return;
		}
		RepeaterConfigSnapshot snapshot = read(stack);
		setDisplayTexts(
			stack,
			KEY_INPUT_DISPLAY_TEXTS,
			resolveDisplayTexts(level, LinkNodeType.TRIGGER_SOURCE, parseOrderedSerials(snapshot.inputSerialExpression()))
		);
		setDisplayTexts(
			stack,
			KEY_OUTPUT_DISPLAY_TEXTS,
			resolveDisplayTexts(level, LinkNodeType.CORE, parseOrderedSerials(snapshot.outputSerialExpression()))
		);
	}

	/**
	 * 构建输入侧 tooltip 文本。
	 */
	public static String buildTooltipInputText(ItemStack stack, int maxChars) {
		RepeaterConfigSnapshot snapshot = read(stack);
		return buildTooltipText(snapshot.inputSerialExpression(), getInputDisplayTexts(stack), maxChars);
	}

	/**
	 * 构建输出侧 tooltip 文本。
	 */
	public static String buildTooltipOutputText(ItemStack stack, int maxChars) {
		RepeaterConfigSnapshot snapshot = read(stack);
		return buildTooltipText(snapshot.outputSerialExpression(), getOutputDisplayTexts(stack), maxChars);
	}

	private static String buildTooltipText(String serialExpression, Collection<String> displayTexts, int maxChars) {
		String normalizedExpression = serialExpression == null ? "" : serialExpression.trim();
		if (normalizedExpression.isEmpty()) {
			return "-";
		}
		List<Long> orderedSerials = parseOrderedSerials(normalizedExpression);
		return DisplayTextListFormatUtil.buildText(NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, displayTexts), maxChars);
	}

	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}

	private static String readString(CompoundTag tag, String key) {
		return tag != null && tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : "";
	}

	private static void writeStringOrRemove(CompoundTag tag, String key, String value) {
		if (tag == null || key == null || key.isBlank()) {
			return;
		}
		if (value == null || value.isBlank()) {
			tag.remove(key);
			return;
		}
		tag.putString(key, value.trim());
	}

	private static void setDisplayTexts(ItemStack stack, String key, List<String> displayTexts) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> writeDisplayTexts(tag, key, displayTexts));
	}

	private static void writeDisplayTexts(CompoundTag tag, String key, List<String> displayTexts) {
		if (tag == null || key == null || key.isBlank()) {
			return;
		}
		if (displayTexts == null || displayTexts.isEmpty()) {
			tag.remove(key);
			return;
		}
		ListTag listTag = new ListTag();
		for (String displayText : displayTexts) {
			String normalized = NodeAliasDisplayUtil.normalizeAlias(displayText);
			if (!normalized.isEmpty()) {
				listTag.add(StringTag.valueOf(normalized));
			}
		}
		if (listTag.isEmpty()) {
			tag.remove(key);
			return;
		}
		tag.put(key, listTag);
	}

	private static List<String> readDisplayTexts(ItemStack stack, String key, List<Long> serials) {
		CompoundTag tag = readTag(stack);
		if (serials.isEmpty()) {
			return List.of();
		}
		if (tag == null || !tag.contains(key, Tag.TAG_LIST)) {
			return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, List.of());
		}
		ListTag listTag = tag.getList(key, Tag.TAG_STRING);
		List<String> displayTexts = new ArrayList<>(listTag.size());
		for (int index = 0; index < listTag.size(); index++) {
			displayTexts.add(listTag.getString(index));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, displayTexts);
	}

	private static List<Long> parseOrderedSerials(String rawExpression) {
		return List.copyOf(SerialParseUtil.parseTargetsOrdered(rawExpression, 0).orderedTargets());
	}

	private static List<String> resolveDisplayTexts(ServerLevel level, LinkNodeType type, List<Long> serials) {
		if (level == null || type == null || serials == null || serials.isEmpty()) {
			return List.of();
		}
		List<String> displayTexts = new ArrayList<>(serials.size());
		for (long serial : serials) {
			displayTexts.add(NodeAliasServerSupport.resolveDisplayText(level, type, serial));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, displayTexts);
	}
}
