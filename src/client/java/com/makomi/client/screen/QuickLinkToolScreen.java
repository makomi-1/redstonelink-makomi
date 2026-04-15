package com.makomi.client.screen;

import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import com.makomi.network.QuickLinkNetwork;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 快速连接工具缓存编辑界面。
 */
public class QuickLinkToolScreen extends Screen {
	private static final Component SAVE = Component.translatable("screen.redstonelink.quick_link.save");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.quick_link.clear");
	private static final StyledMultiLineEditBox.Style QUICK_LINK_SERIAL_INPUT_BOX_STYLE = new StyledMultiLineEditBox.Style(
		0xFF8D2A2A,
		0xFF610000,
		0xFFFFC2C2,
		0xFFFFD5D5
	);
	private static final StyledMultiLineEditBox.Style QUICK_LINK_CHANNEL_INPUT_BOX_STYLE = new StyledMultiLineEditBox.Style(
		0xFF24416B,
		0xFF294879,
		0xFFD7E7FF,
		0xFFD7E7FF
	);
	private static final StyledButton.Style QUICK_LINK_SERIAL_BUTTON_STYLE = new StyledButton.Style(
		0xE0A54545,
		0xF0C85D5D,
		0x995F3434,
		0xFF610000,
		0xFFFFC2C2,
		0xFF744C4C,
		0xFFFFF3F3,
		0xFFD5B8B8
	);
	private static final StyledButton.Style QUICK_LINK_CHANNEL_BUTTON_STYLE = new StyledButton.Style(
		0xE0456DA5,
		0xF05A84C2,
		0x992E4363,
		0xFF294879,
		0xFF5E8FD3,
		0xFF263A56,
		0xFFF2F7FF,
		0xFFA6B7CE
	);
	private static final int TITLE_TOP_MARGIN = 48;
	private static final int MODE_LINE_MARGIN = 14;
	private static final int LABEL_MARGIN = 14;
	private static final int CACHE_TYPE_BUTTON_MARGIN = 8;
	private static final int BUTTON_ROW_MARGIN = 6;
	private static final int STATUS_MESSAGE_MARGIN = 16;
	private static final int BUTTON_WIDTH = 108;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final int INPUT_BOX_WIDTH = BUTTON_WIDTH * 2 + BUTTON_GAP;
	private static final int INPUT_BOX_HEIGHT = 64;
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_CONTENT_HEIGHT = 188;
	private static final int ACTION_BUTTON_COUNT = 2;
	private static final int BACKGROUND_HORIZONTAL_PADDING = 12;
	private static final int BACKGROUND_TOP_PADDING = 18;
	private static final int BACKGROUND_BOTTOM_PADDING = 26;
	private static final Theme QUICK_LINK_SERIAL_THEME = new Theme(
		GuiBackgroundRenderSupport.BackgroundPreset.QUICK_LINK_SERIAL,
		QUICK_LINK_SERIAL_INPUT_BOX_STYLE,
		QUICK_LINK_SERIAL_BUTTON_STYLE,
		0xFFFFF2F2,
		0xFFFFD0D0
	);
	private static final Theme QUICK_LINK_CHANNEL_THEME = new Theme(
		GuiBackgroundRenderSupport.BackgroundPreset.QUICK_LINK_CHANNEL,
		QUICK_LINK_CHANNEL_INPUT_BOX_STYLE,
		QUICK_LINK_CHANNEL_BUTTON_STYLE,
		0xFFF0F6FF,
		0xFFD7E7FF
	);

	private final QuickLinkToolData.Snapshot initialSnapshot;

	private MultiLineEditBox inputBox;
	private Button cacheTypeButton;
	private Component statusMessage = Component.empty();
	private LinkNodeType currentSerialCacheType;

	public QuickLinkToolScreen(QuickLinkToolData.Snapshot snapshot) {
		super(Component.translatable("screen.redstonelink.quick_link.title"));
		initialSnapshot = QuickLinkToolData.normalize(snapshot);
		currentSerialCacheType = initialSnapshot.serialCacheType();
	}

