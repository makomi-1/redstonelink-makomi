package com.makomi.client.screen;

import com.makomi.data.StatePanelRecordingSessionService;
import com.makomi.network.StatePanelNetwork;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 状态面板录制配置界面。
 * <p>
 * 该界面只负责“录制参数配置 + 开始/结束控制 + 状态查看”，
 * 录制结果的主处理仍交给网页端。
 * </p>
 */
public class StatePanelRecordingScreen extends Screen {
	private static final Component TITLE = Component.translatable("screen.redstonelink.state_panel.recording.title");
	private static final Component REFRESH = Component.translatable("screen.redstonelink.state_panel.recording.refresh");
	private static final Component START = Component.translatable("screen.redstonelink.state_panel.recording.start");
	private static final Component STOP = Component.translatable("screen.redstonelink.state_panel.recording.stop");
	private static final Component BACK = Component.translatable("screen.redstonelink.state_panel.recording.back");
	private static final int PANEL_WIDTH = 448;
	private static final int PANEL_HEIGHT = 306;
	private static final int PADDING = 6;
	private static final int FIELD_HEIGHT = 20;
	private static final int BUTTON_HEIGHT = 20;
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
	private StyledEditBox titleBox;
	private StyledEditBox sampleEveryTicksBox;
	private StyledEditBox capacityBox;
	private Button autoOpenWebButton;
	private Button refreshButton;
	private Button startButton;
	private Button stopButton;
	private Button backButton;
	private StatePanelNetwork.StatePanelRecordingSessionPayload sessionSnapshot;
	private Component statusMessage = Component.empty();
	private int statusMessageColor = STATUS_ERROR_TEXT_COLOR;
	private boolean autoOpenWeb = true;

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

		autoOpenWebButton = addRenderableWidget(
			createThemedButton(autoOpenWebLabel(), layout.fieldX(), layout.autoOpenButtonY(), layout.fieldWidth(), button -> toggleAutoOpenWeb())
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

		if (sessionSnapshot == null) {
			sampleEveryTicksBox.setValue(Integer.toString(StatePanelRecordingSessionService.DEFAULT_SAMPLE_EVERY_TICKS));
			capacityBox.setValue(Integer.toString(StatePanelRecordingSessionService.DEFAULT_CAPACITY_PER_NODE));
		}
		updateControlsFromSession();
		requestSessionRefresh();
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderTransparentBackground(guiGraphics);
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
					Integer.toString(sessionSnapshot.subscriptionCount())
				),
				layout.fieldX(),
				layout.runtimeInfoY(),
				SUBTEXT_COLOR,
				false
			);
		}

		renderSubscriptionPreview(guiGraphics, layout);

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawString(font, statusMessage, layout.panelLeft(), layout.statusMessageY(), statusMessageColor, false);
		}
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
				titleBox.setValue(payload.title());
				sampleEveryTicksBox.setValue(Integer.toString(payload.sampleEveryTicks()));
				capacityBox.setValue(Integer.toString(payload.capacityPerNode()));
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
		ClientPlayNetworking.send(
			new StatePanelNetwork.StartStatePanelRecordingPayload(
				titleBox.getValue(),
				sampleEveryTicks,
				capacity,
				autoOpenWeb
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
		if (autoOpenWebButton != null) {
			autoOpenWebButton.active = !active;
			autoOpenWebButton.setMessage(autoOpenWebLabel());
		}
		if (startButton != null) {
			startButton.active = !active && !subscriptions.isEmpty();
		}
		if (stopButton != null) {
			stopButton.active = active;
		}
	}

	private void renderSubscriptionPreview(GuiGraphics guiGraphics, PanelLayout layout) {
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.state_panel.recording.subscription_preview"),
			layout.panelLeft(),
			layout.previewTitleY(),
			LABEL_TEXT_COLOR,
			false
		);
		int maxRows = 4;
		for (int row = 0; row < maxRows; row++) {
			int textY = layout.previewRowY(row);
			if (row >= subscriptions.size()) {
				guiGraphics.drawString(font, "-", layout.panelLeft(), textY, SUBTEXT_COLOR, false);
				continue;
			}
			StatePanelNetwork.SubscriptionEntryPayload subscription = subscriptions.get(row);
			String previewText = font.plainSubstrByWidth(
				subscription.displayText() + " [" + subscription.serial() + "]",
				layout.panelWidth()
			);
			guiGraphics.drawString(font, previewText, layout.panelLeft(), textY, VALUE_TEXT_COLOR, false);
		}
		if (subscriptions.size() > maxRows) {
			guiGraphics.drawString(
				font,
				Component.translatable(
					"screen.redstonelink.state_panel.recording.subscription_more",
					Integer.toString(subscriptions.size() - maxRows)
				),
				layout.fieldX(),
				layout.previewFooterY(),
				SUBTEXT_COLOR,
				false
			);
		}
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
		int actionButtonWidth = (panelWidth - (PADDING * 3)) / 4;
		return new PanelLayout(
			panelLeft,
			panelTop,
			panelWidth,
			panelHeight,
			panelTop + 8,
			panelTop + 30,
			panelTop + 52,
			panelTop + 80,
			panelTop + 108,
			panelTop + 136,
			panelTop + 164,
			panelTop + 194,
			panelTop + 224,
			panelTop + 246,
			panelTop + 286,
			fieldX,
			fieldWidth,
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
		int autoOpenButtonY,
		int actionButtonsY,
		int previewTitleY,
		int previewStartY,
		int statusMessageY,
		int fieldX,
		int fieldWidth,
		int actionButtonWidth
	) {
		int actionButtonX(int index) {
			return panelLeft + (actionButtonWidth + PADDING) * index;
		}

		int previewRowY(int row) {
			return previewStartY + row * 10;
		}

		int previewFooterY() {
			return previewStartY + 40;
		}
	}
}
