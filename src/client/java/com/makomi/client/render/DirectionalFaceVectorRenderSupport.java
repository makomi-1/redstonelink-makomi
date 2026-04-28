package com.makomi.client.render;

import com.makomi.block.LinkCoreBlock;
import com.makomi.block.LinkRepeaterBlock;
import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import com.makomi.item.DirectionalFaceEditorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
	public static void renderEnabledFaceVectors(BlockEntity blockEntity, PoseStack poseStack, VertexConsumer vertexConsumer) {
		if (blockEntity == null || poseStack == null || vertexConsumer == null || !supportsFaceVectorRendering(blockEntity)) {
			return;
		}
		BlockState blockState = blockEntity.getBlockState();
		int packedColor = resolveThemeColor(blockEntity);
		PoseStack.Pose pose = poseStack.last();
		renderFaceVectorPass(
			NodeFaceSetBlockStateSupport.resolveEnabledFaces(blockState),
			vertexConsumer,
			pose,
			blockState,
			(packedColor >> 16) & 0xFF,
			(packedColor >> 8) & 0xFF,
			packedColor & 0xFF,
			255
		);
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

	private static void renderFaceVectorPass(
		List<Direction> enabledFaces,
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		BlockState blockState,
		int red,
		int green,
		int blue,
		int alpha
	) {
		if (enabledFaces == null || enabledFaces.isEmpty() || vertexConsumer == null || pose == null || blockState == null) {
			return;
		}
		for (Direction enabledFace : enabledFaces) {
			renderFaceArrow(
				vertexConsumer,
				pose,
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

	private static void renderFaceArrow(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		Direction face,
		int red,
		int green,
		int blue,
		int alpha
	) {
		if (vertexConsumer == null || pose == null || face == null) {
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
		renderLine(vertexConsumer, pose, startX, startY, startZ, endX, endY, endZ, directionX, directionY, directionZ, red, green, blue, alpha);

		float baseX = endX - directionX * ARROW_HEAD_LENGTH;
		float baseY = endY - directionY * ARROW_HEAD_LENGTH;
		float baseZ = endZ - directionZ * ARROW_HEAD_LENGTH;
		for (float[] offset : resolveArrowHeadOffsets(face)) {
			renderLine(
				vertexConsumer,
				pose,
				endX,
				endY,
				endZ,
				baseX + offset[0],
				baseY + offset[1],
				baseZ + offset[2],
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

	private static void renderLine(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
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
		vertexConsumer.addVertex(pose, startX, startY, startZ).setColor(red, green, blue, alpha).setNormal(pose, normalX, normalY, normalZ);
		vertexConsumer.addVertex(pose, endX, endY, endZ).setColor(red, green, blue, alpha).setNormal(pose, normalX, normalY, normalZ);
	}
}
