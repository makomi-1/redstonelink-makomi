package com.makomi.client.screen;

import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.data.LinkFilterSignalMode;
import com.makomi.data.LinkFilterSignalThresholdSource;
import com.makomi.data.LinkFilterTargetMode;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.network.LinkFilterEditorTargetKind;
import com.makomi.network.LinkFilterNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 过滤器编辑界面。
 */
public class LinkFilterEditorScreen extends Screen {
	private static final Component SAVE = Component.translatable("screen.redstonelink.link_filter.save");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.link_filter.clear");
	private static final StyledMultiLineEditBox.Style FILTER_INPUT_BOX_STYLE = new StyledMultiLineEditBox.Style(
		0xFFA00029,
		0xFF6A001A,
		0xFFFF8A80,
		0xFFFFCCD5
	);
	private static final StyledEditBox.Style FILTER_EDIT_BOX_STYLE = new StyledEditBox.Style(
		0xFFA00029,
		0xFF6A001A,
		0xFFFF8A80,
		0x995A2531,
		0xFF7D4B57,
		0xFFFFF4F6,
		0xFFD2B4BC
	);
	private static final StyledButton.Style FILTER_BUTTON_STYLE = new StyledButton.Style(
		0xE0A00029,
		0xF0BE1E3C,
		0x995A2531,
		0xFF6A001A,
		0xFFFF8A80,
		0xFF7D4B57,
		0xFFFFF4F6,
		0xFFD2B4BC
	);
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_CONTENT_HEIGHT = 296;
	private static final int PANEL_PREFERRED_WIDTH = 320;
	private static final int TITLE_TOP_MARGIN = 22;
	private static final int SUBTITLE_MARGIN = 14;
	private static final int GROUP_LABEL_MARGIN = 8;
	private static final int SERIAL_INPUT_HEIGHT = 54;
	private static final int CHANNEL_INPUT_MAX_LENGTH = 19;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final int FIXED_THRESHOLD_WIDTH = 56;
	private static final int STATUS_MESSAGE_MARGIN = 10;
	private static final int BACKGROUND_HORIZONTAL_PADDING = 12;
	private static final int BACKGROUND_TOP_PADDING = 16;
	private static final int BACKGROUND_BOTTOM_PADDING = 22;

	private final LinkFilterEditorTargetKind targetKind;
	private final String dimensionKey;
	private final long blockPosLong;
	private final int selectedSlot;
	private final LinkFilterKind filterKind;
	private final String initialDisplayAlias;
	private final LinkFilterConfigSnapshot initialSnapshot;

	private StyledEditBox aliasInput;
	private EditBox channelInputBox;
	private MultiLineEditBox serialInputBox;
	private EditBox fixedThresholdBox;
	private Button[] targetModeButtons = new Button[0];
	private Button[] nodeSetButtons = new Button[0];
	private Button[] thresholdSourceButtons = new Button[0];
	private Button[] signalModeButtons = new Button[0];
	private LinkFilterTargetMode currentTargetMode;
	private LinkFilterNodeSetMode currentNodeSetMode;
	private LinkFilterSignalThresholdSource currentSignalThresholdSource;
	private LinkFilterSignalMode currentSignalMode;
	private Component statusMessage = Component.empty();

	public LinkFilterEditorScreen(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		LinkFilterKind filterKind,
		String initialDisplayAlias,
		LinkFilterConfigSnapshot initialSnapshot
	) {
		super(Component.translatable(titleTranslationKey(filterKind)));
		this.targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
		this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
		this.blockPosLong = blockPosLong;
		this.selectedSlot = this.targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
		this.filterKind = filterKind == null ? LinkFilterKind.SEND : filterKind;
		this.initialDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(initialDisplayAlias);
		this.initialSnapshot = initialSnapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : initialSnapshot;
		currentTargetMode = this.initialSnapshot.targetMode();
		currentNodeSetMode = this.initialSnapshot.nodeSetMode();
		currentSignalThresholdSource = this.initialSnapshot.signalThresholdSource();
		currentSignalMode = this.initialSnapshot.signalMode();
	}

