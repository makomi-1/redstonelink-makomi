package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.testsupport.TestMinecraftSupport;
import com.makomi.util.SerialParseUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 区块激活器即时副作用计划测试。
 */
@Tag("stable-core")
class ChunkActivatorImmediateEffectServiceTest {
	static {
		TestMinecraftSupport.bootstrapMinecraft();
	}

	/**
	 * core/resident 节点集从 [1,2] 切到 [2,3] 时，只应把新增的 3 视为 resident bootstrap 目标。
	 */
	@Test
	void buildPlanShouldOnlyBootstrapNewResidentCoreSerials() {
		PlacedChunkActivatorSavedData.ActivatorEntry previousEntry = activatorEntry(
			LinkNodeType.CORE,
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("1/2", ChunkActivatorMode.RESIDENT),
			true
		);
		PlacedChunkActivatorSavedData.ActivatorEntry nextEntry = activatorEntry(
			LinkNodeType.CORE,
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("2/3", ChunkActivatorMode.RESIDENT),
			true
		);

		ChunkActivatorImmediateEffectService.ImmediateEffectPlan plan = ChunkActivatorImmediateEffectService.buildPlan(
			previousEntry,
			nextEntry
		);

		assertEquals(Set.of(), plan.residentTriggerSources());
		assertEquals(Set.of(3L), plan.residentCores());
		assertEquals(Set.of(), plan.replayCores());
		assertEquals(Set.of(), plan.replayTriggerSources());
	}

	/**
	 * triggerSource/resident 节点集新增成员时，只应进入 resident bootstrap，而不补发当前真值。
	 */
	@Test
	void buildPlanShouldOnlyBootstrapNewResidentTriggerSourceMembers() {
		PlacedChunkActivatorSavedData.ActivatorEntry previousEntry = activatorEntry(
			LinkNodeType.TRIGGER_SOURCE,
			new ChunkActivatorConfigSnapshot("11", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			true
		);
		PlacedChunkActivatorSavedData.ActivatorEntry nextEntry = activatorEntry(
			LinkNodeType.TRIGGER_SOURCE,
			new ChunkActivatorConfigSnapshot("11/12", ChunkActivatorMode.RESIDENT),
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			true
		);

		ChunkActivatorImmediateEffectService.ImmediateEffectPlan plan = ChunkActivatorImmediateEffectService.buildPlan(
			previousEntry,
			nextEntry
		);

		assertEquals(Set.of(12L), plan.residentTriggerSources());
		assertEquals(Set.of(), plan.residentCores());
		assertEquals(Set.of(), plan.replayCores());
		assertEquals(Set.of(), plan.replayTriggerSources());
	}

	/**
	 * triggerSource/force_load 节点集新增成员时，应进入“补发当前真值”路径，而不贡献 resident。
	 */
	@Test
	void buildPlanShouldReplayOnlyNewForceLoadTriggerSourceMembers() {
		PlacedChunkActivatorSavedData.ActivatorEntry previousEntry = activatorEntry(
			LinkNodeType.TRIGGER_SOURCE,
			new ChunkActivatorConfigSnapshot("31", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			true
		);
		PlacedChunkActivatorSavedData.ActivatorEntry nextEntry = activatorEntry(
			LinkNodeType.TRIGGER_SOURCE,
			new ChunkActivatorConfigSnapshot("31/32", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			true
		);

		ChunkActivatorImmediateEffectService.ImmediateEffectPlan plan = ChunkActivatorImmediateEffectService.buildPlan(
			previousEntry,
			nextEntry
		);

		assertEquals(Set.of(), plan.residentTriggerSources());
		assertEquals(Set.of(), plan.residentCores());
		assertEquals(Set.of(), plan.replayCores());
		assertEquals(Set.of(32L), plan.replayTriggerSources());
	}

	/**
	 * force_load core 新增成员时，应进入“定向 replay 到新 core”路径，而不是 resident 或 triggerSource replay。
	 */
	@Test
	void buildPlanShouldReplayOnlyNewForceLoadCoreMembers() {
		PlacedChunkActivatorSavedData.ActivatorEntry previousEntry = activatorEntry(
			LinkNodeType.CORE,
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("21", ChunkActivatorMode.FORCE_LOAD),
			true
		);
		PlacedChunkActivatorSavedData.ActivatorEntry nextEntry = activatorEntry(
			LinkNodeType.CORE,
			new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD),
			new ChunkActivatorConfigSnapshot("21/22", ChunkActivatorMode.FORCE_LOAD),
			true
		);

		ChunkActivatorImmediateEffectService.ImmediateEffectPlan plan = ChunkActivatorImmediateEffectService.buildPlan(
			previousEntry,
			nextEntry
		);

		assertEquals(Set.of(), plan.residentTriggerSources());
		assertEquals(Set.of(), plan.residentCores());
		assertEquals(Set.of(22L), plan.replayCores());
		assertEquals(Set.of(), plan.replayTriggerSources());
	}

	private static PlacedChunkActivatorSavedData.ActivatorEntry activatorEntry(
		LinkNodeType activeType,
		ChunkActivatorConfigSnapshot triggerSourceConfig,
		ChunkActivatorConfigSnapshot coreConfig,
		boolean active
	) {
		try {
			ChunkActivatorConfigStateSnapshot snapshot = new ChunkActivatorConfigStateSnapshot(activeType, triggerSourceConfig, coreConfig);
			Object entryKey = new BlockPosActivatorKey(Level.OVERWORLD, new BlockPos(8, 64, 8)).asEntryKey();
			Class<?> entryType = Class.forName("com.makomi.data.PlacedChunkActivatorSavedData$ActivatorEntry");
			java.lang.reflect.Constructor<?> constructor = entryType.getDeclaredConstructor(
				Class.forName("com.makomi.data.PlacedChunkActivatorSavedData$ActivatorEntryKey"),
				ChunkActivatorConfigStateSnapshot.class,
				Set.class,
				Set.class,
				String.class,
				boolean.class
			);
			constructor.setAccessible(true);
			return (PlacedChunkActivatorSavedData.ActivatorEntry) constructor.newInstance(
				entryKey,
				snapshot,
				parseSerials(triggerSourceConfig.serialExpression()),
				parseSerials(coreConfig.serialExpression()),
				"bench",
				active
			);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("failed to construct activator entry", ex);
		}
	}

	private static Set<Long> parseSerials(String expression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			expression,
			PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE
		);
		return parseResult.orderedTargets().isEmpty() ? Set.of() : Set.copyOf(parseResult.orderedTargets());
	}

	/**
	 * 测试夹具：用反射隔离私有 entry key 类型构造。
	 */
	private record BlockPosActivatorKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos pos) {
		private Object asEntryKey() {
			try {
				Class<?> keyType = Class.forName("com.makomi.data.PlacedChunkActivatorSavedData$ActivatorEntryKey");
				java.lang.reflect.Constructor<?> constructor = keyType.getDeclaredConstructor(
					net.minecraft.resources.ResourceKey.class,
					BlockPos.class
				);
				constructor.setAccessible(true);
				return constructor.newInstance(dimension, pos);
			} catch (ReflectiveOperationException ex) {
				throw new IllegalStateException("failed to construct activator entry key", ex);
			}
		}
	}
}
