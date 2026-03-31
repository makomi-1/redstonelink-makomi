package com.makomi.client.screen;

/**
 * 居中表单布局支持工具。
 * <p>
 * 为以“居中表单”为主体的 GUI 提供统一的视口收敛与控件宽度计算，
 * 避免各界面散落相同的几何推导逻辑。
 * </p>
 */
final class CenteredFormLayoutSupport {
	private CenteredFormLayoutSupport() {
	}

	/**
	 * 解析居中面板的基础包围盒。
	 */
	static CenteredPanelBox resolvePanelBox(
		int screenWidth,
		int screenHeight,
		int preferredWidth,
		int preferredHeight,
		int edgeMargin
	) {
		int panelWidth = Math.min(preferredWidth, Math.max(1, screenWidth - edgeMargin * 2));
		int panelLeft = clampVisibleStart((screenWidth - panelWidth) / 2, panelWidth, screenWidth);
		int panelTop = clampVisibleStart((screenHeight - preferredHeight) / 2, preferredHeight, screenHeight);
		return new CenteredPanelBox(panelLeft, panelTop, panelWidth, preferredHeight);
	}

	/**
	 * 计算平均分配的按钮宽度。
	 */
	static int resolveSplitWidth(int containerWidth, int gap, int count) {
		if (count <= 0) {
			return 0;
		}
		return Math.max(1, (containerWidth - gap * Math.max(0, count - 1)) / count);
	}

	/**
	 * 保证面板左上角不被推到可视区域外。
	 */
	static int clampVisibleStart(int desiredStart, int elementSize, int containerSize) {
		if (containerSize <= 0) {
			return 0;
		}
		int minStart = 0;
		int maxStart = containerSize - elementSize;
		if (maxStart < minStart) {
			return Math.max(0, (containerSize - elementSize) / 2);
		}
		return Math.max(minStart, Math.min(desiredStart, maxStart));
	}

	/**
	 * 居中面板基础包围盒。
	 */
	record CenteredPanelBox(int left, int top, int width, int height) {
	}
}
