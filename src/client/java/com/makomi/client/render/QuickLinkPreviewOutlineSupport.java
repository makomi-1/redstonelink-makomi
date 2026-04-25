package com.makomi.client.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * quick-link 缓存方框外轮廓提取支撑。
 * <p>
 * 仅负责把一组整方块坐标转换为“最外层边界线段”，
 * 不依赖任何客户端渲染静态字段，便于逻辑复用与单元测试。
 * </p>
 */
final class QuickLinkPreviewOutlineSupport {
	private QuickLinkPreviewOutlineSupport() {
	}

	/**
	 * 仅基于外露面边界生成线段，避免相连面的内部接缝继续显示。
	 */
	static List<Segment> buildBoundarySegments(Set<BlockPos> occupiedBlocks) {
		if (occupiedBlocks == null || occupiedBlocks.isEmpty()) {
			return List.of();
		}
		Map<PlaneKey, Set<FaceCell>> faceCellsByPlane = new LinkedHashMap<>();
		for (BlockPos blockPos : occupiedBlocks) {
			if (blockPos == null) {
				continue;
			}
			addExposedFaceCell(faceCellsByPlane, occupiedBlocks, BoundaryAxis.X, blockPos.getX(), blockPos.getZ(), blockPos.getY(), blockPos.west());
			addExposedFaceCell(faceCellsByPlane, occupiedBlocks, BoundaryAxis.X, blockPos.getX() + 1, blockPos.getZ(), blockPos.getY(), blockPos.east());
			addExposedFaceCell(faceCellsByPlane, occupiedBlocks, BoundaryAxis.Y, blockPos.getY(), blockPos.getX(), blockPos.getZ(), blockPos.below());
			addExposedFaceCell(faceCellsByPlane, occupiedBlocks, BoundaryAxis.Y, blockPos.getY() + 1, blockPos.getX(), blockPos.getZ(), blockPos.above());
			addExposedFaceCell(faceCellsByPlane, occupiedBlocks, BoundaryAxis.Z, blockPos.getZ(), blockPos.getX(), blockPos.getY(), blockPos.north());
			addExposedFaceCell(faceCellsByPlane, occupiedBlocks, BoundaryAxis.Z, blockPos.getZ() + 1, blockPos.getX(), blockPos.getY(), blockPos.south());
		}

		List<Segment> segments = new ArrayList<>();
		Set<Segment> uniqueSegments = new LinkedHashSet<>();
		for (Map.Entry<PlaneKey, Set<FaceCell>> entry : faceCellsByPlane.entrySet()) {
			Set<Edge2D> boundaryEdges = new LinkedHashSet<>();
			for (FaceCell faceCell : entry.getValue()) {
				toggleEdge(boundaryEdges, new Edge2D(faceCell.u(), faceCell.v(), faceCell.u(), faceCell.v() + 1));
				toggleEdge(boundaryEdges, new Edge2D(faceCell.u() + 1, faceCell.v(), faceCell.u() + 1, faceCell.v() + 1));
				toggleEdge(boundaryEdges, new Edge2D(faceCell.u(), faceCell.v(), faceCell.u() + 1, faceCell.v()));
				toggleEdge(boundaryEdges, new Edge2D(faceCell.u(), faceCell.v() + 1, faceCell.u() + 1, faceCell.v() + 1));
			}
			for (Edge2D edge : boundaryEdges) {
				Segment segment = toSegment(entry.getKey(), edge);
				if (uniqueSegments.add(segment)) {
					segments.add(segment);
				}
			}
		}
		return mergeCollinearSegments(segments);
	}

	private static void addExposedFaceCell(
		Map<PlaneKey, Set<FaceCell>> faceCellsByPlane,
		Set<BlockPos> occupiedBlocks,
		BoundaryAxis axis,
		int planeCoordinate,
		int u,
		int v,
		BlockPos neighborPos
	) {
		if (faceCellsByPlane == null || occupiedBlocks == null || occupiedBlocks.contains(neighborPos)) {
			return;
		}
		faceCellsByPlane.computeIfAbsent(new PlaneKey(axis, planeCoordinate), ignored -> new LinkedHashSet<>()).add(new FaceCell(u, v));
	}

	private static void toggleEdge(Set<Edge2D> boundaryEdges, Edge2D edge) {
		if (boundaryEdges == null || edge == null) {
			return;
		}
		if (!boundaryEdges.add(edge)) {
			boundaryEdges.remove(edge);
		}
	}

