package com.makomi.client;

import com.makomi.data.LinkNodeType;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.InteractionHand;

/**
 * 客户端 UI 打开钩子桥接。
 * <p>
 * 服务端代码可通过该类调用“打开配对界面”动作，实际实现由客户端入口在运行时注入。
 * </p>
 */
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

	/**
	 * 注入“手持物配对界面”打开器。
	 */
	public static void setPairingScreenOpener(Consumer<InteractionHand> opener) {
		handPairingScreenOpener = Objects.requireNonNull(opener);
	}

	/**
	 * 注入“节点配对界面”打开器。
	 */
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
