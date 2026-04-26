package com.makomi.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.network.QuickLinkNetwork;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * quick-link 缓存方框边界提取回归测试。
 */
@Tag("stable-core")
class QuickLinkOutlineRendererTest {

	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * 单个方块的外轮廓应保留 12 条边。
	 */
	@Test
	void singleBlockShouldKeepTwelveBoundaryEdges() throws Exception {
		assertEquals(12, countBoundarySegments(Set.of(BlockPos.ZERO)));
	}

	/**
	 * 相连方块的共享面不应继续输出内部边。
	 */
	@Test
	void adjacentBlocksShouldCollapseInternalFaceEdges() throws Exception {
		assertEquals(12, countBoundarySegments(Set.of(BlockPos.ZERO, BlockPos.ZERO.east())));
	}

	/**
	 * 不相连方块仍应各自保留完整外框。
	 */
	@Test
	void separatedBlocksShouldKeepIndependentOutlines() throws Exception {
		assertEquals(24, countBoundarySegments(Set.of(BlockPos.ZERO, new BlockPos(2, 0, 0))));
	}

	/**
	 * 第三形态显示对象应按对象键去重，并允许清空。
	 */
	@Test
	void visualizeObjectsShouldDeduplicateAndClear() {
		QuickLinkOutlineRenderer.clearVisualizedObjects();
		QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload payload = new QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload(
			"link_repeater",
			18L,
			"minecraft:overworld",
			BlockPos.ZERO.asLong(),
			"转发器#18",
			java.util.List.of(
				new QuickLinkNetwork.QuickLinkVisualizeTarget(
					"core",
					27L,
					"minecraft:overworld",
					BlockPos.ZERO.east().asLong(),
					"core#27"
				)
			)
		);

		QuickLinkOutlineRenderer.acceptVisualizeSnapshot(payload);
		assertTrue(QuickLinkOutlineRenderer.hasVisualizedObject("link_repeater", 18L));

		QuickLinkOutlineRenderer.acceptVisualizeSnapshot(payload);
		assertEquals(1, QuickLinkOutlineRenderer.clearVisualizedObjects());
		assertFalse(QuickLinkOutlineRenderer.hasVisualizedObject("link_repeater", 18L));
		assertEquals(0, QuickLinkOutlineRenderer.clearVisualizedObjects());
	}

	/**
	 * 第三形态连线命中应解析到另一端对象，并对缺失展示文本做序号兜底。
	 */
	@Test
	void resolveHoveredVisualizedTargetShouldReturnNormalizedTargetDisplay() {
		QuickLinkOutlineRenderer.clearVisualizedObjects();
		QuickLinkOutlineRenderer.acceptVisualizeSnapshot(
			new QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload(
				"triggerSource",
				4L,
				"minecraft:overworld",
				BlockPos.ZERO.asLong(),
				"triggerSource(#4)",
				java.util.List.of(
					new QuickLinkNetwork.QuickLinkVisualizeTarget(
						"core",
						27L,
						"minecraft:overworld",
						new BlockPos(4, 0, 0).asLong(),
						"   "
					)
				)
			)
		);

		QuickLinkOutlineRenderer.HoveredVisualizedTarget hoveredTarget = QuickLinkOutlineRenderer.resolveHoveredVisualizedTarget(
			"minecraft:overworld",
			new Vec3(2.5D, 0.5D, -2.0D),
			new Vec3(0.0D, 0.0D, 1.0D),
			8.0D
		);

		assertNotNull(hoveredTarget);
		assertEquals("core", hoveredTarget.objectTypeToken());
		assertEquals(27L, hoveredTarget.objectSerial());
		assertEquals("#27", hoveredTarget.displayText());
		assertEquals(new BlockPos(4, 0, 0).asLong(), hoveredTarget.blockPosLong());
		QuickLinkOutlineRenderer.clearVisualizedObjects();
	}

	private static int countBoundarySegments(Set<BlockPos> occupiedBlocks) throws Exception {
		return QuickLinkPreviewOutlineSupport.buildBoundarySegments(occupiedBlocks).size();
	}
}
