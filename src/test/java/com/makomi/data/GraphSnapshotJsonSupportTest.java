package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * graph snapshot JSON 与结构校验码回归测试。
 */
@Tag("stable-core")
class GraphSnapshotJsonSupportTest {
	/**
	 * 结构校验码应只依赖稳定结构语义，不受顺序和动态元信息影响。
	 */
	@Test
	void structureChecksumShouldIgnoreOrderAndDynamicMetadata() {
		GraphSnapshotBundle baseBundle = buildBundle(
			"serial-r3-base",
			3L,
			128L,
			"player-a",
			List.of(
				new GraphSnapshotBundle.GraphNodeInfo(
					"triggerSource:12",
					LinkNodeType.TRIGGER_SOURCE,
					12L,
					"样例来源",
					"样例来源(#12)",
					true,
					false,
					"serial",
					0L,
					3L,
					0L,
					List.of("readonly", "outbound")
				),
				new GraphSnapshotBundle.GraphNodeInfo(
					"core:88",
					LinkNodeType.CORE,
					88L,
					"样例核心",
					"样例核心(#88)",
					true,
					false,
					"serial",
					0L,
					0L,
					2L,
					List.of("readonly", "inbound")
				)
			),
			List.of(
				new GraphSnapshotBundle.GraphEdgeInfo(
					"triggerSource:12->core:88",
					"triggerSource:12",
					"core:88",
					"serial",
					true,
					false
				)
			)
		);
		String checksum = GraphSnapshotJsonSupport.buildStructureChecksum(baseBundle);
		GraphSnapshotBundle reorderedBundle = buildBundle(
			"serial-r99-other",
			99L,
			4096L,
			"player-b",
			List.of(baseBundle.nodes().get(1), baseBundle.nodes().get(0)),
			List.of(baseBundle.edges().get(0))
		);

		assertEquals(checksum, GraphSnapshotJsonSupport.buildStructureChecksum(reorderedBundle));
	}

	/**
	 * 文件名应绑定 graphRevision 与结构校验码，且导出 JSON 不再携带运行态字段。
	 */
	@Test
	void buildFileNameShouldUseRevisionAndJsonShouldExcludeRuntimeFields() {
		GraphSnapshotBundle bundle = buildBundle(
			"serial-r3-base",
			3L,
			128L,
			"player-a",
			List.of(
				new GraphSnapshotBundle.GraphNodeInfo(
					"triggerSource:12",
					LinkNodeType.TRIGGER_SOURCE,
					12L,
					"样例来源",
					"样例来源(#12)",
					true,
					false,
					"serial",
					0L,
					3L,
					0L,
					List.of("outbound", "readonly")
				)
			),
			List.of()
		);
		String checksum = GraphSnapshotJsonSupport.buildStructureChecksum(bundle);
		GraphSnapshotBundle bundleWithChecksum = new GraphSnapshotBundle(
			bundle.snapshotId(),
			bundle.mode(),
			bundle.graphRevision(),
			bundle.generatedAtTick(),
			bundle.viewerPlayerId(),
			checksum,
			bundle.nodes(),
			bundle.edges(),
			bundle.stats()
		);
		String fileName = GraphSnapshotJsonSupport.buildFileName(bundleWithChecksum);
		String json = GraphSnapshotJsonSupport.toJson(bundleWithChecksum);

		assertTrue(fileName.startsWith("graph-serial-r3-"));
		assertTrue(fileName.endsWith(".json.gz"));
		assertTrue(fileName.contains(checksum.substring(0, 12)));
		assertTrue(json.contains("\"structureChecksum\""));
		assertFalse(json.contains("\"online\""));
		assertFalse(json.contains("\"active\""));
		assertFalse(json.contains("\"inputPower\""));
		assertFalse(json.contains("\"outputPower\""));
	}

	private static GraphSnapshotBundle buildBundle(
		String snapshotId,
		long graphRevision,
		long generatedAtTick,
		String viewerPlayerId,
		List<GraphSnapshotBundle.GraphNodeInfo> nodes,
		List<GraphSnapshotBundle.GraphEdgeInfo> edges
	) {
		return new GraphSnapshotBundle(
			snapshotId,
			"serial",
			graphRevision,
			generatedAtTick,
			viewerPlayerId,
			"",
			nodes,
			edges,
			new GraphSnapshotBundle.GraphStats(nodes.size(), edges.size(), 1, Math.max(0, nodes.size() - 1), 0)
		);
	}
}
