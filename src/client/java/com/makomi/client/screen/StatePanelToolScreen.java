package com.makomi.client.screen;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.network.StatePanelNetwork;
import com.makomi.util.SerialParseUtil;
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
	private static final int VISIBLE_ROWS = 8;
	private static final int ROW_HEIGHT = 20;
	private static final int INPUT_HEIGHT = 20;
	private static final int INPUT_WIDTH = 240;
	private static final int BUTTON_WIDTH = 76;
	private static final int BUTTON_HEIGHT = 20;
	private static final int REMOVE_BUTTON_WIDTH = 20;
	private static final int PADDING = 6;

	private final List<StatePanelNetwork.SubscriptionEntryPayload> initialSubscriptions;
	private final List<StatePanelNetwork.StatePanelSnapshotEntry> entries = new ArrayList<>();
	private final List<Button> removeButtons = new ArrayList<>();
	private EditBox inputBox;
	private Button typeToggleButton;
	private LinkNodeType currentType = LinkNodeType.CORE;
	private Component statusMessage = Component.empty();
	private int scrollOffset;

	public StatePanelToolScreen(List<StatePanelNetwork.SubscriptionEntryPayload> subscriptions) {
		super(TITLE);
		this.initialSubscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
	}

	@Override
	protected void init() {
		super.init();
		int left = panelLeft();
		int inputY = panelTop() + 24;
		inputBox = new EditBox(font, left, inputY, INPUT_WIDTH, INPUT_HEIGHT, Component.empty());
		inputBox.setHint(Component.translatable("screen.redstonelink.state_panel.input_hint"));
		addRenderableWidget(inputBox);
		setInitialFocus(inputBox);

		typeToggleButton = addRenderableWidget(
			Button
				.builder(typeToggleLabel(), button -> {
					currentType = currentType == LinkNodeType.CORE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
					button.setMessage(typeToggleLabel());
				})
				.bounds(left, inputY + INPUT_HEIGHT + PADDING, INPUT_WIDTH, BUTTON_HEIGHT)
				.build()
		);

		int actionY = typeToggleButton.getY() + BUTTON_HEIGHT + PADDING;
		addRenderableWidget(Button.builder(SUBSCRIBE, button -> subscribe()).bounds(left, actionY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		addRenderableWidget(
			Button
				.builder(REFRESH, button -> requestRefresh())
				.bounds(left + BUTTON_WIDTH + PADDING, actionY, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build()
		);
		addRenderableWidget(
			Button
				.builder(RECORD, button -> requestRecord())
				.bounds(left + (BUTTON_WIDTH + PADDING) * 2, actionY, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build()
		);

		int listTop = actionY + BUTTON_HEIGHT + PADDING + 2;
		for (int row = 0; row < VISIBLE_ROWS; row++) {
			final int visibleRow = row;
			Button removeButton = addRenderableWidget(
				Button
					.builder(Component.literal("×"), button -> removeAtVisibleRow(visibleRow))
					.bounds(left + INPUT_WIDTH - REMOVE_BUTTON_WIDTH, listTop + row * ROW_HEIGHT, REMOVE_BUTTON_WIDTH, BUTTON_HEIGHT)
					.build()
			);
			removeButtons.add(removeButton);
		}

		initializeEntriesFromOpenPayload();
		requestRefresh();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		int centerX = width / 2;
		int left = panelLeft();
		int top = panelTop();
		guiGraphics.drawCenteredString(font, TITLE, centerX, top, 0xFFFFFF);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.state_panel.input"), left, top + 10, 0xC8C8C8, false);

		renderList(guiGraphics, left, listTopY());
		updateRemoveButtons();

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawString(font, statusMessage, left, listTopY() + VISIBLE_ROWS * ROW_HEIGHT + PADDING, 0xFF7777, false);
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
		String input = inputBox.getValue() == null ? "" : inputBox.getValue().trim();
		if (input.isEmpty()) {
			statusMessage = Component.translatable("screen.redstonelink.state_panel.input_empty");
			return;
		}
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(input, 0);
		if (!parseResult.invalidEntries().isEmpty()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", parseResult.invalidEntries())
			);
			return;
		}
		ClientPlayNetworking.send(new StatePanelNetwork.SubscribeStatePanelPayload(LinkNodeSemantics.toSemanticName(currentType), input));
		statusMessage = Component.empty();
	}

	private void requestRefresh() {
		ClientPlayNetworking.send(new StatePanelNetwork.RefreshStatePanelPayload());
	}

	private void requestRecord() {
		ClientPlayNetworking.send(new StatePanelNetwork.RecordStatePanelPayload());
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
					0
				)
			);
		}
		entries.sort(entryComparator());
		normalizeScrollOffset();
	}

	private void renderList(GuiGraphics guiGraphics, int left, int listTop) {
		for (int row = 0; row < VISIBLE_ROWS; row++) {
			int index = scrollOffset + row;
			int rowY = listTop + row * ROW_HEIGHT;
			if (index >= entries.size()) {
				guiGraphics.drawString(font, "-", left + 4, rowY + 6, 0x666666, false);
				continue;
			}
			StatePanelNetwork.StatePanelSnapshotEntry entry = entries.get(index);
			String typeLabel = LinkNodeSemantics.toSemanticName(entry.nodeType());
			String status = buildStatusText(entry);
			String line = typeLabel + " #" + entry.serial() + "  " + status;
			guiGraphics.drawString(font, line, left + 4, rowY + 6, 0xE6E6E6, false);
		}
	}

	private void updateRemoveButtons() {
		int listTop = listTopY();
		for (int row = 0; row < removeButtons.size(); row++) {
			Button removeButton = removeButtons.get(row);
			int index = scrollOffset + row;
			removeButton.visible = index < entries.size();
			removeButton.active = index < entries.size();
			removeButton.setY(listTop + row * ROW_HEIGHT);
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

	private static String buildStatusText(StatePanelNetwork.StatePanelSnapshotEntry entry) {
		String online = entry.online() ? "online" : "offline";
		String active = entry.active() ? "active" : "idle";
		String retired = entry.retired() ? "retired" : "alive";
		return online + " / " + active + " / " + retired + " / in=" + entry.inputPower() + " out=" + entry.outputPower();
	}

	private Component typeToggleLabel() {
		return Component.translatable("screen.redstonelink.state_panel.type", LinkNodeSemantics.toSemanticName(currentType));
	}

	private int panelLeft() {
		return width / 2 - INPUT_WIDTH / 2;
	}

	private int panelTop() {
		return height / 2 - 120;
	}

	private int listTopY() {
		return panelTop() + 24 + INPUT_HEIGHT + PADDING + BUTTON_HEIGHT + PADDING + BUTTON_HEIGHT + PADDING + 2;
	}
}