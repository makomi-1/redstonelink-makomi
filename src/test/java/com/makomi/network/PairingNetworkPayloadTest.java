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
	 * 按钮配对包 targets 应做不可变拷贝，避免外部修改污染消息体。
	 */
	@Test
	void buttonPayloadShouldCopyAndFreezeTargets() {
		List<Long> source = new ArrayList<>(List.of(7L, 3L, 9L));
		PairingNetwork.OpenButtonPairingPayload payload = new PairingNetwork.OpenButtonPairingPayload(100L, source);

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
	 * 按钮配对包编解码往返应保持字段一致。
	 */
	@Test
	void buttonPayloadCodecRoundTripShouldPreserveFields() {
		PairingNetwork.OpenButtonPairingPayload original = new PairingNetwork.OpenButtonPairingPayload(123L, List.of(1L, 4L, 9L));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenButtonPairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenButtonPairingPayload decoded = PairingNetwork.OpenButtonPairingPayload.CODEC.decode(buffer);

		assertEquals(original.sourceSerial(), decoded.sourceSerial());
		assertEquals(original.targets(), decoded.targets());
		assertEquals(PairingNetwork.OpenButtonPairingPayload.TYPE, decoded.type());
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
	 * 编解码时应保留空目标列表。
	 */
	@Test
	void payloadCodecShouldAllowEmptyTargets() {
		PairingNetwork.OpenButtonPairingPayload original = new PairingNetwork.OpenButtonPairingPayload(77L, List.of());
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

		PairingNetwork.OpenButtonPairingPayload.CODEC.encode(buffer, original);
		PairingNetwork.OpenButtonPairingPayload decoded = PairingNetwork.OpenButtonPairingPayload.CODEC.decode(buffer);

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

		assertThrows(RuntimeException.class, () -> PairingNetwork.OpenButtonPairingPayload.CODEC.decode(buffer));
	}
}
