package com.makomi.client.render;

import com.makomi.RedstoneLink;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.DepthTestFunction;
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
 * 智能眼镜第三形态穿墙连线专用渲染支持。
 * <p>
 * 1.21.11 起 vanilla `RenderType` 的世界线框默认仍受深度测试约束，
 * 无法稳定满足“穿墙连线”语义，因此这里单独维护一条禁用深度测试的自定义管线。
 * </p>
 */
final class QuickLinkVisualizedLineRenderSupport {
	private static final RenderPipeline VISUALIZED_LINES_THROUGH_WALLS = RenderPipelines.register(
		RenderPipeline
			.builder(RenderPipelines.LINES_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, "pipeline/visualized_lines_through_walls"))
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withDepthWrite(false)
			.build()
	);
	private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
	private static MappableRingBuffer vertexBuffer;

	private QuickLinkVisualizedLineRenderSupport() {
	}

	/**
	 * 按当前世界渲染上下文提交并立即绘制穿墙连线。
	 */
	static void render(WorldRenderContext worldRenderContext, List<ColoredLineSegment> lineSegments) {
		Minecraft minecraft = Minecraft.getInstance();
		if (
			worldRenderContext == null
				|| worldRenderContext.matrices() == null
				|| lineSegments == null
				|| lineSegments.isEmpty()
				|| minecraft == null
				|| minecraft.getMainRenderTarget() == null
		) {
			return;
		}

		BufferBuilder bufferBuilder = new BufferBuilder(
			ALLOCATOR,
			VISUALIZED_LINES_THROUGH_WALLS.getVertexFormatMode(),
			VISUALIZED_LINES_THROUGH_WALLS.getVertexFormat()
		);
		PoseStack.Pose pose = worldRenderContext.matrices().last();
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
		for (ColoredLineSegment lineSegment : lineSegments) {
			writeLine(bufferBuilder, pose, cameraPosition, lineSegment);
		}

		MeshData builtBuffer = bufferBuilder.buildOrThrow();
		MeshData.DrawState drawState = builtBuffer.drawState();
		VertexFormat vertexFormat = drawState.format();
		GpuBuffer vertices = upload(drawState, vertexFormat, builtBuffer);
		draw(minecraft, builtBuffer, drawState, vertices, vertexFormat);
		vertexBuffer.rotate();
	}

	/**
	 * 释放自定义穿墙连线管线持有的 GPU/CPU 缓冲。
	 */
	static void close() {
		ALLOCATOR.close();
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
	}

	/**
	 * 将线段顶点写入自定义管线缓冲。
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
	 * 将构建好的顶点数据上传到 ring buffer。
	 */
	private static GpuBuffer upload(MeshData.DrawState drawState, VertexFormat vertexFormat, MeshData builtBuffer) {
		int vertexBufferSize = drawState.vertexCount() * vertexFormat.getVertexSize();
		if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
			if (vertexBuffer != null) {
				vertexBuffer.close();
			}
			vertexBuffer = new MappableRingBuffer(
				() -> RedstoneLink.MOD_ID + " visualized line pipeline",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
				vertexBufferSize
			);
		}

		CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
		try (
			GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
				vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()),
				false,
				true
			)
		) {
			MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
		}
		return vertexBuffer.currentBuffer();
	}

	/**
	 * 执行一次自定义管线 draw call。
	 */
	private static void draw(
		Minecraft minecraft,
		MeshData builtBuffer,
		MeshData.DrawState drawState,
		GpuBuffer vertices,
		VertexFormat vertexFormat
	) {
		GpuBuffer indices;
		VertexFormat.IndexType indexType;
		if (VISUALIZED_LINES_THROUGH_WALLS.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
			builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
			indices = VISUALIZED_LINES_THROUGH_WALLS.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
			indexType = builtBuffer.drawState().indexType();
		} else {
			RenderSystem.AutoStorageIndexBuffer sequentialBuffer = RenderSystem.getSequentialBuffer(
				VISUALIZED_LINES_THROUGH_WALLS.getVertexFormatMode()
			);
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
					() -> RedstoneLink.MOD_ID + " visualized line pipeline",
					minecraft.getMainRenderTarget().getColorTextureView(),
					OptionalInt.empty(),
					minecraft.getMainRenderTarget().getDepthTextureView(),
					OptionalDouble.empty()
				)
		) {
			renderPass.setPipeline(VISUALIZED_LINES_THROUGH_WALLS);
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);
			renderPass.setVertexBuffer(0, vertices);
			renderPass.setIndexBuffer(indices, indexType);
			renderPass.drawIndexed(0, 0, drawState.indexCount(), 1);
		}
		builtBuffer.close();
	}

	/**
	 * 单条穿墙连线所需的最小渲染参数。
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
}
