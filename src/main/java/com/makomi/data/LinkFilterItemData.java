package com.makomi.data;

import com.makomi.util.SerialDisplayFormatUtil;
import com.makomi.util.SerialParseUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

	private LinkFilterItemData() {
	}

	/**
	 * 读取过滤器物品配置；缺失字段会回退到默认快照。
	 */
	public static LinkFilterConfigSnapshot read(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return new LinkFilterConfigSnapshot(
			tag.getString(KEY_SERIAL_EXPRESSION),
			LinkFilterTargetMode.tryParseToken(tag.getString(KEY_TARGET_MODE)).orElse(null),
			tag.contains(KEY_CHANNEL, Tag.TAG_LONG) ? Math.max(0L, tag.getLong(KEY_CHANNEL)) : 0L,
			LinkFilterNodeSetMode.tryParseToken(tag.getString(KEY_NODE_SET_MODE)).orElse(LinkFilterNodeSetMode.DISABLED),
			LinkFilterSignalThresholdSource
				.tryParseToken(tag.getString(KEY_SIGNAL_THRESHOLD_SOURCE))
				.orElse(LinkFilterSignalThresholdSource.FIXED_INPUT),
			tag.contains(KEY_FIXED_SIGNAL_THRESHOLD) ? tag.getInt(KEY_FIXED_SIGNAL_THRESHOLD) : 15,
			LinkFilterSignalMode.tryParseToken(tag.getString(KEY_SIGNAL_MODE)).orElse(LinkFilterSignalMode.DISABLED)
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
		});
	}

	/**
	 * 读取过滤器物品缓存的展示别名。
	 */
	public static String getDisplayAlias(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_DISPLAY_ALIAS, Tag.TAG_STRING)) {
			return "";
		}
		return NodeAliasDisplayUtil.normalizeAlias(tag.getString(KEY_DISPLAY_ALIAS));
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
	 * 为 tooltip 构建节点集结构化文本，保持 `N / A:B` 风格。
	 */
	public static String buildTooltipSerialExpressionText(LinkFilterConfigSnapshot snapshot, int maxChars) {
		LinkFilterConfigSnapshot normalized = snapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : snapshot;
		String serialExpression = normalized.serialExpression().trim();
		if (serialExpression.isEmpty()) {
			return "-";
		}
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(serialExpression, 0);
		return SerialDisplayFormatUtil.buildText(parseResult.orderedTargets(), maxChars);
	}

	/**
	 * 为 tooltip 构建过滤目标文本；序号模式使用结构化序号组，频道模式输出频道号。
	 */
	public static String buildTooltipTargetText(LinkFilterConfigSnapshot snapshot, int maxChars) {
		LinkFilterConfigSnapshot normalized = snapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : snapshot;
		if (normalized.usesChannelTarget()) {
			return normalized.channel() > 0L ? Long.toString(normalized.channel()) : "-";
		}
		return buildTooltipSerialExpressionText(normalized, maxChars);
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
}
