package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PlacedLinkFilterSavedData 稳定契约测试。
 */
@Tag("stable-core")
class PlacedLinkFilterSavedDataTest {
	/**
	 * 已放置过滤器应按放置态参与查询，不再因为邻居输入为 0 而整体失效。
	 */
	@Test
	void collectFiltersShouldStillReturnPlacedFilterWhenNeighborSignalIsZero() {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		BlockPos filterPos = new BlockPos(0, 64, 0);
		LinkFilterConfigSnapshot configSnapshot = new LinkFilterConfigSnapshot(
			"",
			LinkFilterNodeSetMode.DISABLED,
			LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
			15,
			LinkFilterSignalMode.UPPER_BOUND
		);

		assertFalse(data.upsert(null, Level.OVERWORLD, filterPos, configSnapshot, 0));
		assertFalse(data.upsert(LinkFilterKind.SEND, null, filterPos, configSnapshot, 0));
		assertFalse(data.upsert(LinkFilterKind.SEND, Level.OVERWORLD, null, configSnapshot, 0));
		assertTrue(data.upsert(LinkFilterKind.SEND, Level.OVERWORLD, filterPos, configSnapshot, 0));
		assertFalse(data.upsert(LinkFilterKind.SEND, Level.OVERWORLD, filterPos, configSnapshot, 0));

		List<LinkFilterRuleEvaluator.FilterRuntimeView> filters = data.collectFilters(
			Level.OVERWORLD,
			new BlockPos(8, 64, 0),
			LinkFilterKind.SEND
		);

		assertEquals(1, filters.size());
		assertEquals(0, filters.getFirst().neighborSignalStrength());
		assertFalse(LinkFilterRuleEvaluator.allows(filters, 1L, 1));
		assertTrue(LinkFilterRuleEvaluator.allows(filters, 1L, 0));

		assertTrue(data.remove(Level.OVERWORLD, LinkFilterKind.SEND, filterPos));
		assertFalse(data.remove(Level.OVERWORLD, LinkFilterKind.SEND, filterPos));
		assertTrue(data.collectFilters(Level.OVERWORLD, new BlockPos(8, 64, 0), LinkFilterKind.SEND).isEmpty());
	}

	/**
	 * 无采样恢复路径应保留旧的邻居输入快照，避免 `clearRemoved()` 把最近一次阈值误清零。
	 */
	@Test
	void upsertPreservingNeighborSignalShouldKeepPreviousNeighborSnapshot() {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		BlockPos filterPos = new BlockPos(10, 70, 10);

		assertTrue(
			data.upsert(
				LinkFilterKind.SEND,
				Level.OVERWORLD,
				filterPos,
				new LinkFilterConfigSnapshot(
					"",
					LinkFilterNodeSetMode.DISABLED,
					LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
					15,
					LinkFilterSignalMode.UPPER_BOUND
				),
				11
			)
		);

		assertTrue(
			data.upsertPreservingNeighborSignal(
				LinkFilterKind.SEND,
				Level.OVERWORLD,
				filterPos,
				new LinkFilterConfigSnapshot(
					"1/3",
					LinkFilterNodeSetMode.WHITELIST,
					LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
					2,
					LinkFilterSignalMode.LOWER_BOUND
				)
			)
		);

		PlacedLinkFilterSavedData.FilterEntry updatedEntry = data.entriesSnapshot().getFirst();
		assertEquals(11, updatedEntry.neighborSignalStrength());
		assertTrue(updatedEntry.usesNeighborSignalThreshold());
		assertEquals(Set.of(1L, 3L), updatedEntry.serials());
		assertEquals(LinkFilterNodeSetMode.WHITELIST, updatedEntry.configSnapshot().nodeSetMode());
		assertEquals(LinkFilterSignalMode.LOWER_BOUND, updatedEntry.configSnapshot().signalMode());
	}

