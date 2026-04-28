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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏节点幽灵模型渲染器。
 * <p>
 * 仅在佩戴智能眼镜时，为隐藏节点补一层半透明普通模型。
 * </p>
 */
public final class HideNodeGhostRenderer<T extends PairableNodeBlockEntity> implements BlockEntityRenderer<T> {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final float GHOST_ALPHA_SCALE = 0.42F;

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
