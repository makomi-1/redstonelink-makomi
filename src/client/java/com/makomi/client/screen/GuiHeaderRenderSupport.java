package com.makomi.client.screen;

import com.makomi.RedstoneLink;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * GUI 头部标题与图标渲染支持。
 * <p>
 * 统一封装标题、副标题、图标锚点和最小包围盒计算，
 * 便于不同 screen 复用同一套头部布局规则。
 * </p>
 */
final class GuiHeaderRenderSupport {
	private static final int SUBTITLE_MARGIN = 14;
	private static final int ICON_TEXTURE_SIZE = 16;
	private static final int ICON_RENDER_SCALE = 4;
	private static final int ICON_SIZE = ICON_TEXTURE_SIZE * ICON_RENDER_SCALE;
	private static final IconTextureSpec TRIGGER_SOURCE_ICON_LEFT = new IconTextureSpec(
		texture("triggersource_icon_left.png"),
		new VisibleBounds(4, 6, 10, 10)
	);
	private static final IconTextureSpec TRIGGER_SOURCE_ICON_RIGHT = new IconTextureSpec(
		texture("triggersource_icon_right.png"),
		new VisibleBounds(7, 3, 9, 13)
	);
	private static final IconTextureSpec CORE_ICON_LEFT = new IconTextureSpec(
		texture("core_icon_left.png"),
		new VisibleBounds(5, 6, 9, 9)
	);
	private static final IconTextureSpec CORE_ICON_RIGHT = new IconTextureSpec(
		texture("core_icon_right.png"),
		new VisibleBounds(7, 3, 9, 13)
	);
	private static final IconTextureSpec FILTER_ICON_LEFT = new IconTextureSpec(
		texture("filter_icon_left.png"),
		new VisibleBounds(5, 6, 11, 9)
	);
	private static final IconTextureSpec FILTER_ICON_RIGHT = new IconTextureSpec(
		texture("filter_icon_right.png"),
		new VisibleBounds(7, 2, 9, 12)
	);

	private GuiHeaderRenderSupport() {
	}

	/**
	 * 绘制居中的头部区域。
	 */
	static void drawCenteredHeader(GuiGraphicsExtractor guiGraphics, Font font, HeaderSpec spec, int centerX, int topY) {
		drawCenteredHeader(guiGraphics, font, spec, centerX, topY, resolveCenteredHeaderTextBounds(font, spec, centerX, topY));
	}

	/**
	 * 绘制居中的头部区域，并将角标锚定到指定组件包围盒的左右上角。
	 */
	static void drawCenteredHeader(
		GuiGraphicsExtractor guiGraphics,
		Font font,
		HeaderSpec spec,
		int centerX,
		int topY,
		GuiBackgroundRenderSupport.RegionBounds componentBounds
	) {
		HeaderLayout layout = resolveCenteredHeaderLayout(font, spec, centerX, topY, componentBounds);
		if (hasText(spec.title())) {
			GuiTitleRenderSupport.drawCenteredPrimaryTitle(guiGraphics, font, spec.title(), centerX, topY);
		}
		if (hasText(spec.subtitle())) {
			GuiTitleRenderSupport.drawCenteredSecondaryTitle(guiGraphics, font, spec.subtitle(), centerX, topY + SUBTITLE_MARGIN, spec.subtitleColor());
		}
		renderIcon(guiGraphics, layout.leftIconTexture(), layout.leftIconLeft(), layout.leftIconTop());
		renderIcon(guiGraphics, layout.rightIconTexture(), layout.rightIconLeft(), layout.rightIconTop());
	}

	/**
	 * 解析居中头部的最小包围盒。
	 */
	static GuiBackgroundRenderSupport.RegionBounds resolveCenteredHeaderBounds(Font font, HeaderSpec spec, int centerX, int topY) {
		return resolveCenteredHeaderBounds(font, spec, centerX, topY, resolveCenteredHeaderTextBounds(font, spec, centerX, topY));
	}

