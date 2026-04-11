package com.makomi.client.screen;

import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

/**
 * 核心（core）配对界面。
 * <p>
 * 负责显示当前核心序列号与已连接目标，发送逻辑由抽象父类统一处理。
 * </p>
 */
public class CorePairingScreen extends AbstractMultiPairingScreen {
	private static final StyledMultiLineEditBox.Style CORE_INPUT_BOX_STYLE = new StyledMultiLineEditBox.Style(
		0xFF0D47A1,
		0xFF08306B,
		0xFF4FC3F7,
		0xFFB9D7FF
	);
	private static final StyledButton.Style CORE_ACTION_BUTTON_STYLE = new StyledButton.Style(
		0xE00D47A1,
		0xF01565C0,
		0x99122B45,
		0xFF08306B,
		0xFF4FC3F7,
		0xFF445A73,
		0xFFF4FAFF,
		0xFF9EB1C8
	);
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
	public CorePairingScreen(long sourceSerial, List<Long> currentTargets, long graphRevision, long sourceRevision, long coreRevision) {
		super(TITLE, sourceSerial, currentTargets, graphRevision, sourceRevision, coreRevision);
	}

	public CorePairingScreen(long sourceSerial, List<Long> currentTargets, long graphRevision, long sourceRevision) {
		this(sourceSerial, currentTargets, graphRevision, sourceRevision, 0L);
	}

	public CorePairingScreen(long sourceSerial, List<Long> currentTargets) {
		this(sourceSerial, currentTargets, 0L, 0L);
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

	@Override
	protected int currentLinksTextColor() {
		return backgroundPreset().borderColor();
	}

	@Override
	protected StyledMultiLineEditBox.Style inputBoxStyle() {
		return CORE_INPUT_BOX_STYLE;
	}

	@Override
	protected StyledButton.Style actionButtonStyle(ActionButtonKind kind) {
		return CORE_ACTION_BUTTON_STYLE;
	}

	@Override
	protected GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.CORE_PAIRING;
	}

	/**
	 * @return core 视角对应的节点类型（CORE）
	 */
	@Override
	protected LinkNodeType sourceType() {
		return SOURCE_TYPE;
	}

	/**
	 * core 配对改走结构化提交，再由服务端拆成 `triggerSource -> core` 正向写入。
	 */
	@Override
	protected void submitPairingRequest(long sourceSerial, String rawTargetsInput) {
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			return;
		}
		ClientPlayNetworking.send(new PairingNetwork.SubmitCorePairingPayload(sourceSerial, rawTargetsInput, coreRevision));
	}

	/**
	 * core 清空操作走空表达式结构化提交。
	 */
	@Override
	protected void clearPairingRequest(long sourceSerial) {
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			return;
		}
		ClientPlayNetworking.send(new PairingNetwork.SubmitCorePairingPayload(sourceSerial, "", coreRevision));
	}
}
