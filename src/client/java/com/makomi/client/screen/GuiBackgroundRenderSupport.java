package com.makomi.client.screen;

import com.makomi.RedstoneLink;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * 通用 GUI 背景绘制支持。
 * <p>
 * 统一封装可复用的背景资源与 nine-slice 拉伸逻辑，避免各个 screen
 * 分散维护背景贴图坐标和窗口自适应规则。当前先提供全屏与区域两种入口，
 * 后续其它 GUI 可直接复用并切换到不同预设。
 * </p>
 */
final class GuiBackgroundRenderSupport {
	private static final Identifier CORE_PAIRING_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/core_pairing_background.png"
	);
	private static final Identifier TRIGGER_SOURCE_PAIRING_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/triggersource_pairing_background.png"
	);
	private static final Identifier FILTER_PAIRING_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/filter_pairing_background.png"
	);
	private static final Identifier QUICK_LINK_SERIAL_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/qlt_sd_background.png"
	);
	private static final Identifier QUICK_LINK_CHANNEL_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/qlt_cp_background.png"
	);
	private static final Identifier STATE_PANEL_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/status_panel_background.png"
	);
	private static final Identifier CHUNK_ACTIVATOR_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/chunk_activator_background.png"
	);
	private static final Identifier REPEATER_BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
		RedstoneLink.MOD_ID,
		"textures/gui/repeater_background.png"
	);

	private GuiBackgroundRenderSupport() {
	}

	/**
	 * 绘制整屏背景。
	 */
	static void renderFullscreen(GuiGraphicsExtractor guiGraphics, BackgroundPreset preset, int screenWidth, int screenHeight) {
		renderRegion(guiGraphics, preset, 0, 0, screenWidth, screenHeight);
	}

	/**
	 * 按内容包围盒和额外留白绘制背景。
	 */
	static void renderWrappedRegion(
		GuiGraphicsExtractor guiGraphics,
		BackgroundPreset preset,
		RegionBounds contentBounds,
		RegionPadding padding
	) {
		if (contentBounds == null || padding == null) {
			return;
		}
		int left = contentBounds.left() - padding.left();
		int top = contentBounds.top() - padding.top();
		int width = contentBounds.width() + padding.left() + padding.right();
		int height = contentBounds.height() + padding.top() + padding.bottom();
		renderRegion(guiGraphics, preset, left, top, width, height);
	}

	/**
	 * 按指定矩形区域绘制背景。
	 */
	static void renderRegion(GuiGraphicsExtractor guiGraphics, BackgroundPreset preset, int left, int top, int width, int height) {
		if (preset == null || width <= 0 || height <= 0) {
			return;
		}
		renderNineSlice(guiGraphics, preset.style(), left, top, width, height);
	}

	/**
	 * 通过 nine-slice 方式拉伸背景，尽量保持边框厚度稳定。
	 */
	private static void renderNineSlice(
		GuiGraphicsExtractor guiGraphics,
		BackgroundStyle style,
		int left,
		int top,
		int width,
		int height
	) {
		SliceBorder targetBorder = style.resolveTargetBorder(width, height);
		int leftBorder = targetBorder.left();
		int topBorder = targetBorder.top();
		int rightBorder = targetBorder.right();
		int bottomBorder = targetBorder.bottom();

		int centerX = left + leftBorder;
		int centerY = top + topBorder;
		int centerWidth = Math.max(0, width - leftBorder - rightBorder);
		int centerHeight = Math.max(0, height - topBorder - bottomBorder);

		int sourceCenterWidth = Math.max(0, style.textureWidth() - style.leftBorder() - style.rightBorder());
		int sourceCenterHeight = Math.max(0, style.textureHeight() - style.topBorder() - style.bottomBorder());
		int sourceRightX = style.textureWidth() - style.rightBorder();
		int sourceBottomY = style.textureHeight() - style.bottomBorder();

		// 先铺中心，再覆盖四边和四角，保证最终边框视觉最稳定。
		blitSlice(
			guiGraphics,
			style,
			centerX,
			centerY,
			centerWidth,
			centerHeight,
			style.leftBorder(),
			style.topBorder(),
			sourceCenterWidth,
			sourceCenterHeight
		);
		blitSlice(
			guiGraphics,
			style,
			centerX,
			top,
			centerWidth,
			topBorder,
			style.leftBorder(),
			0,
			sourceCenterWidth,
			style.topBorder()
		);
		blitSlice(
			guiGraphics,
			style,
			centerX,
			top + height - bottomBorder,
			centerWidth,
			bottomBorder,
			style.leftBorder(),
			sourceBottomY,
			sourceCenterWidth,
			style.bottomBorder()
		);
		blitSlice(
			guiGraphics,
			style,
			left,
			centerY,
			leftBorder,
			centerHeight,
			0,
			style.topBorder(),
			style.leftBorder(),
			sourceCenterHeight
		);
		blitSlice(
			guiGraphics,
			style,
			left + width - rightBorder,
			centerY,
			rightBorder,
			centerHeight,
			sourceRightX,
			style.topBorder(),
			style.rightBorder(),
			sourceCenterHeight
		);
		blitSlice(guiGraphics, style, left, top, leftBorder, topBorder, 0, 0, style.leftBorder(), style.topBorder());
		blitSlice(
			guiGraphics,
			style,
			left + width - rightBorder,
			top,
			rightBorder,
			topBorder,
			sourceRightX,
			0,
			style.rightBorder(),
			style.topBorder()
		);
		blitSlice(
			guiGraphics,
			style,
			left,
			top + height - bottomBorder,
			leftBorder,
			bottomBorder,
			0,
			sourceBottomY,
			style.leftBorder(),
			style.bottomBorder()
		);
		blitSlice(
			guiGraphics,
			style,
			left + width - rightBorder,
			top + height - bottomBorder,
			rightBorder,
			bottomBorder,
			sourceRightX,
			sourceBottomY,
			style.rightBorder(),
			style.bottomBorder()
		);
	}

	/**
	 * 绘制单个切片。
	 */
	private static void blitSlice(
		GuiGraphicsExtractor guiGraphics,
		BackgroundStyle style,
		int left,
		int top,
		int width,
		int height,
		int u,
		int v,
		int regionWidth,
		int regionHeight
	) {
		if (width <= 0 || height <= 0 || regionWidth <= 0 || regionHeight <= 0) {
			return;
		}
		guiGraphics.blit(
			RenderPipelines.GUI_TEXTURED,
			style.texture(),
			left,
			top,
			(float) u,
			(float) v,
			width,
			height,
			regionWidth,
			regionHeight,
			style.textureWidth(),
			style.textureHeight()
		);
	}

	/**
	 * 背景预设入口。
	 * <p>
	 * 各个 GUI 预设保留独立背景与主色，便于调用方只关心语义类型，
	 * 而不关心底层贴图文件名与主题常量。
	 * </p>
	 */
	enum BackgroundPreset {
		TRIGGER_SOURCE_PAIRING(new BackgroundStyle(TRIGGER_SOURCE_PAIRING_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFF9F5600),
		CORE_PAIRING(new BackgroundStyle(CORE_PAIRING_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFF0D47A1),
		FILTER_EDITOR(new BackgroundStyle(FILTER_PAIRING_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFFA00029),
		QUICK_LINK_SERIAL(new BackgroundStyle(QUICK_LINK_SERIAL_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFF610000),
		QUICK_LINK_CHANNEL(new BackgroundStyle(QUICK_LINK_CHANNEL_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFF294879),
		STATE_PANEL(new BackgroundStyle(STATE_PANEL_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFF9D0000),
		CHUNK_ACTIVATOR(new BackgroundStyle(CHUNK_ACTIVATOR_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFF572186),
		REPEATER(new BackgroundStyle(REPEATER_BACKGROUND_TEXTURE, 180, 200, 4, 4, 4, 4), 0xFF12651A);

		private final BackgroundStyle style;
		private final int borderColor;

		BackgroundPreset(BackgroundStyle style, int borderColor) {
			this.style = style;
			this.borderColor = borderColor;
		}

		BackgroundStyle style() {
			return style;
		}

		/**
		 * @return 与背景贴图边框对齐的主色，可供文本或控件样式复用
		 */
		int borderColor() {
			return borderColor;
		}
	}

	/**
	 * 背景贴图元数据。
	 */
	private record BackgroundStyle(
		Identifier texture,
		int textureWidth,
		int textureHeight,
		int leftBorder,
		int topBorder,
		int rightBorder,
		int bottomBorder
	) {
		SliceBorder resolveTargetBorder(int targetWidth, int targetHeight) {
			int[] horizontal = resolveAxisBorder(leftBorder, rightBorder, targetWidth);
			int[] vertical = resolveAxisBorder(topBorder, bottomBorder, targetHeight);
			return new SliceBorder(horizontal[0], vertical[0], horizontal[1], vertical[1]);
		}

		private static int[] resolveAxisBorder(int startBorder, int endBorder, int targetSize) {
			if (targetSize <= 0) {
				return new int[] { 0, 0 };
			}
			int totalBorder = Math.max(0, startBorder) + Math.max(0, endBorder);
			if (totalBorder <= targetSize) {
				return new int[] { Math.max(0, startBorder), Math.max(0, endBorder) };
			}
			if (totalBorder <= 0) {
				return new int[] { 0, 0 };
			}

			int scaledStart = Math.round(targetSize * (Math.max(0, startBorder) / (float) totalBorder));
			scaledStart = Math.max(0, Math.min(targetSize, scaledStart));
			return new int[] { scaledStart, Math.max(0, targetSize - scaledStart) };
		}
	}

	/**
	 * GUI 内容区域包围盒。
	 */
	record RegionBounds(int left, int top, int width, int height) {
		RegionBounds include(RegionBounds other) {
			if (other == null || other.width() <= 0 || other.height() <= 0) {
				return this;
			}
			if (width <= 0 || height <= 0) {
				return other;
			}

			int mergedLeft = Math.min(left, other.left());
			int mergedTop = Math.min(top, other.top());
			int mergedRight = Math.max(left + width, other.left() + other.width());
			int mergedBottom = Math.max(top + height, other.top() + other.height());
			return new RegionBounds(mergedLeft, mergedTop, mergedRight - mergedLeft, mergedBottom - mergedTop);
		}
	}

	/**
	 * 背景包裹留白。
	 */
	record RegionPadding(int left, int top, int right, int bottom) {
	}

	/**
	 * 目标区域实际采用的边框尺寸。
	 */
	private record SliceBorder(int left, int top, int right, int bottom) {
	}
}
