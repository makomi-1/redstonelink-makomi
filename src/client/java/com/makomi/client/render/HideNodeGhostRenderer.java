package com.makomi.client.render;

import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.HideNodeSupport;
import com.makomi.data.SmartGlassesAccessSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏节点幽灵模型渲染器。
 * <p>
 * 1.21.11 已切到 submit 管线，因此这里在渲染状态中携带“幽灵方块状态 + 远外显状态”，
 * 提交阶段先压一层半透明普通模型，再复用既有远外显文本渲染器。
 * </p>
 */
public final class HideNodeGhostRenderer<T extends PairableNodeBlockEntity>
	implements BlockEntityRenderer<T, HideNodeGhostRenderer.HideNodeGhostRenderState> {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final float GHOST_ALPHA_SCALE = 0.42F;
	private static final BlockDisplayContext GHOST_DISPLAY_CONTEXT = BlockDisplayContext.create();

	private final LinkNodeFarOverlayRenderer<T> farOverlayRenderer;
	private final BlockModelResolver blockModelResolver;
	private final MovingBlockRenderState ghostMovingBlockState;
	private final List<BlockStateModelPart> ghostModelParts;
	private final RandomSource ghostRandom;

	public HideNodeGhostRenderer(BlockEntityRendererProvider.Context context) {
		this.farOverlayRenderer = new LinkNodeFarOverlayRenderer<>(context);
		this.blockModelResolver = context.blockModelResolver();
		this.ghostMovingBlockState = new MovingBlockRenderState();
		this.ghostModelParts = new ArrayList<>();
		this.ghostRandom = RandomSource.create(42L);
	}

	@Override
	public HideNodeGhostRenderState createRenderState() {
		return new HideNodeGhostRenderState(farOverlayRenderer.createRenderState());
	}

	@Override
	public void extractRenderState(
		T blockEntity,
		HideNodeGhostRenderState renderState,
		float partialTick,
		net.minecraft.world.phys.Vec3 cameraPosition,
		CrumblingOverlay crumblingOverlay
	) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, renderState, partialTick, cameraPosition, crumblingOverlay);
		renderState.ghostDisplayState = null;
		renderState.ghostModelState.clear();
		farOverlayRenderer.extractRenderState(blockEntity, renderState.farOverlayState, partialTick, cameraPosition, crumblingOverlay);
		if (blockEntity == null) {
			return;
		}
		if (net.minecraft.client.Minecraft.getInstance().player == null) {
			return;
		}
		if (!SmartGlassesAccessSupport.canRenderSerialOverlay(net.minecraft.client.Minecraft.getInstance().player)) {
			return;
		}
		renderState.ghostDisplayState = HideNodeSupport.resolveGhostDisplayState(blockEntity.getBlockState());
		if (renderState.ghostDisplayState != null) {
			blockModelResolver.update(renderState.ghostModelState, renderState.ghostDisplayState, GHOST_DISPLAY_CONTEXT);
		}
	}

	@Override
	public void submit(
		HideNodeGhostRenderState renderState,
		PoseStack poseStack,
		SubmitNodeCollector submitNodeCollector,
		CameraRenderState cameraRenderState
	) {
		if (renderState.ghostDisplayState != null && !renderState.ghostModelState.isEmpty()) {
			// 26.1 里 `BlockModelRenderState.submit(..., int)` 的最后一个参数已经是 outlineColor，
			// 继续传白色 ARGB 会把 hide 节点渲染成白色高亮。这里改回“保留贴图 RGB、仅压低 alpha”的幽灵提交流程。
			submitNodeCollector.submitCustomGeometry(
				poseStack,
				RenderTypes.translucentMovingBlock(),
				(pose, vertexConsumer) -> renderGhostModel(renderState, pose, vertexConsumer)
			);
		}
		farOverlayRenderer.submit(renderState.farOverlayState, poseStack, submitNodeCollector, cameraRenderState);
	}

	@Override
	public int getViewDistance() {
		return farOverlayRenderer.getViewDistance();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return farOverlayRenderer.shouldRenderOffScreen();
	}

	/**
	 * 按 26.1 当前方块模型管线手动铺四边形，仅缩减 alpha，不改原贴图 RGB。
	 */
	private void renderGhostModel(HideNodeGhostRenderState renderState, PoseStack.Pose pose, VertexConsumer vertexConsumer) {
		if (renderState == null || renderState.ghostDisplayState == null || pose == null || vertexConsumer == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}

		BlockState ghostDisplayState = renderState.ghostDisplayState;
		BlockStateModelSet blockStateModelSet = minecraft.getModelManager().getBlockStateModelSet();
		BlockStateModel blockStateModel = blockStateModelSet.get(ghostDisplayState);
		if (blockStateModel == null) {
			return;
		}

		configureGhostMovingBlockState(ghostDisplayState);
		ghostModelParts.clear();
		try {
			ghostRandom.setSeed(ghostDisplayState.getSeed(ghostMovingBlockState.randomSeedPos));
			blockStateModel.collectParts(ghostRandom, ghostModelParts);
			if (ghostModelParts.isEmpty()) {
				return;
			}

			BlockQuadOutput quadOutput = (offsetX, offsetY, offsetZ, bakedQuad, quadInstance) ->
				putGhostBakedQuad(pose, vertexConsumer, bakedQuad, quadInstance);
			ModelBlockRenderer modelRenderer = new ModelBlockRenderer(resolveAmbientOcclusionEnabled(minecraft), false, minecraft.getBlockColors());
			modelRenderer.tesselateBlock(
				quadOutput,
				1.0F,
				1.0F,
				1.0F,
				ghostMovingBlockState,
				ghostMovingBlockState.blockPos,
				ghostDisplayState,
				blockStateModel,
				ghostDisplayState.getSeed(ghostMovingBlockState.randomSeedPos)
			);
		} finally {
			ghostModelParts.clear();
		}
	}

	/**
	 * 复用 vanilla moving block 取色/光照上下文，确保 tint 和图层选择与普通方块一致。
	 */
	private void configureGhostMovingBlockState(BlockState ghostDisplayState) {
		ghostMovingBlockState.blockState = ghostDisplayState;
		ghostMovingBlockState.blockPos = net.minecraft.core.BlockPos.ZERO;
		ghostMovingBlockState.randomSeedPos = net.minecraft.core.BlockPos.ZERO;
		if (Minecraft.getInstance().level != null) {
			ghostMovingBlockState.biome = Minecraft.getInstance().level.getBiome(net.minecraft.core.BlockPos.ZERO);
			ghostMovingBlockState.cardinalLighting = Minecraft.getInstance().level.cardinalLighting();
			ghostMovingBlockState.lightEngine = Minecraft.getInstance().level.getLightEngine();
		}
	}

	/**
	 * 26.1 的方块模型烘焙仍受 AO 选项影响，保持与玩家当前客户端设置一致。
	 */
	private static boolean resolveAmbientOcclusionEnabled(Minecraft minecraft) {
		return minecraft != null && minecraft.options != null && Boolean.TRUE.equals(minecraft.options.ambientOcclusion().get());
	}

	/**
	 * 把 vanilla 算好的顶点色仅压缩 alpha，避免再次走 outline/tint 语义。
	 */
	private static void putGhostBakedQuad(
		PoseStack.Pose pose,
		VertexConsumer vertexConsumer,
		net.minecraft.client.resources.model.geometry.BakedQuad bakedQuad,
		QuadInstance quadInstance
	) {
		QuadInstance ghostQuadInstance = new QuadInstance();
		ghostQuadInstance.setOverlayCoords(quadInstance.overlayCoords());
		for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
			ghostQuadInstance.setLightCoords(vertexIndex, quadInstance.getLightCoords(vertexIndex));
			ghostQuadInstance.setColor(vertexIndex, ARGB.multiplyAlpha(quadInstance.getColor(vertexIndex), GHOST_ALPHA_SCALE));
		}
		vertexConsumer.putBakedQuad(pose, bakedQuad, ghostQuadInstance);
	}

	/**
	 * 隐藏节点渲染状态。
	 */
	public static final class HideNodeGhostRenderState extends BlockEntityRenderState {
		private final LinkNodeFarOverlayRenderer.LinkNodeFarOverlayRenderState farOverlayState;
		private final BlockModelRenderState ghostModelState;
		private BlockState ghostDisplayState;

		private HideNodeGhostRenderState(LinkNodeFarOverlayRenderer.LinkNodeFarOverlayRenderState farOverlayState) {
			this.farOverlayState = farOverlayState;
			this.ghostModelState = new BlockModelRenderState();
		}
	}
}
