package com.makomi.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏节点 ghost 模型渲染支撑。
 * <p>
 * 统一使用固定透明度，把普通方块模型压成智能眼镜下的半透明幽灵层。
 * </p>
 */
final class HideGhostRenderSupport {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final float GHOST_ALPHA_SCALE = 0.42F;

	private HideGhostRenderSupport() {
	}

	/**
	 * 使用固定透明度渲染普通节点模型，形成智能眼镜下的 ghost 层。
	 */
	static void renderGhostModel(
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
	 * 将模型顶点 alpha 压缩到固定比例，配合半透明渲染层形成 ghost 效果。
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
