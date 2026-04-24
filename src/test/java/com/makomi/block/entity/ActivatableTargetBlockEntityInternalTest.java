package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.config.RedstoneLinkConfigTestHelper;
import com.makomi.data.LinkNodeType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ActivatableTargetBlockEntity 内部仲裁与聚合逻辑测试。
 * <p>
 * 本测试聚焦纯逻辑分支，不依赖游戏场景 tick 执行。
 * </p>
 */
@Tag("stable-core")
class ActivatableTargetBlockEntityInternalTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 同步源强度聚合应按 sourceSerial + strength 语义稳定更新。
	 */
	@Test
	void updateSyncSignalStrengthShouldTrackMaxStrength() {
		TestTargetEntity target = createTarget();

		invokeUpdateSyncSignalStrength(target, 1L, 7);
		assertEquals(7, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(1L), getLongSetField(target, "syncSignalMaxSources"));

		// 同一 source 提升强度应更新 max。
		invokeUpdateSyncSignalStrength(target, 1L, 10);
		assertEquals(10, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(1L), getLongSetField(target, "syncSignalMaxSources"));

		// 第二来源更低强度不应改变 max。
		invokeUpdateSyncSignalStrength(target, 2L, 3);
		assertEquals(10, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(1L), getLongSetField(target, "syncSignalMaxSources"));

		// 同强度并列来源应全部进入 maxSources 集合。
		invokeUpdateSyncSignalStrength(target, 2L, 10);
		assertEquals(10, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(1L, 2L), getLongSetField(target, "syncSignalMaxSources"));

		// 移除最大来源后，应回落到剩余来源最大值。
		invokeUpdateSyncSignalStrength(target, 1L, 0);
		assertEquals(10, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(2L), getLongSetField(target, "syncSignalMaxSources"));

		// sourceSerial<=0 视为全量重置路径。
		invokeUpdateSyncSignalStrength(target, 0L, 0);
		assertEquals(0, getIntField(target, "syncSignalMaxStrength"));
		assertTrue(getLongSetField(target, "syncSignalMaxSources").isEmpty());
	}

	/**
	 * 低优先级请求不应覆盖当前仲裁优先级。
	 */
	@Test
	void acceptByPriorityShouldRejectLowerPriority() {
		TestTargetEntity target = createTarget();
		setField(target, "authorityTimeKey", ActivatableTargetBlockEntity.TimeKey.of(10L, 0));
		setField(target, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.PULSE);
		setField(target, "pulseUntilGameTime", 20L);
		setField(target, "arbitrationTimeKey", ActivatableTargetBlockEntity.TimeKey.of(10L, 0));
		setField(target, "arbitrationPriority", 2);

		boolean accepted = invokeAcceptByPriority(
			target,
			10L,
			0,
			1,
			ActivatableTargetBlockEntity.EffectiveMode.TOGGLE,
			0L
		);
		assertFalse(accepted);
		assertEquals(2, getIntField(target, "arbitrationPriority"));
	}

	/**
	 * 更高优先级到来时应重置同 tick 的低优先级合并缓存。
	 */
	@Test
	void acceptByPriorityShouldResetTickMergeCachesOnUpgrade() {
		TestTargetEntity target = createTarget();
		setField(target, "authorityTimeKey", ActivatableTargetBlockEntity.TimeKey.of(10L, 0));
		setField(target, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.TOGGLE);
		setField(target, "arbitrationTimeKey", ActivatableTargetBlockEntity.TimeKey.of(10L, 0));
		setField(target, "arbitrationPriority", 1);
		setField(target, "tickResolvedInitialized", true);
		setField(target, "toggleMergeInitialized", true);
		setField(target, "toggleMergeParity", true);

		boolean accepted = invokeAcceptByPriority(target, 10L, 0, 3, ActivatableTargetBlockEntity.EffectiveMode.SYNC, 1L);
		assertTrue(accepted);
		assertEquals(3, getIntField(target, "arbitrationPriority"));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, getField(target, "authorityMode"));
		assertFalse(getBooleanField(target, "tickResolvedInitialized"));
		assertFalse(getBooleanField(target, "toggleMergeInitialized"));
		assertFalse(getBooleanField(target, "toggleMergeParity"));
	}

	/**
	 * NBT 读写应保持结构真值（toggle/pulse）一致，并可重建派生态。
	 */
	@Test
	void saveAndLoadShouldPreserveActivationSnapshot() {
		TestTargetEntity source = createTarget();
		setField(source, "toggleState", false);
		setField(source, "toggleSnapshotRecorded", true);
		setField(source, "toggleEventTimeKey", ActivatableTargetBlockEntity.TimeKey.of(30L, 0));
		setField(source, "toggleEventSeq", 5L);
		setField(source, "configuredMode", ActivationMode.PULSE);
		setField(source, "pulseUntilGameTime", 40L);
		setField(source, "pulseEpoch", 2L);
		setField(source, "pulseResetArmed", true);
		setField(source, "pulseSnapshotRecorded", true);
		setField(source, "pulseEventTimeKey", ActivatableTargetBlockEntity.TimeKey.of(40L, 0));
		setField(source, "pulseEventSeq", 7L);
		setField(source, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.PULSE);
		setField(source, "authorityTimeKey", ActivatableTargetBlockEntity.TimeKey.of(40L, 0));
		setField(source, "authoritySeq", 7L);

		CompoundTag tag = new CompoundTag();
		source.saveForTest(tag);
		assertTrue(tag.contains("ConfiguredMode"));
		assertFalse(tag.contains("ActivationMode"));
		assertTrue(tag.contains("PulseEventRecorded"));
		assertTrue(tag.contains("ToggleEventRecorded"));
		assertFalse(tag.contains("PulseConcurrentEntries"));
		assertFalse(tag.contains("ToggleConcurrentEntries"));

		TestTargetEntity restored = createTarget();
		restored.loadForTest(tag);
		assertTrue(getBooleanField(restored, "active"));
		assertEquals(ActivationMode.PULSE, getField(restored, "configuredMode"));
		assertEquals(40L, getLongField(restored, "pulseUntilGameTime"));
		assertEquals(2L, getLongField(restored, "pulseEpoch"));
		assertTrue(getBooleanField(restored, "pulseResetArmed"));
		assertTrue(getBooleanField(restored, "pulseSnapshotRecorded"));
		assertEquals(ActivatableTargetBlockEntity.TimeKey.of(40L, 0), getField(restored, "pulseEventTimeKey"));
		assertEquals(7L, getLongField(restored, "pulseEventSeq"));
		assertTrue(getBooleanField(restored, "toggleSnapshotRecorded"));
		assertFalse(getBooleanField(restored, "toggleState"));
		assertEquals(ActivatableTargetBlockEntity.TimeKey.of(30L, 0), getField(restored, "toggleEventTimeKey"));
		assertEquals(5L, getLongField(restored, "toggleEventSeq"));
	}

	/**
	 * 落盘时只应保留真实持久化 SYNC 来源，不应把运行时模拟 SYNC 泄漏进存档。
	 */
	@Test
	void saveShouldExcludeRuntimeSimulatedSyncFromPersistentSnapshot() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 7, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.applyRuntimeSimulatedSyncSource(99L, 15, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));

		CompoundTag tag = new CompoundTag();
		target.saveForTest(tag);

		assertEquals(Map.of(1L, 7), readSyncStrengthsFromTag(tag));
		assertEquals(Set.of(1L), Set.copyOf(longArrayToBoxedSet(tag.getLongArray("SyncMaxSources"))));
	}

	/**
	 * 读档后应按当前派生态登记一次静默 blockstate 校正，而不是沿用旧缓存外显。
	 */
	@Test
	void loadShouldScheduleSilentBlockStateSyncFromDerivedState() {
		TestTargetEntity target = createTarget();
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("Active", true);
		tag.putInt("ResolvedOutputPower", 15);

		target.loadForTest(tag);

		assertFalse(getBooleanField(target, "active"));
		assertTrue(getBooleanField(target, "pendingLoadBlockStateSync"));

		target.consumePendingLoadBlockStateSync();

		assertFalse(getBooleanField(target, "pendingLoadBlockStateSync"));
		assertEquals(1, target.getSilentBlockStateSyncCount());
		assertFalse(target.getLastSilentBlockStateSyncActive());

		// 只应同步一次，避免重复读档后再次无条件写回。
		target.consumePendingLoadBlockStateSync();
		assertEquals(1, target.getSilentBlockStateSyncCount());
	}

	/**
	 * 非法 ActivationMode 文本应回退 TOGGLE。
	 */
	@Test
	void loadShouldFallbackToToggleWhenActivationModeInvalid() {
		TestTargetEntity target = createTarget();
		CompoundTag tag = new CompoundTag();
		tag.putString("ConfiguredMode", "invalid_mode");

		target.loadForTest(tag);
		assertEquals(ActivationMode.TOGGLE, getField(target, "configuredMode"));
	}

	/**
	 * 运行态生效模式应由 authority 决定，并与结构真值一致。
	 */
	@Test
	void getEffectiveModeShouldFollowPriority() {
		TestTargetEntity target = createTarget();
		setField(target, "syncSignalMaxStrength", 8);
		setField(target, "pulseUntilGameTime", 20L);
		setField(target, "toggleState", true);
		setField(target, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.SYNC);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());

		setField(target, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.PULSE);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());

		setField(target, "syncSignalMaxStrength", 0);
		setField(target, "pulseUntilGameTime", 0L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());

		setField(target, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.TOGGLE);
		setField(target, "toggleSnapshotRecorded", true);
		setField(target, "toggleState", true);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		setField(target, "toggleState", false);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
	}

	/**
	 * 后到 toggle 应按时间键覆盖先到 sync，并清掉更早的旧 sync 真值。
	 */
	@Test
	void laterToggleShouldClearEarlierSyncSnapshot() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());

		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertEquals(0, getIntField(target, "syncSignalMaxStrength"));
		assertTrue(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());
	}

	/**
	 * 同时间键冲突应按固定优先级处理：SYNC > PULSE > TOGGLE。
	 */
	@Test
	void sameTimeKeyShouldUseFixedModePriority() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		target.syncBySource(1L, 9, ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());
	}

	/**
	 * 更晚的 `sync` 应清掉更早事件快照，移除后不再回露旧 `toggle`。
	 */
	@Test
	void laterSyncShouldClearEarlierToggleSnapshot() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		target.syncBySource(2L, 15, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));
		assertFalse(getBooleanField(target, "toggleState"));

		target.syncBySource(2L, 0, ActivatableTargetBlockEntity.EventMeta.of(12L, 0, 3L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));
		assertFalse(getBooleanField(target, "active"));
	}

	/**
	 * 更晚 tick 的较低强度 `sync` 应覆盖更早 tick 的较高强度 `sync`。
	 */
	@Test
	void laterSyncShouldOverrideEarlierHigherStrengthWithinSyncOnly() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(15, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(1L), getLongSetField(target, "syncSignalMaxSources"));

		target.syncBySource(2L, 7, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());
		assertEquals(7, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(2L), getLongSetField(target, "syncSignalMaxSources"));
		assertEquals(Set.of(ActivatableTargetBlockEntity.TimeKey.of(11L, 0)), getConcurrentBucketField(target, "syncConcurrentBuckets").keySet());
	}

	/**
	 * 最新 tick 的 `sync` 被移除后，不应回露更早 tick 的旧 `sync`。
	 */
	@Test
	void removingLatestSyncShouldNotRevealEarlierTickSync() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.syncBySource(2L, 7, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));

		target.syncBySource(2L, 0, ActivatableTargetBlockEntity.EventMeta.of(12L, 0, 3L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertEquals(0, getIntField(target, "syncSignalMaxStrength"));
		assertTrue(getLongSetField(target, "syncSignalMaxSources").isEmpty());
		assertTrue(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());
	}

	/**
	 * 更晚的 `pulse` 会覆盖更早 `toggle`，脉冲结束后保持其自身回落结果。
	 */
	@Test
	void laterPulseShouldClearEarlierToggleSnapshot() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		target.triggerBySource(2L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		expirePulseWindow(target, 11L, 2L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "active"));
	}

	/**
	 * 更晚的 `pulse` 在跨 tick 覆盖更早 `sync` 后，回落时不应再回露旧 `sync`。
	 */
	@Test
	void laterPulseShouldClearEarlierSyncTruth() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());

		target.triggerBySource(2L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());
		assertTrue(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());

		expirePulseWindow(target, 11L, 2L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "active"));
	}

	/**
	 * 更晚的 `toggle` 在跨 tick 覆盖更早 `sync` 后，应直接淘汰旧 `sync` 真值。
	 */
	@Test
	void laterToggleShouldClearEarlierSyncTruth() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());

		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertTrue(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());
		assertTrue(getBooleanField(target, "toggleSnapshotRecorded"));
		assertFalse(getBooleanField(target, "toggleState"));
		assertEquals(0, invokeResolveDerivedOutputPowerFromTruth(target));
	}

	/**
	 * 同 tick 的 `toggle` 仍受 `SYNC > PULSE > TOGGLE` 优先级约束，不应误清同 tick `sync`。
	 */
	@Test
	void sameTimeToggleShouldNotClearExistingSyncTruth() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());

		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());
		assertFalse(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));
		assertEquals(15, invokeResolveDerivedOutputPowerFromTruth(target));
	}

	/**
	 * 同一来源 later toggle 应保留为最新的“关态 toggle 结果”，而不是退回 NONE。
	 */
	@Test
	void laterToggleFromSameSourceShouldPersistLatestOffResult() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertTrue(getBooleanField(target, "toggleSnapshotRecorded"));
		assertFalse(getBooleanField(target, "active"));
	}

	/**
	 * `sync` 清掉旧事件后，后续新的 `toggle` 应从清空后的事件域重新写入结果。
	 */
	@Test
	void laterToggleAfterSyncClearShouldWriteNewResult() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		target.syncBySource(2L, 15, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		target.syncBySource(2L, 0, ActivatableTargetBlockEntity.EventMeta.of(12L, 0, 3L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertEquals(0, invokeResolveDerivedOutputPowerFromTruth(target));

		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(13L, 0, 4L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertTrue(getBooleanField(target, "toggleSnapshotRecorded"));
		assertTrue(invokeResolveDerivedOutputPowerFromTruth(target) > 0);
		assertTrue(getBooleanField(target, "toggleState"));
	}

	/**
	 * pulse 下落窗口内，later toggle 仍应按时间键立即覆盖 earlier pulse。
	 */
	@Test
	void laterToggleShouldOverrideEarlierPulseImmediately() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());

		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "pulseSnapshotRecorded"));
		assertTrue(getBooleanField(target, "toggleSnapshotRecorded"));
		assertEquals(0, invokeResolveDerivedOutputPowerFromTruth(target));

		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleState"));
	}

	/**
	 * 同时间粒度 `pulse` 与 `toggle` 并发时，仅保留更高优先级的 `pulse`。
	 */
	@Test
	void sameTimePulseThenToggleShouldDiscardToggleFallback() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 2L));

		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());
		assertTrue(getBooleanField(target, "pulseSnapshotRecorded"));
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		expirePulseWindow(target, 10L, 2L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertEquals(0, invokeResolveDerivedOutputPowerFromTruth(target));
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));
	}

	/**
	 * 同时间粒度 `sync` 会清掉事件域持久化，移除后不再回露旧事件。
	 */
	@Test
	void sameTimeSyncReplayShouldClearPersistedEvents() {
		TestTargetEntity target = createTarget();
		target.triggerBySource(2L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.triggerBySource(3L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 2L));

		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());
		assertTrue(getBooleanField(target, "pulseSnapshotRecorded"));
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 3L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "pulseSnapshotRecorded"));
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		target.applyDispatchDelta(
			ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
			ActivatableTargetBlockEntity.DeltaAction.REMOVE,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			ActivationMode.TOGGLE,
			0,
			ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 4L)
		);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "active"));
	}

	/**
	 * 重复两轮同时间粒度 `pulse+toggle` 时，每轮都只保留 `pulse`，回落后保持关闭。
	 */
	@Test
	void repeatedSameTimePulseThenToggleShouldStayOffAfterPulseEnds() {
		TestTargetEntity target = createTarget();

		target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 2L));
		expirePulseWindow(target, 10L, 2L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "active"));
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 3L));
		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 4L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		expirePulseWindow(target, 20L, 4L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "active"));
	}

	/**
	 * pulse 回落后应给批窗口内 delayed pulse 留出生效余量，避免被 stale guard 误拒。
	 */
	@Test
	void pulseExpireFallbackShouldAcceptDelayedPulseWithinDispatchBatchWindow() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.dispatch.batchWindowTicks", "2");
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(properties, () -> {
			TestTargetEntity target = createTarget();
			target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));

			ActivatableTargetBlockEntity.TimeKey fallbackTimeKey = invokeResolvePulseExpireFallbackTimeKey(target, 12L);
			expirePulseWindow(target, fallbackTimeKey.tick(), 1L);
			target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));

			assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());
			assertTrue(getBooleanField(target, "pulseSnapshotRecorded"));
		});
	}

	/**
	 * pulse 回落后对窗口外旧事件仍应保持拒绝，避免 stale event 复活目标。
	 */
	@Test
	void pulseExpireFallbackShouldRejectPulseOutsideDispatchBatchWindow() throws Exception {
		Properties properties = new Properties();
		properties.setProperty("crosschunk.dispatch.batchWindowTicks", "2");
		RedstoneLinkConfigTestHelper.withCrossChunkConfig(properties, () -> {
			TestTargetEntity target = createTarget();
			target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));

			ActivatableTargetBlockEntity.TimeKey fallbackTimeKey = invokeResolvePulseExpireFallbackTimeKey(target, 12L);
			expirePulseWindow(target, fallbackTimeKey.tick(), 1L);
			target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(9L, 0, 2L));

			assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
			assertFalse(getBooleanField(target, "pulseSnapshotRecorded"));
		});
	}

	/**
	 * TOGGLE 基准应取当前解析目标状态，而不是仅在有 level 时才刷新的 active 缓存。
	 */
	@Test
	void toggleMergeShouldUseResolvedTargetStateAsBaseState() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		setField(target, "active", false);

		invoke(target, "applyToggleMerged", new Class<?>[] {}, new Object[] {});
		assertFalse(getBooleanField(target, "toggleState"));
	}

	/**
	 * 已移除旧缓存回放：缺失结构真值时，不再依赖 Active/默认功率回退。
	 */
	@Test
	void loadShouldNotFallbackDefaultPowerWhenResolvedPowerMissing() {
		TestTargetEntity target = createTarget();
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("Active", true);

		target.loadForTest(tag);
		assertFalse(getBooleanField(target, "active"));
		assertEquals(0, getIntField(target, "resolvedOutputPower"));
	}

	/**
	 * SYNC 来源表与并列最大来源应可持久化并在读档后重建。
	 */
	@Test
	void saveAndLoadShouldPreserveSyncTruthSnapshot() {
		TestTargetEntity source = createTarget();
		invokeUpdateSyncSignalStrength(source, 1L, 12);
		invokeUpdateSyncSignalStrength(source, 2L, 12);
		invokeUpdateSyncSignalStrength(source, 3L, 4);
		setField(source, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.SYNC);
		setField(source, "authorityTimeKey", ActivatableTargetBlockEntity.TimeKey.of(12L, 0));
		setField(source, "authoritySeq", 3L);

		CompoundTag tag = new CompoundTag();
		source.saveForTest(tag);

		TestTargetEntity restored = createTarget();
		restored.loadForTest(tag);
		assertEquals(12, getIntField(restored, "syncSignalMaxStrength"));
		assertEquals(Set.of(1L, 2L), getLongSetField(restored, "syncSignalMaxSources"));
		assertEquals(12, getIntField(restored, "resolvedOutputPower"));
		assertTrue(getBooleanField(restored, "active"));
		assertEquals(3, getLongIntMapField(restored, "syncSignalStrengthBySource").size());
	}

	/**
	 * 落盘与读档都应只保留最新 tick 的 `sync` 帧，不再回带更早 tick 的高强度结果。
	 */
	@Test
	void saveAndLoadShouldPreserveLatestSyncFrameOnly() {
		TestTargetEntity source = createTarget();
		source.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		source.syncBySource(2L, 7, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));

		CompoundTag tag = new CompoundTag();
		source.saveForTest(tag);
		assertEquals(Map.of(2L, 7), readSyncStrengthsFromTag(tag));
		assertEquals(Set.of(2L), Set.copyOf(longArrayToBoxedSet(tag.getLongArray("SyncMaxSources"))));

		TestTargetEntity restored = createTarget();
		restored.loadForTest(tag);
		assertEquals(7, getIntField(restored, "syncSignalMaxStrength"));
		assertEquals(Set.of(2L), getLongSetField(restored, "syncSignalMaxSources"));
		assertEquals(7, getIntField(restored, "resolvedOutputPower"));
		assertTrue(getBooleanField(restored, "active"));
	}

	/**
	 * bucket 变化即使不改变当前解析输出，也应独立触发 setChanged，确保结构化真值可及时落盘。
	 */
	@Test
	void bucketMutationShouldSetChangedEvenWhenResolvedOutputStaysSame() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.resetSetChangedCount();

		target.syncBySource(2L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 2L));
		assertEquals(1, target.getSetChangedCount());
		assertEquals(15, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(1L, 2L), getLongSetField(target, "syncSignalMaxSources"));

		target.resetSetChangedCount();
		target.syncBySource(1L, 0, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 3L));
		assertEquals(1, target.getSetChangedCount());
		assertEquals(15, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(2L), getLongSetField(target, "syncSignalMaxSources"));
	}

	/**
	 * 批提交应把同一批的多来源 sync 合并成一次最终重算与一次脏标记。
	 */
	@Test
	void applyDispatchBatchShouldCommitMultipleSyncUpdatesOnce() {
		TestTargetEntity target = createTarget();

		target.applyDispatchBatch(
			List.of(
				new ActivatableTargetBlockEntity.DispatchBatchEntry(
					ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
					ActivatableTargetBlockEntity.DeltaAction.UPSERT,
					LinkNodeType.TRIGGER_SOURCE,
					1L,
					ActivationMode.TOGGLE,
					12,
					ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L)
				),
				new ActivatableTargetBlockEntity.DispatchBatchEntry(
					ActivatableTargetBlockEntity.DeltaKind.SYNC_SIGNAL,
					ActivatableTargetBlockEntity.DeltaAction.UPSERT,
					LinkNodeType.TRIGGER_SOURCE,
					2L,
					ActivationMode.TOGGLE,
					15,
					ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 2L)
				)
			)
		);

		assertEquals(1, target.getSetChangedCount());
		assertEquals(15, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(Set.of(2L), getLongSetField(target, "syncSignalMaxSources"));
		assertEquals(2, getLongIntMapField(target, "syncSignalStrengthBySource").size());
	}

	/**
	 * 批提交中的同 tick `pulse + toggle` 只保留 `pulse`，不再记录 `toggle` fallback。
	 */
	@Test
	void applyDispatchBatchShouldKeepPulseWinnerAndDiscardToggleFallbackWithinSingleCommit() {
		TestTargetEntity target = createTarget();

		target.applyDispatchBatch(
			List.of(
				new ActivatableTargetBlockEntity.DispatchBatchEntry(
					ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
					ActivatableTargetBlockEntity.DeltaAction.UPSERT,
					LinkNodeType.TRIGGER_SOURCE,
					1L,
					ActivationMode.PULSE,
					0,
					ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L)
				),
				new ActivatableTargetBlockEntity.DispatchBatchEntry(
					ActivatableTargetBlockEntity.DeltaKind.ACTIVATION,
					ActivatableTargetBlockEntity.DeltaAction.UPSERT,
					LinkNodeType.TRIGGER_SOURCE,
					2L,
					ActivationMode.TOGGLE,
					0,
					ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 2L)
				)
			)
		);

		assertEquals(1, target.getSetChangedCount());
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());
		assertTrue(getBooleanField(target, "pulseSnapshotRecorded"));
		assertFalse(getBooleanField(target, "toggleSnapshotRecorded"));

		expirePulseWindow(target, 10L, 2L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "active"));
	}

	/**
	 * later `pulse/toggle` 已淘汰旧 sync 后，后续 invalidation 不应再重复制造脏写。
	 */
	@Test
	void applyDispatchBatchShouldClearHistoricalSyncContributionOnce() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(12L, 0, 3L));
		target.resetSetChangedCount();

		target.applyDispatchBatch(
			List.of(
				new ActivatableTargetBlockEntity.DispatchBatchEntry(
					ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
					ActivatableTargetBlockEntity.DeltaAction.REMOVE,
					LinkNodeType.TRIGGER_SOURCE,
					1L,
					ActivationMode.TOGGLE,
					0,
					ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 4L)
				)
			)
		);

		assertEquals(0, target.getSetChangedCount());
		assertTrue(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());
		assertFalse(getBooleanField(target, "pulseSnapshotRecorded"));
		assertTrue(getBooleanField(target, "toggleSnapshotRecorded"));
		assertEquals(0, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleState"));
		assertEquals(0, invokeResolveDerivedOutputPowerFromTruth(target));
	}

	/**
	 * triggerSource 区块卸载失效只应剔除 sync 贡献，不应清掉 pulse/toggle 事件快照。
	 */
	@Test
	void chunkUnloadInvalidationShouldOnlyRemoveSyncContribution() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.triggerBySource(1L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		target.triggerBySource(1L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(12L, 0, 3L));

		target.applyDispatchDelta(
			ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_CHUNK_UNLOAD_INVALIDATION,
			ActivatableTargetBlockEntity.DeltaAction.REMOVE,
			LinkNodeType.TRIGGER_SOURCE,
			1L,
			ActivationMode.TOGGLE,
			0,
			ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 4L)
		);

		assertTrue(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());
		assertFalse(getBooleanField(target, "pulseSnapshotRecorded"));
		assertTrue(getBooleanField(target, "toggleSnapshotRecorded"));
		assertEquals(0, getIntField(target, "syncSignalMaxStrength"));
		assertEquals(0, invokeResolveDerivedOutputPowerFromTruth(target));
	}

	/**
	 * triggerSource 其它失效只应剔除同来源的 sync 贡献，不应回滚 pulse/toggle 事件快照。
	 */
	@Test
	void triggerSourceInvalidationShouldOnlyRemoveSyncContribution() {
		TestTargetEntity target = createTarget();
		target.syncBySource(2L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		target.triggerBySource(2L, ActivationMode.PULSE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(12L, 0, 3L));

		target.applyDispatchDelta(
			ActivatableTargetBlockEntity.DeltaKind.TRIGGER_SOURCE_INVALIDATION,
			ActivatableTargetBlockEntity.DeltaAction.REMOVE,
			LinkNodeType.TRIGGER_SOURCE,
			2L,
			ActivationMode.TOGGLE,
			0,
			ActivatableTargetBlockEntity.EventMeta.of(20L, 0, 4L)
		);

		assertTrue(getConcurrentBucketField(target, "syncConcurrentBuckets").isEmpty());
		assertFalse(getBooleanField(target, "pulseSnapshotRecorded"));
		assertTrue(getBooleanField(target, "toggleSnapshotRecorded"));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		assertFalse(getBooleanField(target, "toggleState"));
		assertEquals(0, invokeResolveDerivedOutputPowerFromTruth(target));
	}

	private static TestTargetEntity createTarget() {
		return new TestTargetEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
	}

	private static void invokeUpdateSyncSignalStrength(TestTargetEntity target, long sourceSerial, int signalStrength) {
		invoke(
			target,
			"updateSyncSignalStrength",
			new Class<?>[] { long.class, int.class },
			new Object[] { sourceSerial, signalStrength }
		);
	}

	private static boolean invokeAcceptByPriority(
		TestTargetEntity target,
		long tick,
		int slot,
		int priority,
		ActivatableTargetBlockEntity.EffectiveMode mode,
		long seq
	) {
		return (Boolean) invoke(
			target,
			"acceptByPriority",
			new Class<?>[] {
				ActivatableTargetBlockEntity.TimeKey.class,
				int.class,
				ActivatableTargetBlockEntity.EffectiveMode.class,
				long.class
			},
			new Object[] { ActivatableTargetBlockEntity.TimeKey.of(tick, slot), priority, mode, seq }
		);
	}

	private static ActivatableTargetBlockEntity.TimeKey invokeResolvePulseExpireFallbackTimeKey(
		TestTargetEntity target,
		long nowTick
	) {
		return (ActivatableTargetBlockEntity.TimeKey) invoke(
			target,
			"resolvePulseExpireFallbackTimeKey",
			new Class<?>[] { long.class },
			new Object[] { nowTick }
		);
	}

	private static int invokeResolveDerivedOutputPowerFromTruth(TestTargetEntity target) {
		return (Integer) invoke(target, "resolveDerivedOutputPowerFromTruth", new Class<?>[] {}, new Object[] {});
	}

	/**
	 * 测试辅助：直接清空 pulse 并发桶，模拟脉冲窗口结束后的回落重算。
	 */
	private static void expirePulseWindow(TestTargetEntity target, long fallbackTick, long fallbackSeq) {
		getConcurrentBucketField(target, "pulseConcurrentBuckets").clear();
		setField(target, "pulseUntilGameTime", 0L);
		setField(target, "pulseResetArmed", false);
		setField(target, "pulseSnapshotRecorded", false);
		invoke(target, "recomputeToggleTruthFromConcurrentBuckets", new Class<?>[] {}, new Object[] {});
		invoke(
			target,
			"recomputeAuthorityFromConcurrentBuckets",
			new Class<?>[] { ActivatableTargetBlockEntity.TimeKey.class, long.class },
			new Object[] { ActivatableTargetBlockEntity.TimeKey.of(fallbackTick, 0), fallbackSeq }
		);
		invoke(target, "applyDerivedStateFromTruth", new Class<?>[] {}, new Object[] {});
	}

	private static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
		try {
			Method method = ActivatableTargetBlockEntity.class.getDeclaredMethod(methodName, paramTypes);
			method.setAccessible(true);
			return method.invoke(target, args);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
			throw new IllegalStateException("failed to invoke method: " + methodName, ex);
		}
	}

	private static Object getField(Object target, String fieldName) {
		try {
			Field field = resolveDeclaredField(target, fieldName);
			field.setAccessible(true);
			return field.get(resolveFieldOwner(target, fieldName));
		} catch (NoSuchFieldException | IllegalAccessException ex) {
			throw new IllegalStateException("failed to read field: " + fieldName, ex);
		}
	}

	private static int getIntField(Object target, String fieldName) {
		return (Integer) getField(target, fieldName);
	}

	private static long getLongField(Object target, String fieldName) {
		return (Long) getField(target, fieldName);
	}

	private static boolean getBooleanField(Object target, String fieldName) {
		return (Boolean) getField(target, fieldName);
	}

	@SuppressWarnings("unchecked")
	private static Set<Long> getLongSetField(Object target, String fieldName) {
		return (Set<Long>) getField(target, fieldName);
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, Integer> getLongIntMapField(Object target, String fieldName) {
		return (Map<Long, Integer>) getField(target, fieldName);
	}

	private static Map<?, ?> getConcurrentBucketField(Object target, String fieldName) {
		return (Map<?, ?>) getField(target, fieldName);
	}

	private static Map<Long, Integer> readSyncStrengthsFromTag(CompoundTag tag) {
		Map<Long, Integer> result = new LinkedHashMap<>();
		if (tag == null || !tag.contains("SyncSourceStrengths", net.minecraft.nbt.Tag.TAG_LIST)) {
			return result;
		}
		ListTag sourceList = tag.getList("SyncSourceStrengths", net.minecraft.nbt.Tag.TAG_COMPOUND);
		for (int index = 0; index < sourceList.size(); index++) {
			CompoundTag sourceTag = sourceList.getCompound(index);
			result.put(sourceTag.getLong("Serial"), sourceTag.getInt("Strength"));
		}
		return result;
	}

	private static Set<Long> longArrayToBoxedSet(long[] values) {
		Set<Long> result = new java.util.LinkedHashSet<>();
		if (values == null) {
			return result;
		}
		for (long value : values) {
			result.add(value);
		}
		return result;
	}

	private static void setField(Object target, String fieldName, Object value) {
		try {
			Field field = resolveDeclaredField(target, fieldName);
			field.setAccessible(true);
			field.set(resolveFieldOwner(target, fieldName), value);
		} catch (NoSuchFieldException | IllegalAccessException ex) {
			throw new IllegalStateException("failed to set field: " + fieldName, ex);
		}
	}

	/**
	 * 按“主类优先，组件兜底”解析测试字段，避免测试绑死主类私有布局。
	 */
	private static Field resolveDeclaredField(Object target, String fieldName) throws NoSuchFieldException {
		Class<?> type = resolveFieldOwner(target, fieldName).getClass();
		while (type != null) {
			try {
				return type.getDeclaredField(fieldName);
			} catch (NoSuchFieldException ignored) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(fieldName);
	}

	/**
	 * 将旧字段名路由到职责拆分后的组件实例。
	 */
	private static Object resolveFieldOwner(Object target, String fieldName) {
		if (!(target instanceof ActivatableTargetBlockEntity activatableTarget)) {
			return target;
		}
		return switch (fieldName) {
			case "authorityTimeKey",
				"authorityMode",
				"authoritySeq",
				"arbitrationTimeKey",
				"arbitrationPriority",
				"toggleMergeInitialized",
				"toggleMergeBaseActive",
				"toggleMergeParity" -> activatableTarget.internalArbitrationComponent();
			case "pulseUntilGameTime",
				"pulseEpoch",
				"toggleState",
				"pulseResetArmed",
				"pulseSnapshotRecorded",
				"pulseEventTimeKey",
				"pulseEventSeq",
				"toggleSnapshotRecorded",
				"toggleEventTimeKey",
				"toggleEventSeq",
				"syncSignalStrengthBySource",
				"syncSignalMaxStrength",
				"syncSignalMaxSources",
				"syncConcurrentBuckets",
				"runtimeSimulatedSyncConcurrentBuckets",
				"pulseConcurrentBuckets",
				"toggleConcurrentBuckets",
				"toggleConcurrentCount",
				"toggleFrameStartContributors",
				"toggleSourcesTouchedInCurrentFrame" -> activatableTarget.internalConcurrentComponent();
			case "resolvedOutputPower",
				"pendingLoadBlockStateSync",
				"tickResolvedInitialized",
				"tickResolvedTimeKey",
				"tickResolvedState",
				"tickResolvedPower",
				"fanoutResolvedInitialized",
				"fanoutResolvedTimeKey",
				"fanoutResolvedState",
				"fanoutResolvedPower" -> activatableTarget.internalObservationComponent();
			default -> activatableTarget;
		};
	}

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends PairableNodeBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends PairableNodeBlockEntity>) type;
	}

	/**
	 * 测试用最小目标实体：仅提供基类要求的抽象实现。
	 */
	private static final class TestTargetEntity extends ActivatableTargetBlockEntity {
		private int setChangedCount;
		private int silentBlockStateSyncCount;
		private boolean lastSilentBlockStateSyncActive;

		private TestTargetEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		@Override
		public void setChanged() {
			super.setChanged();
			setChangedCount++;
		}

		@Override
		protected void onActiveChanged(boolean active) {}

		@Override
		protected void syncBlockStateFromDerivedState(boolean active) {
			silentBlockStateSyncCount++;
			lastSilentBlockStateSyncActive = active;
		}

		@Override
		protected boolean shouldQueueLoadBlockStateSync(boolean active) {
			return true;
		}

		@Override
		protected void schedulePulseReset(int pulseTicks) {}

		@Override
		protected LinkNodeType getNodeType() {
			return LinkNodeType.CORE;
		}

		private void saveForTest(CompoundTag tag) {
			saveAdditional(tag, null);
		}

		private void loadForTest(CompoundTag tag) {
			loadAdditional(tag, null);
		}

		private int getSetChangedCount() {
			return setChangedCount;
		}

		private void resetSetChangedCount() {
			setChangedCount = 0;
		}

		private int getSilentBlockStateSyncCount() {
			return silentBlockStateSyncCount;
		}

		private boolean getLastSilentBlockStateSyncActive() {
			return lastSilentBlockStateSyncActive;
		}
	}
}
