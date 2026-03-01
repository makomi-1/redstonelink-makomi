package com.makomi.client;

import com.makomi.data.LinkNodeType;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.InteractionHand;

public final class ClientHooks {
	@FunctionalInterface
	public interface NodePairingScreenOpener {
		void open(LinkNodeType nodeType, long nodeSerial, long currentTargetSerial);
	}

	private static Consumer<InteractionHand> handPairingScreenOpener = hand -> {
		// 服务端默认是空操作，避免误调用客户端类。
	};

	private static NodePairingScreenOpener nodePairingScreenOpener = (nodeType, nodeSerial, currentTargetSerial) -> {
		// 服务端默认是空操作，避免误调用客户端类。
	};

	private ClientHooks() {
	}

	public static void setPairingScreenOpener(Consumer<InteractionHand> opener) {
		handPairingScreenOpener = Objects.requireNonNull(opener);
	}

	public static void setNodePairingScreenOpener(NodePairingScreenOpener opener) {
		nodePairingScreenOpener = Objects.requireNonNull(opener);
	}

	public static void openPairingScreen(InteractionHand hand) {
		handPairingScreenOpener.accept(hand);
	}

	public static void openPairingScreen(LinkNodeType nodeType, long nodeSerial, long currentTargetSerial) {
		nodePairingScreenOpener.open(nodeType, nodeSerial, currentTargetSerial);
	}
}
