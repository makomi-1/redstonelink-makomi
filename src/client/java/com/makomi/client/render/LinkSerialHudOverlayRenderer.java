package com.makomi.client.render;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import com.makomi.util.SerialDisplayFormatUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 屏幕中心近距离序号外显渲染器。
 * <p>
 * 仅在玩家近距离准星命中可配对节点时显示，并复用远外显的颜色语义。
 * </p>
 */
public final class LinkSerialHudOverlayRenderer {
	private static final String KEY_NEAR_OVERLAY_SERIAL_LINE = "hud.redstonelink.near_overlay.serial_line";
	private static final String KEY_NEAR_OVERLAY_STATUS_LINE = "hud.redstonelink.near_overlay.status_line";
	private static final String KEY_NEAR_OVERLAY_LINKS_LINE = "hud.redstonelink.near_overlay.links_line";
	private static final String KEY_NEAR_OVERLAY_STATUS_ON = "hud.redstonelink.near_overlay.status_on";
	private static final String KEY_NEAR_OVERLAY_STATUS_OFF = "hud.redstonelink.near_overlay.status_off";
	private static final String KEY_NEAR_OVERLAY_LINKS_EMPTY = "hud.redstonelink.near_overlay.links_empty";
	private static final String KEY_NEAR_OVERLAY_TYPE_CORE = "hud.redstonelink.near_overlay.type_core";
	private static final String KEY_NEAR_OVERLAY_TYPE_TRIGGER_SOURCE = "hud.redstonelink.near_overlay.type_trigger_source";
	private static final String KEY_NEAR_OVERLAY_TYPE_NODE = "hud.redstonelink.near_overlay.type_node";
	private static final int BACKGROUND_COLOR = 0xD012141A;
	private static final int BORDER_COLOR = 0xA0363E4D;
	private static final int TEXT_PADDING_X = 6;
	private static final int TEXT_PADDING_Y = 4;
	private static final int LINE_SPACING = 2;
	private static final int LINKS_LINE_MAX_WIDTH = 280;
	/**
	 * 近外显位置缩放的分辨率基准宽度（2K）。
	 */
	private static final float POSITION_BASE_WIDTH = 2560.0F;
	/**
	 * 近外显位置缩放的分辨率基准高度（2K）。
	 */
	private static final float POSITION_BASE_HEIGHT = 1440.0F;
	/**
	 * 近外显文本相对准星中心的水平偏移像素（正数右移，负数左移）。
	 */
	private static final int CROSSHAIR_OFFSET_X = 0;
	/**
	 * 近外显文本相对准星中心的下移像素。
	 */
	private static final int CROSSHAIR_OFFSET_Y = 250;
	/**
	 * 同一节点“当前连接”请求最小间隔（毫秒），用于限流。
	 */
	private static final long CURRENT_LINKS_REQUEST_INTERVAL_MILLIS = 250L;
	/**
	 * “当前连接”快照缓存存活时长（毫秒）。
	 */
	private static final long CURRENT_LINKS_CACHE_TTL_MILLIS = 1500L;
	/**
	 * “当前连接”缓存最大条目数，超过后按最旧过期时间裁剪。
	 */
	private static final int CURRENT_LINKS_CACHE_MAX_ENTRIES = 256;
	/**
	 * 近外显文本缓存，避免每帧重复格式化连接信息。
	 */
	private static CachedNearOverlayLines cachedNearOverlayLines = CachedNearOverlayLines.empty();
	/**
	 * “当前连接”缓存（按维度+坐标+来源类型+来源序号）。
	 */
	private static final Map<OverlayTargetKey, CachedCurrentLinksSnapshot> currentLinksSnapshotCache = new HashMap<>();
	/**
	 * “当前连接”请求节流表（按维度+坐标+来源类型+来源序号）。
	 */
	private static final Map<OverlayTargetKey, Long> currentLinksRequestDeadlines = new HashMap<>();
	/**
	 * 位置缩放缓存宽度。
	 */
	private static int cachedScaleScreenWidth = -1;
	/**
	 * 位置缩放缓存高度。
	 */
	private static int cachedScaleScreenHeight = -1;
	/**
	 * 位置缩放缓存值。
	 */
	private static float cachedPositionScale = 1.0F;

