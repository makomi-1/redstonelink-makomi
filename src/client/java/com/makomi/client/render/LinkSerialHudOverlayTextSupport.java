package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorMode;
import com.makomi.data.CrossChunkNodeIdentity;
import com.makomi.data.LinkConnectionMode;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.data.LinkFilterSignalMode;
import com.makomi.data.LinkFilterSignalThresholdSource;
import com.makomi.data.LinkFilterTargetMode;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterDelay;
import com.makomi.util.CurrentLinksDisplayFormatUtil;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
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
 * 负责序号/状态/最终 IO/当前连接/频道/跨区块身份文案组装，以及命中文本缓存，不承担快照请求与实际绘制职责。
 * </p>
 */
final class LinkSerialHudOverlayTextSupport {
	private static final String KEY_NEAR_OVERLAY_SERIAL_LINE = "hud.redstonelink.near_overlay.serial_line";
	private static final String KEY_NEAR_OVERLAY_STATUS_LINE = "hud.redstonelink.near_overlay.status_line";
	private static final String KEY_NEAR_OVERLAY_FINAL_IO_LINE = "hud.redstonelink.near_overlay.final_io_line";
	private static final String KEY_NEAR_OVERLAY_LINKS_LINE = "hud.redstonelink.near_overlay.links_line";
	private static final String KEY_NEAR_OVERLAY_CHANNEL_LINE = "hud.redstonelink.near_overlay.channel_line";
	private static final String KEY_NEAR_OVERLAY_CROSSCHUNK_LINE = "hud.redstonelink.near_overlay.crosschunk_line";
	private static final String KEY_NEAR_OVERLAY_STATUS_ON = "hud.redstonelink.near_overlay.status_on";
	private static final String KEY_NEAR_OVERLAY_STATUS_OFF = "hud.redstonelink.near_overlay.status_off";
	private static final String KEY_NEAR_OVERLAY_LINKS_EMPTY = "hud.redstonelink.near_overlay.links_empty";
	private static final String KEY_NEAR_OVERLAY_CROSSCHUNK_NORMAL = "hud.redstonelink.near_overlay.crosschunk_normal";
	private static final String KEY_NEAR_OVERLAY_CROSSCHUNK_FORCE_LOAD = "hud.redstonelink.near_overlay.crosschunk_force_load";
	private static final String KEY_NEAR_OVERLAY_CROSSCHUNK_RESIDENT = "hud.redstonelink.near_overlay.crosschunk_resident";
	private static final String KEY_NEAR_OVERLAY_TYPE_CORE = "hud.redstonelink.near_overlay.type_core";
	private static final String KEY_NEAR_OVERLAY_TYPE_TRIGGER_SOURCE = "hud.redstonelink.near_overlay.type_trigger_source";
	private static final String KEY_NEAR_OVERLAY_TYPE_NODE = "hud.redstonelink.near_overlay.type_node";
	private static final String KEY_NEAR_OVERLAY_FILTER_TITLE_LINE = "hud.redstonelink.near_overlay.filter_title_line";
	private static final String KEY_NEAR_OVERLAY_FILTER_SERVICE_LINE = "hud.redstonelink.near_overlay.filter_service_line";
	private static final String KEY_NEAR_OVERLAY_FILTER_NODE_SET_LINE = "hud.redstonelink.near_overlay.filter_node_set_line";
	private static final String KEY_NEAR_OVERLAY_FILTER_MODE_LINE = "hud.redstonelink.near_overlay.filter_mode_line";
	private static final String KEY_NEAR_OVERLAY_FILTER_THRESHOLD_LINE = "hud.redstonelink.near_overlay.filter_threshold_line";
	private static final String KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_TITLE_LINE = "hud.redstonelink.near_overlay.chunk_activator_title_line";
	private static final String KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_SERVICE_LINE = "hud.redstonelink.near_overlay.chunk_activator_service_line";
	private static final String KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_MODE_LINE = "hud.redstonelink.near_overlay.chunk_activator_mode_line";
	private static final String KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_NODE_SET_LINE = "hud.redstonelink.near_overlay.chunk_activator_node_set_line";
	private static final String KEY_NEAR_OVERLAY_REPEATER_TITLE_LINE = "hud.redstonelink.near_overlay.repeater_title_line";
	private static final String KEY_NEAR_OVERLAY_REPEATER_DELAY_LINE = "hud.redstonelink.near_overlay.repeater_delay_line";
	private static final String KEY_NEAR_OVERLAY_REPEATER_INPUT_LINE = "hud.redstonelink.near_overlay.repeater_input_line";
	private static final String KEY_NEAR_OVERLAY_REPEATER_OUTPUT_LINE = "hud.redstonelink.near_overlay.repeater_output_line";
	private static final String KEY_NEAR_OVERLAY_REPEATER_CROSSCHUNK_LINE = "hud.redstonelink.near_overlay.repeater_crosschunk_line";
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
		return String.join(
			"|",
			translate(KEY_NEAR_OVERLAY_STATUS_LINE, ""),
			translate(KEY_NEAR_OVERLAY_FINAL_IO_LINE, "", ""),
			translate(KEY_NEAR_OVERLAY_CHANNEL_LINE, ""),
			translate(KEY_NEAR_OVERLAY_CROSSCHUNK_LINE, ""),
			translate(KEY_NEAR_OVERLAY_CROSSCHUNK_NORMAL),
			translate(KEY_NEAR_OVERLAY_CROSSCHUNK_FORCE_LOAD),
			translate(KEY_NEAR_OVERLAY_CROSSCHUNK_RESIDENT)
		);
	}

	/**
	 * 生成近外显文本：
	 * 1. `[物品名]序号`
	 * 2. 激活状态（ON/OFF）
	 * 3. 最终 IO
	 * 4. 当前连接（结构化表达式）
	 * 5. 频道（仅频道模式显示）
	 * 6. 跨区块身份
	 *
	 * @param pairableNodeBlockEntity 当前命中的可配对节点
	 * @param serialText 序号文本
	 * @param font 当前 HUD 字体
	 * @param dimensionKey 当前维度键
	 * @param blockPosLong 当前方块坐标压缩值
	 * @param currentLinksSnapshot 当前连接与跨区块身份快照
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
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot currentLinksSnapshot,
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
			currentLinksSnapshot,
			runtimeHudSnapshot,
			fontIdentity
		)) {
			return cached.lines();
		}

		List<String> lines = new ArrayList<>(6);
		lines.add(translate(KEY_NEAR_OVERLAY_SERIAL_LINE, resolveItemPrefix(pairableNodeBlockEntity), serialText));
		lines.add(translate(KEY_NEAR_OVERLAY_STATUS_LINE, resolveActivationStatusText(activationStatusToken)));
		lines.add(translate(
			KEY_NEAR_OVERLAY_FINAL_IO_LINE,
			resolveRuntimeHudPowerText(runtimeHudSnapshot, true),
			resolveRuntimeHudPowerText(runtimeHudSnapshot, false)
		));
		lines.add(
			translate(
				KEY_NEAR_OVERLAY_LINKS_LINE,
				buildCurrentLinksDisplayText(
					font,
					currentLinksSnapshot.linkedTargets(),
					currentLinksSnapshot.linkedTargetDisplayTexts()
				)
			)
		);
		if (shouldRenderChannelLine(currentLinksSnapshot)) {
			lines.add(translate(KEY_NEAR_OVERLAY_CHANNEL_LINE, resolveChannelValueText(currentLinksSnapshot.channel())));
		}
		lines.add(
			translate(
				KEY_NEAR_OVERLAY_CROSSCHUNK_LINE,
				resolveCrossChunkIdentityText(currentLinksSnapshot.crossChunkIdentity())
			)
		);
		List<String> immutableLines = List.copyOf(lines);
		cachedNearOverlayLines = new CachedNearOverlayLines(
			dimensionKey,
			blockPosLong,
			languageSignature,
			serialText,
			activationStatusToken,
			block,
			currentLinksSnapshot,
			runtimeHudSnapshot,
			fontIdentity,
			immutableLines
		);
		return immutableLines;
	}

	/**
	 * 生成过滤器近外显文本。
	 */
	static List<String> buildNearOverlayLines(AbstractLinkFilterBlockEntity filterBlockEntity, Font font) {
		if (filterBlockEntity == null || font == null || filterBlockEntity.filterKind() == null) {
			return List.of();
		}
		LinkFilterConfigSnapshot snapshot = filterBlockEntity.snapshot();
		String filterTitle = composeFilterTitleText(resolveItemPrefix(filterBlockEntity), filterBlockEntity.displayAlias());
		return List.of(
			translate(KEY_NEAR_OVERLAY_FILTER_TITLE_LINE, filterTitle),
			translate(KEY_NEAR_OVERLAY_STATUS_LINE, resolveFilterActivationStatusText(filterBlockEntity)),
			translate(
				KEY_NEAR_OVERLAY_FILTER_SERVICE_LINE,
				LinkNodeSemantics.toSemanticName(filterBlockEntity.filterKind().servicedNodeType())
			),
			translate(resolveFilterTargetLineTranslationKey(snapshot), resolveFilterTargetText(font, snapshot, filterBlockEntity.nodeSetDisplayTexts())),
			translate(
				KEY_NEAR_OVERLAY_FILTER_MODE_LINE,
				resolveFilterNodeSetModeText(snapshot.nodeSetMode()),
				resolveFilterSignalModeText(snapshot.signalMode())
			),
			translate(
				KEY_NEAR_OVERLAY_FILTER_THRESHOLD_LINE,
				resolveFilterThresholdSourceText(snapshot.signalThresholdSource()),
				Integer.toString(snapshot.fixedSignalThreshold()),
				Integer.toString(filterBlockEntity.sampleNeighborSignalStrength())
			)
		);
	}

	/**
	 * 生成区块激活器近外显文本。
	 */
	static List<String> buildNearOverlayLines(LinkChunkActivatorBlockEntity chunkActivatorBlockEntity, Font font) {
		if (chunkActivatorBlockEntity == null || font == null) {
			return List.of();
		}
		ChunkActivatorConfigSnapshot activeConfig = chunkActivatorBlockEntity.activeConfig();
		String titleText = composeChunkActivatorTitleText(
			LinkSerialOverlayRenderCommon.resolveChunkActivatorTitle(chunkActivatorBlockEntity.getBlockState()),
			chunkActivatorBlockEntity.displayAlias()
		);
		List<Long> orderedSerials = SerialParseUtil.parseTargetsOrdered(activeConfig.serialExpression(), 0).orderedTargets();
		return List.of(
			translate(KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_TITLE_LINE, titleText),
			translate(
				KEY_NEAR_OVERLAY_STATUS_LINE,
				resolveActivationStatusText(chunkActivatorBlockEntity.active() ? ActivationStatusToken.ON : ActivationStatusToken.OFF)
			),
			translate(
				KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_SERVICE_LINE,
				LinkNodeSemantics.toSemanticName(chunkActivatorBlockEntity.activeType())
			),
			translate(KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_MODE_LINE, resolveChunkActivatorModeText(activeConfig.mode())),
			translate(
				KEY_NEAR_OVERLAY_CHUNK_ACTIVATOR_NODE_SET_LINE,
				buildCurrentLinksText(font, orderedSerials, chunkActivatorBlockEntity.activeNodeSetDisplayTexts())
			)
		);
	}

	/**
	 * 生成转发器近外显文本。
	 */
	static List<String> buildNearOverlayLines(
		LinkRepeaterBlockEntity repeaterBlockEntity,
		Font font,
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot triggerSourceSnapshot,
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot coreSnapshot
	) {
		if (repeaterBlockEntity == null || font == null) {
			return List.of();
		}
		RepeaterConfigSnapshot snapshot = repeaterBlockEntity.snapshot();
		List<Long> inputSerials = SerialParseUtil.parseTargetsOrdered(snapshot.inputSerialExpression(), 0).orderedTargets();
		List<Long> outputSerials = SerialParseUtil.parseTargetsOrdered(snapshot.outputSerialExpression(), 0).orderedTargets();
		String titleText = composeRepeaterTitleText(
			LinkSerialOverlayRenderCommon.resolveRepeaterTitle(repeaterBlockEntity.getBlockState()),
			repeaterBlockEntity.getSerialDisplayText()
		);
		return List.of(
			translate(KEY_NEAR_OVERLAY_REPEATER_TITLE_LINE, titleText),
			translate(KEY_NEAR_OVERLAY_REPEATER_DELAY_LINE, resolveRepeaterDelayText(snapshot.delay())),
			translate(KEY_NEAR_OVERLAY_REPEATER_INPUT_LINE, buildCurrentLinksText(font, inputSerials, repeaterBlockEntity.inputDisplayTexts())),
			translate(KEY_NEAR_OVERLAY_REPEATER_OUTPUT_LINE, buildCurrentLinksText(font, outputSerials, repeaterBlockEntity.outputDisplayTexts())),
			translate(
				KEY_NEAR_OVERLAY_FINAL_IO_LINE,
				Integer.toString(repeaterBlockEntity.getCurrentInputPower()),
				Integer.toString(repeaterBlockEntity.getCurrentDispatchedOutputPower())
			),
			translate(
				KEY_NEAR_OVERLAY_REPEATER_CROSSCHUNK_LINE,
				resolveCrossChunkIdentityText(
					triggerSourceSnapshot == null ? CrossChunkNodeIdentity.NORMAL : triggerSourceSnapshot.crossChunkIdentity()
				),
				resolveCrossChunkIdentityText(coreSnapshot == null ? CrossChunkNodeIdentity.NORMAL : coreSnapshot.crossChunkIdentity())
			)
		);
	}

	/**
	 * 读取过滤器当前外显激活状态。
	 * <p>
	 * 过滤器当前用方块状态 `powered` 表达是否被激活，因此这里直接复用该布尔外显语义。
	 * </p>
	 */
	private static String resolveFilterActivationStatusText(AbstractLinkFilterBlockEntity filterBlockEntity) {
		BlockState state = filterBlockEntity == null ? null : filterBlockEntity.getBlockState();
		Boolean powered = readBooleanPropertyByName(state, "powered");
		return resolveActivationStatusText(Boolean.TRUE.equals(powered) ? ActivationStatusToken.ON : ActivationStatusToken.OFF);
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
		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		return resolveBlockDisplayName(state, fallbackNodeTypeText(nodeType));
	}

	/**
	 * 获取过滤器标题所需的方块显示名。
	 */
	private static String resolveItemPrefix(AbstractLinkFilterBlockEntity filterBlockEntity) {
		return resolveBlockDisplayName(filterBlockEntity.getBlockState(), fallbackFilterTitle(filterBlockEntity.filterKind()));
	}

	/**
	 * 组合过滤器近外显标题：保留方块标题，并在存在别名时附加别名。
	 */
	static String composeFilterTitleText(String itemPrefix, String rawDisplayAlias) {
		String normalizedPrefix = itemPrefix == null ? "" : itemPrefix;
		String normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(rawDisplayAlias);
		if (normalizedAlias.isEmpty()) {
			return normalizedPrefix;
		}
		if (normalizedPrefix.isBlank()) {
			return normalizedAlias;
		}
		return normalizedPrefix + " " + normalizedAlias;
	}

	/**
	 * 组合区块激活器近外显标题：保留方块标题，并在存在别名时附加别名。
	 */
	private static String composeChunkActivatorTitleText(String itemPrefix, String rawDisplayAlias) {
		String normalizedPrefix = itemPrefix == null ? "" : itemPrefix;
		String normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(rawDisplayAlias);
		if (normalizedAlias.isEmpty()) {
			return normalizedPrefix;
		}
		if (normalizedPrefix.isBlank()) {
			return normalizedAlias;
		}
		return normalizedPrefix + " " + normalizedAlias;
	}

	/**
	 * 组合转发器 HUD 标题：保留方块标题，并附加序号展示文本。
	 */
	private static String composeRepeaterTitleText(String itemPrefix, String serialDisplayText) {
		String normalizedPrefix = itemPrefix == null ? "" : itemPrefix;
		String normalizedSerialDisplayText = serialDisplayText == null ? "" : serialDisplayText.trim();
		if (normalizedSerialDisplayText.isEmpty()) {
			return normalizedPrefix;
		}
		if (normalizedPrefix.isBlank()) {
			return normalizedSerialDisplayText;
		}
		return normalizedPrefix + " " + normalizedSerialDisplayText;
	}

	/**
	 * 解析过滤器当前目标文本；序号模式显示结构化序号组，频道模式显示频道值。
	 */
	private static String resolveFilterTargetText(Font font, LinkFilterConfigSnapshot snapshot, List<String> nodeSetDisplayTexts) {
		LinkFilterConfigSnapshot normalized = snapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : snapshot;
		if (normalized.targetMode() == LinkFilterTargetMode.CHANNEL) {
			return resolveChannelValueText(normalized.channel());
		}
		List<Long> orderedSerials = SerialParseUtil.parseTargetsOrdered(normalized.serialExpression(), 0).orderedTargets();
		return buildCurrentLinksText(font, orderedSerials, nodeSetDisplayTexts);
	}

	/**
	 * 解析过滤器近外显目标行标签。
	 * <p>
	 * 频道模式展示的是频道值，因此应复用通用频道行文案；序号模式才展示节点集。
	 * </p>
	 */
	static String resolveFilterTargetLineTranslationKey(LinkFilterConfigSnapshot snapshot) {
		LinkFilterConfigSnapshot normalized = snapshot == null ? new LinkFilterConfigSnapshot("", null, null, 15, null) : snapshot;
		return normalized.targetMode() == LinkFilterTargetMode.CHANNEL
			? KEY_NEAR_OVERLAY_CHANNEL_LINE
			: KEY_NEAR_OVERLAY_FILTER_NODE_SET_LINE;
	}

	/**
	 * 统一解析方块/物品显示名。
	 */
	private static String resolveBlockDisplayName(BlockState state, String fallbackText) {
		if (state == null) {
			return fallbackText;
		}
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
		return fallbackText;
	}

	/**
	 * 节点标题兜底文本。
	 */
	private static String fallbackNodeTypeText(LinkNodeType nodeType) {
		if (nodeType == LinkNodeType.CORE) {
			return translate(KEY_NEAR_OVERLAY_TYPE_CORE);
		}
		if (nodeType == LinkNodeType.TRIGGER_SOURCE) {
			return translate(KEY_NEAR_OVERLAY_TYPE_TRIGGER_SOURCE);
		}
		return translate(KEY_NEAR_OVERLAY_TYPE_NODE);
	}

	/**
	 * 过滤器标题兜底文本。
	 */
	private static String fallbackFilterTitle(LinkFilterKind filterKind) {
		return translate(
			filterKind == LinkFilterKind.RECEIVE
				? "screen.redstonelink.link_filter.receive.title"
				: "screen.redstonelink.link_filter.send.title"
		);
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
	 * 解析跨区块身份对应的本地化文本。
	 */
	static String resolveCrossChunkIdentityText(CrossChunkNodeIdentity crossChunkIdentity) {
		return translate(resolveCrossChunkIdentityMessageKey(crossChunkIdentity));
	}

	/**
	 * 解析跨区块身份对应的翻译键。
	 */
	static String resolveCrossChunkIdentityMessageKey(CrossChunkNodeIdentity crossChunkIdentity) {
		CrossChunkNodeIdentity normalizedIdentity = crossChunkIdentity == null ? CrossChunkNodeIdentity.NORMAL : crossChunkIdentity;
		return switch (normalizedIdentity) {
			case FORCE_LOAD -> KEY_NEAR_OVERLAY_CROSSCHUNK_FORCE_LOAD;
			case RESIDENT -> KEY_NEAR_OVERLAY_CROSSCHUNK_RESIDENT;
			case NORMAL -> KEY_NEAR_OVERLAY_CROSSCHUNK_NORMAL;
		};
	}

	/**
	 * 当前连接快照是否需要额外渲染频道行。
	 */
	static boolean shouldRenderChannelLine(
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot currentLinksSnapshot
	) {
		return currentLinksSnapshot != null && currentLinksSnapshot.connectionMode() == LinkConnectionMode.CHANNEL;
	}

	/**
	 * 将频道值转换为近外显可显示文本。
	 */
	static String resolveChannelValueText(long channel) {
		return channel > 0L ? Long.toString(channel) : "-";
	}

	/**
	 * 构建第四行“当前连接”文本，复用 GUI 的结构化展示规则（N / A:B + / + (+n)）。
	 */
	private static String buildCurrentLinksDisplayText(
		Font font,
		List<Long> linkedTargets,
		List<String> linkedTargetDisplayTexts
	) {
		return buildCurrentLinksDisplayText(linkedTargets, linkedTargetDisplayTexts, LINKS_LINE_MAX_WIDTH, font::width);
	}

	private static String buildCurrentLinksDisplayText(
		List<Long> linkedTargets,
		List<String> linkedTargetDisplayTexts,
		int maxWidth,
		ToIntFunction<String> measure
	) {
		if (linkedTargets == null || linkedTargets.isEmpty()) {
			return translate(KEY_NEAR_OVERLAY_LINKS_EMPTY);
		}
		return CurrentLinksDisplayFormatUtil.buildText(linkedTargets, linkedTargetDisplayTexts, maxWidth, measure);
	}


	/**
	 * 构建节点集文本，优先使用服务端同步的别名展示文本，缺失时按序号展示。
	 */
	static String buildCurrentLinksText(Font font, List<Long> linkedTargets, List<String> linkedTargetDisplayTexts) {
		return buildCurrentLinksText(linkedTargets, linkedTargetDisplayTexts, LINKS_LINE_MAX_WIDTH, font::width);
	}

	/**
	 * 构建第三形态连线悬停 HUD，显示另一端对象的展示文本与方块坐标。
	 */
	static List<String> buildVisualizedHoverOverlayLines(String displayText, long serial, long blockPosLong) {
		String normalizedDisplayText = NodeAliasDisplayUtil.normalizeDisplayText(displayText, serial);
		if (normalizedDisplayText.isBlank() || "-".equals(normalizedDisplayText)) {
			return List.of();
		}
		BlockPos blockPos = BlockPos.of(blockPosLong);
		return List.of(
			"[" + normalizedDisplayText + "]",
			"(" + blockPos.getX() + ", " + blockPos.getY() + ", " + blockPos.getZ() + ")"
		);
	}

	static String buildCurrentLinksText(
		List<Long> linkedTargets,
		List<String> linkedTargetDisplayTexts,
		int maxWidth,
		ToIntFunction<String> measure
	) {
		if (linkedTargets == null || linkedTargets.isEmpty()) {
			return translate(KEY_NEAR_OVERLAY_LINKS_EMPTY);
		}
		return com.makomi.util.DisplayTextListFormatUtil.buildText(
			NodeAliasDisplayUtil.normalizeDisplayTexts(linkedTargets, linkedTargetDisplayTexts),
			maxWidth,
			measure
		);
	}

	/**
	 * 解析过滤器节点集模式文本。
	 */
	private static String resolveFilterNodeSetModeText(LinkFilterNodeSetMode nodeSetMode) {
		LinkFilterNodeSetMode normalizedMode = nodeSetMode == null ? LinkFilterNodeSetMode.DISABLED : nodeSetMode;
		return switch (normalizedMode) {
			case WHITELIST -> translate("screen.redstonelink.link_filter.node_set_mode.whitelist");
			case BLOCKLIST -> translate("screen.redstonelink.link_filter.node_set_mode.blocklist");
			case DISABLED -> translate("screen.redstonelink.link_filter.node_set_mode.disabled");
		};
	}

	/**
	 * 解析过滤器信号模式文本。
	 */
	private static String resolveFilterSignalModeText(LinkFilterSignalMode signalMode) {
		LinkFilterSignalMode normalizedSignalMode = signalMode == null ? LinkFilterSignalMode.DISABLED : signalMode;
		return switch (normalizedSignalMode) {
			case UPPER_BOUND -> translate("screen.redstonelink.link_filter.signal_mode.upper_bound");
			case LOWER_BOUND -> translate("screen.redstonelink.link_filter.signal_mode.lower_bound");
			case DISABLED -> translate("screen.redstonelink.link_filter.signal_mode.disabled");
		};
	}

	/**
	 * 解析过滤器阈值来源文本。
	 */
	private static String resolveFilterThresholdSourceText(LinkFilterSignalThresholdSource thresholdSource) {
		LinkFilterSignalThresholdSource normalizedThresholdSource = thresholdSource == null
			? LinkFilterSignalThresholdSource.FIXED_INPUT
			: thresholdSource;
		return switch (normalizedThresholdSource) {
			case FIXED_INPUT -> translate("screen.redstonelink.link_filter.threshold_source.fixed_input");
			case NEIGHBOR_MAX_INPUT -> translate("screen.redstonelink.link_filter.threshold_source.neighbor_max_input");
		};
	}

	/**
	 * 解析区块激活器激活模式文本。
	 */
	private static String resolveChunkActivatorModeText(ChunkActivatorMode mode) {
		ChunkActivatorMode normalizedMode = mode == null ? ChunkActivatorMode.FORCE_LOAD : mode;
		return switch (normalizedMode) {
			case FORCE_LOAD -> translate("screen.redstonelink.chunk_activator.mode.force_load");
			case RESIDENT -> translate("screen.redstonelink.chunk_activator.mode.resident");
		};
	}

	/**
	 * 解析转发器延迟档位文本。
	 */
	private static String resolveRepeaterDelayText(RepeaterDelay delay) {
		RepeaterDelay normalizedDelay = delay == null ? RepeaterDelay.ONE_TICK : delay;
		return switch (normalizedDelay) {
			case ONE_TICK -> translate("screen.redstonelink.repeater.delay.one_tick");
			case TWO_TICKS -> translate("screen.redstonelink.repeater.delay.two_ticks");
		};
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
	 * @param currentLinksSnapshotRef 当前连接与跨区块身份快照引用
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
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot currentLinksSnapshotRef,
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
				LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot.empty(),
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
			LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot currentCurrentLinksSnapshotRef,
			LinkSerialHudOverlaySnapshotSupport.CachedRuntimeHudSnapshot currentRuntimeHudSnapshotRef,
			int currentFontIdentity
		) {
			return blockPosLong == currentBlockPosLong
				&& fontIdentity == currentFontIdentity
				&& activationStatusToken == currentActivationStatusToken
				&& block == currentBlock
				&& currentLinksSnapshotRef == currentCurrentLinksSnapshotRef
				&& runtimeHudSnapshotRef == currentRuntimeHudSnapshotRef
				&& dimensionKey.equals(currentDimensionKey)
				&& languageSignature.equals(currentLanguageSignature)
				&& serialText.equals(currentSerialText);
		}
	}
}
