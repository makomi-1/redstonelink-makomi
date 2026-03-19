package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
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
		setField(source, "toggleState", true);
		setField(source, "configuredMode", ActivationMode.PULSE);
		setField(source, "pulseUntilGameTime", 40L);
		setField(source, "pulseEpoch", 2L);
		setField(source, "pulseResetArmed", true);
		setField(source, "authorityMode", ActivatableTargetBlockEntity.EffectiveMode.PULSE);
		setField(source, "authorityTimeKey", ActivatableTargetBlockEntity.TimeKey.of(40L, 0));
		setField(source, "authoritySeq", 7L);

		CompoundTag tag = new CompoundTag();
		source.saveForTest(tag);
		assertTrue(tag.contains("ConfiguredMode"));
		assertFalse(tag.contains("ActivationMode"));

		TestTargetEntity restored = createTarget();
		restored.loadForTest(tag);
		assertTrue(getBooleanField(restored, "active"));
		assertEquals(ActivationMode.PULSE, getField(restored, "configuredMode"));
		assertEquals(40L, getLongField(restored, "pulseUntilGameTime"));
		assertEquals(2L, getLongField(restored, "pulseEpoch"));
		assertTrue(getBooleanField(restored, "pulseResetArmed"));
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
		setField(target, "toggleState", true);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		setField(target, "toggleState", false);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
	}

	/**
	 * 后到事件应按时间键覆盖先到事件，避免 SYNC 常驻压制后续触发。
	 */
	@Test
	void laterToggleShouldOverrideEarlierSyncByTimeKey() {
		TestTargetEntity target = createTarget();
		target.syncBySource(1L, 15, ActivatableTargetBlockEntity.EventMeta.of(10L, 0, 1L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());

		target.triggerBySource(2L, ActivationMode.TOGGLE, ActivatableTargetBlockEntity.EventMeta.of(11L, 0, 2L));
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());
		// 结构真值保留：仅运行态 authority 切换。
		assertEquals(15, getIntField(target, "syncSignalMaxStrength"));
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
	 * TOGGLE 基准应取当前 active：当外显为亮且 toggleState 为 false 时，首次 TOGGLE 应直接回落。
	 */
	@Test
	void toggleMergeShouldUseActiveAsBaseState() {
		TestTargetEntity target = createTarget();
		setField(target, "active", true);
		setField(target, "toggleState", false);

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
			Field field = ActivatableTargetBlockEntity.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			return field.get(target);
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

	private static void setField(Object target, String fieldName, Object value) {
		try {
			Field field = ActivatableTargetBlockEntity.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (NoSuchFieldException | IllegalAccessException ex) {
			throw new IllegalStateException("failed to set field: " + fieldName, ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends PairableNodeBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends PairableNodeBlockEntity>) type;
	}

	/**
	 * 测试用最小目标实体：仅提供基类要求的抽象实现。
	 */
	private static final class TestTargetEntity extends ActivatableTargetBlockEntity {
		private TestTargetEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		@Override
		protected void onActiveChanged(boolean active) {}

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
	}
}
