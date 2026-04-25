package com.makomi.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GraphSnapshotExportService capabilityFlags 回归测试。
 */
@Tag("stable-core")
class GraphSnapshotExportServiceCapabilityFlagTest {
	/**
	 * 转发器统一序号导出到 graph 时，`triggerSource/core` 两端都应带上 repeater 标记。
	 */
	@Test
	void resolveCapabilityFlagsShouldIncludeRepeaterForRepeaterSerial() throws Exception {
		LinkSavedData savedData = new LinkSavedData();
		savedData.markRepeaterSerial(77L);

		List<String> flags = invokeResolveCapabilityFlags(savedData, LinkNodeType.TRIGGER_SOURCE, 77L, LinkConnectionMode.SERIAL);

		assertTrue(flags.contains("outbound"));
		assertTrue(flags.contains("readonly"));
		assertTrue(flags.contains("repeater"));
		assertFalse(flags.contains("channel"));
	}

	/**
	 * 非转发器节点仍应保持原有 capabilityFlags，不额外泄漏 repeater 标记。
	 */
	@Test
	void resolveCapabilityFlagsShouldKeepNormalNodeWithoutRepeaterMarker() throws Exception {
		LinkSavedData savedData = new LinkSavedData();

		List<String> flags = invokeResolveCapabilityFlags(savedData, LinkNodeType.CORE, 12L, LinkConnectionMode.CHANNEL);

		assertTrue(flags.contains("inbound"));
		assertTrue(flags.contains("readonly"));
		assertTrue(flags.contains("channel"));
		assertFalse(flags.contains("repeater"));
	}

	@SuppressWarnings("unchecked")
	private static List<String> invokeResolveCapabilityFlags(
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long serial,
		LinkConnectionMode connectionMode
	) throws Exception {
		Method method = GraphSnapshotExportService.class.getDeclaredMethod(
			"resolveCapabilityFlags",
			LinkSavedData.class,
			LinkNodeType.class,
			long.class,
			LinkConnectionMode.class
		);
		method.setAccessible(true);
		return (List<String>) method.invoke(null, savedData, nodeType, serial, connectionMode);
	}
}
