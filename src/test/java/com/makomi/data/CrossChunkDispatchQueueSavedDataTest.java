package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivationMode;
import com.makomi.config.RedstoneLinkConfigTestHelper;
import com.makomi.testsupport.TestMinecraftSupport;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CrossChunkDispatchQueueSavedData 稳定契约测试。
 */
@Tag("stable-core")
class CrossChunkDispatchQueueSavedDataTest {
	static {
		TestMinecraftSupport.bootstrapMinecraft();
	}

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
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
			CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT
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
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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

		CompoundTag root = TestMinecraftSupport.saveSavedData(source);
		CrossChunkDispatchQueueSavedData restored = invokeLoad(root);
		assertEquals(1, restored.pendingSize());
		CrossChunkDispatchQueueSavedData.PendingDispatchEntry entry = restored.pendingEntriesSnapshot().getFirst();
		assertEquals(Level.NETHER, entry.dimension());
		assertEquals(new BlockPos(3, 70, 7), entry.pos());
		assertEquals(12, entry.syncSignalStrength());
		assertTrue(restored.isStaleByAcceptedVersion(key, upsert.entry().version()));
	}

	/**
	 * 已移除的旧版 ACTIVATION 持久项读档时应直接丢弃。
	 */
	@Test
	void loadShouldDropRemovedLegacyActivationEntry() throws Exception {
		CompoundTag root = new CompoundTag();
		ListTag pendingEntries = new ListTag();
		CompoundTag legacyEntry = new CompoundTag();
		legacyEntry.putString("sourceType", "triggerSource");
		legacyEntry.putLong("sourceSerial", 7L);
		legacyEntry.putString("targetType", "core");
		legacyEntry.putLong("targetSerial", 17L);
		legacyEntry.putString("dispatchKind", "ACTIVATION");
		legacyEntry.putString("dispatchAction", "REMOVE");
		legacyEntry.putString("dimension", TestMinecraftSupport.dimensionId(Level.OVERWORLD));
		legacyEntry.putLong("pos", BlockPos.ZERO.asLong());
		legacyEntry.putString("activationMode", "TOGGLE");
		legacyEntry.putInt("syncSignalStrength", 0);
		legacyEntry.putLong("enqueueTick", 10L);
		legacyEntry.putInt("enqueueSlot", 0);
		legacyEntry.putLong("expireTick", 40L);
		legacyEntry.putLong("version", 3L);
		pendingEntries.add(legacyEntry);
		root.put("pendingEntries", pendingEntries);
		root.put("acceptedVersions", new ListTag());
		root.put("issuedVersions", new ListTag());

		CrossChunkDispatchQueueSavedData restored = invokeLoad(root);
		assertEquals(0, restored.pendingSize());
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
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
			CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT
		);
		data.upsertPending(
			key,
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			0,
			0L,
			0,
			20L
		);
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> firstSnapshot = data.pendingEntriesSnapshot();
		List<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> secondSnapshot = data.pendingEntriesSnapshot();
		assertSame(firstSnapshot, secondSnapshot);

		data.upsertPending(
			new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				3L,
				LinkNodeType.CORE,
				4L,
				CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT
			),
			CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
			CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT
		);
		List<CrossChunkDispatchQueueSavedData.UpsertResult> results = data.upsertPendingBatch(
			List.of(
				new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
					validKey,
					CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
						CrossChunkDispatchQueueSavedData.DispatchKind.TOGGLE_EVENT
					),
					CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
					CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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
					CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
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

	/**
	 * 队列达到容量上限后，应拒绝新增 key 的单条入队。
	 */
	@Test
	void upsertPendingShouldRejectNewKeyWhenQueueCapacityReached() throws Exception {
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(queueCapacityProperties(1), () -> {
			CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
			CrossChunkDispatchQueueSavedData.UpsertResult first = data.upsertPending(
				new CrossChunkDispatchQueueSavedData.DispatchKey(
					LinkNodeType.TRIGGER_SOURCE,
					31L,
					LinkNodeType.CORE,
					41L,
					CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
				),
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				BlockPos.ZERO,
				ActivationMode.TOGGLE,
				15,
				0L,
				0,
				200L
			);
			CrossChunkDispatchQueueSavedData.UpsertResult second = data.upsertPending(
				new CrossChunkDispatchQueueSavedData.DispatchKey(
					LinkNodeType.TRIGGER_SOURCE,
					32L,
					LinkNodeType.CORE,
					42L,
					CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
				),
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				new BlockPos(1, 64, 1),
				ActivationMode.TOGGLE,
				7,
				1L,
				0,
				200L
			);
			assertTrue(first.accepted());
			assertFalse(second.accepted());
			assertEquals(1, data.pendingSize());
		});
	}

	/**
	 * 队列达到容量上限后，已存在 key 的覆盖更新仍应允许。
	 */
	@Test
	void upsertPendingShouldAllowOverwriteWhenQueueCapacityReached() throws Exception {
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(queueCapacityProperties(1), () -> {
			CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
			CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				51L,
				LinkNodeType.CORE,
				61L,
				CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
			);
			CrossChunkDispatchQueueSavedData.UpsertResult first = data.upsertPending(
				key,
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				BlockPos.ZERO,
				ActivationMode.TOGGLE,
				3,
				0L,
				0,
				200L
			);
			CrossChunkDispatchQueueSavedData.UpsertResult second = data.upsertPending(
				key,
				CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
				Level.OVERWORLD,
				new BlockPos(2, 64, 2),
				ActivationMode.TOGGLE,
				9,
				1L,
				0,
				220L
			);
			assertTrue(first.accepted());
			assertTrue(second.accepted());
			assertEquals(1, data.pendingSize());
			assertEquals(2L, second.entry().version());
			assertEquals(new BlockPos(2, 64, 2), data.pendingEntriesSnapshot().getFirst().pos());
		});
	}

	/**
	 * 批量 upsert 应只接受剩余容量内的新增 key，并允许已有 key 覆盖。
	 */
	@Test
	void upsertPendingBatchShouldRejectNewKeysBeyondRemainingCapacity() throws Exception {
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(queueCapacityProperties(2), () -> {
			CrossChunkDispatchQueueSavedData data = new CrossChunkDispatchQueueSavedData();
			CrossChunkDispatchQueueSavedData.DispatchKey existingKey = new CrossChunkDispatchQueueSavedData.DispatchKey(
				LinkNodeType.TRIGGER_SOURCE,
				71L,
				LinkNodeType.CORE,
				81L,
				CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
			);
			assertTrue(
				data.upsertPending(
					existingKey,
					CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
					Level.OVERWORLD,
					BlockPos.ZERO,
					ActivationMode.TOGGLE,
					1,
					0L,
					0,
					200L
				).accepted()
			);

			List<CrossChunkDispatchQueueSavedData.UpsertResult> results = data.upsertPendingBatch(
				List.of(
					new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
						existingKey,
						CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
						Level.OVERWORLD,
						new BlockPos(3, 64, 3),
						ActivationMode.TOGGLE,
						5,
						2L,
						0,
						240L
					),
					new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
						new CrossChunkDispatchQueueSavedData.DispatchKey(
							LinkNodeType.TRIGGER_SOURCE,
							72L,
							LinkNodeType.CORE,
							82L,
							CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
						),
						CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
						Level.OVERWORLD,
						new BlockPos(4, 64, 4),
						ActivationMode.TOGGLE,
						6,
						2L,
						0,
						240L
					),
					new CrossChunkDispatchQueueSavedData.PendingUpsertRequest(
						new CrossChunkDispatchQueueSavedData.DispatchKey(
							LinkNodeType.TRIGGER_SOURCE,
							73L,
							LinkNodeType.CORE,
							83L,
							CrossChunkDispatchQueueSavedData.DispatchKind.SYNC_SIGNAL
						),
						CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT,
						Level.OVERWORLD,
						new BlockPos(5, 64, 5),
						ActivationMode.TOGGLE,
						7,
						2L,
						0,
						240L
					)
				)
			);

			assertEquals(3, results.size());
			assertTrue(results.get(0).accepted());
			assertTrue(results.get(1).accepted());
			assertFalse(results.get(2).accepted());
			assertEquals(2, data.pendingSize());
		});
	}

	private static Properties queueCapacityProperties(int maxPendingEntries) {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.queue.maxPendingEntries", Integer.toString(maxPendingEntries));
		return properties;
	}

	private static CrossChunkDispatchQueueSavedData invokeLoad(CompoundTag root) throws Exception {
		return TestMinecraftSupport.invokePrivateStaticLoad(
			CrossChunkDispatchQueueSavedData.class,
			CrossChunkDispatchQueueSavedData.class,
			root
		);
	}
}
