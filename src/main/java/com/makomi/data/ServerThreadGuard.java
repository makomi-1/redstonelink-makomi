package com.makomi.data;

import net.minecraft.server.MinecraftServer;

/**
 * 服务端主线程访问边界 guard。
 * <p>
 * 当前仓库的多个运行态服务默认依赖“所有状态访问都在服务端主线程”这一约定。
 * 本 helper 用于把该约定显式化，避免后续异步入口误入后静默污染状态。
 * </p>
 */
final class ServerThreadGuard {
	@FunctionalInterface
	interface SameThreadProbe {
		boolean test(MinecraftServer server);
	}

	private static SameThreadProbe sameThreadProbe = server -> server.isSameThread();

	private ServerThreadGuard() {
	}

	/**
	 * 要求当前访问发生在指定服务端的主线程；`server == null` 时保持测试友好，直接放行。
	 */
	static void requireServerThread(MinecraftServer server, String operationName) {
		if (server == null) {
			return;
		}
		if (sameThreadProbe.test(server)) {
			return;
		}
		String normalizedOperationName = operationName == null || operationName.isBlank() ? "server state access" : operationName;
		throw new IllegalStateException("Operation must run on the server thread: " + normalizedOperationName);
	}

	/**
	 * 测试专用：覆写线程判定逻辑，便于模拟越界线程访问。
	 */
	static void setSameThreadProbeForTesting(SameThreadProbe probe) {
		sameThreadProbe = probe == null ? server -> server.isSameThread() : probe;
	}

	/**
	 * 测试专用：恢复默认线程判定逻辑。
	 */
	static void resetForTesting() {
		sameThreadProbe = server -> server.isSameThread();
	}
}
