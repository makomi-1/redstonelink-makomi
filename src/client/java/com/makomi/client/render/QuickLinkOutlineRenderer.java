package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.util.SerialParseUtil;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 快速连接工具命中描边与缓存外显渲染器。
 */
public final class QuickLinkOutlineRenderer {
	private static final OutlineColor CORE_OUTLINE_COLOR = OutlineColor.fromPackedColor(
		LinkSerialOverlayRenderCommon.resolveNodeTextColor(LinkNodeType.CORE)
	);
	private static final OutlineColor TRIGGER_SOURCE_OUTLINE_COLOR = OutlineColor.fromPackedColor(
		LinkSerialOverlayRenderCommon.resolveNodeTextColor(LinkNodeType.TRIGGER_SOURCE)
	);
	private static final OutlineColor REPEATER_OUTLINE_COLOR = OutlineColor.fromPackedColor(
		LinkSerialOverlayRenderCommon.resolveRepeaterTextColor()
	);
	private static final OutlineColor FILTER_OUTLINE_COLOR = new OutlineColor(1.0F, 0.16F, 0.16F);
	private static final int PREVIEW_CACHE_TTL_TICKS = 6;
	private static final RenderType QUICK_LINK_PREVIEW_RENDER_TYPE = RenderType.create(
		"redstonelink_quick_link_preview_lines",
		DefaultVertexFormat.POSITION_COLOR_NORMAL,
		VertexFormat.Mode.LINES,
		1536,
		false,
		true,
		RenderType.CompositeState
			.builder()
			.setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
			.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
			.setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
			.setCullState(RenderStateShard.NO_CULL)
			.setWriteMaskState(RenderStateShard.COLOR_WRITE)
			.setOutputState(RenderStateShard.TRANSLUCENT_TARGET)
			.setLineState(RenderStateShard.DEFAULT_LINE)
			.createCompositeState(false)
	);
	private static CachedPreviewOutlineState cachedPreviewOutlineState;

	private QuickLinkOutlineRenderer() {
	}

	/**
	 * 在默认方块描边阶段绘制 quick-link 自定义描边。
	 *
	 * @return `false` 表示已自行渲染并取消默认白色描边；其余情况保持默认行为
	 */
	public static boolean onBlockOutline(
		WorldRenderContext worldRenderContext,
		WorldRenderContext.BlockOutlineContext blockOutlineContext
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return true;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return true;
		}
		OutlineColor outlineColor = resolveOutlineColor(minecraft, blockOutlineContext.blockPos());
		if (outlineColor == null) {
			return true;
		}

		if (worldRenderContext.matrixStack() == null || worldRenderContext.consumers() == null) {
			return true;
		}

