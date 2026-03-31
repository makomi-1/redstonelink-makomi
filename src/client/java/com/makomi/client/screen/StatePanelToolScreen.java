package com.makomi.client.screen;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.network.StatePanelNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

	/** 面板主体默认宽度。 */
	private static final int PANEL_WIDTH = 500;
	private static final int VISIBLE_ROWS = 8;
	private static final int ROW_HEIGHT = 20;
	private static final int INPUT_HEIGHT = 20;
	private static final int PADDING = 6;
	private static final int ACTION_BUTTON_COUNT = 3;
	private static final int BUTTON_HEIGHT = 20;
	private static final int REMOVE_BUTTON_WIDTH = 20;
	private static final int CLEAN_ALL_BUTTON_WIDTH = 72;
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_CONTENT_HEIGHT = 304;
	private static final int LIST_ROW_TEXT_OFFSET_Y = 6;
	private static final int TYPE_LABEL_TOP_OFFSET = 10;
	private static final int INPUT_TOP_OFFSET = 24;
	private static final int HEADER_TOP_EXTRA_OFFSET = 2;
	private static final int REMOVE_BUTTON_GAP = 6;
	private static final int COLUMN_GAP = 6;

	private static final int LIST_LEFT_PADDING = 4;
	private static final int COL_TYPE_W = 116;
	private static final int COL_SERIAL_W = 96;
	private static final int MIN_COL_TYPE_W = 72;
	private static final int MIN_COL_SERIAL_W = 64;
	private static final int MIN_COL_STATUS_W = 64;

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

		inputBox = new EditBox(font, layout.panelLeft(), layout.inputY(), layout.panelWidth(), INPUT_HEIGHT, Component.empty());
		inputBox.setHint(Component.translatable("screen.redstonelink.state_panel.input_hint"));
		inputBox.setValue(preservedInput);
		addRenderableWidget(inputBox);
		setInitialFocus(inputBox);

		typeToggleButton = addRenderableWidget(
			Button
				.builder(typeToggleLabel(), button -> {
					currentType = currentType == LinkNodeType.CORE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
					button.setMessage(typeToggleLabel());
				})
				.bounds(layout.panelLeft(), layout.typeToggleY(), layout.panelWidth(), BUTTON_HEIGHT)
				.build()
		);

		subscribeButton = addRenderableWidget(
			Button
				.builder(SUBSCRIBE, button -> subscribe())
				.bounds(layout.actionButtonX(0), layout.actionY(), layout.actionButtonWidth(), BUTTON_HEIGHT)
				.build()
		);
		refreshButton = addRenderableWidget(
			Button
				.builder(REFRESH, button -> requestRefresh())
				.bounds(layout.actionButtonX(1), layout.actionY(), layout.actionButtonWidth(), BUTTON_HEIGHT)
				.build()
		);
		recordButton = addRenderableWidget(
			Button
				.builder(RECORD, button -> requestRecord())
				.bounds(layout.actionButtonX(2), layout.actionY(), layout.actionButtonWidth(), BUTTON_HEIGHT)
				.build()
		);

		cleanAllButton = addRenderableWidget(
			Button
				.builder(CLEAN_ALL, button -> requestCleanAll())
				.bounds(layout.cleanAllButtonX(), layout.headerY(), CLEAN_ALL_BUTTON_WIDTH, BUTTON_HEIGHT)
				.build()
		);

		for (int row = 0; row < VISIBLE_ROWS; row++) {
			final int visibleRow = row;
			Button removeButton = addRenderableWidget(
				Button
					.builder(Component.literal("×"), button -> removeAtVisibleRow(visibleRow))
					.bounds(layout.removeButtonX(), layout.listRowY(row), REMOVE_BUTTON_WIDTH, BUTTON_HEIGHT)
					.build()
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
		renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		int centerX = width / 2;
		guiGraphics.drawCenteredString(font, TITLE, centerX, layout.panelTop(), 0xFFFFFF);
		guiGraphics.drawString(
			font,
			Component.translatable("screen.redstonelink.state_panel.input"),
			layout.panelLeft(),
			layout.panelTop() + TYPE_LABEL_TOP_OFFSET,
			0xC8C8C8,
			false
		);

		renderHeader(guiGraphics, layout);
		renderList(guiGraphics, layout);

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawString(font, statusMessage, layout.panelLeft(), layout.statusMessageY(), 0xFF7777, false);
		}
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
		statusMessage = Component.translatable(messageKey, args).withColor(success ? 0x66CC66 : 0xFF7777);
	}

	private void subscribe() {
		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(inputBox.getValue());
		if (validation.empty()) {
			statusMessage = Component.translatable("screen.redstonelink.state_panel.input_empty");
			return;
		}
		if (!validation.valid()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", validation.invalidEntries())
			);
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

	private void requestRecord() {
		ClientPlayNetworking.send(new StatePanelNetwork.RecordStatePanelPayload());
	}

	private void requestCleanAll() {
		ClientPlayNetworking.send(new StatePanelNetwork.CleanAllStatePanelPayload());
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
	 * 渲染表头行，含列标签与右侧 clean all 按钮。
	 */
	private void renderHeader(GuiGraphics guiGraphics, StatePanelLayout layout) {
		guiGraphics.drawString(font, clipTextToWidth(HEADER_TYPE.getString(), layout.typeWidth()), layout.typeX(), layout.headerY() + LIST_ROW_TEXT_OFFSET_Y, 0xAAAAAA, false);
		guiGraphics.drawString(
			font,
			clipTextToWidth(HEADER_SERIAL.getString(), layout.serialWidth()),
			layout.serialX(),
			layout.headerY() + LIST_ROW_TEXT_OFFSET_Y,
			0xAAAAAA,
			false
		);
		guiGraphics.drawString(
			font,
			clipTextToWidth(HEADER_STATUS.getString(), layout.statusWidth()),
			layout.statusX(),
			layout.headerY() + LIST_ROW_TEXT_OFFSET_Y,
			0xAAAAAA,
			false
		);
	}

	/**
	 * 渲染列表数据行，每列独立绘制，保证列对齐。
	 */
	private void renderList(GuiGraphics guiGraphics, StatePanelLayout layout) {
		for (int row = 0; row < VISIBLE_ROWS; row++) {
			int index = scrollOffset + row;
			int rowY = layout.listRowY(row);
			if (index >= entries.size()) {
				guiGraphics.drawString(font, "-", layout.typeX(), rowY + LIST_ROW_TEXT_OFFSET_Y, 0x666666, false);
				continue;
			}
			StatePanelNetwork.StatePanelSnapshotEntry entry = entries.get(index);
			String typeLabel = LinkNodeSemantics.toSemanticName(entry.nodeType());
			String serialLabel = "#" + entry.serial();
			String status = buildStatusText(entry, hasAppliedServerSnapshot);

			guiGraphics.drawString(font, clipTextToWidth(typeLabel, layout.typeWidth()), layout.typeX(), rowY + LIST_ROW_TEXT_OFFSET_Y, 0xE6E6E6, false);
			guiGraphics.drawString(
				font,
				clipTextToWidth(serialLabel, layout.serialWidth()),
				layout.serialX(),
				rowY + LIST_ROW_TEXT_OFFSET_Y,
				0xDDDDDD,
				false
			);
			guiGraphics.drawString(
				font,
				clipTextToWidth(status, layout.statusWidth()),
				layout.statusX(),
				rowY + LIST_ROW_TEXT_OFFSET_Y,
				0xCCCCCC,
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
		String online = entry.online() ? "online" : "offline";
		String active = entry.active() ? "active" : "idle";
		String retired = entry.retired() ? "retired" : "alive";
		StringBuilder sb = new StringBuilder(64);
		sb.append(online);
		sb.append(" / ");
		sb.append(active);
		sb.append(" / ");
		sb.append(retired);
		sb.append(" / in=");
		sb.append(entry.inputPower());
		sb.append(" out=");
		sb.append(entry.outputPower());
		return sb.toString();
	}

	private Component typeToggleLabel() {
		return Component.translatable("screen.redstonelink.state_panel.type", LinkNodeSemantics.toSemanticName(currentType));
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
			typeToggleButton.setWidth(layout.panelWidth());
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
		int actionButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(panelWidth, PADDING, ACTION_BUTTON_COUNT);
		int inputY = panelTop + INPUT_TOP_OFFSET;
		int typeToggleY = inputY + INPUT_HEIGHT + PADDING;
		int actionY = typeToggleY + BUTTON_HEIGHT + PADDING;
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
			actionY,
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
	 * 状态面板统一布局结果。
	 */
	static record StatePanelLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int inputY,
		int typeToggleY,
		int actionY,
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
			return panelLeft + (actionButtonWidth + PADDING) * index;
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
