package com.makomi.client.screen;

import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorConfigStateSnapshot;
import com.makomi.data.ChunkActivatorMode;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.PlacedChunkActivatorSavedData;
import com.makomi.network.ChunkActivatorNetwork;
import com.makomi.network.LinkFilterEditorTargetKind;
import com.makomi.util.SerialParseUtil;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 区块激活器编辑界面。
 */
public class ChunkActivatorEditorScreen extends Screen {
	private static final Component SAVE = Component.translatable("screen.redstonelink.chunk_activator.save");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.chunk_activator.clear");
	private static final int THEME_BORDER_COLOR = GuiBackgroundRenderSupport.BackgroundPreset.CHUNK_ACTIVATOR.borderColor();
	private static final int THEME_FILL_COLOR = 0xCC8641C0;
	private static final int THEME_HOVER_FILL_COLOR = 0xF09B4BDD;
	private static final int THEME_DISABLED_FILL_COLOR = 0x9957257B;
	private static final int THEME_FOCUSED_BORDER_COLOR = 0xFFB36DE9;
	private static final int THEME_DISABLED_BORDER_COLOR = 0xFF6C3A99;
	private static final int THEME_TEXT_COLOR = 0xFFFFF5FF;
	private static final int THEME_DISABLED_TEXT_COLOR = 0xFFDCC7EE;
	private static final int THEME_COUNTER_TEXT_COLOR = 0xFFF2DFFF;
	private static final StyledMultiLineEditBox.Style SERIAL_INPUT_BOX_STYLE = new StyledMultiLineEditBox.Style(
		THEME_FILL_COLOR,
		THEME_BORDER_COLOR,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_COUNTER_TEXT_COLOR
	);
	private static final StyledEditBox.Style EDIT_BOX_STYLE = new StyledEditBox.Style(
		THEME_FILL_COLOR,
		THEME_BORDER_COLOR,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_DISABLED_FILL_COLOR,
		THEME_DISABLED_BORDER_COLOR,
		THEME_TEXT_COLOR,
		THEME_DISABLED_TEXT_COLOR
	);
	private static final StyledButton.Style BUTTON_STYLE = new StyledButton.Style(
		0xE08641C0,
		THEME_HOVER_FILL_COLOR,
		THEME_DISABLED_FILL_COLOR,
		THEME_BORDER_COLOR,
		THEME_FOCUSED_BORDER_COLOR,
		THEME_DISABLED_BORDER_COLOR,
		THEME_TEXT_COLOR,
		THEME_DISABLED_TEXT_COLOR
	);
	private static final int SCREEN_EDGE_MARGIN = 16;
	private static final int PANEL_CONTENT_HEIGHT = 228;
	private static final int PANEL_PREFERRED_WIDTH = 320;
	private static final int TITLE_TOP_MARGIN = 22;
	private static final int GROUP_LABEL_MARGIN = 8;
	private static final int SERIAL_INPUT_HEIGHT = 64;
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

	private StyledEditBox aliasInput;
	private MultiLineEditBox serialInputBox;
	private Button[] typeButtons = new Button[0];
	private Button[] modeButtons = new Button[0];
	private String draftDisplayAlias;
	private ChunkActivatorConfigStateSnapshot configStateSnapshot;
	private LinkNodeType currentType;
	private Component statusMessage = Component.empty();

	public ChunkActivatorEditorScreen(
		LinkFilterEditorTargetKind targetKind,
		String dimensionKey,
		long blockPosLong,
		int selectedSlot,
		String initialDisplayAlias,
		ChunkActivatorConfigStateSnapshot initialConfigStateSnapshot
	) {
		super(Component.translatable("screen.redstonelink.chunk_activator.title"));
		this.targetKind = targetKind == null ? LinkFilterEditorTargetKind.BLOCK_ENTITY : targetKind;
		this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
		this.blockPosLong = blockPosLong;
		this.selectedSlot = this.targetKind.usesHeldMainHandTarget() ? Math.max(0, selectedSlot) : -1;
		this.draftDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(initialDisplayAlias);
		this.configStateSnapshot = initialConfigStateSnapshot == null
			? new ChunkActivatorConfigStateSnapshot(null, null, null)
			: initialConfigStateSnapshot;
		this.currentType = this.configStateSnapshot.activeType();
	}

