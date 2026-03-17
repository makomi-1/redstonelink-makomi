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
	private static final int CORE_TEXT_COLOR = 0xFF5DD7FF;
	private static final int TRIGGER_SOURCE_TEXT_COLOR = 0xFFFFC66A;
	private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
	private static final int SEE_THROUGH_ALPHA = 0x20;
	private static final int BACKGROUND_COLOR = 0x40000000;
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final double FACE_OFFSET = 0.56D;
	private static final double BLOCK_TOP_TEXT_Y = 1.25D;

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

		String serialText = blockEntity.getSerialDisplayText();
		if (serialText.isEmpty()) {
			return;
		}
		LinkNodeType nodeType = blockEntity.getLinkNodeType();
		String displayText = serialText;
		int textColor = resolveNodeTextColor(nodeType);
		int seeThroughColor = withAlpha(textColor, SEE_THROUGH_ALPHA);

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		int maxDistance = RedstoneLinkClientDisplayConfig.serialOverlayMaxDistance();
		double maxDistanceSqr = (double) maxDistance * maxDistance;
		double centerX = blockEntity.getBlockPos().getX() + 0.5D;
		double centerY = blockEntity.getBlockPos().getY() + 0.5D;
		double centerZ = blockEntity.getBlockPos().getZ() + 0.5D;
		if (minecraft.player.distanceToSqr(centerX, centerY, centerZ) > maxDistanceSqr) {
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
			seeThroughColor,
			false,
			poseStack.last().pose(),
			buffer,
			Font.DisplayMode.SEE_THROUGH,
			BACKGROUND_COLOR,
			FULL_BRIGHT
		);
		font.drawInBatch(
			displayText,
			textStartX,
			0.0F,
			textColor,
			false,
			poseStack.last().pose(),
			buffer,
			Font.DisplayMode.NORMAL,
			0,
			FULL_BRIGHT
		);

		poseStack.popPose();
	}

	@Override
	public int getViewDistance() {
		return RedstoneLinkClientDisplayConfig.serialOverlayMaxDistance();
	}

	/**
	 * 按节点类型返回固定文本颜色，用于不同类型外显快速区分。
	 */
	public static int resolveNodeTextColor(LinkNodeType nodeType) {
		if (nodeType == LinkNodeType.CORE) {
			return CORE_TEXT_COLOR;
		}
		if (nodeType == LinkNodeType.TRIGGER_SOURCE) {
			return TRIGGER_SOURCE_TEXT_COLOR;
		}
		return DEFAULT_TEXT_COLOR;
	}

	/**
	 * 将文本颜色替换为指定透明度，保持原有 RGB 不变。
	 */
	private static int withAlpha(int color, int alpha) {
		return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
	}
}
