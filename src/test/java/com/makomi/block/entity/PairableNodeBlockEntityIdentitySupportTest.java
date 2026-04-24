package com.makomi.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.data.LinkNodeType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PairableNodeBlockEntity 节点身份匹配支撑测试。
 */
@Tag("stable-core")
class PairableNodeBlockEntityIdentitySupportTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 默认实现应只匹配主身份。
	 */
	@Test
	void matchesNodeIdentityShouldOnlyMatchPrimaryIdentityByDefault() {
		SingleIdentityTestEntity entity = new SingleIdentityTestEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		entity.setLinkData(42L);

		assertTrue(entity.matchesNodeIdentity(LinkNodeType.CORE, 42L));
		assertFalse(entity.matchesNodeIdentity(LinkNodeType.TRIGGER_SOURCE, 42L));
		assertFalse(entity.matchesNodeIdentity(LinkNodeType.CORE, 43L));
	}

	/**
	 * 多身份节点可额外暴露同序号的附加身份。
	 */
	@Test
	void forEachNodeIdentityShouldAllowDualIdentityExtension() {
		DualIdentityTestEntity entity = new DualIdentityTestEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
		entity.setLinkData(77L);

		assertTrue(entity.matchesNodeIdentity(LinkNodeType.CORE, 77L));
		assertTrue(entity.matchesNodeIdentity(LinkNodeType.TRIGGER_SOURCE, 77L));
		assertFalse(entity.matchesNodeIdentity(LinkNodeType.TRIGGER_SOURCE, 78L));

		List<String> identities = new ArrayList<>();
		entity.forEachNodeIdentity((nodeType, serial) -> identities.add(nodeType.name() + ":" + serial));
		assertEquals(List.of("CORE:77", "TRIGGER_SOURCE:77"), identities);
	}

	@SuppressWarnings("unchecked")
	private static BlockEntityType<? extends PairableNodeBlockEntity> castType(BlockEntityType<?> type) {
		return (BlockEntityType<? extends PairableNodeBlockEntity>) type;
	}

	/**
	 * 默认单身份测试实体。
	 */
	private static final class SingleIdentityTestEntity extends PairableNodeBlockEntity {
		private SingleIdentityTestEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		@Override
		protected LinkNodeType getNodeType() {
			return LinkNodeType.CORE;
		}
	}

	/**
	 * 双身份测试实体：模拟一个物理实体同时承载 `core + triggerSource`。
	 */
	private static final class DualIdentityTestEntity extends PairableNodeBlockEntity {
		private DualIdentityTestEntity(BlockPos pos, BlockState state) {
			super(castType(BlockEntityType.BEACON), pos, state);
		}

		@Override
		public boolean matchesNodeIdentity(LinkNodeType nodeType, long serial) {
			return serial > 0L
				&& serial == getSerial()
				&& (nodeType == LinkNodeType.CORE || nodeType == LinkNodeType.TRIGGER_SOURCE);
		}

		@Override
		public void forEachNodeIdentity(java.util.function.BiConsumer<LinkNodeType, Long> consumer) {
			if (consumer == null || getSerial() <= 0L) {
				return;
			}
			consumer.accept(LinkNodeType.CORE, getSerial());
			consumer.accept(LinkNodeType.TRIGGER_SOURCE, getSerial());
		}

		@Override
		protected LinkNodeType getNodeType() {
			return LinkNodeType.CORE;
		}
	}
}
