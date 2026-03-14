package com.makomi.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.makomi.data.LinkNodeType;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PairingNetwork 分支调度契约测试。
 */
@Tag("stable-core")
class PairingNetworkDispatchTest {
	/**
	 * 来源类型为空时应直接返回。
	 */
	@Test
	void openPairingBySourceTypeShouldReturnWhenSourceTypeIsNull() {
		assertDoesNotThrow(() -> PairingNetwork.openPairingBySourceType(null, null, 101L));
	}

	/**
	 * triggerSource 入口在缺少玩家上下文时应抛出空指针，保持现有守卫行为。
	 */
	@Test
	void openTriggerSourcePairingShouldFailFastWhenPlayerIsMissing() {
		assertThrows(NullPointerException.class, () -> PairingNetwork.openTriggerSourcePairing(null, 101L));
	}

	/**
	 * core 入口在缺少玩家上下文时应抛出空指针，保持现有守卫行为。
	 */
	@Test
	void openCorePairingShouldFailFastWhenPlayerIsMissing() {
		assertThrows(NullPointerException.class, () -> PairingNetwork.openCorePairing(null, 202L));
	}

	/**
	 * 来源类型映射应输出 triggerSource payload。
	 */
	@Test
	void buildPayloadForSourceTypeShouldMapButtonToTriggerSourcePayload() throws Exception {
		CustomPacketPayload payload = invokeBuildPayload(LinkNodeType.BUTTON, 11L, List.of(3L, 7L));
		PairingNetwork.OpenTriggerSourcePairingPayload typedPayload = assertInstanceOf(
			PairingNetwork.OpenTriggerSourcePairingPayload.class,
			payload
		);
		assertEquals(11L, typedPayload.sourceSerial());
		assertEquals(List.of(3L, 7L), typedPayload.targets());
	}

	/**
	 * 来源类型映射应输出 core payload。
	 */
	@Test
	void buildPayloadForSourceTypeShouldMapCoreToCorePayload() throws Exception {
		CustomPacketPayload payload = invokeBuildPayload(LinkNodeType.CORE, 22L, List.of(5L));
		PairingNetwork.OpenCorePairingPayload typedPayload = assertInstanceOf(PairingNetwork.OpenCorePairingPayload.class, payload);
		assertEquals(22L, typedPayload.sourceSerial());
		assertEquals(List.of(5L), typedPayload.targets());
	}

	private static CustomPacketPayload invokeBuildPayload(LinkNodeType sourceType, long sourceSerial, List<Long> targets)
		throws Exception {
		Method method = PairingNetwork.class.getDeclaredMethod(
			"buildPayloadForSourceType",
			LinkNodeType.class,
			long.class,
			List.class
		);
		method.setAccessible(true);
		return (CustomPacketPayload) method.invoke(null, sourceType, sourceSerial, targets);
	}
}
