package com.makomi.client.screen;

import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 触发源（triggerSource）配对界面。
 * <p>
 * 负责显示当前触发源序列号与已连接目标，并向服务端发送覆盖式 {@code set_links} 命令。
 * </p>
 */
public class TriggerSourcePairingScreen extends AbstractMultiPairingScreen {
	private static final LinkNodeType SOURCE_TYPE = LinkNodeType.BUTTON;
	private static final Component TITLE = Component.translatable("screen.redstonelink.button_pairing.title");
	private static final Component INPUT_LABEL = Component.translatable("screen.redstonelink.button_pairing.input");
	private static final Component INVALID_INPUT = Component.translatable("screen.redstonelink.button_pairing.invalid");

	/**
	 * 基于明确的来源序列号与当前连接初始化界面。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param currentTargets 当前已连接目标序列号列表
	 */
	public TriggerSourcePairingScreen(long sourceSerial, List<Long> currentTargets) {
		super(TITLE, sourceSerial, currentTargets);
	}

	/**
	 * 基于玩家手持物品初始化界面。
	 *
	 * @param hand 手持槽位（主手/副手）
	 */
	public TriggerSourcePairingScreen(InteractionHand hand) {
		this(resolveHeldSerial(hand), List.of());
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
			"screen.redstonelink.button_pairing.serial",
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
		return Component.translatable("screen.redstonelink.button_pairing.current_links", linkedText);
	}

	/**
	 * 发送覆盖式 {@code set_links} 命令。
	 *
	 * @param sourceSerial 来源节点序列号
	 * @param rawTargetsInput 输入框中的原始目标文本
	 * @param targetCount 去重后的目标数量
	 */
	@Override
	protected void sendSetLinksCommand(long sourceSerial, String rawTargetsInput, int targetCount) {
		if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
			return;
		}
		String base = setLinksBaseCommand(sourceSerial);
		String normalizedTargets = rawTargetsInput == null ? "" : rawTargetsInput.trim();
		if (normalizedTargets.isEmpty()) {
			minecraft.player.connection.sendCommand(base);
			return;
		}

		String command = base + " " + normalizedTargets;
		if (targetCount > 1) {
			command += " confirm";
		}
		minecraft.player.connection.sendCommand(command);
	}

	/**
	 * 发送清空连接命令。
	 *
	 * @param sourceSerial 来源节点序列号
	 */
	@Override
	protected void sendClearLinksCommand(long sourceSerial) {
		if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
			return;
		}
		minecraft.player.connection.sendCommand(setLinksBaseCommand(sourceSerial));
	}

	/**
	 * 构建 set_links 命令前缀。
	 *
	 * @param sourceSerial 来源序列号
	 * @return 命令前缀文本
	 */
	private static String setLinksBaseCommand(long sourceSerial) {
		return "redstonelink set_links " + LinkNodeSemantics.toCommandToken(SOURCE_TYPE) + " " + sourceSerial;
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
}
