package com.makomi.client.render;

import com.makomi.block.HideCoreBlock;
import com.makomi.block.HideSyncTriggerSourceBlock;
import com.makomi.block.LinkCoreBlock;
import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.SmartGlassesAccessSupport;
import com.makomi.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏节点幽灵模型渲染器。
 * <p>
 * 1.21.11 已切到 submit 管线，因此这里在渲染状态中携带“幽灵方块状态 + 远外显状态”，
 * 提交阶段先压一层半透明普通模型，再复用既有远外显文本渲染器。
 * </p>
 */
public final class HideNodeGhostRenderer<T extends PairableNodeBlockEntity>
	implements BlockEntityRenderer<T, HideNodeGhostRenderer.HideNodeGhostRenderState> {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final float GHOST_ALPHA_SCALE = 0.42F;

	private final LinkNodeFarOverlayRenderer<T> farOverlayRenderer;

	public HideNodeGhostRenderer(BlockEntityRendererProvider.Context context) {
		this.farOverlayRenderer = new LinkNodeFarOverlayRenderer<>(context);
	}

	@Override
	public HideNodeGhostRenderState createRenderState() {
		return new HideNodeGhostRenderState(farOverlayRenderer.createRenderState());
	}

	@Override
	public void extractRenderState(
		T blockEntity,
		HideNodeGhostRenderState renderState,
		float partialTick,
		net.minecraft.world.phys.Vec3 cameraPosition,
		CrumblingOverlay crumblingOverlay
	) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, renderState, partialTick, cameraPosition, crumblingOverlay);
		renderState.ghostDisplayState = null;
		farOverlayRenderer.extractRenderState(blockEntity, renderState.farOverlayState, partialTick, cameraPosition, crumblingOverlay);
		if (blockEntity == null) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || !SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)) {
			return;
		}
		renderState.ghostDisplayState = resolveGhostDisplayState(blockEntity.getBlockState());
	}

	@Override
	public void submit(
		HideNodeGhostRenderState renderState,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		CameraRenderState cameraRenderState
	) {
		if (renderState.ghostDisplayState != null) {
			// `submitBlock(..., tint)` 会把幽灵节点压成纯白高亮；这里改回“贴图 + 固定透明度”的原设计，
			// 但仍通过 1.21.11 的 submit 管线提交自定义几何。
			submitNodeCollector.submitCustomGeometry(
				poseStack,
				RenderTypes.translucentMovingBlock(),
				(pose, vertexConsumer) -> renderGhostModel(
					Minecraft.getInstance(),
					renderState.ghostDisplayState,
					pose,
					vertexConsumer,
					OverlayTexture.NO_OVERLAY
				)
			);
		}
		farOverlayRenderer.submit(renderState.farOverlayState, poseStack, submitNodeCollector, cameraRenderState);
	}

	@Override
	public int getViewDistance() {
		return farOverlayRenderer.getViewDistance();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return farOverlayRenderer.shouldRenderOffScreen();
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
		PoseStack.Pose pose,
		VertexConsumer vertexConsumer,
		int packedOverlay
	) {
		if (minecraft == null || ghostDisplayState == null || pose == null || vertexConsumer == null) {
			return;
		}
		VertexConsumer alphaConsumer = new FixedAlphaVertexConsumer(vertexConsumer, GHOST_ALPHA_SCALE);
		minecraft
			.getBlockRenderer()
			.getModelRenderer()
			.renderModel(
				pose,
				alphaConsumer,
				minecraft.getBlockRenderer().getBlockModel(ghostDisplayState),
				1.0F,
				1.0F,
				1.0F,
				FULL_BRIGHT,
				packedOverlay
			);
	}

	/**
	 * 隐藏节点渲染状态。
	 */
	public static final class HideNodeGhostRenderState extends BlockEntityRenderState {
		private final LinkNodeFarOverlayRenderer.LinkNodeFarOverlayRenderState farOverlayState;
		private BlockState ghostDisplayState;

		private HideNodeGhostRenderState(LinkNodeFarOverlayRenderer.LinkNodeFarOverlayRenderState farOverlayState) {
			this.farOverlayState = farOverlayState;
		}
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
		public VertexConsumer setColor(int color) {
			int alpha = (color >>> 24) & 0xFF;
			int scaledAlpha = Math.max(0, Math.min(255, Math.round(alpha * alphaScale)));
			delegate.setColor((scaledAlpha << 24) | (color & 0x00FFFFFF));
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

		@Override
		public VertexConsumer setLineWidth(float width) {
			delegate.setLineWidth(width);
			return this;
		}
	}
}
