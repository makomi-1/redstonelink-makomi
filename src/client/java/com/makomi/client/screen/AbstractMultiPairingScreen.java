package com.makomi.client.screen;

import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasSavedData;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.network.PairingNetwork;
import com.makomi.util.CurrentLinksDisplayFormatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 多目标配对界面抽象基类。
 * <p>
 * 统一处理输入解析、数量校验、确认/清空动作和当前连接展示。
 * </p>
 */
public abstract class AbstractMultiPairingScreen extends Screen {
	private static final Component CONFIRM = Component.translatable("screen.redstonelink.pairing.confirm");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.pairing.clear");
	private static final int DEFAULT_CURRENT_LINKS_TEXT_COLOR = 0xC8C8C8;
	private static final int STATUS_MESSAGE_ERROR_COLOR = 0xFF6666;
	private static final int STATUS_MESSAGE_SUCCESS_COLOR = 0xFF9AE39A;
	/**
	 * 输入框悬停提示：规则行。
	 */
	private static final Component INPUT_TOOLTIP_RULE = Component.translatable("screen.redstonelink.pairing.input_tooltip_rule");
	/**
	 * 输入框悬停提示：示例行。
	 */
	private static final Component INPUT_TOOLTIP_EXAMPLE = Component.translatable(
		"screen.redstonelink.pairing.input_tooltip_example"
	);
	/**
	 * “当前连接”在主界面的最大展示宽度（像素）。
	 */
	private static final int CURRENT_LINKS_LIST_MAX_WIDTH = 220;
	/**
	 * 悬停 tooltip 最大宽度（像素）。
	 */
	private static final int TOOLTIP_MAX_WIDTH = 320;
	/**
	 * 悬停 tooltip 最多展示的结构化分段数。
	 */
	private static final int TOOLTIP_MAX_ITEMS = 100;
	/**
	 * 按钮行在 render 基准下的 Y 偏移。
	 */
	private static final int BUTTON_ROW_MARGIN = 8;
	/**
	 * 按钮高度（像素）。
	 */
	private static final int ACTION_BUTTON_HEIGHT = 20;
	/**
	 * 按钮宽度（像素）。
	 */
	private static final int ACTION_BUTTON_WIDTH = 108;
	/**
	 * 按钮之间的水平间距（像素）。
	 */
	private static final int ACTION_BUTTON_GAP = 4;
	/**
	 * pairing 面板的首选宽度（像素）。
	 */
	private static final int PANEL_PREFERRED_WIDTH = ACTION_BUTTON_WIDTH * 2 + ACTION_BUTTON_GAP;
	/**
	 * 窗口边缘安全留白（像素）。
	 */
	private static final int SCREEN_EDGE_MARGIN = 16;
	/**
	 * 别名输入框高度（像素）。
	 */
	private static final int ALIAS_INPUT_HEIGHT = 20;
	/**
	 * 别名输入框固定宽度（像素）。
	 */
	private static final int ALIAS_INPUT_WIDTH = 60;
	/**
	 * 别名输入框与固定序号后缀的间距（像素）。
	 */
	private static final int ALIAS_SUFFIX_GAP = 4;
	/**
	 * 输入框高度（像素）。
	 */
	private static final int INPUT_BOX_HEIGHT = 56;
	/**
	 * 面板内容默认高度（像素）。
	 */
	private static final int PANEL_CONTENT_HEIGHT = 212;
	/**
	 * 转发器入口下的紧凑布局高度（像素）。
	 */
	private static final int COMPACT_PANEL_CONTENT_HEIGHT = 188;
	/**
	 * 主操作按钮数量。
	 */
	private static final int ACTION_BUTTON_COUNT = 2;
	/**
	 * 非法提示与按钮底部的间距（像素）。
	 */
	private static final int STATUS_MESSAGE_MARGIN = 4;
	/**
	 * 输入标签与输入框顶部的间距（像素）。
	 */
	private static final int INPUT_LABEL_MARGIN = 2;
	/**
	 * 内容背景左右留白（像素）。
	 */
	private static final int BACKGROUND_HORIZONTAL_PADDING = 12;
	/**
	 * 内容背景顶部留白（像素）。
	 */
	private static final int BACKGROUND_TOP_PADDING = 18;
	/**
	 * 内容背景底部留白（像素）。
	 */
	private static final int BACKGROUND_BOTTOM_PADDING = 26;

