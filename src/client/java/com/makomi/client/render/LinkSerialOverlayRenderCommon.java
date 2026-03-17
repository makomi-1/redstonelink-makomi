package com.makomi.client.render;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeType;
import net.minecraft.client.Minecraft;

/**
 * 序号外显公共渲染语义工具。
 * <p>
 * 统一沉淀远外显与近外显共享的基础判断，避免重复实现。
 * </p>
 */
public final class LinkSerialOverlayRenderCommon {
	private static final int CORE_TEXT_COLOR = 0xFF5DD7FF;
	private static final int TRIGGER_SOURCE_TEXT_COLOR = 0xFFFFC66A;
	private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;

	private LinkSerialOverlayRenderCommon() {
	}

	/**
	 * 按节点类型返回固定文本颜色，用于不同类型外显快速区分。
	 */
	public static int resolveNodeTextColor(LinkNodeType nodeType) {
		if (nodeType == LinkNodeType.CORE) {
			return CORE_TEXT_COLOR;
		}
		if (nodeType == LinkNodeType.TRIGGER_SOURCE) {
			return TRIGGER_SOURCE_TEXT_COLOR;
		}
		return DEFAULT_TEXT_COLOR;
	}

	/**
	 * 读取可显示序号文本，并统一做空值归一化。
	 */
	public static String resolveDisplaySerialText(PairableNodeBlockEntity blockEntity) {
		if (blockEntity == null) {
			return "";
		}
		String serialText = blockEntity.getSerialDisplayText();
		return serialText == null ? "" : serialText;
	}

	/**
	 * 判断玩家与节点中心距离是否在可显示范围内。
	 */
	public static boolean isWithinDisplayDistance(
		Minecraft minecraft,
		PairableNodeBlockEntity blockEntity,
		double maxDistance
	) {
		if (minecraft == null || minecraft.player == null || blockEntity == null || maxDistance <= 0.0D) {
			return false;
		}
		double maxDistanceSqr = maxDistance * maxDistance;
		double centerX = blockEntity.getBlockPos().getX() + 0.5D;
		double centerY = blockEntity.getBlockPos().getY() + 0.5D;
		double centerZ = blockEntity.getBlockPos().getZ() + 0.5D;
		return minecraft.player.distanceToSqr(centerX, centerY, centerZ) <= maxDistanceSqr;
	}
}
