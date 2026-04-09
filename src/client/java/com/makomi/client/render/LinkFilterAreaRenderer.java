package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkDispatchFilterService;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
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

		LevelRenderer.renderLineBox(poseStack, buffer.getBuffer(RenderType.lines()), FILTER_BOX, RED, GREEN, BLUE, 1.0F);
	}

	@Override
	public int getViewDistance() {
		return RedstoneLinkClientDisplayConfig.overlay().maxDistance();
	}
}