		VoxelShape voxelShape = blockOutlineContext.blockState().getShape(minecraft.level, blockOutlineContext.blockPos());
		VertexConsumer lineVertexConsumer = worldRenderContext.consumers().getBuffer(RenderType.lines());
		LevelRenderer.renderVoxelShape(
			worldRenderContext.matrixStack(),
			lineVertexConsumer,
			voxelShape,
			(double) blockOutlineContext.blockPos().getX() - blockOutlineContext.cameraX(),
			(double) blockOutlineContext.blockPos().getY() - blockOutlineContext.cameraY(),
			(double) blockOutlineContext.blockPos().getZ() - blockOutlineContext.cameraZ(),
			outlineColor.red(),
			outlineColor.green(),
			outlineColor.blue(),
			1.0F,
			false
		);
		return false;
	}

	/**
	 * 在半透明阶段后额外绘制 quick-link 缓存对象的穿墙线框外显。
	 */
	private static void onAfterTranslucent(WorldRenderContext worldRenderContext) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			cachedPreviewOutlineState = null;
			return;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			cachedPreviewOutlineState = null;
			return;
		}
		if (worldRenderContext.matrixStack() == null || worldRenderContext.consumers() == null) {
			return;
		}

		List<PreviewOutlineShape> previewShapes = resolvePreviewOutlineShapes(minecraft);
		if (previewShapes.isEmpty()) {
			return;
		}

		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
		VertexConsumer lineVertexConsumer = worldRenderContext.consumers().getBuffer(QUICK_LINK_PREVIEW_RENDER_TYPE);
		for (PreviewOutlineShape previewShape : previewShapes) {
			OutlineColor color = previewShape.color();
			LevelRenderer.renderVoxelShape(
				worldRenderContext.matrixStack(),
				lineVertexConsumer,
				previewShape.shape(),
				-cameraPosition.x,
				-cameraPosition.y,
				-cameraPosition.z,
				color.red(),
				color.green(),
				color.blue(),
				1.0F,
				false
			);
		}
	}

	/**
	 * 注册方块描边与缓存外显事件。
	 */
	public static void register() {
		WorldRenderEvents.BLOCK_OUTLINE.register(QuickLinkOutlineRenderer::onBlockOutline);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(QuickLinkOutlineRenderer::onAfterTranslucent);
	}

	/**
	 * 根据当前命中方块解析 quick-link 应使用的描边颜色。
	 */
	private static OutlineColor resolveOutlineColor(Minecraft minecraft, BlockPos blockPos) {
		if (minecraft == null || minecraft.level == null || blockPos == null) {
			return null;
		}
		return resolveBlockEntityOutlineColor(minecraft.level.getBlockEntity(blockPos));
	}

	/**
	 * 解析 quick-link 缓存对象预览的线框集合，并对短时间内重复帧复用扫描结果。
	 */
	private static List<PreviewOutlineShape> resolvePreviewOutlineShapes(Minecraft minecraft) {
		if (
			minecraft == null
				|| minecraft.player == null
				|| minecraft.level == null
				|| minecraft.options == null
				|| !(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)
		) {
			cachedPreviewOutlineState = null;
			return List.of();
		}

		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(minecraft.player.getMainHandItem());
		if (snapshot.mode() != QuickLinkToolData.Mode.SERIAL || snapshot.serialCacheExpression().isBlank()) {
			cachedPreviewOutlineState = null;
			return List.of();
		}

		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(snapshot.serialCacheExpression(), 0);
		if (parseResult.exceedLimit() || !parseResult.invalidEntries().isEmpty() || parseResult.orderedTargets().isEmpty()) {
			cachedPreviewOutlineState = null;
			return List.of();
		}

		String dimensionKey = minecraft.level.dimension().location().toString();
		int playerChunkX = minecraft.player.chunkPosition().x;
		int playerChunkZ = minecraft.player.chunkPosition().z;
		int renderDistance = minecraft.options.renderDistance().get();
		long gameTime = minecraft.level.getGameTime();
		if (
			cachedPreviewOutlineState != null
				&& cachedPreviewOutlineState.matches(
					dimensionKey,
					snapshot.serialCacheType(),
					snapshot.serialCacheExpression(),
					playerChunkX,
					playerChunkZ,
					renderDistance,
					gameTime
				)
		) {
			return cachedPreviewOutlineState.previewShapes();
		}

		List<PreviewOutlineShape> previewShapes = scanPreviewOutlineShapes(
			minecraft,
			snapshot.serialCacheType(),
			new LinkedHashSet<>(parseResult.orderedTargets()),
			playerChunkX,
			playerChunkZ,
			renderDistance
		);
		cachedPreviewOutlineState = new CachedPreviewOutlineState(
			dimensionKey,
			snapshot.serialCacheType(),
			snapshot.serialCacheExpression(),
			playerChunkX,
			playerChunkZ,
			renderDistance,
			gameTime + PREVIEW_CACHE_TTL_TICKS,
			previewShapes
		);
		return previewShapes;
	}

	/**
	 * 扫描当前客户端已加载区块，按主题色合并缓存对象的最外层线框。
	 */
	private static List<PreviewOutlineShape> scanPreviewOutlineShapes(
		Minecraft minecraft,
		LinkNodeType cacheType,
		Set<Long> cachedSerials,
		int playerChunkX,
		int playerChunkZ,
		int renderDistance
	) {
		if (minecraft == null || minecraft.level == null || cachedSerials == null || cachedSerials.isEmpty()) {
			return List.of();
		}

		Map<OutlineColor, VoxelShape> mergedShapesByColor = new LinkedHashMap<>();
		for (int chunkX = playerChunkX - renderDistance; chunkX <= playerChunkX + renderDistance; chunkX++) {
			for (int chunkZ = playerChunkZ - renderDistance; chunkZ <= playerChunkZ + renderDistance; chunkZ++) {
				LevelChunk levelChunk = minecraft.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (levelChunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity)) {
						continue;
					}
					long serial = pairableNodeBlockEntity.getSerial();
					if (serial <= 0L || !cachedSerials.contains(serial) || !pairableNodeBlockEntity.matchesNodeIdentity(cacheType, serial)) {
						continue;
					}
					OutlineColor outlineColor = resolveBlockEntityOutlineColor(blockEntity);
					if (outlineColor == null) {
						continue;
					}
					BlockPos blockPos = pairableNodeBlockEntity.getBlockPos();
					VoxelShape blockShape = Shapes.box(
						blockPos.getX(),
						blockPos.getY(),
						blockPos.getZ(),
						blockPos.getX() + 1.0D,
						blockPos.getY() + 1.0D,
						blockPos.getZ() + 1.0D
					);
					mergedShapesByColor.merge(outlineColor, blockShape, (currentShape, nextShape) -> Shapes.or(currentShape, nextShape));
				}
			}
		}

		return mergedShapesByColor
			.entrySet()
			.stream()
			.map(entry -> new PreviewOutlineShape(entry.getValue(), entry.getKey()))
			.toList();
	}

	/**
	 * 按真实方块实体类型解析 quick-link 外显颜色。
	 */
	private static OutlineColor resolveBlockEntityOutlineColor(BlockEntity blockEntity) {
		if (blockEntity instanceof LinkRepeaterBlockEntity) {
			return REPEATER_OUTLINE_COLOR;
		}
		if (blockEntity instanceof PairableNodeBlockEntity pairableNodeBlockEntity) {
			LinkNodeType targetType = pairableNodeBlockEntity.getLinkNodeType();
			if (targetType == null) {
				return null;
			}
			return targetType == LinkNodeType.CORE ? CORE_OUTLINE_COLOR : TRIGGER_SOURCE_OUTLINE_COLOR;
		}
		if (blockEntity instanceof AbstractLinkFilterBlockEntity) {
			return FILTER_OUTLINE_COLOR;
		}
		return null;
	}

	/**
	 * quick-link 描边颜色值。
	 */
	private record OutlineColor(float red, float green, float blue) {
		static OutlineColor fromPackedColor(int color) {
			return new OutlineColor(
				((color >> 16) & 0xFF) / 255.0F,
				((color >> 8) & 0xFF) / 255.0F,
				(color & 0xFF) / 255.0F
			);
		}
	}

	/**
	 * 单个 quick-link 预览线框及其颜色。
	 */
	private record PreviewOutlineShape(VoxelShape shape, OutlineColor color) {
	}

	/**
	 * quick-link 缓存对象预览的短 TTL 客户端缓存。
	 */
	private record CachedPreviewOutlineState(
		String dimensionKey,
		LinkNodeType cacheType,
		String serialExpression,
		int playerChunkX,
		int playerChunkZ,
		int renderDistance,
		long expireGameTick,
		List<PreviewOutlineShape> previewShapes
	) {
		CachedPreviewOutlineState {
			previewShapes = List.copyOf(previewShapes);
		}

		boolean matches(
			String dimensionKey,
			LinkNodeType cacheType,
			String serialExpression,
			int playerChunkX,
			int playerChunkZ,
			int renderDistance,
			long gameTime
		) {
			return this.dimensionKey.equals(dimensionKey)
				&& this.cacheType == cacheType
				&& this.serialExpression.equals(serialExpression)
				&& this.playerChunkX == playerChunkX
				&& this.playerChunkZ == playerChunkZ
				&& this.renderDistance == renderDistance
				&& gameTime <= expireGameTick;
		}
	}
}