	@Override
	protected void init() {
		super.init();
		ChunkActivatorLayout layout = resolveLayout(width, height, font.lineHeight);
		ChunkActivatorConfigSnapshot currentConfig = currentConfig();

		aliasInput = createAliasInputBox(layout);
		aliasInput.setMaxLength(NodeAliasSavedData.maxAliasLength());
		aliasInput.setHint(Component.translatable("screen.redstonelink.pairing.alias_hint"));
		aliasInput.setValue(draftDisplayAlias);
		addRenderableWidget(aliasInput);

		serialInputBox = createSerialInputBox(layout);
		serialInputBox.setCharacterLimit(com.makomi.config.RedstoneLinkConfig.command().linkSetMaxInputLength());
		serialInputBox.setValue(currentConfig.serialExpression());
		addRenderableWidget(serialInputBox);

		int splitButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), BUTTON_GAP, 2);
		typeButtons = new Button[] {
			createOptionButton(
				layout.panelLeft(),
				layout.typeRowY(),
				splitButtonWidth,
				() -> requestSwitchType(LinkNodeType.TRIGGER_SOURCE)
			),
			createOptionButton(
				layout.panelLeft() + splitButtonWidth + BUTTON_GAP,
				layout.typeRowY(),
				splitButtonWidth,
				() -> requestSwitchType(LinkNodeType.CORE)
			)
		};
		modeButtons = new Button[] {
			createOptionButton(
				layout.panelLeft(),
				layout.modeRowY(),
				splitButtonWidth,
				() -> updateCurrentMode(ChunkActivatorMode.FORCE_LOAD)
			),
			createOptionButton(
				layout.panelLeft() + splitButtonWidth + BUTTON_GAP,
				layout.modeRowY(),
				splitButtonWidth,
				() -> updateCurrentMode(ChunkActivatorMode.RESIDENT)
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

		refreshTypeButtonMessages();
		refreshModeButtonMessages();
		setInitialFocus(serialInputBox);
	}

	@Override
	public void resize(int width, int height) {
		captureDraftStateFromWidgets();
		super.resize(width, height);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

		ChunkActivatorLayout layout = resolveLayout(width, height, font.lineHeight);
		int centerX = width / 2;
		GuiBackgroundRenderSupport.RegionBounds baseContentBounds = resolveBaseContentBounds(layout);
		GuiHeaderRenderSupport.drawCenteredHeader(guiGraphics, font, headerSpec(), centerX, layout.titleY(), baseContentBounds);
		guiGraphics.text(font, Component.translatable("screen.redstonelink.chunk_activator.alias"), layout.panelLeft(), layout.aliasLabelY(), 0xFFFFFF, false);
		guiGraphics.text(
			font,
			Component.translatable("screen.redstonelink.chunk_activator.active_type"),
			layout.panelLeft(),
			layout.typeLabelY(),
			0xFFFFFF,
			false
		);
		guiGraphics.text(font, Component.translatable("screen.redstonelink.chunk_activator.mode"), layout.panelLeft(), layout.modeLabelY(), 0xFFFFFF, false);
		guiGraphics.text(font, Component.translatable("screen.redstonelink.chunk_activator.serial_input"), layout.panelLeft(), layout.serialLabelY(), 0xFFFFFF, false);
		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.centeredText(font, statusMessage, centerX, layout.statusMessageY(), 0xFF6666);
		}
		renderSerialInputDecorations(guiGraphics);
		renderModeButtonTooltip(guiGraphics, mouseX, mouseY);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
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
		renderSerialInputBackground(guiGraphics);
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
	public boolean isPauseScreen() {
		return false;
	}

	private void saveAndClose() {
		captureDraftStateFromWidgets();
		Component validationMessage = validateDraftState();
		if (validationMessage != null) {
			statusMessage = validationMessage;
			return;
		}

		String normalizedDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(draftDisplayAlias);
		statusMessage = Component.empty();
		ClientPlayNetworking.send(
			new ChunkActivatorNetwork.SaveChunkActivatorPayload(
				targetKind,
				dimensionKey,
				blockPosLong,
				selectedSlot,
				normalizedDisplayAlias,
				configStateSnapshot.withActiveType(currentType)
			)
		);
		onClose();
	}

	private void resetForm() {
		draftDisplayAlias = "";
		configStateSnapshot = configStateSnapshot.withConfig(currentType, new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD));
		statusMessage = Component.empty();
		if (aliasInput != null) {
			aliasInput.setValue("");
		}
		if (serialInputBox != null) {
			serialInputBox.setValue("");
		}
		refreshModeButtonMessages();
	}

	private void requestSwitchType(LinkNodeType requestedType) {
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(requestedType);
		if (normalizedType == currentType) {
			return;
		}
		captureDraftStateFromWidgets();
		Minecraft minecraft = this.minecraft;
		if (minecraft == null) {
			currentType = normalizedType;
			return;
		}
		minecraft.setScreen(
			new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						currentType = normalizedType;
						statusMessage = Component.empty();
					}
					minecraft.setScreen(this);
				},
				Component.translatable("screen.redstonelink.chunk_activator.switch_type.confirm.title"),
				Component.translatable(
					"screen.redstonelink.chunk_activator.switch_type.confirm.message",
					LinkNodeSemantics.toSemanticName(normalizedType)
				)
			)
		);
	}

	private void updateCurrentMode(ChunkActivatorMode nextMode) {
		configStateSnapshot = configStateSnapshot.withConfig(
			currentType,
			new ChunkActivatorConfigSnapshot(currentSerialExpression(), nextMode)
		);
		refreshModeButtonMessages();
	}

	private void captureDraftStateFromWidgets() {
		if (aliasInput != null) {
			draftDisplayAlias = aliasInput.getValue();
		}
		if (serialInputBox != null) {
			configStateSnapshot = configStateSnapshot.withConfig(
				currentType,
				new ChunkActivatorConfigSnapshot(serialInputBox.getValue(), currentMode())
			);
		}
	}

	private Component validateDraftState() {
		for (LinkNodeType type : new LinkNodeType[] { LinkNodeType.TRIGGER_SOURCE, LinkNodeType.CORE }) {
			ChunkActivatorConfigSnapshot configSnapshot = configStateSnapshot.configFor(type);
			SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(configSnapshot.serialExpression());
			if (!validation.valid()) {
				return Component.translatable(
					"screen.redstonelink.chunk_activator.invalid_tokens_for_type",
					LinkNodeSemantics.toSemanticName(type),
					String.join(", ", validation.invalidEntries())
				);
			}
			SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
				validation.normalizedExpression(),
				PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE
			);
			if (parseResult.exceedLimit()) {
				return Component.translatable(
					"screen.redstonelink.chunk_activator.too_many_serials_for_type",
					LinkNodeSemantics.toSemanticName(type),
					Integer.toString(PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE)
				);
			}
			configStateSnapshot = configStateSnapshot.withConfig(
				type,
				new ChunkActivatorConfigSnapshot(validation.normalizedExpression(), configSnapshot.mode())
			);
		}
		return null;
	}

	private void refreshTypeButtonMessages() {
		setOptionButtonMessage(typeButtons[0], currentType == LinkNodeType.TRIGGER_SOURCE, Component.literal("triggerSource"));
		setOptionButtonMessage(typeButtons[1], currentType == LinkNodeType.CORE, Component.literal("core"));
	}

	private void refreshModeButtonMessages() {
		ChunkActivatorMode currentMode = currentMode();
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

	/**
	 * 为强加载/常驻模式按钮渲染悬停提示，直接解释当前模式的跨区块语义。
	 */
	private void renderModeButtonTooltip(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
		if (modeButtons.length < 2) {
			return;
		}
		if (modeButtons[0] != null && modeButtons[0].isMouseOver(mouseX, mouseY)) {
			GuiTooltipRenderSupport.renderComponentTooltip(guiGraphics, font, buildModeTooltipLines(ChunkActivatorMode.FORCE_LOAD), mouseX, mouseY);
			return;
		}
		if (modeButtons[1] != null && modeButtons[1].isMouseOver(mouseX, mouseY)) {
			GuiTooltipRenderSupport.renderComponentTooltip(guiGraphics, font, buildModeTooltipLines(ChunkActivatorMode.RESIDENT), mouseX, mouseY);
		}
	}

	/**
	 * 构建模式按钮 tooltip 文案。
	 */
	static List<Component> buildModeTooltipLines(ChunkActivatorMode mode) {
		ChunkActivatorMode normalizedMode = mode == null ? ChunkActivatorMode.FORCE_LOAD : mode;
		String translationKey = switch (normalizedMode) {
			case FORCE_LOAD -> "screen.redstonelink.chunk_activator.mode.tooltip.force_load";
			case RESIDENT -> "screen.redstonelink.chunk_activator.mode.tooltip.resident";
		};
		return List.of(Component.translatable(translationKey));
	}

	private Button createOptionButton(int x, int y, int width, Runnable onPress) {
		Button button = new StyledButton(x, y, width, BUTTON_HEIGHT, Component.empty(), value -> onPress.run(), BUTTON_STYLE);
		addRenderableWidget(button);
		return button;
	}

	private MultiLineEditBox createSerialInputBox(ChunkActivatorLayout layout) {
		return StyledMultiLineEditBox.create(
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

	/**
	 * 26.1 多行输入框不再开放主题背景覆写，这里在背景层补画区块激活器配色外壳。
	 */
	private void renderSerialInputBackground(GuiGraphicsExtractor guiGraphics) {
		if (serialInputBox != null) {
			StyledMultiLineEditBox.renderBackground(guiGraphics, serialInputBox, SERIAL_INPUT_BOX_STYLE);
		}
	}

	/**
	 * 统一补画无阴影容量计数，避免当前版本原版 decorations 恢复阴影计数。
	 */
	private void renderSerialInputDecorations(GuiGraphicsExtractor guiGraphics) {
		if (serialInputBox != null) {
			ShadowlessCounterMultiLineEditBox.renderCharacterLimitCounter(
				guiGraphics,
				serialInputBox,
				font,
				com.makomi.config.RedstoneLinkConfig.command().linkSetMaxInputLength(),
				SERIAL_INPUT_BOX_STYLE.counterTextColor()
			);
		}
	}

	private Button createActionButton(Component message, int x, int y, int width, Button.OnPress onPress) {
		return new StyledButton(x, y, width, BUTTON_HEIGHT, message, onPress, BUTTON_STYLE);
	}

	private ChunkActivatorConfigSnapshot currentConfig() {
		return configStateSnapshot.configFor(currentType);
	}

	private ChunkActivatorMode currentMode() {
		return currentConfig().mode();
	}

	private String currentSerialExpression() {
		return serialInputBox == null ? currentConfig().serialExpression() : serialInputBox.getValue();
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
		int typeLabelY = aliasInputY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
		int typeRowY = typeLabelY + GROUP_LABEL_MARGIN;
		int modeLabelY = typeRowY + BUTTON_HEIGHT + GROUP_LABEL_MARGIN;
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
			typeLabelY,
			typeRowY,
			modeLabelY,
			modeRowY,
			serialLabelY,
			serialInputY,
			actionButtonY,
			statusMessageY
		);
	}

	private GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.CHUNK_ACTIVATOR;
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
				leftAlignedTextBounds(
					Component.translatable("screen.redstonelink.chunk_activator.active_type"),
					layout.panelLeft(),
					layout.typeLabelY()
				)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.typeRowY(), layout.panelWidth(), BUTTON_HEIGHT)
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
				LinkNodeSemantics.toSemanticName(currentType)
			),
			resolveHeaderSubtitleColor(),
			null
		);
	}

	/**
	 * 区块激活器界面固定使用专属主题色；服务对象语义仅保留在副标题文案里。
	 */
	private int resolveHeaderSubtitleColor() {
		return GuiBackgroundRenderSupport.BackgroundPreset.CHUNK_ACTIVATOR.borderColor();
	}

	static record ChunkActivatorLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int titleY,
		int aliasLabelY,
		int aliasInputY,
		int typeLabelY,
		int typeRowY,
		int modeLabelY,
		int modeRowY,
		int serialLabelY,
		int serialInputY,
		int actionButtonY,
		int statusMessageY
	) {
	}
}
