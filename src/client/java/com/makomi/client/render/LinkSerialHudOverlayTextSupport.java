package com.makomi.client.render;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeType;
import com.makomi.util.SerialDisplayFormatUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 近外显文案格式化支持。
 * <p>
 * 负责序号/状态/最终 IO/当前连接四行文案组装，以及命中文本缓存，不承担快照请求与实际绘制职责。
 * </p>
 */
final class LinkSerialHudOverlayTextSupport {
	private static final String KEY_NEAR_OVERLAY_SERIAL_LINE = "hud.redstonelink.near_overlay.serial_line";
	private static final String KEY_NEAR_OVERLAY_STATUS_LINE = "hud.redstonelink.near_overlay.status_line";
	private static final String KEY_NEAR_OVERLAY_FINAL_IO_LINE = "hud.redstonelink.near_overlay.final_io_line";
	private static final String KEY_NEAR_OVERLAY_LINKS_LINE = "hud.redstonelink.near_overlay.links_line";
	private static final String KEY_NEAR_OVERLAY_STATUS_ON = "hud.redstonelink.near_overlay.status_on";
	private static final String KEY_NEAR_OVERLAY_STATUS_OFF = "hud.redstonelink.near_overlay.status_off";
	private static final String KEY_NEAR_OVERLAY_LINKS_EMPTY = "hud.redstonelink.near_overlay.links_empty";
	private static final String KEY_NEAR_OVERLAY_TYPE_CORE = "hud.redstonelink.near_overlay.type_core";
	private static final String KEY_NEAR_OVERLAY_TYPE_TRIGGER_SOURCE = "hud.redstonelink.near_overlay.type_trigger_source";
	private static final String KEY_NEAR_OVERLAY_TYPE_NODE = "hud.redstonelink.near_overlay.type_node";
	private static final int LINKS_LINE_MAX_WIDTH = 280;
	/**
	 * 近外显文本缓存，避免每帧重复格式化连接信息。
	 */
	private static CachedNearOverlayLines cachedNearOverlayLines = CachedNearOverlayLines.empty();

	private LinkSerialHudOverlayTextSupport() {
	}

	/**
	 * 生成与当前语言环境绑定的签名，用于识别本地化文本缓存是否失效。
	 *
	 * @return 由关键翻译项拼出的语言签名
	 */
	static String resolveLanguageSignature() {
		return translate(KEY_NEAR_OVERLAY_STATUS_LINE, "") + "|" + translate(KEY_NEAR_OVERLAY_FINAL_IO_LINE, "", "");
	}

	/**
	 * 生成近外显文本：
	 * 1. `[物品名]序号`
	 * 2. 激活状态（ON/OFF）
	 * 3. 最终 IO
	 * 4. 当前连接（结构化表达式）
	 *
	 * @param pairableNodeBlockEntity 当前命中的可配对节点
	 * @param serialText 序号文本
	 * @param font 当前 HUD 字体
	 * @param dimensionKey 当前维度键
	 * @param blockPosLong 当前方块坐标压缩值
	 * @param linkedTargetsSnapshot 当前连接快照
	 * @param runtimeHudSnapshot 最终 IO 快照
	 * @param languageSignature 当前语言签名
	 * @return 可直接绘制的多行文本
	 */
	static List<String> buildNearOverlayLines(
		PairableNodeBlockEntity pairableNodeBlockEntity,
		String serialText,
		Font font,
		String dimensionKey,
		long blockPosLong,
		List<Long> linkedTargetsSnapshot,
		LinkSerialHudOverlaySnapshotSupport.CachedRuntimeHudSnapshot runtimeHudSnapshot,
		String languageSignature
	) {
		ActivationStatusToken activationStatusToken = resolveActivationStatusToken(pairableNodeBlockEntity);
		Block block = pairableNodeBlockEntity.getBlockState().getBlock();
		int fontIdentity = System.identityHashCode(font);
		CachedNearOverlayLines cached = cachedNearOverlayLines;
		if (cached.matches(
			dimensionKey,
			blockPosLong,
			languageSignature,
			serialText,
			activationStatusToken,
			block,
			linkedTargetsSnapshot,
			runtimeHudSnapshot,
			fontIdentity
		)) {
			return cached.lines();
		}

		List<String> lines = new ArrayList<>(4);
		lines.add(translate(KEY_NEAR_OVERLAY_SERIAL_LINE, resolveItemPrefix(pairableNodeBlockEntity), serialText));
		lines.add(translate(KEY_NEAR_OVERLAY_STATUS_LINE, resolveActivationStatusText(activationStatusToken)));
		lines.add(translate(
			KEY_NEAR_OVERLAY_FINAL_IO_LINE,
			resolveRuntimeHudPowerText(runtimeHudSnapshot, true),
			resolveRuntimeHudPowerText(runtimeHudSnapshot, false)
		));
		lines.add(translate(KEY_NEAR_OVERLAY_LINKS_LINE, buildCurrentLinksText(font, linkedTargetsSnapshot)));
		List<String> immutableLines = List.copyOf(lines);
		cachedNearOverlayLines = new CachedNearOverlayLines(
			dimensionKey,
			blockPosLong,
			languageSignature,
			serialText,
			activationStatusToken,
			block,
			linkedTargetsSnapshot,
			runtimeHudSnapshot,
			fontIdentity,
			immutableLines
		);
		return immutableLines;
	}

