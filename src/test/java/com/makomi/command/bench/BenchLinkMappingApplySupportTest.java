package com.makomi.command.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * bench 结构化批量建链规则展开测试。
 */
@Tag("stable-core")
class BenchLinkMappingApplySupportTest {
	/**
	 * broadcast_all 应把全部目标分发给每个 source。
	 */
	@Test
	void resolveTargetsForSourceIndexShouldBroadcastAll() {
		List<Long> targets = List.of(10L, 11L, 12L);

		List<Long> resolved = BenchLinkMappingApplySupport.resolveTargetsForSourceIndex(
			targets,
			2,
			BenchLinkMappingApplySupport.MappingSpec.broadcastAll()
		);

		assertEquals(List.of(10L, 11L, 12L), resolved);
	}

	/**
	 * fan_in_first 应只命中首个目标。
	 */
	@Test
	void resolveTargetsForSourceIndexShouldFanInToFirstTarget() {
		List<Long> targets = List.of(21L, 22L, 23L);

		List<Long> resolved = BenchLinkMappingApplySupport.resolveTargetsForSourceIndex(
			targets,
			5,
			BenchLinkMappingApplySupport.MappingSpec.fanInFirst()
		);

		assertEquals(List.of(21L), resolved);
	}

	/**
	 * zip 应按 source 索引逐项映射到同索引目标。
	 */
	@Test
	void resolveTargetsForSourceIndexShouldZipToMatchingTarget() {
		List<Long> targets = List.of(31L, 32L, 33L);

		List<Long> resolved = BenchLinkMappingApplySupport.resolveTargetsForSourceIndex(
			targets,
			1,
			BenchLinkMappingApplySupport.MappingSpec.zip()
		);

		assertEquals(List.of(32L), resolved);
	}

	/**
	 * banded 应按窗口规则并支持 wrap。
	 */
	@Test
	void resolveTargetsForSourceIndexShouldApplyBandedWindowWithWrap() {
		List<Long> targets = List.of(100L, 101L, 102L, 103L, 104L);

		List<Long> resolved = BenchLinkMappingApplySupport.resolveTargetsForSourceIndex(
			targets,
			1,
			BenchLinkMappingApplySupport.MappingSpec.banded(3, 2, 1, true)
		);

		assertEquals(List.of(103L, 104L, 100L), resolved);
	}

	/**
	 * buildTargetsBySource 应按 source 顺序生成覆盖集合。
	 */
	@Test
	void buildTargetsBySourceShouldBuildAssignments() {
		Map<Long, Set<Long>> targetsBySource = BenchLinkMappingApplySupport.buildTargetsBySource(
			List.of(1L, 2L),
			List.of(10L, 11L, 12L, 13L),
			BenchLinkMappingApplySupport.MappingSpec.banded(2, 2, 0, true),
			8
		);

		assertEquals(Set.of(10L, 11L), targetsBySource.get(1L));
		assertEquals(Set.of(12L, 13L), targetsBySource.get(2L));
	}

	/**
	 * 解析后若超出单 source 目标上限，应直接拒绝。
	 */
	@Test
	void buildTargetsBySourceShouldRejectWhenResolvedTargetsExceedLimit() {
		assertThrows(
			IllegalArgumentException.class,
			() -> BenchLinkMappingApplySupport.buildTargetsBySource(
				List.of(1L),
				List.of(10L, 11L, 12L),
				BenchLinkMappingApplySupport.MappingSpec.broadcastAll(),
				2
			)
		);
	}
}
