package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
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
 * 隐藏过滤器 ghost 模型渲染器。
 * <p>
 * 仅在佩戴智能眼镜时绘制半透明普通过滤器模型，并继续复用过滤器原有作用域外显。
 * </p>
 */
public final class HideFilterGhostRenderer<T extends AbstractLinkFilterBlockEntity>
	implements BlockEntityRenderer<T, HideFilterGhostRenderer.HideFilterGhostRenderState> {
	private final LinkFilterAreaRenderer<T> areaRenderer;

	public HideFilterGhostRenderer(BlockEntityRendererProvider.Context context) {
		this.areaRenderer = new LinkFilterAreaRenderer<>(context);
	}

	@Override
	public HideFilterGhostRenderState createRenderState() {
		return new HideFilterGhostRenderState(areaRenderer.createRenderState());
	}

	@Override
	public void extractRenderState(
		T blockEntity,
		HideFilterGhostRenderState renderState,
		float partialTick,
		net.minecraft.world.phys.Vec3 cameraPosition,
		CrumblingOverlay crumblingOverlay
	) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, renderState, partialTick, cameraPosition, crumblingOverlay);
		renderState.ghostDisplayState = null;
		areaRenderer.extractRenderState(blockEntity, renderState.areaState, partialTick, cameraPosition, crumblingOverlay);
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
		HideFilterGhostRenderState renderState,
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
		areaRenderer.submit(renderState.areaState, poseStack, submitNodeCollector, cameraRenderState);
	}

	@Override
	public int getViewDistance() {
		// 佩戴智能眼镜后，隐藏过滤器的幽灵显示与作用域外显都不再受常规距离裁剪。
		return Integer.MAX_VALUE;
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return areaRenderer.shouldRenderOffScreen();
	}

	/**
	 * 隐藏过滤器渲染状态。
	 */
	public static final class HideFilterGhostRenderState extends BlockEntityRenderState {
		private final LinkFilterAreaRenderer.LinkFilterAreaRenderState areaState;
		private BlockState ghostDisplayState;

		private HideFilterGhostRenderState(LinkFilterAreaRenderer.LinkFilterAreaRenderState areaState) {
			this.areaState = areaState;
		}
	}
}
