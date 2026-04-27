package com.makomi.client.screen;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.network.StatePanelNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 状态面板工具界面。
 */
public class StatePanelToolScreen extends Screen {
	private static final Component TITLE = Component.translatable("screen.redstonelink.state_panel.title");
	private static final Component SUBSCRIBE = Component.translatable("screen.redstonelink.state_panel.subscribe");
	private static final Component REFRESH = Component.translatable("screen.redstonelink.state_panel.refresh");
	private static final Component RECORD = Component.translatable("screen.redstonelink.state_panel.record");
	private static final Component CLEAN_ALL = Component.translatable("screen.redstonelink.state_panel.clean_all");
	private static final Component HEADER_TYPE = Component.translatable("screen.redstonelink.state_panel.header_type");
	private static final Component HEADER_SERIAL = Component.translatable("screen.redstonelink.state_panel.header_serial");
	private static final Component HEADER_STATUS = Component.translatable("screen.redstonelink.state_panel.header_status");
	private static final Component STATUS_LOADING = Component.translatable("screen.redstonelink.state_panel.status_loading");
	private static final Component STATUS_HIDDEN = Component.translatable("screen.redstonelink.state_panel.status_hidden");
	private static final StyledEditBox.Style STATE_PANEL_INPUT_BOX_STYLE = new StyledEditBox.Style(
		0xFF943434,
		0xFF9D0000,
		0xFFFFB8B8,
		0x99643B3B,
		0xFF7E4A4A,
		0xFFFFF3F3,
		0xFFD6BABA
	);
	private static final StyledButton.Style STATE_PANEL_BUTTON_STYLE = new StyledButton.Style(
		0xE0B14C4C,
		0xF0D56B6B,
		0x99644343,
		0xFF9D0000,
		0xFFFFC1C1,
		0xFF866060,
		0xFFFFF4F4,
		0xFFD5B8B8
	);

	/** 面板主体默认宽度。 */
	private static final int PANEL_WIDTH = 492;
	private static final int VISIBLE_ROWS = 8;
	private static final int ROW_HEIGHT = 20;
	private static final int INPUT_HEIGHT = 20;
	private static final int PADDING = 6;
	private static final int ACTION_BUTTON_COUNT = 3;
	private static final int BUTTON_HEIGHT = 20;
	private static final int REMOVE_BUTTON_WIDTH = 20;
	private static final int CLEAN_ALL_BUTTON_WIDTH = 72;
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_CONTENT_HEIGHT = 278;
	private static final int LIST_ROW_TEXT_OFFSET_Y = 6;
	private static final int TYPE_LABEL_TOP_OFFSET = 10;
	private static final int INPUT_TOP_OFFSET = 24;
	private static final int HEADER_TOP_EXTRA_OFFSET = 2;
	private static final int REMOVE_BUTTON_GAP = 6;
	private static final int COLUMN_GAP = 4;
	private static final int BACKGROUND_HORIZONTAL_PADDING = 14;
	private static final int BACKGROUND_TOP_PADDING = 18;
	private static final int BACKGROUND_BOTTOM_PADDING = 24;
	private static final int INPUT_LABEL_TEXT_COLOR = 0xFFFFE3E3;
	private static final int HEADER_TEXT_COLOR = 0xFFFFCBCB;
	private static final int LIST_TYPE_TEXT_COLOR = 0xFFFFF0F0;
	private static final int LIST_SERIAL_TEXT_COLOR = 0xFFFFE0E0;
	private static final int LIST_STATUS_TEXT_COLOR = 0xFFFFD0D0;
	private static final int LIST_EMPTY_TEXT_COLOR = 0xFFB67C7C;
	private static final int STATUS_SUCCESS_TEXT_COLOR = 0xFF9AE39A;
	private static final int STATUS_ERROR_TEXT_COLOR = 0xFFFFC1C1;

	private static final int LIST_LEFT_PADDING = 4;
	private static final int COL_TYPE_W = 92;
	private static final int COL_SERIAL_W = 140;
	private static final int MIN_COL_TYPE_W = 64;
	private static final int MIN_COL_SERIAL_W = 96;
	private static final int MIN_COL_STATUS_W = 72;
	private static final int TYPE_TOGGLE_PREFERRED_WIDTH = 148;
	private static final int TYPE_TOGGLE_MIN_WIDTH = 92;
	private static final int ACTION_BUTTON_MIN_WIDTH = 56;

