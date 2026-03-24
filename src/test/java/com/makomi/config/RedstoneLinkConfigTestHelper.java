package com.makomi.config;

import java.lang.reflect.Field;
import java.util.Properties;

/**
 * 配置测试辅助。
 * <p>
 * 统一收口 parser 直调与测试期快照注入，避免各测试类自行反射门面私有字段。
 * </p>
 */
public final class RedstoneLinkConfigTestHelper {
	private RedstoneLinkConfigTestHelper() {
	}

	/**
	 * 解析服务端聚合配置快照。
	 */
	static RedstoneLinkServerConfigSnapshot parseServer(Properties properties) {
		return RedstoneLinkConfigParser.parse(properties);
	}

	/**
	 * 解析跨区块配置快照。
	 */
	public static RedstoneLinkCrossChunkConfig parseCrossChunk(Properties properties) {
		return RedstoneLinkCrossChunkConfigParser.parse(properties);
	}

	/**
	 * 以临时跨区块配置快照执行测试代码。
	 */
	public static void withCrossChunkConfig(Properties properties, ThrowingRunnable action) throws Exception {
		withCrossChunkConfig(parseCrossChunk(properties), action);
	}

	/**
	 * 以临时跨区块配置快照执行测试代码。
	 */
	public static void withCrossChunkConfig(RedstoneLinkCrossChunkConfig crossChunkConfig, ThrowingRunnable action) throws Exception {
		Field snapshotField = RedstoneLinkConfig.class.getDeclaredField("snapshot");
		snapshotField.setAccessible(true);
		RedstoneLinkServerConfigSnapshot previous = (RedstoneLinkServerConfigSnapshot) snapshotField.get(null);
		RedstoneLinkServerConfigSnapshot replaced = new RedstoneLinkServerConfigSnapshot(
			previous.general(),
			previous.command(),
			previous.rateLimit(),
			previous.privacy(),
			previous.writeControl(),
			previous.interaction(),
			previous.runtime(),
			crossChunkConfig
		);
		try {
			snapshotField.set(null, replaced);
			action.run();
		} finally {
			snapshotField.set(null, previous);
		}
	}

	@FunctionalInterface
	public interface ThrowingRunnable {
		void run() throws Exception;
	}
}
