package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 状态面板录制结果 JSON 导出测试。
 */
@Tag("stable-core")
class StatePanelRecordingJsonSupportTest {
	/**
	 * JSON 导出应保留 recording bundle 的关键字段。
	 */
	@Test
	void toJsonShouldContainRecordingManifestAndSeries() {
		StatePanelRecordingBundle bundle = sampleBundle();

		String json = StatePanelRecordingJsonSupport.toJson(bundle);

		assertTrue(json.contains("\"kind\":\"recordingBundle\""));
		assertTrue(json.contains("\"type\":\"triggerSource\""));
		assertTrue(json.contains("\"traceKind\":\"pulseTriggerSource\""));
		assertTrue(json.contains("\"tick\":100"));
	}

	/**
	 * gzip 导出与文件名生成应返回非空结果。
	 */
	@Test
	void toCompressedJsonBytesAndBuildFileNameShouldProduceStableExport() throws IOException {
		StatePanelRecordingBundle bundle = sampleBundle();

		byte[] compressedBytes = StatePanelRecordingJsonSupport.toCompressedJsonBytes(bundle);
		String fileName = StatePanelRecordingJsonSupport.buildFileName(bundle);

		assertTrue(compressedBytes.length > 0);
		assertTrue(fileName.endsWith(".json.gz"));
		assertFalse(fileName.contains(" "));
	}

	private static StatePanelRecordingBundle sampleBundle() {
		return new StatePanelRecordingBundle(
			new StatePanelRecordingBundle.Manifest(
				"recording-12345678",
				"Demo Recording",
				100L,
				120L,
				2,
				1,
				2,
				StatePanelRecordingBundle.FORMAT_VERSION
			),
			List.of(
				new StatePanelRecordingBundle.RecordedNodeInfo(
					"triggerSource:12",
					LinkNodeType.TRIGGER_SOURCE,
					12L,
					"triggerSource 12",
					"pulseTriggerSource",
					true,
					false,
					true
				)
			),
			List.of(
				new StatePanelRecordingBundle.NodeSeries(
					"triggerSource:12",
					List.of(
						new StatePanelRecordingBundle.RecordedSample(100L, true, false, 0, 0),
						new StatePanelRecordingBundle.RecordedSample(102L, true, true, 12, 15)
					)
				)
			),
			List.of(new StatePanelRecordingBundle.RecordingMarker(100L, "recording-start"))
		);
	}
}
