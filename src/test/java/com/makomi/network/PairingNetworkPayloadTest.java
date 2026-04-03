package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
			source
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
		PairingNetwork.OpenCorePairingPayload payload = new PairingNetwork.OpenCorePairingPayload(200L, source);

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
			" 1/3:5 "
		);
		PairingNetwork.SubmitTriggerSourcePairingPayload emptyPayload = new PairingNetwork.SubmitTriggerSourcePairingPayload(301L, null);

		assertEquals(300L, payload.sourceSerial());
		assertEquals("1/3:5", payload.targetsExpression());
		assertEquals("", emptyPayload.targetsExpression());
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
			List.of(1L, 4L, 9L)
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenTriggerSourcePairingPayload decoded = PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.decode(
			buffer
		);

		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targets(), decoded.targets());
		assertEquals(PairingNetwork.OpenTriggerSourcePairingPayload.TYPE, decoded.type());
	}

	/**
	 * 核心配对包编解码往返应保持字段一致。
	 */
	@Test
	void corePayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.OpenCorePairingPayload original = new PairingNetwork.OpenCorePairingPayload(321L, List.of(8L, 6L));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenCorePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenCorePairingPayload decoded = PairingNetwork.OpenCorePairingPayload.CODEC.decode(buffer);

		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targets(), decoded.targets());
		assertEquals(PairingNetwork.OpenCorePairingPayload.TYPE, decoded.type());
	}

	/**
	 * triggerSource 结构化提交包编解码往返应保持字段一致。
	 */
	@Test
	void submitTriggerSourcePairingPayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.SubmitTriggerSourcePairingPayload original = new PairingNetwork.SubmitTriggerSourcePairingPayload(
			456L,
			"1/7:9"
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.SubmitTriggerSourcePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.SubmitTriggerSourcePairingPayload decoded = PairingNetwork.SubmitTriggerSourcePairingPayload.CODEC.decode(
			buffer
		);

		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targetsExpression(), decoded.targetsExpression());
		assertEquals(PairingNetwork.SubmitTriggerSourcePairingPayload.TYPE, decoded.type());
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
	 * 编解码时应保留空目标列表。
	 */
	@Test
	void payloadCodecShouldAllowEmptyTargets() {
		PairingNetwork.OpenTriggerSourcePairingPayload original = new PairingNetwork.OpenTriggerSourcePairingPayload(
			77L,
			List.of()
		);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenTriggerSourcePairingPayload decoded = PairingNetwork.OpenTriggerSourcePairingPayload.CODEC.decode(
			buffer
		);

		assertEquals(77L, decoded.sourceSerial());
		assertEquals(List.of(), decoded.targets());
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
