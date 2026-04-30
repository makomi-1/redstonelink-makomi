package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.client.config.RedstoneLinkClientDisplayConfig;
import com.makomi.data.LinkDispatchFilterService;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

/**
 * 过滤器作用域线框渲染器。
 */
public final class LinkFilterAreaRenderer<T extends AbstractLinkFilterBlockEntity>
	implements BlockEntityRenderer<T, LinkFilterAreaRenderer.LinkFilterAreaRenderState> {
	private static final float RED = 1.0F;
	private static final float GREEN = 0.22F;
	private static final float BLUE = 0.22F;
	private static final float FILL_ALPHA = 0.18F;
	private static final float TEXT_SCALE = 0.03F;
	private static final int BACKGROUND_COLOR = 0x80000000;
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final double BLOCK_TOP_TEXT_Y = 1.25D;
	private static final AABB FILTER_BOX = new AABB(
		-LinkDispatchFilterService.FILTER_RADIUS,
		-LinkDispatchFilterService.FILTER_RADIUS,
		-LinkDispatchFilterService.FILTER_RADIUS,
		LinkDispatchFilterService.FILTER_RADIUS + 1.0D,
		LinkDispatchFilterService.FILTER_RADIUS + 1.0D,
		LinkDispatchFilterService.FILTER_RADIUS + 1.0D
	);
	private final Font font;

	public LinkFilterAreaRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	@Override
	public LinkFilterAreaRenderState createRenderState() {
		return new LinkFilterAreaRenderState();
	}

	@Override
	public void extractRenderState(
		T blockEntity,
		LinkFilterAreaRenderState renderState,
		float partialTick,
		net.minecraft.world.phys.Vec3 cameraPosition,
		CrumblingOverlay crumblingOverlay
	) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, renderState, partialTick, cameraPosition, crumblingOverlay);
		renderState.displayText = "";
		renderState.renderArea = false;
		if (blockEntity == null || !RedstoneLinkClientDisplayConfig.overlay().farOverlayEnabled()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!SmartGlassesAccessSupport.canRenderSerialOverlay(minecraft.player)) {
			return;
		}
		double maxDistance = RedstoneLinkClientDisplayConfig.overlay().maxDistance();
		double centerX = blockEntity.getBlockPos().getX() + 0.5D;
		double centerY = blockEntity.getBlockPos().getY() + 0.5D;
		double centerZ = blockEntity.getBlockPos().getZ() + 0.5D;
		if (minecraft.player.distanceToSqr(centerX, centerY, centerZ) > maxDistance * maxDistance) {
			return;
		}
		renderState.renderArea = true;
		renderState.displayText = LinkSerialOverlayRenderCommon.resolveFilterDisplayText(blockEntity);
		renderState.textColor = LinkSerialOverlayRenderCommon.resolveFilterTextColor(blockEntity.filterKind());
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
		LinkFilterAreaRenderState renderState,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		CameraRenderState cameraRenderState
	) {
		if (renderState.renderArea) {
			submitNodeCollector.submitCustomGeometry(
				poseStack,
				RenderTypes.debugFilledBox(),
				(pose, vertexConsumer) -> renderFilterFill(pose, vertexConsumer)
			);
			submitNodeCollector.submitCustomGeometry(
				poseStack,
				RenderTypes.linesTranslucent(),
				(pose, vertexConsumer) -> renderFilterOutline(pose, vertexConsumer)
			);
		}
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
	 * 绘制过滤器影响域外显：
	 * 先绘制不穿墙半透明红色面层，再叠加原有线框轮廓。
	 */
	private static void renderFilterFill(
		PoseStack.Pose pose,
		VertexConsumer vertexConsumer
	) {
		float minX = (float) FILTER_BOX.minX;
		float minY = (float) FILTER_BOX.minY;
		float minZ = (float) FILTER_BOX.minZ;
		float maxX = (float) FILTER_BOX.maxX;
		float maxY = (float) FILTER_BOX.maxY;
		float maxZ = (float) FILTER_BOX.maxZ;

		addDoubleSidedQuad(vertexConsumer, pose, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
		addDoubleSidedQuad(vertexConsumer, pose, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ);
		addDoubleSidedQuad(vertexConsumer, pose, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ);
		addDoubleSidedQuad(vertexConsumer, pose, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
		addDoubleSidedQuad(vertexConsumer, pose, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
		addDoubleSidedQuad(vertexConsumer, pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ);
	}

	/**
	 * 按 12 条边显式写入过滤器范围线框。
	 */
	private static void renderFilterOutline(PoseStack.Pose pose, VertexConsumer vertexConsumer) {
		float minX = (float) FILTER_BOX.minX;
		float minY = (float) FILTER_BOX.minY;
		float minZ = (float) FILTER_BOX.minZ;
		float maxX = (float) FILTER_BOX.maxX;
		float maxY = (float) FILTER_BOX.maxY;
		float maxZ = (float) FILTER_BOX.maxZ;
		addLine(vertexConsumer, pose, minX, minY, minZ, maxX, minY, minZ);
		addLine(vertexConsumer, pose, maxX, minY, minZ, maxX, minY, maxZ);
		addLine(vertexConsumer, pose, maxX, minY, maxZ, minX, minY, maxZ);
		addLine(vertexConsumer, pose, minX, minY, maxZ, minX, minY, minZ);
		addLine(vertexConsumer, pose, minX, maxY, minZ, maxX, maxY, minZ);
		addLine(vertexConsumer, pose, maxX, maxY, minZ, maxX, maxY, maxZ);
		addLine(vertexConsumer, pose, maxX, maxY, maxZ, minX, maxY, maxZ);
		addLine(vertexConsumer, pose, minX, maxY, maxZ, minX, maxY, minZ);
		addLine(vertexConsumer, pose, minX, minY, minZ, minX, maxY, minZ);
		addLine(vertexConsumer, pose, maxX, minY, minZ, maxX, maxY, minZ);
		addLine(vertexConsumer, pose, maxX, minY, maxZ, maxX, maxY, maxZ);
		addLine(vertexConsumer, pose, minX, minY, maxZ, minX, maxY, maxZ);
	}

	/**
	 * 按双面四边形顺序写入单个面顶点。
	 * <p>
	 * 1.21.11 下范围面层会受背面剔除影响，直接单面提交会在不同观察角度缺面，
	 * 因此这里显式补交一份反向 winding，保证过滤器范围体始终完整。
	 * </p>
	 */
	private static void addDoubleSidedQuad(
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
		addColoredVertex(vertexConsumer, pose, x4, y4, z4);
		addColoredVertex(vertexConsumer, pose, x3, y3, z3);
		addColoredVertex(vertexConsumer, pose, x2, y2, z2);
		addColoredVertex(vertexConsumer, pose, x1, y1, z1);
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

	/**
	 * 写入单条线框边。
	 */
	private static void addLine(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		float startX,
		float startY,
		float startZ,
		float endX,
		float endY,
		float endZ
	) {
		float deltaX = endX - startX;
		float deltaY = endY - startY;
		float deltaZ = endZ - startZ;
		float length = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
		float normalX = length <= 0.0F ? 1.0F : deltaX / length;
		float normalY = length <= 0.0F ? 0.0F : deltaY / length;
		float normalZ = length <= 0.0F ? 0.0F : deltaZ / length;
		vertexConsumer
			.addVertex(pose, startX, startY, startZ)
			.setColor(RED, GREEN, BLUE, 1.0F)
			.setNormal(pose, normalX, normalY, normalZ)
			.setLineWidth(1.0F);
		vertexConsumer
			.addVertex(pose, endX, endY, endZ)
			.setColor(RED, GREEN, BLUE, 1.0F)
			.setNormal(pose, normalX, normalY, normalZ)
			.setLineWidth(1.0F);
	}

	/**
	 * 将文本颜色替换为指定透明度，保持原有 RGB 不变。
	 */
	private static int withAlpha(int color, int alpha) {
		return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
	}

	/**
	 * 过滤器范围远外显渲染状态。
	 */
	public static final class LinkFilterAreaRenderState extends BlockEntityRenderState {
		private boolean renderArea;
		private String displayText = "";
		private int textColor = 0xFFFFFFFF;
	}
}
