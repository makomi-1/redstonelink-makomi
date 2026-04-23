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
	private static final String KEY_TRIGGER_SOURCE_NODE_SET_DISPLAY_TEXTS = "rl_chunk_activator_trigger_source_node_set_display_texts";
	private static final String KEY_CORE_NODE_SET_DISPLAY_TEXTS = "rl_chunk_activator_core_node_set_display_texts";

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
			tag.remove(KEY_TRIGGER_SOURCE_NODE_SET_DISPLAY_TEXTS);
			tag.remove(KEY_CORE_NODE_SET_DISPLAY_TEXTS);
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
	 * 读取当前 activeType 对应的节点集展示文本缓存。
	 */
	public static List<String> getActiveNodeSetDisplayTexts(ItemStack stack) {
		ChunkActivatorConfigStateSnapshot snapshot = read(stack);
		return getNodeSetDisplayTexts(stack, snapshot.activeType());
	}

	/**
	 * 按节点类型读取节点集展示文本缓存。
	 */
	public static List<String> getNodeSetDisplayTexts(ItemStack stack, LinkNodeType type) {
		ChunkActivatorConfigStateSnapshot snapshot = read(stack);
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(type);
		List<Long> orderedSerials = parseOrderedSerials(snapshot.configFor(normalizedType).serialExpression());
		if (orderedSerials.isEmpty()) {
			return List.of();
		}
		return readDisplayTexts(readTag(stack), displayTextsKey(normalizedType), orderedSerials);
	}

	/**
	 * 覆盖写入指定节点类型的节点集展示文本缓存。
	 */
	public static void setNodeSetDisplayTexts(
		ItemStack stack,
		ChunkActivatorConfigStateSnapshot snapshot,
		LinkNodeType type,
		Collection<String> displayTexts
	) {
		ChunkActivatorConfigStateSnapshot normalizedSnapshot = snapshot == null
			? new ChunkActivatorConfigStateSnapshot(LinkNodeType.TRIGGER_SOURCE, null, null)
			: snapshot;
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(type);
		List<Long> orderedSerials = parseOrderedSerials(normalizedSnapshot.configFor(normalizedType).serialExpression());
		List<String> normalizedDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, displayTexts);
		CustomData.update(
			DataComponents.CUSTOM_DATA,
			stack,
			tag -> writeDisplayTexts(tag, displayTextsKey(normalizedType), normalizedDisplayTexts)
		);
	}

	/**
	 * 按服务端别名真值刷新两套节点集展示文本缓存。
	 */
	public static void syncNodeSetDisplayTexts(ItemStack stack, ServerLevel level) {
		ChunkActivatorConfigStateSnapshot snapshot = read(stack);
		syncNodeSetDisplayTexts(stack, level, snapshot, LinkNodeType.TRIGGER_SOURCE);
		syncNodeSetDisplayTexts(stack, level, snapshot, LinkNodeType.CORE);
	}

	/**
	 * 为 tooltip 构建结构化节点集文本。
	 */
	public static String buildTooltipSerialExpressionText(ChunkActivatorConfigSnapshot snapshot, int maxChars) {
		return buildTooltipSerialExpressionText(snapshot, List.of(), maxChars);
	}

	/**
	 * 为 tooltip 构建节点集展示文本，优先使用别名展示文本。
	 */
	public static String buildTooltipSerialExpressionText(
		ChunkActivatorConfigSnapshot snapshot,
		Collection<String> displayTexts,
		int maxChars
	) {
		ChunkActivatorConfigSnapshot normalized = snapshot == null
			? new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			: snapshot;
		String serialExpression = normalized.serialExpression().trim();
		if (serialExpression.isEmpty()) {
			return "-";
		}
		List<Long> orderedSerials = parseOrderedSerials(serialExpression);
		return DisplayTextListFormatUtil.buildText(NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, displayTexts), maxChars);
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

	private static void syncNodeSetDisplayTexts(
		ItemStack stack,
		ServerLevel level,
		ChunkActivatorConfigStateSnapshot snapshot,
		LinkNodeType type
	) {
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(type);
		List<Long> orderedSerials = parseOrderedSerials(snapshot.configFor(normalizedType).serialExpression());
		List<String> displayTexts = resolveDisplayTexts(level, normalizedType, orderedSerials);
		setNodeSetDisplayTexts(stack, snapshot, normalizedType, displayTexts);
	}

	private static List<Long> parseOrderedSerials(String serialExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			serialExpression,
			PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE
		);
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

	private static String displayTextsKey(LinkNodeType type) {
		return ChunkActivatorConfigStateSnapshot.normalizeType(type) == LinkNodeType.CORE
			? KEY_CORE_NODE_SET_DISPLAY_TEXTS
			: KEY_TRIGGER_SOURCE_NODE_SET_DISPLAY_TEXTS;
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
		if (tag == null || key == null || key.isBlank() || !tag.contains(key, Tag.TAG_LIST)) {
			return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, List.of());
		}
		ListTag listTag = tag.getList(key, Tag.TAG_STRING);
		List<String> displayTexts = new ArrayList<>(listTag.size());
		for (int index = 0; index < listTag.size(); index++) {
			displayTexts.add(listTag.getString(index));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, displayTexts);
	}
}
