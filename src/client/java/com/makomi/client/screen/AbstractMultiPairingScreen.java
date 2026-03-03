package com.makomi.client.screen;

import com.makomi.config.RedstoneLinkConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public abstract class AbstractMultiPairingScreen extends Screen {
	private static final Component CONFIRM = Component.translatable("screen.redstonelink.pairing.confirm");
	private static final Component CLEAR = Component.translatable("screen.redstonelink.pairing.clear");

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
		guiGraphics.drawString(font, currentLinksLine(currentTargets), centerX - 110, baseY + 30, 0xC8C8C8, false);
		guiGraphics.drawString(font, inputLabel(), centerX - 110, baseY + 42, 0xFFFFFF, false);

		if (!statusMessage.getString().isEmpty()) {
			guiGraphics.drawCenteredString(font, statusMessage, centerX, baseY + 105, 0xFF6666);
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
		String text = rawText == null ? "" : rawText.trim();
		if (text.isEmpty()) {
			return new TargetParseResult(Set.of(), List.of(), false);
		}

		String[] tokens = text.split("[,;\\s]+");
		LinkedHashSet<Long> result = new LinkedHashSet<>();
		List<String> invalidEntries = new ArrayList<>();
		for (String token : tokens) {
			if (token.isBlank()) {
				continue;
			}
			if (!token.matches("[0-9]+")) {
				invalidEntries.add(token);
				continue;
			}

			long serial;
			try {
				serial = Long.parseLong(token);
			} catch (NumberFormatException ignored) {
				invalidEntries.add(token);
				continue;
			}

			if (serial <= 0L) {
				invalidEntries.add(token);
				continue;
			}
			result.add(serial);
			if (result.size() > maxTargetCount) {
				return new TargetParseResult(Set.of(), invalidEntries, true);
			}
		}
		return new TargetParseResult(Set.copyOf(result), List.copyOf(invalidEntries), false);
	}

	protected static String joinTargets(List<Long> targets) {
		return targets.stream().map(String::valueOf).collect(Collectors.joining(","));
	}

	protected record TargetParseResult(Set<Long> targets, List<String> invalidEntries, boolean exceedLimit) {}
}
