package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.LinkRepeaterBlock;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器加载期 blockstate 挂起校正测试。
 */
@Tag("stable-core")
class LinkRepeaterBlockEntityLoadResyncTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 读档时若方块当前 `ACTIVE` 外显与已派发输出态不一致，应登记挂起校正。
	 * <p>
	 * 这里特意不写入输入侧活跃真值，验证转发器不会错误依赖 `owner.isActive()`，
	 * 而是按 `dispatchedOutputPower` 决定可见态。
	 * </p>
	 */
	@Test
	void loadShouldQueueSilentBlockStateSyncFromDispatchedOutputState() {
		TestRepeaterFixture fixture = createRepeaterFixture(false);
		TestRepeaterEntity repeater = new TestRepeaterEntity(fixture.type(), BlockPos.ZERO, fixture.state());
		CompoundTag tag = new CompoundTag();
		tag.putInt("dispatchedOutputPower", 15);

		repeater.loadForTest(tag);

		assertTrue(repeater.hasPendingLoadBlockStateSync());
	}

	/**
	 * 若当前方块状态已经与已派发输出态一致，则不应重复登记加载后静默校正。
	 */
	@Test
	void loadShouldSkipSilentBlockStateSyncWhenRepeaterStateAlreadyMatchesDispatchedOutput() {
		TestRepeaterFixture fixture = createRepeaterFixture(true);
		TestRepeaterEntity repeater = new TestRepeaterEntity(fixture.type(), BlockPos.ZERO, fixture.state());
		CompoundTag tag = new CompoundTag();
		tag.putInt("dispatchedOutputPower", 15);

		repeater.loadForTest(tag);

		assertFalse(repeater.hasPendingLoadBlockStateSync());
	}

	/**
	 * 读档附着恢复期间不得直接同步方块状态，避免把加载关键路径重新变成阻塞写块。
	 */
	@Test
	void clearRemovedShouldNotEagerlySyncBlockStateDuringAttachRecovery() throws Exception {
		String source = Files.readString(
			Path.of("src/main/java/com/makomi/block/entity/LinkRepeaterBlockEntity.java"),
			StandardCharsets.UTF_8
		);
		int clearRemovedStart = source.indexOf("public void clearRemoved()");
		int onActiveChangedStart = source.indexOf("protected void onActiveChanged(boolean active)");
		assertTrue(clearRemovedStart >= 0 && onActiveChangedStart > clearRemovedStart);
		String clearRemovedBody = source.substring(clearRemovedStart, onActiveChangedStart);

		assertFalse(clearRemovedBody.contains("syncRepeaterActiveBlockState("));
	}

	/**
	 * 最小转发器测试实体：只复用读档与挂起校正判断，不依赖真实方块实体注册。
	 */
	private static final class TestRepeaterEntity extends LinkRepeaterBlockEntity {
		private TestRepeaterEntity(
			BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
			BlockPos pos,
			BlockState state
		) {
			super(blockEntityType, pos, state);
		}

		private void loadForTest(CompoundTag tag) {
			loadAdditional(tag, null);
		}
	}

	/**
	 * 仅为单测临时创建最小转发器块和对应方块实体类型，避免依赖真实模组注册。
	 */
	private static TestRepeaterFixture createRepeaterFixture(boolean active) {
		try (RegistryWriteWindow ignored = RegistryWriteWindow.open()) {
			LinkRepeaterBlock block = new LinkRepeaterBlock(BlockBehaviour.Properties.of());
			@SuppressWarnings("unchecked")
			BlockEntityType<? extends PairableNodeBlockEntity> type =
				(BlockEntityType<? extends PairableNodeBlockEntity>) (BlockEntityType<?>) BlockEntityType.Builder.of(
					(pos, state) -> null,
					block
				).build(null);
			return new TestRepeaterFixture(type, block.defaultBlockState().setValue(LinkRepeaterBlock.ACTIVE, active));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("无法创建转发器测试夹具", exception);
		}
	}

	private record TestRepeaterFixture(BlockEntityType<? extends PairableNodeBlockEntity> type, BlockState state) {}

	/**
	 * 注册表写窗口：仅在测试中短暂恢复 `MappedRegistry` 的 intrusive holder 创建能力。
	 */
	private static final class RegistryWriteWindow implements AutoCloseable {
		private final RegistryState[] states;

		private RegistryWriteWindow(RegistryState... states) {
			this.states = states;
		}

		private static RegistryWriteWindow open() throws ReflectiveOperationException {
			return new RegistryWriteWindow(
				RegistryState.open((MappedRegistry<?>) BuiltInRegistries.BLOCK),
				RegistryState.open((MappedRegistry<?>) BuiltInRegistries.BLOCK_ENTITY_TYPE)
			);
		}

		@Override
		public void close() throws ReflectiveOperationException {
			for (int index = states.length - 1; index >= 0; index--) {
				states[index].close();
			}
		}
	}

	/**
	 * 单个注册表的临时可写快照。
	 */
	private static final class RegistryState implements AutoCloseable {
		private final MappedRegistry<?> registry;
		private final boolean frozen;
		private final Map<?, ?> intrusiveHolders;

		private RegistryState(MappedRegistry<?> registry, boolean frozen, Map<?, ?> intrusiveHolders) {
			this.registry = registry;
			this.frozen = frozen;
			this.intrusiveHolders = intrusiveHolders;
		}

		private static RegistryState open(MappedRegistry<?> registry) throws ReflectiveOperationException {
			boolean frozen = FROZEN_FIELD.getBoolean(registry);
			Map<?, ?> intrusiveHolders = (Map<?, ?>) UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.get(registry);
			FROZEN_FIELD.setBoolean(registry, false);
			UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.set(registry, new IdentityHashMap<>());
			return new RegistryState(registry, frozen, intrusiveHolders);
		}

		@Override
		public void close() throws ReflectiveOperationException {
			UNREGISTERED_INTRUSIVE_HOLDERS_FIELD.set(registry, intrusiveHolders);
			FROZEN_FIELD.setBoolean(registry, frozen);
		}
	}

	private static final Field FROZEN_FIELD = field("frozen");
	private static final Field UNREGISTERED_INTRUSIVE_HOLDERS_FIELD = field("unregisteredIntrusiveHolders");

	private static Field field(String name) {
		try {
			Field field = MappedRegistry.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}
}