	/**
	 * 保存与读取应保留 canonical kind/dimension/pos/config/neighborSignal 字段，并恢复查询能力。
	 */
	@Test
	void saveAndLoadShouldKeepCanonicalFieldsAndQueryability() throws Exception {
		PlacedLinkFilterSavedData data = new PlacedLinkFilterSavedData();
		assertTrue(
			data.upsert(
				LinkFilterKind.SEND,
				Level.OVERWORLD,
				new BlockPos(4, 70, 4),
				new LinkFilterConfigSnapshot(
					"",
					LinkFilterNodeSetMode.DISABLED,
					LinkFilterSignalThresholdSource.FIXED_INPUT,
					15,
					LinkFilterSignalMode.DISABLED
				),
				3
			)
		);
		assertTrue(
			data.upsert(
				LinkFilterKind.RECEIVE,
				Level.NETHER,
				new BlockPos(32, 70, 32),
				new LinkFilterConfigSnapshot(
					"3/7/7",
					LinkFilterNodeSetMode.WHITELIST,
					LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
					2,
					LinkFilterSignalMode.LOWER_BOUND
				),
				6
			)
		);

		CompoundTag root = data.save(new CompoundTag(), null);
		ListTag entries = root.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
		assertEquals(2, entries.size());

		CompoundTag receiveEntry = findEntry(entries, "receive", Level.NETHER);
		assertNotNull(receiveEntry);
		assertEquals(new BlockPos(32, 70, 32).asLong(), receiveEntry.getLong("pos"));
		assertEquals("3/7/7", receiveEntry.getString("serialExpression"));
		assertEquals("whitelist", receiveEntry.getString("nodeSetMode"));
		assertEquals("neighbor_max_input", receiveEntry.getString("signalThresholdSource"));
		assertEquals("lower_bound", receiveEntry.getString("signalMode"));
		assertEquals(6, receiveEntry.getInt("neighborSignalStrength"));

		PlacedLinkFilterSavedData loaded = invokeLoad(root);
		List<LinkFilterRuleEvaluator.FilterRuntimeView> receiveFilters = loaded.collectFilters(
			Level.NETHER,
			new BlockPos(24, 70, 32),
			LinkFilterKind.RECEIVE
		);

		assertEquals(1, receiveFilters.size());
		assertEquals(Set.of(3L, 7L), receiveFilters.getFirst().serials());
		assertEquals(6, receiveFilters.getFirst().neighborSignalStrength());
		assertTrue(LinkFilterRuleEvaluator.allows(receiveFilters, 3L, 6));
		assertFalse(LinkFilterRuleEvaluator.allows(receiveFilters, 9L, 6));
	}

	/**
	 * 读取时应忽略非法维度和非法 kind 条目。
	 */
	@Test
	void loadShouldIgnoreInvalidDimensionAndKindEntries() throws Exception {
		CompoundTag root = new CompoundTag();
		ListTag entries = new ListTag();
		entries.add(entry("minecraft:overworld", "send", new BlockPos(1, 2, 3), "", "disabled", "fixed_input", 15, "disabled", 0));
		entries.add(entry("invalid::dimension", "send", new BlockPos(1, 2, 3), "", "disabled", "fixed_input", 15, "disabled", 0));
		entries.add(entry("minecraft:overworld", "unknown", new BlockPos(1, 2, 3), "", "disabled", "fixed_input", 15, "disabled", 0));
		root.put("entries", entries);

		PlacedLinkFilterSavedData loaded = invokeLoad(root);
		assertEquals(1, loaded.entriesSnapshot().size());
		assertEquals(
			1,
			loaded.collectFilters(Level.OVERWORLD, new BlockPos(1, 2, 3), LinkFilterKind.SEND).size()
		);
	}

	private static PlacedLinkFilterSavedData invokeLoad(CompoundTag root) throws Exception {
		Method loadMethod = PlacedLinkFilterSavedData.class.getDeclaredMethod(
			"load",
			CompoundTag.class,
			HolderLookup.Provider.class
		);
		loadMethod.setAccessible(true);
		return (PlacedLinkFilterSavedData) loadMethod.invoke(null, root, null);
	}

	private static CompoundTag findEntry(ListTag entries, String kind, ResourceKey<Level> dimension) {
		for (net.minecraft.nbt.Tag element : entries) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			if (kind.equals(entryTag.getString("kind")) && dimension.location().toString().equals(entryTag.getString("dimension"))) {
				return entryTag;
			}
		}
		return null;
	}

	private static CompoundTag entry(
		String dimension,
		String kind,
		BlockPos pos,
		String serialExpression,
		String nodeSetMode,
		String signalThresholdSource,
		int fixedSignalThreshold,
		String signalMode,
		int neighborSignalStrength
	) {
		CompoundTag entry = new CompoundTag();
		entry.putString("dimension", dimension);
		entry.putString("kind", kind);
		entry.putLong("pos", pos.asLong());
		entry.putString("serialExpression", serialExpression);
		entry.putString("nodeSetMode", nodeSetMode);
		entry.putString("signalThresholdSource", signalThresholdSource);
		entry.putInt("fixedSignalThreshold", fixedSignalThreshold);
		entry.putString("signalMode", signalMode);
		entry.putInt("neighborSignalStrength", neighborSignalStrength);
		return entry;
	}
}
