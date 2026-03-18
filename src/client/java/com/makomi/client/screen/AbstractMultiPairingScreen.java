package com.makomi.client.screen;

import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.util.SerialDisplayFormatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 多目标配对界面抽象基类。
 * <p>
 * 统一处理输入解析、数量校验、确认/清空动作和当前连接展示。
 * </p>
 */
public abstract class AbstractMultiPairingScreen extends Screen {
	private static final Component CONFIRM = Component.translatable("screen.redstonelink.pairing.confirm");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.pairing.clear");
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
	private static final int BUTTON_ROW_Y_OFFSET_RENDER = 102;
	/**
	 * 按钮高度（像素）。
	 */
	private static final int ACTION_BUTTON_HEIGHT = 20;
	/**
	 * 非法提示与按钮底部的间距（像素）。
	 */
	private static final int STATUS_MESSAGE_MARGIN = 4;

	protected final long sourceSerial;
	protected final List<Long> currentTargets;

	private EditBox serialInput;
	private Component statusMessage = Component.empty();

	protected AbstractMultiPairingScreen(Component title, long sourceSerial, List<Long> currentTargets) {
		super(title);
		this.sourceSerial = sourceSerial;
		this.currentTargets = new ArrayList<>(currentTargets);
	}

	@Override
	protected void init() {
		super.init();
		int centerX = width / 2;
		int baseY = height / 2 - 42;

		serialInput = new EditBox(font, centerX - 110, baseY + 52, 220, 20, inputLabel());
		serialInput.setMaxLength(RedstoneLinkClientDisplayConfig.pairingInputMaxLength());

		String initialInputText = joinTargets(currentTargets);
		// 仅在非空场景回填并自动聚焦，空场景不抢焦点，避免光标跳动影响示例阅读。
		if (!initialInputText.isEmpty()) {
			serialInput.setValue(initialInputText);
		}
		setInitialFocus(serialInput);
		addRenderableWidget(serialInput);

		addRenderableWidget(Button.builder(CONFIRM, button -> submit()).bounds(centerX - 110, baseY + 80, 108, 20).build());
		addRenderableWidget(Button.builder(CLEAR, button -> clearPair()).bounds(centerX + 2, baseY + 80, 108, 20).build());
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		int centerX = width / 2;
		int baseY = height / 2 - 64;

		guiGraphics.drawCenteredString(font, title, centerX, baseY, 0xFFFFFF);
		guiGraphics.drawCenteredString(font, serialLine(sourceSerial), centerX, baseY + 14, 0xC8C8C8);
		int currentLinksX = centerX - 110;
		int currentLinksY = baseY + 30;
		Component currentLinksLine = currentLinksLine(currentTargets);
		guiGraphics.drawString(font, currentLinksLine, currentLinksX, currentLinksY, 0xC8C8C8, false);
		guiGraphics.drawString(font, inputLabel(), centerX - 110, baseY + 42, 0xFFFFFF, false);

		if (!statusMessage.getString().isEmpty()) {
			// 锚定到按钮底部下方，保持靠近但不与按钮重合。
			int statusMessageY = baseY + BUTTON_ROW_Y_OFFSET_RENDER + ACTION_BUTTON_HEIGHT + STATUS_MESSAGE_MARGIN;
			guiGraphics.drawCenteredString(font, statusMessage, centerX, statusMessageY, 0xFF6666);
		}

		if (isMouseOverInput(mouseX, mouseY)) {
			List<Component> tooltipLines = buildInputTooltipLines();
			if (!tooltipLines.isEmpty()) {
				guiGraphics.renderTooltip(font, tooltipLines, Optional.empty(), mouseX, mouseY);
			}
		} else if (
			!currentTargets.isEmpty()
				&& isMouseOver(currentLinksX, currentLinksY, font.width(currentLinksLine), font.lineHeight, mouseX, mouseY)
		) {
			List<Component> tooltipLines = buildTooltipLines(currentTargets);
			if (!tooltipLines.isEmpty()) {
				guiGraphics.renderTooltip(font, tooltipLines, Optional.empty(), mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 257 || keyCode == 335) {
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

	protected abstract Component serialLine(long sourceSerial);

	protected abstract Component currentLinksLine(List<Long> currentTargets);

	/**
	 * @return `link set` 命令的来源类型（triggerSource/core 语义入口）
	 */
	protected abstract LinkNodeType sourceType();

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
	 * 发送 clear_links 语义（通过空目标覆盖 `link set` 实现）。
	 *
	 * @param sourceSerial 来源节点序列号
	 */
	protected final void sendClearLinksCommand(long sourceSerial) {
		if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
			return;
		}
		minecraft.player.connection.sendCommand(setLinksBaseCommand(sourceSerial));
	}

	/**
	 * 构建 `link set` 命令前缀。
	 */
	private String setLinksBaseCommand(long sourceSerial) {
		return "redstonelink link set " + LinkNodeSemantics.toCommandToken(sourceType()) + " " + sourceSerial;
	}

	/**
	 * 构建主界面“当前连接”结构化文本（`N`/`A:B` + `/`）。
	 *
	 * @param currentTargets 当前目标序号列表
	 * @return 主界面显示文本
	 */
	protected final String buildCurrentLinksText(List<Long> currentTargets) {
		int maxItems = currentTargets == null ? 0 : currentTargets.size();
		return buildTargetsText(currentTargets, maxItems, CURRENT_LINKS_LIST_MAX_WIDTH);
	}

	/**
	 * 构建“当前连接”悬停 tooltip，按分段换行并保持结构化表达式。
	 *
	 * @param currentTargets 当前目标序号列表
	 * @return tooltip 文本行
	 */
	protected final List<Component> buildTooltipLines(List<Long> currentTargets) {
		if (currentTargets == null || currentTargets.isEmpty() || TOOLTIP_MAX_ITEMS <= 0 || TOOLTIP_MAX_WIDTH <= 0) {
			return List.of();
		}

		SerialDisplayFormatUtil.StructuredExpression expression = SerialDisplayFormatUtil.buildExpression(currentTargets);
		if (expression.isEmpty()) {
			return List.of();
		}

		int displaySegments = Math.min(expression.segments().size(), TOOLTIP_MAX_ITEMS);
		List<String> lines = new ArrayList<>();
		StringBuilder currentLine = new StringBuilder();
		for (int i = 0; i < displaySegments; i++) {
			String segment = expression.segments().get(i);
			if (currentLine.isEmpty()) {
				if (font.width(segment) <= TOOLTIP_MAX_WIDTH) {
					currentLine.append(segment);
				} else {
					lines.add(truncateTextByWidth(segment, TOOLTIP_MAX_WIDTH));
				}
				continue;
			}

			String candidate = currentLine + "/" + segment;
			if (font.width(candidate) <= TOOLTIP_MAX_WIDTH) {
				currentLine.append("/").append(segment);
				continue;
			}

			lines.add(currentLine.toString());
			currentLine.setLength(0);
			if (font.width(segment) <= TOOLTIP_MAX_WIDTH) {
				currentLine.append(segment);
			} else {
				lines.add(truncateTextByWidth(segment, TOOLTIP_MAX_WIDTH));
			}
		}
		if (currentLine.length() > 0) {
			lines.add(currentLine.toString());
		}

		int remaining = SerialDisplayFormatUtil.countRemainingSerials(expression, displaySegments);
		if (remaining > 0) {
			appendRemainingSuffix(lines, remaining);
		}
		return lines.stream().map(Component::literal).collect(Collectors.toList());
	}

	/**
	 * 按结构化分段和宽度限制构建文本，并在超出时附加 `(+n)`。
	 *
	 * @param targets 目标序号列表
	 * @param maxItems 最大分段数
	 * @param maxWidth 最大宽度（像素）
	 * @return 展示文本
	 */
	private String buildTargetsText(List<Long> targets, int maxItems, int maxWidth) {
		if (targets == null || targets.isEmpty() || maxItems <= 0 || maxWidth <= 0) {
			return "-";
		}

		SerialDisplayFormatUtil.StructuredExpression expression = SerialDisplayFormatUtil.buildExpression(targets);
		if (expression.isEmpty()) {
			return "-";
		}

		int displaySegments = Math.min(expression.segments().size(), maxItems);
		while (displaySegments > 0) {
			String base = String.join("/", expression.segments().subList(0, displaySegments));
			int remaining = SerialDisplayFormatUtil.countRemainingSerials(expression, displaySegments);
			String text = remaining > 0 ? base + buildRemainingSuffix(remaining) : base;
			if (font.width(text) <= maxWidth) {
				return text;
			}
			displaySegments -= 1;
		}

		int remainingAll = SerialDisplayFormatUtil.countRemainingSerials(expression, 0);
		if (remainingAll > 0) {
			String suffixOnly = buildRemainingSuffix(remainingAll);
			if (font.width(suffixOnly) <= maxWidth) {
				return suffixOnly;
			}
		}
		return "-";
	}

	/**
	 * 生成剩余数量提示文本。
	 *
	 * @param remaining 剩余数量
	 * @return 提示文本（`(+n)`）
	 */
	private String buildRemainingSuffix(int remaining) {
		return SerialDisplayFormatUtil.buildRemainingSuffix(remaining);
	}

	/**
	 * 将剩余数量提示拼接到最后一行，必要时另起一行。
	 *
	 * @param lines 已构建行
	 * @param remaining 剩余数量
	 */
	private void appendRemainingSuffix(List<String> lines, int remaining) {
		String suffix = buildRemainingSuffix(remaining);
		if (lines.isEmpty()) {
			lines.add(suffix);
			return;
		}
		int lastIndex = lines.size() - 1;
		String lastLine = lines.get(lastIndex);
		String combined = lastLine + suffix;
		if (font.width(combined) <= TOOLTIP_MAX_WIDTH) {
			lines.set(lastIndex, combined);
			return;
		}
		if (font.width(suffix) <= TOOLTIP_MAX_WIDTH) {
			lines.add(suffix);
			return;
		}
		lines.add(truncateTextByWidth(suffix, TOOLTIP_MAX_WIDTH));
	}

	/**
	 * 按宽度截断单行文本，末尾追加省略号。
	 *
	 * @param text 原文本
	 * @param maxWidth 最大宽度
	 * @return 截断后的文本
	 */
	private String truncateTextByWidth(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		if (font.width(ellipsis) > maxWidth) {
			return "";
		}
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end) + ellipsis) > maxWidth) {
			end -= 1;
		}
		return text.substring(0, end) + ellipsis;
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
			return;
		}

