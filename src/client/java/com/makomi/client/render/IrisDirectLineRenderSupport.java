package com.makomi.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Iris 兼容直接线段绘制支撑。
 * <p>
 * 该 helper 仅用于 Iris 下需要“穿透显示”的世界后置线段外显，
 * 通过直接提交 mesh 并显式关闭深度测试，绕开 Iris 对常规 world line target 的改写。
 * </p>
 */
public final class IrisDirectLineRenderSupport {
	private IrisDirectLineRenderSupport() {
	}

	/**
	 * 以直接绘制方式输出一批线段。
	 */
	public static void drawSegments(PoseStack.Pose pose, List<ColoredLineSegment> segments, float lineWidth) {
		if (pose == null || segments == null || segments.isEmpty()) {
			return;
		}
		float previousLineWidth = RenderSystem.getShaderLineWidth();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.lineWidth(lineWidth);
		RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
		try {
			BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
			for (ColoredLineSegment segment : segments) {
				if (segment == null) {
					continue;
				}
				bufferBuilder
					.addVertex(pose, segment.startX(), segment.startY(), segment.startZ())
					.setColor(segment.red(), segment.green(), segment.blue(), segment.alpha())
					.setNormal(pose, segment.normalX(), segment.normalY(), segment.normalZ());
				bufferBuilder
					.addVertex(pose, segment.endX(), segment.endY(), segment.endZ())
					.setColor(segment.red(), segment.green(), segment.blue(), segment.alpha())
					.setNormal(pose, segment.normalX(), segment.normalY(), segment.normalZ());
			}
			BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
		} finally {
			RenderSystem.lineWidth(previousLineWidth);
			RenderSystem.depthMask(true);
			RenderSystem.enableDepthTest();
			RenderSystem.disableBlend();
		}
	}

	/**
	 * 单条直接绘制线段。
	 */
	public record ColoredLineSegment(
		float startX,
		float startY,
		float startZ,
		float endX,
		float endY,
		float endZ,
		float normalX,
		float normalY,
		float normalZ,
		int red,
		int green,
		int blue,
		int alpha
	) {
		/**
		 * 基于线段两端与颜色创建线段数据。
		 */
		public static ColoredLineSegment of(
			double startX,
			double startY,
			double startZ,
			double endX,
			double endY,
			double endZ,
			int red,
			int green,
			int blue,
			int alpha
		) {
			double deltaX = endX - startX;
			double deltaY = endY - startY;
			double deltaZ = endZ - startZ;
			double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
			float normalX = length <= 0.0D ? 1.0F : (float) (deltaX / length);
			float normalY = length <= 0.0D ? 0.0F : (float) (deltaY / length);
			float normalZ = length <= 0.0D ? 0.0F : (float) (deltaZ / length);
			return new ColoredLineSegment(
				(float) startX,
				(float) startY,
				(float) startZ,
				(float) endX,
				(float) endY,
				(float) endZ,
				normalX,
				normalY,
				normalZ,
				red,
				green,
				blue,
				alpha
			);
		}
	}
}
