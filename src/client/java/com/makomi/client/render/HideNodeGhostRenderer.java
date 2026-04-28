package com.makomi.client.render;

import com.makomi.block.HideCoreBlock;
import com.makomi.block.HideSyncTriggerSourceBlock;
import com.makomi.block.LinkCoreBlock;
import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import com.makomi.data.SmartGlassesAccessSupport;
import com.makomi.item.DirectionalFaceEditorItem;
import com.makomi.registry.ModBlocks;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏节点幽灵模型渲染器。
 * <p>
 * 仅在佩戴智能眼镜时，为隐藏节点补一层半透明普通模型；
 * 当主手持有面编辑器时，再额外渲染启用面的箭头向量。
 * </p>
 */
public final class HideNodeGhostRenderer<T extends PairableNodeBlockEntity> implements BlockEntityRenderer<T> {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final float GHOST_ALPHA_SCALE = 0.42F;
	private static final float ARROW_SHAFT_START = 0.30F;
	private static final float ARROW_SHAFT_END = 0.86F;
	private static final float ARROW_HEAD_LENGTH = 0.18F;
	private static final float ARROW_HEAD_HALF_WIDTH = 0.10F;
	private static final RenderStateShard.LineStateShard FACE_VECTOR_LINE_STATE = new RenderStateShard.LineStateShard(
		java.util.OptionalDouble.of(4.0D)
	);
	private static final RenderType HIDE_FACE_VECTOR_RENDER_TYPE = RenderType.create(
		"redstonelink_hide_face_vectors",
		DefaultVertexFormat.POSITION_COLOR_NORMAL,
		VertexFormat.Mode.LINES,
		1536,
		false,
		true,
		RenderType.CompositeState
			.builder()
			.setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
			.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
			.setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
			.setCullState(RenderStateShard.NO_CULL)
			.setWriteMaskState(RenderStateShard.COLOR_WRITE)
			.setOutputState(RenderStateShard.MAIN_TARGET)
			.setLineState(FACE_VECTOR_LINE_STATE)
			.createCompositeState(false)
	);

	private final LinkNodeFarOverlayRenderer<T> farOverlayRenderer;

	public HideNodeGhostRenderer(BlockEntityRendererProvider.Context context) {
		this.farOverlayRenderer = new LinkNodeFarOverlayRenderer<>(context);
	}

