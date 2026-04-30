package com.makomi.client.render;

import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.data.HideNodeSupport;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏区块激活器 ghost 模型渲染器。
 * <p>
 * 仅在佩戴智能眼镜时绘制半透明普通模型，并继续复用区块激活器远外显。
 * </p>
 */
public final class HideChunkActivatorGhostRenderer implements BlockEntityRenderer<LinkChunkActivatorBlockEntity> {
	private final ChunkActivatorFarOverlayRenderer farOverlayRenderer;

	public HideChunkActivatorGhostRenderer(BlockEntityRendererProvider.Context context) {
		this.farOverlayRenderer = new ChunkActivatorFarOverlayRenderer(context);
	}

	@Override
	public void render(
		LinkChunkActivatorBlockEntity blockEntity,
		float partialTick,
		PoseStack poseStack,
		MultiBufferSource buffer,
		int packedLight,
		int packedOverlay
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)) {
			BlockState ghostDisplayState = HideNodeSupport.resolveGhostDisplayState(blockEntity.getBlockState());
			if (ghostDisplayState != null) {
				HideGhostRenderSupport.renderGhostModel(minecraft, ghostDisplayState, poseStack, buffer, packedOverlay);
			}
		}
		farOverlayRenderer.render(blockEntity, partialTick, poseStack, buffer, packedLight, packedOverlay);
	}

	@Override
	public int getViewDistance() {
		return farOverlayRenderer.getViewDistance();
	}
}