	/**
	 * 解析居中头部的最小包围盒，并将图标锚定到指定组件包围盒。
	 */
	static GuiBackgroundRenderSupport.RegionBounds resolveCenteredHeaderBounds(
		Font font,
		HeaderSpec spec,
		int centerX,
		int topY,
		GuiBackgroundRenderSupport.RegionBounds componentBounds
	) {
		return resolveCenteredHeaderLayout(font, spec, centerX, topY, componentBounds).bounds();
	}

	/**
	 * 解析居中头部的文本包围盒；无文本时返回零尺寸占位盒。
	 */
	static GuiBackgroundRenderSupport.RegionBounds resolveCenteredHeaderTextBounds(Font font, HeaderSpec spec, int centerX, int topY) {
		GuiBackgroundRenderSupport.RegionBounds textBounds = mergeBounds(
			centeredTextBounds(font, spec.title(), centerX, topY),
			centeredTextBounds(font, spec.subtitle(), centerX, topY + SUBTITLE_MARGIN)
		);
		return textBounds != null ? textBounds : new GuiBackgroundRenderSupport.RegionBounds(centerX, topY, 0, 0);
	}

	/**
	 * 解析头部布局结果。
	 */
	private static HeaderLayout resolveCenteredHeaderLayout(
		Font font,
		HeaderSpec spec,
		int centerX,
		int topY,
		GuiBackgroundRenderSupport.RegionBounds componentBounds
	) {
		GuiBackgroundRenderSupport.RegionBounds textBounds = resolveCenteredHeaderTextBounds(font, spec, centerX, topY);
		GuiBackgroundRenderSupport.RegionBounds anchorBounds = resolveAnchorBounds(componentBounds, textBounds);
		HeaderIcon headerIcon = spec.icon();
		IconPair pair = resolveIconPair(headerIcon);
		int iconAnchorOffsetY = headerIcon == null ? 0 : headerIcon.anchorOffsetY();
		GuiBackgroundRenderSupport.RegionBounds leftBounds = null;
		GuiBackgroundRenderSupport.RegionBounds rightBounds = null;
		int leftIconLeft = 0;
		int leftIconTop = 0;
		int rightIconLeft = 0;
		int rightIconTop = 0;
		if (pair.left() != null) {
			leftIconLeft = anchorBounds.left() - pair.left().visibleBounds().leftScaled();
			leftIconTop = anchorBounds.top() - pair.left().visibleBounds().topScaled() + iconAnchorOffsetY;
			leftBounds = new GuiBackgroundRenderSupport.RegionBounds(leftIconLeft, leftIconTop, ICON_SIZE, ICON_SIZE);
		}
		if (pair.right() != null) {
			rightIconLeft = anchorBounds.left() + anchorBounds.width() - pair.right().visibleBounds().rightExclusiveScaled();
			rightIconTop = anchorBounds.top() - pair.right().visibleBounds().topScaled() + iconAnchorOffsetY;
			rightBounds = new GuiBackgroundRenderSupport.RegionBounds(rightIconLeft, rightIconTop, ICON_SIZE, ICON_SIZE);
		}
		return new HeaderLayout(
			mergeBounds(mergeBounds(textBounds, leftBounds), rightBounds),
			pair.left() == null ? null : pair.left().texture(),
			leftIconLeft,
			leftIconTop,
			pair.right() == null ? null : pair.right().texture(),
			rightIconLeft,
			rightIconTop
		);
	}

	/**
	 * 解析图标锚定使用的组件包围盒；无有效组件时回退到标题文本本身。
	 */
	private static GuiBackgroundRenderSupport.RegionBounds resolveAnchorBounds(
		GuiBackgroundRenderSupport.RegionBounds componentBounds,
		GuiBackgroundRenderSupport.RegionBounds textBounds
	) {
		if (componentBounds != null && componentBounds.width() > 0 && componentBounds.height() > 0) {
			return componentBounds;
		}
		return textBounds;
	}

