package com.makomi.client.render;

import com.makomi.block.entity.AbstractLinkFilterBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.QuickLinkToolData;
import com.makomi.data.SmartGlassesAccessSupport;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.network.QuickLinkNetwork;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * quick-link 世界命中描边、预览线框与智能眼镜第三形态外显渲染器。
 */
public final class QuickLinkWorldOverlayRenderer {
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
	private static final OutlineColor[] VISUALIZE_OBJECT_COLORS = new OutlineColor[] {
		OutlineColor.fromPackedColor(0xFF74FF7B),
		OutlineColor.fromPackedColor(0xFF5DD7FF),
		OutlineColor.fromPackedColor(0xFFFFC66A),
		OutlineColor.fromPackedColor(0xFFFF7AA8),
		OutlineColor.fromPackedColor(0xFFFFFF73),
		OutlineColor.fromPackedColor(0xFF8EF6FF),
		OutlineColor.fromPackedColor(0xFFFF9C66),
		OutlineColor.fromPackedColor(0xFFC6FF63)
	};
	private static final int PREVIEW_CACHE_TTL_TICKS = 6;
	private static final double VISUALIZE_HOVER_MAX_DISTANCE = 64.0D;
	private static final double VISUALIZE_HOVER_BASE_THRESHOLD = 0.22D;
	private static final double VISUALIZE_HOVER_DISTANCE_SCALE = 0.02D;
	private static final double VISUALIZE_HOVER_MAX_THRESHOLD = 1.10D;
	private static final double SEGMENT_EPSILON = 1.0E-6D;
	private static CachedPreviewOutlineState cachedPreviewOutlineState;
	private static CachedChannelPreviewState cachedChannelPreviewState;
	private static PendingChannelPreviewRequest pendingChannelPreviewRequest;
	private static final Map<VisualizedObjectKey, VisualizedObjectState> visualizedObjects = new LinkedHashMap<>();
	private static long visualizedRuntimeNodeVersion;
	private static int nextVisualizedColorIndex;

	private QuickLinkWorldOverlayRenderer() {
	}

