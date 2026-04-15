package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.makomi.data.LinkFilterTargetMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 过滤器编辑界面布局回归测试。
 */
@Tag("client")
class LinkFilterEditorScreenLayoutTest {
	/**
	 * 窄屏时应继续把面板宽度收敛进视口。
	 */
	@Test
	void resolveLayoutShouldShrinkPanelWidthIntoViewport() {
		LinkFilterEditorScreen.LinkFilterLayout layout = LinkFilterEditorScreen.resolveLayout(260, 240, 9);

		assertTrue(layout.panelWidth() <= 260);
		assertTrue(layout.panelLeft() >= 0);
		assertTrue(layout.panelLeft() + layout.panelWidth() <= 260);
	}

	/**
	 * 各组控件应沿纵向依次排布，避免单选组与输入框互相覆盖。
	 */
	@Test
	void resolveLayoutShouldKeepRowsOrdered() {
		LinkFilterEditorScreen.LinkFilterLayout layout = LinkFilterEditorScreen.resolveLayout(320, 300, 9);

		assertTrue(layout.aliasInputY() > layout.aliasLabelY());
		assertTrue(layout.targetModeRowY() > layout.targetModeLabelY());
		assertTrue(layout.serialLabelY() > layout.targetModeRowY());
		assertTrue(layout.serialInputY() > layout.serialLabelY());
		assertTrue(layout.nodeSetRowY() > layout.nodeSetLabelY());
		assertTrue(layout.thresholdSourceRowY() > layout.thresholdSourceLabelY());
		assertTrue(layout.fixedThresholdInputY() > layout.fixedThresholdLabelY());
		assertTrue(layout.signalModeRowY() > layout.signalModeLabelY());
		assertTrue(layout.actionButtonY() > layout.signalModeRowY());
		assertTrue(layout.statusMessageY() > layout.actionButtonY());
	}

	/**
	 * 共享输入区命中时，应按当前目标模式路由到对应输入框。
	 */
	@Test
	void resolveTargetInputClickTargetShouldFollowCurrentMode() {
		LinkFilterEditorScreen.LinkFilterLayout layout = LinkFilterEditorScreen.resolveLayout(320, 300, 9);
		double sharedInputCenterX = layout.panelLeft() + (layout.panelWidth() / 2.0);

		assertEquals(
			LinkFilterTargetMode.SERIAL,
			LinkFilterEditorScreen.resolveTargetInputClickTarget(
				layout,
				LinkFilterTargetMode.SERIAL,
				sharedInputCenterX,
				layout.serialInputY() + 10
			)
		);
		assertEquals(
			LinkFilterTargetMode.CHANNEL,
			LinkFilterEditorScreen.resolveTargetInputClickTarget(
				layout,
				LinkFilterTargetMode.CHANNEL,
				sharedInputCenterX,
				layout.channelInputY() + 10
			)
		);
		assertNull(
			LinkFilterEditorScreen.resolveTargetInputClickTarget(
				layout,
				LinkFilterTargetMode.CHANNEL,
				layout.panelLeft() - 4,
				layout.channelInputY() + 10
			)
		);
	}
}
