package com.makomi.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏节点 ghost 模型渲染支撑。
 * <p>
 * 统一使用固定透明度，把普通方块模型压成智能眼镜下的半透明幽灵层。
 * </p>
 */
final class HideGhostRenderSupport {
	private static final int FULL_BRIGHT = 0x00F000F0;
	private static final float GHOST_ALPHA_SCALE = 0.42F;

	private HideGhostRenderSupport() {
	}

	/**
	 * 使用固定透明度渲染普通节点模型，形成智能眼镜下的 ghost 层。
	 */
	static void renderGhostModel(
		Minecraft minecraft,
		BlockState ghostDisplayState,
		PoseStack.Pose pose,
		VertexConsumer vertexConsumer,
		int packedOverlay
	) {
		if (minecraft == null || ghostDisplayState == null || pose == null || vertexConsumer == null) {
			return;
		}

		BlockStateModelSet blockStateModelSet = minecraft.getModelManager().getBlockStateModelSet();
		BlockStateModel blockStateModel = blockStateModelSet.get(ghostDisplayState);
		if (blockStateModel == null) {
			return;
		}

		MovingBlockRenderState movingBlockState = new MovingBlockRenderState();
		configureMovingBlockState(minecraft, movingBlockState, ghostDisplayState);
		List<BlockStateModelPart> ghostModelParts = new ArrayList<>();
		RandomSource ghostRandom = RandomSource.create(42L);
		long seed = ghostDisplayState.getSeed(movingBlockState.randomSeedPos);
		ghostRandom.setSeed(seed);
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
			movingBlockState,
			movingBlockState.blockPos,
			ghostDisplayState,
			blockStateModel,
			seed
		);
	}

	/**
	 * 复用 vanilla moving block 取色/光照上下文，确保 tint 和图层选择与普通方块一致。
	 */
	private static void configureMovingBlockState(
		Minecraft minecraft,
		MovingBlockRenderState movingBlockState,
		BlockState ghostDisplayState
	) {
		movingBlockState.blockState = ghostDisplayState;
		movingBlockState.blockPos = net.minecraft.core.BlockPos.ZERO;
		movingBlockState.randomSeedPos = net.minecraft.core.BlockPos.ZERO;
		if (minecraft.level != null) {
			movingBlockState.biome = minecraft.level.getBiome(net.minecraft.core.BlockPos.ZERO);
			movingBlockState.cardinalLighting = minecraft.level.cardinalLighting();
			movingBlockState.lightEngine = minecraft.level.getLightEngine();
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
}
