package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

		assertTrue(layout.serialInputY() > layout.serialLabelY());
		assertTrue(layout.nodeSetRowY() > layout.nodeSetLabelY());
		assertTrue(layout.thresholdSourceRowY() > layout.thresholdSourceLabelY());
		assertTrue(layout.fixedThresholdInputY() > layout.fixedThresholdLabelY());
		assertTrue(layout.signalModeRowY() > layout.signalModeLabelY());
		assertTrue(layout.actionButtonY() > layout.signalModeRowY());
		assertTrue(layout.statusMessageY() > layout.actionButtonY());
	}
}
