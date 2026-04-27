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
 * 过滤器物品配置读写工具。
 * <p>
 * 统一维护手持过滤器物品上的配置快照，使其可以在
 * “手持物品 -> 已放置方块 -> 掉落物”之间保持一致。
 * </p>
 */
public final class LinkFilterItemData {
	private static final String KEY_SERIAL_EXPRESSION = "rl_filter_serial_expression";
	private static final String KEY_TARGET_MODE = "rl_filter_target_mode";
	private static final String KEY_CHANNEL = "rl_filter_channel";
	private static final String KEY_NODE_SET_MODE = "rl_filter_node_set_mode";
	private static final String KEY_SIGNAL_THRESHOLD_SOURCE = "rl_filter_signal_threshold_source";
	private static final String KEY_FIXED_SIGNAL_THRESHOLD = "rl_filter_fixed_signal_threshold";
	private static final String KEY_SIGNAL_MODE = "rl_filter_signal_mode";
	private static final String KEY_DISPLAY_ALIAS = "rl_filter_display_alias";
	private static final String KEY_NODE_SET_DISPLAY_TEXTS = "rl_filter_node_set_display_texts";

	private LinkFilterItemData() {
	}

	/**
	 * 读取过滤器物品配置；缺失字段会回退到默认快照。
	 */
	public static LinkFilterConfigSnapshot read(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return new LinkFilterConfigSnapshot(
			tag.getStringOr(KEY_SERIAL_EXPRESSION, ""),
			LinkFilterTargetMode.tryParseToken(tag.getStringOr(KEY_TARGET_MODE, "")).orElse(null),
			tag.contains(KEY_CHANNEL) ? Math.max(0L, tag.getLongOr(KEY_CHANNEL, 0L)) : 0L,
			LinkFilterNodeSetMode.tryParseToken(tag.getStringOr(KEY_NODE_SET_MODE, "")).orElse(LinkFilterNodeSetMode.DISABLED),
			LinkFilterSignalThresholdSource
				.tryParseToken(tag.getStringOr(KEY_SIGNAL_THRESHOLD_SOURCE, ""))
				.orElse(LinkFilterSignalThresholdSource.FIXED_INPUT),
			tag.contains(KEY_FIXED_SIGNAL_THRESHOLD) ? tag.getIntOr(KEY_FIXED_SIGNAL_THRESHOLD, 0) : 15,
			LinkFilterSignalMode.tryParseToken(tag.getStringOr(KEY_SIGNAL_MODE, "")).orElse(LinkFilterSignalMode.DISABLED)
		);
	}

