package com.makomi.data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * graph 导出跨重启去重台账。
 * <p>
 * 按玩家记录最近成功下发过的结构校验码与文件名，用于在服务端重启后继续复用本地已有 graph 资产。
 * </p>
 */
public final class GraphExportDedupeSavedData extends SavedData {
	private static final String DATA_NAME = "redstonelink_graph_export_dedupe";
	private static final String KEY_PLAYERS = "players";
	private static final String KEY_PLAYER_ID = "playerId";
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_STRUCTURE_CHECKSUM = "structureChecksum";
	private static final String KEY_FILE_NAME = "fileName";
	private static final int MAX_RECENT_ENTRIES_PER_PLAYER = 64;

	private static final SavedData.Factory<GraphExportDedupeSavedData> FACTORY = new SavedData.Factory<>(
		GraphExportDedupeSavedData::new,
		GraphExportDedupeSavedData::load,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, LinkedHashMap<String, String>> fileNameByChecksumByPlayer = new HashMap<>();

	/**
	 * 获取 graph 导出去重台账实例（主世界持久化）。
	 */
	public static GraphExportDedupeSavedData get(ServerLevel level) {
		ServerLevel overworld = level.getServer().overworld();
		return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	private static GraphExportDedupeSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		GraphExportDedupeSavedData data = new GraphExportDedupeSavedData();
		ListTag playerEntries = tag.getList(KEY_PLAYERS, Tag.TAG_COMPOUND);
		for (int playerIndex = 0; playerIndex < playerEntries.size(); playerIndex++) {
			CompoundTag playerTag = playerEntries.getCompound(playerIndex);
			UUID playerId = tryParsePlayerId(playerTag.getString(KEY_PLAYER_ID));
			if (playerId == null) {
				continue;
			}
			LinkedHashMap<String, String> entries = new LinkedHashMap<>();
			ListTag checksumEntries = playerTag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
			for (int entryIndex = 0; entryIndex < checksumEntries.size(); entryIndex++) {
				CompoundTag entryTag = checksumEntries.getCompound(entryIndex);
				String checksum = normalizeChecksum(entryTag.getString(KEY_STRUCTURE_CHECKSUM));
				String fileName = normalizeFileName(entryTag.getString(KEY_FILE_NAME));
				if (checksum.isEmpty() || fileName.isEmpty()) {
					continue;
				}
				entries.put(checksum, fileName);
				trimToRecentLimit(entries);
			}
			if (!entries.isEmpty()) {
				data.fileNameByChecksumByPlayer.put(playerId, entries);
			}
		}
		return data;
	}

	/**
	 * 判断当前玩家是否已下发过相同 graph 结构文件。
	 */
	public boolean contains(UUID playerId, String structureChecksum, String fileName) {
		if (playerId == null) {
			return false;
		}
		String normalizedChecksum = normalizeChecksum(structureChecksum);
		String normalizedFileName = normalizeFileName(fileName);
		if (normalizedChecksum.isEmpty() || normalizedFileName.isEmpty()) {
			return false;
		}
		Map<String, String> entries = fileNameByChecksumByPlayer.get(playerId);
		return entries != null && normalizedFileName.equals(entries.get(normalizedChecksum));
	}

	/**
	 * 记录当前玩家最近成功下发的 graph 文件。
	 */
	public void remember(UUID playerId, String structureChecksum, String fileName) {
		if (playerId == null) {
			return;
		}
		String normalizedChecksum = normalizeChecksum(structureChecksum);
		String normalizedFileName = normalizeFileName(fileName);
		if (normalizedChecksum.isEmpty() || normalizedFileName.isEmpty()) {
			return;
		}
		LinkedHashMap<String, String> entries = fileNameByChecksumByPlayer.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
		String previousFileName = entries.remove(normalizedChecksum);
		entries.put(normalizedChecksum, normalizedFileName);
		trimToRecentLimit(entries);
		if (!normalizedFileName.equals(previousFileName)) {
			setDirty();
		}
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		ListTag playerEntries = new ListTag();
		for (Map.Entry<UUID, LinkedHashMap<String, String>> playerEntry : fileNameByChecksumByPlayer.entrySet()) {
			if (playerEntry.getValue().isEmpty()) {
				continue;
			}
			CompoundTag playerTag = new CompoundTag();
			playerTag.putString(KEY_PLAYER_ID, playerEntry.getKey().toString());
			ListTag checksumEntries = new ListTag();
			for (Map.Entry<String, String> checksumEntry : playerEntry.getValue().entrySet()) {
				CompoundTag entryTag = new CompoundTag();
				entryTag.putString(KEY_STRUCTURE_CHECKSUM, checksumEntry.getKey());
				entryTag.putString(KEY_FILE_NAME, checksumEntry.getValue());
				checksumEntries.add(entryTag);
			}
			playerTag.put(KEY_ENTRIES, checksumEntries);
			playerEntries.add(playerTag);
		}
		tag.put(KEY_PLAYERS, playerEntries);
		return tag;
	}

	private static UUID tryParsePlayerId(String rawPlayerId) {
		String normalized = rawPlayerId == null ? "" : rawPlayerId.trim();
		if (normalized.isEmpty()) {
			return null;
		}
		try {
			return UUID.fromString(normalized);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String normalizeChecksum(String rawChecksum) {
		return rawChecksum == null ? "" : rawChecksum.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static String normalizeFileName(String rawFileName) {
		return rawFileName == null ? "" : rawFileName.trim();
	}

	private static void trimToRecentLimit(LinkedHashMap<String, String> entries) {
		if (entries.size() <= MAX_RECENT_ENTRIES_PER_PLAYER) {
			return;
		}
		List<String> checksums = List.copyOf(entries.keySet());
		int removeCount = entries.size() - MAX_RECENT_ENTRIES_PER_PLAYER;
		for (int index = 0; index < removeCount; index++) {
			entries.remove(checksums.get(index));
		}
	}
}