	private final List<StatePanelNetwork.SubscriptionEntryPayload> initialSubscriptions;
	private final List<StatePanelNetwork.StatePanelSnapshotEntry> entries = new ArrayList<>();
	private final List<Button> removeButtons = new ArrayList<>();
	private EditBox inputBox;
	private Button typeToggleButton;
	private Button subscribeButton;
	private Button refreshButton;
	private Button recordButton;
	private Button cleanAllButton;
	private LinkNodeType currentType = LinkNodeType.CORE;
	private Component statusMessage = Component.empty();
	private int statusMessageColor = STATUS_ERROR_TEXT_COLOR;
	private int scrollOffset;
	private boolean initialRefreshRequested;
	private boolean hasAppliedServerSnapshot;

	public StatePanelToolScreen(List<StatePanelNetwork.SubscriptionEntryPayload> subscriptions) {
		super(TITLE);
		this.initialSubscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
	}

	@Override
	protected void init() {
		super.init();
		String preservedInput = inputBox == null ? "" : inputBox.getValue();
		StatePanelLayout layout = resolveLayout(width, height);
		removeButtons.clear();

		inputBox = new StyledEditBox(
			font,
			layout.panelLeft(),
			layout.inputY(),
			layout.panelWidth(),
			INPUT_HEIGHT,
			Component.empty(),
			STATE_PANEL_INPUT_BOX_STYLE
		);
		inputBox.setHint(Component.translatable("screen.redstonelink.state_panel.input_hint"));
		inputBox.setValue(preservedInput);
		addRenderableWidget(inputBox);
		setInitialFocus(inputBox);

		typeToggleButton = addRenderableWidget(
			createThemedButton(typeToggleLabel(), layout.panelLeft(), layout.typeToggleY(), layout.typeToggleWidth(), button -> {
				currentType = currentType == LinkNodeType.CORE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
				button.setMessage(typeToggleLabel());
			})
		);

		subscribeButton = addRenderableWidget(
			createThemedButton(SUBSCRIBE, layout.actionButtonX(0), layout.actionY(), layout.actionButtonWidth(), button -> subscribe())
		);
		refreshButton = addRenderableWidget(
			createThemedButton(REFRESH, layout.actionButtonX(1), layout.actionY(), layout.actionButtonWidth(), button -> requestRefresh())
		);
		recordButton = addRenderableWidget(
			createThemedButton(RECORD, layout.actionButtonX(2), layout.actionY(), layout.actionButtonWidth(), button -> openRecordingScreen())
		);

		cleanAllButton = addRenderableWidget(
			createThemedButton(CLEAN_ALL, layout.cleanAllButtonX(), layout.headerY(), CLEAN_ALL_BUTTON_WIDTH, button -> requestCleanAll())
		);

		for (int row = 0; row < VISIBLE_ROWS; row++) {
			final int visibleRow = row;
			Button removeButton = addRenderableWidget(
				createThemedButton(
					Component.literal("×"),
					layout.removeButtonX(),
					layout.listRowY(row),
					REMOVE_BUTTON_WIDTH,
					button -> removeAtVisibleRow(visibleRow)
				)
			);
			removeButtons.add(removeButton);
		}

		applyWidgetLayout(layout);
		if (!initialRefreshRequested) {
			initializeEntriesFromOpenPayload();
			requestRefresh();
			initialRefreshRequested = true;
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		StatePanelLayout layout = resolveLayout(width, height);
		applyWidgetLayout(layout);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		GuiBackgroundRenderSupport.RegionBounds baseContentBounds = resolveBaseContentBounds(layout);
		GuiHeaderRenderSupport.drawCenteredHeader(guiGraphics, font, headerSpec(), width / 2, layout.panelTop(), baseContentBounds);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.state_panel.input"),
			layout.panelLeft(),
			layout.panelTop() + TYPE_LABEL_TOP_OFFSET,
			INPUT_LABEL_TEXT_COLOR,
			false
		);

		renderHeader(guiGraphics, layout);
		renderList(guiGraphics, layout);
		renderStatusHeaderTooltip(guiGraphics, layout, mouseX, mouseY);

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawString(font, statusMessage, layout.panelLeft(), layout.statusMessageY(), statusMessageColor, false);
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		StatePanelLayout layout = resolveLayout(width, height);
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
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (entries.isEmpty()) {
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		int maxOffset = Math.max(0, entries.size() - VISIBLE_ROWS);
		if (verticalAmount > 0D) {
			scrollOffset = Math.max(0, scrollOffset - 1);
			return true;
		}
		if (verticalAmount < 0D) {
			scrollOffset = Math.min(maxOffset, scrollOffset + 1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			subscribe();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * 接收服务端快照后刷新列表。
	 */
	public void applySnapshot(List<StatePanelNetwork.StatePanelSnapshotEntry> snapshotEntries) {
		hasAppliedServerSnapshot = true;
		entries.clear();
		if (snapshotEntries != null && !snapshotEntries.isEmpty()) {
			entries.addAll(snapshotEntries);
			entries.sort(entryComparator());
		}
		normalizeScrollOffset();
	}

	/**
	 * 接收服务端反馈并展示。
	 */
	public void applyFeedback(boolean success, String messageKey, List<String> messageArgs) {
		if (messageKey == null || messageKey.isBlank()) {
			statusMessage = Component.empty();
			return;
		}
		Object[] args = messageArgs == null ? new Object[0] : messageArgs.toArray();
		statusMessage = Component.translatable(messageKey, args);
		statusMessageColor = success ? STATUS_SUCCESS_TEXT_COLOR : STATUS_ERROR_TEXT_COLOR;
	}

	private void subscribe() {
		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(inputBox.getValue());
		if (validation.empty()) {
			statusMessage = Component.translatable("screen.redstonelink.state_panel.input_empty");
			statusMessageColor = STATUS_ERROR_TEXT_COLOR;
			return;
		}
		if (!validation.valid()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", validation.invalidEntries())
			);
			statusMessageColor = STATUS_ERROR_TEXT_COLOR;
			return;
		}
		ClientPlayNetworking.send(
			new StatePanelNetwork.SubscribeStatePanelPayload(LinkNodeSemantics.toSemanticName(currentType), validation.normalizedExpression())
		);
		statusMessage = Component.empty();
	}

	private void requestRefresh() {
		ClientPlayNetworking.send(new StatePanelNetwork.RefreshStatePanelPayload());
	}

	private void requestCleanAll() {
		ClientPlayNetworking.send(new StatePanelNetwork.CleanAllStatePanelPayload());
	}

	private void openRecordingScreen() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new StatePanelRecordingScreen(this, exportSubscriptionsForRecording()));
	}

	private void removeAtVisibleRow(int visibleRow) {
		int index = scrollOffset + visibleRow;
		if (index < 0 || index >= entries.size()) {
			return;
		}
		StatePanelNetwork.StatePanelSnapshotEntry entry = entries.get(index);
		ClientPlayNetworking.send(
			new StatePanelNetwork.RemoveStatePanelSerialPayload(
				LinkNodeSemantics.toSemanticName(entry.nodeType()),
				entry.serial()
			)
		);
	}

	private void initializeEntriesFromOpenPayload() {
		entries.clear();
		for (StatePanelNetwork.SubscriptionEntryPayload subscription : initialSubscriptions) {
			entries.add(
				new StatePanelNetwork.StatePanelSnapshotEntry(
					subscription.nodeType(),
					subscription.serial(),
					subscription.displayText(),
					false,
					false,
					false,
					false,
					0,
					0,
					true
				)
			);
		}
		entries.sort(entryComparator());
		normalizeScrollOffset();
	}

	/**
	 * 导出当前状态面板订阅，用于切入录制 GUI 时展示录制范围预览。
	 */
	private List<StatePanelNetwork.SubscriptionEntryPayload> exportSubscriptionsForRecording() {
		if (!entries.isEmpty()) {
			return entries
				.stream()
				.map(entry -> new StatePanelNetwork.SubscriptionEntryPayload(entry.nodeType(), entry.serial(), entry.displayText()))
				.toList();
		}
		return List.copyOf(initialSubscriptions);
	}

	/**
	 * 渲染表头行，含列标签与右侧 clean all 按钮。
	 */
	private void renderHeader(GuiGraphics guiGraphics, StatePanelLayout layout) {
		guiGraphics.drawString(
			font,
			clipTextToWidth(HEADER_TYPE.getString(), layout.typeWidth()),
			layout.typeX(),
			layout.headerY() + LIST_ROW_TEXT_OFFSET_Y,
			HEADER_TEXT_COLOR,
			false
		);
		guiGraphics.drawString(
			font,
			clipTextToWidth(HEADER_SERIAL.getString(), layout.serialWidth()),
			layout.serialX(),
			layout.headerY() + LIST_ROW_TEXT_OFFSET_Y,
			HEADER_TEXT_COLOR,
			false
		);
		guiGraphics.drawString(
			font,
			clipTextToWidth(HEADER_STATUS.getString(), layout.statusWidth()),
			layout.statusX(),
			layout.headerY() + LIST_ROW_TEXT_OFFSET_Y,
			HEADER_TEXT_COLOR,
			false
		);
	}

	/**
	 * 为“状态”列表头提供字段释义 tooltip，帮助理解状态缩写。
	 */
	private void renderStatusHeaderTooltip(GuiGraphics guiGraphics, StatePanelLayout layout, int mouseX, int mouseY) {
		String visibleHeaderText = clipTextToWidth(HEADER_STATUS.getString(), layout.statusWidth());
		int hoverWidth = font.width(visibleHeaderText);
		if (hoverWidth <= 0) {
			return;
		}
		if (!isMouseOver(layout.statusX(), layout.headerY() + LIST_ROW_TEXT_OFFSET_Y, hoverWidth, font.lineHeight, mouseX, mouseY)) {
			return;
		}
		guiGraphics.renderTooltip(font, buildStatusHeaderTooltipLines(), Optional.empty(), mouseX, mouseY);
	}

	/**
	 * 渲染列表数据行，每列独立绘制，保证列对齐。
	 */
	private void renderList(GuiGraphics guiGraphics, StatePanelLayout layout) {
		for (int row = 0; row < VISIBLE_ROWS; row++) {
			int index = scrollOffset + row;
			int rowY = layout.listRowY(row);
			if (index >= entries.size()) {
				guiGraphics.drawString(font, "-", layout.typeX(), rowY + LIST_ROW_TEXT_OFFSET_Y, LIST_EMPTY_TEXT_COLOR, false);
				continue;
			}
			StatePanelNetwork.StatePanelSnapshotEntry entry = entries.get(index);
			String typeLabel = LinkNodeSemantics.toSemanticName(entry.nodeType());
			String serialLabel = resolveDisplayText(entry);
			String status = buildStatusText(entry, hasAppliedServerSnapshot);

			guiGraphics.drawString(
				font,
				clipTextToWidth(typeLabel, layout.typeWidth()),
				layout.typeX(),
				rowY + LIST_ROW_TEXT_OFFSET_Y,
				LIST_TYPE_TEXT_COLOR,
				false
			);
			guiGraphics.drawString(
				font,
				clipTextToWidth(serialLabel, layout.serialWidth()),
				layout.serialX(),
				rowY + LIST_ROW_TEXT_OFFSET_Y,
				LIST_SERIAL_TEXT_COLOR,
				false
			);
			guiGraphics.drawString(
				font,
				clipTextToWidth(status, layout.statusWidth()),
				layout.statusX(),
				rowY + LIST_ROW_TEXT_OFFSET_Y,
				LIST_STATUS_TEXT_COLOR,
				false
			);
		}
	}

	private void normalizeScrollOffset() {
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - VISIBLE_ROWS)));
	}

	private static Comparator<StatePanelNetwork.StatePanelSnapshotEntry> entryComparator() {
		return Comparator
			.comparing((StatePanelNetwork.StatePanelSnapshotEntry entry) -> LinkNodeSemantics.toSemanticName(entry.nodeType()))
			.thenComparingLong(StatePanelNetwork.StatePanelSnapshotEntry::serial);
	}

	static String buildStatusText(StatePanelNetwork.StatePanelSnapshotEntry entry, boolean hasAppliedServerSnapshot) {
		if (!hasAppliedServerSnapshot) {
			return STATUS_LOADING.getString();
		}
		if (entry == null || !entry.readable()) {
			return STATUS_HIDDEN.getString();
		}
		String active = entry.active() ? "act" : "idle";
		String retired = entry.retired() ? "ret" : "live";
		StringBuilder sb = new StringBuilder(32);
		sb.append(entry.online() ? "on" : "off");
		sb.append(' ');
		sb.append(active);
		sb.append(' ');
		sb.append(retired);
		sb.append(" i");
		sb.append(entry.inputPower());
		sb.append(" o");
		sb.append(entry.outputPower());
		return sb.toString();
	}

	private static String resolveDisplayText(StatePanelNetwork.StatePanelSnapshotEntry entry) {
		if (entry == null) {
			return "-";
		}
		String normalizedDisplayText = NodeAliasDisplayUtil.normalizeAlias(entry.displayText());
		if (!normalizedDisplayText.isEmpty()) {
			return normalizedDisplayText;
		}
		return NodeAliasDisplayUtil.formatDisplayText("", entry.serial());
	}

	private Component typeToggleLabel() {
		return HEADER_TYPE.copy().append(": ").append(Component.literal(LinkNodeSemantics.toSemanticName(currentType)));
	}

	/**
	 * 创建状态面板统一主题按钮。
	 */
	private Button createThemedButton(Component message, int x, int y, int width, Button.OnPress onPress) {
		return new StyledButton(x, y, width, BUTTON_HEIGHT, message, onPress, STATE_PANEL_BUTTON_STYLE);
	}

	/**
	 * @return 状态面板背景预设
	 */
	private GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.STATE_PANEL;
	}

	/**
	 * 解析状态面板内容包围盒。
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveContentBounds(StatePanelLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds baseBounds = resolveBaseContentBounds(layout);
		return baseBounds.include(GuiHeaderRenderSupport.resolveCenteredHeaderBounds(font, headerSpec(), width / 2, layout.panelTop(), baseBounds));
	}

	/**
	 * 解析不含头部图标的基础内容包围盒，用于将图标锚定到整组组件外框。
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveBaseContentBounds(StatePanelLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds bounds = GuiHeaderRenderSupport.resolveCenteredHeaderTextBounds(
			font,
			headerSpec(),
			width / 2,
			layout.panelTop()
		);
		bounds =
			bounds.include(
				leftAlignedTextBounds(
					Component.translatable("screen.redstonelink.state_panel.input"),
					layout.panelLeft(),
					layout.panelTop() + TYPE_LABEL_TOP_OFFSET
				)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(
					layout.panelLeft(),
					layout.inputY(),
					layout.panelWidth(),
					layout.statusMessageY() + font.lineHeight - layout.inputY()
				)
			);
		if (!statusMessage.getString().isEmpty()) {
			bounds = bounds.include(leftAlignedTextBounds(statusMessage, layout.panelLeft(), layout.statusMessageY()));
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
	 * @return 当前状态面板头部规格
	 */
	private GuiHeaderRenderSupport.HeaderSpec headerSpec() {
		return GuiHeaderContextSupport.statePanelHeader(currentType);
	}

	/**
	 * 将所有控件同步到当前屏幕布局，保证窗口变化后按钮与文本列仍共用一套几何结果。
	 */
	private void applyWidgetLayout(StatePanelLayout layout) {
		if (inputBox != null) {
			inputBox.setX(layout.panelLeft());
			inputBox.setY(layout.inputY());
			inputBox.setWidth(layout.panelWidth());
			inputBox.setHeight(INPUT_HEIGHT);
		}
		if (typeToggleButton != null) {
			typeToggleButton.setX(layout.panelLeft());
			typeToggleButton.setY(layout.typeToggleY());
			typeToggleButton.setWidth(layout.typeToggleWidth());
			typeToggleButton.setHeight(BUTTON_HEIGHT);
		}
		applyActionButtonLayout(subscribeButton, layout, 0);
		applyActionButtonLayout(refreshButton, layout, 1);
		applyActionButtonLayout(recordButton, layout, 2);
		if (cleanAllButton != null) {
			cleanAllButton.setX(layout.cleanAllButtonX());
			cleanAllButton.setY(layout.headerY());
			cleanAllButton.setWidth(CLEAN_ALL_BUTTON_WIDTH);
			cleanAllButton.setHeight(BUTTON_HEIGHT);
		}
		updateRemoveButtons(layout);
	}

	/**
	 * 同步主操作按钮几何。
	 */
	private void applyActionButtonLayout(Button button, StatePanelLayout layout, int index) {
		if (button == null) {
			return;
		}
		button.setX(layout.actionButtonX(index));
		button.setY(layout.actionY());
		button.setWidth(layout.actionButtonWidth());
		button.setHeight(BUTTON_HEIGHT);
	}

	/**
	 * 同步删除按钮位置与可见性。
	 */
	private void updateRemoveButtons(StatePanelLayout layout) {
		for (int row = 0; row < removeButtons.size(); row++) {
			Button removeButton = removeButtons.get(row);
			int index = scrollOffset + row;
			removeButton.visible = index < entries.size();
			removeButton.active = index < entries.size();
			removeButton.setX(layout.removeButtonX());
			removeButton.setY(layout.listRowY(row));
			removeButton.setWidth(REMOVE_BUTTON_WIDTH);
			removeButton.setHeight(BUTTON_HEIGHT);
		}
	}

	/**
	 * 按当前屏幕尺寸解析状态面板布局。
	 */
	static StatePanelLayout resolveLayout(int screenWidth, int screenHeight) {
		int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - SCREEN_EDGE_MARGIN * 2));
		int panelLeft = CenteredFormLayoutSupport.clampVisibleStart((screenWidth - panelWidth) / 2, panelWidth, screenWidth);
		int panelTop = CenteredFormLayoutSupport.clampVisibleStart(
			(screenHeight - PANEL_CONTENT_HEIGHT) / 2,
			PANEL_CONTENT_HEIGHT,
			screenHeight
		);
		int inputY = panelTop + INPUT_TOP_OFFSET;
		int typeToggleY = inputY + INPUT_HEIGHT + PADDING;
		int typeToggleWidth = resolveTypeToggleWidth(panelWidth);
		int actionStartX = panelLeft + typeToggleWidth + PADDING;
		int actionAreaWidth = Math.max(1, panelWidth - typeToggleWidth - PADDING);
		int actionButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(actionAreaWidth, PADDING, ACTION_BUTTON_COUNT);
		int actionY = typeToggleY;
		int headerY = actionY + BUTTON_HEIGHT + PADDING + HEADER_TOP_EXTRA_OFFSET;
		int listTop = headerY + ROW_HEIGHT;
		int statusMessageY = listTop + VISIBLE_ROWS * ROW_HEIGHT + PADDING;
		int removeButtonX = panelLeft + panelWidth - REMOVE_BUTTON_WIDTH;
		int cleanAllButtonX = panelLeft + Math.max(0, panelWidth - CLEAN_ALL_BUTTON_WIDTH);
		ColumnLayout columnLayout = resolveColumnLayout(panelLeft, panelWidth, removeButtonX);
		return new StatePanelLayout(
			panelLeft,
			panelTop,
			panelWidth,
			inputY,
			typeToggleY,
			typeToggleWidth,
			actionY,
			actionStartX,
			actionButtonWidth,
			headerY,
			listTop,
			statusMessageY,
			removeButtonX,
			cleanAllButtonX,
			columnLayout.typeX(),
			columnLayout.typeWidth(),
			columnLayout.serialX(),
			columnLayout.serialWidth(),
			columnLayout.statusX(),
			columnLayout.statusWidth()
		);
	}

	/**
	 * 解析类型切换按钮宽度，同时给右侧三颗动作按钮保留最小可点击空间。
	 */
	private static int resolveTypeToggleWidth(int panelWidth) {
		int preferredWidth = Math.min(TYPE_TOGGLE_PREFERRED_WIDTH, Math.max(TYPE_TOGGLE_MIN_WIDTH, panelWidth / 3));
		int minActionAreaWidth = ACTION_BUTTON_MIN_WIDTH * ACTION_BUTTON_COUNT + PADDING * Math.max(0, ACTION_BUTTON_COUNT - 1);
		int maxToggleWidth = Math.max(TYPE_TOGGLE_MIN_WIDTH, panelWidth - PADDING - minActionAreaWidth);
		return Math.max(TYPE_TOGGLE_MIN_WIDTH, Math.min(preferredWidth, maxToggleWidth));
	}

	/**
	 * 解析列表列宽，优先保证状态列与删除列不重叠，再让类型列和序号列按剩余宽度收敛。
	 */
	private static ColumnLayout resolveColumnLayout(int panelLeft, int panelWidth, int removeButtonX) {
		int listStartX = panelLeft + LIST_LEFT_PADDING;
		int listContentWidth = Math.max(0, removeButtonX - REMOVE_BUTTON_GAP - listStartX - COLUMN_GAP * 2);

		int typeWidth = Math.min(COL_TYPE_W, Math.max(MIN_COL_TYPE_W, listContentWidth - COL_SERIAL_W - MIN_COL_STATUS_W));
		int serialWidth = Math.min(COL_SERIAL_W, Math.max(MIN_COL_SERIAL_W, listContentWidth - typeWidth - MIN_COL_STATUS_W));
		int statusWidth = Math.max(0, listContentWidth - typeWidth - serialWidth);
		if (statusWidth < MIN_COL_STATUS_W) {
			int deficit = MIN_COL_STATUS_W - statusWidth;
			int typeReducible = Math.max(0, typeWidth - MIN_COL_TYPE_W);
			int shrinkFromType = Math.min(deficit, typeReducible);
			typeWidth -= shrinkFromType;
			deficit -= shrinkFromType;
			int serialReducible = Math.max(0, serialWidth - MIN_COL_SERIAL_W);
			int shrinkFromSerial = Math.min(deficit, serialReducible);
			serialWidth -= shrinkFromSerial;
			statusWidth = Math.max(0, listContentWidth - typeWidth - serialWidth);
		}

		int typeX = listStartX;
		int serialX = typeX + typeWidth + COLUMN_GAP;
		int statusX = serialX + serialWidth + COLUMN_GAP;
		int maxStatusWidth = Math.max(0, panelLeft + panelWidth - REMOVE_BUTTON_WIDTH - REMOVE_BUTTON_GAP - statusX);
		statusWidth = Math.min(statusWidth, maxStatusWidth);
		return new ColumnLayout(typeX, Math.max(0, typeWidth), serialX, Math.max(0, serialWidth), statusX, Math.max(0, statusWidth));
	}

	/**
	 * 按当前列宽裁剪字符串，避免文本压住删除按钮。
	 */
	private String clipTextToWidth(String text, int maxWidth) {
		if (text == null || text.isEmpty() || maxWidth <= 0) {
			return "";
		}
		return font.plainSubstrByWidth(text, maxWidth);
	}

	/**
	 * 构建“状态”列表头 tooltip 内容，解释状态串中每个缩写字段。
	 */
	static List<Component> buildStatusHeaderTooltipLines() {
		return List.of(
			Component.translatable("screen.redstonelink.state_panel.header_status.tooltip.on_off"),
			Component.translatable("screen.redstonelink.state_panel.header_status.tooltip.act_idle"),
			Component.translatable("screen.redstonelink.state_panel.header_status.tooltip.ret_live"),
			Component.translatable("screen.redstonelink.state_panel.header_status.tooltip.input_power"),
			Component.translatable("screen.redstonelink.state_panel.header_status.tooltip.output_power")
		);
	}

	/**
	 * 判断鼠标是否位于指定矩形区域内。
	 */
	private static boolean isMouseOver(int x, int y, int width, int height, int mouseX, int mouseY) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	/**
	 * 状态面板统一布局结果。
	 */
	static record StatePanelLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int inputY,
		int typeToggleY,
		int typeToggleWidth,
		int actionY,
		int actionStartX,
		int actionButtonWidth,
		int headerY,
		int listTop,
		int statusMessageY,
		int removeButtonX,
		int cleanAllButtonX,
		int typeX,
		int typeWidth,
		int serialX,
		int serialWidth,
		int statusX,
		int statusWidth
	) {
		int actionButtonX(int index) {
			return actionStartX + (actionButtonWidth + PADDING) * index;
		}

		int listRowY(int row) {
			return listTop + row * ROW_HEIGHT;
		}
	}

	/**
	 * 列布局结果。
	 */
	private record ColumnLayout(int typeX, int typeWidth, int serialX, int serialWidth, int statusX, int statusWidth) {}
}
