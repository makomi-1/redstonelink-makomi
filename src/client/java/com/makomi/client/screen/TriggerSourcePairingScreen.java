package com.makomi.client.screen;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.network.PairingNetwork;
import com.makomi.util.SignalStrengths;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 触发源（triggerSource）配对界面。
 * <p>
 * 负责显示当前触发源序列号与已连接目标，发送逻辑由抽象父类统一处理。
 * </p>
 */
public class TriggerSourcePairingScreen extends AbstractMultiPairingScreen {
	private static final StyledMultiLineEditBox.Style TRIGGER_SOURCE_INPUT_BOX_STYLE = new StyledMultiLineEditBox.Style(
		0xFF9F5600,
		0xFF6E3A00,
		0xFFFFD180,
		0xFFFFD9B0
	);
	private static final StyledEditBox.Style TRIGGER_SOURCE_ALIAS_INPUT_STYLE = new StyledEditBox.Style(
		0xFF9F5600,
		0xFF6E3A00,
		0xFFFFD180,
		0x9960402A,
		0xFF6E3A00,
		0xFFFFF7F0,
		0xFFD4B8A2
	);
	private static final StyledButton.Style TRIGGER_SOURCE_ACTION_BUTTON_STYLE = new StyledButton.Style(
		0xE09F5600,
		0xF0BF6A14,
		0x9960402A,
		0xFF6E3A00,
		0xFFFFD180,
		0xFF7A5C47,
		0xFFFFF7F0,
		0xFFD4B8A2
	);
	private static final LinkNodeType SOURCE_TYPE = LinkNodeType.TRIGGER_SOURCE;
	private static final Component TITLE = Component.translatable("screen.redstonelink.trigger_source_pairing.title");
	private static final Component INPUT_LABEL = Component.translatable("screen.redstonelink.trigger_source_pairing.input");
	private static final Component INVALID_INPUT = Component.translatable("screen.redstonelink.trigger_source_pairing.invalid");
	private static final Component CHANNEL_INPUT_LABEL = Component.translatable("screen.redstonelink.pairing.channel_input");
	private static final Component INVALID_CHANNEL_INPUT = Component.translatable("screen.redstonelink.pairing.invalid_channel");
	private final String displayContextToken;
	private int currentSyncSignalStrength;
	private Button decreaseSignalButton;
	private Button signalValueButton;
	private Button increaseSignalButton;

	/**
	 * 基于明确来源序列号与当前连接初始化界面。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param currentTargets 当前已连接目标序列号列表
	 */
	public TriggerSourcePairingScreen(
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
		this.currentSyncSignalStrength = resolveInitialSyncLinkerSignalStrength(this.displayContextToken, sourceSerial);
	}

	public TriggerSourcePairingScreen(
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

	public TriggerSourcePairingScreen(long sourceSerial, List<Long> currentTargets, long graphRevision, long sourceRevision) {
		this(
			sourceSerial,
			currentTargets,
			List.of(),
			graphRevision,
			sourceRevision,
			0L,
			LinkConnectionMode.SERIAL,
			0L,
			LinkGuiDisplayContext.TRIGGER_SOURCE,
			"",
			NodeAliasDisplayUtil.formatDisplayText("", sourceSerial)
		);
	}

	public TriggerSourcePairingScreen(long sourceSerial, List<Long> currentTargets) {
		this(sourceSerial, currentTargets, 0L, 0L);
	}

	/**
	 * 基于玩家手持物品初始化界面。
	 *
	 * @param hand 手持槽位（主手/副手）
	 */
	public TriggerSourcePairingScreen(InteractionHand hand) {
		this(
			resolveHeldSerial(hand),
			List.of(),
			List.of(),
			0L,
			0L,
			0L,
			LinkConnectionMode.SERIAL,
			0L,
			resolveHeldDisplayContextToken(hand),
			resolveHeldSourceAlias(hand),
			resolveHeldSourceDisplayText(hand)
		);
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
		return Component.translatable("screen.redstonelink.trigger_source_pairing.current_links", currentLinksText);
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
		if (isRepeaterContext()) {
			return RepeaterPairingThemeSupport.inputBoxStyle();
		}
		return TRIGGER_SOURCE_INPUT_BOX_STYLE;
	}

	@Override
	protected StyledEditBox.Style aliasInputStyle() {
		if (isRepeaterContext()) {
			return RepeaterPairingThemeSupport.aliasInputStyle();
		}
		return TRIGGER_SOURCE_ALIAS_INPUT_STYLE;
	}

	@Override
	protected StyledButton.Style actionButtonStyle(ActionButtonKind kind) {
		if (isRepeaterContext()) {
			return RepeaterPairingThemeSupport.actionButtonStyle();
		}
		return TRIGGER_SOURCE_ACTION_BUTTON_STYLE;
	}

	@Override
	protected boolean allowChannelMode() {
		return !LinkGuiDisplayContext.LINK_REPEATER.equals(displayContextToken);
	}

	@Override
	protected LayoutDensity layoutDensity() {
		return isRepeaterContext() ? LayoutDensity.COMPACT : super.layoutDensity();
	}

	@Override
	protected boolean hasSupplementalButtonRow() {
		return isSyncLinkerContext();
	}

	@Override
	protected GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		if (isRepeaterContext()) {
			return RepeaterPairingThemeSupport.backgroundPreset();
		}
		return GuiBackgroundRenderSupport.BackgroundPreset.TRIGGER_SOURCE_PAIRING;
	}