	@Override
	protected void init() {
		super.init();
		String preservedInput = inputBox == null ? initialInputValue() : inputBox.getValue();
		QuickLinkLayout layout = resolveLayout(width, height, font.lineHeight);
		int inputX = layout.panelLeft();
		int inputY = layout.inputY();
		inputBox = new StyledMultiLineEditBox(
			font,
			inputX,
			inputY,
			layout.panelWidth(),
			INPUT_BOX_HEIGHT,
			inputLabel(),
			Component.empty(),
			currentTheme().inputBoxStyle()
		);
		inputBox.setCharacterLimit(RedstoneLinkClientDisplayConfig.quickLink().serialCacheMaxLength());
		inputBox.setValue(preservedInput);
		addRenderableWidget(inputBox);
		setInitialFocus(inputBox);

		int cacheTypeButtonY = layout.cacheTypeButtonY();
		int buttonRowY = layout.actionButtonY();
		int actionButtonWidth = layout.actionButtonWidth();
		addRenderableWidget(
			createActionButton(SAVE, layout.actionButtonX(0), buttonRowY, actionButtonWidth, button -> saveAndClose())
		);
		addRenderableWidget(
			createActionButton(CLEAR, layout.actionButtonX(1), buttonRowY, actionButtonWidth, button -> {
				inputBox.setValue("");
				statusMessage = Component.empty();
			})
		);

		cacheTypeButton = addRenderableWidget(
			new StyledButton(
				layout.panelLeft(),
				cacheTypeButtonY,
				layout.panelWidth(),
				BUTTON_HEIGHT,
				cacheTypeButtonLabel(),
				button -> {
					currentSerialCacheType = currentSerialCacheType == LinkNodeType.TRIGGER_SOURCE
						? LinkNodeType.CORE
						: LinkNodeType.TRIGGER_SOURCE;
					button.setMessage(cacheTypeButtonLabel());
				},
				currentTheme().buttonStyle()
			)
		);
		cacheTypeButton.active = isSerialMode();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		QuickLinkLayout layout = resolveLayout(width, height, font.lineHeight);
		int centerX = width / 2;
		int leftX = layout.panelLeft();
		Theme theme = currentTheme();
		GuiBackgroundRenderSupport.RegionBounds baseContentBounds = resolveBaseContentBounds(layout);
		GuiHeaderRenderSupport.drawCenteredHeader(guiGraphics, font, headerSpec(), centerX, layout.titleY(), baseContentBounds);
		guiGraphics.drawString(font, inputLabel(), leftX, layout.inputLabelY(), theme.labelTextColor(), false);

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawCenteredString(font, statusMessage, centerX, layout.statusMessageY(), theme.statusMessageColor());
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		QuickLinkLayout layout = resolveLayout(width, height, font.lineHeight);
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

	/**
	 * 保存缓存并关闭界面。
	 */
	private void saveAndClose() {
		SerialInputSyntaxSupport.ValidationResult validation = isSerialMode()
			? validateSerialInput(inputBox.getValue())
			: new SerialInputSyntaxSupport.ValidationResult("", List.of());
		if (isSerialMode() && !validation.valid()) {
			return;
		}

		ClientPlayNetworking.send(
			new QuickLinkNetwork.SaveQuickLinkPayload(
				currentMode().token(),
				LinkNodeSemantics.toSemanticName(currentSerialCacheType),
				isSerialMode() ? validation.normalizedExpression() : initialSnapshot.serialCacheExpression(),
				isSerialMode() ? initialSnapshot.channelCache() : inputBox.getValue(),
				initialSnapshot.applyEditMode().token()
			)
		);
		onClose();
	}

	/**
	 * 校验序号模式输入，仅在客户端做语法级提示。
	 */
	private SerialInputSyntaxSupport.ValidationResult validateSerialInput(String rawInput) {
		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(rawInput);
		if (validation.empty()) {
			statusMessage = Component.empty();
			return validation;
		}
		if (!validation.valid()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", validation.invalidEntries())
			);
			return validation;
		}
		statusMessage = Component.empty();
		return validation;
	}

	/**
	 * @return 当前界面模式
	 */
	private QuickLinkToolData.Mode currentMode() {
		return initialSnapshot.mode();
	}

	/**
	 * @return 当前是否为序号模式
	 */
	private boolean isSerialMode() {
		return currentMode() == QuickLinkToolData.Mode.SERIAL;
	}

	/**
	 * @return 输入框标题
	 */
	private Component inputLabel() {
		return isSerialMode()
			? Component.translatable("screen.redstonelink.quick_link.serial_input")
			: Component.translatable("screen.redstonelink.quick_link.channel_input");
	}

	/**
	 * @return 输入框初始值
	 */
	private String initialInputValue() {
		return isSerialMode() ? initialSnapshot.serialCacheExpression() : initialSnapshot.channelCache();
	}

	/**
	 * @return 缓存类型按钮文案
	 */
	private Component cacheTypeButtonLabel() {
		return Component.translatable(
			"screen.redstonelink.quick_link.serial_cache_type_button",
			LinkNodeSemantics.toSemanticName(currentSerialCacheType)
		);
	}

	/**
	 * @return 当前快速连接工具头部规格
	 */
	private GuiHeaderRenderSupport.HeaderSpec headerSpec() {
		return GuiHeaderContextSupport.quickLinkHeader(currentMode(), currentSerialCacheType);
	}

	/**
	 * 创建快速连接工具主操作按钮。
	 */
	private Button createActionButton(Component message, int x, int y, int width, Button.OnPress onPress) {
		return new StyledButton(x, y, width, BUTTON_HEIGHT, message, onPress, currentTheme().buttonStyle());
	}