	/**
	 * 将最终 IO 快照转换为 HUD 文本；无快照时统一显示 `-`。
	 */
	private static String resolveRuntimeHudPowerText(
		LinkSerialHudOverlaySnapshotSupport.CachedRuntimeHudSnapshot runtimeHudSnapshot,
		boolean inputSide
	) {
		if (runtimeHudSnapshot == null || !runtimeHudSnapshot.available()) {
			return translate(KEY_NEAR_OVERLAY_LINKS_EMPTY);
		}
		return Integer.toString(inputSide ? runtimeHudSnapshot.inputPower() : runtimeHudSnapshot.outputPower());
	}

	/**
	 * 获取第一行前缀所需的物品名，优先使用方块对应物品名。
	 */
	private static String resolveItemPrefix(PairableNodeBlockEntity pairableNodeBlockEntity) {
		BlockState state = pairableNodeBlockEntity.getBlockState();
		Block block = state.getBlock();
		Item blockItem = block.asItem();
		if (blockItem != Items.AIR) {
			String itemName = blockItem.getDescription().getString();
			if (!itemName.isBlank()) {
				return itemName;
			}
		}
		String blockName = block.getName().getString();
		if (!blockName.isBlank()) {
			return blockName;
		}
		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		if (nodeType == LinkNodeType.CORE) {
			return translate(KEY_NEAR_OVERLAY_TYPE_CORE);
		}
		if (nodeType == LinkNodeType.TRIGGER_SOURCE) {
			return translate(KEY_NEAR_OVERLAY_TYPE_TRIGGER_SOURCE);
		}
		return translate(KEY_NEAR_OVERLAY_TYPE_NODE);
	}

	/**
	 * 解析第二行激活状态令牌（ON/OFF）。
	 */
	private static ActivationStatusToken resolveActivationStatusToken(PairableNodeBlockEntity pairableNodeBlockEntity) {
		if (pairableNodeBlockEntity instanceof ActivatableTargetBlockEntity activatableTargetBlockEntity) {
			return activatableTargetBlockEntity.isActive() ? ActivationStatusToken.ON : ActivationStatusToken.OFF;
		}

		BlockState state = pairableNodeBlockEntity.getBlockState();
		Boolean active = readBooleanPropertyByName(state, "active");
		if (active != null) {
			return active ? ActivationStatusToken.ON : ActivationStatusToken.OFF;
		}
		Boolean powered = readBooleanPropertyByName(state, "powered");
		if (powered != null) {
			return powered ? ActivationStatusToken.ON : ActivationStatusToken.OFF;
		}
		return ActivationStatusToken.OFF;
	}

	/**
	 * 将激活状态令牌转为本地化文本。
	 */
	private static String resolveActivationStatusText(ActivationStatusToken activationStatusToken) {
		return activationStatusToken == ActivationStatusToken.ON
			? translate(KEY_NEAR_OVERLAY_STATUS_ON)
			: translate(KEY_NEAR_OVERLAY_STATUS_OFF);
	}

