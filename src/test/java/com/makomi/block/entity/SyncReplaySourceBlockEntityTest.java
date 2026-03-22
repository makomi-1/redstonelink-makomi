package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.data.LinkNodeType;
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

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends LinkButtonBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends LinkButtonBlockEntity>) type;
	}

	/**
	 * 最小测试实体：仅复用 sync replay 基类持久化，不依赖真实注册表。
	 */
	private static final class TestSyncReplayEntity extends SyncReplaySourceBlockEntity {
		private TestSyncReplayEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		private void saveForTest(CompoundTag tag) {
			saveAdditional(tag, null);
		}

		private void loadForTest(CompoundTag tag) {
			loadAdditional(tag, null);
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
