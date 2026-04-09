package com.makomi.client.screen;

import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.data.LinkFilterSignalMode;
import com.makomi.data.LinkFilterSignalThresholdSource;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.network.LinkFilterNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 过滤器编辑界面。
 */
public class LinkFilterEditorScreen extends Screen {
	private static final Component SAVE = Component.translatable("screen.redstonelink.link_filter.save");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.link_filter.clear");
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_CONTENT_HEIGHT = 266;
	private static final int PANEL_PREFERRED_WIDTH = 320;
	private static final int TITLE_TOP_MARGIN = 26;
	private static final int SUBTITLE_MARGIN = 14;
	private static final int GROUP_LABEL_MARGIN = 12;
	private static final int SERIAL_INPUT_HEIGHT = 54;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final int FIXED_THRESHOLD_WIDTH = 56;
	private static final int STATUS_MESSAGE_MARGIN = 14;

	private final String dimensionKey;
	private final long blockPosLong;
	private final LinkFilterKind filterKind;
	private final LinkFilterConfigSnapshot initialSnapshot;

	private MultiLineEditBox serialInputBox;
	private EditBox fixedThresholdBox;
	private Button[] nodeSetButtons = new Button[0];
	private Button[] thresholdSourceButtons = new Button[0];
	private Button[] signalModeButtons = new Button[0];
	private LinkFilterNodeSetMode currentNodeSetMode;
	private LinkFilterSignalThresholdSource currentSignalThresholdSource;
	private LinkFilterSignalMode currentSignalMode;
	private Component statusMessage = Component.empty();

	public LinkFilterEditorScreen(
		String dimensionKey,
		long blockPosLong,
		LinkFilterKind filterKind,
		LinkFilterConfigSnapshot initialSnapshot
	) {
		super(Component.translatable(titleTranslationKey(filterKind)));
		this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
		this.blockPosLong = blockPosLong;
		this.filterKind = filterKind == null ? LinkFilterKind.SEND : filterKind;
		this.initialSnapshot = initialSnapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : initialSnapshot;
		currentNodeSetMode = this.initialSnapshot.nodeSetMode();
		currentSignalThresholdSource = this.initialSnapshot.signalThresholdSource();
		currentSignalMode = this.initialSnapshot.signalMode();
	}

	@Override
	protected void init() {
		super.init();
		LinkFilterLayout layout = resolveLayout(width, height, font.lineHeight);
		String preservedSerialExpression = serialInputBox == null ? initialSnapshot.serialExpression() : serialInputBox.getValue();
		String preservedFixedThreshold = fixedThresholdBox == null
			? Integer.toString(initialSnapshot.fixedSignalThreshold())
			: fixedThresholdBox.getValue();
		serialInputBox = new MultiLineEditBox(
			font,
			layout.panelLeft(),
			layout.serialInputY(),
			layout.panelWidth(),
			SERIAL_INPUT_HEIGHT,
			Component.translatable("screen.redstonelink.link_filter.serial_input"),
			Component.empty()
		);
		serialInputBox.setCharacterLimit(com.makomi.config.RedstoneLinkConfig.command().linkSetMaxInputLength());
		serialInputBox.setValue(preservedSerialExpression);
		addRenderableWidget(serialInputBox);
		setInitialFocus(serialInputBox);

		int tripleButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 3);
		int doubleButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		nodeSetButtons = new Button[] {
			createOptionButton(
				layout.panelLeft(),
				layout.nodeSetRowY(),
				tripleButtonWidth,
				() -> currentNodeSetMode = LinkFilterNodeSetMode.WHITELIST
			),
			createOptionButton(
				layout.panelLeft() + tripleButtonWidth + BUTTON_GAP,
				layout.nodeSetRowY(),
				tripleButtonWidth,
				() -> currentNodeSetMode = LinkFilterNodeSetMode.BLOCKLIST
			),
			createOptionButton(
				layout.panelLeft() + (tripleButtonWidth + BUTTON_GAP) * 2,
				layout.nodeSetRowY(),
				tripleButtonWidth,
				() -> currentNodeSetMode = LinkFilterNodeSetMode.DISABLED
			)
		};
		thresholdSourceButtons = new Button[] {
			createOptionButton(
				layout.panelLeft(),
				layout.thresholdSourceRowY(),
				doubleButtonWidth,
				() -> currentSignalThresholdSource = LinkFilterSignalThresholdSource.FIXED_INPUT
			),
			createOptionButton(
				layout.panelLeft() + doubleButtonWidth + BUTTON_GAP,
				layout.thresholdSourceRowY(),
				doubleButtonWidth,
				() -> currentSignalThresholdSource = LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT
			)
		};
		signalModeButtons = new Button[] {
			createOptionButton(
				layout.panelLeft(),
				layout.signalModeRowY(),
				tripleButtonWidth,
				() -> currentSignalMode = LinkFilterSignalMode.UPPER_BOUND
			),
			createOptionButton(
				layout.panelLeft() + tripleButtonWidth + BUTTON_GAP,
				layout.signalModeRowY(),
				tripleButtonWidth,
				() -> currentSignalMode = LinkFilterSignalMode.LOWER_BOUND
			),
			createOptionButton(
				layout.panelLeft() + (tripleButtonWidth + BUTTON_GAP) * 2,
				layout.signalModeRowY(),
				tripleButtonWidth,
				() -> currentSignalMode = LinkFilterSignalMode.DISABLED
			)
		};

