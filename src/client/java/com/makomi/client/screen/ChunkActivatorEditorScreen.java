package com.makomi.client.screen;

import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorMode;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.PlacedChunkActivatorSavedData;
import com.makomi.network.ChunkActivatorNetwork;
import com.makomi.network.LinkFilterEditorTargetKind;
import com.makomi.util.SerialParseUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 区块激活器编辑界面。
 */
public class ChunkActivatorEditorScreen extends Screen {
	private static final Component SAVE = Component.translatable("screen.redstonelink.chunk_activator.save");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.chunk_activator.clear");
	private static final StyledMultiLineEditBox.Style SERIAL_INPUT_BOX_STYLE = new StyledMultiLineEditBox.Style(
		0xFF9A4D00,
		0xFF6D3300,
		0xFFFFC791,
		0xFFFFE8CF
	);
	private static final StyledEditBox.Style EDIT_BOX_STYLE = new StyledEditBox.Style(
		0xFF9A4D00,
		0xFF6D3300,
		0xFFFFC791,
		0x99643A17,
		0xFF8B5A33,
		0xFFFFF7F0,
		0xFFE0C2A8
	);
	private static final StyledButton.Style BUTTON_STYLE = new StyledButton.Style(
		0xE09A4D00,
		0xF0C96C14,
		0x99643A17,
		0xFF6D3300,
		0xFFFFC791,
		0xFF8B5A33,
		0xFFFFF7F0,
		0xFFE0C2A8
	);
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_CONTENT_HEIGHT = 202;
	private static final int PANEL_PREFERRED_WIDTH = 320;
	private static final int TITLE_TOP_MARGIN = 22;
	private static final int GROUP_LABEL_MARGIN = 8;
	private static final int SERIAL_INPUT_HEIGHT = 76;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final int STATUS_MESSAGE_MARGIN = 10;
	private static final int BACKGROUND_HORIZONTAL_PADDING = 12;
	private static final int BACKGROUND_TOP_PADDING = 16;
	private static final int BACKGROUND_BOTTOM_PADDING = 22;

	private final LinkFilterEditorTargetKind targetKind;
	private final String dimensionKey;
	private final long blockPosLong;
	private final int selectedSlot;
	private final String initialDisplayAlias;
	private final ChunkActivatorConfigSnapshot initialSnapshot;

	private StyledEditBox aliasInput;
	private MultiLineEditBox serialInputBox;
	private Button[] modeButtons = new Button[0];
	private ChunkActivatorMode currentMode;
	private Component statusMessage = Component.empty();

	public ChunkActivatorEditorScreen(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String initialDisplayAlias,
		ChunkActivatorConfigSnapshot initialSnapshot
	) {
		super(Component.translatable("screen.redstonelink.chunk_activator.title"));
		this.targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
		this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
		this.blockPosLong = blockPosLong;
		this.selectedSlot = this.targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
		this.initialDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(initialDisplayAlias);
		this.initialSnapshot = initialSnapshot == null ? new ChunkActivatorConfigSnapshot("", null) : initialSnapshot;
		currentMode = this.initialSnapshot.mode();
	}

