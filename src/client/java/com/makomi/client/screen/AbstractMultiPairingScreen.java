package com.makomi.client.screen;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Optional;

/**
 * 多目标配对界面抽象基类。
 * <p>
 * 统一输入框解析、目标数量校验与确认/清空按钮流程，
 * 由子类决定命令前缀与文案（按钮侧或核心侧）。
 * </p>
 */
public abstract class AbstractMultiPairingScreen extends Screen {
	private static final Component CONFIRM = Component.translatable("screen.redstonelink.pairing.confirm");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.pairing.clear");
	/**
	 * “当前连接”列表在主界面的最大显示宽度（像素）。
	 */
	private static final int CURRENT_LINKS_LIST_MAX_WIDTH = 220;
	/**
	 * Tooltip 列表的最大显示宽度（像素）。
	 */
	private static final int TOOLTIP_MAX_WIDTH = 320;
	/**
	 * Tooltip 最多展示的目标条目数量（可调）。
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
				guiGraphics.renderTooltip(font, tooltipLines,Optional.empty(), mouseX, mouseY);
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
	 * 组装“当前连接”列表的主界面展示文本，按宽度截断并补充剩余数量。
	 *
	 * @param currentTargets 当前目标序列号列表
	 * @return 截断后的目标列表文本（包含 …(+N) 形式）
	 */
	protected final String buildCurrentLinksText(List<Long> currentTargets) {
		int maxItems = currentTargets == null ? 0 : currentTargets.size();
		return buildTargetsText(currentTargets, maxItems, CURRENT_LINKS_LIST_MAX_WIDTH);
	}

	/**
	 * 组装“当前连接”列表的 tooltip 文本行，按条目数限制并按宽度换行扩展高度。
	 *
	 * @param currentTargets 当前目标序列号列表
	 * @return tooltip 文本行列表（包含 …(+N) 形式）
	 */
	protected final List<Component> buildTooltipLines(List<Long> currentTargets) {
		if (currentTargets == null || currentTargets.isEmpty() || TOOLTIP_MAX_ITEMS <= 0 || TOOLTIP_MAX_WIDTH <= 0) {
			return List.of();
		}
		List<String> items = currentTargets.stream().map(String::valueOf).collect(Collectors.toList());
		int total = items.size();
		int displayCount = Math.min(total, TOOLTIP_MAX_ITEMS);
		int remaining = total - displayCount;
		List<String> lines = new ArrayList<>();
		StringBuilder currentLine = new StringBuilder();
		for (int i = 0; i < displayCount; i++) {
			String item = items.get(i);
			if (currentLine.isEmpty()) {
				if (font.width(item) <= TOOLTIP_MAX_WIDTH) {
					currentLine.append(item);
				} else {
					lines.add(truncateTextByWidth(item, TOOLTIP_MAX_WIDTH));
				}
				continue;
			}
			String candidate = currentLine + "," + item;
			if (font.width(candidate) <= TOOLTIP_MAX_WIDTH) {
				currentLine.append(",").append(item);
				continue;
			}
			lines.add(currentLine.toString());
			currentLine.setLength(0);
			if (font.width(item) <= TOOLTIP_MAX_WIDTH) {
				currentLine.append(item);
			} else {
				lines.add(truncateTextByWidth(item, TOOLTIP_MAX_WIDTH));
			}
		}
		if (currentLine.length() > 0) {
			lines.add(currentLine.toString());
		}
		if (remaining > 0) {
			appendRemainingSuffix(lines, remaining);
		}
		return lines.stream().map(Component::literal).collect(Collectors.toList());
	}

	/**
	 * 将目标序列号列表按“条目数 + 宽度”截断，并在末尾追加 …(+N) 提示。
	 *
	 * @param targets 目标序列号列表
	 * @param maxItems 最大显示条目数
	 * @param maxWidth 最大显示宽度（像素）
	 * @return 截断后的列表文本
	 */
	private String buildTargetsText(List<Long> targets, int maxItems, int maxWidth) {
		if (targets == null || targets.isEmpty() || maxItems <= 0 || maxWidth <= 0) {
			return "-";
		}
		List<String> items = targets.stream().map(String::valueOf).collect(Collectors.toList());
		int total = items.size();
		int displayCount = Math.min(total, maxItems);
		int remaining = total - displayCount;
		while (displayCount > 0) {
			String base = String.join(",", items.subList(0, displayCount));
			String text = remaining > 0 ? base + buildRemainingSuffix(remaining) : base;
			if (font.width(text) <= maxWidth) {
				return text;
			}
			displayCount -= 1;
			remaining += 1;
		}
		if (remaining > 0) {
			String suffixOnly = buildRemainingSuffix(remaining);
			if (font.width(suffixOnly) <= maxWidth) {
				return suffixOnly;
			}
		}
		return "-";
	}

	/**
	 * 生成“剩余数量”提示文本，格式为 …(+N)。
	 *
	 * @param remaining 剩余数量
	 * @return 提示文本
	 */
	private String buildRemainingSuffix(int remaining) {
		return "…(+" + remaining + ")";
	}

	/**
	 * 将剩余数量提示追加到最后一行，必要时另起一行。
	 *
	 * @param lines 已构建的行文本
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
	 * 将单行文本按宽度截断，末尾追加省略号。
	 *
	 * @param text 原始文本
	 * @param maxWidth 最大宽度
	 * @return 截断后的文本
	 */
	private String truncateTextByWidth(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
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
	 *
	 * @param x 区域左上角 X
	 * @param y 区域左上角 Y
	 * @param width 区域宽度
	 * @param height 区域高度
	 * @param mouseX 鼠标 X
	 * @param mouseY 鼠标 Y
	 * @return 是否悬停
	 */
	private boolean isMouseOver(int x, int y, int width, int height, int mouseX, int mouseY) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

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

		sendSetLinksCommand(sourceSerial, parseResult.targets());
		onClose();
	}

	private void clearPair() {
		if (sourceSerial <= 0L) {
			statusMessage = invalidInput();
			return;
		}

		sendClearLinksCommand(sourceSerial);
		onClose();
	}

	protected static TargetParseResult parseTargets(String rawText, int maxTargetCount) {
		SerialParseUtil.TargetParseResult parsed = SerialParseUtil.parseTargets(rawText, maxTargetCount);
		return new TargetParseResult(parsed.targets(), parsed.invalidEntries(), parsed.exceedLimit());
	}

	protected static String joinTargets(List<Long> targets) {
		return targets.stream().map(String::valueOf).collect(Collectors.joining(","));
	}

	protected record TargetParseResult(Set<Long> targets, List<String> invalidEntries, boolean exceedLimit) {}
}