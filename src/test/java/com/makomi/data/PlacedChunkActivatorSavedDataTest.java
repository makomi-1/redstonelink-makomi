package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PlacedChunkActivatorSavedData 稳定契约测试。
 */
@Tag("stable-core")
class PlacedChunkActivatorSavedDataTest {
	/**
	 * 激活态区块激活器应按当前作用类型聚合强加载/resident 贡献，
	 * 且切换到另一种作用类型时不能丢失未生效侧配置。
	 */
	@Test
	void upsertShouldAggregateActiveContributionsByActiveTypeAndRetainInactiveConfig() {
		PlacedChunkActivatorSavedData data = new PlacedChunkActivatorSavedData();
		BlockPos activatorPos = new BlockPos(1, 64, 1);
		ChunkActivatorConfigStateSnapshot triggerSourceActiveSnapshot = new ChunkActivatorConfigStateSnapshot(
			LinkNodeType.TRIGGER_SOURCE,
			new ChunkActivatorConfigSnapshot("7/8/8", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("31/32", ChunkActivatorMode.RESIDENT)
		);
		ChunkActivatorConfigStateSnapshot coreActiveSnapshot = triggerSourceActiveSnapshot.withActiveType(LinkNodeType.CORE);

		assertTrue(data.upsert(Level.OVERWORLD, activatorPos, triggerSourceActiveSnapshot, "force", true));
		assertTrue(data.containsActiveForceLoad(LinkNodeType.TRIGGER_SOURCE, 7L));
		assertTrue(data.containsActiveForceLoad(LinkNodeType.TRIGGER_SOURCE, 8L));
		assertFalse(data.containsActiveResident(LinkNodeType.TRIGGER_SOURCE, 7L));
		assertFalse(data.containsActiveForceLoad(LinkNodeType.CORE, 31L));
		assertEquals(0L, data.residentStateVersion());
		assertFalse(data.hasResidents());

		assertTrue(data.upsert(Level.OVERWORLD, activatorPos, coreActiveSnapshot, "core", true));
		assertFalse(data.containsActiveForceLoad(LinkNodeType.TRIGGER_SOURCE, 7L));
		assertFalse(data.containsActiveForceLoad(LinkNodeType.TRIGGER_SOURCE, 8L));
		assertTrue(data.containsActiveForceLoad(LinkNodeType.CORE, 31L));
		assertTrue(data.containsActiveForceLoad(LinkNodeType.CORE, 32L));
		assertTrue(data.containsActiveResident(LinkNodeType.CORE, 31L));
		assertTrue(data.containsActiveResident(LinkNodeType.CORE, 32L));
		assertEquals(1L, data.residentStateVersion());
		assertTrue(data.hasResidents());
		assertEquals(
			"7/8/8",
			data.findEntry(Level.OVERWORLD, activatorPos).orElseThrow().configStateSnapshot().triggerSourceConfig().serialExpression()
		);
		assertEquals(
			"31/32",
			data.findEntry(Level.OVERWORLD, activatorPos).orElseThrow().configStateSnapshot().coreConfig().serialExpression()
		);

		assertTrue(data.upsert(Level.OVERWORLD, activatorPos, coreActiveSnapshot, "core-updated", true));
		assertEquals(1L, data.residentStateVersion());

		assertTrue(data.remove(Level.OVERWORLD, activatorPos));
		assertEquals(2L, data.residentStateVersion());
		assertFalse(data.containsActiveResident(LinkNodeType.CORE, 31L));
		assertFalse(data.containsActiveResident(LinkNodeType.CORE, 32L));
		assertFalse(data.containsActiveForceLoad(LinkNodeType.CORE, 31L));
		assertFalse(data.hasResidents());
	}

	/**
	 * 反序列化时应兼容旧单配置格式，并恢复新双配置格式的主表、索引与激活态贡献。
	 */
	@Test
	void loadShouldRestoreLegacyAndDualConfigEntries() throws Exception {
		CompoundTag root = new CompoundTag();
		ListTag entries = new ListTag();
		entries.add(legacyEntry("minecraft:overworld", BlockPos.ZERO, "11/12", "resident", "A", true));
		entries.add(dualEntry("minecraft:overworld", new BlockPos(4, 70, 4), "core", "", "force_load", "99", "resident", "", true));
		entries.add(legacyEntry("bad path", new BlockPos(8, 70, 8), "77", "resident", "", true));
		root.put("entries", entries);

		PlacedChunkActivatorSavedData loaded = invokeLoad(root);

		assertEquals(2, loaded.entriesSnapshot().size());
		assertTrue(loaded.containsActiveForceLoad(LinkNodeType.TRIGGER_SOURCE, 11L));
		assertTrue(loaded.containsActiveResident(LinkNodeType.TRIGGER_SOURCE, 11L));
		assertTrue(loaded.containsActiveForceLoad(LinkNodeType.TRIGGER_SOURCE, 12L));
		assertTrue(loaded.containsActiveResident(LinkNodeType.TRIGGER_SOURCE, 12L));
		assertTrue(loaded.containsActiveForceLoad(LinkNodeType.CORE, 99L));
		assertTrue(loaded.containsActiveResident(LinkNodeType.CORE, 99L));
		assertFalse(loaded.containsActiveForceLoad(LinkNodeType.TRIGGER_SOURCE, 99L));
		assertTrue(loaded.hasResidents());
	}

	private static CompoundTag legacyEntry(
		String dimension,
		BlockPos pos,
		String serialExpression,
		String mode,
		String displayAlias,
		boolean active
	) {
		CompoundTag entry = new CompoundTag();
		entry.putString("dimension", dimension);
		entry.putLong("pos", pos.asLong());
		entry.putString("serialExpression", serialExpression);
		entry.putString("mode", mode);
		if (!displayAlias.isBlank()) {
			entry.putString("displayAlias", displayAlias);
		}
		entry.putBoolean("active", active);
		return entry;
	}

	private static CompoundTag dualEntry(
		String dimension,
		BlockPos pos,
		String activeType,
		String triggerSourceSerialExpression,
		String triggerSourceMode,
		String coreSerialExpression,
		String coreMode,
		String displayAlias,
		boolean active
	) {
		CompoundTag entry = new CompoundTag();
		entry.putString("dimension", dimension);
		entry.putLong("pos", pos.asLong());
		entry.putString("activeType", activeType);
		entry.putString("triggerSourceSerialExpression", triggerSourceSerialExpression);
		entry.putString("triggerSourceMode", triggerSourceMode);
		entry.putString("coreSerialExpression", coreSerialExpression);
		entry.putString("coreMode", coreMode);
		if (!displayAlias.isBlank()) {
			entry.putString("displayAlias", displayAlias);
		}
		entry.putBoolean("active", active);
		return entry;
	}

	private static PlacedChunkActivatorSavedData invokeLoad(CompoundTag root) throws Exception {
		Method loadMethod = PlacedChunkActivatorSavedData.class.getDeclaredMethod(
			"load",
			CompoundTag.class,
			HolderLookup.Provider.class
		);
		loadMethod.setAccessible(true);
		return (PlacedChunkActivatorSavedData) loadMethod.invoke(null, root, null);
	}
}
