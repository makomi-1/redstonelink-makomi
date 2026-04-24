package com.makomi.client.screen;

import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
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
	private static final StyledEditBox.Style CORE_ALIAS_INPUT_STYLE = new StyledEditBox.Style(
		0xFF0D47A1,
		0xFF08306B,
		0xFF4FC3F7,
		0x99122B45,
		0xFF08306B,
		0xFFF4FAFF,
		0xFF9EB1C8
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
	private static final Component CHANNEL_INPUT_LABEL = Component.translatable("screen.redstonelink.pairing.channel_input");
	private static final Component INVALID_CHANNEL_INPUT = Component.translatable("screen.redstonelink.pairing.invalid_channel");
	private final String displayContextToken;

	/**
	 * 基于明确来源序列号与当前连接初始化界面。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param currentTargets 当前已连接目标序列号列表
	 */
	public CorePairingScreen(
		long sourceSerial,
		List<Long> currentTargets,
		List<String> currentTargetDisplayTexts,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		LinkConnectionMode connectionMode,
		long channel,
		String displayContextToken,
		String sourceAlias,
		String sourceDisplayText
	) {
		super(
			TITLE,
			sourceSerial,
			sourceAlias,
			sourceDisplayText,
			currentTargets,
			currentTargetDisplayTexts,
			graphRevision,
			sourceRevision,
			coreRevision,
			connectionMode,
			channel
		);
		this.displayContextToken = LinkGuiDisplayContext.normalizePairingContextToken(displayContextToken, SOURCE_TYPE);
	}

	public CorePairingScreen(
		long sourceSerial,
		List<Long> currentTargets,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		LinkConnectionMode connectionMode,
		long channel,
		String displayContextToken,
		String sourceDisplayText
	) {
		this(
			sourceSerial,
			currentTargets,
			List.of(),
			graphRevision,
			sourceRevision,
			coreRevision,
			connectionMode,
			channel,
			displayContextToken,
			"",
			sourceDisplayText
		);
	}

	public CorePairingScreen(long sourceSerial, List<Long> currentTargets, long graphRevision, long sourceRevision) {
		this(
			sourceSerial,
			currentTargets,
			List.of(),
			graphRevision,
			sourceRevision,
			0L,
			LinkConnectionMode.SERIAL,
			0L,
			LinkGuiDisplayContext.CORE,
			"",
			NodeAliasDisplayUtil.formatDisplayText("", sourceSerial)
		);
	}

	public CorePairingScreen(long sourceSerial, List<Long> currentTargets) {
		this(sourceSerial, currentTargets, 0L, 0L);
	}

	/**
	 * @return 输入框标签文本
	 */
	@Override
	protected Component inputLabel() {
		return isChannelMode() ? CHANNEL_INPUT_LABEL : INPUT_LABEL;
	}

	/**
	 * @return 输入非法时提示文本
	 */
	@Override
	protected Component invalidInput() {
		return isChannelMode() ? INVALID_CHANNEL_INPUT : INVALID_INPUT;
	}

	/**
	 * 组装“当前连接”展示文本。
	 *
	 * @param currentLinksText 当前连接文本
	 * @return 本地化后的连接文本
	 */
	@Override
	protected Component currentLinksLine(String currentLinksText) {
		return Component.translatable("screen.redstonelink.core_pairing.current_links", currentLinksText);
	}

	@Override
	protected Component headerTitle() {
		return GuiHeaderContextSupport.pairingHeader(displayContextToken, SOURCE_TYPE, Component.empty(), 0xFFFFFFFF).title();
	}

	@Override
	protected GuiHeaderRenderSupport.HeaderIcon headerIcon() {
		return GuiHeaderContextSupport.pairingHeader(displayContextToken, SOURCE_TYPE, Component.empty(), 0xFFFFFFFF).icon();
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
	protected StyledEditBox.Style aliasInputStyle() {
		return CORE_ALIAS_INPUT_STYLE;
	}

	@Override
	protected StyledButton.Style actionButtonStyle(ActionButtonKind kind) {
		return CORE_ACTION_BUTTON_STYLE;
	}

	@Override
	protected boolean allowChannelMode() {
		return !LinkGuiDisplayContext.LINK_REPEATER.equals(displayContextToken);
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
	protected void submitPairingRequest(
		long sourceSerial,
		LinkConnectionMode connectionMode,
		String rawTargetsInput,
		long channel
	) {
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			return;
		}
		ClientPlayNetworking.send(
			new PairingNetwork.SubmitCorePairingPayload(sourceSerial, connectionMode.token(), rawTargetsInput, channel, coreRevision)
		);
	}
}
