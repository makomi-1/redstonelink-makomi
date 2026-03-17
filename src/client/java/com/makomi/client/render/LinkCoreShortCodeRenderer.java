package com.makomi.client.render;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkNodeType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 节点序号外显渲染器。
 * <p>
 * 统一在客户端渲染序号文本，供 core/triggerSource 节点复用。
 * </p>
 */
public final class LinkCoreShortCodeRenderer<T extends PairableNodeBlockEntity> implements BlockEntityRenderer<T> {
	private static final float TEXT_SCALE = 0.03F;
	private static final int BACKGROUND_COLOR = 0x80000000;
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final double FACE_OFFSET = 0.80D;
	private static final double BLOCK_TOP_TEXT_Y = 1.25D;
	/**
	 * 前景文字相对背景的微小 Z 偏移，避免同层渲染时出现背景压字。
	 */
	private static final float FOREGROUND_Z_BIAS = 0.5F;

	private final Font font;

	public LinkCoreShortCodeRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.getFont();
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
		if (!RedstoneLinkClientDisplayConfig.isFarOverlayEnabled()) {
			return;
		}

		String serialText = LinkSerialOverlayRenderCommon.resolveDisplaySerialText(blockEntity);
		if (serialText.isEmpty()) {
			return;
		}
		LinkNodeType nodeType = blockEntity.getLinkNodeType();
		String displayText = serialText;
		int textColor = LinkSerialOverlayRenderCommon.resolveNodeTextColor(nodeType);
		int backgroundGlyphColor = withAlpha(textColor, 0x00);

		Minecraft minecraft = Minecraft.getInstance();
		int maxDistance = RedstoneLinkClientDisplayConfig.serialOverlayMaxDistance();
		if (!LinkSerialOverlayRenderCommon.isWithinDisplayDistance(minecraft, blockEntity, maxDistance)) {
			return;
		}

		BlockState blockState = blockEntity.getBlockState();

		poseStack.pushPose();
		if (blockState.hasProperty(LinkRedstoneDustCoreBlock.SUPPORT_FACE)) {
			Direction supportFace = blockState.getValue(LinkRedstoneDustCoreBlock.SUPPORT_FACE);
			Direction outward = supportFace.getOpposite();
			poseStack.translate(
				0.5D + outward.getStepX() * FACE_OFFSET,
				0.5D + outward.getStepY() * FACE_OFFSET,
				0.5D + outward.getStepZ() * FACE_OFFSET
			);
		} else {
			// 对完整方块（发射器/核心块）固定显示在顶边上方一点，避免贴脸遮挡。
			poseStack.translate(0.5D, BLOCK_TOP_TEXT_Y, 0.5D);
		}
		poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());

		float textScale = TEXT_SCALE * RedstoneLinkClientDisplayConfig.serialOverlayFontScale();
		poseStack.scale(textScale, -textScale, textScale);

		float textStartX = -font.width(displayText) / 2.0F;
		font.drawInBatch(
			displayText,
			textStartX,
			0.0F,
			backgroundGlyphColor,
			false,
			poseStack.last().pose(),
			buffer,
			Font.DisplayMode.POLYGON_OFFSET,
			BACKGROUND_COLOR,
			FULL_BRIGHT
		);
		Font.DisplayMode foregroundDisplayMode = RedstoneLinkClientDisplayConfig.isFarOverlaySeeThroughEnabled()
			? Font.DisplayMode.SEE_THROUGH
			: Font.DisplayMode.POLYGON_OFFSET;
		poseStack.pushPose();
		poseStack.translate(0.0D, 0.0D, FOREGROUND_Z_BIAS);
		font.drawInBatch(
			displayText,
			textStartX,
			0.0F,
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
		return RedstoneLinkClientDisplayConfig.serialOverlayMaxDistance();
	}

	/**
	 * 将文本颜色替换为指定透明度，保持原有 RGB 不变。
	 */
	private static int withAlpha(int color, int alpha) {
		return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
	}
}
