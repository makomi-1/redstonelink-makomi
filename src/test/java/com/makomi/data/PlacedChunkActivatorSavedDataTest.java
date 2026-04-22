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
	 * 激活态区块激活器应按模式聚合强加载/resident 贡献，
	 * 且 alias-only 更新不应推动 resident 版本。
	 */
	@Test
	void upsertShouldAggregateActiveContributionsByMode() {
		PlacedChunkActivatorSavedData data = new PlacedChunkActivatorSavedData();
		BlockPos forceLoadPos = new BlockPos(1, 64, 1);
		BlockPos residentPos = new BlockPos(2, 64, 2);

		assertTrue(
			data.upsert(
				Level.OVERWORLD,
				forceLoadPos,
				new ChunkActivatorConfigSnapshot("7/8/8", ChunkActivatorMode.FORCE_LOAD),
				"force",
				true
			)
		);
		assertTrue(data.containsActiveForceLoadTriggerSource(7L));
		assertTrue(data.containsActiveForceLoadTriggerSource(8L));
		assertFalse(data.containsActiveResidentTriggerSource(7L));
		assertEquals(0L, data.residentStateVersion());
		assertFalse(data.hasResidents());

		assertTrue(
			data.upsert(
				Level.OVERWORLD,
				residentPos,
				new ChunkActivatorConfigSnapshot("8/9", ChunkActivatorMode.RESIDENT),
				"resident",
				true
			)
		);
		assertTrue(data.containsActiveForceLoadTriggerSource(9L));
		assertTrue(data.containsActiveResidentTriggerSource(8L));
		assertTrue(data.containsActiveResidentTriggerSource(9L));
		assertEquals(1L, data.residentStateVersion());
		assertTrue(data.hasResidents());

		assertTrue(
			data.upsert(
				Level.OVERWORLD,
				residentPos,
				new ChunkActivatorConfigSnapshot("8/9", ChunkActivatorMode.RESIDENT),
				"resident-updated",
				true
			)
		);
		assertEquals(1L, data.residentStateVersion());
		assertTrue(data.containsActiveResidentTriggerSource(8L));
		assertTrue(data.containsActiveResidentTriggerSource(9L));

		assertTrue(data.remove(Level.OVERWORLD, residentPos));
		assertEquals(2L, data.residentStateVersion());
		assertFalse(data.containsActiveResidentTriggerSource(8L));
		assertFalse(data.containsActiveResidentTriggerSource(9L));
		assertTrue(data.containsActiveForceLoadTriggerSource(8L));
		assertFalse(data.containsActiveForceLoadTriggerSource(9L));
		assertFalse(data.hasResidents());
	}

	/**
	 * 反序列化时应恢复主表、索引与激活态贡献，并忽略非法维度。
	 */
	@Test
	void loadShouldRestoreEntriesIndexAndContributions() throws Exception {
		CompoundTag root = new CompoundTag();
		ListTag entries = new ListTag();
		entries.add(entry("minecraft:overworld", BlockPos.ZERO, "11/12", "resident", "A", true));
		entries.add(entry("minecraft:overworld", new BlockPos(4, 70, 4), "99", "force_load", "", false));
		entries.add(entry("bad path", new BlockPos(8, 70, 8), "77", "resident", "", true));
		root.put("entries", entries);

		PlacedChunkActivatorSavedData loaded = invokeLoad(root);

		assertEquals(2, loaded.entriesSnapshot().size());
		assertTrue(loaded.containsActiveForceLoadTriggerSource(11L));
		assertTrue(loaded.containsActiveForceLoadTriggerSource(12L));
		assertTrue(loaded.containsActiveResidentTriggerSource(11L));
		assertTrue(loaded.containsActiveResidentTriggerSource(12L));
		assertFalse(loaded.containsActiveForceLoadTriggerSource(99L));
		assertFalse(loaded.containsActiveResidentTriggerSource(99L));
		assertTrue(loaded.hasResidents());
	}

	private static CompoundTag entry(
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
