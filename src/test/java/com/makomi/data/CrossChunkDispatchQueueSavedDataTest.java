package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivationMode;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CrossChunkDispatchQueueSavedData 稳定契约测试。
 */
@Tag("stable-core")
class CrossChunkDispatchQueueSavedDataTest {
	/**
	 * upsert 同 key 应保留单条 pending，并分配单调 version。
	 */
	@Test
	void upsertPendingShouldAllocateMonotonicVersionByKey() {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			9L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);

		CrossChunkDispatchQueueSavedData.UpsertResult first = data.upsertPending(
			key,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			6,
			100L,
			0,
			200L
		);
		assertTrue(first.accepted());
		assertNotNull(first.entry());
		assertEquals(1L, first.entry().version());

		CrossChunkDispatchQueueSavedData.UpsertResult second = data.upsertPending(
			key,
			Level.OVERWORLD,
			new BlockPos(1, 64, 1),
			ActivationMode.TOGGLE,
			15,
			101L,
			0,
			220L
		);
		assertTrue(second.accepted());
		assertNotNull(second.entry());
		assertEquals(2L, second.entry().version());
		assertEquals(1, data.pendingSize());
	}

	/**
	 * 版本护栏应按 lastAcceptedVersion 拒绝旧版本。
	 */
	@Test
	void staleGuardShouldRejectAcceptedOrOlderVersion() {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			3L,
			LinkNodeType.CORE,
			11L,
			CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
		);

		assertFalse(data.isStaleByAcceptedVersion(key, 1L));
		assertTrue(data.markAccepted(key, 2L));
		assertTrue(data.isStaleByAcceptedVersion(key, 1L));
		assertTrue(data.isStaleByAcceptedVersion(key, 2L));
		assertFalse(data.isStaleByAcceptedVersion(key, 3L));
		assertFalse(data.markAccepted(key, 2L));
	}

	/**
	 * save/load 应保留 pending 与版本护栏状态。
	 */
	@Test
	void saveAndLoadShouldKeepPendingAndVersionGuard() throws Exception {
		CrossChunkDispatchQueueSavedData source = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			5L,
			LinkNodeType.CORE,
			15L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);
		CrossChunkDispatchQueueSavedData.UpsertResult upsert = source.upsertPending(
			key,
			Level.NETHER,
			new BlockPos(3, 70, 7),
			ActivationMode.TOGGLE,
			12,
			300L,
			0,
			360L
		);
		assertTrue(upsert.accepted());
		assertTrue(source.markAccepted(key, upsert.entry().version()));

		CompoundTag root = source.save(new CompoundTag(), null);
		CrossChunkDispatchQueueSavedData restored = invokeLoad(root);
		assertEquals(1, restored.pendingSize());
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry = restored.pendingEntriesSnapshot().getFirst();
		assertEquals(Level.NETHER, entry.dimension());
		assertEquals(new BlockPos(3, 70, 7), entry.pos());
		assertEquals(12, entry.syncSignalStrength());
		assertTrue(restored.isStaleByAcceptedVersion(key, upsert.entry().version()));
	}

	/**
	 * purgeExpired 应忽略旧版本索引，避免新版本条目被旧过期时间误删。
	 */
	@Test
	void purgeExpiredShouldSkipStaleExpireIndexForSameKey() {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			10L,
			LinkNodeType.CORE,
			20L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);
		CrossChunkDispatchQueueSavedData.UpsertResult first = data.upsertPending(
			key,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			5,
			0L,
			0,
			5L
		);
		assertTrue(first.accepted());
		CrossChunkDispatchQueueSavedData.UpsertResult second = data.upsertPending(
			key,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			9,
			1L,
			0,
			100L
		);
		assertTrue(second.accepted());
		assertEquals(0, data.purgeExpired(6L));
		assertEquals(1, data.pendingSize());
	}

	/**
	 * pendingEntriesSnapshot 在未变更时应复用缓存，变更后重建快照。
	 */
	@Test
	void pendingEntriesSnapshotShouldReuseCacheBeforeDirty() {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			LinkNodeType.CORE,
			2L,
			CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
		);
		data.upsertPending(key, Level.OVERWORLD, BlockPos.ZERO, ActivationMode.TOGGLE, 0, 0L, 0, 20L);
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> firstSnapshot = data.pendingEntriesSnapshot();
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> secondSnapshot = data.pendingEntriesSnapshot();
		assertSame(firstSnapshot, secondSnapshot);

		data.upsertPending(
			new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				3L,
				LinkNodeType.CORE,
				4L,
				CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
			),
			Level.OVERWORLD,
			new BlockPos(1, 64, 1),
			ActivationMode.TOGGLE,
			0,
			1L,
			0,
			20L
		);
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> thirdSnapshot = data.pendingEntriesSnapshot();
		assertEquals(2, thirdSnapshot.size());
	}

	/**
	 * 批量 upsert 应统一返回结果并写入有效条目。
	 */
	@Test
	void upsertPendingBatchShouldAcceptValidRequests() {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey validKey = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			8L,
			LinkNodeType.CORE,
			9L,
			CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
		);
		List<CrossChunkDispatchQueueSavedData.UpsertResult> results = data.upsertPendingBatch(
			List.of(
				new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
					validKey,
					Level.OVERWORLD,
					BlockPos.ZERO,
					ActivationMode.TOGGLE,
					0,
					2L,
					0,
					60L
				),
				new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
					new CrossChunkDispatchQueueSavedData.DispatchKey(
						LinkNodeType.TRIGGER_SOURCE,
						0L,
						LinkNodeType.CORE,
						11L,
						CrossChunkDispatchQueueSavedData.DispatchKind.ACTIVATION
					),
					Level.OVERWORLD,
					BlockPos.ZERO,
					ActivationMode.TOGGLE,
					0,
					2L,
					0,
					60L
				)
			)
		);

		assertEquals(2, results.size());
		assertTrue(results.get(0).accepted());
		assertFalse(results.get(1).accepted());
		assertEquals(1, data.pendingSize());
	}

	/**
	 * 批量 upsert 同 key 应按“本批最后有效请求”覆盖，并复用单次写入结果。
	 */
	@Test
	void upsertPendingBatchShouldCoalesceDuplicateKeyByLastValidRequest() {
		CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			LinkNodeType.TRIGGER_SOURCE,
			21L,
			LinkNodeType.CORE,
			31L,
			CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
		);
		List<CrossChunkDispatchQueueSavedData.UpsertResult> results = data.upsertPendingBatch(
			List.of(
				new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
					key,
					Level.OVERWORLD,
					new BlockPos(1, 64, 1),
					ActivationMode.TOGGLE,
					3,
					10L,
					0,
					200L
				),
				new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
					key,
					Level.OVERWORLD,
					new BlockPos(9, 70, 9),
					ActivationMode.TOGGLE,
					11,
					11L,
					0,
					220L
				)
			)
		);

		assertEquals(2, results.size());
		assertTrue(results.get(0).accepted());
		assertTrue(results.get(1).accepted());
		assertEquals(results.get(1).entry(), results.get(0).entry());
		assertEquals(1, data.pendingSize());
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry = data.pendingEntriesSnapshot().getFirst();
		assertEquals(new BlockPos(9, 70, 9), entry.pos());
		assertEquals(11, entry.syncSignalStrength());
	}

	private static CrossChunkDispatchQueueSavedData invokeLoad(CompoundTag root) throws Exception {
		Method loadMethod = CrossChunkDispatchQueueSavedData.class.getDeclaredMethod(
			"load",
			CompoundTag.class,
			HolderLookup.Provider.class
		);
		loadMethod.setAccessible(true);
		return (CrossChunkDispatchQueueSavedData) loadMethod.invoke(null, root, null);
	}
}