	/**
	 * @return 当前轮次接入的快速连接工具背景预设
	 */
	private GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return currentTheme().backgroundPreset();
	}

	/**
	 * @return 当前模式对应的主题样式
	 */
	private Theme currentTheme() {
		return currentMode() == QuickLinkToolData.Mode.CHANNEL ? QUICK_LINK_CHANNEL_THEME : QUICK_LINK_SERIAL_THEME;
	}

	/**
	 * 解析当前界面的内容包围盒。
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveContentBounds(QuickLinkLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds baseBounds = resolveBaseContentBounds(layout);
		return baseBounds.include(GuiHeaderRenderSupport.resolveCenteredHeaderBounds(font, headerSpec(), width / 2, layout.titleY(), baseBounds));
	}

	/**
	 * 解析不含头部图标的基础内容包围盒，用于统一图标锚点。
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveBaseContentBounds(QuickLinkLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds bounds = GuiHeaderRenderSupport.resolveCenteredHeaderTextBounds(
			font,
			headerSpec(),
			width / 2,
			layout.titleY()
		);
		bounds = bounds.include(leftAlignedTextBounds(inputLabel(), layout.panelLeft(), layout.inputLabelY()));
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.inputY(), layout.panelWidth(), INPUT_BOX_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(
					layout.panelLeft(),
					layout.cacheTypeButtonY(),
					layout.panelWidth(),
					BUTTON_HEIGHT
				)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(
					layout.panelLeft(),
					layout.actionButtonY(),
					layout.panelWidth(),
					BUTTON_HEIGHT
				)
			);
		if (!statusMessage.getString().isEmpty()) {
			bounds = bounds.include(centeredTextBounds(statusMessage, width / 2, layout.statusMessageY()));
		}
		return bounds;
	}

	/**
	 * 生成左对齐文本包围盒。
	 */
	private GuiBackgroundRenderSupport.RegionBounds leftAlignedTextBounds(Component text, int left, int top) {
		return new GuiBackgroundRenderSupport.RegionBounds(left, top, Math.max(1, font.width(text)), font.lineHeight);
	}

	/**
	 * 生成居中文本包围盒。
	 */
	private GuiBackgroundRenderSupport.RegionBounds centeredTextBounds(Component text, int centerX, int top) {
		int textWidth = Math.max(1, font.width(text));
		return new GuiBackgroundRenderSupport.RegionBounds(centerX - (textWidth / 2), top, textWidth, font.lineHeight);
	}

	/**
	 * @return 输入框左上角 X 坐标
	 */
	static QuickLinkLayout resolveLayout(int screenWidth, int screenHeight, int fontLineHeight) {
		CenteredFormLayoutSupport.CenteredPanelBox panelBox = CenteredFormLayoutSupport.resolvePanelBox(
			screenWidth,
			screenHeight,
			INPUT_BOX_WIDTH,
			PANEL_CONTENT_HEIGHT,
			SCREEN_EDGE_MARGIN
		);
		int titleY = panelBox.top();
		int inputLabelY = titleY + TITLE_TOP_MARGIN - LABEL_MARGIN;
		int inputY = titleY + TITLE_TOP_MARGIN;
		int cacheTypeButtonY = inputY + INPUT_BOX_HEIGHT + CACHE_TYPE_BUTTON_MARGIN + 4;
		int actionButtonY = cacheTypeButtonY + BUTTON_HEIGHT + BUTTON_ROW_MARGIN;
		int actionButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(panelBox.width(), BUTTON_GAP, ACTION_BUTTON_COUNT);
		int statusMessageY = actionButtonY + BUTTON_HEIGHT + STATUS_MESSAGE_MARGIN;
		return new QuickLinkLayout(
			panelBox.left(),
			panelBox.top(),
			panelBox.width(),
			titleY,
			inputLabelY,
			inputY,
			cacheTypeButtonY,
			actionButtonY,
			actionButtonWidth,
			statusMessageY
		);
	}

	/**
	 * QuickLink 编辑界面布局结果。
	 */
	static record QuickLinkLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int titleY,
		int inputLabelY,
		int inputY,
		int cacheTypeButtonY,
		int actionButtonY,
		int actionButtonWidth,
		int statusMessageY
	) {
		int actionButtonX(int index) {
			return panelLeft + (actionButtonWidth + BUTTON_GAP) * Math.max(0, index);
		}
	}

	/**
	 * quick-link 各模式复用的界面主题。
	 */
	private record Theme(
		GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset,
		StyledMultiLineEditBox.Style inputBoxStyle,
		StyledButton.Style buttonStyle,
		int labelTextColor,
		int statusMessageColor
	) {
	}
}
