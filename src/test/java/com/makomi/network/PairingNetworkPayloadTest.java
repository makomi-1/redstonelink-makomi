package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.makomi.data.CrossChunkNodeIdentity;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.NodeAliasDisplayUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 配对网络 Payload 的稳定契约测试。
 */
@Tag("stable-core")
class PairingNetworkPayloadTest {

	/**
	 * 触发源配对包 targets 应做不可变拷贝，避免外部修改污染消息体。
	 */
	@Test
	void triggerSourcePayloadShouldCopyAndFreezeTargets() {
		List<Long> source = new ArrayList<>(List.of(7L, 3L, 9L));
		PairingNetwork.OpenTriggerSourcePairingPayload payload = new PairingNetwork.OpenTriggerSourcePairingPayload(
			100L,
			source,
			8L,
			5L,
			0L,
			LinkGuiDisplayContext.LINK_TOGGLE_BUTTON,
			"大门1",
			"大门1(#100)"
		);

		assertNotSame(source, payload.targets());
		assertEquals(List.of(7L, 3L, 9L), payload.targets());

		source.add(11L);
		assertEquals(List.of(7L, 3L, 9L), payload.targets());
		assertThrows(UnsupportedOperationException.class, () -> payload.targets().add(12L));
	}

	/**
	 * 核心配对包 targets 应做不可变拷贝，避免外部修改污染消息体。
	 */
	@Test
	void corePayloadShouldCopyAndFreezeTargets() {
		List<Long> source = new ArrayList<>(List.of(2L, 5L));
		PairingNetwork.OpenCorePairingPayload payload = new PairingNetwork.OpenCorePairingPayload(
			200L,
			source,
			9L,
			0L,
			6L,
			LinkGuiDisplayContext.LINK_REDSTONE_CORE,
			"中控",
			"中控(#200)"
		);

		assertNotSame(source, payload.targets());
		assertEquals(List.of(2L, 5L), payload.targets());

		source.clear();
		assertEquals(List.of(2L, 5L), payload.targets());
		assertThrows(UnsupportedOperationException.class, () -> payload.targets().add(6L));
	}

	/**
	 * triggerSource 结构化提交包应规范化空文本并保持字段稳定。
	 */
	@Test
	void submitTriggerSourcePairingPayloadShouldNormalizeExpression() {
		PairingNetwork.SubmitTriggerSourcePairingPayload payload = new PairingNetwork.SubmitTriggerSourcePairingPayload(
			300L,
			" 1/3:5 ",
			12L
		);
		PairingNetwork.SubmitTriggerSourcePairingPayload emptyPayload = new PairingNetwork.SubmitTriggerSourcePairingPayload(
			301L,
			null,
			0L
		);

		assertEquals(300L, payload.sourceSerial());
		assertEquals("1/3:5", payload.targetsExpression());
		assertEquals(12L, payload.expectedSourceRevision());
		assertEquals("", emptyPayload.targetsExpression());
	}

	/**
	 * core 结构化提交包应规范化空文本并保持字段稳定。
	 */
	@Test
	void submitCorePairingPayloadShouldNormalizeExpression() {
		PairingNetwork.SubmitCorePairingPayload payload = new PairingNetwork.SubmitCorePairingPayload(400L, " 2/4:6 ", 17L);
		PairingNetwork.SubmitCorePairingPayload emptyPayload = new PairingNetwork.SubmitCorePairingPayload(401L, null, 0L);

		assertEquals(400L, payload.coreSerial());
		assertEquals("2/4:6", payload.triggerSourceExpression());
		assertEquals(17L, payload.expectedCoreRevision());
		assertEquals("", emptyPayload.triggerSourceExpression());
	}

	/**
	 * pairing GUI alias 提交包应规范化空白并保持字段稳定。
	 */
	@Test
	void submitPairingAliasPayloadShouldNormalizeAlias() {
		PairingNetwork.SubmitPairingAliasPayload payload = new PairingNetwork.SubmitPairingAliasPayload("core", 512L, " 中控A ");
		PairingNetwork.SubmitPairingAliasPayload emptyPayload = new PairingNetwork.SubmitPairingAliasPayload("triggerSource", 513L, null);

		assertEquals("core", payload.sourceType());
		assertEquals(512L, payload.sourceSerial());
		assertEquals("中控A", payload.sourceAlias());
		assertEquals("", emptyPayload.sourceAlias());
	}