	private LinkSerialHudOverlayRenderer() {
	}

	/**
	 * 接收服务端下发的“当前连接”快照并写入本地缓存。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPosLong 方块坐标压缩值
	 * @param sourceType 语义类型（triggerSource/core）
	 * @param sourceSerial 来源序号
	 * @param linkedTargets 可见目标列表（已脱敏）
	 */
	public static void updateCurrentLinksSnapshot(
		String dimensionKey,
		long blockPosLong,
		String sourceType,
		long sourceSerial,
		List<Long> linkedTargets
	) {
		Optional<LinkNodeType> parsedType = LinkNodeSemantics.tryParseCanonicalType(sourceType);
		if (parsedType.isEmpty() || sourceSerial <= 0L || dimensionKey == null || dimensionKey.isBlank()) {
			return;
		}
		OverlayTargetKey targetKey = new OverlayTargetKey(dimensionKey, blockPosLong, parsedType.get(), sourceSerial);
		long now = System.currentTimeMillis();
		currentLinksSnapshotCache.put(
			targetKey,
			new CachedCurrentLinksSnapshot(now + CURRENT_LINKS_CACHE_TTL_MILLIS, normalizeLinkedTargets(linkedTargets))
		);
		currentLinksRequestDeadlines.remove(targetKey);
		trimCurrentLinksCacheIfNeeded();
	}