	@Override
	protected void initSupplementalButtonRow(MultiPairingLayout layout) {
		int buttonWidth = CenteredFormLayoutSupport.resolveSplitWidth(layout.panelWidth(), actionButtonGap(), 3);
		int buttonHeight = actionButtonHeight();
		int rowY = layout.supplementalButtonY();
		decreaseSignalButton =
			addRenderableWidget(
				createActionButton(
					ActionButtonKind.CONFIRM,
					Component.empty(),
					layout.panelLeft(),
					rowY,
					buttonWidth,
					button -> adjustSyncSignalStrength(-1)
				)
			);
		signalValueButton =
			addRenderableWidget(
				createActionButton(
					ActionButtonKind.CONFIRM,
					Component.empty(),
					layout.panelLeft() + buttonWidth + actionButtonGap(),
					rowY,
					buttonWidth,
					button -> {}
				)
			);
		increaseSignalButton =
			addRenderableWidget(
				createActionButton(
					ActionButtonKind.CONFIRM,
					Component.empty(),
					layout.panelLeft() + (buttonWidth + actionButtonGap()) * 2,
					rowY,
					buttonWidth,
					button -> adjustSyncSignalStrength(1)
				)
			);
		signalValueButton.active = false;
		signalValueButton.setHeight(buttonHeight);
		refreshSyncSignalButtons();
	}

	/**
	 * @return 当前 GUI 是否由转发器入口打开
	 */
	private boolean isRepeaterContext() {
		return LinkGuiDisplayContext.LINK_REPEATER.equals(displayContextToken);
	}

	/**
	 * @return 当前 GUI 是否由同步遥控器入口打开
	 */
	private boolean isSyncLinkerContext() {
		return LinkGuiDisplayContext.REDSTONELINK_SYNC_LINKER.equals(displayContextToken);
	}

	/**
	 * @return triggerSource 语义对应的来源类型（TRIGGER_SOURCE）
	 */
	@Override
	protected LinkNodeType sourceType() {
		return SOURCE_TYPE;
	}

	/**
	 * triggerSource 配对改走结构化提交，避免再拼装命令字符串。
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
			new PairingNetwork.SubmitTriggerSourcePairingPayload(
				sourceSerial,
				connectionMode.token(),
				rawTargetsInput,
				channel,
				sourceRevision
			)
		);
	}

	/**
	 * 调整同步遥控器当前缓存强度，并立即同步到本地主手物品与服务端真值。
	 */
	private void adjustSyncSignalStrength(int delta) {
		int nextSignalStrength = SignalStrengths.clamp(currentSyncSignalStrength + delta);
		if (delta == 0) {
			return;
		}
		currentSyncSignalStrength = nextSignalStrength;
		ItemStack held = resolveHeldSyncLinkerStack(sourceSerial);
		long expectedSerial = sourceSerial;
		if (!held.isEmpty()) {
			expectedSerial = LinkItemData.getSerial(held);
			LinkItemData.setSyncLinkerSignalStrength(held, currentSyncSignalStrength);
		}
		ClientPlayNetworking.send(new PairingNetwork.SaveSyncLinkerSignalStrengthPayload(expectedSerial, currentSyncSignalStrength));
		refreshSyncSignalButtons();
	}

