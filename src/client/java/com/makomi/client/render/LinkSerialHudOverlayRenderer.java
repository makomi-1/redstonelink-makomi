package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.CrossChunkNodeIdentity;
import com.makomi.data.LinkNodeType;
import com.makomi.data.SmartGlassesAccessSupport;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 屏幕中心近距离序号外显渲染器。
 * <p>
 * 主类只保留命中判定、快照读取、文案组装和实际绘制的顶层编排；具体职责已下沉到 helper。
 * </p>
 */
public final class LinkSerialHudOverlayRenderer {
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
	 * @param connectionModeToken 命中节点当前连接模式
	 * @param channel 命中节点当前频道号
	 * @param crossChunkIdentity 命中节点的跨区块身份
	 */
	public static void updateCurrentLinksSnapshot(
		String dimensionKey,
		long blockPosLong,
		String sourceType,
		long sourceSerial,
		List<Long> linkedTargets,
		List<String> linkedTargetDisplayTexts,
		String connectionModeToken,
		long channel,
		CrossChunkNodeIdentity crossChunkIdentity
	) {
		LinkSerialHudOverlaySnapshotSupport.updateCurrentLinksSnapshot(
			dimensionKey,
			blockPosLong,
			sourceType,
			sourceSerial,
			linkedTargets,
			linkedTargetDisplayTexts,
			connectionModeToken,
			channel,
			crossChunkIdentity
		);
	}

	/**
	 * 接收服务端下发的“最终 IO”快照并写入本地缓存。
	 *
	 * @param dimensionKey 维度键
	 * @param blockPosLong 方块坐标压缩值
	 * @param sourceType 语义类型（triggerSource/core）
	 * @param sourceSerial 来源序号
	 * @param available 当前是否存在可读运行态
	 * @param inputPower 最终输入强度
	 * @param outputPower 最终输出强度
	 */
	public static void updateRuntimeHudSnapshot(
		String dimensionKey,
		long blockPosLong,
		String sourceType,
		long sourceSerial,
		boolean available,
		int inputPower,
		int outputPower
	) {
		LinkSerialHudOverlaySnapshotSupport.updateRuntimeHudSnapshot(
			dimensionKey,
			blockPosLong,
			sourceType,
			sourceSerial,
			available,
			inputPower,
			outputPower
		);
	}

