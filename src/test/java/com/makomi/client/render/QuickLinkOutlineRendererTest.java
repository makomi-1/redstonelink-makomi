package com.makomi.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * quick-link 缓存方框边界提取回归测试。
 */
@Tag("stable-core")
class QuickLinkOutlineRendererTest {

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

	private static int countBoundarySegments(Set<BlockPos> occupiedBlocks) throws Exception {
		return QuickLinkPreviewOutlineSupport.buildBoundarySegments(occupiedBlocks).size();
	}
}
