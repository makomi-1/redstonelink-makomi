package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.data.HideNodeSupport;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏过滤器 ghost 模型渲染器。
 * <p>
 * 仅在佩戴智能眼镜时绘制半透明普通过滤器模型，并继续复用过滤器原有作用域外显。
 * </p>
 */
public final class HideFilterGhostRenderer<T extends AbstractLinkFilterBlockEntity> implements BlockEntityRenderer<T> {
	private final LinkFilterAreaRenderer<T> areaRenderer;

	public HideFilterGhostRenderer(BlockEntityRendererProvider.Context context) {
		this.areaRenderer = new LinkFilterAreaRenderer<>(context);
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
			BlockState ghostDisplayState = HideNodeSupport.resolveGhostDisplayState(blockEntity.getBlockState());
			if (ghostDisplayState != null) {
				HideGhostRenderSupport.renderGhostModel(minecraft, ghostDisplayState, poseStack, buffer, packedOverlay);
			}
		}
		areaRenderer.render(blockEntity, partialTick, poseStack, buffer, packedLight, packedOverlay);
	}

	@Override
	public int getViewDistance() {
		return areaRenderer.getViewDistance();
	}
}
