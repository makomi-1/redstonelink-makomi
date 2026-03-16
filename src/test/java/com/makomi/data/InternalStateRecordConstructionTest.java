package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.makomi.block.entity.ActivationMode;
import java.lang.reflect.Constructor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * data 包内部状态记录体构造契约测试。
 */
@Tag("stable-core")
class InternalStateRecordConstructionTest {
	/**
	 * LinkNodeRetireEvents 内部记录体应可构造并保持字段值。
	 */
	@Test
	void retireEventInternalRecordsShouldConstructWithExpectedValues() throws Exception {
		Class<?> pendingKeyClass = Class.forName("com.makomi.data.LinkNodeRetireEvents$PendingKey");
		Constructor<?> pendingKeyConstructor = pendingKeyClass.getDeclaredConstructor(LinkNodeType.class, long.class);
		pendingKeyConstructor.setAccessible(true);
		Object pendingKey = pendingKeyConstructor.newInstance(LinkNodeType.TRIGGER_SOURCE, 10L);
		assertNotNull(pendingKey);

		Class<?> pendingEntryClass = Class.forName("com.makomi.data.LinkNodeRetireEvents$PendingEntry");
		Constructor<?> pendingEntryConstructor = pendingEntryClass.getDeclaredConstructor(
			pendingKeyClass,
			Class.forName("net.minecraft.resources.ResourceKey"),
			BlockPos.class,
			long.class
		);
		pendingEntryConstructor.setAccessible(true);
		Object pendingEntry = pendingEntryConstructor.newInstance(pendingKey, Level.OVERWORLD, BlockPos.ZERO, 40L);
		assertNotNull(pendingEntry);
		assertEquals(40L, pendingEntryClass.getDeclaredMethod("expireTick").invoke(pendingEntry));

		Class<?> pendingRetireStateClass = Class.forName("com.makomi.data.LinkNodeRetireEvents$PendingRetireState");
		Constructor<?> stateConstructor = pendingRetireStateClass.getDeclaredConstructor();
		stateConstructor.setAccessible(true);
		Object pendingRetireState = stateConstructor.newInstance();
		assertNotNull(pendingRetireState);
	}

	/**
	 * CrossChunkDispatchService 内部记录体应可构造并保持字段值。
	 */
	@Test
	void crossChunkInternalRecordsShouldConstructWithExpectedValues() throws Exception {
		Class<?> dispatchKindClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchKind");
		Object activationKind = java.util.Arrays
			.stream(dispatchKindClass.getEnumConstants())
			.filter(constant -> ((Enum<?>) constant).name().equals("ACTIVATION"))
			.findFirst()
			.orElseThrow();

		Class<?> dispatchKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$DispatchKey");
		Constructor<?> dispatchKeyConstructor = dispatchKeyClass.getDeclaredConstructor(
			LinkNodeType.class,
			long.class,
			LinkNodeType.class,
			long.class,
			dispatchKindClass
		);
		dispatchKeyConstructor.setAccessible(true);
		Object dispatchKey = dispatchKeyConstructor.newInstance(LinkNodeType.TRIGGER_SOURCE, 1L, LinkNodeType.CORE, 2L, activationKind);
		assertNotNull(dispatchKey);

		Class<?> pendingDispatchClass = Class.forName("com.makomi.data.CrossChunkDispatchService$PendingDispatch");
		Constructor<?> pendingDispatchConstructor = pendingDispatchClass.getDeclaredConstructor(
			dispatchKeyClass,
			Class.forName("net.minecraft.resources.ResourceKey"),
			BlockPos.class,
			ActivationMode.class,
			boolean.class,
			long.class
		);
		pendingDispatchConstructor.setAccessible(true);
		Object pendingDispatch = pendingDispatchConstructor.newInstance(
			dispatchKey,
			Level.OVERWORLD,
			BlockPos.ZERO,
			ActivationMode.TOGGLE,
			false,
			80L
		);
		assertNotNull(pendingDispatch);
		assertEquals(80L, pendingDispatchClass.getDeclaredMethod("expireGameTick").invoke(pendingDispatch));

		Class<?> forcedChunkKeyClass = Class.forName("com.makomi.data.CrossChunkDispatchService$ForcedChunkKey");
		Constructor<?> forcedChunkKeyConstructor = forcedChunkKeyClass.getDeclaredConstructor(
			Class.forName("net.minecraft.resources.ResourceKey"),
			int.class,
			int.class
		);
		forcedChunkKeyConstructor.setAccessible(true);
		Object forcedChunkKey = forcedChunkKeyConstructor.newInstance(Level.OVERWORLD, 3, 4);
		assertNotNull(forcedChunkKey);
		assertEquals(3, forcedChunkKeyClass.getDeclaredMethod("chunkX").invoke(forcedChunkKey));
	}
}
