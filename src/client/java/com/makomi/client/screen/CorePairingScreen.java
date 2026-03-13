package com.makomi.client.screen;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;

/**
 * 核心节点（Core）配对界面。
 * <p>
 * 负责展示核心序列号、当前连接列表，并向服务端提交覆盖式 set_links 命令。
 */
public class CorePairingScreen extends AbstractMultiPairingScreen {
	private static final Component TITLE = Component.translatable("screen.redstonelink.core_pairing.title");
	private static final Component INPUT_LABEL = Component.translatable("screen.redstonelink.core_pairing.input");
	private static final Component INPUT_HINT = Component.translatable("screen.redstonelink.core_pairing.input_hint");
	private static final Component INVALID_INPUT = Component.translatable("screen.redstonelink.core_pairing.invalid");

	/**
	 * 基于明确的核心序列号和当前连接初始化界面。
	 *
	 * @param sourceSerial 源核心序列号
	 * @param currentTargets 当前目标序列号集合
	 */
	public CorePairingScreen(long sourceSerial, List<Long> currentTargets) {
		super(TITLE, sourceSerial, currentTargets);
	}

	/**
	 * @return 输入框标签文本
	 */
	@Override
	protected Component inputLabel() {
		return INPUT_LABEL;
	}

	/**
	 * @return 输入框提示文本
	 */
	@Override
	protected Component inputHint() {
		return INPUT_HINT;
	}

	/**
	 * @return 输入非法时的提示文本
	 */
	@Override
	protected Component invalidInput() {
		return INVALID_INPUT;
	}

	/**
	 * 组装核心序列号展示行。
	 *
	 * @param sourceSerial 核心序列号
	 * @return 本地化后的序列号描述
	 */
	@Override
	protected Component serialLine(long sourceSerial) {
		return Component.translatable(
			"screen.redstonelink.core_pairing.serial",
			sourceSerial > 0L ? Long.toString(sourceSerial) : "-"
		);
	}

	/**
	 * 组装当前连接展示行。
	 *
	 * @param currentTargets 当前目标序列号列表
	 * @return 本地化后的连接描述
	 */
	@Override
	protected Component currentLinksLine(List<Long> currentTargets) {
		String linkedText = currentTargets.isEmpty() ? "-" : buildCurrentLinksText(currentTargets);
		return Component.translatable("screen.redstonelink.core_pairing.current_links", linkedText);
	}

	/**
	 * 发送 set_links 覆盖命令。
	 *
	 * @param sourceSerial 源核心序列号
	 * @param targets 目标集合；为空时表示清空
	 */
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

		String listArg = targets.stream().map(String::valueOf).collect(Collectors.joining("/"));
		minecraft.player.connection.sendCommand(base + " " + listArg);
	}

	/**
	 * 发送清空连接命令。
	 *
	 * @param sourceSerial 源核心序列号
	 */
	@Override
	protected void sendClearLinksCommand(long sourceSerial) {
		if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
			return;
		}
		minecraft.player.connection.sendCommand("redstonelink set_links core " + sourceSerial);
	}
}
