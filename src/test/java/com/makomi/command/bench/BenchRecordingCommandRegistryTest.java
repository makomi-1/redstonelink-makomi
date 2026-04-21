package com.makomi.command.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.data.QuickLinkOperationFeedback;
import com.makomi.data.StatePanelRecordingSessionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * bench recording 命令稳定 summary 契约测试。
 */
@Tag("stable-core")
class BenchRecordingCommandRegistryTest {
	/**
	 * prepare summary 应稳定暴露触发器与订阅规模。
	 */
	@Test
	void buildPrepareSummaryShouldExposeStableCounts() {
		assertEquals(
			"[RedstoneLink/Bench] recording_prepare outcome=prepared triggerSourceSerial=5 coreCount=2 subscriptionCount=3",
			BenchRecordingCommandRegistry.buildPrepareSummary(5L, 2, 3)
		);
	}

	/**
	 * start summary 应在权限不足时输出稳定 rejected reason。
	 */
	@Test
	void buildStartSummaryShouldExposePermissionDeniedReason() {
		StatePanelRecordingSessionService.StartResult startResult = new StatePanelRecordingSessionService.StartResult(
			false,
			QuickLinkOperationFeedback.failure("message.redstonelink.permission.insufficient"),
			StatePanelRecordingSessionService.SessionSnapshot.inactive(0)
		);

		assertEquals(
			"[RedstoneLink/Bench] recording_start outcome=rejected reason=permission_insufficient active=false subscriptionCount=0 mountedCount=0 sampleEveryTicks=2 autoOpenWeb=true",
			BenchRecordingCommandRegistry.buildStartSummary(startResult)
		);
	}

	/**
	 * stop summary 应稳定暴露导出文件名与字节数。
	 */
	@Test
	void buildStopSummaryShouldExposeExportShape() {
		StatePanelRecordingSessionService.StopResult stopResult = new StatePanelRecordingSessionService.StopResult(
			true,
			QuickLinkOperationFeedback.success("message.redstonelink.state_panel.recording.stop.done", "recording-test.json.gz"),
			StatePanelRecordingSessionService.SessionSnapshot.inactive(2),
			new StatePanelRecordingSessionService.ExportBundle(
				"recording-test.json.gz",
				"zip".getBytes(StandardCharsets.UTF_8),
				false
			)
		);

		assertEquals(
			"[RedstoneLink/Bench] recording_stop outcome=exported reason=exported active=false subscriptionCount=2 mountedCount=0 exportBytes=3 fileName=recording-test.json.gz autoOpenWeb=false",
			BenchRecordingCommandRegistry.buildStopSummary(stopResult)
		);
	}
}
