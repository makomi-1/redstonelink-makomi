package com.makomi.client.screen;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SerialDisplayFormatUtil;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
		serialInput.setMaxLength(512);
		serialInput.setHint(inputHint());
		serialInput.setValue(joinTargets(currentTargets));
		addRenderableWidget(serialInput);
		setInitialFocus(serialInput);

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
			guiGraphics.drawCenteredString(font, statusMessage, centerX, baseY + 105, 0xFF6666);
		}
		if (!currentTargets.isEmpty()
			&& isMouseOver(currentLinksX, currentLinksY, font.width(currentLinksLine), font.lineHeight, mouseX, mouseY)) {
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

	protected abstract Component inputHint();

	protected abstract Component invalidInput();

	protected abstract Component serialLine(long sourceSerial);

	protected abstract Component currentLinksLine(List<Long> currentTargets);

	protected abstract void sendSetLinksCommand(long sourceSerial, Set<Long> targets);

	protected abstract void sendClearLinksCommand(long sourceSerial);

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
	 * 提交覆盖式 set_links。
	 */
	private void submit() {
		if (sourceSerial <= 0L) {
			statusMessage = invalidInput();
			return;
		}

		int maxTargetCount = Math.max(1, RedstoneLinkConfig.maxTargetsPerSetLinks());
		TargetParseResult parseResult = parseTargets(serialInput.getValue(), maxTargetCount);
		if (!parseResult.invalidEntries().isEmpty()) {
			String invalidEntries = String.join(", ", parseResult.invalidEntries());
			statusMessage = Component.translatable("screen.redstonelink.pairing.invalid_tokens", invalidEntries);
			return;
		}
		if (parseResult.exceedLimit()) {
			statusMessage = Component.translatable("screen.redstonelink.pairing.too_many", maxTargetCount);
			return;
		}
		if (!parseResult.duplicateEntries().isEmpty() && minecraft != null && minecraft.player != null) {
			String duplicates = parseResult.duplicateEntries()
				.stream()
				.sorted()
				.map(String::valueOf)
				.collect(Collectors.joining(", "));
			minecraft.player.displayClientMessage(
				Component.translatable("message.redstonelink.duplicate_targets_deduped", duplicates),
				false
			);
		}

		sendSetLinksCommand(sourceSerial, parseResult.targets());
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
	 * 解析输入框目标文本。
	 */
	protected static TargetParseResult parseTargets(String rawText, int maxTargetCount) {
		SerialParseUtil.TargetParseResult parsed = SerialParseUtil.parseTargets(rawText, maxTargetCount);
		return new TargetParseResult(parsed.targets(), parsed.invalidEntries(), parsed.duplicateEntries(), parsed.exceedLimit());
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
	 * 输入解析结果。
	 */
	protected record TargetParseResult(
		Set<Long> targets,
		List<String> invalidEntries,
		List<Long> duplicateEntries,
		boolean exceedLimit
	) {}
}