	/**
	 * 在 HUD 层绘制近距离序号外显。
	 */
	public static void onHudRender(GuiGraphics guiGraphics, DeltaTracker tickCounter) {
		if (!RedstoneLinkClientDisplayConfig.isNearOverlayEnabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || minecraft.hitResult == null) {
			return;
		}
		if (minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
			return;
		}

		BlockHitResult blockHitResult = (BlockHitResult) minecraft.hitResult;
		BlockEntity blockEntity = minecraft.level.getBlockEntity(blockHitResult.getBlockPos());
		if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return;
		}
		String serialText = LinkSerialOverlayRenderCommon.resolveDisplaySerialText(pairableNodeBlockEntity);
		if (serialText.isEmpty()) {
			return;
		}
		double maxDistance = RedstoneLinkClientDisplayConfig.serialOverlayNearDistance();
		if (!LinkSerialOverlayRenderCommon.isWithinDisplayDistance(minecraft, pairableNodeBlockEntity, maxDistance)) {
			return;
		}

		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		String dimensionKey = minecraft.level.dimension().location().toString();
		long blockPosLong = blockHitResult.getBlockPos().asLong();
		List<Long> linkedTargetsSnapshot = resolveCurrentLinksSnapshotWithLazyRequest(
			pairableNodeBlockEntity,
			dimensionKey,
			blockPosLong
		);
		String languageSignature = translate(KEY_NEAR_OVERLAY_STATUS_LINE, "");
		List<String> displayLines = buildNearOverlayLines(
			pairableNodeBlockEntity,
			serialText,
			minecraft.font,
			dimensionKey,
			blockPosLong,
			linkedTargetsSnapshot,
			languageSignature
		);
		if (displayLines.isEmpty()) {
			return;
		}
		int textColor = LinkSerialOverlayRenderCommon.resolveNodeTextColor(nodeType);
		drawCenteredWithDeepBackground(
			guiGraphics,
			minecraft.font,
			displayLines,
			textColor,
			RedstoneLinkClientDisplayConfig.serialOverlayFontScale()
		);
	}

	/**
	 * 在屏幕中心绘制深色底板文本。
	 */
	private static void drawCenteredWithDeepBackground(
		GuiGraphics guiGraphics,
		Font font,
		List<String> lines,
		int textColor,
		float scale
	) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		int screenWidth = guiGraphics.guiWidth();
		int screenHeight = guiGraphics.guiHeight();
		float maxLineWidth = 0.0F;
		for (String line : lines) {
			maxLineWidth = Math.max(maxLineWidth, font.width(line));
		}
		float totalLineHeight = (font.lineHeight * lines.size()) + (LINE_SPACING * Math.max(0, lines.size() - 1));
		float scaledTextWidth = maxLineWidth * scale;
		float scaledTextHeight = totalLineHeight * scale;
		float positionScale = resolvePositionScale(screenWidth, screenHeight);
		float centerX = resolveVisibleCenterX(screenWidth, scaledTextWidth, CROSSHAIR_OFFSET_X * positionScale);
		float centerY = resolveVisibleCenterY(screenHeight, scaledTextHeight, CROSSHAIR_OFFSET_Y * positionScale);
		int left = Math.round(centerX - (scaledTextWidth / 2.0F)) - TEXT_PADDING_X;
		int top = Math.round(centerY - (scaledTextHeight / 2.0F)) - TEXT_PADDING_Y;
		int right = Math.round(centerX + (scaledTextWidth / 2.0F)) + TEXT_PADDING_X;
		int bottom = Math.round(centerY + (scaledTextHeight / 2.0F)) + TEXT_PADDING_Y;

		guiGraphics.fill(left, top, right, bottom, BACKGROUND_COLOR);
		guiGraphics.fill(left - 1, top - 1, right + 1, top, BORDER_COLOR);
		guiGraphics.fill(left - 1, bottom, right + 1, bottom + 1, BORDER_COLOR);
		guiGraphics.fill(left - 1, top, left, bottom, BORDER_COLOR);
		guiGraphics.fill(right, top, right + 1, bottom, BORDER_COLOR);

		float textX = centerX - (scaledTextWidth / 2.0F);
		float textY = centerY - (scaledTextHeight / 2.0F);
		PoseStack poseStack = guiGraphics.pose();
		poseStack.pushPose();
		poseStack.translate(textX, textY, 0.0F);
		poseStack.scale(scale, scale, 1.0F);
		float currentY = 0.0F;
		for (String line : lines) {
			float lineStartX = (maxLineWidth - font.width(line)) / 2.0F;
			guiGraphics.drawString(font, line, Math.round(lineStartX), Math.round(currentY), textColor, false);
			currentY += font.lineHeight + LINE_SPACING;
		}
		poseStack.popPose();
	}

	/**
	 * 计算可视区域内的近外显中心 X，避免窗口化时文本被偏移出屏幕。
	 */
	private static float resolveVisibleCenterX(int screenWidth, float scaledTextWidth, float offsetX) {
		float desiredCenterX = (screenWidth / 2.0F) + offsetX;
		float minCenterX = (scaledTextWidth / 2.0F) + TEXT_PADDING_X + 1.0F;
		float maxCenterX = screenWidth - (scaledTextWidth / 2.0F) - TEXT_PADDING_X - 1.0F;
		if (minCenterX > maxCenterX) {
			return screenWidth / 2.0F;
		}
		return Math.max(minCenterX, Math.min(desiredCenterX, maxCenterX));
	}

	/**
	 * 计算可视区域内的近外显中心 Y，避免窗口化时文本被偏移出屏幕。
	 */
	private static float resolveVisibleCenterY(int screenHeight, float scaledTextHeight, float offsetY) {
		float desiredCenterY = (screenHeight / 2.0F) + offsetY;
		float minCenterY = (scaledTextHeight / 2.0F) + TEXT_PADDING_Y + 1.0F;
		float maxCenterY = screenHeight - (scaledTextHeight / 2.0F) - TEXT_PADDING_Y - 1.0F;
		if (minCenterY > maxCenterY) {
			return screenHeight / 2.0F;
		}
		return Math.max(minCenterY, Math.min(desiredCenterY, maxCenterY));
	}

	/**
	 * 计算近外显“位置偏移”的等比缩放系数，基准为 2560x1440。
	 */
	private static float resolvePositionScale(int screenWidth, int screenHeight) {
		if (screenWidth <= 0 || screenHeight <= 0) {
			return 1.0F;
		}
		if (screenWidth == cachedScaleScreenWidth && screenHeight == cachedScaleScreenHeight) {
			return cachedPositionScale;
		}
		float scaleByWidth = screenWidth / POSITION_BASE_WIDTH;
		float scaleByHeight = screenHeight / POSITION_BASE_HEIGHT;
		cachedPositionScale = Math.min(scaleByWidth, scaleByHeight);
		cachedScaleScreenWidth = screenWidth;
		cachedScaleScreenHeight = screenHeight;
		return cachedPositionScale;
	}

	/**
	 * 懒加载获取“当前连接”快照：优先读缓存，过期后按节流规则发起网络请求。
	 */
	private static List<Long> resolveCurrentLinksSnapshotWithLazyRequest(
		PairableNodeBlockEntity pairableNodeBlockEntity,
		String dimensionKey,
		long blockPosLong
	) {
		if (pairableNodeBlockEntity == null || dimensionKey == null || dimensionKey.isBlank()) {
			return List.of();
		}
		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		long sourceSerial = pairableNodeBlockEntity.getSerial();
		if (nodeType == null || sourceSerial <= 0L) {
			return List.of();
		}

		long now = System.currentTimeMillis();
		cleanupExpiredCurrentLinksCache(now);
		OverlayTargetKey targetKey = new OverlayTargetKey(dimensionKey, blockPosLong, nodeType, sourceSerial);
		CachedCurrentLinksSnapshot cachedSnapshot = currentLinksSnapshotCache.get(targetKey);
		if (cachedSnapshot != null && cachedSnapshot.expireAtMillis() >= now) {
			return cachedSnapshot.linkedTargets();
		}

		requestCurrentLinksSnapshotIfAllowed(targetKey, now);
		return cachedSnapshot == null ? List.of() : cachedSnapshot.linkedTargets();
	}

	/**
	 * 按节流规则请求服务端下发“当前连接”快照。
	 */
	private static void requestCurrentLinksSnapshotIfAllowed(OverlayTargetKey targetKey, long now) {
		Long nextAllowedMillis = currentLinksRequestDeadlines.get(targetKey);
		if (nextAllowedMillis != null && nextAllowedMillis > now) {
			return;
		}
		currentLinksRequestDeadlines.put(targetKey, now + CURRENT_LINKS_REQUEST_INTERVAL_MILLIS);
		ClientPlayNetworking.send(
			new PairingNetwork.RequestCurrentLinksPayload(
				targetKey.dimensionKey(),
				targetKey.blockPosLong(),
				LinkNodeSemantics.toSemanticName(targetKey.nodeType()),
				targetKey.sourceSerial()
			)
		);
	}

	/**
	 * 清理过期缓存与过期请求节流记录。
	 */
	private static void cleanupExpiredCurrentLinksCache(long now) {
		currentLinksSnapshotCache.entrySet().removeIf(entry -> entry.getValue().expireAtMillis() < now);
		currentLinksRequestDeadlines.entrySet().removeIf(entry -> entry.getValue() < now);
	}

	/**
	 * 当缓存条目过多时按最旧过期时间裁剪，避免无限增长。
	 */
	private static void trimCurrentLinksCacheIfNeeded() {
		if (currentLinksSnapshotCache.size() <= CURRENT_LINKS_CACHE_MAX_ENTRIES) {
			return;
		}
		OverlayTargetKey oldestKey = null;
		long oldestExpireAt = Long.MAX_VALUE;
		for (Map.Entry<OverlayTargetKey, CachedCurrentLinksSnapshot> entry : currentLinksSnapshotCache.entrySet()) {
			long expireAtMillis = entry.getValue().expireAtMillis();
			if (expireAtMillis < oldestExpireAt) {
				oldestExpireAt = expireAtMillis;
				oldestKey = entry.getKey();
			}
		}
		if (oldestKey != null) {
			currentLinksSnapshotCache.remove(oldestKey);
			currentLinksRequestDeadlines.remove(oldestKey);
		}
	}

	/**
	 * 规范化网络下发目标序号：过滤非法值、升序去重。
	 */
	private static List<Long> normalizeLinkedTargets(List<Long> linkedTargets) {
		if (linkedTargets == null || linkedTargets.isEmpty()) {
			return List.of();
		}
		List<Long> values = new ArrayList<>(linkedTargets.size());
		for (Long value : linkedTargets) {
			if (value != null && value > 0L) {
				values.add(value);
			}
		}
		if (values.isEmpty()) {
			return List.of();
		}
		values.sort(Long::compareTo);
		List<Long> deduplicated = new ArrayList<>(values.size());
		long previous = Long.MIN_VALUE;
		for (long value : values) {
			if (value == previous) {
				continue;
			}
			deduplicated.add(value);
			previous = value;
		}
		return deduplicated.isEmpty() ? List.of() : List.copyOf(deduplicated);
	}

	/**
	 * 生成近外显三行文本：
	 * 1. [物品名]序号
	 * 2. 激活状态（ON/OFF）
	 * 3. 当前连接（结构化表达式）
	 */
	private static List<String> buildNearOverlayLines(
		PairableNodeBlockEntity pairableNodeBlockEntity,
		String serialText,
		Font font,
		String dimensionKey,
		long blockPosLong,
		List<Long> linkedTargetsSnapshot,
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
			fontIdentity
		)) {
			return cached.lines();
		}

		List<String> lines = new ArrayList<>(3);
		lines.add(translate(KEY_NEAR_OVERLAY_SERIAL_LINE, resolveItemPrefix(pairableNodeBlockEntity), serialText));
		lines.add(translate(KEY_NEAR_OVERLAY_STATUS_LINE, resolveActivationStatusText(activationStatusToken)));
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
			fontIdentity,
			immutableLines
		);
		return immutableLines;
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
	 * 构建第三行“当前连接”文本，复用 GUI 的结构化展示规则（N / A:B + / + (+n)）。
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
	 * “当前连接”缓存键（维度 + 坐标 + 来源类型 + 来源序号）。
	 */
	private record OverlayTargetKey(
		String dimensionKey,
		long blockPosLong,
		LinkNodeType nodeType,
		long sourceSerial
	) {
	}

	/**
	 * “当前连接”缓存值。
	 *
	 * @param expireAtMillis 过期时间戳
	 * @param linkedTargets 可见连接快照
	 */
	private record CachedCurrentLinksSnapshot(long expireAtMillis, List<Long> linkedTargets) {
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
	 * @param fontIdentity 字体对象标识
	 * @param lines 三行显示文本
	 */
	private record CachedNearOverlayLines(
		String dimensionKey,
		long blockPosLong,
		String languageSignature,
		String serialText,
		ActivationStatusToken activationStatusToken,
		Block block,
		List<Long> linkedTargetsRef,
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
			int currentFontIdentity
		) {
			return blockPosLong == currentBlockPosLong
				&& fontIdentity == currentFontIdentity
				&& activationStatusToken == currentActivationStatusToken
				&& block == currentBlock
				&& linkedTargetsRef == currentLinkedTargetsRef
				&& dimensionKey.equals(currentDimensionKey)
				&& languageSignature.equals(currentLanguageSignature)
				&& serialText.equals(currentSerialText);
		}
	}
}
