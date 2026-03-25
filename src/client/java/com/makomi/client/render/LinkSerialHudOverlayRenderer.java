package com.makomi.client.render;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkNodeType;
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
	 */
	public static void updateCurrentLinksSnapshot(
		String dimensionKey,
		long blockPosLong,
		String sourceType,
		long sourceSerial,
		List<Long> linkedTargets
	) {
		LinkSerialHudOverlaySnapshotSupport.updateCurrentLinksSnapshot(
			dimensionKey,
			blockPosLong,
			sourceType,
			sourceSerial,
			linkedTargets
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
		double maxDistance = RedstoneLinkClientDisplayConfig.overlay().nearDistance();
		if (!LinkSerialOverlayRenderCommon.isWithinDisplayDistance(minecraft, pairableNodeBlockEntity, maxDistance)) {
			return;
		}

		LinkNodeType nodeType = pairableNodeBlockEntity.getLinkNodeType();
		String dimensionKey = minecraft.level.dimension().location().toString();
		long blockPosLong = blockHitResult.getBlockPos().asLong();
		List<Long> linkedTargetsSnapshot = LinkSerialHudOverlaySnapshotSupport.resolveCurrentLinksSnapshotWithLazyRequest(
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
			linkedTargetsSnapshot,
			runtimeHudSnapshot,
			LinkSerialHudOverlayTextSupport.resolveLanguageSignature()
		);
		if (displayLines.isEmpty()) {
			return;
		}
		int textColor = LinkSerialOverlayRenderCommon.resolveNodeTextColor(nodeType);
		LinkSerialHudOverlayDrawSupport.drawCenteredWithDeepBackground(
			guiGraphics,
			minecraft.font,
			displayLines,
			textColor,
			RedstoneLinkClientDisplayConfig.overlay().fontScale()
		);
	}
}
