package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.block.LinkRepeaterBlock;
import com.makomi.data.LinkNodeType;
import com.makomi.testsupport.TestMinecraftSupport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 转发器双身份契约测试。
 */
@Tag("stable-core")
class LinkRepeaterBlockEntityIdentityTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 实际转发器实体应同时匹配同号 `core/triggerSource` 两种逻辑身份。
	 */
	@Test
	void matchesNodeIdentityShouldExposeCoreAndTriggerSourceOnSameSerial() {
		TestRepeaterFixture fixture = createRepeaterFixture();
		TestRepeaterEntity entity = new TestRepeaterEntity(fixture.type(), BlockPos.ZERO, fixture.state());
		entity.setLinkData(42L);

		assertTrue(entity.matchesNodeIdentity(LinkNodeType.CORE, 42L));
		assertTrue(entity.matchesNodeIdentity(LinkNodeType.TRIGGER_SOURCE, 42L));
		assertFalse(entity.matchesNodeIdentity(LinkNodeType.CORE, 43L));
		assertFalse(entity.matchesNodeIdentity(LinkNodeType.TRIGGER_SOURCE, 43L));
	}

	/**
	 * 转发器遍历身份时应先暴露 `core`，再暴露同号 `triggerSource`。
	 */
	@Test
	void forEachNodeIdentityShouldEmitCoreThenTriggerSource() {
		TestRepeaterFixture fixture = createRepeaterFixture();
		TestRepeaterEntity entity = new TestRepeaterEntity(fixture.type(), BlockPos.ZERO, fixture.state());
		entity.setLinkData(77L);

		List<String> identities = new ArrayList<>();
		entity.forEachNodeIdentity((nodeType, serial) -> identities.add(nodeType.name() + ":" + serial));

		assertEquals(List.of("CORE:77", "TRIGGER_SOURCE:77"), identities);
	}

	/**
	 * 最小转发器测试实体：仅复用真实双身份实现，不依赖模组注册表项。
	 */
	private static final class TestRepeaterEntity extends LinkRepeaterBlockEntity {
		private TestRepeaterEntity(
			BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
			BlockPos pos,
			BlockState state
		) {
			super(blockEntityType, pos, state);
		}
	}

	/**
	 * 仅为单测临时创建最小转发器块和方块实体类型。
	 */
	private static TestRepeaterFixture createRepeaterFixture() {
		return TestMinecraftSupport.withWritableBlockRegistries(() -> {
			LinkRepeaterBlock block = new LinkRepeaterBlock(
				BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion().setId(
					BuiltInRegistries.BLOCK.getResourceKey(Blocks.OBSERVER).orElseThrow()
				)
			);
			return new TestRepeaterFixture(TestMinecraftSupport.createPlaceholderBlockEntityType(block), block.defaultBlockState());
		});
	}

	private record TestRepeaterFixture(BlockEntityType<? extends PairableNodeBlockEntity> type, BlockState state) {}
}