	@Override
	protected void init() {
		super.init();
		LinkFilterLayout layout = resolveLayout(width, height, font.lineHeight);
		String preservedDisplayAlias = aliasInput == null ? initialDisplayAlias : aliasInput.getValue();
		String preservedChannel = channelInputBox == null ? initialChannelValue() : channelInputBox.getValue();
		String preservedSerialExpression = serialInputBox == null ? initialSnapshot.serialExpression() : serialInputBox.getValue();
		String preservedFixedThreshold = fixedThresholdBox == null
			? Integer.toString(initialSnapshot.fixedSignalThreshold())
			: fixedThresholdBox.getValue();
		aliasInput = createAliasInputBox(layout);
		aliasInput.setMaxLength(NodeAliasSavedData.maxAliasLength());
		aliasInput.setHint(Component.translatable("screen.redstonelink.pairing.alias_hint"));
		aliasInput.setValue(preservedDisplayAlias);
		addRenderableWidget(aliasInput);
		serialInputBox = createSerialInputBox(layout);
		serialInputBox.setCharacterLimit(com.makomi.config.RedstoneLinkConfig.command().linkSetMaxInputLength());
		serialInputBox.setValue(preservedSerialExpression);
		addRenderableWidget(serialInputBox);
		channelInputBox = createChannelInputBox(layout);
		channelInputBox.setMaxLength(CHANNEL_INPUT_MAX_LENGTH);
		channelInputBox.setFilter(value -> value.chars().allMatch(Character::isDigit));
		channelInputBox.setValue(preservedChannel);
		addRenderableWidget(channelInputBox);

		int tripleButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 3);
		int doubleButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		targetModeButtons = new Button[] {
			createOptionButton(
				layout.panelLeft(),
				layout.targetModeRowY(),
				doubleButtonWidth,
				() -> switchTargetMode(LinkFilterTargetMode.SERIAL)
			),
			createOptionButton(
				layout.panelLeft() + doubleButtonWidth + BUTTON_GAP,
				layout.targetModeRowY(),
				doubleButtonWidth,
				() -> switchTargetMode(LinkFilterTargetMode.CHANNEL)
			)
		};
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

		fixedThresholdBox = new StyledEditBox(
			font,
			layout.panelLeft(),
			layout.fixedThresholdInputY(),
			FIXED_THRESHOLD_WIDTH,
			BUTTON_HEIGHT,
			Component.translatable("screen.redstonelink.link_filter.fixed_threshold"),
			FILTER_EDIT_BOX_STYLE
		);
		fixedThresholdBox.setMaxLength(2);
		fixedThresholdBox.setFilter(value -> value.chars().allMatch(Character::isDigit));
		fixedThresholdBox.setValue(preservedFixedThreshold);
		addRenderableWidget(fixedThresholdBox);

		int actionButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		addRenderableWidget(
			createActionButton(SAVE, layout.panelLeft(), layout.actionButtonY(), actionButtonWidth, button -> saveAndClose())
		);
		addRenderableWidget(
			createActionButton(
				CLEAR,
				layout.panelLeft() + actionButtonWidth + BUTTON_GAP,
				layout.actionButtonY(),
				actionButtonWidth,
				button -> resetForm()
			)
		);