	protected final long sourceSerial;
	protected String sourceAlias;
	protected String sourceDisplayText;
	protected final List<Long> currentTargets;
	protected final List<String> currentTargetDisplayTexts;
	protected final long graphRevision;
	protected final long sourceRevision;
	protected final long coreRevision;
	protected LinkConnectionMode currentConnectionMode;
	protected long currentChannel;

	private StyledEditBox aliasInput;
	private MultiLineEditBox serialInput;
	private Button modeButton;
	private Component statusMessage = Component.empty();
	private int statusMessageColor = STATUS_MESSAGE_ERROR_COLOR;

	protected AbstractMultiPairingScreen(
		Component title,
		long sourceSerial,
		String sourceAlias,
		String sourceDisplayText,
		List<Long> currentTargets,
		List<String> currentTargetDisplayTexts,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		LinkConnectionMode connectionMode,
		long channel
	) {
		super(title);
		this.sourceSerial = sourceSerial;
		this.sourceAlias = normalizeSourceAlias(sourceAlias);
		this.sourceDisplayText = normalizeSourceDisplayText(sourceSerial, this.sourceAlias, sourceDisplayText);
		this.currentTargets = new ArrayList<>(currentTargets);
		this.currentTargetDisplayTexts = new ArrayList<>(
			NodeAliasDisplayUtil.normalizeDisplayTexts(this.currentTargets, currentTargetDisplayTexts)
		);
		this.graphRevision = Math.max(0L, graphRevision);
		this.sourceRevision = Math.max(0L, sourceRevision);
		this.coreRevision = Math.max(0L, coreRevision);
		this.currentConnectionMode = connectionMode == null ? LinkConnectionMode.SERIAL : connectionMode;
		this.currentChannel = Math.max(0L, channel);
	}

	protected AbstractMultiPairingScreen(
		Component title,
		long sourceSerial,
		String sourceAlias,
		String sourceDisplayText,
		List<Long> currentTargets,
		List<String> currentTargetDisplayTexts,
		long graphRevision,
		long sourceRevision
	) {
		this(
			title,
			sourceSerial,
			sourceAlias,
			sourceDisplayText,
			currentTargets,
			currentTargetDisplayTexts,
			graphRevision,
			sourceRevision,
			0L,
			LinkConnectionMode.SERIAL,
			0L
		);
	}

	protected AbstractMultiPairingScreen(
		Component title,
		long sourceSerial,
		String sourceAlias,
		String sourceDisplayText,
		List<Long> currentTargets,
		long graphRevision,
		long sourceRevision
	) {
		this(title, sourceSerial, sourceAlias, sourceDisplayText, currentTargets, List.of(), graphRevision, sourceRevision);
	}

	protected AbstractMultiPairingScreen(Component title, long sourceSerial, String sourceAlias, String sourceDisplayText, List<Long> currentTargets) {
		this(title, sourceSerial, sourceAlias, sourceDisplayText, currentTargets, List.of(), 0L, 0L);
	}

	protected AbstractMultiPairingScreen(
		Component title,
		long sourceSerial,
		String sourceDisplayText,
		List<Long> currentTargets,
		List<String> currentTargetDisplayTexts,
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) {
		this(
			title,
			sourceSerial,
			"",
			sourceDisplayText,
			currentTargets,
			currentTargetDisplayTexts,
			graphRevision,
			sourceRevision,
			coreRevision,
			LinkConnectionMode.SERIAL,
			0L
		);
	}

	protected AbstractMultiPairingScreen(
		Component title,
		long sourceSerial,
		String sourceDisplayText,
		List<Long> currentTargets,
		long graphRevision,
		long sourceRevision,
		long coreRevision
	) {
		this(title, sourceSerial, sourceDisplayText, currentTargets, List.of(), graphRevision, sourceRevision, coreRevision);
	}

	protected AbstractMultiPairingScreen(
		Component title,
		long sourceSerial,
		String sourceDisplayText,
		List<Long> currentTargets,
		List<String> currentTargetDisplayTexts,
		long graphRevision,
		long sourceRevision
	) {
		this(
			title,
			sourceSerial,
			"",
			sourceDisplayText,
			currentTargets,
			currentTargetDisplayTexts,
			graphRevision,
			sourceRevision,
			0L,
			LinkConnectionMode.SERIAL,
			0L
		);
	}

	protected AbstractMultiPairingScreen(
		Component title,
		long sourceSerial,
		String sourceDisplayText,
		List<Long> currentTargets,
		long graphRevision,
		long sourceRevision
	) {
		this(title, sourceSerial, sourceDisplayText, currentTargets, List.of(), graphRevision, sourceRevision);
	}

