package com.makomi.client.render;

import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * 节点序号外显渲染器。
 * <p>
 * 统一在客户端渲染序号文本，供 core/triggerSource 节点复用。
 * </p>
 */
public final class LinkNodeFarOverlayRenderer<T extends PairableNodeBlockEntity>
	implements BlockEntityRenderer<T, LinkNodeFarOverlayRenderer.LinkNodeFarOverlayRenderState> {
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

	private final Font font;

	public LinkNodeFarOverlayRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	@Override
	public LinkNodeFarOverlayRenderState createRenderState() {
		return new LinkNodeFarOverlayRenderState();
	}

	@Override
	public void extractRenderState(
		T blockEntity,
		LinkNodeFarOverlayRenderState renderState,
		float partialTick,
		net.minecraft.world.phys.Vec3 cameraPosition,
		CrumblingOverlay crumblingOverlay
	) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, renderState, partialTick, cameraPosition, crumblingOverlay);
		renderState.displayText = "";
		renderState.outwardDirection = null;
		if (blockEntity == null || !RedstoneLinkClientDisplayConfig.overlay().farOverlayEnabled()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)) {
			return;
		}

		String serialText = LinkSerialOverlayRenderCommon.resolveDisplaySerialText(blockEntity);
		if (serialText.isEmpty()) {
			return;
		}
		LinkNodeType nodeType = blockEntity.getLinkNodeType();
		renderState.displayText = serialText;
		renderState.textColor = blockEntity instanceof LinkRepeaterBlockEntity
			? LinkSerialOverlayRenderCommon.resolveRepeaterTextColor()
			: LinkSerialOverlayRenderCommon.resolveNodeTextColor(nodeType);

		int maxDistance = RedstoneLinkClientDisplayConfig.overlay().maxDistance();
		if (!LinkSerialOverlayRenderCommon.isWithinDisplayDistance(minecraft, blockEntity, maxDistance)) {
			renderState.displayText = "";
			return;
		}
		renderState.outwardDirection = resolveOverlayOutwardDirection(blockEntity.getBlockState());
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
		LinkNodeFarOverlayRenderState renderState,
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
		if (renderState.outwardDirection != null) {
			applyFaceAnchoredBillboardTransform(poseStack, cameraRenderState, renderState.outwardDirection);
		} else {
			applyTopBillboardTransform(poseStack, cameraRenderState);
		}
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
	 * 对贴附类节点应用“贴面定位 + 面向摄像头”的统一变换。
	 * <p>
	 * 贴附方向只决定文本锚点所在的方块表面；
	 * 文本板本身始终与其他节点保持一致，统一朝向摄像头。
	 * </p>
	 */
	private static void applyFaceAnchoredBillboardTransform(
		PoseStack poseStack,
		CameraRenderState cameraRenderState,
		Direction outward
	) {
		poseStack.translate(
			0.5D + outward.getStepX() * (0.5D + FACE_OFFSET),
			0.5D + outward.getStepY() * (0.5D + FACE_OFFSET),
			0.5D + outward.getStepZ() * (0.5D + FACE_OFFSET)
		);
		poseStack.mulPose(cameraRenderState.orientation);
	}

	/**
	 * 对完整体节点沿用“顶部公告牌”式显示，避免被实体体积遮挡。
	 */
	private static void applyTopBillboardTransform(PoseStack poseStack, CameraRenderState cameraRenderState) {
		poseStack.translate(0.5D, BLOCK_TOP_TEXT_Y, 0.5D);
		poseStack.mulPose(cameraRenderState.orientation);
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

	/**
	 * 节点远外显渲染状态。
	 */
	public static final class LinkNodeFarOverlayRenderState extends BlockEntityRenderState {
		private String displayText = "";
		private int textColor = 0xFFFFFFFF;
		private Direction outwardDirection;
	}
}
