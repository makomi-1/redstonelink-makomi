package com.makomi.client.render;

import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;

/**
 * 区块激活器远外显渲染器。
 * <p>
 * 区块激活器没有序号，因此 far overlay 改为显示“别名优先，空别名回退标题”。
 * </p>
 */
public final class ChunkActivatorFarOverlayRenderer
	implements BlockEntityRenderer<LinkChunkActivatorBlockEntity, ChunkActivatorFarOverlayRenderer.ChunkActivatorFarOverlayRenderState> {
	private static final float TEXT_SCALE = 0.03F;
	private static final int BACKGROUND_COLOR = 0x80000000;
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final double BLOCK_TOP_TEXT_Y = 1.25D;

	private final Font font;

	public ChunkActivatorFarOverlayRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	@Override
	public ChunkActivatorFarOverlayRenderState createRenderState() {
		return new ChunkActivatorFarOverlayRenderState();
	}

	@Override
	public void extractRenderState(
		LinkChunkActivatorBlockEntity blockEntity,
		ChunkActivatorFarOverlayRenderState renderState,
		float partialTick,
		net.minecraft.world.phys.Vec3 cameraPosition,
		CrumblingOverlay crumblingOverlay
	) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, renderState, partialTick, cameraPosition, crumblingOverlay);
		renderState.displayText = "";
		if (blockEntity == null || !RedstoneLinkClientDisplayConfig.overlay().farOverlayEnabled()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
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
		renderState.displayText = displayText;
		renderState.textColor = LinkSerialOverlayRenderCommon.resolveChunkActivatorTextColor();
	}

	@Override
	public int getViewDistance() {
		return RedstoneLinkClientDisplayConfig.overlay().maxDistance();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public void submit(
		ChunkActivatorFarOverlayRenderState renderState,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		CameraRenderState cameraRenderState
	) {
		if (renderState.displayText.isEmpty() || cameraRenderState == null || cameraRenderState.orientation == null) {
			return;
		}
		int backgroundGlyphColor = withAlpha(renderState.textColor, 0x00);
		Font.DisplayMode foregroundDisplayMode = RedstoneLinkClientDisplayConfig.overlay().farSeeThrough()
			? Font.DisplayMode.SEE_THROUGH
			: Font.DisplayMode.POLYGON_OFFSET;
		float textScale = TEXT_SCALE * RedstoneLinkClientDisplayConfig.overlay().fontScale();
		float textStartX = -font.width(renderState.displayText) / 2.0F;
		float textStartY = -font.lineHeight / 2.0F;
		var visualText = Component.literal(renderState.displayText).getVisualOrderText();

		poseStack.pushPose();
		poseStack.translate(0.5D, BLOCK_TOP_TEXT_Y, 0.5D);
		poseStack.mulPose(cameraRenderState.orientation);
		poseStack.scale(textScale, -textScale, textScale);
		submitNodeCollector.submitText(
			poseStack,
			textStartX,
			textStartY,
			visualText,
			false,
			Font.DisplayMode.POLYGON_OFFSET,
			FULL_BRIGHT,
			backgroundGlyphColor,
			BACKGROUND_COLOR,
			0
		);
		submitNodeCollector.submitText(
			poseStack,
			textStartX,
			textStartY,
			visualText,
			false,
			foregroundDisplayMode,
			FULL_BRIGHT,
			renderState.textColor,
			0,
			0
		);
		poseStack.popPose();
	}

	/**
	 * 将文本颜色替换为指定透明度，保持原有 RGB 不变。
	 */
	private static int withAlpha(int color, int alpha) {
		return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
	}

	/**
	 * 区块激活器远外显渲染状态。
	 */
	public static final class ChunkActivatorFarOverlayRenderState extends BlockEntityRenderState {
		private String displayText = "";
		private int textColor = 0xFFFFFFFF;
	}
}
