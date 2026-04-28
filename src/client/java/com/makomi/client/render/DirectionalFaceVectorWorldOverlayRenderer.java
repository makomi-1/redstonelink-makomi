package com.makomi.client.render;

import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * 定向面箭头世界后置渲染器。
 * <p>
 * 箭头迁移到 world overlay 最终阶段后绘制，避免继续受方块实体本体渲染批次影响，
 * 让其显示语义更接近现有命中描边与 quick-link 外显。
 * </p>
 */
public final class DirectionalFaceVectorWorldOverlayRenderer {
	private DirectionalFaceVectorWorldOverlayRenderer() {
	}

	/**
	 * 注册世界后置阶段的定向箭头渲染钩子。
	 */
	public static void register() {
		WorldRenderEvents.LAST.register(DirectionalFaceVectorWorldOverlayRenderer::onLast);
	}

	/**
	 * 在世界最终阶段渲染全部可见定向箭头。
	 */
	private static void onLast(WorldRenderContext worldRenderContext) {
		Minecraft minecraft = Minecraft.getInstance();
		if (
			minecraft.player == null
				|| minecraft.level == null
				|| !SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)
				|| !DirectionalFaceVectorRenderSupport.shouldRenderFaceVectors(minecraft)
				|| worldRenderContext.matrixStack() == null
				|| worldRenderContext.consumers() == null
		) {
			return;
		}

		double maxDistance = RedstoneLinkClientDisplayConfig.overlay().maxDistance();
		if (maxDistance <= 0.0D) {
			return;
		}
		double maxDistanceSqr = maxDistance * maxDistance;
		int renderDistance = Math.max(1, (int) Math.ceil(maxDistance / 16.0D));
		int playerChunkX = minecraft.player.chunkPosition().x;
		int playerChunkZ = minecraft.player.chunkPosition().z;
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
		PoseStack poseStack = worldRenderContext.matrixStack();

		for (int chunkX = playerChunkX - renderDistance; chunkX <= playerChunkX + renderDistance; chunkX++) {
			for (int chunkZ = playerChunkZ - renderDistance; chunkZ <= playerChunkZ + renderDistance; chunkZ++) {
				LevelChunk levelChunk = minecraft.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (levelChunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
					if (!DirectionalFaceVectorRenderSupport.supportsFaceVectorRendering(blockEntity)) {
						continue;
					}
					BlockPos blockPos = blockEntity.getBlockPos();
					double centerX = blockPos.getX() + 0.5D;
					double centerY = blockPos.getY() + 0.5D;
					double centerZ = blockPos.getZ() + 0.5D;
					if (minecraft.player.distanceToSqr(centerX, centerY, centerZ) > maxDistanceSqr) {
						continue;
					}
					poseStack.pushPose();
					poseStack.translate(
						blockPos.getX() - cameraPosition.x,
						blockPos.getY() - cameraPosition.y,
						blockPos.getZ() - cameraPosition.z
					);
					DirectionalFaceVectorRenderSupport.renderEnabledFaceVectors(blockEntity, poseStack, worldRenderContext.consumers());
					poseStack.popPose();
				}
			}
		}
	}
}
