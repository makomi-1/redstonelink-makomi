package com.makomi.client.render;

import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * 区块激活器远外显渲染器。
 * <p>
 * 区块激活器没有序号，因此 far overlay 改为显示“别名优先，空别名回退标题”。
 * </p>
 */
public final class ChunkActivatorFarOverlayRenderer implements BlockEntityRenderer<LinkChunkActivatorBlockEntity> {
	private static final float TEXT_SCALE = 0.03F;
	private static final int BACKGROUND_COLOR = 0x80000000;
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final double BLOCK_TOP_TEXT_Y = 1.25D;
	private static final float FOREGROUND_Z_BIAS = 0.5F;

	private final Font font;

	public ChunkActivatorFarOverlayRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.getFont();
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
		if (!RedstoneLinkClientDisplayConfig.overlay().farOverlayEnabled()) {
			return;
		}
		if (!SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)) {
			return;
		}

		String displayText = LinkSerialOverlayRenderCommon.resolveChunkActivatorDisplayText(blockEntity);
		if (displayText.isEmpty()) {
			return;
		}

		double maxDistance = RedstoneLinkClientDisplayConfig.overlay().maxDistance();
		if (!LinkSerialOverlayRenderCommon.isWithinDisplayDistance(minecraft, blockEntity, maxDistance)) {
			return;
		}

		int textColor = LinkSerialOverlayRenderCommon.resolveChunkActivatorTextColor();
		int backgroundGlyphColor = withAlpha(textColor, 0x00);
		Font.DisplayMode foregroundDisplayMode = RedstoneLinkClientDisplayConfig.overlay().farSeeThrough()
			? Font.DisplayMode.SEE_THROUGH
			: Font.DisplayMode.POLYGON_OFFSET;

		poseStack.pushPose();
		poseStack.translate(0.5D, BLOCK_TOP_TEXT_Y, 0.5D);
		poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
		float textScale = TEXT_SCALE * RedstoneLinkClientDisplayConfig.overlay().fontScale();
		poseStack.scale(textScale, -textScale, textScale);

		float textStartX = -font.width(displayText) / 2.0F;
		float textStartY = -font.lineHeight / 2.0F;
		font.drawInBatch(
			displayText,
			textStartX,
			textStartY,
			backgroundGlyphColor,
			false,
			poseStack.last().pose(),
			buffer,
			Font.DisplayMode.POLYGON_OFFSET,
			BACKGROUND_COLOR,
			FULL_BRIGHT
		);
		poseStack.pushPose();
		poseStack.translate(0.0D, 0.0D, FOREGROUND_Z_BIAS);
		font.drawInBatch(
			displayText,
			textStartX,
			textStartY,
			textColor,
			false,
			poseStack.last().pose(),
			buffer,
			foregroundDisplayMode,
			0,
			FULL_BRIGHT
		);
		poseStack.popPose();
		poseStack.popPose();
	}

	@Override
	public int getViewDistance() {
		return RedstoneLinkClientDisplayConfig.overlay().maxDistance();
	}

	/**
	 * 将文本颜色替换为指定透明度，保持原有 RGB 不变。
	 */
	private static int withAlpha(int color, int alpha) {
		return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
	}
}