	private static Segment toSegment(PlaneKey planeKey, Edge2D edge) {
		return switch (planeKey.axis()) {
			case X -> Segment.of(
				planeKey.coordinate(),
				edge.startV(),
				edge.startU(),
				planeKey.coordinate(),
				edge.endV(),
				edge.endU()
			);
			case Y -> Segment.of(
				edge.startU(),
				planeKey.coordinate(),
				edge.startV(),
				edge.endU(),
				planeKey.coordinate(),
				edge.endV()
			);
			case Z -> Segment.of(
				edge.startU(),
				edge.startV(),
				planeKey.coordinate(),
				edge.endU(),
				edge.endV(),
				planeKey.coordinate()
			);
		};
	}

	/**
	 * 把同一直线且首尾相接的单位线段合并成长线，避免中点残留视觉接缝。
	 */
	private static List<Segment> mergeCollinearSegments(List<Segment> segments) {
		if (segments == null || segments.isEmpty()) {
			return List.of();
		}
		Map<SegmentFamilyKey, List<IntRange>> rangesByFamily = new LinkedHashMap<>();
		for (Segment segment : segments) {
			if (segment == null) {
				continue;
			}
			SegmentFamilyKey familyKey = SegmentFamilyKey.of(segment);
			rangesByFamily.computeIfAbsent(familyKey, ignored -> new ArrayList<>()).add(IntRange.of(segment));
		}

		List<Segment> mergedSegments = new ArrayList<>();
		for (Map.Entry<SegmentFamilyKey, List<IntRange>> entry : rangesByFamily.entrySet()) {
			List<IntRange> ranges = entry.getValue();
			ranges.sort((left, right) -> {
				int byStart = Integer.compare(left.start(), right.start());
				return byStart != 0 ? byStart : Integer.compare(left.end(), right.end());
			});
			IntRange current = null;
			for (IntRange range : ranges) {
				if (current == null) {
					current = range;
					continue;
				}
				if (range.start() <= current.end()) {
					current = new IntRange(current.start(), Math.max(current.end(), range.end()));
					continue;
				}
				mergedSegments.add(entry.getKey().toSegment(current));
				current = range;
			}
			if (current != null) {
				mergedSegments.add(entry.getKey().toSegment(current));
			}
		}
		return List.copyOf(mergedSegments);
	}

	private record PlaneKey(BoundaryAxis axis, int coordinate) {
	}

	private record FaceCell(int u, int v) {
	}

	private record Edge2D(int startU, int startV, int endU, int endV) {
		Edge2D {
			if (startU > endU || (startU == endU && startV > endV)) {
				int nextStartU = endU;
				int nextStartV = endV;
				endU = startU;
				endV = startV;
				startU = nextStartU;
				startV = nextStartV;
			}
		}
	}

	private enum BoundaryAxis {
		X,
		Y,
		Z
	}

	private record SegmentFamilyKey(BoundaryAxis axis, int fixedA, int fixedB) {
		static SegmentFamilyKey of(Segment segment) {
			if (segment.startX() != segment.endX()) {
				return new SegmentFamilyKey(BoundaryAxis.X, segment.startY(), segment.startZ());
			}
			if (segment.startY() != segment.endY()) {
				return new SegmentFamilyKey(BoundaryAxis.Y, segment.startX(), segment.startZ());
			}
			return new SegmentFamilyKey(BoundaryAxis.Z, segment.startX(), segment.startY());
		}

		Segment toSegment(IntRange range) {
			return switch (axis) {
				case X -> Segment.of(range.start(), fixedA, fixedB, range.end(), fixedA, fixedB);
				case Y -> Segment.of(fixedA, range.start(), fixedB, fixedA, range.end(), fixedB);
				case Z -> Segment.of(fixedA, fixedB, range.start(), fixedA, fixedB, range.end());
			};
		}
	}

	private record IntRange(int start, int end) {
		static IntRange of(Segment segment) {
			if (segment.startX() != segment.endX()) {
				return new IntRange(segment.startX(), segment.endX());
			}
			if (segment.startY() != segment.endY()) {
				return new IntRange(segment.startY(), segment.endY());
			}
			return new IntRange(segment.startZ(), segment.endZ());
		}
	}

	/**
	 * 边界线段整数端点。
	 */
	record Segment(int startX, int startY, int startZ, int endX, int endY, int endZ) {
		static Segment of(int startX, int startY, int startZ, int endX, int endY, int endZ) {
			if (
				startX > endX
					|| (startX == endX && startY > endY)
					|| (startX == endX && startY == endY && startZ > endZ)
			) {
				int nextStartX = endX;
				int nextStartY = endY;
				int nextStartZ = endZ;
				endX = startX;
				endY = startY;
				endZ = startZ;
				startX = nextStartX;
				startY = nextStartY;
				startZ = nextStartZ;
			}
			return new Segment(startX, startY, startZ, endX, endY, endZ);
		}
	}
}