	@Override
	protected void init() {
		super.init();
		ChunkActivatorLayout layout = resolveLayout(width, height, font.lineHeight);
		String preservedDisplayAlias = aliasInput == null ? initialDisplayAlias : aliasInput.getValue();
		String preservedSerialExpression = serialInputBox == null ? initialSnapshot.serialExpression() : serialInputBox.getValue();

		aliasInput = createAliasInputBox(layout);
		aliasInput.setMaxLength(NodeAliasSavedData.maxAliasLength());
		aliasInput.setHint(Component.translatable("screen.redstonelink.pairing.alias_hint"));
		aliasInput.setValue(preservedDisplayAlias);
		addRenderableWidget(aliasInput);

		serialInputBox = createSerialInputBox(layout);
		serialInputBox.setCharacterLimit(com.makomi.config.RedstoneLinkConfig.command().linkSetMaxInputLength());
		serialInputBox.setValue(preservedSerialExpression);
		addRenderableWidget(serialInputBox);

		int doubleButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		modeButtons = new Button[] {
			createOptionButton(
				layout.panelLeft(),
				layout.modeRowY(),
				doubleButtonWidth,
				() -> currentMode = ChunkActivatorMode.FORCE_LOAD
			),
			createOptionButton(
				layout.panelLeft() + doubleButtonWidth + BUTTON_GAP,
				layout.modeRowY(),
				doubleButtonWidth,
				() -> currentMode = ChunkActivatorMode.RESIDENT
			)
		};

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

		refreshModeButtonMessages();
		setInitialFocus(serialInputBox);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		ChunkActivatorLayout layout = resolveLayout(width, height, font.lineHeight);
		int centerX = width / 2;
		GuiBackgroundRenderSupport.RegionBounds baseContentBounds = resolveBaseContentBounds(layout);
		GuiHeaderRenderSupport.drawCenteredHeader(guiGraphics, font, headerSpec(), centerX, layout.titleY(), baseContentBounds);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.chunk_activator.alias"), layout.panelLeft(), layout.aliasLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.chunk_activator.mode"), layout.panelLeft(), layout.modeLabelY(), 0xFFFFFF, false);
		guiGraphics.drawString(font, Component.translatable("screen.redstonelink.chunk_activator.serial_input"), layout.panelLeft(), layout.serialLabelY(), 0xFFFFFF, false);
		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawCenteredString(font, statusMessage, centerX, layout.statusMessageY(), 0xFF6666);
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		ChunkActivatorLayout layout = resolveLayout(width, height, font.lineHeight);
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

	private void saveAndClose() {
		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(serialInputBox.getValue());
		if (!validation.valid()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", validation.invalidEntries())
			);
			return;
		}
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			validation.normalizedExpression(),
			PlacedChunkActivatorSavedData.MAX_TRIGGER_SOURCE_COUNT
		);
		if (parseResult.exceedLimit()) {
			statusMessage = Component.translatable(
				"message.redstonelink.chunk_activator.too_many_serials",
				Integer.toString(PlacedChunkActivatorSavedData.MAX_TRIGGER_SOURCE_COUNT)
			);
			return;
		}

		String normalizedDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(aliasInput == null ? "" : aliasInput.getValue());
		statusMessage = Component.empty();
		ClientPlayNetworking.send(
			new ChunkActivatorNetwork.SaveChunkActivatorPayload(
				targetKind,
				dimensionKey,
				blockPosLong,
				selectedSlot,
				normalizedDisplayAlias,
				new ChunkActivatorConfigSnapshot(validation.normalizedExpression(), currentMode)
			)
		);
		onClose();
	}

	private void resetForm() {
		if (aliasInput != null) {
			aliasInput.setValue("");
		}
		if (serialInputBox != null) {
			serialInputBox.setValue("");
		}
		currentMode = ChunkActivatorMode.FORCE_LOAD;
		statusMessage = Component.empty();
		refreshModeButtonMessages();
	}

	private void refreshModeButtonMessages() {
		setOptionButtonMessage(
			modeButtons[0],
			currentMode == ChunkActivatorMode.FORCE_LOAD,
			Component.translatable("screen.redstonelink.chunk_activator.mode.force_load")
		);
		setOptionButtonMessage(
			modeButtons[1],
			currentMode == ChunkActivatorMode.RESIDENT,
			Component.translatable("screen.redstonelink.chunk_activator.mode.resident")
		);
	}

	private Button createOptionButton(int x, int y, int width, Runnable onPress) {
		Button button = new StyledButton(x, y, width, BUTTON_HEIGHT, Component.empty(), value -> {
			onPress.run();
			refreshModeButtonMessages();
		}, BUTTON_STYLE);
		addRenderableWidget(button);
		return button;
	}

	private MultiLineEditBox createSerialInputBox(ChunkActivatorLayout layout) {
		return new StyledMultiLineEditBox(
			font,
			layout.panelLeft(),
			layout.serialInputY(),
			layout.panelWidth(),
			SERIAL_INPUT_HEIGHT,
			Component.translatable("screen.redstonelink.chunk_activator.serial_input"),
			Component.empty(),
			SERIAL_INPUT_BOX_STYLE
		);
	}

	private StyledEditBox createAliasInputBox(ChunkActivatorLayout layout) {
		return new StyledEditBox(
			font,
			layout.panelLeft(),
			layout.aliasInputY(),
			layout.panelWidth(),
			BUTTON_HEIGHT,
			Component.translatable("screen.redstonelink.chunk_activator.alias"),
			EDIT_BOX_STYLE
		);
	}

	private Button createActionButton(Component message, int x, int y, int width, Button.OnPress onPress) {
		return new StyledButton(x, y, width, BUTTON_HEIGHT, message, onPress, BUTTON_STYLE);
	}

	private static void setOptionButtonMessage(Button button, boolean selected, Component label) {
		if (button != null) {
			button.setMessage(Component.literal(selected ? "\u25CF " : "\u25CB ").append(label));
		}
	}

	static ChunkActivatorLayout resolveLayout(int screenWidth, int screenHeight, int fontLineHeight) {
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
		int modeLabelY = aliasInputY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int modeRowY = modeLabelY + GROUP_LABEL_MARGIN;
		int serialLabelY = modeRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int serialInputY = serialLabelY + GROUP_LABEL_MARGIN;
		int actionButtonY = serialInputY + SERIAL_INPUT_HEIGHT + GROUP_LABEL_MARGIN;
		int statusMessageY = actionButtonY + BUTTON_HEIGHT + STATUS_MESSAGE_MARGIN;
		return new ChunkActivatorLayout(
			panelBox.left(),
			panelBox.top(),
			panelBox.width(),
			titleY,
			aliasLabelY,
			aliasInputY,
			modeLabelY,
			modeRowY,
			serialLabelY,
			serialInputY,
			actionButtonY,
			statusMessageY
		);
	}

	private GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.TRIGGER_SOURCE_PAIRING;
	}

	private GuiBackgroundRenderSupport.RegionBounds resolveContentBounds(ChunkActivatorLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds baseBounds = resolveBaseContentBounds(layout);
		return baseBounds.include(
			GuiHeaderRenderSupport.resolveCenteredHeaderBounds(font, headerSpec(), width / 2, layout.titleY(), baseBounds)
		);
	}

	private GuiBackgroundRenderSupport.RegionBounds resolveBaseContentBounds(ChunkActivatorLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds bounds = GuiHeaderRenderSupport.resolveCenteredHeaderTextBounds(
			font,
			headerSpec(),
			width / 2,
			layout.titleY()
		);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.chunk_activator.alias"), layout.panelLeft(), layout.aliasLabelY())
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.aliasInputY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.chunk_activator.mode"), layout.panelLeft(), layout.modeLabelY())
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.modeRowY(), layout.panelWidth(), BUTTON_HEIGHT)
			);
		bounds =
			bounds.include(
				leftAlignedTextBounds(Component.translatable("screen.redstonelink.chunk_activator.serial_input"), layout.panelLeft(), layout.serialLabelY())
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.serialInputY(), layout.panelWidth(), SERIAL_INPUT_HEIGHT)
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

	private GuiBackgroundRenderSupport.RegionBounds leftAlignedTextBounds(Component text, int left, int top) {
		return new GuiBackgroundRenderSupport.RegionBounds(left, top, Math.max(1, font.width(text)), font.lineHeight);
	}

	private GuiBackgroundRenderSupport.RegionBounds centeredTextBounds(Component text, int centerX, int top) {
		int textWidth = Math.max(1, font.width(text));
		return new GuiBackgroundRenderSupport.RegionBounds(centerX - (textWidth / 2), top, textWidth, font.lineHeight);
	}

	private GuiHeaderRenderSupport.HeaderSpec headerSpec() {
		return new GuiHeaderRenderSupport.HeaderSpec(
			Component.translatable("screen.redstonelink.chunk_activator.title"),
			Component.translatable(
				"screen.redstonelink.chunk_activator.service_line",
				LinkNodeSemantics.toSemanticName(LinkNodeType.TRIGGER_SOURCE)
			),
			backgroundPreset().borderColor(),
			new GuiHeaderRenderSupport.HeaderIcon(GuiHeaderRenderSupport.IconKind.TRIGGER_SOURCE, -10)
		);
	}

	static record ChunkActivatorLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int titleY,
		int aliasLabelY,
		int aliasInputY,
		int modeLabelY,
		int modeRowY,
		int serialLabelY,
		int serialInputY,
		int actionButtonY,
		int statusMessageY
	) {
	}
}
