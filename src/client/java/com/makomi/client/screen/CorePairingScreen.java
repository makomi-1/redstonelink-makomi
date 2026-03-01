package com.makomi.client.screen;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;

public class CorePairingScreen extends AbstractMultiPairingScreen {
	private static final Component TITLE = Component.translatable("screen.redstonelink.core_pairing.title");
	private static final Component INPUT_LABEL = Component.translatable("screen.redstonelink.core_pairing.input");
	private static final Component INPUT_HINT = Component.translatable("screen.redstonelink.core_pairing.input_hint");
	private static final Component INVALID_INPUT = Component.translatable("screen.redstonelink.core_pairing.invalid");

	public CorePairingScreen(long sourceSerial, List<Long> currentTargets) {
		super(TITLE, sourceSerial, currentTargets);
	}

	@Override
	protected Component inputLabel() {
		return INPUT_LABEL;
	}

	@Override
	protected Component inputHint() {
		return INPUT_HINT;
	}

	@Override
	protected Component invalidInput() {
		return INVALID_INPUT;
	}

	@Override
	protected Component serialLine(long sourceSerial) {
		return Component.translatable(
			"screen.redstonelink.core_pairing.serial",
			sourceSerial > 0L ? Long.toString(sourceSerial) : "-"
		);
	}

	@Override
	protected Component currentLinksLine(List<Long> currentTargets) {
		String linkedText = currentTargets.isEmpty() ? "-" : joinTargets(currentTargets);
		return Component.translatable("screen.redstonelink.core_pairing.current_links", linkedText);
	}

	@Override
	protected void sendSetLinksCommand(long sourceSerial, Set<Long> targets) {
		if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
			return;
		}
		String base = "redstonelink set_links core " + sourceSerial;
		if (targets.isEmpty()) {
			minecraft.player.connection.sendCommand(base);
			return;
		}

		String listArg = targets.stream().map(String::valueOf).collect(Collectors.joining(","));
		minecraft.player.connection.sendCommand(base + " " + listArg);
	}

	@Override
	protected void sendClearLinksCommand(long sourceSerial) {
		if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
			return;
		}
		minecraft.player.connection.sendCommand("redstonelink set_links core " + sourceSerial);
	}
}
