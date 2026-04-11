package com.makomi.client.screen;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
	private final String displayContextToken;

	/**
	 * 基于明确来源序列号与当前连接初始化界面。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param currentTargets 当前已连接目标序列号列表
	 */
	public TriggerSourcePairingScreen(
		long sourceSerial,
		List<Long> currentTargets,
		long graphRevision,
		long sourceRevision,
		long coreRevision,
		String displayContextToken
	) {
		super(TITLE, sourceSerial, currentTargets, graphRevision, sourceRevision, coreRevision);
		this.displayContextToken = LinkGuiDisplayContext.normalizePairingContextToken(displayContextToken, SOURCE_TYPE);
	}

	public TriggerSourcePairingScreen(long sourceSerial, List<Long> currentTargets, long graphRevision, long sourceRevision) {
		this(sourceSerial, currentTargets, graphRevision, sourceRevision, 0L, LinkGuiDisplayContext.TRIGGER_SOURCE);
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
		this(resolveHeldSerial(hand), List.of(), 0L, 0L, 0L, resolveHeldDisplayContextToken(hand));
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
			"screen.redstonelink.trigger_source_pairing.serial",
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
		return Component.translatable("screen.redstonelink.trigger_source_pairing.current_links", linkedText);
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
		return TRIGGER_SOURCE_INPUT_BOX_STYLE;
	}

	@Override
	protected StyledButton.Style actionButtonStyle(ActionButtonKind kind) {
		return TRIGGER_SOURCE_ACTION_BUTTON_STYLE;
	}

	@Override
	protected GuiBackgroundRenderSupport.BackgroundPreset backgroundPreset() {
		return GuiBackgroundRenderSupport.BackgroundPreset.TRIGGER_SOURCE_PAIRING;
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
	protected void submitPairingRequest(long sourceSerial, String rawTargetsInput) {
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			return;
		}
		ClientPlayNetworking.send(new PairingNetwork.SubmitTriggerSourcePairingPayload(sourceSerial, rawTargetsInput, sourceRevision));
	}

	/**
	 * triggerSource 清空操作走空表达式结构化提交。
	 */
	@Override
	protected void clearPairingRequest(long sourceSerial) {
		if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
			return;
		}
		ClientPlayNetworking.send(new PairingNetwork.SubmitTriggerSourcePairingPayload(sourceSerial, "", sourceRevision));
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
}
