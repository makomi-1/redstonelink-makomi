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
	private static final String KEY_SERIAL_EXPRESSION = "rl_chunk_activator_serial_expression";
	private static final String KEY_MODE = "rl_chunk_activator_mode";
	private static final String KEY_DISPLAY_ALIAS = "rl_chunk_activator_display_alias";

	private ChunkActivatorItemData() {
	}

	/**
	 * 读取区块激活器物品配置。
	 */
	public static ChunkActivatorConfigSnapshot read(ItemStack stack) {
		CompoundTag tag = readTag(stack);
		return new ChunkActivatorConfigSnapshot(
			tag.getString(KEY_SERIAL_EXPRESSION),
			ChunkActivatorMode.tryParseToken(tag.getString(KEY_MODE)).orElse(ChunkActivatorMode.FORCE_LOAD)
		);
	}

	/**
	 * 写入区块激活器物品配置。
	 */
	public static void write(ItemStack stack, ChunkActivatorConfigSnapshot snapshot) {
		ChunkActivatorConfigSnapshot normalized = snapshot == null
			? new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			: snapshot;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			writeStringOrRemove(tag, KEY_SERIAL_EXPRESSION, normalized.serialExpression());
			tag.putString(KEY_MODE, normalized.mode().token());
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