		refreshOptionButtonMessages();
		refreshTargetInputState();
		refreshFixedThresholdState();
		setInitialFocus(currentTargetMode == LinkFilterTargetMode.CHANNEL ? channelInputBox : serialInputBox);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		LinkFilterLayout layout = resolveLayout(width, height, font.lineHeight);
		int centerX = width / 2;
		GuiBackgroundRenderSupport.RegionBounds baseContentBounds = resolveBaseContentBounds(layout);
		GuiHeaderRenderSupport.drawCenteredHeader(guiGraphics, font, headerSpec(), centerX, layout.titleY(), baseContentBounds);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.link_filter.alias"), layout.panelLeft(), layout.aliasLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, currentTargetInputLabel(), layout.panelLeft(), layout.serialLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.link_filter.fixed_threshold"),
			layout.panelLeft(),
			layout.fixedThresholdLabelY(),
			0xFFFFFF,
			false
		);
		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawCenteredString(font, statusMessage, centerX, layout.statusMessageY(), 0xFF6666);
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		LinkFilterLayout layout = resolveLayout(width, height, font.lineHeight);
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
	public boolean keyPressed(KeyEvent event) {
		int keyCode = event.key();
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			saveAndClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		LinkFilterTargetMode clickTargetMode = resolveTargetInputClickTarget(
			resolveLayout(width, height, font.lineHeight),
			currentTargetMode,
			mouseX,
			mouseY
		);
		if (button == 0 && clickTargetMode != null) {
			return clickTargetMode == LinkFilterTargetMode.CHANNEL
				? routeTargetInputClick(channelInputBox, clickTargetMode, mouseX, mouseY, button)
				: routeTargetInputClick(serialInputBox, clickTargetMode, mouseX, mouseY, button);
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * 保存当前过滤器配置并关闭界面。
	 */
	private void saveAndClose() {
		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(
			currentTargetMode == LinkFilterTargetMode.SERIAL ? serialInputBox.getValue() : ""
		);
		if (currentTargetMode == LinkFilterTargetMode.SERIAL && !validation.valid()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", validation.invalidEntries())
			);
			return;
		}
		long parsedChannel = currentTargetMode == LinkFilterTargetMode.CHANNEL ? parseChannelInput() : 0L;
		if (currentTargetMode == LinkFilterTargetMode.CHANNEL && parsedChannel < 0L) {
			statusMessage = Component.translatable("screen.redstonelink.link_filter.channel_invalid");
			return;
		}

		int fixedThreshold = parseFixedThreshold();
		if (fixedThreshold < 0) {
			statusMessage = Component.translatable("screen.redstonelink.link_filter.fixed_threshold_invalid");
			return;
		}
		String normalizedDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(aliasInput == null ? "" : aliasInput.getValue());
		statusMessage = Component.empty();
		ClientPlayNetworking.send(
			new LinkFilterNetwork.SaveFilterPayload(
				targetKind,
				dimensionKey,
				blockPosLong,
				selectedSlot,
				filterKind,
				normalizedDisplayAlias,
				new LinkFilterConfigSnapshot(
					currentTargetMode == LinkFilterTargetMode.SERIAL ? validation.normalizedExpression() : "",
					currentTargetMode,
					parsedChannel,
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
		if (aliasInput != null) {
			aliasInput.setValue("");
		}
		serialInputBox.setValue("");
		if (channelInputBox != null) {
			channelInputBox.setValue("");
		}
		currentTargetMode = LinkFilterTargetMode.SERIAL;
		fixedThresholdBox.setValue("15");
		currentNodeSetMode = LinkFilterNodeSetMode.DISABLED;
		currentSignalThresholdSource = LinkFilterSignalThresholdSource.FIXED_INPUT;
		currentSignalMode = LinkFilterSignalMode.DISABLED;
		statusMessage = Component.empty();
		refreshOptionButtonMessages();
		refreshTargetInputState();
		refreshFixedThresholdState();
	}

	/**
	 * 刷新单选组按钮文案。
	 */
	private void refreshOptionButtonMessages() {
		setOptionButtonMessage(
			targetModeButtons[0],
			currentTargetMode == LinkFilterTargetMode.SERIAL,
			Component.translatable("screen.redstonelink.link_filter.target_mode.serial")
		);
		setOptionButtonMessage(
			targetModeButtons[1],
			currentTargetMode == LinkFilterTargetMode.CHANNEL,
			Component.translatable("screen.redstonelink.link_filter.target_mode.channel")
		);
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
	 * 切换过滤目标模式，并清空另一种模式残留输入。
	 */
	private void switchTargetMode(LinkFilterTargetMode nextTargetMode) {
		if (nextTargetMode == null || nextTargetMode == currentTargetMode) {
			return;
		}
		currentTargetMode = nextTargetMode;
		if (currentTargetMode == LinkFilterTargetMode.SERIAL) {
			if (channelInputBox != null) {
				channelInputBox.setValue("");
			}
		} else {
			if (serialInputBox != null) {
				serialInputBox.setValue("");
			}
		}
		statusMessage = Component.empty();
		refreshOptionButtonMessages();
		refreshTargetInputState();
		focusCurrentTargetInput();
	}

	/**
	 * 刷新序号/频道输入框的可见与可编辑状态。
	 */
	private void refreshTargetInputState() {
		if (serialInputBox != null) {
			serialInputBox.visible = currentTargetMode == LinkFilterTargetMode.SERIAL;
			serialInputBox.active = currentTargetMode == LinkFilterTargetMode.SERIAL;
			if (currentTargetMode != LinkFilterTargetMode.SERIAL) {
				serialInputBox.setFocused(false);
			}
		}
		if (channelInputBox != null) {
			channelInputBox.visible = currentTargetMode == LinkFilterTargetMode.CHANNEL;
			channelInputBox.active = currentTargetMode == LinkFilterTargetMode.CHANNEL;
			channelInputBox.setEditable(currentTargetMode == LinkFilterTargetMode.CHANNEL);
			if (currentTargetMode != LinkFilterTargetMode.CHANNEL) {
				channelInputBox.setFocused(false);
			}
		}
	}

	/**
	 * 将共享输入区点击显式路由到当前模式输入框，避免隐藏的多行框抢占事件。
	 */
	private boolean routeTargetInputClick(AbstractWidget targetInput, LinkFilterTargetMode targetMode, double mouseX, double mouseY, int button) {
		if (targetInput == null || !targetInput.visible || !targetInput.active) {
			return false;
		}
		clearTargetInputFocus();
		targetInput.setFocused(true);
		setFocused(targetInput);
		boolean handled = targetInput.mouseClicked(mouseX, mouseY, button);
		return handled || isWithinTargetInputBounds(resolveLayout(width, height, font.lineHeight), targetMode, mouseX, mouseY);
	}

	/**
	 * 清理共享输入区控件焦点，避免模式切换后残留旧输入框抢焦点。
	 */
	private void clearTargetInputFocus() {
		if (serialInputBox != null) {
			serialInputBox.setFocused(false);
		}
		if (channelInputBox != null) {
			channelInputBox.setFocused(false);
		}
	}

	/**
	 * 将焦点切到当前模式对应输入框。
	 */
	private void focusCurrentTargetInput() {
		clearTargetInputFocus();
		if (currentTargetMode == LinkFilterTargetMode.CHANNEL) {
			if (channelInputBox != null && channelInputBox.visible && channelInputBox.active) {
				channelInputBox.setFocused(true);
				setFocused(channelInputBox);
			}
			return;
		}
		if (serialInputBox != null && serialInputBox.visible && serialInputBox.active) {
			serialInputBox.setFocused(true);
			setFocused(serialInputBox);
		}
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
	 * 解析频道输入框；空白回退为 0，非法值返回 -1。
	 */
	private long parseChannelInput() {
		if (channelInputBox == null || channelInputBox.getValue().isBlank()) {
			return 0L;
		}
		try {
			long parsed = Long.parseLong(channelInputBox.getValue());
			return parsed >= 0L ? parsed : -1L;
		} catch (NumberFormatException ignored) {
			return -1L;
		}
	}

	/**
	 * 创建统一的单选按钮。
	 */
	private Button createOptionButton(int x, int y, int width, Runnable onPress) {
		Button button = new StyledButton(x, y, width, BUTTON_HEIGHT, Component.empty(), value -> {
			onPress.run();
			refreshOptionButtonMessages();
			refreshFixedThresholdState();
		}, FILTER_BUTTON_STYLE);
		addRenderableWidget(button);
		return button;
	}

	/**
	 * 创建过滤器序号输入框，沿用与背景一致的主题色。
	 */
	private MultiLineEditBox createSerialInputBox(LinkFilterLayout layout) {
		return new StyledMultiLineEditBox(
			font,
			layout.panelLeft(),
			layout.serialInputY(),
			layout.panelWidth(),
			SERIAL_INPUT_HEIGHT,
			Component.translatable("screen.redstonelink.link_filter.serial_input"),
			Component.empty(),
			FILTER_INPUT_BOX_STYLE
		);
	}

	/**
	 * 创建过滤器频道单行输入框。
	 */
	private StyledEditBox createChannelInputBox(LinkFilterLayout layout) {
		return new StyledEditBox(
			font,
			layout.panelLeft(),
			layout.channelInputY(),
			layout.panelWidth(),
			BUTTON_HEIGHT,
			Component.translatable("screen.redstonelink.link_filter.channel_input"),
			FILTER_EDIT_BOX_STYLE
		);
	}

	/**
	 * 创建过滤器别名单行输入框。
	 */
	private StyledEditBox createAliasInputBox(LinkFilterLayout layout) {
		return new StyledEditBox(
			font,
			layout.panelLeft(),
			layout.aliasInputY(),
			layout.panelWidth(),
			BUTTON_HEIGHT,
			Component.translatable("screen.redstonelink.link_filter.alias"),
			FILTER_EDIT_BOX_STYLE
		);
	}

	/**
	 * 创建过滤器主操作按钮。
	 */
	private Button createActionButton(Component message, int x, int y, int width, Button.OnPress onPress) {
		return new StyledButton(x, y, width, BUTTON_HEIGHT, message, onPress, FILTER_BUTTON_STYLE);
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
		int aliasLabelY = titleY + TITLE_TOP_MARGIN;
		int aliasInputY = aliasLabelY + GROUP_LABEL_MARGIN;
		int targetModeLabelY = aliasInputY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int targetModeRowY = targetModeLabelY;
		int serialLabelY = targetModeRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int serialInputY = serialLabelY + GROUP_LABEL_MARGIN;
		int channelInputY = serialInputY;
		int nodeSetLabelY = serialInputY + SERIAL_INPUT_HEIGHT + GROUP_LABEL_MARGIN;
		int nodeSetRowY = nodeSetLabelY;
		int thresholdSourceLabelY = nodeSetRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int thresholdSourceRowY = thresholdSourceLabelY;
		int fixedThresholdLabelY = thresholdSourceRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int fixedThresholdInputY = fixedThresholdLabelY + GROUP_LABEL_MARGIN;
		int signalModeLabelY = fixedThresholdInputY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int signalModeRowY = signalModeLabelY;
		int actionButtonY = signalModeRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int statusMessageY = actionButtonY + BUTTON_HEIGHT + STATUS_MESSAGE_MARGIN;
		return new LinkFilterLayout(
			panelBox.left(),
			panelBox.top(),
			panelBox.width(),
			titleY,
			aliasLabelY,
			aliasInputY,
			targetModeLabelY,
			targetModeRowY,
			serialLabelY,
			serialInputY,
			channelInputY,
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
	 * 解析共享输入区点击应落到哪种输入模式。
	 */
	static LinkFilterTargetMode resolveTargetInputClickTarget(
		LinkFilterLayout layout,
		LinkFilterTargetMode currentTargetMode,
		double mouseX,
		double mouseY
	) {
		if (layout == null || currentTargetMode == null) {
			return null;
		}
		return isWithinTargetInputBounds(layout, currentTargetMode, mouseX, mouseY) ? currentTargetMode : null;
	}

	/**
	 * 判断鼠标是否命中当前模式对应的共享输入区外框。
	 */
	private static boolean isWithinTargetInputBounds(
		LinkFilterLayout layout,
		LinkFilterTargetMode targetMode,
		double mouseX,
		double mouseY
	) {
		if (layout == null || targetMode == null) {
			return false;
		}
		int inputX = layout.panelLeft();
		int inputY = targetMode == LinkFilterTargetMode.CHANNEL ? layout.channelInputY() : layout.serialInputY();
		int inputHeight = targetMode == LinkFilterTargetMode.CHANNEL ? BUTTON_HEIGHT : SERIAL_INPUT_HEIGHT;
		return mouseX >= inputX &&
			mouseX < inputX + layout.panelWidth() &&
			mouseY >= inputY &&
			mouseY < inputY + inputHeight;
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
	 * @return 过滤器编辑器对应的背景预设
	 */
	private GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.FILTER_EDITOR;
	}

	/**
	 * 解析当前过滤器编辑界面的内容包围盒。
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveContentBounds(LinkFilterLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds baseBounds = resolveBaseContentBounds(layout);
		return baseBounds.include(GuiHeaderRenderSupport.resolveCenteredHeaderBounds(font, headerSpec(), width / 2, layout.titleY(), baseBounds));
	}

	/**
	 * 解析不含头部图标的基础内容包围盒，用于给图标提供统一锚点。
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveBaseContentBounds(LinkFilterLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds bounds = GuiHeaderRenderSupport.resolveCenteredHeaderTextBounds(
			font,
			headerSpec(),
			width / 2,
			layout.titleY()
		);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.link_filter.alias"), layout.panelLeft(), layout.aliasLabelY())
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.aliasInputY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.targetModeRowY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(currentTargetInputLabel(), layout.panelLeft(), layout.serialLabelY())
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.serialInputY(), layout.panelWidth(), SERIAL_INPUT_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.nodeSetRowY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.thresholdSourceRowY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(
					Component.translatable("screen.redstonelink.link_filter.fixed_threshold"),
					layout.panelLeft(),
					layout.fixedThresholdLabelY()
				)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.fixedThresholdInputY(), FIXED_THRESHOLD_WIDTH, BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.signalModeRowY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.actionButtonY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		if (!statusMessage.getString().isEmpty()) {
			bounds = bounds.include(centeredTextBounds(statusMessage, width / 2, layout.statusMessageY()));
		}
		return bounds;
	}

	/**
	 * 解析当前目标输入区标签。
	 */
	private Component currentTargetInputLabel() {
		return currentTargetMode == LinkFilterTargetMode.CHANNEL
			? Component.translatable("screen.redstonelink.link_filter.channel_input")
			: Component.translatable("screen.redstonelink.link_filter.serial_input");
	}

	/**
	 * 解析初始频道输入文本。
	 */
	private String initialChannelValue() {
		return initialSnapshot.channel() > 0L ? Long.toString(initialSnapshot.channel()) : "";
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
	 * @return 当前过滤器头部规格
	 */
	private GuiHeaderRenderSupport.HeaderSpec headerSpec() {
		return GuiHeaderContextSupport.filterHeader(filterKind, backgroundPreset().borderColor());
	}

	/**
	 * 过滤器界面布局结果。
	 */
	static record LinkFilterLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int titleY,
		int aliasLabelY,
		int aliasInputY,
		int targetModeLabelY,
		int targetModeRowY,
		int serialLabelY,
		int serialInputY,
		int channelInputY,
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
