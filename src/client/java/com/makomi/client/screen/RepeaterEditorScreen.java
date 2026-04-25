package com.makomi.client.screen;

import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterDelay;
import com.makomi.network.LinkFilterEditorTargetKind;
import com.makomi.network.RepeaterNetwork;
import com.makomi.util.DisplayTextListFormatUtil;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 转发器最小编辑界面。
 * <p>
 * 仅暴露统一别名、固定延迟档位，以及跳转到输入/输出两侧配对界面的入口。
 * </p>
 */
public class RepeaterEditorScreen extends Screen {
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_PREFERRED_WIDTH = 320;
	private static final int PANEL_CONTENT_HEIGHT = 224;
	private static final int TITLE_TOP_MARGIN = 22;
	private static final int GROUP_LABEL_MARGIN = 8;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final int BACKGROUND_HORIZONTAL_PADDING = 12;
	private static final int BACKGROUND_TOP_PADDING = 16;
	private static final int BACKGROUND_BOTTOM_PADDING = 22;
	private static final int SUMMARY_TOOLTIP_MAX_WIDTH = 320;
	private static final int SUMMARY_TOOLTIP_MAX_ITEMS = 100;
	private static final int THEME_BORDER_COLOR = GuiBackgroundRenderSupport.BackgroundPreset.REPEATER.borderColor();
	private static final int THEME_FILL_COLOR = 0xCC47BF53;
	private static final int THEME_HOVER_FILL_COLOR = 0xE05AD868;
	private static final int THEME_DISABLED_FILL_COLOR = 0x9949844E;
	private static final int THEME_FOCUSED_BORDER_COLOR = 0xFF96FF9F;
	private static final int THEME_DISABLED_BORDER_COLOR = 0xFF2F7C37;
	private static final int THEME_TEXT_COLOR = 0xFFF4FFF4;
	private static final int THEME_DISABLED_TEXT_COLOR = 0xFFCBE6CE;
	private static final int THEME_VALUE_TEXT_COLOR = 0xFF96FF9F;
	private static final StyledEditBox.Style EDIT_BOX_STYLE = new StyledEditBox.Style(
		THEME_FILL_COLOR,
		THEME_BORDER_COLOR,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_DISABLED_FILL_COLOR,
		THEME_DISABLED_BORDER_COLOR,
		THEME_TEXT_COLOR,
		THEME_DISABLED_TEXT_COLOR
	);
	private static final StyledButton.Style BUTTON_STYLE = new StyledButton.Style(
		0xE047BF53,
		THEME_HOVER_FILL_COLOR,
		THEME_DISABLED_FILL_COLOR,
		THEME_BORDER_COLOR,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_DISABLED_BORDER_COLOR,
		THEME_TEXT_COLOR,
		THEME_DISABLED_TEXT_COLOR
	);

	private final LinkFilterEditorTargetKind targetKind;
	private final String dimensionKey;
	private final long blockPosLong;
	private final int selectedSlot;
	private final long serial;
	private final RepeaterConfigSnapshot initialSnapshot;
	private final long expectedCoreRevision;
	private final long expectedSourceRevision;
	private final List<String> inputDisplayTexts;
	private final List<String> outputDisplayTexts;
	private final String inputSummary;
	private final String outputSummary;

	private StyledEditBox aliasInput;
	private Button[] delayButtons = new Button[0];
	private RepeaterDelay currentDelay;

	public RepeaterEditorScreen(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		long serial,
		String initialDisplayAlias,
		RepeaterConfigSnapshot initialSnapshot,
		List<String> inputDisplayTexts,
		List<String> outputDisplayTexts,
		long expectedCoreRevision,
		long expectedSourceRevision
	) {
		super(Component.translatable("screen.redstonelink.repeater.title"));
		this.targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
		this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
		this.blockPosLong = blockPosLong;
		this.selectedSlot = this.targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
		this.serial = Math.max(0L, serial);
		this.initialSnapshot = initialSnapshot == null ? RepeaterConfigSnapshot.empty() : initialSnapshot;
		this.expectedCoreRevision = Math.max(0L, expectedCoreRevision);
		this.expectedSourceRevision = Math.max(0L, expectedSourceRevision);
		this.currentDelay = this.initialSnapshot.delay();
		this.initialAlias = NodeAliasDisplayUtil.normalizeAlias(initialDisplayAlias);
		this.inputDisplayTexts = normalizeDisplayTexts(this.initialSnapshot.inputSerialExpression(), inputDisplayTexts);
		this.outputDisplayTexts = normalizeDisplayTexts(this.initialSnapshot.outputSerialExpression(), outputDisplayTexts);
		this.inputSummary = buildSummary(this.initialSnapshot.inputSerialExpression(), this.inputDisplayTexts);
		this.outputSummary = buildSummary(this.initialSnapshot.outputSerialExpression(), this.outputDisplayTexts);
	}

