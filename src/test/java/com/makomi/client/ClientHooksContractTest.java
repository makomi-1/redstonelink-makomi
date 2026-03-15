package com.makomi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.makomi.data.LinkNodeType;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GUI 打开钩子契约测试。
 */
@Tag("client")
class ClientHooksContractTest {
	/**
	 * 每个用例后重置静态 opener，避免测试间相互污染。
	 */
	@AfterEach
	void resetOpeners() {
		ClientHooks.setPairingScreenOpener(hand -> {
			// no-op
		});
		ClientHooks.setNodePairingScreenOpener((nodeType, nodeSerial, currentTargetSerial) -> {
			// no-op
		});
	}

	/**
	 * 手持物入口应把参数原样转发给注入 opener。
	 */
	@Test
	void openPairingScreenShouldDelegateToHandOpener() {
		AtomicReference<InteractionHand> capturedHand = new AtomicReference<>();
		ClientHooks.setPairingScreenOpener(capturedHand::set);

		ClientHooks.openPairingScreen(InteractionHand.OFF_HAND);

		assertEquals(InteractionHand.OFF_HAND, capturedHand.get());
	}

	/**
	 * 节点入口应把 nodeType/nodeSerial/currentTargetSerial 原样转发。
	 */
	@Test
	void openPairingScreenShouldDelegateToNodeOpener() {
		AtomicReference<NodeOpenInvocation> capturedInvocation = new AtomicReference<>();
		ClientHooks.setNodePairingScreenOpener((nodeType, nodeSerial, currentTargetSerial) -> capturedInvocation.set(
			new NodeOpenInvocation(nodeType, nodeSerial, currentTargetSerial)
		));

		ClientHooks.openPairingScreen(LinkNodeType.CORE, 201L, 808L);

		NodeOpenInvocation invocation = capturedInvocation.get();
		assertNotNull(invocation);
		assertEquals(LinkNodeType.CORE, invocation.nodeType());
		assertEquals(201L, invocation.nodeSerial());
		assertEquals(808L, invocation.currentTargetSerial());
	}

	/**
	 * 注入空 opener 应立即失败，保持入参契约清晰。
	 */
	@Test
	void setOpenerShouldRejectNullReference() {
		assertThrows(NullPointerException.class, () -> ClientHooks.setPairingScreenOpener(null));
		assertThrows(NullPointerException.class, () -> ClientHooks.setNodePairingScreenOpener(null));
	}

	/**
	 * 节点打开调用快照。
	 */
	private record NodeOpenInvocation(LinkNodeType nodeType, long nodeSerial, long currentTargetSerial) {}
}
