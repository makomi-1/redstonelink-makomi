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
	private static final int TITLE_TOP_MARGIN = 48;
	private static final int MODE_LINE_MARGIN = 14;
	private static final int LABEL_MARGIN = 14;
	private static final int CHANNEL_NOTE_MARGIN = 2;
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
		inputBox = new MultiLineEditBox(font, inputX, inputY, layout.panelWidth(), INPUT_BOX_HEIGHT, inputLabel(), Component.empty());
		inputBox.setCharacterLimit(RedstoneLinkClientDisplayConfig.quickLink().serialCacheMaxLength());
		inputBox.setValue(preservedInput);
		addRenderableWidget(inputBox);
		setInitialFocus(inputBox);

		int cacheTypeButtonY = layout.cacheTypeButtonY();
		int buttonRowY = layout.actionButtonY();
		int actionButtonWidth = layout.actionButtonWidth();
		addRenderableWidget(
			Button.builder(SAVE, button -> saveAndClose()).bounds(layout.actionButtonX(0), buttonRowY, actionButtonWidth, BUTTON_HEIGHT).build()
		);
		addRenderableWidget(
			Button
				.builder(CLEAR, button -> {
					inputBox.setValue("");
					statusMessage = Component.empty();
				})
				.bounds(layout.actionButtonX(1), buttonRowY, actionButtonWidth, BUTTON_HEIGHT)
				.build()
		);

		cacheTypeButton = addRenderableWidget(
			Button
				.builder(cacheTypeButtonLabel(), button -> {
					currentSerialCacheType = currentSerialCacheType == LinkNodeType.TRIGGER_SOURCE
						? LinkNodeType.CORE
						: LinkNodeType.TRIGGER_SOURCE;
					button.setMessage(cacheTypeButtonLabel());
				})
				.bounds(layout.panelLeft(), cacheTypeButtonY, layout.panelWidth(), BUTTON_HEIGHT)
				.build()
		);
		cacheTypeButton.active = isSerialMode();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		QuickLinkLayout layout = resolveLayout(width, height, font.lineHeight);
		int centerX = width / 2;
		int titleY = layout.titleY();
		int leftX = layout.panelLeft();
		guiGraphics.drawCenteredString(font, title, centerX, titleY, 0xFFFFFF);
		guiGraphics.drawCenteredString(
			font,
			Component.translatable("screen.redstonelink.quick_link.mode_line", Component.translatable(currentMode().translationKey())),
			centerX,
			titleY + MODE_LINE_MARGIN,
			0xC8C8C8
		);
		if (isSerialMode()) {
			guiGraphics.drawString(font, inputLabel(), leftX, layout.inputLabelY(), 0xFFFFFF, false);
		} else {
			guiGraphics.drawString(font, inputLabel(), leftX, layout.inputLabelY(), 0xFFFFFF, false);
			guiGraphics.drawString(
				font,
				Component.translatable("screen.redstonelink.quick_link.channel_note"),
				leftX,
				layout.channelNoteY(),
				0xE0B040,
				false
			);
		}

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawCenteredString(font, statusMessage, centerX, layout.statusMessageY(), 0xFF6666);
		}
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
				isSerialMode() ? initialSnapshot.channelCache() : inputBox.getValue()
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
		int channelNoteY = inputY - CHANNEL_NOTE_MARGIN;
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
			channelNoteY,
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
		int channelNoteY,
		int cacheTypeButtonY,
		int actionButtonY,
		int actionButtonWidth,
		int statusMessageY
	) {
		int actionButtonX(int index) {
			return panelLeft + (actionButtonWidth + BUTTON_GAP) * Math.max(0, index);
		}
	}
}
