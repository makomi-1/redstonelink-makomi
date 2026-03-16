package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PendingRetireQueueSavedData 稳定契约测试。
 */
@Tag("stable-core")
class PendingRetireQueueSavedDataTest {
	/**
	 * upsert/remove 应遵循参数守卫、覆盖更新与去重规则。
	 */
	@Test
	void upsertAndRemoveShouldRespectInputGuards() {
		PendingRetireQueueSavedData data = new PendingRetireQueueSavedData();
		ResourceKey<Level> dimension = Level.OVERWORLD;
		BlockPos pos = new BlockPos(1, 64, 1);

		assertFalse(data.upsert(null, 1L, dimension, pos, 20L));
		assertFalse(data.upsert(LinkNodeType.TRIGGER_SOURCE, 0L, dimension, pos, 20L));
		assertFalse(data.upsert(LinkNodeType.TRIGGER_SOURCE, 1L, null, pos, 20L));
		assertFalse(data.upsert(LinkNodeType.TRIGGER_SOURCE, 1L, dimension, null, 20L));
		assertFalse(data.upsert(LinkNodeType.TRIGGER_SOURCE, 1L, dimension, pos, -1L));

		assertTrue(data.upsert(LinkNodeType.TRIGGER_SOURCE, 1L, dimension, pos, 20L));
		assertFalse(data.upsert(LinkNodeType.TRIGGER_SOURCE, 1L, dimension, pos, 20L));
		assertTrue(data.upsert(LinkNodeType.TRIGGER_SOURCE, 1L, dimension, pos, 21L));

		List<PendingRetireQueueSavedData.PendingRetireEntry> entries = data.entriesSnapshot();
		assertEquals(1, entries.size());
		assertEquals(21L, entries.get(0).expireTick());
		assertThrows(UnsupportedOperationException.class, () -> entries.add(entries.get(0)));

		assertTrue(data.remove(LinkNodeType.TRIGGER_SOURCE, 1L));
		assertFalse(data.remove(LinkNodeType.TRIGGER_SOURCE, 1L));
		assertFalse(data.remove(null, 1L));
		assertFalse(data.remove(LinkNodeType.TRIGGER_SOURCE, 0L));
		assertTrue(data.entriesSnapshot().isEmpty());
	}

	/**
	 * 保存与读取应保留 canonical type/dimension/pos/expireTick 字段。
	 */
	@Test
	void saveAndLoadShouldKeepCanonicalEntryFields() throws Exception {
		PendingRetireQueueSavedData data = new PendingRetireQueueSavedData();
		assertTrue(data.upsert(LinkNodeType.TRIGGER_SOURCE, 3L, Level.OVERWORLD, new BlockPos(3, 70, 3), 40L));
		assertTrue(data.upsert(LinkNodeType.CORE, 7L, Level.NETHER, new BlockPos(7, 50, 9), 80L));

		CompoundTag root = data.save(new CompoundTag(), null);
		ListTag entriesTag = root.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
		assertEquals(2, entriesTag.size());
		assertNotNull(findEntry(entriesTag, "triggerSource", 3L));
		assertNotNull(findEntry(entriesTag, "core", 7L));

		PendingRetireQueueSavedData loaded = invokeLoad(root);
		List<PendingRetireQueueSavedData.PendingRetireEntry> loadedEntries = loaded.entriesSnapshot();
		assertEquals(2, loadedEntries.size());
		assertTrue(containsEntry(loadedEntries, LinkNodeType.TRIGGER_SOURCE, 3L, Level.OVERWORLD, new BlockPos(3, 70, 3), 40L));
		assertTrue(containsEntry(loadedEntries, LinkNodeType.CORE, 7L, Level.NETHER, new BlockPos(7, 50, 9), 80L));
	}

	/**
	 * 读取时应忽略非法类型、非法序号、非法维度与非法到期刻。
	 */
	@Test
	void loadShouldIgnoreInvalidEntries() throws Exception {
		CompoundTag root = new CompoundTag();
		ListTag entries = new ListTag();
		entries.add(entry("triggerSource", 11L, "minecraft:overworld", new BlockPos(1, 2, 3), 33L));
		entries.add(entry("unknown", 11L, "minecraft:overworld", new BlockPos(1, 2, 3), 33L));
		entries.add(entry("core", 0L, "minecraft:overworld", new BlockPos(1, 2, 3), 33L));
		entries.add(entry("core", 12L, "invalid::dimension", new BlockPos(1, 2, 3), 33L));
		entries.add(entry("core", 13L, "minecraft:the_nether", new BlockPos(1, 2, 3), -1L));
		root.put("entries", entries);

		PendingRetireQueueSavedData loaded = invokeLoad(root);
		List<PendingRetireQueueSavedData.PendingRetireEntry> loadedEntries = loaded.entriesSnapshot();
		assertEquals(1, loadedEntries.size());
		assertTrue(containsEntry(loadedEntries, LinkNodeType.TRIGGER_SOURCE, 11L, Level.OVERWORLD, new BlockPos(1, 2, 3), 33L));
	}

	private static PendingRetireQueueSavedData invokeLoad(CompoundTag root) throws Exception {
		Method loadMethod = PendingRetireQueueSavedData.class.getDeclaredMethod(
			"load",
			CompoundTag.class,
			HolderLookup.Provider.class
		);
		loadMethod.setAccessible(true);
		return (PendingRetireQueueSavedData) loadMethod.invoke(null, root, null);
	}

	private static CompoundTag findEntry(ListTag entries, String type, long serial) {
		for (net.minecraft.nbt.Tag element : entries) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			if (type.equals(entryTag.getString("type")) && serial == entryTag.getLong("serial")) {
				return entryTag;
			}
		}
		return null;
	}

	private static boolean containsEntry(
		List<PendingRetireQueueSavedData.PendingRetireEntry> entries,
		LinkNodeType nodeType,
		long serial,
		ResourceKey<Level> dimension,
		BlockPos pos,
		long expireTick
	) {
		for (PendingRetireQueueSavedData.PendingRetireEntry entry : entries) {
			if (entry.nodeType() != nodeType) {
				continue;
			}
			if (entry.serial() != serial) {
				continue;
			}
			if (!entry.dimension().equals(dimension)) {
				continue;
			}
			if (!entry.pos().equals(pos)) {
				continue;
			}
			if (entry.expireTick() != expireTick) {
				continue;
			}
			return true;
		}
		return false;
	}

	private static CompoundTag entry(String type, long serial, String dimension, BlockPos pos, long expireTick) {
		CompoundTag entry = new CompoundTag();
		entry.putString("type", type);
		entry.putLong("serial", serial);
		entry.putString("dimension", dimension);
		entry.putLong("pos", pos.asLong());
		entry.putLong("expireTick", expireTick);
		return entry;
	}
}
