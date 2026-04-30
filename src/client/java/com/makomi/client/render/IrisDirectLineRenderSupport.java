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
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Iris 兼容直接线段绘制支撑。
 * <p>
 * 该 helper 仅用于 Iris 下需要“穿透显示”的世界后置线段外显，
 * 通过直接提交 mesh 并显式关闭深度测试，绕开 Iris 对常规 world line target 的改写。
 * </p>
 */
public final class IrisDirectLineRenderSupport {
	private static final float SEGMENT_EPSILON = 1.0E-5F;
	private static final float MIN_HALF_WIDTH = 0.006F;
	private static final float MAX_HALF_WIDTH = 0.050F;
	private static final float BASE_HALF_WIDTH_SCALE = 0.0022F;
	private static final float DISTANCE_HALF_WIDTH_SCALE = 0.0005F;

	private IrisDirectLineRenderSupport() {
	}

	/**
	 * 以直接绘制方式输出一批线段。
	 */
	public static void drawSegments(PoseStack.Pose pose, List<ColoredLineSegment> segments, float lineWidth) {
		if (pose == null || segments == null || segments.isEmpty()) {
			return;
		}
		Matrix4f poseMatrix = pose.pose();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		try {
			BufferBuilder bufferBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
			for (ColoredLineSegment segment : segments) {
				if (segment == null) {
					continue;
				}
				appendSegmentQuad(bufferBuilder, poseMatrix, segment, lineWidth);
			}
			BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());
		} finally {
			RenderSystem.depthMask(true);
			RenderSystem.enableCull();
			RenderSystem.enableDepthTest();
			RenderSystem.disableBlend();
		}
	}

	/**
	 * 把单条线段展开成面向相机的细长四边形。
	 * <p>
	 * Iris 直绘分支不再使用 vanilla `rendertype_lines`，
	 * 避免平行视角下屏幕空间扩线退化导致的边闪烁/消失。
	 * </p>
	 */
	private static void appendSegmentQuad(
		BufferBuilder bufferBuilder,
		Matrix4f poseMatrix,
		ColoredLineSegment segment,
		float lineWidth
	) {
		Vector3f start = transformPosition(poseMatrix, segment.startX(), segment.startY(), segment.startZ());
		Vector3f end = transformPosition(poseMatrix, segment.endX(), segment.endY(), segment.endZ());
		float deltaX = end.x - start.x;
		float deltaY = end.y - start.y;
		float deltaZ = end.z - start.z;
		float segmentLengthSqr = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
		if (segmentLengthSqr <= SEGMENT_EPSILON) {
			return;
		}
		float segmentLength = (float) Math.sqrt(segmentLengthSqr);
		float directionX = deltaX / segmentLength;
		float directionY = deltaY / segmentLength;
		float directionZ = deltaZ / segmentLength;
		float midpointX = (start.x + end.x) * 0.5F;
		float midpointY = (start.y + end.y) * 0.5F;
		float midpointZ = (start.z + end.z) * 0.5F;
		float midpointLengthSqr = midpointX * midpointX + midpointY * midpointY + midpointZ * midpointZ;
		float viewX;
		float viewY;
		float viewZ;
		if (midpointLengthSqr <= SEGMENT_EPSILON) {
			viewX = 0.0F;
			viewY = 0.0F;
			viewZ = 1.0F;
		} else {
			float inverseMidpointLength = 1.0F / (float) Math.sqrt(midpointLengthSqr);
			viewX = midpointX * inverseMidpointLength;
			viewY = midpointY * inverseMidpointLength;
			viewZ = midpointZ * inverseMidpointLength;
		}

		float sideX = directionY * viewZ - directionZ * viewY;
		float sideY = directionZ * viewX - directionX * viewZ;
		float sideZ = directionX * viewY - directionY * viewX;
		float sideLengthSqr = sideX * sideX + sideY * sideY + sideZ * sideZ;
		if (sideLengthSqr <= SEGMENT_EPSILON) {
			float[] fallbackAxis = resolveFallbackAxis(directionX, directionY, directionZ);
			sideX = directionY * fallbackAxis[2] - directionZ * fallbackAxis[1];
			sideY = directionZ * fallbackAxis[0] - directionX * fallbackAxis[2];
			sideZ = directionX * fallbackAxis[1] - directionY * fallbackAxis[0];
			sideLengthSqr = sideX * sideX + sideY * sideY + sideZ * sideZ;
			if (sideLengthSqr <= SEGMENT_EPSILON) {
				return;
			}
		}

		float sideScale = resolveHalfWidth((float) Math.sqrt(midpointLengthSqr), lineWidth) / (float) Math.sqrt(sideLengthSqr);
		sideX *= sideScale;
		sideY *= sideScale;
		sideZ *= sideScale;

		addVertex(bufferBuilder, start.x + sideX, start.y + sideY, start.z + sideZ, segment.red(), segment.green(), segment.blue(), segment.alpha());
		addVertex(bufferBuilder, end.x + sideX, end.y + sideY, end.z + sideZ, segment.red(), segment.green(), segment.blue(), segment.alpha());
		addVertex(bufferBuilder, end.x - sideX, end.y - sideY, end.z - sideZ, segment.red(), segment.green(), segment.blue(), segment.alpha());
		addVertex(bufferBuilder, start.x - sideX, start.y - sideY, start.z - sideZ, segment.red(), segment.green(), segment.blue(), segment.alpha());
	}

	/**
	 * 把局部顶点坐标先应用当前 pose，再转成直接绘制所需的相机相对坐标。
	 */
	private static Vector3f transformPosition(Matrix4f poseMatrix, float x, float y, float z) {
		return poseMatrix.transformPosition(x, y, z, new Vector3f());
	}

	/**
	 * 解析与线段方向最不平行的世界轴，作为近乎视线平行时的兜底横向轴。
	 */
	private static float[] resolveFallbackAxis(float directionX, float directionY, float directionZ) {
		float absoluteX = Math.abs(directionX);
		float absoluteY = Math.abs(directionY);
		float absoluteZ = Math.abs(directionZ);
		if (absoluteX <= absoluteY && absoluteX <= absoluteZ) {
			return new float[] { 1.0F, 0.0F, 0.0F };
		}
		if (absoluteY <= absoluteX && absoluteY <= absoluteZ) {
			return new float[] { 0.0F, 1.0F, 0.0F };
		}
		return new float[] { 0.0F, 0.0F, 1.0F };
	}

	/**
	 * 按相机距离粗略放大线段厚度，让远处带状线不至于细到不可见。
	 */
	private static float resolveHalfWidth(float cameraDistance, float lineWidth) {
		float baseHalfWidth = Math.max(MIN_HALF_WIDTH, lineWidth * BASE_HALF_WIDTH_SCALE);
		float distanceScaledHalfWidth = Math.max(
			baseHalfWidth,
			Math.max(1.0F, cameraDistance) * Math.max(1.0F, lineWidth) * DISTANCE_HALF_WIDTH_SCALE
		);
		return Math.min(MAX_HALF_WIDTH, distanceScaledHalfWidth);
	}

	/**
	 * 写入一个 PositionColor 顶点。
	 */
	private static void addVertex(BufferBuilder bufferBuilder, float x, float y, float z, int red, int green, int blue, int alpha) {
		bufferBuilder.addVertex(x, y, z).setColor(red, green, blue, alpha);
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
			return new ColoredLineSegment(
				(float) startX,
				(float) startY,
				(float) startZ,
				(float) endX,
				(float) endY,
				(float) endZ,
				red,
				green,
				blue,
				alpha
			);
		}
	}
}