	protected AbstractMultiPairingScreen(Component title, long sourceSerial, String sourceDisplayText, List<Long> currentTargets) {
		this(title, sourceSerial, "", sourceDisplayText, currentTargets, List.of(), 0L, 0L);
	}

	@Override
	protected void init() {
		super.init();
		if (!allowChannelMode()) {
			currentConnectionMode = LinkConnectionMode.SERIAL;
			currentChannel = 0L;
		}
		String preservedAlias = aliasInput == null ? sourceAlias : aliasInput.getValue();
		String preservedInput = serialInput == null ? initialInputValue() : serialInput.getValue();
		MultiPairingLayout layout = resolveLayout(width, height, font.lineHeight, layoutDensity());
		aliasInput = createAliasInputBox(layout);
		aliasInput.setMaxLength(NodeAliasSavedData.maxAliasLength());
		aliasInput.setHint(Component.translatable("screen.redstonelink.pairing.alias_hint"));
		aliasInput.setValue(preservedAlias);
		addRenderableWidget(aliasInput);
		int inputX = layout.panelLeft();
		int inputY = layout.inputY();
		serialInput = createSerialInputBox(layout, inputX, inputY);
		serialInput.setCharacterLimit(RedstoneLinkClientDisplayConfig.pairing().inputMaxLength());

		// 仅在非空场景回填并自动聚焦，空场景不抢焦点，避免光标跳动影响示例阅读。
		if (!preservedInput.isEmpty()) {
			serialInput.setValue(preservedInput);
		}
		setInitialFocus(serialInput);
		addRenderableWidget(serialInput);
		if (allowChannelMode()) {
			modeButton =
				addRenderableWidget(
					createActionButton(
						ActionButtonKind.MODE,
						modeButtonLabel(),
						layout.panelLeft(),
						layout.modeButtonY(),
						layout.panelWidth(),
						button -> toggleConnectionMode()
					)
				);
		} else {
			modeButton = null;
		}

		int buttonRowY = layout.actionButtonY();
		int actionButtonWidth = layout.actionButtonWidth();
		addRenderableWidget(
			createActionButton(ActionButtonKind.CONFIRM, CONFIRM, layout.actionButtonX(0), buttonRowY, actionButtonWidth, button -> submit())
		);
		addRenderableWidget(
			createActionButton(ActionButtonKind.CLEAR, CLEAR, layout.actionButtonX(1), buttonRowY, actionButtonWidth, button -> clearPair())
		);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		MultiPairingLayout layout = resolveLayout(width, height, font.lineHeight, layoutDensity());
		int centerX = width / 2;
		int baseY = layout.titleY();
		int currentLinksX = layout.panelLeft();
		int currentLinksY = layout.currentLinksY();
		int currentLinksValueY = layout.currentLinksValueY();
		GuiBackgroundRenderSupport.RegionBounds baseContentBounds = resolveBaseContentBounds(layout);

		GuiHeaderRenderSupport.drawCenteredHeader(guiGraphics, font, headerSpec(), centerX, baseY, baseContentBounds);
		guiGraphics.drawString(font, aliasSerialSuffix(), aliasSuffixX(layout), layout.aliasSuffixY(), currentLinksTextColor(), false);
		String currentLinksText = buildCurrentLinksText(currentTargets, currentTargetDisplayTexts);
		Component currentLinksLabel = currentLinksLine("");
		Component currentLinksValue = Component.literal(currentLinksText);
		guiGraphics.drawString(font, currentLinksLabel, currentLinksX, currentLinksY, currentLinksTextColor(), false);
		guiGraphics.drawString(font, currentLinksValue, currentLinksX, currentLinksValueY, currentLinksTextColor(), false);
		guiGraphics.drawString(font, inputLabel(), currentLinksX, layout.inputLabelY(), 0xFFFFFF, false);

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawCenteredString(font, statusMessage, centerX, layout.statusMessageY(), statusMessageColor);
		}

