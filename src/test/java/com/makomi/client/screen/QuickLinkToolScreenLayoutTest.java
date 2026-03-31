package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * QuickLink 布局计算回归测试。
 */
@Tag("client")
class QuickLinkToolScreenLayoutTest {
	/**
	 * 窄屏时应收敛输入面板宽度，避免继续顶出可视区。
	 */
	@Test
	void resolveLayoutShouldShrinkPanelWidthIntoViewport() {
		QuickLinkToolScreen.QuickLinkLayout layout = QuickLinkToolScreen.resolveLayout(320, 240, 9);

		assertEquals(220, layout.panelWidth());
		assertEquals(50, layout.panelLeft());
		assertTrue(layout.panelLeft() + layout.panelWidth() <= 320);
	}

	/**
	 * 主操作按钮在收敛后仍应完整落在面板宽度内。
	 */
	@Test
	void resolveLayoutShouldKeepActionButtonsInsidePanel() {
		QuickLinkToolScreen.QuickLinkLayout layout = QuickLinkToolScreen.resolveLayout(180, 240, 9);

		int secondButtonRight = layout.actionButtonX(1) + layout.actionButtonWidth();
		assertTrue(secondButtonRight <= layout.panelLeft() + layout.panelWidth());
		assertTrue(layout.cacheTypeButtonY() > layout.inputY());
		assertTrue(layout.statusMessageY() > layout.actionButtonY());
	}
}