		fixedThresholdBox = new EditBox(
			font,
			layout.panelLeft(),
			layout.fixedThresholdInputY(),
			FIXED_THRESHOLD_WIDTH,
			BUTTON_HEIGHT,
			Component.translatable("screen.redstonelink.link_filter.fixed_threshold")
		);
		fixedThresholdBox.setMaxLength(2);
		fixedThresholdBox.setFilter(value -> value.chars().allMatch(Character::isDigit));
		fixedThresholdBox.setValue(preservedFixedThreshold);
		addRenderableWidget(fixedThresholdBox);

		int actionButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		addRenderableWidget(
			Button.builder(SAVE, button -> saveAndClose()).bounds(layout.panelLeft(), layout.actionButtonY(), actionButtonWidth, BUTTON_HEIGHT).build()
		);
		addRenderableWidget(
			Button
				.builder(CLEAR, button -> resetForm())
				.bounds(layout.panelLeft() + actionButtonWidth + BUTTON_GAP, layout.actionButtonY(), actionButtonWidth, BUTTON_HEIGHT)
				.build()
		);

		refreshOptionButtonMessages();
		refreshFixedThresholdState();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		LinkFilterLayout layout = resolveLayout(width, height, font.lineHeight);
		int centerX = width / 2;
		guiGraphics.drawCenteredString(font, title, centerX, layout.titleY(), 0xFFFFFF);
		guiGraphics.drawCenteredString(
			font,
			Component.translatable(
				"screen.redstonelink.link_filter.service_line",
				LinkNodeSemantics.toSemanticName(filterKind.servicedNodeType())
			),
			centerX,
			layout.titleY() + SUBTITLE_MARGIN,
			0xC8C8C8
		);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.link_filter.serial_input"), layout.panelLeft(), layout.serialLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.link_filter.node_set_mode"), layout.panelLeft(), layout.nodeSetLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.link_filter.signal_threshold_source"),
			layout.panelLeft(),
			layout.thresholdSourceLabelY(),
			0xFFFFFF,
			false
		);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.link_filter.fixed_threshold"),
			layout.panelLeft(),
			layout.fixedThresholdLabelY(),
			0xFFFFFF,
			false
		);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.link_filter.signal_mode"), layout.panelLeft(), layout.signalModeLabelY(), 0xFFFFFF, false);
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
	 * 保存当前过滤器配置并关闭界面。
	 */
	private void saveAndClose() {
		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(serialInputBox.getValue());
		if (!validation.valid()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", validation.invalidEntries())
			);
			return;
		}

		int fixedThreshold = parseFixedThreshold();
		if (fixedThreshold < 0) {
			statusMessage = Component.translatable("screen.redstonelink.link_filter.fixed_threshold_invalid");
			return;
		}
		statusMessage = Component.empty();
		ClientPlayNetworking.send(
			new LinkFilterNetwork.SaveFilterPayload(
				dimensionKey,
				blockPosLong,
				filterKind,
				new LinkFilterConfigSnapshot(
					validation.normalizedExpression(),
					currentNodeSetMode,
					currentSignalThresholdSource,
					fixedThreshold,
					currentSignalMode
				)
			)
		);
		onClose();
	}

	/**
	 * 本地清空表单，恢复到默认关闭状态。
	 */
	private void resetForm() {
		serialInputBox.setValue("");
		fixedThresholdBox.setValue("15");
		currentNodeSetMode = LinkFilterNodeSetMode.DISABLED;
		currentSignalThresholdSource = LinkFilterSignalThresholdSource.FIXED_INPUT;
		currentSignalMode = LinkFilterSignalMode.DISABLED;
		statusMessage = Component.empty();
		refreshOptionButtonMessages();
		refreshFixedThresholdState();
	}

	/**
	 * 刷新单选组按钮文案。
	 */
	private void refreshOptionButtonMessages() {
		setOptionButtonMessage(
			nodeSetButtons[0],
			currentNodeSetMode == LinkFilterNodeSetMode.WHITELIST,
			Component.translatable("screen.redstonelink.link_filter.node_set_mode.whitelist")
		);
		setOptionButtonMessage(
			nodeSetButtons[1],
			currentNodeSetMode == LinkFilterNodeSetMode.BLOCKLIST,
			Component.translatable("screen.redstonelink.link_filter.node_set_mode.blocklist")
		);
		setOptionButtonMessage(
			nodeSetButtons[2],
			currentNodeSetMode == LinkFilterNodeSetMode.DISABLED,
			Component.translatable("screen.redstonelink.link_filter.node_set_mode.disabled")
		);
		setOptionButtonMessage(
			thresholdSourceButtons[0],
			currentSignalThresholdSource == LinkFilterSignalThresholdSource.FIXED_INPUT,
			Component.translatable("screen.redstonelink.link_filter.threshold_source.fixed_input")
		);
		setOptionButtonMessage(
			thresholdSourceButtons[1],
			currentSignalThresholdSource == LinkFilterSignalThresholdSource.NEIGHBOR_MAX_INPUT,
			Component.translatable("screen.redstonelink.link_filter.threshold_source.neighbor_max_input")
		);
		setOptionButtonMessage(
			signalModeButtons[0],
			currentSignalMode == LinkFilterSignalMode.UPPER_BOUND,
			Component.translatable("screen.redstonelink.link_filter.signal_mode.upper_bound")
		);
		setOptionButtonMessage(
			signalModeButtons[1],
			currentSignalMode == LinkFilterSignalMode.LOWER_BOUND,
			Component.translatable("screen.redstonelink.link_filter.signal_mode.lower_bound")
		);
		setOptionButtonMessage(
			signalModeButtons[2],
			currentSignalMode == LinkFilterSignalMode.DISABLED,
			Component.translatable("screen.redstonelink.link_filter.signal_mode.disabled")
		);
	}

	/**
	 * 刷新固定阈值输入框可用态。
	 */
	private void refreshFixedThresholdState() {
		fixedThresholdBox.setEditable(currentSignalThresholdSource == LinkFilterSignalThresholdSource.FIXED_INPUT);
		fixedThresholdBox.active = currentSignalThresholdSource == LinkFilterSignalThresholdSource.FIXED_INPUT;
	}

	/**
	 * 解析固定阈值输入框。
	 */
	private int parseFixedThreshold() {
		if (currentSignalThresholdSource != LinkFilterSignalThresholdSource.FIXED_INPUT) {
			return 15;
		}
		if (fixedThresholdBox.getValue().isBlank()) {
			return -1;
		}
		try {
			int parsed = Integer.parseInt(fixedThresholdBox.getValue());
			return parsed >= 0 && parsed <= 15 ? parsed : -1;
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	/**
	 * 创建统一的单选按钮。
	 */
	private Button createOptionButton(int x, int y, int width, Runnable onPress) {
		Button button = Button.builder(Component.empty(), value -> {
			onPress.run();
			refreshOptionButtonMessages();
			refreshFixedThresholdState();
		}).bounds(x, y, width, BUTTON_HEIGHT).build();
		addRenderableWidget(button);
		return button;
	}

	/**
	 * 设置单选按钮文案。
	 */
	private static void setOptionButtonMessage(Button button, boolean selected, Component label) {
		if (button != null) {
			button.setMessage(Component.literal(selected ? "\u25CF " : "\u25CB ").append(label));
		}
	}

	/**
	 * 解析当前界面布局。
	 */
	static LinkFilterLayout resolveLayout(int screenWidth, int screenHeight, int fontLineHeight) {
		CenteredFormLayoutSupport.CenteredPanelBox panelBox = CenteredFormLayoutSupport.resolvePanelBox(
			screenWidth,
			screenHeight,
			PANEL_PREFERRED_WIDTH,
			PANEL_CONTENT_HEIGHT,
			SCREEN_EDGE_MARGIN
		);
		int titleY = panelBox.top() + 6;
		int serialLabelY = titleY + TITLE_TOP_MARGIN;
		int serialInputY = serialLabelY + GROUP_LABEL_MARGIN;
		int nodeSetLabelY = serialInputY + SERIAL_INPUT_HEIGHT + GROUP_LABEL_MARGIN;
		int nodeSetRowY = nodeSetLabelY + GROUP_LABEL_MARGIN;
		int thresholdSourceLabelY = nodeSetRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int thresholdSourceRowY = thresholdSourceLabelY + GROUP_LABEL_MARGIN;
		int fixedThresholdLabelY = thresholdSourceRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int fixedThresholdInputY = fixedThresholdLabelY + GROUP_LABEL_MARGIN;
		int signalModeLabelY = fixedThresholdInputY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int signalModeRowY = signalModeLabelY + GROUP_LABEL_MARGIN;
		int actionButtonY = signalModeRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int statusMessageY = actionButtonY + BUTTON_HEIGHT + STATUS_MESSAGE_MARGIN;
		return new LinkFilterLayout(
			panelBox.left(),
			panelBox.top(),
			panelBox.width(),
			titleY,
			serialLabelY,
			serialInputY,
			nodeSetLabelY,
			nodeSetRowY,
			thresholdSourceLabelY,
			thresholdSourceRowY,
			fixedThresholdLabelY,
			fixedThresholdInputY,
			signalModeLabelY,
			signalModeRowY,
			actionButtonY,
			statusMessageY
		);
	}

	/**
	 * 标题翻译键解析。
	 */
	private static String titleTranslationKey(LinkFilterKind filterKind) {
		return filterKind == LinkFilterKind.RECEIVE
			? "screen.redstonelink.link_filter.receive.title"
			: "screen.redstonelink.link_filter.send.title";
	}

	/**
	 * 过滤器界面布局结果。
	 */
	static record LinkFilterLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int titleY,
		int serialLabelY,
		int serialInputY,
		int nodeSetLabelY,
		int nodeSetRowY,
		int thresholdSourceLabelY,
		int thresholdSourceRowY,
		int fixedThresholdLabelY,
		int fixedThresholdInputY,
		int signalModeLabelY,
		int signalModeRowY,
		int actionButtonY,
		int statusMessageY
	) {
	}
}
