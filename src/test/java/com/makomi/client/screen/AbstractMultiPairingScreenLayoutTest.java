package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * pairing 基类布局计算回归测试。
 */
@Tag("client")
class AbstractMultiPairingScreenLayoutTest {
	/**
	 * 默认宽度下应保持居中，且不越出可视区。
	 */
	@Test
	void resolveLayoutShouldKeepPreferredWidthWhenViewportIsEnough() {
		AbstractMultiPairingScreen.MultiPairingLayout layout = AbstractMultiPairingScreen.resolveLayout(320, 240, 9);

		assertEquals(220, layout.panelWidth());
		assertEquals(50, layout.panelLeft());
		assertTrue(layout.panelLeft() + layout.panelWidth() <= 320);
	}

	/**
	 * 两个主操作按钮应始终完整落在面板内部，并保持自上而下的布局顺序。
	 */
	@Test
	void resolveLayoutShouldKeepButtonsInsidePanel() {
		AbstractMultiPairingScreen.MultiPairingLayout layout = AbstractMultiPairingScreen.resolveLayout(180, 200, 9);

		int secondButtonRight = layout.actionButtonX(1) + layout.actionButtonWidth();
		assertTrue(secondButtonRight <= layout.panelLeft() + layout.panelWidth());
		assertTrue(layout.currentLinksY() > layout.titleY());
		assertTrue(layout.inputY() > layout.inputLabelY());
		assertTrue(layout.statusMessageY() > layout.actionButtonY());
	}
}
