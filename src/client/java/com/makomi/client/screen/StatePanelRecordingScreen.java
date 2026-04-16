package com.makomi.client.screen;

import com.makomi.client.web.LocalWebAppBridgeService;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.StatePanelRecordingSessionService;
import com.makomi.network.StatePanelNetwork;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 状态面板录制配置界面。
 * <p>
 * 该界面负责“录制参数配置 + 订阅子集选择 + 开始/结束控制 + 状态查看”，
 * 录制结果的主处理仍交给网页端。
 * </p>
 */
public class StatePanelRecordingScreen extends Screen {
	private static final Component TITLE = Component.translatable("screen.redstonelink.state_panel.recording.title");
	private static final Component REFRESH = Component.translatable("screen.redstonelink.state_panel.recording.refresh");
	private static final Component START = Component.translatable("screen.redstonelink.state_panel.recording.start");
	private static final Component STOP = Component.translatable("screen.redstonelink.state_panel.recording.stop");
	private static final Component BACK = Component.translatable("screen.redstonelink.state_panel.recording.back");
	private static final Component SELECT_ALL = Component.translatable("screen.redstonelink.state_panel.recording.select_all");
	private static final Component CLEAR_SELECTION = Component.translatable("screen.redstonelink.state_panel.recording.clear_selection");
	private static final Component SCROLL_UP = Component.translatable("screen.redstonelink.state_panel.recording.scroll_up");
	private static final Component SCROLL_DOWN = Component.translatable("screen.redstonelink.state_panel.recording.scroll_down");
	private static final Component OPEN_RECORDING_PAGE = Component.translatable("screen.redstonelink.state_panel.recording.open_recording_page");
	private static final int PANEL_WIDTH = 448;
	private static final int PANEL_HEIGHT = 424;
	private static final int PADDING = 6;
	private static final int FIELD_HEIGHT = 20;
	private static final int BUTTON_HEIGHT = 20;
	private static final int VISIBLE_SUBSCRIPTION_ROWS = 5;
	private static final int SUBSCRIPTION_ROW_STEP = 22;
	private static final int STATUS_SUCCESS_TEXT_COLOR = 0xFF9AE39A;
	private static final int STATUS_ERROR_TEXT_COLOR = 0xFFFFC1C1;
	private static final int LABEL_TEXT_COLOR = 0xFFFFE3E3;
	private static final int VALUE_TEXT_COLOR = 0xFFFFF3F3;
	private static final int SUBTEXT_COLOR = 0xFFD8B5B5;
	private static final StyledEditBox.Style RECORDING_INPUT_BOX_STYLE = new StyledEditBox.Style(
		0xFF943434,
		0xFF9D0000,
		0xFFFFB8B8,
		0x99643B3B,
		0xFF7E4A4A,
		0xFFFFF3F3,
		0xFFD6BABA
	);
	private static final StyledButton.Style RECORDING_BUTTON_STYLE = new StyledButton.Style(
		0xE0B14C4C,
		0xF0D56B6B,
		0x99644343,
		0xFF9D0000,
		0xFFFFC1C1,
		0xFF866060,
		0xFFFFF4F4,
		0xFFD5B8B8
	);

	private final Screen parentScreen;
	private final List<StatePanelNetwork.SubscriptionEntryPayload> subscriptions;
	private final Set<String> selectedSubscriptionKeys = new LinkedHashSet<>();
	private final List<Button> subscriptionButtons = new ArrayList<>();
	private StyledEditBox titleBox;
	private StyledEditBox sampleEveryTicksBox;
	private StyledEditBox capacityBox;
	private StyledEditBox durationTicksBox;
	private Button autoOpenWebButton;
	private Button openRecordingPageButton;
	private Button refreshButton;
	private Button startButton;
	private Button stopButton;
	private Button backButton;
	private Button selectAllButton;
	private Button clearSelectionButton;
	private Button scrollUpButton;
	private Button scrollDownButton;
	private StatePanelNetwork.StatePanelRecordingSessionPayload sessionSnapshot;
	private Component statusMessage = Component.empty();
	private int statusMessageColor = STATUS_ERROR_TEXT_COLOR;
	private boolean autoOpenWeb = true;
	private int subscriptionScrollOffset = 0;

