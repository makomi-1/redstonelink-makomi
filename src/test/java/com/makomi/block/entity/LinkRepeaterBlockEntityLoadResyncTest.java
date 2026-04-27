package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.LinkRepeaterBlock;
import com.makomi.testsupport.TestMinecraftSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
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
			TestMinecraftSupport.loadBlockEntityCustomOnly(this, tag);
		}
	}

	/**
	 * 仅为单测临时创建最小转发器块和对应方块实体类型，避免依赖真实模组注册。
	 */
	private static TestRepeaterFixture createRepeaterFixture(boolean active) {
		return TestMinecraftSupport.withWritableBlockRegistries(() -> {
			LinkRepeaterBlock block = new LinkRepeaterBlock(
				BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion().setId(
					BuiltInRegistries.BLOCK.getResourceKey(Blocks.OBSERVER).orElseThrow()
				)
			);
			return new TestRepeaterFixture(
				TestMinecraftSupport.createPlaceholderBlockEntityType(block),
				block.defaultBlockState().setValue(LinkRepeaterBlock.ACTIVE, active)
			);
		});
	}

	private record TestRepeaterFixture(BlockEntityType<? extends PairableNodeBlockEntity> type, BlockState state) {}
}
