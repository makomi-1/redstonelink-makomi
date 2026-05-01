package com.makomi.client.render;

import com.makomi.RedstoneLink;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * 世界外显穿透几何公共渲染支持。
 * <p>
 * 1.21.11 起世界线框与 debug filled box 默认仍受深度测试约束，
 * 本模组的连线、描边、定向箭头与过滤器范围需要统一改为“穿墙可见”语义，
 * 因此这里集中维护禁用深度测试的自定义线段/面层管线。
 * </p>
 */
final class SeeThroughWorldGeometryRenderSupport {
	private static final RenderPipeline LINES_THROUGH_WALLS = RenderPipelines.register(
		RenderPipeline
			.builder(RenderPipelines.LINES_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "pipeline/overlay_lines_through_walls"))
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withDepthWrite(false)
			.build()
	);
	private static final RenderPipeline FILLED_QUADS_THROUGH_WALLS = RenderPipelines.register(
		RenderPipeline
			.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "pipeline/overlay_filled_quads_through_walls"))
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withCull(false)
			.withDepthWrite(false)
			.build()
	);
	private static final ByteBufferBuilder LINE_ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static final ByteBufferBuilder FILLED_ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
	private static MappableRingBuffer lineVertexBuffer;
	private static MappableRingBuffer filledVertexBuffer;

	private SeeThroughWorldGeometryRenderSupport() {
	}

	/**
	 * 提交并立即绘制一批穿透世界遮挡的线段。
	 */
	static void renderLines(
		WorldRenderContext worldRenderContext,
		List<ColoredLineSegment> lineSegments
	) {
		if (lineSegments == null || lineSegments.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.getMainRenderTarget() == null || worldRenderContext == null || worldRenderContext.matrices() == null) {
			return;
		}

		BufferBuilder bufferBuilder = new BufferBuilder(
			LINE_ALLOCATOR,
			LINES_THROUGH_WALLS.getVertexFormatMode(),
			LINES_THROUGH_WALLS.getVertexFormat()
		);
		PoseStack.Pose pose = worldRenderContext.matrices().last();
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
		for (ColoredLineSegment lineSegment : lineSegments) {
			writeLine(bufferBuilder, pose, cameraPosition, lineSegment);
		}
		finishAndDraw(
			minecraft,
			bufferBuilder,
			LINES_THROUGH_WALLS,
			LINE_ALLOCATOR,
			"see through line pipeline",
			buffer -> lineVertexBuffer = buffer,
			lineVertexBuffer
		);
		if (lineVertexBuffer != null) {
			lineVertexBuffer.rotate();
		}
	}

	/**
	 * 提交并立即绘制一批穿透世界遮挡的半透明面层。
	 */
	static void renderFilledQuads(
		WorldRenderContext worldRenderContext,
		List<ColoredQuad> quads
	) {
		if (quads == null || quads.isEmpty()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.getMainRenderTarget() == null || worldRenderContext == null || worldRenderContext.matrices() == null) {
			return;
		}

		BufferBuilder bufferBuilder = new BufferBuilder(
			FILLED_ALLOCATOR,
			FILLED_QUADS_THROUGH_WALLS.getVertexFormatMode(),
			FILLED_QUADS_THROUGH_WALLS.getVertexFormat()
		);
		PoseStack.Pose pose = worldRenderContext.matrices().last();
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
		for (ColoredQuad quad : quads) {
			writeQuad(bufferBuilder, pose, cameraPosition, quad);
		}
		finishAndDraw(
			minecraft,
			bufferBuilder,
			FILLED_QUADS_THROUGH_WALLS,
			FILLED_ALLOCATOR,
			"see through filled quad pipeline",
			buffer -> filledVertexBuffer = buffer,
			filledVertexBuffer
		);
		if (filledVertexBuffer != null) {
			filledVertexBuffer.rotate();
		}
	}

	/**
	 * 释放公共穿透渲染支持占用的 CPU/GPU 缓冲。
	 */
	static void close() {
		LINE_ALLOCATOR.close();
		FILLED_ALLOCATOR.close();
		if (lineVertexBuffer != null) {
			lineVertexBuffer.close();
			lineVertexBuffer = null;
		}
		if (filledVertexBuffer != null) {
			filledVertexBuffer.close();
			filledVertexBuffer = null;
		}
	}

	/**
	 * 写入一条世界空间线段。
	 */
	private static void writeLine(
		BufferBuilder bufferBuilder,
		PoseStack.Pose pose,
		Vec3 cameraPosition,
		ColoredLineSegment lineSegment
	) {
		if (bufferBuilder == null || pose == null || cameraPosition == null || lineSegment == null) {
			return;
		}
		float startX = (float) (lineSegment.startX() - cameraPosition.x);
		float startY = (float) (lineSegment.startY() - cameraPosition.y);
		float startZ = (float) (lineSegment.startZ() - cameraPosition.z);
		float endX = (float) (lineSegment.endX() - cameraPosition.x);
		float endY = (float) (lineSegment.endY() - cameraPosition.y);
		float endZ = (float) (lineSegment.endZ() - cameraPosition.z);
		bufferBuilder
			.addVertex(pose, startX, startY, startZ)
			.setColor(lineSegment.red(), lineSegment.green(), lineSegment.blue(), lineSegment.alpha())
			.setNormal(pose, lineSegment.normalX(), lineSegment.normalY(), lineSegment.normalZ())
			.setLineWidth(1.0F);
		bufferBuilder
			.addVertex(pose, endX, endY, endZ)
			.setColor(lineSegment.red(), lineSegment.green(), lineSegment.blue(), lineSegment.alpha())
			.setNormal(pose, lineSegment.normalX(), lineSegment.normalY(), lineSegment.normalZ())
			.setLineWidth(1.0F);
	}

	/**
	 * 写入一个世界空间四边形。
	 */
	private static void writeQuad(
		BufferBuilder bufferBuilder,
		PoseStack.Pose pose,
		Vec3 cameraPosition,
		ColoredQuad quad
	) {
		if (bufferBuilder == null || pose == null || cameraPosition == null || quad == null) {
			return;
		}
		addQuadVertex(bufferBuilder, pose, cameraPosition, quad.x1(), quad.y1(), quad.z1(), quad.red(), quad.green(), quad.blue(), quad.alpha());
		addQuadVertex(bufferBuilder, pose, cameraPosition, quad.x2(), quad.y2(), quad.z2(), quad.red(), quad.green(), quad.blue(), quad.alpha());
		addQuadVertex(bufferBuilder, pose, cameraPosition, quad.x3(), quad.y3(), quad.z3(), quad.red(), quad.green(), quad.blue(), quad.alpha());
		addQuadVertex(bufferBuilder, pose, cameraPosition, quad.x4(), quad.y4(), quad.z4(), quad.red(), quad.green(), quad.blue(), quad.alpha());
	}

	/**
	 * 写入四边形的单个顶点。
	 */
	private static void addQuadVertex(
		BufferBuilder bufferBuilder,
		PoseStack.Pose pose,
		Vec3 cameraPosition,
		double x,
		double y,
		double z,
		int red,
		int green,
		int blue,
		int alpha
	) {
		bufferBuilder
			.addVertex(pose, (float) (x - cameraPosition.x), (float) (y - cameraPosition.y), (float) (z - cameraPosition.z))
			.setColor(red, green, blue, alpha);
	}

	/**
	 * 完成缓冲构建、上传并执行一次 draw call。
	 */
	private static void finishAndDraw(
		Minecraft minecraft,
		BufferBuilder bufferBuilder,
		RenderPipeline renderPipeline,
		ByteBufferBuilder allocator,
		String bufferLabel,
		RingBufferSetter ringBufferSetter,
		MappableRingBuffer currentRingBuffer
	) {
		if (minecraft == null || bufferBuilder == null || renderPipeline == null || allocator == null || ringBufferSetter == null) {
			return;
		}
		MeshData builtBuffer = bufferBuilder.buildOrThrow();
		MeshData.DrawState drawState = builtBuffer.drawState();
		int vertexBufferSize = drawState.vertexCount() * drawState.format().getVertexSize();
		MappableRingBuffer ensuredRingBuffer = ensureRingBuffer(currentRingBuffer, vertexBufferSize, bufferLabel);
		ringBufferSetter.set(ensuredRingBuffer);
		upload(ensuredRingBuffer, builtBuffer);
		draw(minecraft, builtBuffer, drawState, ensuredRingBuffer.currentBuffer(), renderPipeline, allocator);
	}

	/**
	 * 确保 ring buffer 容量满足本次 draw 需求。
	 */
	private static MappableRingBuffer ensureRingBuffer(
		MappableRingBuffer currentRingBuffer,
		int vertexBufferSize,
		String bufferLabel
	) {
		if (currentRingBuffer == null || currentRingBuffer.size() < vertexBufferSize) {
			if (currentRingBuffer != null) {
				currentRingBuffer.close();
			}
			return new MappableRingBuffer(
				() -> RedstoneLink.MOD_ID + " " + bufferLabel,
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
				vertexBufferSize
			);
		}
		return currentRingBuffer;
	}

	/**
	 * 将顶点缓冲上传到当前 ring buffer。
	 */
	private static void upload(MappableRingBuffer ringBuffer, MeshData builtBuffer) {
		if (ringBuffer == null || builtBuffer == null) {
			return;
		}
		CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
		try (
			GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
				ringBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()),
				false,
				true
			)
		) {
			MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
		}
	}

	/**
	 * 执行一次实际的渲染提交。
	 */
	private static void draw(
		Minecraft minecraft,
		MeshData builtBuffer,
		MeshData.DrawState drawState,
		GpuBuffer vertices,
		RenderPipeline renderPipeline,
		ByteBufferBuilder allocator
	) {
		if (minecraft == null || builtBuffer == null || drawState == null || vertices == null || renderPipeline == null || allocator == null) {
			return;
		}
		GpuBuffer indices;
		VertexFormat.IndexType indexType;
		if (renderPipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
			builtBuffer.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
			indices = renderPipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
			indexType = builtBuffer.drawState().indexType();
		} else {
			RenderSystem.AutoStorageIndexBuffer sequentialBuffer = RenderSystem.getSequentialBuffer(renderPipeline.getVertexFormatMode());
			indices = sequentialBuffer.getBuffer(drawState.indexCount());
			indexType = sequentialBuffer.type();
		}

		var dynamicTransforms = RenderSystem
			.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
		try (
			RenderPass renderPass = RenderSystem
				.getDevice()
				.createCommandEncoder()
				.createRenderPass(
					() -> RedstoneLink.MOD_ID + " see through overlay pipeline",
					minecraft.getMainRenderTarget().getColorTextureView(),
					OptionalInt.empty(),
					minecraft.getMainRenderTarget().getDepthTextureView(),
					OptionalDouble.empty()
				)
		) {
			renderPass.setPipeline(renderPipeline);
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);
			renderPass.setVertexBuffer(0, vertices);
			renderPass.setIndexBuffer(indices, indexType);
			renderPass.drawIndexed(0, 0, drawState.indexCount(), 1);
		}
		builtBuffer.close();
	}

	/**
	 * 穿透线段的最小渲染参数。
	 */
	record ColoredLineSegment(
		double startX,
		double startY,
		double startZ,
		double endX,
		double endY,
		double endZ,
		float normalX,
		float normalY,
		float normalZ,
		int red,
		int green,
		int blue,
		int alpha
	) {
	}

	/**
	 * 穿透半透明面层的最小渲染参数。
	 */
	record ColoredQuad(
		double x1,
		double y1,
		double z1,
		double x2,
		double y2,
		double z2,
		double x3,
		double y3,
		double z3,
		double x4,
		double y4,
		double z4,
		int red,
		int green,
		int blue,
		int alpha
	) {
	}

	/**
	 * ring buffer 赋值桥，避免在公共上传逻辑里展开分支。
	 */
	@FunctionalInterface
	private interface RingBufferSetter {
		void set(MappableRingBuffer ringBuffer);
	}
}
