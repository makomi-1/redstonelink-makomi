package com.makomi.command.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.data.GraphSnapshotBundle;
import com.makomi.data.GraphWriteJsonSupport;
import com.makomi.data.LinkNodeType;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * bench graph 命令稳定 summary 契约测试。
 */
@Tag("stable-core")
class BenchGraphCommandRegistryTest {
	/**
	 * 导出 summary 应稳定输出 graph 结构摘要与 dedupe 标记。
	 */
	@Test
	void buildExportSummaryShouldExposeStableBundleShape() {
		GraphSnapshotBundle bundle = new GraphSnapshotBundle(
			"serial-r7-abc123",
			"serial",
			7L,
			120L,
			"player-a",
			"abc123def456",
			List.of(),
			List.of(),
			new GraphSnapshotBundle.GraphStats(3, 2, 1, 2, 0)
		);

		assertEquals(
			"[RedstoneLink/Bench] graph_export outcome=exported mode=serial graphRevision=7 snapshotId=serial-r7-abc123 nodeCount=3 edgeCount=2 triggerSourceCount=1 coreCount=2 maskedSourceCount=0 reusedExisting=false fileName=graph-serial-r7-abc123def456.json.gz checksum=abc123def456",
			BenchGraphCommandRegistry.buildExportSummary(
				bundle,
				"graph-serial-r7-abc123def456.json.gz",
				false
			)
		);
	}

	/**
	 * preview summary 应稳定暴露保存窗口判定字段。
	 */
	@Test
	void buildWriteSummaryShouldExposePreviewFields() {
		String responseJson = GraphWriteJsonSupport.buildPreviewResponse(
			"当前可保存。",
			21L,
			new GraphWriteJsonSupport.PreviewState(1, 4, 6, true, true, false, false, 0L, 0L, true)
		);

		assertEquals(
			"[RedstoneLink/Bench] graph_preview outcome=preview sourceType=triggerSource sourceSerial=5 requestedTargetCount=2 status=ok result=preview reason=preview graphRevision=21 updatedNodeCount=0 previewCanSave=true previewGraphCost=4 previewGraphWriteUnitCount=6",
			BenchGraphCommandRegistry.buildWriteSummary("graph_preview", 5L, 2, responseJson)
		);
	}

	/**
	 * applied summary 应稳定暴露最小 updatedNodes 数量与 applied outcome。
	 */
	@Test
	void buildWriteSummaryShouldExposeAppliedFields() {
		String responseJson = GraphWriteJsonSupport.buildAppliedResponse(
			"已保存。",
			22L,
			List.of(
				new GraphWriteJsonSupport.UpdatedNodeState(
					"triggerSource:5",
					LinkNodeType.TRIGGER_SOURCE,
					5L,
					"",
					"",
					"",
					0L,
					9L,
					0L
				),
				new GraphWriteJsonSupport.UpdatedNodeState(
					"core:8",
					LinkNodeType.CORE,
					8L,
					"",
					"",
					"",
					0L,
					0L,
					3L
				)
			)
		);

		assertEquals(
			"[RedstoneLink/Bench] graph_save outcome=applied sourceType=triggerSource sourceSerial=5 requestedTargetCount=2 status=ok result=applied reason=applied graphRevision=22 updatedNodeCount=2 previewCanSave=- previewGraphCost=0 previewGraphWriteUnitCount=0",
			BenchGraphCommandRegistry.buildWriteSummary("graph_save", 5L, 2, responseJson)
		);
	}

}
