package com.makomi.client.screen;

import com.makomi.data.LinkNodeType;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 核心（core）配对界面。
 * <p>
 * 负责显示当前核心序列号与已连接目标，发送逻辑由抽象父类统一处理。
 * </p>
 */
public class CorePairingScreen extends AbstractMultiPairingScreen {
	private static final LinkNodeType SOURCE_TYPE = LinkNodeType.CORE;
	private static final Component TITLE = Component.translatable("screen.redstonelink.core_pairing.title");
	private static final Component INPUT_LABEL = Component.translatable("screen.redstonelink.core_pairing.input");
	private static final Component INVALID_INPUT = Component.translatable("screen.redstonelink.core_pairing.invalid");

	/**
	 * 基于明确来源序列号与当前连接初始化界面。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param currentTargets 当前已连接目标序列号列表
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
	 * @return 输入非法时提示文本
	 */
	@Override
	protected Component invalidInput() {
		return INVALID_INPUT;
	}

	/**
	 * 组装来源节点序列号展示文本。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @return 本地化后的序列号文本
	 */
	@Override
	protected Component serialLine(long sourceSerial) {
		return Component.translatable(
			"screen.redstonelink.core_pairing.serial",
			sourceSerial > 0L ? Long.toString(sourceSerial) : "-"
		);
	}

	/**
	 * 组装“当前连接”展示文本。
	 *
	 * @param currentTargets 当前目标序列号列表
	 * @return 本地化后的连接文本
	 */
	@Override
	protected Component currentLinksLine(List<Long> currentTargets) {
		String linkedText = currentTargets.isEmpty() ? "-" : buildCurrentLinksText(currentTargets);
		return Component.translatable("screen.redstonelink.core_pairing.current_links", linkedText);
	}

	/**
	 * @return core 语义对应的来源类型（CORE）
	 */
	@Override
	protected LinkNodeType sourceType() {
		return SOURCE_TYPE;
	}
}
