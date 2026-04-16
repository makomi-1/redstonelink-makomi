package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 状态面板网络 Payload 稳定契约测试。
 */
@Tag("stable-core")
class StatePanelNetworkPayloadTest {
	/**
	 * 打开面板包编解码应保留订阅列表。
	 */
	@Test
	void openPayloadCodecRoundTripShouldPreserveSubscriptions() {
		StatePanelNetwork.OpenStatePanelPayload original = new StatePanelNetwork.OpenStatePanelPayload(
			List.of(
				new StatePanelNetwork.SubscriptionEntryPayload(LinkNodeType.CORE, 3L, "中控(#3)"),
				new StatePanelNetwork.SubscriptionEntryPayload(LinkNodeType.TRIGGER_SOURCE, 9L, "大门1(#9)")
			)
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.OpenStatePanelPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.OpenStatePanelPayload decoded = StatePanelNetwork.OpenStatePanelPayload.CODEC.decode(buffer);

		assertEquals(original.subscriptions(), decoded.subscriptions());
		assertEquals(StatePanelNetwork.OpenStatePanelPayload.TYPE, decoded.type());
	}

	/**
	 * 订阅请求编解码应保留类型 token 与序号表达式。
	 */
	@Test
	void subscribePayloadCodecRoundTripShouldPreserveFields() {
		StatePanelNetwork.SubscribeStatePanelPayload original = new StatePanelNetwork.SubscribeStatePanelPayload(
			"triggerSource",
			"1:5/8"
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.SubscribeStatePanelPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.SubscribeStatePanelPayload decoded = StatePanelNetwork.SubscribeStatePanelPayload.CODEC.decode(buffer);

		assertEquals(original.nodeTypeToken(), decoded.nodeTypeToken());
		assertEquals(original.serialExpression(), decoded.serialExpression());
	}

	/**
	 * 清空全部订阅请求包应支持空体往返编解码。
	 */
	@Test
	void cleanAllPayloadCodecRoundTripShouldPreserveType() {
		StatePanelNetwork.CleanAllStatePanelPayload original = new StatePanelNetwork.CleanAllStatePanelPayload();
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.CleanAllStatePanelPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.CleanAllStatePanelPayload decoded = StatePanelNetwork.CleanAllStatePanelPayload.CODEC.decode(buffer);

		assertEquals(StatePanelNetwork.CleanAllStatePanelPayload.TYPE, decoded.type());
	}

	/**
	 * 快照回执编解码应保留节点状态字段。
	 */
	@Test
	void snapshotPayloadCodecRoundTripShouldPreserveEntries() {
		StatePanelNetwork.StatePanelSnapshotPayload original = new StatePanelNetwork.StatePanelSnapshotPayload(
			List.of(
				new StatePanelNetwork.StatePanelSnapshotEntry(
					LinkNodeType.CORE,
					21L,
					"中控(#21)",
					true,
					false,
					true,
					true,
					7,
					15,
					true
				),
				new StatePanelNetwork.StatePanelSnapshotEntry(
					LinkNodeType.TRIGGER_SOURCE,
					33L,
					NodeAliasDisplayUtil.formatDisplayText("", 33L),
					false,
					false,
					false,
					false,
					0,
					0,
					false
				)
			)
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.StatePanelSnapshotPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.StatePanelSnapshotPayload decoded = StatePanelNetwork.StatePanelSnapshotPayload.CODEC.decode(buffer);

		assertEquals(original.entries(), decoded.entries());
	}

	/**
	 * 反馈回执编解码应保留成功标记、翻译键与参数。
	 */
	@Test
	void feedbackPayloadCodecRoundTripShouldPreserveFields() {
		StatePanelNetwork.StatePanelFeedbackPayload original = new StatePanelNetwork.StatePanelFeedbackPayload(
			true,
			"message.redstonelink.state_panel.subscribe.done",
			List.of("12")
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.StatePanelFeedbackPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.StatePanelFeedbackPayload decoded = StatePanelNetwork.StatePanelFeedbackPayload.CODEC.decode(buffer);

		assertEquals(original.success(), decoded.success());
		assertEquals(original.messageKey(), decoded.messageKey());
		assertEquals(original.messageArgs(), decoded.messageArgs());
	}

	/**
	 * 录制开始请求编解码应保留标题、采样参数与自动打开网页开关。
	 */
	@Test
	void recordingStartPayloadCodecRoundTripShouldPreserveFields() {
		StatePanelNetwork.StartStatePanelRecordingPayload original = new StatePanelNetwork.StartStatePanelRecordingPayload(
			"test-recording",
			2,
			128,
			true
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.StartStatePanelRecordingPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.StartStatePanelRecordingPayload decoded = StatePanelNetwork.StartStatePanelRecordingPayload.CODEC.decode(buffer);

		assertEquals(original.title(), decoded.title());
		assertEquals(original.sampleEveryTicks(), decoded.sampleEveryTicks());
		assertEquals(original.capacityPerNode(), decoded.capacityPerNode());
		assertEquals(original.autoOpenWeb(), decoded.autoOpenWeb());
	}

	/**
	 * 录制会话状态回执编解码应保留运行态摘要字段。
	 */
	@Test
	void recordingSessionPayloadCodecRoundTripShouldPreserveFields() {
		StatePanelNetwork.StatePanelRecordingSessionPayload original = new StatePanelNetwork.StatePanelRecordingSessionPayload(
			true,
			"test-recording",
			3,
			256,
			false,
			12,
			7,
			1024L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.StatePanelRecordingSessionPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.StatePanelRecordingSessionPayload decoded = StatePanelNetwork.StatePanelRecordingSessionPayload.CODEC.decode(buffer);

		assertEquals(original.active(), decoded.active());
		assertEquals(original.title(), decoded.title());
		assertEquals(original.sampleEveryTicks(), decoded.sampleEveryTicks());
		assertEquals(original.capacityPerNode(), decoded.capacityPerNode());
		assertEquals(original.autoOpenWeb(), decoded.autoOpenWeb());
		assertEquals(original.subscriptionCount(), decoded.subscriptionCount());
		assertEquals(original.mountedCount(), decoded.mountedCount());
		assertEquals(original.startedTick(), decoded.startedTick());
	}

	/**
	 * 录制结果分块编解码应保留文件名、分块索引与二进制内容。
	 */
	@Test
	void recordingExportChunkPayloadCodecRoundTripShouldPreserveFields() {
		StatePanelNetwork.StatePanelRecordingExportChunkPayload original = new StatePanelNetwork.StatePanelRecordingExportChunkPayload(
			"recording-1-test.json.gz",
			1,
			3,
			true,
			new byte[] { 1, 2, 3, 4 }
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		StatePanelNetwork.StatePanelRecordingExportChunkPayload.CODEC.encode(buffer, original);
		StatePanelNetwork.StatePanelRecordingExportChunkPayload decoded = StatePanelNetwork.StatePanelRecordingExportChunkPayload.CODEC.decode(buffer);

		assertEquals(original.fileName(), decoded.fileName());
		assertEquals(original.chunkIndex(), decoded.chunkIndex());
		assertEquals(original.totalChunks(), decoded.totalChunks());
		assertEquals(original.autoOpenWeb(), decoded.autoOpenWeb());
		assertArrayEquals(new byte[] { 1, 2, 3, 4 }, decoded.chunkBytes());
	}

	/**
	 * 订阅请求在解包阶段应拒绝超过显式上限的序号表达式。
	 */
	@Test
	void subscribePayloadCodecShouldRejectTooLongSerialExpression() {
		String tooLongExpression = "1".repeat(RedstoneLinkConfig.command().linkSetMaxInputLength() + 1);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeUtf("triggerSource");
		buffer.writeUtf(tooLongExpression);

		assertThrows(RuntimeException.class, () -> StatePanelNetwork.SubscribeStatePanelPayload.CODEC.decode(buffer));
	}

	/**
	 * 反馈回执在解包阶段应拒绝超过显式上限的翻译键。
	 */
	@Test
	void feedbackPayloadCodecShouldRejectTooLongMessageKey() {
		String tooLongMessageKey = "m".repeat(257);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeBoolean(true);
		buffer.writeUtf(tooLongMessageKey);
		buffer.writeVarInt(0);

		assertThrows(RuntimeException.class, () -> StatePanelNetwork.StatePanelFeedbackPayload.CODEC.decode(buffer));
	}

	/**
	 * 录制结果分块在解包阶段应拒绝超过显式上限的文件名。
	 */
	@Test
	void recordingExportChunkPayloadCodecShouldRejectTooLongFileName() {
		String tooLongFileName = "a".repeat(161);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeUtf(tooLongFileName);
		buffer.writeVarInt(0);
		buffer.writeVarInt(1);
		buffer.writeBoolean(true);
		buffer.writeByteArray(new byte[] { 1 });

		assertThrows(RuntimeException.class, () -> StatePanelNetwork.StatePanelRecordingExportChunkPayload.CODEC.decode(buffer));
	}
}