	/**
	 * 配对反馈包参数列表应做不可变拷贝，避免外部修改污染消息体。
	 */
	@Test
	void pairingFeedbackPayloadShouldCopyAndFreezeArgs() {
		List<String> args = new ArrayList<>(List.of("3", "42"));
		PairingNetwork.PairingFeedbackPayload payload = new PairingNetwork.PairingFeedbackPayload(
			true,
			"message.redstonelink.set_links_done",
			args
		);

		assertNotSame(args, payload.messageArgs());
		assertEquals(List.of("3", "42"), payload.messageArgs());

		args.add("extra");
		assertEquals(List.of("3", "42"), payload.messageArgs());
		assertThrows(UnsupportedOperationException.class, () -> payload.messageArgs().add("blocked"));
	}

	/**
	 * 触发源配对包编解码往返应保持字段一致。
	 */
	@Test
	void triggerSourcePayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.OpenTriggerSourcePairingPayload original = new PairingNetwork.OpenTriggerSourcePairingPayload(
			123L,
			List.of(1L, 4L, 9L),
			21L,
			13L,
			0L,
			LinkGuiDisplayContext.LINK_PULSE_EMITTER,
			"脉冲源",
			"脉冲源(#123)"
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenTriggerSourcePairingPayload decoded = PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.decode(
			buffer
		);

		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targets(), decoded.targets());
		assertEquals(original.graphRevision(), decoded.graphRevision());
		assertEquals(original.sourceRevision(), decoded.sourceRevision());
		assertEquals(original.coreRevision(), decoded.coreRevision());
		assertEquals(original.displayContextToken(), decoded.displayContextToken());
		assertEquals(original.sourceAlias(), decoded.sourceAlias());
		assertEquals(original.sourceDisplayText(), decoded.sourceDisplayText());
		assertEquals(PairingNetwork.OpenTriggerSourcePairingPayload.TYPE, decoded.type());
	}

	/**
	 * 核心配对包编解码往返应保持字段一致。
	 */
	@Test
	void corePayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.OpenCorePairingPayload original = new PairingNetwork.OpenCorePairingPayload(
			321L,
			List.of(8L, 6L),
			34L,
			0L,
			5L,
			LinkGuiDisplayContext.LINK_REDSTONE_DUST_CORE,
			"红石核心",
			"红石核心(#321)"
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenCorePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenCorePairingPayload decoded = PairingNetwork.OpenCorePairingPayload.CODEC.decode(buffer);

		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targets(), decoded.targets());
		assertEquals(original.graphRevision(), decoded.graphRevision());
		assertEquals(original.sourceRevision(), decoded.sourceRevision());
		assertEquals(original.coreRevision(), decoded.coreRevision());
		assertEquals(original.displayContextToken(), decoded.displayContextToken());
		assertEquals(original.sourceAlias(), decoded.sourceAlias());
		assertEquals(original.sourceDisplayText(), decoded.sourceDisplayText());
		assertEquals(PairingNetwork.OpenCorePairingPayload.TYPE, decoded.type());
	}

	/**
	 * triggerSource 结构化提交包编解码往返应保持字段一致。
	 */
	@Test
	void submitTriggerSourcePairingPayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.SubmitTriggerSourcePairingPayload original = new PairingNetwork.SubmitTriggerSourcePairingPayload(
			456L,
			"1/7:9",
			44L
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.SubmitTriggerSourcePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.SubmitTriggerSourcePairingPayload decoded = PairingNetwork.SubmitTriggerSourcePairingPayload.CODEC.decode(
			buffer
		);

		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targetsExpression(), decoded.targetsExpression());
		assertEquals(original.expectedSourceRevision(), decoded.expectedSourceRevision());
		assertEquals(PairingNetwork.SubmitTriggerSourcePairingPayload.TYPE, decoded.type());
	}

	/**
	 * core 结构化提交包编解码往返应保持字段一致。
	 */
	@Test
	void submitCorePairingPayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.SubmitCorePairingPayload original = new PairingNetwork.SubmitCorePairingPayload(654L, "3/8:9", 55L);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.SubmitCorePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.SubmitCorePairingPayload decoded = PairingNetwork.SubmitCorePairingPayload.CODEC.decode(buffer);

		assertEquals(original.coreSerial(), decoded.coreSerial());
		assertEquals(original.triggerSourceExpression(), decoded.triggerSourceExpression());
		assertEquals(original.expectedCoreRevision(), decoded.expectedCoreRevision());
		assertEquals(PairingNetwork.SubmitCorePairingPayload.TYPE, decoded.type());
	}

	/**
	 * pairing GUI alias 提交包编解码往返应保持字段一致。
	 */
	@Test
	void submitPairingAliasPayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.SubmitPairingAliasPayload original = new PairingNetwork.SubmitPairingAliasPayload("triggerSource", 777L, "大门A");
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.SubmitPairingAliasPayload.CODEC.encode(buffer, original);
		PairingNetwork.SubmitPairingAliasPayload decoded = PairingNetwork.SubmitPairingAliasPayload.CODEC.decode(buffer);

		assertEquals(original.sourceType(), decoded.sourceType());
		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.sourceAlias(), decoded.sourceAlias());
		assertEquals(PairingNetwork.SubmitPairingAliasPayload.TYPE, decoded.type());
	}

	/**
	 * 配对反馈包编解码往返应保持成功状态、翻译键和参数列表一致。
	 */
	@Test
	void pairingFeedbackPayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.PairingFeedbackPayload original = new PairingNetwork.PairingFeedbackPayload(
			false,
			"message.redstonelink.command.rate_limit.exceeded",
			List.of("1", "2")
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.PairingFeedbackPayload.CODEC.encode(buffer, original);
		PairingNetwork.PairingFeedbackPayload decoded = PairingNetwork.PairingFeedbackPayload.CODEC.decode(buffer);

		assertEquals(original.success(), decoded.success());
		assertEquals(original.messageKey(), decoded.messageKey());
		assertEquals(original.messageArgs(), decoded.messageArgs());
		assertEquals(PairingNetwork.PairingFeedbackPayload.TYPE, decoded.type());
	}

	/**
	 * pairing GUI alias 状态回包编解码往返应保持字段一致。
	 */
	@Test
	void pairingAliasStatePayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.PairingAliasStatePayload original = new PairingNetwork.PairingAliasStatePayload(
			"core",
			901L,
			"中控总站",
			"中控总站(#901)"
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.PairingAliasStatePayload.CODEC.encode(buffer, original);
		PairingNetwork.PairingAliasStatePayload decoded = PairingNetwork.PairingAliasStatePayload.CODEC.decode(buffer);

		assertEquals(original.sourceType(), decoded.sourceType());
		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.sourceAlias(), decoded.sourceAlias());
		assertEquals(original.sourceDisplayText(), decoded.sourceDisplayText());
		assertEquals(PairingNetwork.PairingAliasStatePayload.TYPE, decoded.type());
	}

	/**
	 * 编解码时应保留空目标列表。
	 */
	@Test
	void payloadCodecShouldAllowEmptyTargets() {
		PairingNetwork.OpenTriggerSourcePairingPayload original = new PairingNetwork.OpenTriggerSourcePairingPayload(
			77L,
			List.of(),
			0L,
			0L,
			0L,
			LinkGuiDisplayContext.TRIGGER_SOURCE,
			"",
			NodeAliasDisplayUtil.formatDisplayText("", 77L)
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenTriggerSourcePairingPayload decoded = PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.decode(
			buffer
		);

		assertEquals(77L, decoded.sourceSerial());
		assertEquals(List.of(), decoded.targets());
		assertEquals("", decoded.sourceAlias());
		assertEquals(NodeAliasDisplayUtil.formatDisplayText("", 77L), decoded.sourceDisplayText());
	}

	/**
	 * 当前连接快照包编解码往返应保留跨区块身份字段。
	 */
	@Test
	void currentLinksSnapshotPayloadCodecRoundTripShouldPreserveCrossChunkIdentity() {
		PairingNetwork.CurrentLinksSnapshotPayload original = new PairingNetwork.CurrentLinksSnapshotPayload(
			"minecraft:overworld",
			1234L,
			"triggerSource",
			88L,
			List.of(3L, 7L),
			CrossChunkNodeIdentity.FORCE_LOAD
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.CurrentLinksSnapshotPayload.CODEC.encode(buffer, original);
		PairingNetwork.CurrentLinksSnapshotPayload decoded = PairingNetwork.CurrentLinksSnapshotPayload.CODEC.decode(buffer);

		assertEquals(original.dimensionKey(), decoded.dimensionKey());
		assertEquals(original.blockPos(), decoded.blockPos());
		assertEquals(original.sourceType(), decoded.sourceType());
		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targets(), decoded.targets());
		assertEquals(original.crossChunkIdentity(), decoded.crossChunkIdentity());
	}

	/**
	 * 非法负载（size 为负数）应抛出异常。
	 */
	@Test
	void payloadCodecShouldRejectNegativeSize() {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeVarLong(1L);
		buffer.writeVarInt(-1);

		assertThrows(RuntimeException.class, () -> PairingNetwork.OpenCorePairingPayload.CODEC.decode(buffer));
	}

	/**
	 * size 大于实际数据时应抛出异常。
	 */
	@Test
	void payloadCodecShouldRejectTruncatedPayload() {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		buffer.writeVarLong(1L);
		buffer.writeVarInt(2);
		buffer.writeVarLong(11L);

		assertThrows(RuntimeException.class, () -> PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.decode(buffer));
	}

}
