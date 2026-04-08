package com.makomi.client.render;

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
		if (!(minecraft.level.getBlockEntity(blockOutlineContext.blockPos()) instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
			return true;
		}
		LinkNodeType targetType = pairableNodeBlockEntity.getLinkNodeType();
		if (targetType == null) {
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
			targetType == LinkNodeType.CORE ? CORE_RED : TRIGGER_SOURCE_RED,
			targetType == LinkNodeType.CORE ? CORE_GREEN : TRIGGER_SOURCE_GREEN,
			targetType == LinkNodeType.CORE ? CORE_BLUE : TRIGGER_SOURCE_BLUE,
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
}
