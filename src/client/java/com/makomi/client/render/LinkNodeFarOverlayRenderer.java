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
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * 节点序号外显渲染器。
 * <p>
 * 统一在客户端渲染序号文本，供 core/triggerSource 节点复用。
 * </p>
 */
public final class LinkNodeFarOverlayRenderer<T extends PairableNodeBlockEntity> implements BlockEntityRenderer<T> {
	private static final float TEXT_SCALE = 0.03F;
	private static final int BACKGROUND_COLOR = 0x80000000;
	private static final int FULL_BRIGHT = 0x00F000F0;
	/**
	 * 贴附类节点文本锚点相对方块中心的默认外推距离。
	 * <p>
	 * 以方块中心为原点时，`0D` 表示锚点会落在附着方块表面外再额外推出 0.5 格。
	 * </p>
	 */
	private static final double FACE_OFFSET = 0D;
	private static final double BLOCK_TOP_TEXT_Y = 1.25D;
	/**
	 * 前景文字相对背景的微小 Z 偏移，避免同层渲染时出现背景压字。
	 */
	private static final float FOREGROUND_Z_BIAS = 0.5F;

	private final Font font;

	public LinkNodeFarOverlayRenderer(BlockEntityRendererProvider.Context context) {
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
		if (!RedstoneLinkClientDisplayConfig.overlay().farOverlayEnabled()) {
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
		int maxDistance = RedstoneLinkClientDisplayConfig.overlay().maxDistance();
		if (!LinkSerialOverlayRenderCommon.isWithinDisplayDistance(minecraft, blockEntity, maxDistance)) {
			return;
		}

		BlockState blockState = blockEntity.getBlockState();

		poseStack.pushPose();
		Direction outward = resolveOverlayOutwardDirection(blockState);
		if (outward != null) {
			applyFaceAnchoredBillboardTransform(poseStack, minecraft, outward);
		} else {
			applyTopBillboardTransform(poseStack, minecraft);
		}

		float textScale = TEXT_SCALE * RedstoneLinkClientDisplayConfig.overlay().fontScale();
		poseStack.scale(textScale, -textScale, textScale);

		float textStartX = -font.width(displayText) / 2.0F;
		// 让文本框围绕锚点整体居中，避免底面/顶面因局部原点偏在左上角而出现视觉漂移。
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
		Font.DisplayMode foregroundDisplayMode = RedstoneLinkClientDisplayConfig.overlay().farSeeThrough()
			? Font.DisplayMode.SEE_THROUGH
			: Font.DisplayMode.POLYGON_OFFSET;
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

	/**
	 * 对贴附类节点应用“贴面定位 + 面向摄像头”的统一变换。
	 * <p>
	 * 贴附方向只决定文本锚点所在的方块表面；
	 * 文本板本身始终与其他节点保持一致，统一朝向摄像头。
	 * </p>
	 */
	private static void applyFaceAnchoredBillboardTransform(
		PoseStack poseStack,
		Minecraft minecraft,
		Direction outward
	) {
		poseStack.translate(
			0.5D + outward.getStepX() * FACE_OFFSET,
			0.5D + outward.getStepY() * FACE_OFFSET,
			0.5D + outward.getStepZ() * FACE_OFFSET
		);
		poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
	}

	/**
	 * 对完整体节点沿用“顶部公告牌”式显示，避免被实体体积遮挡。
	 */
	private static void applyTopBillboardTransform(PoseStack poseStack, Minecraft minecraft) {
		poseStack.translate(0.5D, BLOCK_TOP_TEXT_Y, 0.5D);
		poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
	}

	/**
	 * 解析远外显文本框的朝外法线。
	 * <p>
	 * 支持两类需要贴面显示的节点：
	 * 1. 核心粉/透明核心粉：使用 `SUPPORT_FACE`；
	 * 2. 按钮/拉杆：使用 `FACE + FACING` 直接推导朝外方向。
	 * </p>
	 */
	private static Direction resolveOverlayOutwardDirection(BlockState blockState) {
		if (blockState == null) {
			return null;
		}
		if (blockState.hasProperty(LinkRedstoneDustCoreBlock.SUPPORT_FACE)) {
			return blockState.getValue(LinkRedstoneDustCoreBlock.SUPPORT_FACE).getOpposite();
		}
		if (
			blockState.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACE)
				&& blockState.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACING)
		) {
			AttachFace attachFace = blockState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
			return switch (attachFace) {
				case FLOOR -> Direction.UP;
				case CEILING -> Direction.DOWN;
				case WALL -> blockState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
			};
		}
		return null;
	}
}
