package com.makomi.client.render;

import com.makomi.block.entity.PairableNodeBlockEntity;
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
		renderState.ghostDisplayState = HideNodeSupport.resolveGhostDisplayState(blockEntity.getBlockState());
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
		return farOverlayRenderer.getViewDistance();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return farOverlayRenderer.shouldRenderOffScreen();
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
}