	/**
	 * 按当前强度刷新同步遥控器附加按钮的文案与可用状态。
	 */
	private void refreshSyncSignalButtons() {
		if (decreaseSignalButton != null) {
			decreaseSignalButton.setMessage(Component.translatable("screen.redstonelink.sync_linker.signal_strength.decrease"));
			decreaseSignalButton.active = currentSyncSignalStrength > 0;
		}
		if (signalValueButton != null) {
			signalValueButton.setMessage(Component.literal(Integer.toString(currentSyncSignalStrength)));
			signalValueButton.active = false;
		}
		if (increaseSignalButton != null) {
			increaseSignalButton.setMessage(Component.translatable("screen.redstonelink.sync_linker.signal_strength.increase"));
			increaseSignalButton.active = currentSyncSignalStrength < 15;
		}
	}

	/**
	 * 解析玩家手持物品上的来源序列号。
	 *
	 * @param hand 手持槽位
	 * @return 解析后的序列号；无法解析返回 0
	 */
	private static long resolveHeldSerial(InteractionHand hand) {
		if (net.minecraft.client.Minecraft.getInstance().player == null) {
			return 0L;
		}
		ItemStack held = net.minecraft.client.Minecraft.getInstance().player.getItemInHand(hand);
		return LinkItemData.getSerial(held);
	}

	/**
	 * 解析玩家手持物品的配对显示上下文。
	 */
	private static String resolveHeldDisplayContextToken(InteractionHand hand) {
		if (net.minecraft.client.Minecraft.getInstance().player == null) {
			return LinkGuiDisplayContext.TRIGGER_SOURCE;
		}
		ItemStack held = net.minecraft.client.Minecraft.getInstance().player.getItemInHand(hand);
		return LinkGuiDisplayContext.resolvePairingContextToken(held, SOURCE_TYPE);
	}

	/**
	 * 解析玩家手持物品上的来源原始别名。
	 */
	private static String resolveHeldSourceAlias(InteractionHand hand) {
		if (net.minecraft.client.Minecraft.getInstance().player == null) {
			return "";
		}
		ItemStack held = net.minecraft.client.Minecraft.getInstance().player.getItemInHand(hand);
		return LinkItemData.getDisplayAlias(held);
	}

	/**
	 * 解析玩家手持物品上的来源展示文本。
	 */
	private static String resolveHeldSourceDisplayText(InteractionHand hand) {
		long serial = resolveHeldSerial(hand);
		if (net.minecraft.client.Minecraft.getInstance().player == null) {
			return NodeAliasDisplayUtil.formatDisplayText("", serial);
		}
		ItemStack held = net.minecraft.client.Minecraft.getInstance().player.getItemInHand(hand);
		return NodeAliasDisplayUtil.formatDisplayText(resolveHeldSourceAlias(hand), serial);
	}

	/**
	 * 解析同步遥控器打开 GUI 时的初始缓存强度。
	 */
	private static int resolveInitialSyncLinkerSignalStrength(String displayContextToken, long sourceSerial) {
		if (!LinkGuiDisplayContext.REDSTONELINK_SYNC_LINKER.equals(displayContextToken)) {
			return 0;
		}
		ItemStack held = resolveHeldSyncLinkerStack(sourceSerial);
		if (held.isEmpty()) {
			return 0;
		}
		return LinkItemData.getSyncLinkerSignalStrength(held);
	}

	/**
	 * 解析当前主手同步遥控器，并校验序号与当前 GUI 来源一致。
	 */
	private static ItemStack resolveHeldSyncLinkerStack(long sourceSerial) {
		if (net.minecraft.client.Minecraft.getInstance().player == null) {
			return ItemStack.EMPTY;
		}
		ItemStack held = net.minecraft.client.Minecraft.getInstance().player.getMainHandItem();
		if (held.isEmpty() || !(held.getItem() instanceof com.makomi.item.SyncLinkerItem)) {
			return ItemStack.EMPTY;
		}
		long heldSerial = LinkItemData.getSerial(held);
		if (sourceSerial > 0L && heldSerial > 0L && heldSerial != sourceSerial) {
			return ItemStack.EMPTY;
		}
		return held;
	}
}
