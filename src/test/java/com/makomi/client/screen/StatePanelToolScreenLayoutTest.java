package com.makomi.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 状态面板布局计算回归测试。
 */
@Tag("client")
class StatePanelToolScreenLayoutTest {
	/**
	 * 窄屏时应收敛面板宽度，避免继续使用默认宽度顶出可视区。
	 */
	@Test
	void resolveLayoutShouldShrinkPanelWidthIntoViewport() {
		StatePanelToolScreen.StatePanelLayout layout = StatePanelToolScreen.resolveLayout(320, 240);

		assertEquals(288, layout.panelWidth());
		assertEquals(16, layout.panelLeft());
		assertTrue(layout.panelLeft() + layout.panelWidth() <= 320);
	}

	/**
	 * 删除按钮应始终贴合面板最右操作列，避免与文本列脱节。
	 */
	@Test
	void resolveLayoutShouldPinRemoveButtonToPanelRightEdge() {
		StatePanelToolScreen.StatePanelLayout layout = StatePanelToolScreen.resolveLayout(854, 480);

		assertEquals(layout.panelLeft() + layout.panelWidth() - 20, layout.removeButtonX());
		assertTrue(layout.statusX() + layout.statusWidth() <= layout.removeButtonX() - 6);
	}

	/**
	 * 类型切换与三颗主按钮应共用同一行，保证紧凑化后按钮仍留在面板内。
	 */
	@Test
	void resolveLayoutShouldKeepTypeToggleAndActionsOnSameRow() {
		StatePanelToolScreen.StatePanelLayout layout = StatePanelToolScreen.resolveLayout(854, 480);

		assertEquals(layout.typeToggleY(), layout.actionY());
		assertTrue(layout.typeToggleWidth() > 0);
		assertTrue(layout.actionStartX() >= layout.panelLeft() + layout.typeToggleWidth());
		assertTrue(layout.actionButtonX(2) + layout.actionButtonWidth() <= layout.panelLeft() + layout.panelWidth());
	}

	/**
	 * 列坐标在窄屏下仍应保持从左到右有序，状态列宽度不能为负数。
	 */
	@Test
	void resolveLayoutShouldKeepColumnsOrderedOnNarrowScreen() {
		StatePanelToolScreen.StatePanelLayout layout = StatePanelToolScreen.resolveLayout(280, 240);

		assertTrue(layout.typeWidth() >= 0);
		assertTrue(layout.serialWidth() >= 0);
		assertTrue(layout.statusWidth() >= 0);
		assertTrue(layout.typeX() < layout.serialX());
		assertTrue(layout.serialX() < layout.statusX());
		assertTrue(layout.statusX() + layout.statusWidth() <= layout.removeButtonX() - 6);
	}
}
