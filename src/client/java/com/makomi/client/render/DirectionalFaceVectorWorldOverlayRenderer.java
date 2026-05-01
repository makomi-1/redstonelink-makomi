package com.makomi.client.render;

import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.SmartGlassesAccessSupport;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 定向面箭头世界后置渲染器。
 */
public final class DirectionalFaceVectorWorldOverlayRenderer {
	private DirectionalFaceVectorWorldOverlayRenderer() {
	}

	/**
	 * 注册世界后置阶段的定向箭头渲染钩子。
	 */
	public static void register() {
		LevelRenderEvents.END_MAIN.register(DirectionalFaceVectorWorldOverlayRenderer::onLast);
	}

	private static void onLast(LevelRenderContext worldRenderContext) {
		Minecraft minecraft = Minecraft.getInstance();
		if (
			minecraft.player == null
				|| minecraft.level == null
				|| !SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)
				|| !DirectionalFaceVectorRenderSupport.shouldRenderFaceVectors(minecraft)
				|| worldRenderContext.poseStack() == null
		) {
			return;
		}

		double maxDistance = RedstoneLinkClientDisplayConfig.overlay().maxDistance();
		if (maxDistance <= 0.0D) {
			return;
		}
		double maxDistanceSqr = maxDistance * maxDistance;
		int renderDistance = Math.max(1, (int) Math.ceil(maxDistance / 16.0D));
		int playerChunkX = minecraft.player.chunkPosition().x();
		int playerChunkZ = minecraft.player.chunkPosition().z();
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments = new ArrayList<>();

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
					lineSegments.addAll(DirectionalFaceVectorRenderSupport.collectEnabledFaceVectors(blockEntity));
				}
			}
		}
		if (IrisRenderCompatSupport.shouldUseCompatibilityBranch()) {
			IrisDirectLineRenderSupport.drawWorldSegments(worldRenderContext, lineSegments, 4.0F);
		} else {
			SeeThroughWorldGeometryRenderSupport.renderLines(worldRenderContext, lineSegments);
		}
	}
}
