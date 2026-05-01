package com.makomi.client.render;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Iris / Sodium 兼容直接线段绘制支撑。
 * <p>
 * 兼容分支下不再依赖默认世界线段 target，而是把世界线段展开成面向相机的细长四边形，
 * 再复用当前穿透四边形管线提交，绕开 shader 管线下的线段兼容问题。
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
	 * 以兼容分支的直接绘制方式输出一批世界空间线段。
	 */
	public static void drawWorldSegments(
		LevelRenderContext worldRenderContext,
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> segments,
		float lineWidth
	) {
		if (worldRenderContext == null || segments == null || segments.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) {
			return;
		}
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
		List<SeeThroughWorldGeometryRenderSupport.ColoredQuad> quads = new ArrayList<>(segments.size());
		for (SeeThroughWorldGeometryRenderSupport.ColoredLineSegment segment : segments) {
			if (segment == null) {
				continue;
			}
			appendSegmentQuad(quads, cameraPosition, segment, lineWidth);
		}
		SeeThroughWorldGeometryRenderSupport.renderFilledQuads(worldRenderContext, quads);
	}

	/**
	 * 把单条世界线段展开成面向相机的细长四边形。
	 */
	private static void appendSegmentQuad(
		List<SeeThroughWorldGeometryRenderSupport.ColoredQuad> output,
		Vec3 cameraPosition,
		SeeThroughWorldGeometryRenderSupport.ColoredLineSegment segment,
		float lineWidth
	) {
		if (output == null || cameraPosition == null || segment == null) {
			return;
		}
		float startX = (float) segment.startX();
		float startY = (float) segment.startY();
		float startZ = (float) segment.startZ();
		float endX = (float) segment.endX();
		float endY = (float) segment.endY();
		float endZ = (float) segment.endZ();
		float deltaX = endX - startX;
		float deltaY = endY - startY;
		float deltaZ = endZ - startZ;
		float segmentLengthSqr = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
		if (segmentLengthSqr <= SEGMENT_EPSILON) {
			return;
		}
		float segmentLength = (float) Math.sqrt(segmentLengthSqr);
		float directionX = deltaX / segmentLength;
		float directionY = deltaY / segmentLength;
		float directionZ = deltaZ / segmentLength;
		float midpointX = (startX + endX) * 0.5F;
		float midpointY = (startY + endY) * 0.5F;
		float midpointZ = (startZ + endZ) * 0.5F;
		float viewX = midpointX - (float) cameraPosition.x;
		float viewY = midpointY - (float) cameraPosition.y;
		float viewZ = midpointZ - (float) cameraPosition.z;
		float viewLengthSqr = viewX * viewX + viewY * viewY + viewZ * viewZ;
		if (viewLengthSqr <= SEGMENT_EPSILON) {
			viewX = 0.0F;
			viewY = 0.0F;
			viewZ = 1.0F;
			viewLengthSqr = 1.0F;
		}
		float inverseViewLength = 1.0F / (float) Math.sqrt(viewLengthSqr);
		viewX *= inverseViewLength;
		viewY *= inverseViewLength;
		viewZ *= inverseViewLength;

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

		float sideScale = resolveHalfWidth((float) Math.sqrt(viewLengthSqr), lineWidth) / (float) Math.sqrt(sideLengthSqr);
		sideX *= sideScale;
		sideY *= sideScale;
		sideZ *= sideScale;

		output.add(
			new SeeThroughWorldGeometryRenderSupport.ColoredQuad(
				startX + sideX,
				startY + sideY,
				startZ + sideZ,
				endX + sideX,
				endY + sideY,
				endZ + sideZ,
				endX - sideX,
				endY - sideY,
				endZ - sideZ,
				startX - sideX,
				startY - sideY,
				startZ - sideZ,
				segment.red(),
				segment.green(),
				segment.blue(),
				segment.alpha()
			)
		);
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
}
