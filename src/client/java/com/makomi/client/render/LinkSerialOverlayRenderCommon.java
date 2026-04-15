package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.data.LinkFilterKind;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 序号外显公共渲染语义工具。
 * <p>
 * 统一沉淀远外显与近外显共享的基础判断，避免重复实现。
 * </p>
 */
public final class LinkSerialOverlayRenderCommon {
	private static final int CORE_TEXT_COLOR = 0xFF5DD7FF;
	private static final int TRIGGER_SOURCE_TEXT_COLOR = 0xFFFFC66A;
	private static final int FILTER_TEXT_COLOR = 0xFFFF7AA8;
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
	 * 按过滤器类型返回固定文本颜色。
	 * <p>
	 * 当前 send/receive 过滤器的近外显统一使用同一红色主题，避免出现两套不同滤镜色。
	 * </p>
	 */
	public static int resolveFilterTextColor(LinkFilterKind filterKind) {
		if (filterKind == LinkFilterKind.SEND) {
			return FILTER_TEXT_COLOR;
		}
		if (filterKind == LinkFilterKind.RECEIVE) {
			return FILTER_TEXT_COLOR;
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
	 * 读取过滤器可显示文本：优先别名，空别名回退过滤器标题。
	 */
	public static String resolveFilterDisplayText(AbstractLinkFilterBlockEntity blockEntity) {
		if (blockEntity == null || blockEntity.filterKind() == null) {
			return "";
		}
		return composeFilterDisplayText(resolveFilterTitle(blockEntity.getBlockState(), blockEntity.filterKind()), blockEntity.displayAlias());
	}

	/**
	 * 组合过滤器显示文本；优先显示别名，空别名回退标题。
	 */
	static String composeFilterDisplayText(String fallbackTitle, String rawDisplayAlias) {
		String normalizedAlias = NodeAliasDisplayUtil.normalizeAlias(rawDisplayAlias);
		if (!normalizedAlias.isEmpty()) {
			return normalizedAlias;
		}
		return fallbackTitle == null ? "" : fallbackTitle;
	}

	/**
	 * 判断玩家与节点中心距离是否在可显示范围内。
	 */
	public static boolean isWithinDisplayDistance(
		Minecraft minecraft,
		BlockEntity blockEntity,
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

	/**
	 * 解析过滤器标题回退文本。
	 */
	private static String resolveFilterTitle(BlockState state, LinkFilterKind filterKind) {
		return resolveBlockDisplayName(
			state,
			filterKind == LinkFilterKind.RECEIVE
				? "screen.redstonelink.link_filter.receive.title"
				: "screen.redstonelink.link_filter.send.title"
		);
	}

	/**
	 * 统一解析方块标题文本。
	 */
	private static String resolveBlockDisplayName(BlockState state, String fallbackTranslationKey) {
		if (state == null) {
			return net.minecraft.network.chat.Component.translatable(fallbackTranslationKey).getString();
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
		return net.minecraft.network.chat.Component.translatable(fallbackTranslationKey).getString();
	}
}
