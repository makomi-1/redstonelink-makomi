package com.makomi.client.render;

/**
 * 近外显布局计算支持。
 * <p>
 * 负责屏幕中心偏移、分辨率缩放与包围盒计算，不承担文本拼接与实际绘制职责。
 * </p>
 */
final class LinkSerialHudOverlayLayoutSupport {
	static final int TEXT_PADDING_X = 6;
	static final int TEXT_PADDING_Y = 4;
	static final int LINE_SPACING = 2;
	/**
	 * 近外显位置缩放的分辨率基准宽度（2K）。
	 */
	private static final float POSITION_BASE_WIDTH = 2560.0F;
	/**
	 * 近外显位置缩放的分辨率基准高度（2K）。
	 */
	private static final float POSITION_BASE_HEIGHT = 1440.0F;
	/**
	 * 近外显文本相对准星中心的水平偏移像素（正数右移，负数左移）。
	 */
	private static final int CROSSHAIR_OFFSET_X = 0;
	/**
	 * 近外显文本相对准星中心的下移像素。
	 */
	private static final int CROSSHAIR_OFFSET_Y = 250;
	/**
	 * 位置缩放缓存宽度。
	 */
	private static int cachedScaleScreenWidth = -1;
	/**
	 * 位置缩放缓存高度。
	 */
	private static int cachedScaleScreenHeight = -1;
	/**
	 * 位置缩放缓存值。
	 */
	private static float cachedPositionScale = 1.0F;

	private LinkSerialHudOverlayLayoutSupport() {
	}

	/**
	 * 计算近外显在当前屏幕下的绘制布局。
	 *
	 * @param screenWidth HUD 宽度
	 * @param screenHeight HUD 高度
	 * @param scaledTextWidth 缩放后的文本总宽度
	 * @param scaledTextHeight 缩放后的文本总高度
	 * @return 可直接用于绘制背景与文字的布局结果
	 */
	static OverlayLayout resolveLayout(int screenWidth, int screenHeight, float scaledTextWidth, float scaledTextHeight) {
		float positionScale = resolvePositionScale(screenWidth, screenHeight);
		float centerX = resolveVisibleCenterX(screenWidth, scaledTextWidth, CROSSHAIR_OFFSET_X * positionScale);
		float centerY = resolveVisibleCenterY(screenHeight, scaledTextHeight, CROSSHAIR_OFFSET_Y * positionScale);
		int left = Math.round(centerX - (scaledTextWidth / 2.0F)) - TEXT_PADDING_X;
		int top = Math.round(centerY - (scaledTextHeight / 2.0F)) - TEXT_PADDING_Y;
		int right = Math.round(centerX + (scaledTextWidth / 2.0F)) + TEXT_PADDING_X;
		int bottom = Math.round(centerY + (scaledTextHeight / 2.0F)) + TEXT_PADDING_Y;
		float textX = centerX - (scaledTextWidth / 2.0F);
		float textY = centerY - (scaledTextHeight / 2.0F);
		return new OverlayLayout(left, top, right, bottom, textX, textY);
	}

	/**
	 * 计算可视区域内的近外显中心 X，避免窗口化时文本被偏移出屏幕。
	 */
	private static float resolveVisibleCenterX(int screenWidth, float scaledTextWidth, float offsetX) {
		float desiredCenterX = (screenWidth / 2.0F) + offsetX;
		float minCenterX = (scaledTextWidth / 2.0F) + TEXT_PADDING_X + 1.0F;
		float maxCenterX = screenWidth - (scaledTextWidth / 2.0F) - TEXT_PADDING_X - 1.0F;
		if (minCenterX > maxCenterX) {
			return screenWidth / 2.0F;
		}
		return Math.max(minCenterX, Math.min(desiredCenterX, maxCenterX));
	}

	/**
	 * 计算可视区域内的近外显中心 Y，避免窗口化时文本被偏移出屏幕。
	 */
	private static float resolveVisibleCenterY(int screenHeight, float scaledTextHeight, float offsetY) {
		float desiredCenterY = (screenHeight / 2.0F) + offsetY;
		float minCenterY = (scaledTextHeight / 2.0F) + TEXT_PADDING_Y + 1.0F;
		float maxCenterY = screenHeight - (scaledTextHeight / 2.0F) - TEXT_PADDING_Y - 1.0F;
		if (minCenterY > maxCenterY) {
			return screenHeight / 2.0F;
		}
		return Math.max(minCenterY, Math.min(desiredCenterY, maxCenterY));
	}

	/**
	 * 计算近外显“位置偏移”的等比缩放系数，基准为 2560x1440。
	 */
	private static float resolvePositionScale(int screenWidth, int screenHeight) {
		if (screenWidth <= 0 || screenHeight <= 0) {
			return 1.0F;
		}
		if (screenWidth == cachedScaleScreenWidth && screenHeight == cachedScaleScreenHeight) {
			return cachedPositionScale;
		}
		float scaleByWidth = screenWidth / POSITION_BASE_WIDTH;
		float scaleByHeight = screenHeight / POSITION_BASE_HEIGHT;
		cachedPositionScale = Math.min(scaleByWidth, scaleByHeight);
		cachedScaleScreenWidth = screenWidth;
		cachedScaleScreenHeight = screenHeight;
		return cachedPositionScale;
	}

	/**
	 * 近外显绘制布局结果。
	 *
	 * @param left 背景左边界
	 * @param top 背景上边界
	 * @param right 背景右边界
	 * @param bottom 背景下边界
	 * @param textX 文字绘制起点 X
	 * @param textY 文字绘制起点 Y
	 */
	record OverlayLayout(int left, int top, int right, int bottom, float textX, float textY) {
	}
}