	/**
	 * 在 HUD 层绘制近距离序号外显。
	 *
	 * @param guiGraphics HUD 绘图上下文
	 * @param tickCounter HUD 渲染 tick 计数器；当前仅保持与 Fabric 回调签名一致
	 */
	public static void onHudRender(GuiGraphics guiGraphics, DeltaTracker tickCounter) {
		if (!RedstoneLinkClientDisplayConfig.overlay().nearOverlayEnabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		if (!SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)) {
			return;
		}
		if (minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.BLOCK) {
			BlockHitResult blockHitResult = (BlockHitResult) minecraft.hitResult;
			BlockEntity blockEntity = minecraft.level.getBlockEntity(blockHitResult.getBlockPos());
			double maxDistance = RedstoneLinkClientDisplayConfig.overlay().nearDistance();
			if (LinkSerialOverlayRenderCommon.isWithinDisplayDistance(minecraft, blockEntity, maxDistance)) {
				if (blockEntity instanceof LinkRepeaterBlockEntity repeaterBlockEntity) {
					if (renderRepeaterOverlay(guiGraphics, minecraft, repeaterBlockEntity)) {
						return;
					}
				} else if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
					if (renderNodeOverlay(guiGraphics, minecraft, blockHitResult, pairableNodeBlockEntity)) {
						return;
					}
				} else if (blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity) {
					if (renderFilterOverlay(guiGraphics, minecraft, filterBlockEntity)) {
						return;
					}
				} else if (blockEntity instanceof LinkChunkActivatorBlockEntity chunkActivatorBlockEntity) {
					if (renderChunkActivatorOverlay(guiGraphics, minecraft, chunkActivatorBlockEntity)) {
						return;
					}
				}
			}
		}
		renderVisualizedHoverOverlay(guiGraphics, minecraft);
	}

	/**
	 * 绘制转发器近外显。
	 */
	private static boolean renderRepeaterOverlay(
		GuiGraphics guiGraphics,
		Minecraft minecraft,
		LinkRepeaterBlockEntity repeaterBlockEntity
	) {
		String dimensionKey = minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
		long blockPosLong = repeaterBlockEntity.getBlockPos().asLong();
		long serial = repeaterBlockEntity.getSerial();
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot triggerSourceSnapshot = LinkSerialHudOverlaySnapshotSupport.resolveCurrentLinksSnapshotWithLazyRequest(
			dimensionKey,
			blockPosLong,
			LinkNodeType.TRIGGER_SOURCE,
			serial
		);
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot coreSnapshot = LinkSerialHudOverlaySnapshotSupport.resolveCurrentLinksSnapshotWithLazyRequest(
			dimensionKey,
			blockPosLong,
			LinkNodeType.CORE,
			serial
		);
		List<String> displayLines = LinkSerialHudOverlayTextSupport.buildNearOverlayLines(
			repeaterBlockEntity,
			minecraft.font,
			triggerSourceSnapshot,
			coreSnapshot
		);
		if (displayLines.isEmpty()) {
			return false;
		}
		LinkSerialHudOverlayDrawSupport.drawCenteredWithPanel(
			guiGraphics,
			minecraft.font,
			displayLines,
			LinkSerialOverlayRenderCommon.resolveRepeaterTextColor(),
			RedstoneLinkClientDisplayConfig.overlay().fontScale(),
			LinkSerialHudOverlayDrawSupport.PanelStyle.REPEATER
		);
		return true;
	}

	/**
	 * 绘制节点近外显。
	 */
	private static boolean renderNodeOverlay(
		GuiGraphics guiGraphics,
		Minecraft minecraft,
		BlockHitResult blockHitResult,
		PairableNodeBlockEntity pairableNodeBlockEntity
	) {
		String serialText = LinkSerialOverlayRenderCommon.resolveDisplaySerialText(pairableNodeBlockEntity);
		if (serialText.isEmpty()) {
			return false;
		}

		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		String dimensionKey = minecraft.level.dimension().location().toString();
		long blockPosLong = blockHitResult.getBlockPos().asLong();
		LinkSerialHudOverlaySnapshotSupport.CachedCurrentLinksSnapshot currentLinksSnapshot =
			LinkSerialHudOverlaySnapshotSupport.resolveCurrentLinksSnapshotWithLazyRequest(
			pairableNodeBlockEntity,
			dimensionKey,
			blockPosLong
		);
		LinkSerialHudOverlaySnapshotSupport.CachedRuntimeHudSnapshot runtimeHudSnapshot = LinkSerialHudOverlaySnapshotSupport.resolveRuntimeHudSnapshotWithLazyRequest(
			pairableNodeBlockEntity,
			dimensionKey,
			blockPosLong
		);
		List<String> displayLines = LinkSerialHudOverlayTextSupport.buildNearOverlayLines(
			pairableNodeBlockEntity,
			serialText,
			minecraft.font,
			dimensionKey,
			blockPosLong,
			currentLinksSnapshot,
			runtimeHudSnapshot,
			LinkSerialHudOverlayTextSupport.resolveLanguageSignature()
		);
		if (displayLines.isEmpty()) {
			return false;
		}
		int textColor = LinkSerialOverlayRenderCommon.resolveNodeTextColor(nodeType);
		LinkSerialHudOverlayDrawSupport.drawCenteredWithPanel(
			guiGraphics,
			minecraft.font,
			displayLines,
			textColor,
			RedstoneLinkClientDisplayConfig.overlay().fontScale(),
			LinkSerialHudOverlayDrawSupport.resolveNodePanelStyle(nodeType)
		);
		return true;
	}

	/**
	 * 绘制过滤器近外显。
	 */
	private static boolean renderFilterOverlay(
		GuiGraphics guiGraphics,
		Minecraft minecraft,
		AbstractLinkFilterBlockEntity filterBlockEntity
	) {
		if (filterBlockEntity.filterKind() == null) {
			return false;
		}
		List<String> displayLines = LinkSerialHudOverlayTextSupport.buildNearOverlayLines(
			filterBlockEntity,
			minecraft.font
		);
		if (displayLines.isEmpty()) {
			return false;
		}
		LinkSerialHudOverlayDrawSupport.drawCenteredWithPanel(
			guiGraphics,
			minecraft.font,
			displayLines,
			LinkSerialOverlayRenderCommon.resolveFilterTextColor(filterBlockEntity.filterKind()),
			RedstoneLinkClientDisplayConfig.overlay().fontScale(),
			LinkSerialHudOverlayDrawSupport.resolveFilterPanelStyle()
		);
		return true;
	}

	/**
	 * 绘制区块激活器近外显。
	 */
	private static boolean renderChunkActivatorOverlay(
		GuiGraphics guiGraphics,
		Minecraft minecraft,
		LinkChunkActivatorBlockEntity chunkActivatorBlockEntity
	) {
		List<String> displayLines = LinkSerialHudOverlayTextSupport.buildNearOverlayLines(
			chunkActivatorBlockEntity,
			minecraft.font
		);
		if (displayLines.isEmpty()) {
			return false;
		}
		LinkSerialHudOverlayDrawSupport.drawCenteredWithPanel(
			guiGraphics,
			minecraft.font,
			displayLines,
			LinkSerialOverlayRenderCommon.resolveChunkActivatorTextColor(),
			RedstoneLinkClientDisplayConfig.overlay().fontScale(),
			LinkSerialHudOverlayDrawSupport.resolveChunkActivatorPanelStyle()
		);
		return true;
	}

	/**
	 * 在未命中有效方块近外显时，回退到第三形态连线命中 HUD。
	 */
	private static void renderVisualizedHoverOverlay(GuiGraphics guiGraphics, Minecraft minecraft) {
		QuickLinkOutlineRenderer.HoveredVisualizedTarget hoveredTarget = QuickLinkOutlineRenderer.resolveHoveredVisualizedTarget(
			minecraft
		);
		if (hoveredTarget == null) {
			return;
		}
		List<String> displayLines = LinkSerialHudOverlayTextSupport.buildVisualizedHoverOverlayLines(
			hoveredTarget.displayText(),
			hoveredTarget.objectSerial(),
			hoveredTarget.blockPosLong()
		);
		if (displayLines.isEmpty()) {
			return;
		}
		LinkSerialHudOverlayDrawSupport.drawCenteredWithPanel(
			guiGraphics,
			minecraft.font,
			displayLines,
			resolveVisualizedHoverTextColor(hoveredTarget),
			RedstoneLinkClientDisplayConfig.overlay().fontScale(),
			resolveVisualizedHoverPanelStyle(hoveredTarget)
		);
	}

	/**
	 * 为第三形态悬停 HUD 选择与目标对象一致的文本颜色。
	 */
	private static int resolveVisualizedHoverTextColor(QuickLinkOutlineRenderer.HoveredVisualizedTarget hoveredTarget) {
		if (hoveredTarget == null) {
			return LinkSerialOverlayRenderCommon.resolveNodeTextColor(null);
		}
		return hoveredTarget.isRepeater()
			? LinkSerialOverlayRenderCommon.resolveRepeaterTextColor()
			: LinkSerialOverlayRenderCommon.resolveNodeTextColor(hoveredTarget.resolveNodeType());
	}

	/**
	 * 为第三形态悬停 HUD 选择与目标对象一致的面板主题。
	 */
	private static LinkSerialHudOverlayDrawSupport.PanelStyle resolveVisualizedHoverPanelStyle(
		QuickLinkOutlineRenderer.HoveredVisualizedTarget hoveredTarget
	) {
		if (hoveredTarget == null) {
			return LinkSerialHudOverlayDrawSupport.PanelStyle.DEFAULT;
		}
		return hoveredTarget.isRepeater()
			? LinkSerialHudOverlayDrawSupport.PanelStyle.REPEATER
			: LinkSerialHudOverlayDrawSupport.resolveNodePanelStyle(hoveredTarget.resolveNodeType());
	}
}
