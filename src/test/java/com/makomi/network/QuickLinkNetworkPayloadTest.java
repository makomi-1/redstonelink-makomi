package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.QuickLinkToolData;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 快速连接工具网络 Payload 稳定契约测试。
 */
@Tag("stable-core")
class QuickLinkNetworkPayloadTest {
	/**
	 * 编辑器打开包编解码往返应保持缓存快照字段一致。
	 */
	@Test
	void openEditorPayloadCodecRoundTripShouldPreserveSnapshot() {
		QuickLinkToolData.Snapshot snapshot = new QuickLinkToolData.Snapshot(
			QuickLinkToolData.Mode.SERIAL,
			LinkNodeType.CORE,
			"1:5/7",
			"alpha"
		);
		QuickLinkNetwork.OpenQuickLinkEditorPayload original = new QuickLinkNetwork.OpenQuickLinkEditorPayload(snapshot);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.OpenQuickLinkEditorPayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.OpenQuickLinkEditorPayload decoded = QuickLinkNetwork.OpenQuickLinkEditorPayload.CODEC.decode(buffer);

		assertEquals(original.snapshot(), decoded.snapshot());
		assertEquals(QuickLinkNetwork.OpenQuickLinkEditorPayload.TYPE, decoded.type());
	}

	/**
	 * 采集请求编解码往返应保留命中维度与方块坐标。
	 */
	@Test
	void collectPayloadCodecRoundTripShouldPreserveFields() {
		QuickLinkNetwork.CollectQuickLinkPayload original = new QuickLinkNetwork.CollectQuickLinkPayload(
			"minecraft:overworld",
			42L,
			"triggerSource",
			77L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.CollectQuickLinkPayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.CollectQuickLinkPayload decoded = QuickLinkNetwork.CollectQuickLinkPayload.CODEC.decode(buffer);

		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.expectedNodeTypeToken(), decoded.expectedNodeTypeToken());
		assertEquals(original.expectedNodeSerial(), decoded.expectedNodeSerial());
	}

	/**
	 * 应用请求编解码往返应保留命中维度、方块坐标与期望节点身份。
	 */
	@Test
	void applyPayloadCodecRoundTripShouldPreserveFields() {
		QuickLinkNetwork.ApplyQuickLinkPayload original = new QuickLinkNetwork.ApplyQuickLinkPayload(
			"minecraft:the_nether",
			84L,
			"core",
			105L,
			17L,
			3L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.ApplyQuickLinkPayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.ApplyQuickLinkPayload decoded = QuickLinkNetwork.ApplyQuickLinkPayload.CODEC.decode(buffer);

		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.expectedNodeTypeToken(), decoded.expectedNodeTypeToken());
		assertEquals(original.expectedNodeSerial(), decoded.expectedNodeSerial());
		assertEquals(original.expectedGraphRevision(), decoded.expectedGraphRevision());
		assertEquals(original.expectedSourceRevision(), decoded.expectedSourceRevision());
	}

	/**
	 * apply 预检请求编解码往返应保留命中节点身份。
	 */
	@Test
	void requestApplyBaselinePayloadCodecRoundTripShouldPreserveFields() {
		QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload original = new QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload(
			"minecraft:overworld",
			128L,
			"triggerSource",
			9L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload decoded =
			QuickLinkNetwork.RequestApplyQuickLinkBaselinePayload.CODEC.decode(buffer);

		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.expectedNodeTypeToken(), decoded.expectedNodeTypeToken());
		assertEquals(original.expectedNodeSerial(), decoded.expectedNodeSerial());
	}

	/**
	 * apply revision 基线回包编解码往返应保留目标上下文与 revision 字段。
	 */
	@Test
	void applyBaselinePayloadCodecRoundTripShouldPreserveFields() {
		QuickLinkNetwork.ApplyQuickLinkBaselinePayload original = new QuickLinkNetwork.ApplyQuickLinkBaselinePayload(
			"minecraft:the_end",
			256L,
			"core",
			42L,
			18L,
			0L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.ApplyQuickLinkBaselinePayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.ApplyQuickLinkBaselinePayload decoded = QuickLinkNetwork.ApplyQuickLinkBaselinePayload.CODEC.decode(buffer);

		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPosLong(), decoded.blockPosLong());
		assertEquals(original.expectedNodeTypeToken(), decoded.expectedNodeTypeToken());
		assertEquals(original.expectedNodeSerial(), decoded.expectedNodeSerial());
		assertEquals(original.graphRevision(), decoded.graphRevision());
		assertEquals(original.sourceRevision(), decoded.sourceRevision());
	}

	/**
	 * 统一 quick-link 反馈回执应保留成功状态、翻译键和参数列表。
	 */
	@Test
	void feedbackPayloadCodecRoundTripShouldPreserveFields() {
		QuickLinkNetwork.QuickLinkFeedbackPayload original = new QuickLinkNetwork.QuickLinkFeedbackPayload(
			true,
			"message.redstonelink.quick_link.apply.done.core",
			List.of("3", "42")
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		QuickLinkNetwork.QuickLinkFeedbackPayload.CODEC.encode(buffer, original);
		QuickLinkNetwork.QuickLinkFeedbackPayload decoded = QuickLinkNetwork.QuickLinkFeedbackPayload.CODEC.decode(buffer);

		assertEquals(original.success(), decoded.success());
		assertEquals(original.messageKey(), decoded.messageKey());
		assertEquals(original.messageArgs(), decoded.messageArgs());
	}

	/**
	 * 保存请求在解包阶段应拒绝超过显式上限的表达式，避免把超长文本留给业务层后置处理。
	 */
	@Test
	void savePayloadCodecShouldRejectTooLongSerialExpression() {
		String tooLongExpression = "1".repeat(RedstoneLinkConfig.command().linkSetMaxInputLength() + 1);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeUtf(QuickLinkToolData.Mode.SERIAL.token());
		buffer.writeUtf("core");
		buffer.writeUtf(tooLongExpression);
		buffer.writeUtf("");

		assertThrows(RuntimeException.class, () -> QuickLinkNetwork.SaveQuickLinkPayload.CODEC.decode(buffer));
	}

	/**
	 * 采集请求在解包阶段应拒绝超过维度键上限的输入。
	 */
	@Test
	void collectPayloadCodecShouldRejectTooLongDimensionKey() {
		String tooLongDimensionKey = "x".repeat(PairingNetworkPayloadSupport.DIMENSION_KEY_MAX_LENGTH + 1);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeUtf(tooLongDimensionKey);
		buffer.writeLong(42L);
		buffer.writeUtf("triggerSource");
		buffer.writeVarLong(77L);

		assertThrows(RuntimeException.class, () -> QuickLinkNetwork.CollectQuickLinkPayload.CODEC.decode(buffer));
	}
}