		if (isMouseOverInput(mouseX, mouseY)) {
			List<Component> tooltipLines = buildInputTooltipLines();
			if (!tooltipLines.isEmpty()) {
				guiGraphics.renderTooltip(font, tooltipLines, Optional.empty(), mouseX, mouseY);
			}
		} else if (
			!currentTargetDisplayTexts.isEmpty()
				&& isMouseOver(
					currentLinksX,
					currentLinksY,
					layout.panelWidth(),
					currentLinksValueY - currentLinksY + font.lineHeight,
					mouseX,
					mouseY
				)
		) {
			List<Component> tooltipLines = buildTooltipLines(currentTargets, currentTargetDisplayTexts);
			if (!tooltipLines.isEmpty()) {
				guiGraphics.renderTooltip(font, tooltipLines, Optional.empty(), mouseX, mouseY);
			}
		}
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		// 配对界面背景只包裹实际内容区域，并通过更大的垂直留白形成稳定的表单容器感。
		MultiPairingLayout layout = resolveLayout(width, height, font.lineHeight, layoutDensity());
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
			if (aliasInput != null && aliasInput.isFocused()) {
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
			if (serialInput != null && serialInput.isFocused()) {
				if (hasControlDown()) {
					submit();
					return true;
				}
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
			submit();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	protected abstract Component inputLabel();

	protected abstract Component invalidInput();

	protected abstract Component currentLinksLine(String currentLinksText);

	/**
	 * @return 当前界面头部标题；子类可覆写为更具体的双语标题
	 */
	protected Component headerTitle() {
		return title;
	}

	/**
	 * @return 当前界面头部副标题；别名编辑模式下改为空，由下方独立 alias row 承接
	 */
	protected Component headerSubtitle() {
		return Component.empty();
	}

	/**
	 * @return 当前界面头部副标题颜色；默认使用浅灰色
	 */
	protected int headerSubtitleColor() {
		return DEFAULT_CURRENT_LINKS_TEXT_COLOR;
	}

	/**
	 * @return 当前界面头部图标；默认不显示
	 */
	protected GuiHeaderRenderSupport.HeaderIcon headerIcon() {
		return null;
	}

	/**
	 * @return 当前界面完整头部渲染规格
	 */
	protected GuiHeaderRenderSupport.HeaderSpec headerSpec() {
		return new GuiHeaderRenderSupport.HeaderSpec(headerTitle(), headerSubtitle(), headerSubtitleColor(), headerIcon());
	}

	/**
	 * @return 当前配对界面的背景预设
	 */
	protected abstract GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset();

	/**
	 * @return “当前连接”文本颜色；子类可覆写为主题色或背景边框色
	 */
	protected int currentLinksTextColor() {
		return DEFAULT_CURRENT_LINKS_TEXT_COLOR;
	}

	/**
	 * @return 当前界面的输入框皮肤；返回 `null` 表示沿用原版默认输入框背景
	 */
	protected StyledMultiLineEditBox.Style inputBoxStyle() {
		return null;
	}

	/**
	 * @return 当前界面的别名单行输入框皮肤；返回 `null` 表示沿用默认主题输入框
	 */
	protected StyledEditBox.Style aliasInputStyle() {
		return null;
	}

	/**
	 * @param kind 按钮语义类型
	 * @return 当前界面的按钮皮肤；返回 `null` 表示沿用原版默认按钮
	 */
	protected StyledButton.Style actionButtonStyle(ActionButtonKind kind) {
		return null;
	}

	/**
	 * @return 当前上下文是否允许切换到频道模式
	 */
	protected boolean allowChannelMode() {
		return true;
	}

	/**
	 * @return 当前界面的布局密度；默认保留完整模式切换行
	 */
	protected LayoutDensity layoutDensity() {
		return LayoutDensity.DEFAULT;
	}

	/**
	 * @return `link set` 命令的来源类型（triggerSource/core 语义入口）
	 */
	protected abstract LinkNodeType sourceType();

	/**
	 * 提交配对写入请求。
	 * <p>
	 * 默认仍走命令兼容链路，子类可覆写为结构化 payload。
	 * </p>
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param rawTargetsInput 输入框中的原始目标文本
	 */
	protected void submitPairingRequest(
		long sourceSerial,
		LinkConnectionMode connectionMode,
		String rawTargetsInput,
		long channel
	) {
		if (connectionMode == LinkConnectionMode.CHANNEL) {
			return;
		}
		sendSetLinksCommand(sourceSerial, rawTargetsInput);
	}

	/**
	 * 发送覆盖式 `link set` 命令。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param rawTargetsInput 输入框中的原始目标文本
	 */
	protected final void sendSetLinksCommand(long sourceSerial, String rawTargetsInput) {
		if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
			return;
		}
		String base = setLinksBaseCommand(sourceSerial);
		String normalizedTargets = rawTargetsInput == null ? "" : rawTargetsInput.trim();
		if (normalizedTargets.isEmpty()) {
			minecraft.player.connection.sendCommand(base);
			return;
		}

		// 客户端不再按数量做业务裁决；非空输入统一追加 confirm，最终由服务端判定是否执行。
		String command = base + " " + normalizedTargets + " confirm";
		minecraft.player.connection.sendCommand(command);
	}

	/**
	 * 构建 `link set` 命令前缀。
	 */
	private String setLinksBaseCommand(long sourceSerial) {
		return "redstonelink link set " + LinkNodeSemantics.toCommandToken(sourceType()) + " " + sourceSerial;
	}

	/**
	 * 构建主界面“当前连接”结构化文本（`#N`/`#A:#B` + `/`）。
	 *
	 * @param currentTargets 当前目标序号列表
	 * @return 主界面显示文本
	 */
	protected final String buildCurrentLinksText(List<Long> currentTargets, List<String> currentTargetDisplayTexts) {
		return CurrentLinksDisplayFormatUtil.buildText(
			currentTargets,
			currentTargetDisplayTexts,
			CURRENT_LINKS_LIST_MAX_WIDTH,
			font::width
		);
	}

	/**
	 * 构建“当前连接”悬停 tooltip，按分段换行并保持结构化表达式。
	 *
	 * @param currentTargets 当前目标序号列表
	 * @return tooltip 文本行
	 */
	protected final List<Component> buildTooltipLines(List<Long> currentTargets, List<String> currentTargetDisplayTexts) {
		if (
			currentTargets == null
				|| currentTargets.isEmpty()
				|| currentTargetDisplayTexts == null
				|| currentTargetDisplayTexts.isEmpty()
				|| TOOLTIP_MAX_ITEMS <= 0
				|| TOOLTIP_MAX_WIDTH <= 0
		) {
			return List.of();
		}
		return CurrentLinksDisplayFormatUtil
			.buildWrappedLines(currentTargets, currentTargetDisplayTexts, TOOLTIP_MAX_WIDTH, TOOLTIP_MAX_ITEMS, font::width)
			.stream()
			.map(text -> (Component) Component.literal(text))
			.toList();
	}

	/**
	 * 判断鼠标是否悬停在指定矩形区域内。
	 */
	private boolean isMouseOver(int x, int y, int width, int height, int mouseX, int mouseY) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	/**
	 * 判断鼠标是否悬停在输入框区域内。
	 */
	private boolean isMouseOverInput(int mouseX, int mouseY) {
		if (serialInput == null) {
			return false;
		}
		return isMouseOver(
			serialInput.getX(),
			serialInput.getY(),
			serialInput.getWidth(),
			serialInput.getHeight(),
			mouseX,
			mouseY
		);
	}

	/**
	 * 构建输入框悬停提示（规则 + 示例），并按宽度自动换行。
	 */
	private List<Component> buildInputTooltipLines() {
		List<Component> lines = new ArrayList<>(2);
		if (allowChannelMode() && isChannelMode()) {
			lines.add(Component.translatable("screen.redstonelink.pairing.channel_input_tooltip_rule"));
			return lines;
		}
		lines.add(INPUT_TOOLTIP_RULE);
		lines.add(INPUT_TOOLTIP_EXAMPLE);
		return lines;
	}

	/**
	 * 提交覆盖式 `link set`。
	 */
	private void submit() {
		if (sourceSerial <= 0L) {
			statusMessage = invalidInput();
			statusMessageColor = STATUS_MESSAGE_ERROR_COLOR;
			return;
		}
		if (isChannelMode()) {
			Long parsedChannel = parseChannelInput(serialInput.getValue());
			if (parsedChannel == null) {
				statusMessage = invalidInput();
				statusMessageColor = STATUS_MESSAGE_ERROR_COLOR;
				return;
			}
			currentChannel = parsedChannel;
			submitAliasIfChanged();
			if (parsedChannel > 0L) {
				submitPairingRequest(sourceSerial, LinkConnectionMode.CHANNEL, "", parsedChannel);
			}
			onClose();
			return;
		}

		SerialInputSyntaxSupport.ValidationResult validation = SerialInputSyntaxSupport.validate(serialInput.getValue());
		if (!validation.valid()) {
			statusMessage = Component.translatable(
				"screen.redstonelink.pairing.invalid_tokens",
				String.join(", ", validation.invalidEntries())
			);
			statusMessageColor = STATUS_MESSAGE_ERROR_COLOR;
			return;
		}

		submitAliasIfChanged();
		submitPairingRequest(sourceSerial, LinkConnectionMode.SERIAL, validation.normalizedExpression(), 0L);
		onClose();
	}

	/**
	 * 清空当前源节点的连接。
	 */
	private void clearPair() {
		if (sourceSerial <= 0L) {
			statusMessage = invalidInput();
			statusMessageColor = STATUS_MESSAGE_ERROR_COLOR;
			return;
		}

		currentConnectionMode = LinkConnectionMode.SERIAL;
		currentChannel = 0L;
		submitPairingRequest(sourceSerial, LinkConnectionMode.SERIAL, "", 0L);
		onClose();
	}

	/**
	 * 提交当前节点的别名保存请求。
	 */
	private void submitAliasIfChanged() {
		if (sourceSerial <= 0L || aliasInput == null || net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			return;
		}
		String normalizedAlias = normalizeSourceAlias(aliasInput.getValue());
		if (normalizedAlias.equals(sourceAlias)) {
			return;
		}
		ClientPlayNetworking.send(
			new PairingNetwork.SubmitPairingAliasPayload(
				LinkNodeSemantics.toSemanticName(sourceType()),
				sourceSerial,
				normalizedAlias
			)
		);
		sourceAlias = normalizedAlias;
	}

	/**
	 * 根据当前界面样式创建序号输入框。
	 */
	private MultiLineEditBox createSerialInputBox(MultiPairingLayout layout, int inputX, int inputY) {
		StyledMultiLineEditBox.Style style = inputBoxStyle();
		if (style == null) {
			return new ShadowlessCounterMultiLineEditBox(
				font,
				inputX,
				inputY,
				layout.panelWidth(),
				INPUT_BOX_HEIGHT,
				inputLabel(),
				Component.empty()
			);
		}
		return new StyledMultiLineEditBox(font, inputX, inputY, layout.panelWidth(), INPUT_BOX_HEIGHT, inputLabel(), Component.empty(), style);
	}

	/**
	 * 根据当前界面样式创建别名单行输入框。
	 */
	private StyledEditBox createAliasInputBox(MultiPairingLayout layout) {
		return new StyledEditBox(
			font,
			layout.panelLeft(),
			layout.aliasInputY(),
			aliasInputWidth(layout),
			ALIAS_INPUT_HEIGHT,
			Component.empty(),
			aliasInputStyle()
		);
	}

	/**
	 * 根据当前界面样式创建操作按钮。
	 */
	private Button createActionButton(
		ActionButtonKind kind,
		Component message,
		int x,
		int y,
		int width,
		Button.OnPress onPress
	) {
		StyledButton.Style style = actionButtonStyle(kind);
		if (style == null) {
			return Button.builder(message, onPress).bounds(x, y, width, ACTION_BUTTON_HEIGHT).build();
		}
		return new StyledButton(x, y, width, ACTION_BUTTON_HEIGHT, message, onPress, style);
	}

	/**
	 * 解析当前界面内容组件的最小包围框。
	 * <p>
	 * 这里只纳入固定布局中的标题、序号、当前连接、输入标签、输入框和按钮，
	 * 不包含 tooltip。
	 * </p>
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveContentBounds(MultiPairingLayout layout) {
		GuiBackgroundRenderSupport.RegionBounds baseBounds = resolveBaseContentBounds(layout);
		return baseBounds.include(GuiHeaderRenderSupport.resolveCenteredHeaderBounds(font, headerSpec(), width / 2, layout.titleY(), baseBounds));
	}

	/**
	 * 解析不含头部图标的基础内容包围盒，用于给图标提供整体组件锚点。
	 */
	private GuiBackgroundRenderSupport.RegionBounds resolveBaseContentBounds(MultiPairingLayout layout) {
		Component currentLinksLabel = currentLinksLine("");
		Component currentLinksValue = Component.literal(buildCurrentLinksText(currentTargets, currentTargetDisplayTexts));
		GuiBackgroundRenderSupport.RegionBounds bounds = GuiHeaderRenderSupport.resolveCenteredHeaderTextBounds(
			font,
			headerSpec(),
			width / 2,
			layout.titleY()
		);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.aliasInputY(), aliasInputWidth(layout), ALIAS_INPUT_HEIGHT)
			);
		bounds = bounds.include(leftAlignedTextBounds(aliasSerialSuffix(), aliasSuffixX(layout), layout.aliasSuffixY()));
		bounds = bounds.include(leftAlignedTextBounds(currentLinksLabel, layout.panelLeft(), layout.currentLinksY()));
		bounds = bounds.include(leftAlignedTextBounds(currentLinksValue, layout.panelLeft(), layout.currentLinksValueY()));
		if (modeButton != null) {
			bounds =
				bounds.include(
					new GuiBackgroundRenderSupport.RegionBounds(
						layout.panelLeft(),
						layout.modeButtonY(),
						layout.panelWidth(),
						ACTION_BUTTON_HEIGHT
					)
				);
		}
		bounds = bounds.include(leftAlignedTextBounds(inputLabel(), layout.panelLeft(), layout.inputLabelY()));
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(layout.panelLeft(), layout.inputY(), layout.panelWidth(), INPUT_BOX_HEIGHT)
			);
		bounds =
			bounds.include(
				new GuiBackgroundRenderSupport.RegionBounds(
					layout.panelLeft(),
					layout.actionButtonY(),
					layout.panelWidth(),
					ACTION_BUTTON_HEIGHT
				)
			);
		return bounds;
	}

	/**
	 * 生成左对齐文本包围盒。
	 */
	private GuiBackgroundRenderSupport.RegionBounds leftAlignedTextBounds(Component text, int left, int top) {
		return new GuiBackgroundRenderSupport.RegionBounds(left, top, Math.max(1, font.width(text)), font.lineHeight);
	}

	/**
	 * 将目标序号列表转换为结构化输入文本。
	 */
	/**
	 * 按当前屏幕尺寸解析 pairing 界面布局。
	 */
	static MultiPairingLayout resolveLayout(int screenWidth, int screenHeight, int fontLineHeight) {
		return resolveLayout(screenWidth, screenHeight, fontLineHeight, LayoutDensity.DEFAULT);
	}

	/**
	 * 按当前屏幕尺寸与布局密度解析 pairing 界面布局。
	 */
	static MultiPairingLayout resolveLayout(int screenWidth, int screenHeight, int fontLineHeight, LayoutDensity layoutDensity) {
		LayoutDensity resolvedDensity = layoutDensity == null ? LayoutDensity.DEFAULT : layoutDensity;
		CenteredFormLayoutSupport.CenteredPanelBox panelBox = CenteredFormLayoutSupport.resolvePanelBox(
			screenWidth,
			screenHeight,
			PANEL_PREFERRED_WIDTH,
			resolvedDensity == LayoutDensity.COMPACT ? COMPACT_PANEL_CONTENT_HEIGHT : PANEL_CONTENT_HEIGHT,
			SCREEN_EDGE_MARGIN
		);
		int titleY = panelBox.top();
		int aliasInputY = titleY + 30;
		int aliasSuffixY = aliasInputY + Math.max(0, (ALIAS_INPUT_HEIGHT - fontLineHeight) / 2) + 1;
		int currentLinksY = aliasInputY + ALIAS_INPUT_HEIGHT + 4;
		int currentLinksValueY = currentLinksY + fontLineHeight + 1;
		int modeButtonY = currentLinksValueY + fontLineHeight + 2;
		int inputLabelY = modeButtonY + (resolvedDensity == LayoutDensity.COMPACT ? 0 : ACTION_BUTTON_HEIGHT + 5);
		int inputY = inputLabelY + fontLineHeight + INPUT_LABEL_MARGIN;
		int actionButtonY = inputY + INPUT_BOX_HEIGHT + BUTTON_ROW_MARGIN - 2;
		int actionButtonWidth = CenteredFormLayoutSupport.resolveSplitWidth(panelBox.width(), ACTION_BUTTON_GAP, ACTION_BUTTON_COUNT);
		int statusMessageY = actionButtonY + ACTION_BUTTON_HEIGHT + STATUS_MESSAGE_MARGIN;
		return new MultiPairingLayout(
			panelBox.left(),
			panelBox.top(),
			panelBox.width(),
			titleY,
			aliasInputY,
			aliasSuffixY,
			currentLinksY,
			currentLinksValueY,
			modeButtonY,
			inputLabelY,
			inputY,
			actionButtonY,
			actionButtonWidth,
			statusMessageY
		);
	}

	/**
	 * pairing 界面布局结果。
	 */
	static record MultiPairingLayout(
		int panelLeft,
		int panelTop,
		int panelWidth,
		int titleY,
		int aliasInputY,
		int aliasSuffixY,
		int currentLinksY,
		int currentLinksValueY,
		int modeButtonY,
		int inputLabelY,
		int inputY,
		int actionButtonY,
		int actionButtonWidth,
		int statusMessageY
	) {
		int actionButtonX(int index) {
			return panelLeft + (actionButtonWidth + ACTION_BUTTON_GAP) * Math.max(0, index);
		}
	}

	/**
	 * pairing 布局密度枚举。
	 */
	protected enum LayoutDensity {
		DEFAULT,
		COMPACT,
	}

	/**
	 * pairing 主操作按钮语义。
	 */
	protected enum ActionButtonKind {
		MODE,
		CONFIRM,
		CLEAR,
	}

	/**
	 * 接收配对界面服务端反馈；界面开启时优先落到状态行，否则由网络层回退到聊天栏。
	 */
	public void applyPairingFeedback(boolean success, String messageKey, List<String> messageArgs) {
		if (messageKey == null || messageKey.isBlank()) {
			statusMessage = Component.empty();
			return;
		}
		Object[] args = messageArgs == null ? new Object[0] : messageArgs.toArray();
		statusMessage = Component.translatable(messageKey, args);
		statusMessageColor = success ? STATUS_MESSAGE_SUCCESS_COLOR : STATUS_MESSAGE_ERROR_COLOR;
	}

	/**
	 * 判断当前配对界面是否命中指定来源节点。
	 */
	public boolean matchesSourceNode(LinkNodeType type, long serial) {
		return type == sourceType() && sourceSerial == serial;
	}

	/**
	 * 接收服务端回写的最新别名状态，并同步到 GUI 输入框。
	 */
	public void applySourceAliasState(LinkNodeType type, long serial, String alias, String displayText) {
		if (!matchesSourceNode(type, serial)) {
			return;
		}
		sourceAlias = normalizeSourceAlias(alias);
		sourceDisplayText = normalizeSourceDisplayText(sourceSerial, sourceAlias, displayText);
		if (aliasInput != null && !sourceAlias.equals(aliasInput.getValue())) {
			aliasInput.setValue(sourceAlias);
		}
	}

	private int aliasInputWidth(MultiPairingLayout layout) {
		return ALIAS_INPUT_WIDTH;
	}

	private int aliasSuffixX(MultiPairingLayout layout) {
		return layout.panelLeft() + aliasInputWidth(layout) + ALIAS_SUFFIX_GAP;
	}

	private Component aliasSerialSuffix() {
		return Component.literal("(" + NodeAliasDisplayUtil.formatSerialToken(sourceSerial) + ")");
	}

	/**
	 * @return 当前界面是否处于频道模式
	 */
	protected final boolean isChannelMode() {
		return currentConnectionMode == LinkConnectionMode.CHANNEL;
	}

	private String initialInputValue() {
		if (isChannelMode()) {
			return Long.toString(Math.max(0L, currentChannel));
		}
		return SerialInputSyntaxSupport.joinTargets(currentTargets);
	}

	private Component modeButtonLabel() {
		return Component.translatable(
			"screen.redstonelink.pairing.mode_button",
			Component.translatable(currentConnectionMode.translationKey())
		);
	}

	private void toggleConnectionMode() {
		if (!allowChannelMode()) {
			currentConnectionMode = LinkConnectionMode.SERIAL;
			currentChannel = 0L;
			return;
		}
		currentConnectionMode = currentConnectionMode.next();
		currentChannel = 0L;
		statusMessage = Component.empty();
		statusMessageColor = STATUS_MESSAGE_ERROR_COLOR;
		if (serialInput != null) {
			serialInput.setValue(isChannelMode() ? "0" : "");
			setInitialFocus(serialInput);
		}
		if (modeButton != null) {
			modeButton.setMessage(modeButtonLabel());
		}
	}

	private Long parseChannelInput(String rawInput) {
		String normalized = rawInput == null ? "" : rawInput.trim();
		if (normalized.isEmpty()) {
			return 0L;
		}
		try {
			long parsed = Long.parseLong(normalized);
			return parsed >= 0L ? parsed : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static String normalizeSourceAlias(String sourceAlias) {
		return NodeAliasDisplayUtil.normalizeAlias(sourceAlias);
	}

	private static String normalizeSourceDisplayText(long sourceSerial, String sourceAlias, String sourceDisplayText) {
		String normalizedDisplayText = NodeAliasDisplayUtil.normalizeAlias(sourceDisplayText);
		return normalizedDisplayText.isEmpty()
			? NodeAliasDisplayUtil.formatDisplayText(sourceAlias, sourceSerial)
			: normalizedDisplayText;
	}
}