	/**
	 * 构建第四行“当前连接”文本，复用 GUI 的结构化展示规则（N / A:B + / + (+n)）。
	 */
	private static String buildCurrentLinksText(Font font, List<Long> linkedTargets) {
		if (linkedTargets == null || linkedTargets.isEmpty()) {
			return translate(KEY_NEAR_OVERLAY_LINKS_EMPTY);
		}
		SerialDisplayFormatUtil.StructuredExpression expression = SerialDisplayFormatUtil.buildExpression(linkedTargets);
		if (expression.isEmpty()) {
			return translate(KEY_NEAR_OVERLAY_LINKS_EMPTY);
		}

		int displaySegments = expression.segments().size();
		while (displaySegments > 0) {
			String base = String.join("/", expression.segments().subList(0, displaySegments));
			int remaining = SerialDisplayFormatUtil.countRemainingSerials(expression, displaySegments);
			String text = remaining > 0 ? base + SerialDisplayFormatUtil.buildRemainingSuffix(remaining) : base;
			if (font.width(text) <= LINKS_LINE_MAX_WIDTH) {
				return text;
			}
			displaySegments -= 1;
		}

		int remainingAll = SerialDisplayFormatUtil.countRemainingSerials(expression, 0);
		String suffixOnly = SerialDisplayFormatUtil.buildRemainingSuffix(remainingAll);
		return font.width(suffixOnly) <= LINKS_LINE_MAX_WIDTH ? suffixOnly : translate(KEY_NEAR_OVERLAY_LINKS_EMPTY);
	}

	/**
	 * 从方块状态中按属性名读取布尔值。
	 */
	private static Boolean readBooleanPropertyByName(BlockState state, String propertyName) {
		if (state == null || propertyName == null || propertyName.isBlank()) {
			return null;
		}
		for (Property<?> property : state.getProperties()) {
			if (!propertyName.equals(property.getName())) {
				continue;
			}
			if (property instanceof BooleanProperty booleanProperty) {
				return state.getValue(booleanProperty);
			}
		}
		return null;
	}

	/**
	 * 读取客户端当前语言对应的文本。
	 */
	private static String translate(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	/**
	 * 近外显激活状态令牌。
	 */
	private enum ActivationStatusToken {
		ON,
		OFF
	}

	/**
	 * 近外显文本缓存条目。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPosLong 方块坐标压缩值
	 * @param languageSignature 语言签名
	 * @param serialText 序号文本
	 * @param activationStatusToken 激活状态令牌
	 * @param block 命中方块
	 * @param linkedTargetsRef 连接快照引用
	 * @param runtimeHudSnapshotRef 最终 IO 快照引用
	 * @param fontIdentity 字体对象标识
	 * @param lines 显示文本
	 */
	private record CachedNearOverlayLines(
		String dimensionKey,
		long blockPosLong,
		String languageSignature,
		String serialText,
		ActivationStatusToken activationStatusToken,
		Block block,
		List<Long> linkedTargetsRef,
		LinkSerialHudOverlaySnapshotSupport.CachedRuntimeHudSnapshot runtimeHudSnapshotRef,
		int fontIdentity,
		List<String> lines
	) {
		private static CachedNearOverlayLines empty() {
			return new CachedNearOverlayLines(
				"",
				Long.MIN_VALUE,
				"",
				"",
				ActivationStatusToken.OFF,
				null,
				null,
				LinkSerialHudOverlaySnapshotSupport.CachedRuntimeHudSnapshot.empty(),
				0,
				List.of()
			);
		}

		private boolean matches(
			String currentDimensionKey,
			long currentBlockPosLong,
			String currentLanguageSignature,
			String currentSerialText,
			ActivationStatusToken currentActivationStatusToken,
			Block currentBlock,
			List<Long> currentLinkedTargetsRef,
			LinkSerialHudOverlaySnapshotSupport.CachedRuntimeHudSnapshot currentRuntimeHudSnapshotRef,
			int currentFontIdentity
		) {
			return blockPosLong == currentBlockPosLong
				&& fontIdentity == currentFontIdentity
				&& activationStatusToken == currentActivationStatusToken
				&& block == currentBlock
				&& linkedTargetsRef == currentLinkedTargetsRef
				&& runtimeHudSnapshotRef == currentRuntimeHudSnapshotRef
				&& dimensionKey.equals(currentDimensionKey)
				&& languageSignature.equals(currentLanguageSignature)
				&& serialText.equals(currentSerialText);
		}
	}
}
