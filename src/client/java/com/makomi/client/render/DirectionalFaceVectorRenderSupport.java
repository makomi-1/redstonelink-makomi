package com.makomi.client.render;

import com.makomi.block.LinkCoreBlock;
import com.makomi.block.LinkRepeaterBlock;
import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import com.makomi.item.DirectionalFaceEditorItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 定向面箭头公共渲染支持。
 * <p>
 * 统一沉淀启用面箭头的几何参数、主题色解析与显示门槛，
 * 让隐藏节点与可见节点共享同一套方向可视化语义。
 * </p>
 */
public final class DirectionalFaceVectorRenderSupport {
	private static final float ARROW_SHAFT_START = 0.58F;
	private static final float ARROW_SHAFT_END = 1.08F;
	private static final float ARROW_HEAD_LENGTH = 0.18F;
	private static final float ARROW_HEAD_HALF_WIDTH = 0.10F;

	private DirectionalFaceVectorRenderSupport() {
	}

	/**
	 * 判断当前是否需要进入“定向面可视化”状态。
	 */
	public static boolean shouldRenderFaceVectors(Minecraft minecraft) {
		return minecraft != null
			&& minecraft.player != null
			&& (
				minecraft.player.getMainHandItem().getItem() instanceof DirectionalFaceEditorItem
					|| minecraft.player.getOffhandItem().getItem() instanceof DirectionalFaceEditorItem
					|| RedstoneLinkClientDisplayConfig.isSmartGlassesFaceVectorEnabled()
			);
	}

	/**
	 * 判断指定方块实体是否属于可绘制定向箭头的目标。
	 */
	public static boolean supportsFaceVectorRendering(BlockEntity blockEntity) {
		if (blockEntity == null || !NodeFaceSetBlockStateSupport.hasFaceProperties(blockEntity.getBlockState())) {
			return false;
		}
		return blockEntity instanceof PairableNodeBlockEntity
			|| blockEntity instanceof LinkRepeaterBlockEntity
			|| blockEntity instanceof AbstractLinkFilterBlockEntity
			|| blockEntity instanceof LinkChunkActivatorBlockEntity;
	}

	/**
	 * 按方块状态的启用面集合，为对应方块实体绘制箭头向量。
	 */
	public static List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> collectEnabledFaceVectors(BlockEntity blockEntity) {
		if (blockEntity == null || !supportsFaceVectorRendering(blockEntity)) {
			return List.of();
		}
		BlockState blockState = blockEntity.getBlockState();
		int packedColor = resolveThemeColor(blockEntity);
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments = new ArrayList<>();
		collectFaceVectorPass(
			NodeFaceSetBlockStateSupport.resolveEnabledFaces(blockState),
			lineSegments,
			blockEntity,
			blockState,
			(packedColor >> 16) & 0xFF,
			(packedColor >> 8) & 0xFF,
			packedColor & 0xFF,
			255
		);
		return lineSegments.isEmpty() ? List.of() : List.copyOf(lineSegments);
	}