	private final String initialAlias;

	@Override
	protected void init() {
		super.init();
		RepeaterLayout layout = resolveLayout(width, height, font.lineHeight);
		String preservedAlias = aliasInput == null ? initialAlias : aliasInput.getValue();

		aliasInput = createAliasInputBox(layout);
		aliasInput.setMaxLength(NodeAliasSavedData.maxAliasLength());
		aliasInput.setHint(Component.translatable("screen.redstonelink.pairing.alias_hint"));
		aliasInput.setValue(preservedAlias);
		addRenderableWidget(aliasInput);

		int splitButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		delayButtons = new Button[] {
			createOptionButton(layout.panelLeft(), layout.delayRowY(), splitButtonWidth, () -> currentDelay = RepeaterDelay.ONE_TICK),
			createOptionButton(
				layout.panelLeft() + splitButtonWidth + BUTTON_GAP,
				layout.delayRowY(),
				splitButtonWidth,
				() -> currentDelay = RepeaterDelay.TWO_TICKS
			)
		};

		addRenderableWidget(
			createActionButton(
				Component.translatable("screen.redstonelink.repeater.input_pair"),
				layout.panelLeft(),
				layout.inputButtonY(),
				layout.panelWidth(),
				button -> openPairing(RepeaterNetwork.INPUT_SIDE_TOKEN)
			)
		);
		addRenderableWidget(
			createActionButton(
				Component.translatable("screen.redstonelink.repeater.output_pair"),
				layout.panelLeft(),
				layout.outputButtonY(),
				layout.panelWidth(),
				button -> openPairing(RepeaterNetwork.OUTPUT_SIDE_TOKEN)
			)
		);

		int actionButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		addRenderableWidget(
			createActionButton(
				Component.translatable("screen.redstonelink.repeater.save"),
				layout.panelLeft(),
				layout.actionButtonY(),
				actionButtonWidth,
				button -> saveAndClose()
			)
		);
		addRenderableWidget(
			createActionButton(
				Component.translatable("screen.redstonelink.repeater.close"),
				layout.panelLeft() + actionButtonWidth + BUTTON_GAP,
				layout.actionButtonY(),
				actionButtonWidth,
				button -> onClose()
			)
		);

		refreshDelayButtonMessages();
		setInitialFocus(aliasInput);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		RepeaterLayout layout = resolveLayout(width, height, font.lineHeight);
		GuiBackgroundRenderSupport.RegionBounds baseContentBounds = resolveBaseContentBounds(layout);
		GuiHeaderRenderSupport.drawCenteredHeader(guiGraphics, font, headerSpec(), width / 2, layout.titleY(), baseContentBounds);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.repeater.alias"), layout.panelLeft(), layout.aliasLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.repeater.delay"), layout.panelLeft(), layout.delayLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.repeater.input_summary"), layout.panelLeft(), layout.inputLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, Component.literal(truncateSummary(inputSummary, layout.panelWidth())), layout.panelLeft(), layout.inputValueY(), themeTextColor(), false);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.repeater.output_summary"), layout.panelLeft(), layout.outputLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, Component.literal(truncateSummary(outputSummary, layout.panelWidth())), layout.panelLeft(), layout.outputValueY(), themeTextColor(), false);
		renderSummaryTooltip(guiGraphics, layout, mouseX, mouseY);
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		RepeaterLayout layout = resolveLayout(width, height, font.lineHeight);
		GuiBackgroundRenderSupport.renderWrappedRegion(
			guiGraphics,
			backgroundPreset(),
			resolveContentBounds(layout),
			new GuiBackgroundRenderSupport.RegionPadding(
				BACKGROUND_HORIZONTAL_PADDING,
				BACKGROUND_TOP_PADDING,
				BACKGROUND_HORIZONTAL_PADDING,
				BACKGROUND_BOTTOM_PADDING
			)
		);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			saveAndClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void openPairing(String sideToken) {
		ClientPlayNetworking.send(
			new RepeaterNetwork.OpenRepeaterPairingPayload(
				targetKind,
				dimensionKey,
				blockPosLong,
				selectedSlot,
				serial,
				sideToken
			)
		);
		onClose();
	}

	private void saveAndClose() {
		ClientPlayNetworking.send(
			new RepeaterNetwork.SaveRepeaterPayload(
				targetKind,
				dimensionKey,
				blockPosLong,
				selectedSlot,
				serial,
				NodeAliasDisplayUtil.normalizeAlias(aliasInput == null ? "" : aliasInput.getValue()),
				new RepeaterConfigSnapshot(
					initialSnapshot.inputSerialExpression(),
					initialSnapshot.outputSerialExpression(),
					currentDelay
				),
				expectedCoreRevision,
				expectedSourceRevision
			)
		);
		onClose();
	}

	private void refreshDelayButtonMessages() {
		setOptionButtonMessage(
			delayButtons[0],
			currentDelay == RepeaterDelay.ONE_TICK,
			Component.translatable("screen.redstonelink.repeater.delay.one_tick")
		);
		setOptionButtonMessage(
			delayButtons[1],
			currentDelay == RepeaterDelay.TWO_TICKS,
			Component.translatable("screen.redstonelink.repeater.delay.two_ticks")
		);
	}

	private Button createOptionButton(int x, int y, int width, Runnable onPress) {
		Button button = new StyledButton(x, y, width, BUTTON_HEIGHT, Component.empty(), value -> {
			onPress.run();
			refreshDelayButtonMessages();
		}, BUTTON_STYLE);
		addRenderableWidget(button);
		return button;
	}

	private StyledEditBox createAliasInputBox(RepeaterLayout layout) {
		return new StyledEditBox(
			font,
			layout.panelLeft(),
			layout.aliasInputY(),
			layout.panelWidth(),
			BUTTON_HEIGHT,
			Component.translatable("screen.redstonelink.repeater.alias"),
			EDIT_BOX_STYLE
		);
	}

	private Button createActionButton(Component message, int x, int y, int width, Button.OnPress onPress) {
		return new StyledButton(x, y, width, BUTTON_HEIGHT, message, onPress, BUTTON_STYLE);
	}

	private static void setOptionButtonMessage(Button button, boolean selected, Component label) {
		if (button != null) {
			button.setMessage(Component.literal(selected ? "* " : "o ").append(label));
		}
	}

	private static String normalizeSummary(String rawSummary) {
		String normalized = rawSummary == null ? "" : rawSummary.trim();
		return normalized.isEmpty() ? "-" : normalized;
	}

	/**
	 * 优先按展示文本列表构建摘要，缺失时回退到原始序号表达式。
	 */
	private static String buildSummary(String serialExpression, List<String> displayTexts) {
		if (displayTexts != null && !displayTexts.isEmpty()) {
			return normalizeSummary(DisplayTextListFormatUtil.buildText(displayTexts, 4096));
		}
		return normalizeSummary(serialExpression);
	}

	/**
	 * 将服务端传来的展示文本列表按当前表达式重新归一，避免数量与顺序漂移。
	 */
	private static List<String> normalizeDisplayTexts(String serialExpression, List<String> displayTexts) {
		List<Long> orderedSerials = SerialParseUtil.parseTargetsOrdered(serialExpression, 0).orderedTargets();
		return NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, displayTexts);
	}

	private String truncateSummary(String summary, int maxWidth) {
		if (summary == null || summary.isBlank()) {
			return "-";
		}
		if (font.width(summary) <= maxWidth) {
			return summary;
		}
		String suffix = "...";
		int contentWidth = Math.max(1, maxWidth - font.width(suffix));
		return font.plainSubstrByWidth(summary, contentWidth) + suffix;
	}

	/**
	 * 为输入侧与输出侧摘要补充完整悬停提示。
	 */
	private void renderSummaryTooltip(GuiGraphics guiGraphics, RepeaterLayout layout, int mouseX, int mouseY) {
		if (isMouseOverSummary(layout.panelLeft(), layout.inputLabelY(), layout.panelWidth(), layout.inputValueY(), mouseX, mouseY)) {
			renderTooltipIfPresent(
				guiGraphics,
				buildSummaryTooltipLines(initialSnapshot.inputSerialExpression(), inputDisplayTexts),
				mouseX,
				mouseY
			);
			return;
		}
		if (isMouseOverSummary(layout.panelLeft(), layout.outputLabelY(), layout.panelWidth(), layout.outputValueY(), mouseX, mouseY)) {
			renderTooltipIfPresent(
				guiGraphics,
				buildSummaryTooltipLines(initialSnapshot.outputSerialExpression(), outputDisplayTexts),
				mouseX,
				mouseY
			);
		}
	}

	/**
	 * 将摘要 tooltip 文本转换为客户端可直接绘制的组件列表。
	 */
	private List<Component> buildSummaryTooltipLines(String serialExpression, List<String> displayTexts) {
		return buildSummaryTooltipTexts(
			serialExpression,
			displayTexts,
			SUMMARY_TOOLTIP_MAX_WIDTH,
			SUMMARY_TOOLTIP_MAX_ITEMS,
			font::width
		)
			.stream()
			.map(text -> (Component) Component.literal(text))
			.toList();
	}

	/**
	 * 构建摘要 tooltip 的完整文本。
	 * <p>
	 * 优先按服务端下发的展示文本列表换行；若缺失，则退回原始序号表达式并保留 `N`/`A:B` 结构。
	 * </p>
	 */
	static List<String> buildSummaryTooltipTexts(
		String serialExpression,
		List<String> displayTexts,
		int maxWidth,
		int maxItems,
		ToIntFunction<String> widthMeasure
	) {
		if (maxWidth <= 0 || maxItems <= 0 || widthMeasure == null) {
			return List.of(normalizeSummary(serialExpression));
		}
		List<String> wrappedDisplayTexts = DisplayTextListFormatUtil.buildWrappedLines(displayTexts, maxWidth, maxItems, widthMeasure);
		if (!wrappedDisplayTexts.isEmpty()) {
			return wrappedDisplayTexts;
		}
		List<String> serialTokens = splitSummarySerialTokens(serialExpression);
		if (!serialTokens.isEmpty()) {
			List<String> wrappedSerialTokens = DisplayTextListFormatUtil.buildWrappedLines(
				serialTokens,
				maxWidth,
				maxItems,
				widthMeasure
			);
			if (!wrappedSerialTokens.isEmpty()) {
				return wrappedSerialTokens;
			}
		}
		return List.of(normalizeSummary(serialExpression));
	}

	/**
	 * 将原始序号表达式拆成 tooltip 展示段，保留 `/` 分隔的结构化语义。
	 */
	private static List<String> splitSummarySerialTokens(String serialExpression) {
		if (serialExpression == null || serialExpression.isBlank()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		for (String rawToken : serialExpression.split("/")) {
			String normalizedToken = rawToken == null ? "" : rawToken.trim();
			if (!normalizedToken.isEmpty()) {
				tokens.add(normalizedToken);
			}
		}
		return tokens.isEmpty() ? List.of() : List.copyOf(tokens);
	}

	/**
	 * 仅在 tooltip 有内容时才触发绘制，避免空提示闪烁。
	 */
	private void renderTooltipIfPresent(GuiGraphics guiGraphics, List<Component> tooltipLines, int mouseX, int mouseY) {
		if (!tooltipLines.isEmpty()) {
			guiGraphics.renderTooltip(font, tooltipLines, Optional.empty(), mouseX, mouseY);
		}
	}

	/**
	 * 判断鼠标是否悬停在摘要标签与摘要值组成的区域内。
	 */
	private boolean isMouseOverSummary(int left, int labelY, int width, int valueY, int mouseX, int mouseY) {
		return isMouseOver(left, labelY, width, valueY - labelY + font.lineHeight, mouseX, mouseY);
	}

	/**
	 * 判断鼠标是否命中指定矩形区域。
	 */
	private static boolean isMouseOver(int left, int top, int width, int height, int mouseX, int mouseY) {
		return mouseX >= left && mouseX <= left + width && mouseY >= top && mouseY <= top + height;
	}

	static RepeaterLayout resolveLayout(int screenWidth, int screenHeight, int fontLineHeight) {
		CenteredFormLayoutSupport.CenteredPanelBox panelBox = CenteredFormLayoutSupport.resolvePanelBox(
			screenWidth,
			screenHeight,
			PANEL_PREFERRED_WIDTH,
			PANEL_CONTENT_HEIGHT,
			SCREEN_EDGE_MARGIN
		);
		int titleY = panelBox.top() + 6;
		int aliasLabelY = titleY + TITLE_TOP_MARGIN;
		int aliasInputY = aliasLabelY + GROUP_LABEL_MARGIN;
		int delayLabelY = aliasInputY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int delayRowY = delayLabelY + GROUP_LABEL_MARGIN;
		int inputLabelY = delayRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int inputValueY = inputLabelY + GROUP_LABEL_MARGIN;
		int inputButtonY = inputValueY + fontLineHeight + GROUP_LABEL_MARGIN;
		int outputLabelY = inputButtonY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int outputValueY = outputLabelY + GROUP_LABEL_MARGIN;
		int outputButtonY = outputValueY + fontLineHeight + GROUP_LABEL_MARGIN;
		int actionButtonY = outputButtonY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		return new RepeaterLayout(
			panelBox.left(),
			panelBox.top(),
			panelBox.width(),
			titleY,
			aliasLabelY,
			aliasInputY,
			delayLabelY,
			delayRowY,
			inputLabelY,
			inputValueY,
			inputButtonY,
			outputLabelY,
			outputValueY,
			outputButtonY,
			actionButtonY
		);
	}

	private GuiHeaderRenderSupport.HeaderSpec headerSpec() {
		return new GuiHeaderRenderSupport.HeaderSpec(
			Component.translatable("screen.redstonelink.repeater.title"),
			Component.translatable("screen.redstonelink.repeater.serial", Long.toString(serial)),
			themeTextColor(),
			null
		);
	}

	private GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.REPEATER;
	}

	private int themeTextColor() {
		return THEME_VALUE_TEXT_COLOR;
	}

	private GuiBackgroundRenderSupport.RegionBounds resolveContentBounds(RepeaterLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds baseBounds = resolveBaseContentBounds(layout);
		return baseBounds.include(
			GuiHeaderRenderSupport.resolveCenteredHeaderBounds(font, headerSpec(), width / 2, layout.titleY(), baseBounds)
		);
	}

	private GuiBackgroundRenderSupport.RegionBounds resolveBaseContentBounds(RepeaterLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds bounds = GuiHeaderRenderSupport.resolveCenteredHeaderTextBounds(
			font,
			headerSpec(),
			width / 2,
			layout.titleY()
		);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.repeater.alias"), layout.panelLeft(), layout.aliasLabelY())
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.aliasInputY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.repeater.delay"), layout.panelLeft(), layout.delayLabelY())
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.delayRowY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.repeater.input_summary"), layout.panelLeft(), layout.inputLabelY())
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(
					Component.literal(truncateSummary(inputSummary, layout.panelWidth())),
					layout.panelLeft(),
					layout.inputValueY()
				)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.inputButtonY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.repeater.output_summary"), layout.panelLeft(), layout.outputLabelY())
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(
					Component.literal(truncateSummary(outputSummary, layout.panelWidth())),
					layout.panelLeft(),
					layout.outputValueY()
				)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.outputButtonY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.actionButtonY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		return bounds;
	}

	private GuiBackgroundRenderSupport.RegionBounds leftAlignedTextBounds(Component text, int left, int top) {
		return new GuiBackgroundRenderSupport.RegionBounds(left, top, Math.max(1, font.width(text)), font.lineHeight);
	}

	static record RepeaterLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int titleY,
		int aliasLabelY,
		int aliasInputY,
		int delayLabelY,
		int delayRowY,
		int inputLabelY,
		int inputValueY,
		int inputButtonY,
		int outputLabelY,
		int outputValueY,
		int outputButtonY,
		int actionButtonY
	) {
	}
}
