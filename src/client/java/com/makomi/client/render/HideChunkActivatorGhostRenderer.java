package com.makomi.client.render;

import com.makomi.block.entity.HideChunkActivatorBlockEntity;
import com.makomi.data.HideNodeSupport;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏区块激活器 ghost 模型渲染器。
 * <p>
 * 仅在佩戴智能眼镜时绘制半透明普通模型，并继续复用区块激活器远外显。
 * </p>
 */
public final class HideChunkActivatorGhostRenderer
	implements BlockEntityRenderer<HideChunkActivatorBlockEntity, HideChunkActivatorGhostRenderer.HideChunkActivatorGhostRenderState> {
	private final ChunkActivatorFarOverlayRenderer farOverlayRenderer;

	public HideChunkActivatorGhostRenderer(BlockEntityRendererProvider.Context context) {
		this.farOverlayRenderer = new ChunkActivatorFarOverlayRenderer(context);
	}

	@Override
	public HideChunkActivatorGhostRenderState createRenderState() {
		return new HideChunkActivatorGhostRenderState(farOverlayRenderer.createRenderState());
	}

	@Override
	public void extractRenderState(
		HideChunkActivatorBlockEntity blockEntity,
		HideChunkActivatorGhostRenderState renderState,
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
		renderState.ghostDisplayState = HideNodeSupport.resolveGhostDisplayState(blockEntity.getBlockState());
	}

	@Override
	public void submit(
		HideChunkActivatorGhostRenderState renderState,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		CameraRenderState cameraRenderState
	) {
		if (renderState.ghostDisplayState != null) {
			submitNodeCollector.submitCustomGeometry(
				poseStack,
				RenderTypes.translucentMovingBlock(),
				(pose, vertexConsumer) -> HideGhostRenderSupport.renderGhostModel(
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
		// 佩戴智能眼镜后，隐藏区块激活器的幽灵显示与远外显都不再受常规距离裁剪。
		return Integer.MAX_VALUE;
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return farOverlayRenderer.shouldRenderOffScreen();
	}

	/**
	 * 隐藏区块激活器渲染状态。
	 */
	public static final class HideChunkActivatorGhostRenderState extends BlockEntityRenderState {
		private final ChunkActivatorFarOverlayRenderer.ChunkActivatorFarOverlayRenderState farOverlayState;
		private BlockState ghostDisplayState;

		private HideChunkActivatorGhostRenderState(
			ChunkActivatorFarOverlayRenderer.ChunkActivatorFarOverlayRenderState farOverlayState
		) {
			this.farOverlayState = farOverlayState;
		}
	}
}
