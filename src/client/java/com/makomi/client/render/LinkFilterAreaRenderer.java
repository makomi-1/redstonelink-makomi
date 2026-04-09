package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkDispatchFilterService;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

/**
 * 过滤器作用域线框渲染器。
 */
public final class LinkFilterAreaRenderer<T extends AbstractLinkFilterBlockEntity> implements BlockEntityRenderer<T> {
	private static final float RED = 1.0F;
	private static final float GREEN = 0.22F;
	private static final float BLUE = 0.22F;
	private static final float FILL_ALPHA = 0.18F;
	private static final RenderType FILTER_FILL_RENDER_TYPE = RenderType.create(
		"redstonelink_link_filter_fill",
		DefaultVertexFormat.POSITION_COLOR,
		VertexFormat.Mode.QUADS,
		1536,
		false,
		true,
		RenderType.CompositeState
			.builder()
			.setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
			.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
			.setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
			.setCullState(RenderStateShard.NO_CULL)
			.setOutputState(RenderStateShard.TRANSLUCENT_TARGET)
			.setWriteMaskState(RenderStateShard.COLOR_WRITE)
			.createCompositeState(false)
	);
	private static final AABB FILTER_BOX = new AABB(
		-LinkDispatchFilterService.FILTER_RADIUS,
		-LinkDispatchFilterService.FILTER_RADIUS,
		-LinkDispatchFilterService.FILTER_RADIUS,
		LinkDispatchFilterService.FILTER_RADIUS + 1.0D,
		LinkDispatchFilterService.FILTER_RADIUS + 1.0D,
		LinkDispatchFilterService.FILTER_RADIUS + 1.0D
	);

	public LinkFilterAreaRenderer(BlockEntityRendererProvider.Context context) {
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
		if (blockEntity == null || !RedstoneLinkClientDisplayConfig.overlay().farOverlayEnabled()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		double maxDistance = RedstoneLinkClientDisplayConfig.overlay().maxDistance();
		double centerX = blockEntity.getBlockPos().getX() + 0.5D;
		double centerY = blockEntity.getBlockPos().getY() + 0.5D;
		double centerZ = blockEntity.getBlockPos().getZ() + 0.5D;
		if (minecraft.player.distanceToSqr(centerX, centerY, centerZ) > maxDistance * maxDistance) {
			return;
		}

		renderFilterBox(poseStack, buffer);
	}

	@Override
	public int getViewDistance() {
		return RedstoneLinkClientDisplayConfig.overlay().maxDistance();
	}

	/**
	 * 绘制过滤器影响域外显：
	 * 先绘制不穿墙半透明红色面层，再叠加原有线框轮廓。
	 */
	private static void renderFilterBox(
		PoseStack poseStack,
		MultiBufferSource buffer
	) {
		renderFilterFill(poseStack, buffer.getBuffer(FILTER_FILL_RENDER_TYPE));
		LevelRenderer.renderLineBox(
			poseStack,
			buffer.getBuffer(RenderType.lines()),
			FILTER_BOX,
			RED,
			GREEN,
			BLUE,
			1.0F
		);
	}

	/**
	 * 以六个显式矩形面绘制过滤器影响域填充层，避免链式辅助方法在当前渲染状态下退化为错误三角面。
	 */
	private static void renderFilterFill(
		PoseStack poseStack,
		VertexConsumer vertexConsumer
	) {
		float minX = (float) FILTER_BOX.minX;
		float minY = (float) FILTER_BOX.minY;
		float minZ = (float) FILTER_BOX.minZ;
		float maxX = (float) FILTER_BOX.maxX;
		float maxY = (float) FILTER_BOX.maxY;
		float maxZ = (float) FILTER_BOX.maxZ;
		PoseStack.Pose pose = poseStack.last();

		addQuad(vertexConsumer, pose, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
		addQuad(vertexConsumer, pose, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ);
		addQuad(vertexConsumer, pose, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ);
		addQuad(vertexConsumer, pose, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
		addQuad(vertexConsumer, pose, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
		addQuad(vertexConsumer, pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ);
	}

	/**
	 * 按四边形顺序写入单个面顶点。
	 */
	private static void addQuad(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		float x1,
		float y1,
		float z1,
		float x2,
		float y2,
		float z2,
		float x3,
		float y3,
		float z3,
		float x4,
		float y4,
		float z4
	) {
		addColoredVertex(vertexConsumer, pose, x1, y1, z1);
		addColoredVertex(vertexConsumer, pose, x2, y2, z2);
		addColoredVertex(vertexConsumer, pose, x3, y3, z3);
		addColoredVertex(vertexConsumer, pose, x4, y4, z4);
	}

	/**
	 * 写入一个带统一颜色与透明度的顶点。
	 */
	private static void addColoredVertex(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		float x,
		float y,
		float z
	) {
		vertexConsumer.addVertex(pose, x, y, z).setColor(RED, GREEN, BLUE, FILL_ALPHA);
	}
}
