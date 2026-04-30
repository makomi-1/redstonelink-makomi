package com.makomi.data;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 节点别名持久化数据。
 * <p>
 * 该数据层只维护“节点真值主键 `(type, serial)` 对应的展示别名”，
 * 不参与链接拓扑、读控/写控与 OCC 判断。
 * </p>
 */
public final class NodeAliasSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_node_aliases";
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_TYPE = "type";
	private static final String KEY_SERIAL = "serial";
	private static final String KEY_ALIAS = "alias";
	private static final int MAX_ALIAS_LENGTH = 32;

	private static final Codec<NodeAliasSavedData> CODEC = CompoundTag.CODEC.xmap(
		NodeAliasSavedData::load,
		NodeAliasSavedData::toTag
	);
	private static final SavedDataType<NodeAliasSavedData> TYPE = SavedDataTypeSupport.levelType(
		DATA_NAME,
		NodeAliasSavedData::new,
		CODEC
	);

	private final Map<Long, String> triggerSourceAliasesBySerial = new HashMap<>();
	private final Map<Long, String> coreAliasesBySerial = new HashMap<>();
	private final Map<String, Long> triggerSourceSerialsByAlias = new HashMap<>();
	private final Map<String, Long> coreSerialsByAlias = new HashMap<>();

	/**
	 * 获取共享节点别名数据实例（主世界持久化）。
	 */
	public static NodeAliasSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(TYPE);
	}

	private static NodeAliasSavedData load(CompoundTag tag) {
		NodeAliasSavedData data = new NodeAliasSavedData();
		ListTag entryList = tag.getListOrEmpty(KEY_ENTRIES);
		for (Tag entryTag : entryList) {
			if (!(entryTag instanceof CompoundTag entryCompound)) {
				continue;
			}
			LinkNodeType type = LinkNodeSemantics.tryParseCanonicalType(entryCompound.getStringOr(KEY_TYPE, "")).orElse(null);
			long serial = entryCompound.getLongOr(KEY_SERIAL, 0L);
			String alias = NodeAliasDisplayUtil.normalizeAlias(entryCompound.getStringOr(KEY_ALIAS, ""));
			if (type == null || serial <= 0L || !validateAlias(alias).valid()) {
				continue;
			}
			data.putInternal(type, serial, alias);
		}
		return data;
	}

	/**
	 * 按节点主键查询别名。
	 */
	public Optional<String> getAlias(LinkNodeType type, long serial) {
		if (type == null || serial <= 0L) {
			return Optional.empty();
		}
		String alias = aliasesBySerial(type).get(serial);
		return alias == null || alias.isBlank() ? Optional.empty() : Optional.of(alias);
	}

	/**
	 * 按类型与别名解析节点序号。
	 */
	public OptionalLong resolveSerial(LinkNodeType type, String rawAlias) {
		String alias = NodeAliasDisplayUtil.normalizeAlias(rawAlias);
		if (type == null || alias.isEmpty()) {
			return OptionalLong.empty();
		}
		Long serial = serialsByAlias(type).get(alias);
		return serial == null || serial <= 0L ? OptionalLong.empty() : OptionalLong.of(serial);
	}

	/**
	 * 跨类型按别名解析全部命中项。
	 */
	public List<Entry> resolveAllByAlias(String rawAlias) {
		String alias = NodeAliasDisplayUtil.normalizeAlias(rawAlias);
		if (alias.isEmpty()) {
			return List.of();
		}
		List<Entry> entries = new ArrayList<>(2);
		appendResolvedEntry(entries, LinkNodeType.TRIGGER_SOURCE, alias);
		appendResolvedEntry(entries, LinkNodeType.CORE, alias);
		if (entries.isEmpty()) {
			return List.of();
		}
		return List.copyOf(entries);
	}

	/**
	 * 按类型列出当前全部别名项。
	 */
	public List<Entry> list(LinkNodeType type) {
		if (type == null) {
			return listAll();
		}
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<Long, String> entry : aliasesBySerial(type).entrySet()) {
			entries.add(new Entry(type, entry.getKey(), entry.getValue()));
		}
		entries.sort(Entry.ORDER);
		return entries.isEmpty() ? List.of() : List.copyOf(entries);
	}

	/**
	 * 列出全部别名项。
	 */
	public List<Entry> listAll() {
		List<Entry> entries = new ArrayList<>();
		entries.addAll(listEntriesByType(LinkNodeType.TRIGGER_SOURCE));
		entries.addAll(listEntriesByType(LinkNodeType.CORE));
		entries.sort(Entry.ORDER);
		return entries.isEmpty() ? List.of() : List.copyOf(entries);
	}

	/**
	 * 新增或更新一个别名项。
	 */
	public UpsertResult upsert(LinkNodeType type, long serial, String rawAlias) {
		String alias = NodeAliasDisplayUtil.normalizeAlias(rawAlias);
		ValidationResult validation = validateAlias(alias);
		if (type == null || serial <= 0L || !validation.valid()) {
			return UpsertResult.invalid(validation);
		}

		Map<Long, String> aliasesBySerial = aliasesBySerial(type);
		Map<String, Long> serialsByAlias = serialsByAlias(type);
		Long existingSerial = serialsByAlias.get(alias);
		if (existingSerial != null && existingSerial != serial) {
			return UpsertResult.conflict(existingSerial, alias);
		}

		String previousAlias = aliasesBySerial.get(serial);
		if (alias.equals(previousAlias)) {
			return UpsertResult.unchanged(alias);
		}

		if (previousAlias != null && !previousAlias.isBlank()) {
			serialsByAlias.remove(previousAlias);
		}
		aliasesBySerial.put(serial, alias);
		serialsByAlias.put(alias, serial);
		setDirty();
		return UpsertResult.changed(previousAlias, alias);
	}

	/**
	 * 移除指定节点的别名。
	 */
	public RemoveResult remove(LinkNodeType type, long serial) {
		if (type == null || serial <= 0L) {
			return RemoveResult.notFound();
		}
		Map<Long, String> aliasesBySerial = aliasesBySerial(type);
		String removedAlias = aliasesBySerial.remove(serial);
		if (removedAlias == null || removedAlias.isBlank()) {
			return RemoveResult.notFound();
		}
		serialsByAlias(type).remove(removedAlias);
		setDirty();
		return RemoveResult.removed(removedAlias);
	}

	private CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
		ListTag entryList = new ListTag();
		for (Entry entry : listAll()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putString(KEY_TYPE, LinkNodeSemantics.toSemanticName(entry.nodeType()));
			entryTag.putLong(KEY_SERIAL, entry.serial());
			entryTag.putString(KEY_ALIAS, entry.alias());
			entryList.add(entryTag);
		}
		tag.put(KEY_ENTRIES, entryList);
		return tag;
	}

	/**
	 * 校验别名是否满足第一版规则。
	 */
	public static ValidationResult validateAlias(String rawAlias) {
		String alias = NodeAliasDisplayUtil.normalizeAlias(rawAlias);
		if (alias.isEmpty()) {
			return new ValidationResult(false, "empty", alias);
		}
		if (alias.length() > MAX_ALIAS_LENGTH) {
			return new ValidationResult(false, "too_long", alias);
		}
		boolean hasNonDigit = false;
		for (int index = 0; index < alias.length(); index++) {
			char ch = alias.charAt(index);
			if (!Character.isLetterOrDigit(ch)) {
				return new ValidationResult(false, "invalid_chars", alias);
			}
			if (!Character.isDigit(ch)) {
				hasNonDigit = true;
			}
		}
		if (!hasNonDigit) {
			return new ValidationResult(false, "numeric_only", alias);
		}
		return new ValidationResult(true, "", alias);
	}

	/**
	 * @return 第一版别名长度上限
	 */
	public static int maxAliasLength() {
		return MAX_ALIAS_LENGTH;
	}

	private void appendResolvedEntry(List<Entry> entries, LinkNodeType type, String alias) {
		Long serial = serialsByAlias(type).get(alias);
		if (serial != null && serial > 0L) {
			entries.add(new Entry(type, serial, alias));
		}
	}

	private List<Entry> listEntriesByType(LinkNodeType type) {
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<Long, String> entry : aliasesBySerial(type).entrySet()) {
			entries.add(new Entry(type, entry.getKey(), entry.getValue()));
		}
		return entries;
	}

	private void putInternal(LinkNodeType type, long serial, String alias) {
		Map<Long, String> aliasesBySerial = aliasesBySerial(type);
		Map<String, Long> serialsByAlias = serialsByAlias(type);
		String previousAlias = aliasesBySerial.put(serial, alias);
		if (previousAlias != null && !previousAlias.equals(alias)) {
			serialsByAlias.remove(previousAlias);
		}
		Long previousSerial = serialsByAlias.put(alias, serial);
		if (previousSerial != null && previousSerial != serial) {
			aliasesBySerial.remove(previousSerial);
		}
	}

	private Map<Long, String> aliasesBySerial(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? triggerSourceAliasesBySerial : coreAliasesBySerial;
	}

	private Map<String, Long> serialsByAlias(LinkNodeType type) {
		return type == LinkNodeType.TRIGGER_SOURCE ? triggerSourceSerialsByAlias : coreSerialsByAlias;
	}

	/**
	 * 别名项视图。
	 */
	public record Entry(LinkNodeType nodeType, long serial, String alias) {
		private static final Comparator<Entry> ORDER = Comparator
			.comparing((Entry entry) -> LinkNodeSemantics.toSemanticName(entry.nodeType()))
			.thenComparing(Entry::alias)
			.thenComparingLong(Entry::serial);

		public Entry {
			nodeType = nodeType == LinkNodeType.TRIGGER_SOURCE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
			serial = Math.max(0L, serial);
			alias = NodeAliasDisplayUtil.normalizeAlias(alias);
		}
	}

	/**
	 * 别名校验结果。
	 */
	public record ValidationResult(boolean valid, String reason, String normalizedAlias) {
		public ValidationResult {
			reason = reason == null ? "" : reason;
			normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(normalizedAlias);
		}
	}

	/**
	 * upsert 结果。
	 */
	public record UpsertResult(
		boolean changed,
		boolean conflict,
		boolean valid,
		String previousAlias,
		String alias,
		long conflictSerial,
		ValidationResult validation
	) {
		static UpsertResult invalid(ValidationResult validation) {
			return new UpsertResult(false, false, false, "", "", 0L, validation);
		}

		static UpsertResult conflict(long conflictSerial, String alias) {
			return new UpsertResult(false, true, true, "", NodeAliasDisplayUtil.normalizeAlias(alias), conflictSerial, null);
		}

		static UpsertResult unchanged(String alias) {
			return new UpsertResult(false, false, true, "", NodeAliasDisplayUtil.normalizeAlias(alias), 0L, null);
		}

		static UpsertResult changed(String previousAlias, String alias) {
			return new UpsertResult(
				true,
				false,
				true,
				NodeAliasDisplayUtil.normalizeAlias(previousAlias),
				NodeAliasDisplayUtil.normalizeAlias(alias),
				0L,
				null
			);
		}
	}

	/**
	 * remove 结果。
	 */
	public record RemoveResult(boolean removed, String alias) {
		static RemoveResult notFound() {
			return new RemoveResult(false, "");
		}

		static RemoveResult removed(String alias) {
			return new RemoveResult(true, NodeAliasDisplayUtil.normalizeAlias(alias));
		}
	}
}
