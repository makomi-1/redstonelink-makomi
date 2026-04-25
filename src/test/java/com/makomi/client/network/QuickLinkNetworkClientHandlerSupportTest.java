package com.makomi.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * quick-link 客户端转发器命中身份解析回归测试。
 */
@Tag("stable-core")
class QuickLinkNetworkClientHandlerSupportTest {
	/**
	 * 转发器采集在 triggerSource 缓存下应命中 triggerSource 身份。
	 */
	@Test
	void resolveRepeaterCollectNodeTypeShouldFollowTriggerSourceCache() {
		assertEquals(
			LinkNodeType.TRIGGER_SOURCE,
			QuickLinkNetworkClientHandlerSupport.resolveRepeaterCollectNodeType(
				new QuickLinkToolData.Snapshot(
					QuickLinkToolData.Mode.SERIAL,
					LinkNodeType.TRIGGER_SOURCE,
					"",
					"",
					QuickLinkToolData.ApplyEditMode.REPLACE
				)
			)
		);
	}

	/**
	 * 转发器采集在 core 缓存下应命中 core 身份。
	 */
	@Test
	void resolveRepeaterCollectNodeTypeShouldFallbackToCoreCache() {
		assertEquals(
			LinkNodeType.CORE,
			QuickLinkNetworkClientHandlerSupport.resolveRepeaterCollectNodeType(
				new QuickLinkToolData.Snapshot(
					QuickLinkToolData.Mode.SERIAL,
					LinkNodeType.CORE,
					"",
					"",
					QuickLinkToolData.ApplyEditMode.REPLACE
				)
			)
		);
		assertEquals(LinkNodeType.CORE, QuickLinkNetworkClientHandlerSupport.resolveRepeaterCollectNodeType(null));
	}
}
