package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 状态面板录制界面布局回归测试。
 */
@Tag("client")
class StatePanelRecordingScreenLayoutTest {
	/**
	 * 窄屏时应继续把面板宽度收敛进视口。
	 */
	@Test
	void resolveLayoutShouldShrinkPanelWidthIntoViewport() {
		StatePanelRecordingScreen.PanelLayout layout = StatePanelRecordingScreen.resolveLayout(320, 400);

		assertEquals(288, layout.panelWidth());
		assertEquals(16, layout.panelLeft());
		assertTrue(layout.panelLeft() + layout.panelWidth() <= 320);
	}

	/**
	 * 紧凑化后仍应保留原有字段宽度预留，不挤压主输入区。
	 */
	@Test
	void resolveLayoutShouldPreserveFieldWidthReserveAfterCompaction() {
		StatePanelRecordingScreen.PanelLayout layout = StatePanelRecordingScreen.resolveLayout(854, 480);

		assertEquals(316, layout.fieldWidth());
		assertTrue(layout.splitFieldButtonWidth() > 0);
		assertTrue(layout.splitFieldButtonX(1) + layout.splitFieldButtonWidth() <= layout.panelLeft() + layout.panelWidth());
		assertTrue(layout.actionButtonX(3) + layout.actionButtonWidth() <= layout.panelLeft() + layout.panelWidth());
	}

	/**
	 * 中等高度窗口下，订阅列表和状态文本应仍落在面板与视口内。
	 */
	@Test
	void resolveLayoutShouldKeepSelectionAndStatusInsideViewportOnCompactHeight() {
		StatePanelRecordingScreen.PanelLayout layout = StatePanelRecordingScreen.resolveLayout(854, 400);

		assertTrue(layout.panelTop() >= 0);
		assertTrue(layout.panelTop() + layout.panelHeight() <= 400);
		assertTrue(layout.selectionButtonsY() > layout.selectionTitleY());
		assertTrue(layout.selectionStartY() > layout.selectionButtonsY());
		assertTrue(layout.selectionListBottom() < layout.statusMessageY());
		assertTrue(layout.statusMessageY() + 9 <= layout.panelTop() + layout.panelHeight());
	}

	/**
	 * 录制参数区、操作区与订阅区应沿纵向有序排布，避免紧凑化后互相覆盖。
	 */
	@Test
	void resolveLayoutShouldKeepSectionsOrdered() {
		StatePanelRecordingScreen.PanelLayout layout = StatePanelRecordingScreen.resolveLayout(854, 480);

		assertTrue(layout.summaryY() > layout.titleY());
		assertTrue(layout.runtimeInfoY() > layout.summaryY());
		assertTrue(layout.titleFieldY() > layout.runtimeInfoY());
		assertTrue(layout.autoOpenButtonY() > layout.durationFieldY());
		assertTrue(layout.actionButtonsY() > layout.autoOpenButtonY());
		assertTrue(layout.selectionTitleY() > layout.actionButtonsY());
		assertTrue(layout.selectionRowY(4) + 20 < layout.statusMessageY());
	}
}