	/**
	 * 解析居中文本包围盒；空文本返回 `null`。
	 */
	private static GuiBackgroundRenderSupport.RegionBounds centeredTextBounds(Font font, Component text, int centerX, int topY) {
		if (!hasText(text)) {
			return null;
		}
		int textWidth = Math.max(1, font.width(text));
		return new GuiBackgroundRenderSupport.RegionBounds(centerX - (textWidth / 2), topY, textWidth, font.lineHeight);
	}

	/**
	 * 合并两个包围盒；允许任一参数为空。
	 */
	private static GuiBackgroundRenderSupport.RegionBounds mergeBounds(
		GuiBackgroundRenderSupport.RegionBounds first,
		GuiBackgroundRenderSupport.RegionBounds second
	) {
		if (first == null) {
			return second;
		}
		if (second == null) {
			return first;
		}
		return first.include(second);
	}

	/**
	 * 判断组件是否包含可见文本。
	 */
	private static boolean hasText(Component text) {
		return text != null && !text.getString().isEmpty();
	}

	/**
	 * 渲染头部角标图标。
	 */
	private static void renderIcon(GuiGraphicsExtractor guiGraphics, Identifier texture, int left, int top) {
		if (texture == null) {
			return;
		}
		guiGraphics.blit(
			RenderPipelines.GUI_TEXTURED,
			texture,
			left,
			top,
			0.0F,
			0.0F,
			ICON_SIZE,
			ICON_SIZE,
			ICON_TEXTURE_SIZE,
			ICON_TEXTURE_SIZE,
			ICON_TEXTURE_SIZE,
			ICON_TEXTURE_SIZE
		);
	}

	/**
	 * 解析图标贴图对。
	 */
	private static IconPair resolveIconPair(HeaderIcon icon) {
		if (icon == null) {
			return IconPair.EMPTY;
		}
		return switch (icon.kind()) {
			case TRIGGER_SOURCE -> new IconPair(TRIGGER_SOURCE_ICON_LEFT, TRIGGER_SOURCE_ICON_RIGHT);
			case CORE -> new IconPair(CORE_ICON_LEFT, CORE_ICON_RIGHT);
			case FILTER -> new IconPair(FILTER_ICON_LEFT, FILTER_ICON_RIGHT);
		};
	}

	/**
	 * 生成 GUI 贴图路径。
	 */
	private static Identifier texture(String fileName) {
		return Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "textures/gui/" + fileName);
	}

	/**
	 * 头部渲染规格。
	 */
	record HeaderSpec(Component title, Component subtitle, int subtitleColor, HeaderIcon icon) {
		HeaderSpec {
			title = title == null ? Component.empty() : title;
			subtitle = subtitle == null ? Component.empty() : subtitle;
		}
	}

	/**
	 * 头部图标语义。
	 */
	record HeaderIcon(IconKind kind, int anchorOffsetY) {
		HeaderIcon(IconKind kind) {
			this(kind, 0);
		}
	}

	/**
	 * 头部图标种类。
	 */
	enum IconKind {
		TRIGGER_SOURCE,
		CORE,
		FILTER,
	}

	/**
	 * 头部布局结果。
	 */
	private record HeaderLayout(
		GuiBackgroundRenderSupport.RegionBounds bounds,
		Identifier leftIconTexture,
		int leftIconLeft,
		int leftIconTop,
		Identifier rightIconTexture,
		int rightIconLeft,
		int rightIconTop
	) {}

	/**
	 * 单张图标贴图与其非透明像素包围盒。
	 */
	private record IconTextureSpec(Identifier texture, VisibleBounds visibleBounds) {}

	/**
	 * 同类图标的左右两侧贴图对。
	 */
	private record IconPair(IconTextureSpec left, IconTextureSpec right) {
		private static final IconPair EMPTY = new IconPair(null, null);
	}

	/**
	 * 图标非透明像素包围盒（基于原始 16x16 贴图）。
	 */
	private record VisibleBounds(int minX, int minY, int maxX, int maxY) {
		int leftScaled() {
			return minX * ICON_RENDER_SCALE;
		}

		int topScaled() {
			return minY * ICON_RENDER_SCALE;
		}

		int rightExclusiveScaled() {
			return (maxX + 1) * ICON_RENDER_SCALE;
		}
	}
}
