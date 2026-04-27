package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.data.LinkNodeType;
import com.makomi.testsupport.TestMinecraftSupport;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * sync 来源 replay 快照持久化测试。
 */
@Tag("stable-core")
class SyncReplaySourceBlockEntityTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * sync replay 来源基类应持久化最近一次 sync replay 快照。
	 */
	@Test
	void syncReplaySourceShouldPersistReplaySnapshot() {
		TestSyncReplayEntity source = new TestSyncReplayEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		source.recordReplaySyncSnapshot(12, EventMeta.of(123L, 0, 45L));

		CompoundTag tag = new CompoundTag();
		source.saveForTest(tag);

		TestSyncReplayEntity restored = new TestSyncReplayEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		restored.loadForTest(tag);

		SyncReplaySourceBlockEntity.ReplaySyncSnapshot snapshot = restored.replaySyncSnapshot().orElseThrow();
		assertEquals(12, snapshot.signalStrength());
		assertEquals(EventMeta.of(123L, 0, 45L), snapshot.eventMeta());
	}

	/**
	 * 未记录快照时不应伪造 replay 状态；记录后应可恢复 0 强度快照。
	 */
	@Test
	void syncReplaySourceShouldPersistRecordedZeroStrengthSnapshotOnly() {
		TestSyncReplayEntity fresh = new TestSyncReplayEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		assertTrue(fresh.replaySyncSnapshot().isEmpty());

		fresh.recordReplaySyncSnapshot(0, EventMeta.of(456L, 0, 78L));
		CompoundTag tag = new CompoundTag();
		fresh.saveForTest(tag);

		TestSyncReplayEntity restored = new TestSyncReplayEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		restored.loadForTest(tag);

		SyncReplaySourceBlockEntity.ReplaySyncSnapshot snapshot = restored.replaySyncSnapshot().orElseThrow();
		assertEquals(0, snapshot.signalStrength());
		assertEquals(EventMeta.of(456L, 0, 78L), snapshot.eventMeta());
	}

	/**
	 * 运行时 replay 快照应优先覆盖持久化快照，但不能写进 NBT。
	 */
	@Test
	void runtimeReplaySnapshotShouldOverridePersistentSnapshotWithoutPersisting() {
		TestSyncReplayEntity source = new TestSyncReplayEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		source.recordReplaySyncSnapshot(12, EventMeta.of(123L, 0, 45L));
		source.recordRuntimeReplaySyncSnapshot(3, EventMeta.of(222L, 0, 99L));

		SyncReplaySourceBlockEntity.ReplaySyncSnapshot runtimeSnapshot = source.replaySyncSnapshot().orElseThrow();
		assertEquals(3, runtimeSnapshot.signalStrength());
		assertEquals(EventMeta.of(222L, 0, 99L), runtimeSnapshot.eventMeta());

		CompoundTag tag = new CompoundTag();
		source.saveForTest(tag);

		TestSyncReplayEntity restored = new TestSyncReplayEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		restored.loadForTest(tag);

		SyncReplaySourceBlockEntity.ReplaySyncSnapshot restoredSnapshot = restored.replaySyncSnapshot().orElseThrow();
		assertEquals(12, restoredSnapshot.signalStrength());
		assertEquals(EventMeta.of(123L, 0, 45L), restoredSnapshot.eventMeta());
	}

	/**
	 * 清空运行时快照后，应回退到持久化快照。
	 */
	@Test
	void clearRuntimeReplaySnapshotShouldFallbackToPersistentSnapshot() {
		TestSyncReplayEntity source = new TestSyncReplayEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		source.recordReplaySyncSnapshot(9, EventMeta.of(50L, 0, 1L));
		source.recordRuntimeReplaySyncSnapshot(15, EventMeta.of(60L, 0, 2L));

		source.clearRuntimeReplaySyncSnapshot();

		SyncReplaySourceBlockEntity.ReplaySyncSnapshot snapshot = source.replaySyncSnapshot().orElseThrow();
		assertEquals(9, snapshot.signalStrength());
		assertEquals(EventMeta.of(50L, 0, 1L), snapshot.eventMeta());
	}

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends LinkTriggerSourceBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends LinkTriggerSourceBlockEntity>) type;
	}

	/**
	 * 最小测试实体：仅复用 sync replay 基类持久化，不依赖真实注册表。
	 */
	private static final class TestSyncReplayEntity extends SyncReplaySourceBlockEntity {
		private TestSyncReplayEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		private void saveForTest(CompoundTag tag) {
			tag.merge(TestMinecraftSupport.saveBlockEntityCustomOnly(this));
		}

		private void loadForTest(CompoundTag tag) {
			TestMinecraftSupport.loadBlockEntityCustomOnly(this, tag);
		}

		@Override
		protected LinkNodeType getNodeType() {
			return LinkNodeType.TRIGGER_SOURCE;
		}

		@Override
		protected LinkNodeType getTargetNodeType() {
			return LinkNodeType.CORE;
		}
	}
}
