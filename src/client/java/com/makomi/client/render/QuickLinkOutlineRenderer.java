package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeType;
import com.makomi.item.QuickLinkToolItem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 快速连接工具命中描边渲染器。
 */
public final class QuickLinkOutlineRenderer {
	private static final float CORE_RED = 0.22F;
	private static final float CORE_GREEN = 0.66F;
	private static final float CORE_BLUE = 1.00F;
	private static final float TRIGGER_SOURCE_RED = 1.00F;
	private static final float TRIGGER_SOURCE_GREEN = 0.58F;
	private static final float TRIGGER_SOURCE_BLUE = 0.18F;
	private static final float FILTER_RED = 1.00F;
	private static final float FILTER_GREEN = 0.16F;
	private static final float FILTER_BLUE = 0.16F;

	private QuickLinkOutlineRenderer() {
	}

	/**
	 * 在默认方块描边阶段绘制 quick-link 自定义描边。
	 *
	 * @return `false` 表示已自行渲染并取消默认白色描边；其余情况保持默认行为
	 */
	public static boolean onBlockOutline(
		WorldRenderContext worldRenderContext,
		WorldRenderContext.BlockOutlineContext blockOutlineContext
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return true;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return true;
		}
		OutlineColor outlineColor = resolveOutlineColor(minecraft, blockOutlineContext.blockPos());
		if (outlineColor == null) {
			return true;
		}

		if (worldRenderContext.matrixStack() == null) {
			return true;
		}
		if (worldRenderContext.consumers() == null) {
			return true;
		}

		VoxelShape voxelShape = blockOutlineContext.blockState().getShape(minecraft.level, blockOutlineContext.blockPos());
		VertexConsumer lineVertexConsumer = worldRenderContext.consumers().getBuffer(RenderType.lines());
		LevelRenderer.renderVoxelShape(
			worldRenderContext.matrixStack(),
			lineVertexConsumer,
			voxelShape,
			(double) blockOutlineContext.blockPos().getX() - blockOutlineContext.cameraX(),
			(double) blockOutlineContext.blockPos().getY() - blockOutlineContext.cameraY(),
			(double) blockOutlineContext.blockPos().getZ() - blockOutlineContext.cameraZ(),
			outlineColor.red(),
			outlineColor.green(),
			outlineColor.blue(),
			1.0F,
			false
		);
		return false;
	}

	/**
	 * 注册方块描边事件。
	 */
	public static void register() {
		WorldRenderEvents.BLOCK_OUTLINE.register(QuickLinkOutlineRenderer::onBlockOutline);
	}

	/**
	 * 根据当前命中方块解析 quick-link 应使用的描边颜色。
	 */
	private static OutlineColor resolveOutlineColor(Minecraft minecraft, net.minecraft.core.BlockPos blockPos) {
		if (minecraft == null || minecraft.level == null || blockPos == null) {
			return null;
		}
		if (minecraft.level.getBlockEntity(blockPos) instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
			LinkNodeType targetType = pairableNodeBlockEntity.getLinkNodeType();
			if (targetType == null) {
				return null;
			}
			return targetType == LinkNodeType.CORE
				? new OutlineColor(CORE_RED, CORE_GREEN, CORE_BLUE)
				: new OutlineColor(TRIGGER_SOURCE_RED, TRIGGER_SOURCE_GREEN, TRIGGER_SOURCE_BLUE);
		}
		if (minecraft.level.getBlockEntity(blockPos) instanceof AbstractLinkFilterBlockEntity) {
			return new OutlineColor(FILTER_RED, FILTER_GREEN, FILTER_BLUE);
		}
		return null;
	}

	/**
	 * quick-link 描边颜色值。
	 */
	private record OutlineColor(float red, float green, float blue) {
	}
}