	/**
	 * 按方块类别解析箭头主题色。
	 */
	public static int resolveThemeColor(BlockEntity blockEntity) {
		if (blockEntity instanceof LinkRepeaterBlockEntity) {
			return LinkSerialOverlayRenderCommon.resolveRepeaterTextColor();
		}
		if (blockEntity instanceof AbstractLinkFilterBlockEntity filterBlockEntity) {
			return LinkSerialOverlayRenderCommon.resolveFilterTextColor(filterBlockEntity.filterKind());
		}
		if (blockEntity instanceof LinkChunkActivatorBlockEntity) {
			return LinkSerialOverlayRenderCommon.resolveChunkActivatorTextColor();
		}
		if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
			return LinkSerialOverlayRenderCommon.resolveNodeTextColor(pairableNodeBlockEntity.getLinkNodeType());
		}
		return 0xFFFFFFFF;
	}

	private static void collectFaceVectorPass(
		List<Direction> enabledFaces,
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments,
		BlockEntity blockEntity,
		BlockState blockState,
		int red,
		int green,
		int blue,
		int alpha
	) {
		if (enabledFaces == null || enabledFaces.isEmpty() || lineSegments == null || blockEntity == null || blockState == null) {
			return;
		}
		double originX = blockEntity.getBlockPos().getX();
		double originY = blockEntity.getBlockPos().getY();
		double originZ = blockEntity.getBlockPos().getZ();
		for (Direction enabledFace : enabledFaces) {
			collectFaceArrow(
				lineSegments,
				originX,
				originY,
				originZ,
				resolveRenderedFaceDirection(blockState, enabledFace),
				red,
				green,
				blue,
				alpha
			);
		}
	}

	/**
	 * `core/repeater` 的底层输出判定与玩家点击的向外输出面相反，显示时需要反向修正。
	 */
	private static Direction resolveRenderedFaceDirection(BlockState state, Direction enabledFace) {
		if (state == null || enabledFace == null) {
			return null;
		}
		return state.getBlock() instanceof LinkCoreBlock || state.getBlock() instanceof LinkRepeaterBlock
			? enabledFace.getOpposite()
			: enabledFace;
	}

	private static void collectFaceArrow(
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments,
		double originX,
		double originY,
		double originZ,
		Direction face,
		int red,
		int green,
		int blue,
		int alpha
	) {
		if (lineSegments == null || face == null) {
			return;
		}
		float directionX = face.getStepX();
		float directionY = face.getStepY();
		float directionZ = face.getStepZ();
		float startX = 0.5F + directionX * ARROW_SHAFT_START;
		float startY = 0.5F + directionY * ARROW_SHAFT_START;
		float startZ = 0.5F + directionZ * ARROW_SHAFT_START;
		float endX = 0.5F + directionX * ARROW_SHAFT_END;
		float endY = 0.5F + directionY * ARROW_SHAFT_END;
		float endZ = 0.5F + directionZ * ARROW_SHAFT_END;
		addWorldLine(
			lineSegments,
			originX + startX,
			originY + startY,
			originZ + startZ,
			originX + endX,
			originY + endY,
			originZ + endZ,
			directionX,
			directionY,
			directionZ,
			red,
			green,
			blue,
			alpha
		);

		float baseX = endX - directionX * ARROW_HEAD_LENGTH;
		float baseY = endY - directionY * ARROW_HEAD_LENGTH;
		float baseZ = endZ - directionZ * ARROW_HEAD_LENGTH;
		for (float[] offset : resolveArrowHeadOffsets(face)) {
			addWorldLine(
				lineSegments,
				originX + endX,
				originY + endY,
				originZ + endZ,
				originX + baseX + offset[0],
				originY + baseY + offset[1],
				originZ + baseZ + offset[2],
				directionX,
				directionY,
				directionZ,
				red,
				green,
				blue,
				alpha
			);
		}
	}

	private static float[][] resolveArrowHeadOffsets(Direction face) {
		return switch (face.getAxis()) {
			case X -> new float[][] {
				{ 0.0F, ARROW_HEAD_HALF_WIDTH, ARROW_HEAD_HALF_WIDTH },
				{ 0.0F, ARROW_HEAD_HALF_WIDTH, -ARROW_HEAD_HALF_WIDTH },
				{ 0.0F, -ARROW_HEAD_HALF_WIDTH, ARROW_HEAD_HALF_WIDTH },
				{ 0.0F, -ARROW_HEAD_HALF_WIDTH, -ARROW_HEAD_HALF_WIDTH }
			};
			case Y -> new float[][] {
				{ ARROW_HEAD_HALF_WIDTH, 0.0F, ARROW_HEAD_HALF_WIDTH },
				{ ARROW_HEAD_HALF_WIDTH, 0.0F, -ARROW_HEAD_HALF_WIDTH },
				{ -ARROW_HEAD_HALF_WIDTH, 0.0F, ARROW_HEAD_HALF_WIDTH },
				{ -ARROW_HEAD_HALF_WIDTH, 0.0F, -ARROW_HEAD_HALF_WIDTH }
			};
			case Z -> new float[][] {
				{ ARROW_HEAD_HALF_WIDTH, ARROW_HEAD_HALF_WIDTH, 0.0F },
				{ ARROW_HEAD_HALF_WIDTH, -ARROW_HEAD_HALF_WIDTH, 0.0F },
				{ -ARROW_HEAD_HALF_WIDTH, ARROW_HEAD_HALF_WIDTH, 0.0F },
				{ -ARROW_HEAD_HALF_WIDTH, -ARROW_HEAD_HALF_WIDTH, 0.0F }
			};
		};
	}

	private static void addWorldLine(
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments,
		double startX,
		double startY,
		double startZ,
		double endX,
		double endY,
		double endZ,
		float normalX,
		float normalY,
		float normalZ,
		int red,
		int green,
		int blue,
		int alpha
	) {
		// 1.21.11 的线段顶点格式要求显式携带 LineWidth。
		// 穿透管线最终仍会写入 LineWidth，这里只统一沉淀世界空间线段与法线参数。
		float length = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
		float resolvedNormalX = length <= 0.0F ? 1.0F : normalX / length;
		float resolvedNormalY = length <= 0.0F ? 0.0F : normalY / length;
		float resolvedNormalZ = length <= 0.0F ? 0.0F : normalZ / length;
		lineSegments.add(
			new SeeThroughWorldGeometryRenderSupport.ColoredLineSegment(
				startX,
				startY,
				startZ,
				endX,
				endY,
				endZ,
				resolvedNormalX,
				resolvedNormalY,
				resolvedNormalZ,
				red,
				green,
				blue,
				alpha
			)
		);
	}
}