		List<String> invalidEntries = collectInvalidTargetTokens(serialInput.getValue());
		if (!invalidEntries.isEmpty()) {
			statusMessage = Component.translatable("screen.redstonelink.pairing.invalid_tokens", String.join(", ", invalidEntries));
			return;
		}

		sendSetLinksCommand(sourceSerial, serialInput.getValue());
		onClose();
	}

	/**
	 * 清空当前源节点的连接。
	 */
	private void clearPair() {
		if (sourceSerial <= 0L) {
			statusMessage = invalidInput();
			return;
		}

		sendClearLinksCommand(sourceSerial);
		onClose();
	}

	/**
	 * 将目标序号列表转换为结构化输入文本。
	 */
	protected static String joinTargets(List<Long> targets) {
		if (targets == null || targets.isEmpty()) {
			return "";
		}
		return SerialDisplayFormatUtil.buildExpression(targets).joinAll();
	}

	/**
	 * 收集输入中的非法分段（只做语法校验，不做数量裁决）。
	 */
	private static List<String> collectInvalidTargetTokens(String rawText) {
		String text = rawText == null ? "" : rawText.trim();
		if (text.isEmpty()) {
			return List.of();
		}
		List<String> invalidEntries = new ArrayList<>();
		String[] tokens = text.split("/", -1);
		for (String rawToken : tokens) {
			String token = rawToken == null ? "" : rawToken.trim();
			if (token.isEmpty()) {
				continue;
			}
			if (!isValidTargetToken(token)) {
				invalidEntries.add(rawToken);
			}
		}
		return invalidEntries;
	}

	/**
	 * 校验单个分段：支持 `N` 或 `A:B` 且必须为正整数。
	 */
	private static boolean isValidTargetToken(String token) {
		int colonIndex = token.indexOf(':');
		if (colonIndex < 0) {
			return parsePositiveLong(token) != null;
		}
		if (colonIndex != token.lastIndexOf(':')) {
			return false;
		}
		Long start = parsePositiveLong(token.substring(0, colonIndex));
		Long end = parsePositiveLong(token.substring(colonIndex + 1));
		return start != null && end != null && start <= end;
	}

	/**
	 * 解析正整数字符串（仅语法校验用）。
	 */
	private static Long parsePositiveLong(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch < '0' || ch > '9') {
				return null;
			}
		}
		try {
			long value = Long.parseLong(text);
			return value > 0L ? value : null;
		} catch (NumberFormatException ex) {
			return null;
		}
	}
}
