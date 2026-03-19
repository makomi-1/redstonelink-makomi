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
		setField(target, "arbitrationPriority", 2);

		boolean accepted = invokeAcceptByPriority(target, 1);
		assertFalse(accepted);
		assertEquals(2, getIntField(target, "arbitrationPriority"));
	}

	/**
	 * 更高优先级到来时应重置同 tick 的低优先级合并缓存。
	 */
	@Test
	void acceptByPriorityShouldResetTickMergeCachesOnUpgrade() {
		TestTargetEntity target = createTarget();
		setField(target, "arbitrationPriority", 1);
		setField(target, "tickResolvedInitialized", true);
		setField(target, "toggleMergeInitialized", true);
		setField(target, "toggleMergeParity", true);

		boolean accepted = invokeAcceptByPriority(target, 3);
		assertTrue(accepted);
		assertEquals(3, getIntField(target, "arbitrationPriority"));
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
	 * 运行态生效模式应严格遵循 SYNC > PULSE > TOGGLE > NONE。
	 */
	@Test
	void getEffectiveModeShouldFollowPriority() {
		TestTargetEntity target = createTarget();
		setField(target, "syncSignalMaxStrength", 8);
		setField(target, "pulseUntilGameTime", 20L);
		setField(target, "toggleState", true);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.SYNC, target.getEffectiveMode());

		setField(target, "syncSignalMaxStrength", 0);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.PULSE, target.getEffectiveMode());

		setField(target, "pulseUntilGameTime", 0L);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.TOGGLE, target.getEffectiveMode());

		setField(target, "toggleState", false);
		assertEquals(ActivatableTargetBlockEntity.EffectiveMode.NONE, target.getEffectiveMode());
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

	private static boolean invokeAcceptByPriority(TestTargetEntity target, int priority) {
		return (Boolean) invoke(target, "acceptByPriority", new Class<?>[] { int.class }, new Object[] { priority });
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