	/**
	 * 写入过滤器物品配置；空白表达式会移除对应字段。
	 */
	public static void write(ItemStack stack, LinkFilterConfigSnapshot snapshot) {
		LinkFilterConfigSnapshot normalized = snapshot == null
			? new LinkFilterConfigSnapshot("", LinkFilterTargetMode.SERIAL, 0L, null, null, 15, null)
			: snapshot;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			writeStringOrRemove(tag, KEY_SERIAL_EXPRESSION, normalized.serialExpression().trim());
			tag.putString(KEY_TARGET_MODE, normalized.targetMode().token());
			if (normalized.channel() > 0L) {
				tag.putLong(KEY_CHANNEL, normalized.channel());
			} else {
				tag.remove(KEY_CHANNEL);
			}
			tag.putString(KEY_NODE_SET_MODE, normalized.nodeSetMode().token());
			tag.putString(KEY_SIGNAL_THRESHOLD_SOURCE, normalized.signalThresholdSource().token());
			tag.putInt(KEY_FIXED_SIGNAL_THRESHOLD, normalized.fixedSignalThreshold());
			tag.putString(KEY_SIGNAL_MODE, normalized.signalMode().token());
			tag.remove(KEY_NODE_SET_DISPLAY_TEXTS);
		});
	}

	/**
	 * 读取过滤器物品缓存的展示别名。
	 */
	public static String getDisplayAlias(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_DISPLAY_ALIAS)) {
			return "";
		}
		return NodeAliasDisplayUtil.normalizeAlias(tag.getStringOr(KEY_DISPLAY_ALIAS, ""));
	}

	/**
	 * 写入过滤器物品缓存的展示别名。
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
	 * 读取过滤器物品缓存的节点集展示文本。
	 */
	public static List<String> getNodeSetDisplayTexts(ItemStack stack) {
		LinkFilterConfigSnapshot snapshot = read(stack);
		if (!snapshot.usesSerialTarget()) {
			return List.of();
		}
		List<Long> orderedSerials = parseOrderedSerials(snapshot.serialExpression());
		if (orderedSerials.isEmpty()) {
			return List.of();
		}
		return readDisplayTexts(readTag(stack), KEY_NODE_SET_DISPLAY_TEXTS, orderedSerials);
	}

	/**
	 * 覆盖写入过滤器物品的节点集展示文本缓存。
	 */
	public static void setNodeSetDisplayTexts(
		ItemStack stack,
		LinkFilterConfigSnapshot snapshot,
		Collection<String> displayTexts
	) {
		LinkFilterConfigSnapshot normalized = snapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : snapshot;
		List<Long> orderedSerials = normalized.usesSerialTarget() ? parseOrderedSerials(normalized.serialExpression()) : List.of();
		List<String> normalizedDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, displayTexts);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> writeDisplayTexts(tag, KEY_NODE_SET_DISPLAY_TEXTS, normalizedDisplayTexts));
	}

	/**
	 * 按服务端别名真值刷新过滤器物品节点集展示文本缓存。
	 */
	public static void syncNodeSetDisplayTexts(ItemStack stack, ServerLevel level, LinkNodeType nodeType) {
		LinkFilterConfigSnapshot snapshot = read(stack);
		if (level == null || nodeType == null || !snapshot.usesSerialTarget()) {
			setNodeSetDisplayTexts(stack, snapshot, List.of());
			return;
		}
		List<Long> orderedSerials = parseOrderedSerials(snapshot.serialExpression());
		List<String> displayTexts = resolveDisplayTexts(level, nodeType, orderedSerials);
		setNodeSetDisplayTexts(stack, snapshot, displayTexts);
	}

	/**
	 * 为 tooltip 构建节点集结构化文本，保持 `N / A:B` 风格。
	 */
	public static String buildTooltipSerialExpressionText(LinkFilterConfigSnapshot snapshot, int maxChars) {
		return buildTooltipSerialExpressionText(snapshot, List.of(), maxChars);
	}

	/**
	 * 为 tooltip 构建节点集展示文本，优先使用别名展示文本。
	 */
	public static String buildTooltipSerialExpressionText(
		LinkFilterConfigSnapshot snapshot,
		Collection<String> displayTexts,
		int maxChars
	) {
		LinkFilterConfigSnapshot normalized = snapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : snapshot;
		String serialExpression = normalized.serialExpression().trim();
		if (serialExpression.isEmpty()) {
			return "-";
		}
		List<Long> orderedSerials = parseOrderedSerials(serialExpression);
		return DisplayTextListFormatUtil.buildText(NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, displayTexts), maxChars);
	}

	/**
	 * 为 tooltip 构建过滤目标文本；序号模式使用结构化序号组，频道模式输出频道号。
	 */
	public static String buildTooltipTargetText(LinkFilterConfigSnapshot snapshot, int maxChars) {
		return buildTooltipTargetText(snapshot, List.of(), maxChars);
	}

	/**
	 * 为 tooltip 构建过滤目标文本；序号模式优先显示别名展示文本。
	 */
	public static String buildTooltipTargetText(
		LinkFilterConfigSnapshot snapshot,
		Collection<String> displayTexts,
		int maxChars
	) {
		LinkFilterConfigSnapshot normalized = snapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : snapshot;
		if (normalized.usesChannelTarget()) {
			return normalized.channel() > 0L ? Long.toString(normalized.channel()) : "-";
		}
		return buildTooltipSerialExpressionText(normalized, displayTexts, maxChars);
	}

	/**
	 * 读取物品自定义数据标签副本。
	 */
	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}

	/**
	 * 写入字符串；空值或空白时移除字段。
	 */
	private static void writeStringOrRemove(CompoundTag tag, String key, String value) {
		if (value == null || value.isBlank()) {
			tag.remove(key);
			return;
		}
		tag.putString(key, value);
	}

	private static List<Long> parseOrderedSerials(String serialExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(serialExpression, 0);
		if (parseResult.orderedTargets().isEmpty()) {
			return List.of();
		}
		return List.copyOf(parseResult.orderedTargets());
	}

	private static List<String> resolveDisplayTexts(ServerLevel level, LinkNodeType nodeType, List<Long> serials) {
		if (level == null || nodeType == null || serials == null || serials.isEmpty()) {
			return List.of();
		}
		List<String> displayTexts = new ArrayList<>(serials.size());
		for (long serial : serials) {
			displayTexts.add(NodeAliasServerSupport.resolveDisplayText(level, nodeType, serial));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, displayTexts);
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
			String normalizedText = NodeAliasDisplayUtil.normalizeAlias(displayText);
			if (!normalizedText.isEmpty()) {
				listTag.add(StringTag.valueOf(normalizedText));
			}
		}
		if (listTag.isEmpty()) {
			tag.remove(key);
			return;
		}
		tag.put(key, listTag);
	}

	private static List<String> readDisplayTexts(CompoundTag tag, String key, List<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		if (tag == null || key == null || key.isBlank() || !tag.contains(key)) {
			return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, List.of());
		}
		ListTag listTag = tag.getListOrEmpty(key);
		List<String> displayTexts = new ArrayList<>(listTag.size());
		for (int index = 0; index < listTag.size(); index++) {
			displayTexts.add(listTag.getStringOr(index, ""));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, displayTexts);
	}
}
