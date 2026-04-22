package com.makomi.data;

import com.makomi.util.SerialDisplayFormatUtil;
import com.makomi.util.SerialParseUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 区块激活器物品配置读写工具。
 * <p>
 * 统一维护手持区块激活器与已放置区块激活器之间共享的配置快照。
 * </p>
 */
public final class ChunkActivatorItemData {
	private static final String KEY_ACTIVE_TYPE = "rl_chunk_activator_active_type";
	private static final String KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION = "rl_chunk_activator_trigger_source_serial_expression";
	private static final String KEY_TRIGGER_SOURCE_MODE = "rl_chunk_activator_trigger_source_mode";
	private static final String KEY_CORE_SERIAL_EXPRESSION = "rl_chunk_activator_core_serial_expression";
	private static final String KEY_CORE_MODE = "rl_chunk_activator_core_mode";
	private static final String KEY_DISPLAY_ALIAS = "rl_chunk_activator_display_alias";
	private static final String KEY_LEGACY_SERIAL_EXPRESSION = "rl_chunk_activator_serial_expression";
	private static final String KEY_LEGACY_MODE = "rl_chunk_activator_mode";

	private ChunkActivatorItemData() {
	}

	/**
	 * 读取区块激活器物品配置。
	 */
	public static ChunkActivatorConfigStateSnapshot read(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		ChunkActivatorConfigSnapshot legacyConfig = new ChunkActivatorConfigSnapshot(
			tag.getString(KEY_LEGACY_SERIAL_EXPRESSION),
			ChunkActivatorMode.tryParseToken(tag.getString(KEY_LEGACY_MODE)).orElse(ChunkActivatorMode.FORCE_LOAD)
		);
		ChunkActivatorConfigSnapshot triggerSourceConfig = new ChunkActivatorConfigSnapshot(
			readString(tag, KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION, legacyConfig.serialExpression()),
			ChunkActivatorMode.tryParseToken(readString(tag, KEY_TRIGGER_SOURCE_MODE, legacyConfig.mode().token()))
				.orElse(legacyConfig.mode())
		);
		ChunkActivatorConfigSnapshot coreConfig = new ChunkActivatorConfigSnapshot(
			readString(tag, KEY_CORE_SERIAL_EXPRESSION, ""),
			ChunkActivatorMode.tryParseToken(readString(tag, KEY_CORE_MODE, ChunkActivatorMode.FORCE_LOAD.token()))
				.orElse(ChunkActivatorMode.FORCE_LOAD)
		);
		return new ChunkActivatorConfigStateSnapshot(
			ChunkActivatorConfigStateSnapshot.tryParseTypeToken(readString(tag, KEY_ACTIVE_TYPE, "")).orElse(LinkNodeType.TRIGGER_SOURCE),
			triggerSourceConfig,
			coreConfig
		);
	}

	/**
	 * 写入区块激活器物品配置。
	 */
	public static void write(ItemStack stack, ChunkActivatorConfigStateSnapshot snapshot) {
		ChunkActivatorConfigStateSnapshot normalized = snapshot == null
			? new ChunkActivatorConfigStateSnapshot(LinkNodeType.TRIGGER_SOURCE, null, null)
			: snapshot;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.putString(KEY_ACTIVE_TYPE, ChunkActivatorConfigStateSnapshot.toTypeToken(normalized.activeType()));
			writeStringOrRemove(tag, KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION, normalized.triggerSourceConfig().serialExpression());
			tag.putString(KEY_TRIGGER_SOURCE_MODE, normalized.triggerSourceConfig().mode().token());
			writeStringOrRemove(tag, KEY_CORE_SERIAL_EXPRESSION, normalized.coreConfig().serialExpression());
			tag.putString(KEY_CORE_MODE, normalized.coreConfig().mode().token());
			tag.remove(KEY_LEGACY_SERIAL_EXPRESSION);
			tag.remove(KEY_LEGACY_MODE);
		});
	}

	/**
	 * 读取区块激活器物品缓存的展示别名。
	 */
	public static String getDisplayAlias(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		if (!tag.contains(KEY_DISPLAY_ALIAS, Tag.TAG_STRING)) {
			return "";
		}
		return NodeAliasDisplayUtil.normalizeAlias(tag.getString(KEY_DISPLAY_ALIAS));
	}

	/**
	 * 写入区块激活器物品缓存的展示别名。
	 */
	public static void setDisplayAlias(ItemStack stack, String alias) {
		String normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(alias);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			if (normalizedAlias.isEmpty()) {
				tag.remove(KEY_DISPLAY_ALIAS);
				return;
			}
			tag.putString(KEY_DISPLAY_ALIAS, normalizedAlias);
		});
	}

	/**
	 * 为 tooltip 构建结构化节点集文本。
	 */
	public static String buildTooltipSerialExpressionText(ChunkActivatorConfigSnapshot snapshot, int maxChars) {
		ChunkActivatorConfigSnapshot normalized = snapshot == null
			? new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			: snapshot;
		String serialExpression = normalized.serialExpression().trim();
		if (serialExpression.isEmpty()) {
			return "-";
		}
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(serialExpression, 0);
		return SerialDisplayFormatUtil.buildText(parseResult.orderedTargets(), maxChars);
	}

	/**
	 * 读取物品自定义数据标签副本。
	 */
	private static CompoundTag readTag(ItemStack stack) {
		CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		return customData.copyTag();
	}

	private static String readString(CompoundTag tag, String key, String fallback) {
		if (tag == null || !tag.contains(key, Tag.TAG_STRING)) {
			return fallback;
		}
		return tag.getString(key);
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
