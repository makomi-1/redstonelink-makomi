package com.makomi.client.render;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 快速连接工具合法命中描边渲染器。
 */
public final class QuickLinkOutlineRenderer {
	private QuickLinkOutlineRenderer() {
	}

	/**
	 * 在默认方块描边阶段绘制亮红描边。
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

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(minecraft.player.getMainHandItem());
		if (snapshot.mode() != QuickLinkToolData.Mode.SERIAL || snapshot.serialCacheExpression().isBlank()) {
			return true;
		}

		LinkNodeType expectedTargetType = snapshot.serialCacheType() == LinkNodeType.TRIGGER_SOURCE
			? LinkNodeType.CORE
			: LinkNodeType.TRIGGER_SOURCE;
		if (pairableNodeBlockEntity.getLinkNodeType() != expectedTargetType) {
			return true;
		}

		if (worldRenderContext.matrixStack() == null) {
			return true;
		}

		VoxelShape voxelShape = blockOutlineContext.blockState().getShape(minecraft.level, blockOutlineContext.blockPos());
		LevelRenderer.renderVoxelShape(
			worldRenderContext.matrixStack(),
			blockOutlineContext.vertexConsumer(),
			voxelShape,
			(double) blockOutlineContext.blockPos().getX() - blockOutlineContext.cameraX(),
			(double) blockOutlineContext.blockPos().getY() - blockOutlineContext.cameraY(),
			(double) blockOutlineContext.blockPos().getZ() - blockOutlineContext.cameraZ(),
			1.0F,
			0.2F,
			0.2F,
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