	/**
	 * 在默认方块描边阶段绘制 quick-link 自定义描边。
	 *
	 * @return `false` 表示已自行渲染并取消默认白色描边；其余情况保持默认行为
	 */
	public static boolean onBlockOutline(
		LevelRenderContext worldRenderContext,
		BlockOutlineRenderState blockOutlineRenderState
	) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return true;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			return true;
		}
		BlockPos blockPos = blockOutlineRenderState.pos();
		OutlineColor outlineColor = resolveOutlineColor(minecraft, blockPos);
		if (outlineColor == null) {
			return true;
		}
		if (!SmartGlassesAccessSupport.canRenderQuickLinkVisualization(minecraft.player)) {
			// quick-link 自定义命中方框与普通节点保持同一眼镜门槛；
			// 未戴眼镜时直接吞掉默认白框，避免 visible/hide 节点出现不一致外显。
			return false;
		}
		if (IrisRenderCompatSupport.shouldUseCompatibilityBranch()) {
			// 兼容分支只在这里拦截 vanilla 白框；
			// 实际描边统一延后到 END_MAIN 直接绘制，避免 shader 管线下的位移异常。
			return false;
		}

		if (worldRenderContext.poseStack() == null) {
			return true;
		}

		VoxelShape voxelShape = blockOutlineRenderState.shape();
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> outlineSegments = buildOutlineSegments(
			voxelShape,
			blockPos,
			outlineColor
		);
		SeeThroughWorldGeometryRenderSupport.renderLines(worldRenderContext, outlineSegments);
		return false;
	}

	/**
	 * 在半透明阶段后额外绘制 quick-link 缓存对象的穿墙线框外显。
	 */
	private static void onAfterTranslucent(LevelRenderContext worldRenderContext) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			clearTransientPreviewState();
			clearVisualizedObjectState();
			return;
		}
		if (IrisRenderCompatSupport.shouldUseCompatibilityBranch()) {
			// 兼容分支统一在 END_MAIN 重绘 preview，避免 BEFORE_TRANSLUCENT 下的时机冲突。
			return;
		}
		if (worldRenderContext.poseStack() == null) {
			return;
		}
		renderPreviewOutlines(worldRenderContext, minecraft);
	}

	/**
	 * 在主世界渲染结束后补绘第三形态连线，确保穿透连线不会再被后续主场景阶段覆盖。
	 */
	private static void onEndMain(LevelRenderContext worldRenderContext) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			clearVisualizedObjectState();
			return;
		}
		if (worldRenderContext.poseStack() == null) {
			return;
		}
		if (!SmartGlassesAccessSupport.canRenderQuickLinkVisualization(minecraft.player)) {
			return;
		}
		if (IrisRenderCompatSupport.shouldUseCompatibilityBranch()) {
			renderIrisCompatibleCurrentOutline(worldRenderContext, minecraft);
			renderIrisCompatiblePreviewOutlines(worldRenderContext, minecraft);
		}
		renderVisualizedConnections(worldRenderContext, minecraft);
	}

	/**
	 * 在半透明阶段前绘制 quick-link 缓存对象的穿墙线框预览。
	 */
	private static void renderPreviewOutlines(LevelRenderContext worldRenderContext, Minecraft minecraft) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null) {
			clearTransientPreviewState();
			return;
		}
		if (!(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)) {
			clearTransientPreviewState();
			return;
		}
		if (!SmartGlassesAccessSupport.canRenderQuickLinkVisualization(minecraft.player)) {
			clearTransientPreviewState();
			return;
		}
		if (worldRenderContext.poseStack() == null) {
			return;
		}
		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(minecraft.player.getMainHandItem());
		if (snapshot.mode() == QuickLinkToolData.Mode.VISUALIZE) {
			return;
		}

		List<PreviewOutlineBatch> previewBatches = resolvePreviewOutlineBatches(minecraft);
		if (previewBatches.isEmpty()) {
			return;
		}

		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments = new ArrayList<>();
		for (PreviewOutlineBatch previewBatch : previewBatches) {
			for (LineSegment lineSegment : previewBatch.segments()) {
				lineSegments.add(toColoredLineSegment(lineSegment, previewBatch.color()));
			}
		}
		SeeThroughWorldGeometryRenderSupport.renderLines(worldRenderContext, lineSegments);
	}

	/**
	 * 注册方块描边与缓存外显事件。
	 */
	public static void register() {
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register(QuickLinkWorldOverlayRenderer::onBlockOutline);
		LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(QuickLinkWorldOverlayRenderer::onAfterTranslucent);
		LevelRenderEvents.END_MAIN.register(QuickLinkWorldOverlayRenderer::onEndMain);
	}

	/**
	 * 接收服务端回传的频道缓存预览成员，并失效本地线框缓存。
	 */
	public static void acceptChannelPreview(QuickLinkNetwork.QuickLinkChannelPreviewPayload payload) {
		LinkNodeType cacheType = payload == null ? null : LinkNodeSemantics.tryParseCanonicalType(payload.cacheTypeToken()).orElse(null);
		if (cacheType == null || payload.channel() <= 0L) {
			return;
		}
		cachedChannelPreviewState = new CachedChannelPreviewState(
			cacheType,
			payload.channel(),
			normalizePositivePreviewSerials(payload.memberSerials()),
			currentClientGameTime() + PREVIEW_CACHE_TTL_TICKS
		);
		if (pendingChannelPreviewRequest != null && pendingChannelPreviewRequest.matchesKey(cacheType, payload.channel())) {
			pendingChannelPreviewRequest = null;
		}
		cachedPreviewOutlineState = null;
	}

	/**
	 * 判断第三形态显示对象是否已存在。
	 */
	public static boolean hasVisualizedObject(String objectTypeToken, long objectSerial) {
		return visualizedObjects.containsKey(new VisualizedObjectKey(objectTypeToken, objectSerial));
	}

	/**
	 * 判断当前是否存在任意第三形态显示对象。
	 */
	public static boolean hasVisualizedObjects() {
		return !visualizedObjects.isEmpty();
	}

	/**
	 * 导出当前全部第三形态显示对象的本地 revision 基线。
	 */
	public static List<QuickLinkNetwork.QuickLinkVisualizeTrackedObject> snapshotVisualizedObjectBaselines() {
		if (visualizedObjects.isEmpty()) {
			return List.of();
		}
		List<QuickLinkNetwork.QuickLinkVisualizeTrackedObject> trackedObjects = new ArrayList<>(visualizedObjects.size());
		for (VisualizedObjectState state : visualizedObjects.values()) {
			if (state == null || state.source() == null || state.revisionBaseline() == null) {
				continue;
			}
			trackedObjects.add(state.revisionBaseline());
		}
		return trackedObjects.isEmpty() ? List.of() : List.copyOf(trackedObjects);
	}

	/**
	 * 读取当前第三形态显示缓存所对应的运行时节点版本。
	 */
	public static long visualizedRuntimeNodeVersion() {
		return visualizedRuntimeNodeVersion;
	}

	/**
	 * 接收服务端回传的第三形态显示对象快照。
	 */
	public static void acceptVisualizeSnapshot(QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload payload) {
		if (payload == null || payload.objectTypeToken().isBlank() || payload.objectSerial() <= 0L) {
			return;
		}
		VisualizedObjectKey key = new VisualizedObjectKey(payload.objectTypeToken(), payload.objectSerial());
		OutlineColor color = visualizedObjects.containsKey(key)
			? visualizedObjects.get(key).color()
			: nextVisualizedObjectColor();
		visualizedObjects.put(
			key,
			new VisualizedObjectState(
				new VisualizedObjectRef(
					payload.objectTypeToken(),
					payload.objectSerial(),
					payload.dimensionKey(),
					payload.blockPosLong(),
					normalizeVisualizedDisplayText(payload.displayText(), payload.objectSerial())
				),
				normalizeVisualizedTargets(payload.targets()),
				new QuickLinkNetwork.QuickLinkVisualizeTrackedObject(
					payload.objectTypeToken(),
					payload.objectSerial(),
					payload.graphRevision(),
					payload.sourceRevision(),
					payload.coreRevision()
				),
				color
			)
		);
		visualizedRuntimeNodeVersion = Math.max(visualizedRuntimeNodeVersion, payload.runtimeNodeVersion());
	}

	/**
	 * 接收服务端回传的第三形态增量刷新结果，并局部更新本地缓存。
	 */
	public static void acceptVisualizeRefresh(QuickLinkNetwork.QuickLinkVisualizeRefreshPayload payload) {
		if (payload == null) {
			return;
		}
		for (QuickLinkNetwork.QuickLinkVisualizeObjectKey removal : payload.removals()) {
			if (removal == null || removal.objectTypeToken().isBlank() || removal.objectSerial() <= 0L) {
				continue;
			}
			removeVisualizedObject(removal.objectTypeToken(), removal.objectSerial());
		}
		for (QuickLinkNetwork.QuickLinkVisualizeSnapshotPayload upsert : payload.upserts()) {
			acceptVisualizeSnapshot(upsert);
		}
		visualizedRuntimeNodeVersion = Math.max(0L, payload.runtimeNodeVersion());
	}

	/**
	 * 移除单个第三形态显示对象。
	 */
	public static boolean removeVisualizedObject(String objectTypeToken, long objectSerial) {
		boolean removed = visualizedObjects.remove(new VisualizedObjectKey(objectTypeToken, objectSerial)) != null;
		if (visualizedObjects.isEmpty()) {
			nextVisualizedColorIndex = 0;
			visualizedRuntimeNodeVersion = 0L;
		}
		return removed;
	}

	/**
	 * 清空第三形态全部显示对象。
	 */
	public static int clearVisualizedObjects() {
		int removedCount = visualizedObjects.size();
		clearVisualizedObjectState();
		return removedCount;
	}

	/**
	 * 释放第三形态渲染链持有的客户端资源。
	 */
	public static void close() {
		clearTransientPreviewState();
		clearVisualizedObjectState();
		cachedChannelPreviewState = null;
		pendingChannelPreviewRequest = null;
		SeeThroughWorldGeometryRenderSupport.close();
	}

	/**
	 * 解析当前准星命中的第三形态连线另一端对象。
	 */
	static HoveredVisualizedTarget resolveHoveredVisualizedTarget(Minecraft minecraft) {
		if (
			minecraft == null
				|| minecraft.player == null
				|| minecraft.level == null
				|| visualizedObjects.isEmpty()
				|| !SmartGlassesAccessSupport.canRenderQuickLinkVisualization(minecraft.player)
		) {
			return null;
		}
		Vec3 rayOrigin = minecraft.gameRenderer.getMainCamera().position();
		Vec3 rayDirection = minecraft.player.getViewVector(1.0F);
		if (rayDirection.lengthSqr() <= SEGMENT_EPSILON) {
			return null;
		}
		double maxRayDistance = VISUALIZE_HOVER_MAX_DISTANCE;
		HitResult hitResult = minecraft.hitResult;
		if (hitResult != null) {
			double hitDistance = hitResult.getLocation().distanceTo(rayOrigin);
			if (hitDistance > 0.0D) {
				maxRayDistance = Math.max(4.0D, Math.min(VISUALIZE_HOVER_MAX_DISTANCE, hitDistance + 1.0D));
			}
		}
		return resolveHoveredVisualizedTarget(
			minecraft.level.dimension().identifier().toString(),
			rayOrigin,
			rayDirection.normalize(),
			maxRayDistance
		);
	}

	/**
	 * 纯客户端几何判定：按给定视线射线解析当前命中的第三形态连线另一端对象。
	 */
	static HoveredVisualizedTarget resolveHoveredVisualizedTarget(
		String dimensionKey,
		Vec3 rayOrigin,
		Vec3 rayDirection,
		double maxRayDistance
	) {
		if (
			dimensionKey == null
				|| dimensionKey.isBlank()
				|| rayOrigin == null
				|| rayDirection == null
				|| rayDirection.lengthSqr() <= SEGMENT_EPSILON
				|| maxRayDistance <= 0.0D
				|| visualizedObjects.isEmpty()
		) {
			return null;
		}
		HoveredVisualizedTargetCandidate bestCandidate = null;
		for (VisualizedConnection connection : collectVisibleVisualizedConnections(dimensionKey)) {
			ClosestLineApproach closestLineApproach = resolveClosestLineApproach(
				rayOrigin,
				rayDirection,
				maxRayDistance,
				connection.segment()
			);
			if (closestLineApproach == null) {
				continue;
			}
			double allowedDistance = resolveVisualizeHoverThreshold(closestLineApproach.rayDistance());
			if (closestLineApproach.distance() > allowedDistance) {
				continue;
			}
			HoveredVisualizedTargetCandidate candidate = new HoveredVisualizedTargetCandidate(
				new HoveredVisualizedTarget(
					connection.target().objectTypeToken(),
					connection.target().objectSerial(),
					connection.target().displayText(),
					connection.target().blockPosLong()
				),
				closestLineApproach.rayDistance(),
				closestLineApproach.distance() / allowedDistance
			);
			if (candidate.isBetterThan(bestCandidate)) {
				bestCandidate = candidate;
			}
		}
		return bestCandidate == null ? null : bestCandidate.target();
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
	private static List<PreviewOutlineBatch> resolvePreviewOutlineBatches(Minecraft minecraft) {
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
		PreviewTargetSelection previewTargetSelection = resolvePreviewTargetSelection(minecraft, snapshot);
		if (previewTargetSelection == null || previewTargetSelection.targetSerials().isEmpty()) {
			cachedPreviewOutlineState = null;
			return List.of();
		}

		String dimensionKey = minecraft.level.dimension().identifier().toString();
		int playerChunkX = minecraft.player.chunkPosition().x();
		int playerChunkZ = minecraft.player.chunkPosition().z();
		int renderDistance = minecraft.options.renderDistance().get();
		long gameTime = minecraft.level.getGameTime();
		if (
			cachedPreviewOutlineState != null
				&& cachedPreviewOutlineState.matches(
					dimensionKey,
					previewTargetSelection.cacheType(),
					previewTargetSelection.cacheKey(),
					playerChunkX,
					playerChunkZ,
					renderDistance,
					gameTime
				)
		) {
			return cachedPreviewOutlineState.previewBatches();
		}

		List<PreviewOutlineBatch> previewBatches = scanPreviewOutlineBatches(
			minecraft,
			previewTargetSelection.cacheType(),
			previewTargetSelection.targetSerials(),
			playerChunkX,
			playerChunkZ,
			renderDistance
		);
		cachedPreviewOutlineState = new CachedPreviewOutlineState(
			dimensionKey,
			previewTargetSelection.cacheType(),
			previewTargetSelection.cacheKey(),
			playerChunkX,
			playerChunkZ,
			renderDistance,
			gameTime + PREVIEW_CACHE_TTL_TICKS,
			previewBatches
		);
		return previewBatches;
	}

	/**
	 * 解析当前 quick-link 快照应预览的目标序号集合。
	 */
	private static PreviewTargetSelection resolvePreviewTargetSelection(
		Minecraft minecraft,
		QuickLinkToolData.Snapshot snapshot
	) {
		if (snapshot == null) {
			return null;
		}
		if (snapshot.mode() == QuickLinkToolData.Mode.VISUALIZE) {
			return null;
		}
		if (snapshot.mode() == QuickLinkToolData.Mode.CHANNEL) {
			return resolveChannelPreviewTargetSelection(minecraft, snapshot);
		}
		return resolveSerialPreviewTargetSelection(snapshot);
	}

	/**
	 * 解析序号缓存模式下的预览目标。
	 */
	private static PreviewTargetSelection resolveSerialPreviewTargetSelection(QuickLinkToolData.Snapshot snapshot) {
		if (snapshot == null || snapshot.serialCacheExpression().isBlank()) {
			return null;
		}
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(snapshot.serialCacheExpression(), 0);
		if (parseResult.exceedLimit() || !parseResult.invalidEntries().isEmpty() || parseResult.orderedTargets().isEmpty()) {
			return null;
		}
		return new PreviewTargetSelection(
			snapshot.serialCacheType(),
			snapshot.serialCacheExpression(),
			new LinkedHashSet<>(parseResult.orderedTargets())
		);
	}

	/**
	 * 解析频道缓存模式下的预览目标，并在必要时向服务端请求成员序号。
	 */
	private static PreviewTargetSelection resolveChannelPreviewTargetSelection(
		Minecraft minecraft,
		QuickLinkToolData.Snapshot snapshot
	) {
		if (minecraft == null || snapshot == null) {
			return null;
		}
		long channel = snapshot.channelCacheValue().parseChannelOrZero();
		if (channel <= 0L) {
			return null;
		}
		long gameTime = currentClientGameTime();
		requestChannelPreviewIfNeeded(minecraft, snapshot.serialCacheType(), channel, gameTime);
		if (
			cachedChannelPreviewState == null ||
			!cachedChannelPreviewState.matches(snapshot.serialCacheType(), channel, gameTime)
		) {
			return null;
		}
		return new PreviewTargetSelection(
			snapshot.serialCacheType(),
			Long.toString(channel),
			cachedChannelPreviewState.memberSerials()
		);
	}

	/**
	 * 在客户端短 TTL 内节流频道预览请求，避免逐帧向服务端重复取数。
	 */
	private static void requestChannelPreviewIfNeeded(
		Minecraft minecraft,
		LinkNodeType cacheType,
		long channel,
		long gameTime
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null || cacheType == null || channel <= 0L) {
			return;
		}
		if (cachedChannelPreviewState != null && cachedChannelPreviewState.matches(cacheType, channel, gameTime)) {
			return;
		}
		if (pendingChannelPreviewRequest != null && pendingChannelPreviewRequest.matches(cacheType, channel, gameTime)) {
			return;
		}
		ClientPlayNetworking.send(
			new QuickLinkNetwork.RequestQuickLinkChannelPreviewPayload(LinkNodeSemantics.toSemanticName(cacheType), channel)
		);
		pendingChannelPreviewRequest = new PendingChannelPreviewRequest(cacheType, channel, gameTime + PREVIEW_CACHE_TTL_TICKS);
	}

	/**
	 * 清空 quick-link 预览相关的瞬时状态，避免跨世界残留。
	 */
	private static void clearTransientPreviewState() {
		cachedPreviewOutlineState = null;
		cachedChannelPreviewState = null;
		pendingChannelPreviewRequest = null;
	}

	/**
	 * 清空第三形态显示对象状态。
	 */
	private static void clearVisualizedObjectState() {
		visualizedObjects.clear();
		visualizedRuntimeNodeVersion = 0L;
		nextVisualizedColorIndex = 0;
	}

	/**
	 * 读取当前客户端世界时间；无世界时返回 0。
	 */
	private static long currentClientGameTime() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? 0L : minecraft.level.getGameTime();
	}

	/**
	 * 规范化频道预览成员序号集合：仅保留正数并去重。
	 */
	private static Set<Long> normalizePositivePreviewSerials(Iterable<Long> memberSerials) {
		if (memberSerials == null) {
			return Set.of();
		}
		Set<Long> normalizedSerials = new LinkedHashSet<>();
		for (Long memberSerial : memberSerials) {
			if (memberSerial != null && memberSerial > 0L) {
				normalizedSerials.add(memberSerial);
			}
		}
		return normalizedSerials.isEmpty() ? Set.of() : Set.copyOf(normalizedSerials);
	}

	/**
	 * 扫描当前客户端已加载区块，并只提取缓存对象真正外露的边界线段。
	 */
	private static List<PreviewOutlineBatch> scanPreviewOutlineBatches(
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

		Map<OutlineColor, Set<BlockPos>> previewBlocksByColor = new LinkedHashMap<>();
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
					previewBlocksByColor.computeIfAbsent(outlineColor, ignored -> new LinkedHashSet<>()).add(
						pairableNodeBlockEntity.getBlockPos().immutable()
					);
				}
			}
		}

		return previewBlocksByColor
			.entrySet()
			.stream()
			.map(entry -> new PreviewOutlineBatch(buildBoundaryLineSegments(entry.getValue()), entry.getKey()))
			.filter(entry -> !entry.segments().isEmpty())
			.toList();
	}

	/**
	 * 仅基于外露面边界生成线段，避免相连面的内部接缝继续显示。
	 */
	private static List<LineSegment> buildBoundaryLineSegments(Set<BlockPos> occupiedBlocks) {
		return QuickLinkPreviewOutlineSupport
			.buildBoundarySegments(occupiedBlocks)
			.stream()
			.map(segment -> LineSegment.of(segment.startX(), segment.startY(), segment.startZ(), segment.endX(), segment.endY(), segment.endZ()))
			.toList();
	}

	/**
	 * 写入单条 preview 线段。
	 */
	private static SeeThroughWorldGeometryRenderSupport.ColoredLineSegment toColoredLineSegment(
		LineSegment lineSegment,
		OutlineColor color
	) {
		if (lineSegment == null || color == null) {
			return null;
		}
		int red = Math.round(color.red() * 255.0F);
		int green = Math.round(color.green() * 255.0F);
		int blue = Math.round(color.blue() * 255.0F);
		return new SeeThroughWorldGeometryRenderSupport.ColoredLineSegment(
			lineSegment.startX(),
			lineSegment.startY(),
			lineSegment.startZ(),
			lineSegment.endX(),
			lineSegment.endY(),
			lineSegment.endZ(),
			lineSegment.normalX(),
			lineSegment.normalY(),
			lineSegment.normalZ(),
			red,
			green,
			blue,
			255
		);
	}

	/**
	 * 在兼容分支中重绘当前命中节点描边。
	 * <p>
	 * BEFORE_BLOCK_OUTLINE 在兼容模组下会出现轻微漂移，因此这里统一延后到 END_MAIN。
	 * </p>
	 */
	private static void renderIrisCompatibleCurrentOutline(LevelRenderContext worldRenderContext, Minecraft minecraft) {
		if (
			worldRenderContext == null
				|| minecraft == null
				|| minecraft.player == null
				|| minecraft.level == null
				|| !(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)
				|| !(minecraft.hitResult instanceof BlockHitResult blockHitResult)
		) {
			return;
		}
		BlockPos blockPos = blockHitResult.getBlockPos();
		OutlineColor outlineColor = resolveOutlineColor(minecraft, blockPos);
		if (outlineColor == null) {
			return;
		}
		VoxelShape voxelShape = minecraft
			.level
			.getBlockState(blockPos)
			.getShape(minecraft.level, blockPos, CollisionContext.of(minecraft.player));
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> outlineSegments = buildOutlineSegments(
			voxelShape,
			blockPos,
			outlineColor
		);
		IrisDirectLineRenderSupport.drawWorldSegments(worldRenderContext, outlineSegments, 2.5F);
	}

	/**
	 * 在兼容分支中重绘 quick-link preview 线框。
	 */
	private static void renderIrisCompatiblePreviewOutlines(LevelRenderContext worldRenderContext, Minecraft minecraft) {
		if (
			worldRenderContext == null
				|| minecraft == null
				|| minecraft.player == null
				|| minecraft.level == null
				|| !(minecraft.player.getMainHandItem().getItem() instanceof QuickLinkToolItem)
		) {
			return;
		}
		QuickLinkToolData.Snapshot snapshot = QuickLinkToolData.read(minecraft.player.getMainHandItem());
		if (snapshot.mode() == QuickLinkToolData.Mode.VISUALIZE) {
			return;
		}
		List<PreviewOutlineBatch> previewBatches = resolvePreviewOutlineBatches(minecraft);
		if (previewBatches.isEmpty()) {
			return;
		}
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments = new ArrayList<>();
		for (PreviewOutlineBatch previewBatch : previewBatches) {
			for (LineSegment lineSegment : previewBatch.segments()) {
				lineSegments.add(toColoredLineSegment(lineSegment, previewBatch.color()));
			}
		}
		IrisDirectLineRenderSupport.drawWorldSegments(worldRenderContext, lineSegments, 2.5F);
	}

	/**
	 * 将命中体积外框拆成世界空间线段，供穿透管线复用。
	 */
	private static List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> buildOutlineSegments(
		VoxelShape voxelShape,
		BlockPos blockPos,
		OutlineColor outlineColor
	) {
		if (voxelShape == null || blockPos == null || outlineColor == null) {
			return List.of();
		}
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> outlineSegments = new ArrayList<>();
		int red = Math.round(outlineColor.red() * 255.0F);
		int green = Math.round(outlineColor.green() * 255.0F);
		int blue = Math.round(outlineColor.blue() * 255.0F);
		voxelShape.forAllEdges((startX, startY, startZ, endX, endY, endZ) -> {
			double worldStartX = blockPos.getX() + startX;
			double worldStartY = blockPos.getY() + startY;
			double worldStartZ = blockPos.getZ() + startZ;
			double worldEndX = blockPos.getX() + endX;
			double worldEndY = blockPos.getY() + endY;
			double worldEndZ = blockPos.getZ() + endZ;
			LineSegment lineSegment = LineSegment.of(worldStartX, worldStartY, worldStartZ, worldEndX, worldEndY, worldEndZ);
			outlineSegments.add(
				new SeeThroughWorldGeometryRenderSupport.ColoredLineSegment(
					lineSegment.startX(),
					lineSegment.startY(),
					lineSegment.startZ(),
					lineSegment.endX(),
					lineSegment.endY(),
					lineSegment.endZ(),
					lineSegment.normalX(),
					lineSegment.normalY(),
					lineSegment.normalZ(),
					red,
					green,
					blue,
					255
				)
			);
		});
		return outlineSegments.isEmpty() ? List.of() : List.copyOf(outlineSegments);
	}

	/**
	 * 绘制第三形态全部显示对象的穿墙连线。
	 */
	private static void renderVisualizedConnections(LevelRenderContext worldRenderContext, Minecraft minecraft) {
		if (worldRenderContext == null || minecraft == null || minecraft.level == null || visualizedObjects.isEmpty()) {
			return;
		}
		List<VisualizedConnection> visibleConnections = collectVisibleVisualizedConnections(
			minecraft.level.dimension().identifier().toString()
		);
		if (visibleConnections.isEmpty()) {
			return;
		}
		List<SeeThroughWorldGeometryRenderSupport.ColoredLineSegment> lineSegments = new ArrayList<>(visibleConnections.size());
		for (VisualizedConnection visibleConnection : visibleConnections) {
			OutlineColor color = visibleConnection.color();
			LineSegment lineSegment = visibleConnection.segment();
			lineSegments.add(
				new SeeThroughWorldGeometryRenderSupport.ColoredLineSegment(
					lineSegment.startX(),
					lineSegment.startY(),
					lineSegment.startZ(),
					lineSegment.endX(),
					lineSegment.endY(),
					lineSegment.endZ(),
					lineSegment.normalX(),
					lineSegment.normalY(),
					lineSegment.normalZ(),
					Math.round(color.red() * 255.0F),
					Math.round(color.green() * 255.0F),
					Math.round(color.blue() * 255.0F),
					255
				)
			);
		}
		if (IrisRenderCompatSupport.shouldUseCompatibilityBranch()) {
			IrisDirectLineRenderSupport.drawWorldSegments(worldRenderContext, lineSegments, 2.5F);
		} else {
			SeeThroughWorldGeometryRenderSupport.renderLines(worldRenderContext, lineSegments);
		}
	}

	/**
	 * 收集当前维度下真正可见的第三形态连接线，供渲染与悬停判定复用。
	 */
	private static List<VisualizedConnection> collectVisibleVisualizedConnections(String currentDimensionKey) {
		if (currentDimensionKey == null || currentDimensionKey.isBlank() || visualizedObjects.isEmpty()) {
			return List.of();
		}
		List<VisualizedConnection> visibleConnections = new ArrayList<>();
		for (VisualizedObjectState state : visualizedObjects.values()) {
			VisualizedObjectRef source = state.source();
			if (source == null || !source.hasPosition() || !currentDimensionKey.equals(source.dimensionKey())) {
				continue;
			}
			Vec3 sourceCenter = resolveBlockCenter(source.blockPosLong());
			for (VisualizedObjectRef target : state.targets()) {
				if (target == null || !target.hasPosition() || !currentDimensionKey.equals(target.dimensionKey())) {
					continue;
				}
				Vec3 targetCenter = resolveBlockCenter(target.blockPosLong());
				visibleConnections.add(
					new VisualizedConnection(
						LineSegment.of(sourceCenter.x, sourceCenter.y, sourceCenter.z, targetCenter.x, targetCenter.y, targetCenter.z),
						source,
						target,
						state.color()
					)
				);
			}
		}
		return visibleConnections.isEmpty() ? List.of() : List.copyOf(visibleConnections);
	}

	/**
	 * 计算视线射线与目标线段之间的最短距离。
	 */
	private static ClosestLineApproach resolveClosestLineApproach(
		Vec3 rayOrigin,
		Vec3 rayDirection,
		double maxRayDistance,
		LineSegment lineSegment
	) {
		if (
			rayOrigin == null
				|| rayDirection == null
				|| rayDirection.lengthSqr() <= SEGMENT_EPSILON
				|| maxRayDistance <= 0.0D
				|| lineSegment == null
		) {
			return null;
		}
		Vec3 rayEnd = rayOrigin.add(rayDirection.scale(maxRayDistance));
		Vec3 lineStart = new Vec3(lineSegment.startX(), lineSegment.startY(), lineSegment.startZ());
		Vec3 lineEnd = new Vec3(lineSegment.endX(), lineSegment.endY(), lineSegment.endZ());
		SegmentClosestApproach segmentClosestApproach = resolveSegmentClosestApproach(rayOrigin, rayEnd, lineStart, lineEnd);
		if (segmentClosestApproach == null) {
			return null;
		}
		return new ClosestLineApproach(
			Math.sqrt(segmentClosestApproach.distanceSqr()),
			segmentClosestApproach.firstParameter() * maxRayDistance
		);
	}

	/**
	 * 有限线段与有限线段的最近点求解。
	 */
	private static SegmentClosestApproach resolveSegmentClosestApproach(
		Vec3 firstStart,
		Vec3 firstEnd,
		Vec3 secondStart,
		Vec3 secondEnd
	) {
		if (firstStart == null || firstEnd == null || secondStart == null || secondEnd == null) {
			return null;
		}
		Vec3 firstDelta = firstEnd.subtract(firstStart);
		Vec3 secondDelta = secondEnd.subtract(secondStart);
		Vec3 startDelta = firstStart.subtract(secondStart);
		double firstLengthSqr = firstDelta.dot(firstDelta);
		double secondLengthSqr = secondDelta.dot(secondDelta);
		if (firstLengthSqr <= SEGMENT_EPSILON || secondLengthSqr <= SEGMENT_EPSILON) {
			return null;
		}
		double deltaDot = firstDelta.dot(secondDelta);
		double firstStartDot = firstDelta.dot(startDelta);
		double secondStartDot = secondDelta.dot(startDelta);
		double denominator = firstLengthSqr * secondLengthSqr - deltaDot * deltaDot;
		double firstNumerator;
		double firstDenominator = denominator;
		double secondNumerator;
		double secondDenominator = denominator;
		if (denominator <= SEGMENT_EPSILON) {
			firstNumerator = 0.0D;
			firstDenominator = 1.0D;
			secondNumerator = secondStartDot;
			secondDenominator = secondLengthSqr;
		} else {
			firstNumerator = deltaDot * secondStartDot - secondLengthSqr * firstStartDot;
			secondNumerator = firstLengthSqr * secondStartDot - deltaDot * firstStartDot;
			if (firstNumerator < 0.0D) {
				firstNumerator = 0.0D;
				secondNumerator = secondStartDot;
				secondDenominator = secondLengthSqr;
			} else if (firstNumerator > firstDenominator) {
				firstNumerator = firstDenominator;
				secondNumerator = secondStartDot + deltaDot;
				secondDenominator = secondLengthSqr;
			}
		}
		if (secondNumerator < 0.0D) {
			secondNumerator = 0.0D;
			if (-firstStartDot < 0.0D) {
				firstNumerator = 0.0D;
			} else if (-firstStartDot > firstLengthSqr) {
				firstNumerator = firstDenominator;
			} else {
				firstNumerator = -firstStartDot;
				firstDenominator = firstLengthSqr;
			}
		} else if (secondNumerator > secondDenominator) {
			secondNumerator = secondDenominator;
			if (-firstStartDot + deltaDot < 0.0D) {
				firstNumerator = 0.0D;
			} else if (-firstStartDot + deltaDot > firstLengthSqr) {
				firstNumerator = firstDenominator;
			} else {
				firstNumerator = -firstStartDot + deltaDot;
				firstDenominator = firstLengthSqr;
			}
		}
		double firstParameter = Math.abs(firstNumerator) <= SEGMENT_EPSILON ? 0.0D : firstNumerator / firstDenominator;
		double secondParameter = Math.abs(secondNumerator) <= SEGMENT_EPSILON ? 0.0D : secondNumerator / secondDenominator;
		Vec3 distanceVector = startDelta
			.add(firstDelta.scale(firstParameter))
			.subtract(secondDelta.scale(secondParameter));
		return new SegmentClosestApproach(firstParameter, secondParameter, distanceVector.lengthSqr());
	}

	/**
	 * 长距离连线适度放宽命中阈值，避免实际瞄准体验过于苛刻。
	 */
	private static double resolveVisualizeHoverThreshold(double rayDistance) {
		double normalizedRayDistance = Math.max(0.0D, rayDistance);
		return Math.min(
			VISUALIZE_HOVER_MAX_THRESHOLD,
			VISUALIZE_HOVER_BASE_THRESHOLD + normalizedRayDistance * VISUALIZE_HOVER_DISTANCE_SCALE
		);
	}

	/**
	 * 读取方块中心点，供第三形态连接线复用。
	 */
	private static Vec3 resolveBlockCenter(long blockPosLong) {
		BlockPos blockPos = BlockPos.of(blockPosLong);
		return new Vec3(blockPos.getX() + 0.5D, blockPos.getY() + 0.5D, blockPos.getZ() + 0.5D);
	}

	/**
	 * 规范化第三形态目标对象列表，并按对象键去重。
	 */
	private static List<VisualizedObjectRef> normalizeVisualizedTargets(
		List<QuickLinkNetwork.QuickLinkVisualizeTarget> targets
	) {
		if (targets == null || targets.isEmpty()) {
			return List.of();
		}
		Map<VisualizedObjectKey, VisualizedObjectRef> normalizedTargets = new LinkedHashMap<>();
		for (QuickLinkNetwork.QuickLinkVisualizeTarget target : targets) {
			if (target == null || target.objectTypeToken().isBlank() || target.objectSerial() <= 0L) {
				continue;
			}
			normalizedTargets.putIfAbsent(
				new VisualizedObjectKey(target.objectTypeToken(), target.objectSerial()),
				new VisualizedObjectRef(
					target.objectTypeToken(),
					target.objectSerial(),
					target.dimensionKey(),
					target.blockPosLong(),
					normalizeVisualizedDisplayText(target.displayText(), target.objectSerial())
				)
			);
		}
		return normalizedTargets.isEmpty() ? List.of() : List.copyOf(normalizedTargets.values());
	}

	/**
	 * 规范化第三形态显示文本，缺失时统一退回到序号展示。
	 */
	private static String normalizeVisualizedDisplayText(String displayText, long serial) {
		String normalizedDisplayText = displayText == null ? "" : displayText.trim();
		return normalizedDisplayText.isEmpty()
			? NodeAliasDisplayUtil.formatDisplayText("", serial)
			: normalizedDisplayText;
	}

	/**
	 * 分配下一个第三形态显示对象颜色。
	 */
	private static OutlineColor nextVisualizedObjectColor() {
		OutlineColor color = VISUALIZE_OBJECT_COLORS[nextVisualizedColorIndex % VISUALIZE_OBJECT_COLORS.length];
		nextVisualizedColorIndex++;
		return color;
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

		int packedColor() {
			int packedRed = Math.round(red * 255.0F) & 0xFF;
			int packedGreen = Math.round(green * 255.0F) & 0xFF;
			int packedBlue = Math.round(blue * 255.0F) & 0xFF;
			return 0xFF000000 | (packedRed << 16) | (packedGreen << 8) | packedBlue;
		}
	}

	/**
	 * 同色缓存对象外轮廓线段批次。
	 */
	private record PreviewOutlineBatch(List<LineSegment> segments, OutlineColor color) {
		PreviewOutlineBatch {
			segments = List.copyOf(segments);
		}
	}

	/**
	 * 一次 quick-link 预览扫描所需的目标集合描述。
	 */
	private record PreviewTargetSelection(LinkNodeType cacheType, String cacheKey, Set<Long> targetSerials) {
		PreviewTargetSelection {
			targetSerials = Set.copyOf(targetSerials == null ? Set.of() : targetSerials);
		}
	}

	/**
	 * 单个边界面所在平面。
	 */
	private record PlaneKey(BoundaryAxis axis, int coordinate) {
	}

	/**
	 * 边界面中的单个单位方格。
	 */
	private record FaceCell(int u, int v) {
	}

	/**
	 * 二维边界边，构造时会做端点排序，确保共享边可抵消。
	 */
	private record Edge2D(int startU, int startV, int endU, int endV) {
		Edge2D {
			if (startU > endU || (startU == endU && startV > endV)) {
				int swappedStartU = startU;
				int swappedStartV = startV;
				startU = endU;
				startV = endV;
				endU = swappedStartU;
				endV = swappedStartV;
			}
		}
	}

	/**
	 * 世界坐标系下的一条预览线段。
	 */
	private record LineSegment(
		double startX,
		double startY,
		double startZ,
		double endX,
		double endY,
		double endZ,
		float normalX,
		float normalY,
		float normalZ
	) {
		static LineSegment of(LineSegmentKey key) {
			return of(key.startX(), key.startY(), key.startZ(), key.endX(), key.endY(), key.endZ());
		}

		static LineSegment of(double startX, double startY, double startZ, double endX, double endY, double endZ) {
			double deltaX = endX - startX;
			double deltaY = endY - startY;
			double deltaZ = endZ - startZ;
			double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
			float normalX = length <= 0.0D ? 1.0F : (float) (deltaX / length);
			float normalY = length <= 0.0D ? 0.0F : (float) (deltaY / length);
			float normalZ = length <= 0.0D ? 0.0F : (float) (deltaZ / length);
			return new LineSegment(startX, startY, startZ, endX, endY, endZ, normalX, normalY, normalZ);
		}
	}

	/**
	 * 用整数端点标识一条世界坐标边，用于跨平面去重。
	 */
	private record LineSegmentKey(int startX, int startY, int startZ, int endX, int endY, int endZ) {
		static LineSegmentKey of(int startX, int startY, int startZ, int endX, int endY, int endZ) {
			if (
				startX > endX
					|| (startX == endX && startY > endY)
					|| (startX == endX && startY == endY && startZ > endZ)
			) {
				int nextStartX = endX;
				int nextStartY = endY;
				int nextStartZ = endZ;
				endX = startX;
				endY = startY;
				endZ = startZ;
				startX = nextStartX;
				startY = nextStartY;
				startZ = nextStartZ;
			}
			return new LineSegmentKey(startX, startY, startZ, endX, endY, endZ);
		}
	}

	/**
	 * 边界平面坐标轴。
	 */
	private enum BoundaryAxis {
		X,
		Y,
		Z
	}

	/**
	 * quick-link 缓存对象预览的短 TTL 客户端缓存。
	 */
	private record CachedPreviewOutlineState(
		String dimensionKey,
		LinkNodeType cacheType,
		String cacheKey,
		int playerChunkX,
		int playerChunkZ,
		int renderDistance,
		long expireGameTick,
		List<PreviewOutlineBatch> previewBatches
	) {
		CachedPreviewOutlineState {
			previewBatches = List.copyOf(previewBatches);
		}

		boolean matches(
			String dimensionKey,
			LinkNodeType cacheType,
			String cacheKey,
			int playerChunkX,
			int playerChunkZ,
			int renderDistance,
			long gameTime
		) {
			return this.dimensionKey.equals(dimensionKey)
				&& this.cacheType == cacheType
				&& this.cacheKey.equals(cacheKey)
				&& this.playerChunkX == playerChunkX
				&& this.playerChunkZ == playerChunkZ
				&& this.renderDistance == renderDistance
				&& gameTime <= expireGameTick;
		}
	}

	/**
	 * 服务端回传的频道预览成员缓存。
	 */
	private record CachedChannelPreviewState(
		LinkNodeType cacheType,
		long channel,
		Set<Long> memberSerials,
		long expireGameTick
	) {
		CachedChannelPreviewState {
			memberSerials = Set.copyOf(memberSerials == null ? Set.of() : memberSerials);
		}

		boolean matches(LinkNodeType cacheType, long channel, long gameTime) {
			return this.cacheType == cacheType && this.channel == channel && gameTime <= expireGameTick;
		}
	}

	/**
	 * 客户端已发出但尚未过期的频道预览请求。
	 */
	private record PendingChannelPreviewRequest(LinkNodeType cacheType, long channel, long expireGameTick) {
		boolean matches(LinkNodeType cacheType, long channel, long gameTime) {
			return matchesKey(cacheType, channel) && gameTime <= expireGameTick;
		}

		boolean matchesKey(LinkNodeType cacheType, long channel) {
			return this.cacheType == cacheType && this.channel == channel;
		}
	}

	/**
	 * 第三形态显示对象去重键。
	 */
	private record VisualizedObjectKey(String objectTypeToken, long objectSerial) {
	}

	/**
	 * 第三形态单个对象的客户端本地引用。
	 */
	private record VisualizedObjectRef(
		String objectTypeToken,
		long objectSerial,
		String dimensionKey,
		long blockPosLong,
		String displayText
	) {
		boolean hasPosition() {
			return dimensionKey != null && !dimensionKey.isBlank();
		}
	}

	/**
	 * 第三形态单个显示对象的完整本地状态。
	 */
	private record VisualizedObjectState(
		VisualizedObjectRef source,
		List<VisualizedObjectRef> targets,
		QuickLinkNetwork.QuickLinkVisualizeTrackedObject revisionBaseline,
		OutlineColor color
	) {
		VisualizedObjectState {
			targets = List.copyOf(targets == null ? List.of() : targets);
			revisionBaseline = revisionBaseline == null
				? new QuickLinkNetwork.QuickLinkVisualizeTrackedObject("", 0L, 0L, 0L, 0L)
				: revisionBaseline;
		}
	}

	/**
	 * 第三形态中一条实际可见连接线及其两端对象。
	 */
	private record VisualizedConnection(
		LineSegment segment,
		VisualizedObjectRef source,
		VisualizedObjectRef target,
		OutlineColor color
	) {
	}

	/**
	 * 当前准星命中的第三形态线段对应对象。
	 */
	record HoveredVisualizedTarget(String objectTypeToken, long objectSerial, String displayText, long blockPosLong) {
		boolean isRepeater() {
			return LinkGuiDisplayContext.LINK_REPEATER.equals(objectTypeToken);
		}

		LinkNodeType resolveNodeType() {
			return LinkNodeSemantics.tryParseCanonicalType(objectTypeToken).orElse(null);
		}
	}

	/**
	 * 命中的候选目标比较项；优先按归一化距离，再按视线前向距离排序。
	 */
	private record HoveredVisualizedTargetCandidate(
		HoveredVisualizedTarget target,
		double rayDistance,
		double normalizedDistanceRatio
	) {
		boolean isBetterThan(HoveredVisualizedTargetCandidate currentBest) {
			if (currentBest == null) {
				return true;
			}
			if (normalizedDistanceRatio + SEGMENT_EPSILON < currentBest.normalizedDistanceRatio()) {
				return true;
			}
			if (currentBest.normalizedDistanceRatio() + SEGMENT_EPSILON < normalizedDistanceRatio) {
				return false;
			}
			return rayDistance + SEGMENT_EPSILON < currentBest.rayDistance();
		}
	}

	/**
	 * 视线检测段到目标线段的最近距离结果。
	 */
	private record ClosestLineApproach(double distance, double rayDistance) {
	}

	/**
	 * 两条有限线段最近点求解结果。
	 */
	private record SegmentClosestApproach(double firstParameter, double secondParameter, double distanceSqr) {
	}
}