	public StatePanelRecordingScreen(Screen parentScreen, List<StatePanelNetwork.SubscriptionEntryPayload> subscriptions) {
		super(TITLE);
		this.parentScreen = parentScreen;
		this.subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
	}

	@Override
	protected void init() {
		super.init();
		PanelLayout layout = resolveLayout(width, height);
		String preservedTitle = titleBox == null ? "" : titleBox.getValue();
		String preservedSampleEveryTicks = sampleEveryTicksBox == null
			? Integer.toString(StatePanelRecordingSessionService.DEFAULT_SAMPLE_EVERY_TICKS)
			: sampleEveryTicksBox.getValue();
		String preservedCapacity = capacityBox == null
			? Integer.toString(StatePanelRecordingSessionService.DEFAULT_CAPACITY_PER_NODE)
			: capacityBox.getValue();
		String preservedDurationTicks = durationTicksBox == null ? "" : durationTicksBox.getValue();
		subscriptionButtons.clear();

		titleBox = addRenderableWidget(
			new StyledEditBox(
				font,
				layout.fieldX(),
				layout.titleFieldY(),
				layout.fieldWidth(),
				FIELD_HEIGHT,
				Component.empty(),
				RECORDING_INPUT_BOX_STYLE
			)
		);
		titleBox.setHint(Component.translatable("screen.redstonelink.state_panel.recording.title_hint"));
		titleBox.setValue(preservedTitle);

		sampleEveryTicksBox = addRenderableWidget(
			new StyledEditBox(
				font,
				layout.fieldX(),
				layout.sampleFieldY(),
				layout.fieldWidth(),
				FIELD_HEIGHT,
				Component.empty(),
				RECORDING_INPUT_BOX_STYLE
			)
		);
		sampleEveryTicksBox.setValue(preservedSampleEveryTicks);

		capacityBox = addRenderableWidget(
			new StyledEditBox(
				font,
				layout.fieldX(),
				layout.capacityFieldY(),
				layout.fieldWidth(),
				FIELD_HEIGHT,
				Component.empty(),
				RECORDING_INPUT_BOX_STYLE
			)
		);
		capacityBox.setValue(preservedCapacity);

		durationTicksBox = addRenderableWidget(
			new StyledEditBox(
				font,
				layout.fieldX(),
				layout.durationFieldY(),
				layout.fieldWidth(),
				FIELD_HEIGHT,
				Component.empty(),
				RECORDING_INPUT_BOX_STYLE
			)
		);
		durationTicksBox.setHint(Component.translatable("screen.redstonelink.state_panel.recording.duration_ticks_hint"));
		durationTicksBox.setValue(preservedDurationTicks);

		autoOpenWebButton = addRenderableWidget(
			createThemedButton(
				autoOpenWebLabel(),
				layout.splitFieldButtonX(0),
				layout.autoOpenButtonY(),
				layout.splitFieldButtonWidth(),
				button -> toggleAutoOpenWeb()
			)
		);
		openRecordingPageButton = addRenderableWidget(
			createThemedButton(
				OPEN_RECORDING_PAGE,
				layout.splitFieldButtonX(1),
				layout.autoOpenButtonY(),
				layout.splitFieldButtonWidth(),
				button -> openRecordingPage()
			)
		);
		refreshButton = addRenderableWidget(
			createThemedButton(REFRESH, layout.actionButtonX(0), layout.actionButtonsY(), layout.actionButtonWidth(), button -> requestSessionRefresh())
		);
		startButton = addRenderableWidget(
			createThemedButton(START, layout.actionButtonX(1), layout.actionButtonsY(), layout.actionButtonWidth(), button -> startRecording())
		);
		stopButton = addRenderableWidget(
			createThemedButton(STOP, layout.actionButtonX(2), layout.actionButtonsY(), layout.actionButtonWidth(), button -> stopRecording())
		);
		backButton = addRenderableWidget(
			createThemedButton(BACK, layout.actionButtonX(3), layout.actionButtonsY(), layout.actionButtonWidth(), button -> onClose())
		);
		selectAllButton = addRenderableWidget(
			createThemedButton(
				SELECT_ALL,
				layout.actionButtonX(0),
				layout.selectionButtonsY(),
				layout.actionButtonWidth(),
				button -> selectAllSubscriptions()
			)
		);
		clearSelectionButton = addRenderableWidget(
			createThemedButton(
				CLEAR_SELECTION,
				layout.actionButtonX(1),
				layout.selectionButtonsY(),
				layout.actionButtonWidth(),
				button -> clearSelectedSubscriptions()
			)
		);
		scrollUpButton = addRenderableWidget(
			createThemedButton(
				SCROLL_UP,
				layout.actionButtonX(2),
				layout.selectionButtonsY(),
				layout.actionButtonWidth(),
				button -> adjustSubscriptionScroll(-1)
			)
		);
		scrollDownButton = addRenderableWidget(
			createThemedButton(
				SCROLL_DOWN,
				layout.actionButtonX(3),
				layout.selectionButtonsY(),
				layout.actionButtonWidth(),
				button -> adjustSubscriptionScroll(1)
			)
		);
		for (int row = 0; row < VISIBLE_SUBSCRIPTION_ROWS; row++) {
			final int visibleRow = row;
			subscriptionButtons.add(
				addRenderableWidget(
					createThemedButton(
						Component.literal("-"),
						layout.panelLeft(),
						layout.selectionRowY(row),
						layout.panelWidth(),
						button -> toggleSubscriptionAtVisibleRow(visibleRow)
					)
				)
			);
		}

		if (sessionSnapshot == null) {
			sampleEveryTicksBox.setValue(Integer.toString(StatePanelRecordingSessionService.DEFAULT_SAMPLE_EVERY_TICKS));
			capacityBox.setValue(Integer.toString(StatePanelRecordingSessionService.DEFAULT_CAPACITY_PER_NODE));
			durationTicksBox.setValue("");
		}
		updateControlsFromSession();
		requestSessionRefresh();
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		PanelLayout layout = resolveLayout(width, height);
		GuiBackgroundRenderSupport.renderWrappedRegion(
			guiGraphics,
			GuiBackgroundRenderSupport.BackgroundPreset.STATE_PANEL,
			new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.panelTop(), layout.panelWidth(), layout.panelHeight()),
			new GuiBackgroundRenderSupport.RegionPadding(12, 14, 12, 18)
		);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		PanelLayout layout = resolveLayout(width, height);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		guiGraphics.drawCenteredString(font, TITLE, width / 2, layout.titleY(), VALUE_TEXT_COLOR);
		guiGraphics.drawString(
			font,
			Component.translatable(
				"screen.redstonelink.state_panel.recording.subscription_count",
				Integer.toString(subscriptions.size())
			),
			layout.panelLeft(),
			layout.summaryY(),
			LABEL_TEXT_COLOR,
			false
		);
		guiGraphics.drawString(
			font,
			Component.translatable(
				sessionSnapshot != null && sessionSnapshot.active()
					? "screen.redstonelink.state_panel.recording.session_active"
					: "screen.redstonelink.state_panel.recording.session_inactive"
			),
			layout.fieldX(),
			layout.summaryY(),
			sessionSnapshot != null && sessionSnapshot.active() ? STATUS_SUCCESS_TEXT_COLOR : SUBTEXT_COLOR,
			false
		);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.state_panel.recording.title_label"), layout.panelLeft(), layout.titleFieldY() + 6, LABEL_TEXT_COLOR, false);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.state_panel.recording.sample_every_ticks"),
			layout.panelLeft(),
			layout.sampleFieldY() + 6,
			LABEL_TEXT_COLOR,
			false
		);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.state_panel.recording.capacity"),
			layout.panelLeft(),
			layout.capacityFieldY() + 6,
			LABEL_TEXT_COLOR,
			false
		);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.state_panel.recording.duration_ticks"),
			layout.panelLeft(),
			layout.durationFieldY() + 6,
			LABEL_TEXT_COLOR,
			false
		);

		if (sessionSnapshot != null && sessionSnapshot.active()) {
			guiGraphics.drawString(
				font,
				Component.translatable(
					"screen.redstonelink.state_panel.recording.started_tick",
					Long.toString(sessionSnapshot.startedTick())
				),
				layout.panelLeft(),
				layout.runtimeInfoY(),
				SUBTEXT_COLOR,
				false
			);
			guiGraphics.drawString(
				font,
				Component.translatable(
					"screen.redstonelink.state_panel.recording.mounted_count",
					Integer.toString(sessionSnapshot.mountedCount()),
					Integer.toString(sessionSnapshot.selectedNodeKeys().size())
				),
				layout.fieldX(),
				layout.runtimeInfoY(),
				SUBTEXT_COLOR,
				false
			);
		}

		renderSelectionSummary(guiGraphics, layout);

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawString(font, statusMessage, layout.panelLeft(), layout.statusMessageY(), statusMessageColor, false);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (!isMouseWithinSelectionList(mouseX, mouseY) || subscriptions.isEmpty()) {
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		if (verticalAmount > 0D) {
			return adjustSubscriptionScroll(-1);
		}
		if (verticalAmount < 0D) {
			return adjustSubscriptionScroll(1);
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parentScreen);
		}
	}

	/**
	 * 应用服务端录制会话快照。
	 */
	public void applySessionSnapshot(StatePanelNetwork.StatePanelRecordingSessionPayload payload) {
		sessionSnapshot = payload;
		if (payload != null) {
			autoOpenWeb = payload.autoOpenWeb();
			if (payload.active()) {
				replaceSelectedSubscriptionKeys(payload.selectedNodeKeys());
			}
			if (payload.active() && titleBox != null && sampleEveryTicksBox != null && capacityBox != null && durationTicksBox != null) {
				titleBox.setValue(payload.title());
				sampleEveryTicksBox.setValue(Integer.toString(payload.sampleEveryTicks()));
				capacityBox.setValue(Integer.toString(payload.capacityPerNode()));
				durationTicksBox.setValue(payload.durationTicks() > 0 ? Integer.toString(payload.durationTicks()) : "");
			}
		}
		updateControlsFromSession();
	}

	/**
	 * 应用服务端或客户端本地反馈。
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

	private void toggleAutoOpenWeb() {
		autoOpenWeb = !autoOpenWeb;
		if (autoOpenWebButton != null) {
			autoOpenWebButton.setMessage(autoOpenWebLabel());
		}
	}

	private void openRecordingPage() {
		try {
			applyFeedback(true, "message.redstonelink.web.opened", List.of(LocalWebAppBridgeService.openRecordingPage().toString()));
		} catch (RuntimeException exception) {
			applyFeedback(false, "message.redstonelink.web.open_failed", List.of(resolveOpenWebFailureReason(exception)));
		}
	}

	private void requestSessionRefresh() {
		ClientPlayNetworking.send(new StatePanelNetwork.QueryStatePanelRecordingPayload());
	}

	private void startRecording() {
		int sampleEveryTicks = parsePositiveInt(
			sampleEveryTicksBox.getValue(),
			"screen.redstonelink.state_panel.recording.invalid_sample_every_ticks"
		);
		if (sampleEveryTicks <= 0) {
			return;
		}
		int capacity = parsePositiveInt(capacityBox.getValue(), "screen.redstonelink.state_panel.recording.invalid_capacity");
		if (capacity <= 0) {
			return;
		}
		int durationTicks = parseOptionalDurationTicks(durationTicksBox.getValue());
		if (durationTicks < 0) {
			return;
		}
		List<String> selectedNodeKeys = collectSelectedNodeKeysInOrder();
		if (selectedNodeKeys.isEmpty()) {
			applyFeedback(false, "message.redstonelink.state_panel.recording.no_selection", List.of());
			return;
		}
		ClientPlayNetworking.send(
			new StatePanelNetwork.StartStatePanelRecordingPayload(
				titleBox.getValue(),
				sampleEveryTicks,
				capacity,
				durationTicks,
				autoOpenWeb,
				selectedNodeKeys
			)
		);
	}

	private void stopRecording() {
		ClientPlayNetworking.send(new StatePanelNetwork.StopStatePanelRecordingPayload());
	}

	private int parsePositiveInt(String rawValue, String errorMessageKey) {
		String normalized = rawValue == null ? "" : rawValue.trim();
		if (normalized.isEmpty()) {
			applyFeedback(false, errorMessageKey, List.of());
			return -1;
		}
		try {
			int parsed = Integer.parseInt(normalized);
			if (parsed <= 0) {
				applyFeedback(false, errorMessageKey, List.of());
				return -1;
			}
			return parsed;
		} catch (NumberFormatException ignored) {
			applyFeedback(false, errorMessageKey, List.of());
			return -1;
		}
	}

	/**
	 * 解析“定时录制”输入；空串与非正数统一视为关闭。
	 */
	private int parseOptionalDurationTicks(String rawValue) {
		String normalized = rawValue == null ? "" : rawValue.trim();
		if (normalized.isEmpty()) {
			return 0;
		}
		try {
			int parsed = Integer.parseInt(normalized);
			return Math.max(0, parsed);
		} catch (NumberFormatException ignored) {
			applyFeedback(false, "screen.redstonelink.state_panel.recording.invalid_duration_ticks", List.of());
			return -1;
		}
	}

	private void updateControlsFromSession() {
		boolean active = sessionSnapshot != null && sessionSnapshot.active();
		if (titleBox != null) {
			titleBox.active = !active;
		}
		if (sampleEveryTicksBox != null) {
			sampleEveryTicksBox.active = !active;
		}
		if (capacityBox != null) {
			capacityBox.active = !active;
		}
		if (durationTicksBox != null) {
			durationTicksBox.active = !active;
		}
		if (autoOpenWebButton != null) {
			autoOpenWebButton.active = !active;
			autoOpenWebButton.setMessage(autoOpenWebLabel());
		}
		if (openRecordingPageButton != null) {
			openRecordingPageButton.active = true;
		}
		if (refreshButton != null) {
			refreshButton.active = true;
		}
		if (startButton != null) {
			startButton.active = !active && !collectSelectedNodeKeysInOrder().isEmpty();
		}
		if (stopButton != null) {
			stopButton.active = active;
		}
		if (selectAllButton != null) {
			selectAllButton.active = !active && selectedSubscriptionKeys.size() < subscriptions.size();
		}
		if (clearSelectionButton != null) {
			clearSelectionButton.active = !active && !selectedSubscriptionKeys.isEmpty();
		}
		if (scrollUpButton != null) {
			scrollUpButton.active = !active && subscriptionScrollOffset > 0;
		}
		if (scrollDownButton != null) {
			scrollDownButton.active = !active && subscriptionScrollOffset < maxSubscriptionScrollOffset();
		}
		refreshSubscriptionButtons();
	}

	private void renderSelectionSummary(GuiGraphics guiGraphics, PanelLayout layout) {
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.state_panel.recording.subscription_selection"),
			layout.panelLeft(),
			layout.selectionTitleY(),
			LABEL_TEXT_COLOR,
			false
		);
		guiGraphics.drawString(
			font,
			Component.translatable(
				"screen.redstonelink.state_panel.recording.selected_count",
				Integer.toString(currentSelectedSubscriptionCount()),
				Integer.toString(subscriptions.size())
			),
			layout.fieldX(),
			layout.selectionTitleY(),
			SUBTEXT_COLOR,
			false
		);
	}

	/**
	 * 刷新订阅按钮的可见项与勾选状态。
	 * <p>
	 * 这里将“本地勾选状态”与“当前滚动窗口”压成 5 行按钮，
	 * 以便在不引入复杂滚动列表控件的前提下完成最小可用子集选择。
	 * </p>
	 */
	private void refreshSubscriptionButtons() {
		boolean active = sessionSnapshot != null && sessionSnapshot.active();
		for (int row = 0; row < subscriptionButtons.size(); row++) {
			Button button = subscriptionButtons.get(row);
			int subscriptionIndex = subscriptionScrollOffset + row;
			if (subscriptionIndex >= subscriptions.size()) {
				button.setMessage(Component.literal("-"));
				button.active = false;
				continue;
			}
			StatePanelNetwork.SubscriptionEntryPayload subscription = subscriptions.get(subscriptionIndex);
			boolean selected = selectedSubscriptionKeys.contains(subscriptionNodeKey(subscription));
			String prefix = selected ? "[x] " : "[ ] ";
			String displayText = NodeAliasDisplayUtil.normalizeAlias(subscription.displayText());
			if (displayText.isEmpty()) {
				displayText = NodeAliasDisplayUtil.formatDisplayText("", subscription.serial());
			}
			String label = prefix + displayText;
			button.setMessage(Component.literal(font.plainSubstrByWidth(label, Math.max(20, button.getWidth() - 12))));
			button.active = !active;
		}
	}

	private void toggleSubscriptionAtVisibleRow(int visibleRow) {
		if (sessionSnapshot != null && sessionSnapshot.active()) {
			return;
		}
		int subscriptionIndex = subscriptionScrollOffset + visibleRow;
		if (subscriptionIndex < 0 || subscriptionIndex >= subscriptions.size()) {
			return;
		}
		String nodeKey = subscriptionNodeKey(subscriptions.get(subscriptionIndex));
		if (selectedSubscriptionKeys.contains(nodeKey)) {
			selectedSubscriptionKeys.remove(nodeKey);
		} else {
			selectedSubscriptionKeys.add(nodeKey);
		}
		updateControlsFromSession();
	}

	private void selectAllSubscriptions() {
		selectedSubscriptionKeys.clear();
		for (StatePanelNetwork.SubscriptionEntryPayload subscription : subscriptions) {
			selectedSubscriptionKeys.add(subscriptionNodeKey(subscription));
		}
		updateControlsFromSession();
	}

	private void clearSelectedSubscriptions() {
		selectedSubscriptionKeys.clear();
		updateControlsFromSession();
	}

	private boolean adjustSubscriptionScroll(int delta) {
		int nextOffset = Math.max(0, Math.min(maxSubscriptionScrollOffset(), subscriptionScrollOffset + delta));
		if (nextOffset == subscriptionScrollOffset) {
			return false;
		}
		subscriptionScrollOffset = nextOffset;
		updateControlsFromSession();
		return true;
	}

	private int maxSubscriptionScrollOffset() {
		return Math.max(0, subscriptions.size() - VISIBLE_SUBSCRIPTION_ROWS);
	}

	private void replaceSelectedSubscriptionKeys(List<String> selectedNodeKeys) {
		selectedSubscriptionKeys.clear();
		if (selectedNodeKeys == null || selectedNodeKeys.isEmpty()) {
			return;
		}
		Set<String> selectedNodeKeySet = new LinkedHashSet<>(selectedNodeKeys);
		for (StatePanelNetwork.SubscriptionEntryPayload subscription : subscriptions) {
			String nodeKey = subscriptionNodeKey(subscription);
			if (selectedNodeKeySet.contains(nodeKey)) {
				selectedSubscriptionKeys.add(nodeKey);
			}
		}
	}

	private List<String> collectSelectedNodeKeysInOrder() {
		List<String> selectedNodeKeys = new ArrayList<>();
		for (StatePanelNetwork.SubscriptionEntryPayload subscription : subscriptions) {
			String nodeKey = subscriptionNodeKey(subscription);
			if (selectedSubscriptionKeys.contains(nodeKey)) {
				selectedNodeKeys.add(nodeKey);
			}
		}
		return List.copyOf(selectedNodeKeys);
	}

	private int currentSelectedSubscriptionCount() {
		if (sessionSnapshot != null && sessionSnapshot.active()) {
			return sessionSnapshot.selectedNodeKeys().size();
		}
		return selectedSubscriptionKeys.size();
	}

	private boolean isMouseWithinSelectionList(double mouseX, double mouseY) {
		PanelLayout layout = resolveLayout(width, height);
		return mouseX >= layout.panelLeft()
			&& mouseX <= layout.panelLeft() + layout.panelWidth()
			&& mouseY >= layout.selectionStartY()
			&& mouseY <= layout.selectionListBottom();
	}

	private String subscriptionNodeKey(StatePanelNetwork.SubscriptionEntryPayload subscription) {
		return LinkNodeSemantics.toSemanticName(subscription.nodeType()) + ":" + Math.max(0L, subscription.serial());
	}

	private Component autoOpenWebLabel() {
		return Component.translatable(
			"screen.redstonelink.state_panel.recording.auto_open_web",
			Component.translatable(
				autoOpenWeb
					? "screen.redstonelink.state_panel.recording.toggle_on"
					: "screen.redstonelink.state_panel.recording.toggle_off"
			)
		);
	}

	private static String resolveOpenWebFailureReason(RuntimeException exception) {
		if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
			return "open_failed";
		}
		return exception.getMessage();
	}

	private Button createThemedButton(Component message, int x, int y, int width, Button.OnPress onPress) {
		return new StyledButton(x, y, width, BUTTON_HEIGHT, message, onPress, RECORDING_BUTTON_STYLE);
	}

	static PanelLayout resolveLayout(int screenWidth, int screenHeight) {
		int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 32));
		int panelHeight = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 32));
		int panelLeft = (screenWidth - panelWidth) / 2;
		int panelTop = (screenHeight - panelHeight) / 2;
		int labelWidth = 132;
		int fieldX = panelLeft + labelWidth;
		int fieldWidth = panelWidth - labelWidth;
		int splitFieldButtonWidth = Math.max(1, (fieldWidth - PADDING) / 2);
		int actionButtonWidth = (panelWidth - (PADDING * 3)) / 4;
		return new PanelLayout(
			panelLeft,
			panelTop,
			panelWidth,
			panelHeight,
			panelTop + 8,
			panelTop + 30,
			panelTop + 52,
			panelTop + 76,
			panelTop + 102,
			panelTop + 128,
			panelTop + 154,
			panelTop + 180,
			panelTop + 208,
			panelTop + 240,
			panelTop + 258,
			panelTop + 284,
			panelTop + 398,
			fieldX,
			fieldWidth,
			splitFieldButtonWidth,
			actionButtonWidth
		);
	}

	/**
	 * 录制界面布局结果。
	 */
	record PanelLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int panelHeight,
		int titleY,
		int summaryY,
		int runtimeInfoY,
		int titleFieldY,
		int sampleFieldY,
		int capacityFieldY,
		int durationFieldY,
		int autoOpenButtonY,
		int actionButtonsY,
		int selectionTitleY,
		int selectionButtonsY,
		int selectionStartY,
		int statusMessageY,
		int fieldX,
		int fieldWidth,
		int splitFieldButtonWidth,
		int actionButtonWidth
	) {
		int splitFieldButtonX(int index) {
			return fieldX + (splitFieldButtonWidth + PADDING) * index;
		}

		int actionButtonX(int index) {
			return panelLeft + (actionButtonWidth + PADDING) * index;
		}

		int selectionRowY(int row) {
			return selectionStartY + row * SUBSCRIPTION_ROW_STEP;
		}

		int selectionListBottom() {
			return selectionStartY + ((VISIBLE_SUBSCRIPTION_ROWS - 1) * SUBSCRIPTION_ROW_STEP) + BUTTON_HEIGHT;
		}
	}
}