	@Override
	public void render(
		T blockEntity,
		float partialTick,
		PoseStack poseStack,
		MultiBufferSource buffer,
		int packedLight,
		int packedOverlay
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)) {
			BlockState hiddenState = blockEntity.getBlockState();
			BlockState ghostDisplayState = resolveGhostDisplayState(hiddenState);
			if (ghostDisplayState != null) {
				renderGhostModel(minecraft, ghostDisplayState, poseStack, buffer, packedOverlay);
				if (shouldRenderFaceVectors(minecraft)) {
					renderEnabledFaceVectors(blockEntity, hiddenState, poseStack, buffer);
				}
			}
		}
		farOverlayRenderer.render(blockEntity, partialTick, poseStack, buffer, packedLight, packedOverlay);
	}

	@Override
	public int getViewDistance() {
		return farOverlayRenderer.getViewDistance();
	}

	/**
	 * 将隐藏节点状态映射为智能眼镜下的普通节点显示状态。
	 */
	private static BlockState resolveGhostDisplayState(BlockState hiddenState) {
		if (hiddenState == null) {
			return null;
		}
		if (hiddenState.getBlock() instanceof HideCoreBlock) {
			return ModBlocks.LINK_REDSTONE_CORE.defaultBlockState().setValue(LinkCoreBlock.ACTIVE, hiddenState.getValue(LinkCoreBlock.ACTIVE));
		}
		if (hiddenState.getBlock() instanceof HideSyncTriggerSourceBlock) {
			return ModBlocks.LINK_SYNC_EMITTER.defaultBlockState().setValue(
				LinkSignalEmitterBlock.POWERED,
				hiddenState.getValue(LinkSignalEmitterBlock.POWERED)
			);
		}
		return null;
	}

	/**
	 * 使用固定透明度渲染普通节点模型，形成智能眼镜下的幽灵层。
	 */
	private static void renderGhostModel(
		Minecraft minecraft,
		BlockState ghostDisplayState,
		PoseStack poseStack,
		MultiBufferSource buffer,
		int packedOverlay
	) {
		MultiBufferSource alphaBuffer = renderType -> new FixedAlphaVertexConsumer(
			buffer.getBuffer(RenderType.translucent()),
			GHOST_ALPHA_SCALE
		);
		poseStack.pushPose();
		minecraft.getBlockRenderer().renderSingleBlock(ghostDisplayState, poseStack, alphaBuffer, FULL_BRIGHT, packedOverlay);
		poseStack.popPose();
	}

	/**
	 * 判断当前是否需要进入“面编辑可视化”状态。
	 */
	private static boolean shouldRenderFaceVectors(Minecraft minecraft) {
		return minecraft != null
			&& minecraft.player != null
			&& minecraft.player.getMainHandItem().getItem() instanceof DirectionalFaceEditorItem;
	}

	/**
	 * 为当前启用面绘制箭头向量，便于玩家直接看出输入/输出面集。
	 */
	private static void renderEnabledFaceVectors(
		PairableNodeBlockEntity blockEntity,
		BlockState hiddenState,
		PoseStack poseStack,
		MultiBufferSource buffer
	) {
		int packedColor = LinkSerialOverlayRenderCommon.resolveNodeTextColor(blockEntity.getLinkNodeType());
		int red = (packedColor >> 16) & 0xFF;
		int green = (packedColor >> 8) & 0xFF;
		int blue = packedColor & 0xFF;
		VertexConsumer vertexConsumer = buffer.getBuffer(HIDE_FACE_VECTOR_RENDER_TYPE);
		PoseStack.Pose pose = poseStack.last();
		for (Direction enabledFace : NodeFaceSetBlockStateSupport.resolveEnabledFaces(hiddenState)) {
			renderFaceArrow(vertexConsumer, pose, enabledFace, red, green, blue);
		}
	}

	/**
	 * 绘制单个面的箭头。
	 * <p>
	 * 箭杆从方块中心沿启用面方向伸出，箭头头部再用 4 根短线补出方向感，
	 * 避免只画一根直线时难以区分“是哪一面被启用”。
	 * </p>
	 */
	private static void renderFaceArrow(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		Direction face,
		int red,
		int green,
		int blue
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
		renderLine(vertexConsumer, pose, startX, startY, startZ, endX, endY, endZ, directionX, directionY, directionZ, red, green, blue);

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
				blue
			);
		}
	}

	/**
	 * 解析箭头头部的 4 个偏移方向。
	 */
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

	/**
	 * 追加一条局部坐标系中的线段。
	 */
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
		int blue
	) {
		vertexConsumer.addVertex(pose, startX, startY, startZ).setColor(red, green, blue, 255).setNormal(pose, normalX, normalY, normalZ);
		vertexConsumer.addVertex(pose, endX, endY, endZ).setColor(red, green, blue, 255).setNormal(pose, normalX, normalY, normalZ);
	}

	/**
	 * 将模型顶点 alpha 压缩到固定比例，配合半透明渲染层形成幽灵效果。
	 */
	private static final class FixedAlphaVertexConsumer implements VertexConsumer {
		private final VertexConsumer delegate;
		private final float alphaScale;

		private FixedAlphaVertexConsumer(VertexConsumer delegate, float alphaScale) {
			this.delegate = delegate;
			this.alphaScale = alphaScale;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			delegate.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			int scaledAlpha = Math.max(0, Math.min(255, Math.round(alpha * alphaScale)));
			delegate.setColor(red, green, blue, scaledAlpha);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			delegate.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			delegate.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			delegate.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			delegate.setNormal(x, y, z);
			return this;
		}
	}
}
