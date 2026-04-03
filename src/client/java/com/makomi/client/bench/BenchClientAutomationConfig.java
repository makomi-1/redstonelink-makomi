package com.makomi.client.bench;

import com.makomi.RedstoneLink;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * bench 外部客户端自动入服配置。
 * <p>
 * 该配置由 bench PowerShell 脚本写入客户端实例的 {@code config} 目录，
 * 客户端启动后只读取一次，用于决定是否自动连接与重连 dedicated server。
 * </p>
 */
public record BenchClientAutomationConfig(
	boolean enabled,
	String playerName,
	String serverHost,
	int serverPort,
	int initialConnectDelayMs,
	int reconnectIntervalMs,
	boolean openTickChart,
	int postJoinActionDelayMs,
	boolean commandBridgeEnabled,
	int commandBridgePollIntervalMs
) {
	public static final String CONFIG_FILE_NAME = "redstonelink-bench-client.properties";
	private static final int DEFAULT_SERVER_PORT = 25565;
	private static final int DEFAULT_INITIAL_CONNECT_DELAY_MS = 0;
	private static final int DEFAULT_RECONNECT_INTERVAL_MS = 1000;
	private static final int DEFAULT_POST_JOIN_ACTION_DELAY_MS = 1000;
	private static final int DEFAULT_COMMAND_BRIDGE_POLL_INTERVAL_MS = 50;

	/**
	 * 创建关闭状态的默认配置。
	 */
	public static BenchClientAutomationConfig disabled() {
		return new BenchClientAutomationConfig(
			false,
			"",
			"",
			DEFAULT_SERVER_PORT,
			DEFAULT_INITIAL_CONNECT_DELAY_MS,
			DEFAULT_RECONNECT_INTERVAL_MS,
			false,
			DEFAULT_POST_JOIN_ACTION_DELAY_MS,
			false,
			DEFAULT_COMMAND_BRIDGE_POLL_INTERVAL_MS
		);
	}

	/**
	 * 从客户端实例配置目录读取 bench 自动入服配置。
	 */
	public static BenchClientAutomationConfig load() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
		if (!Files.exists(configPath)) {
			return disabled();
		}

		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException exception) {
			RedstoneLink.LOGGER.warn("Failed to read bench client automation config: {}", configPath, exception);
			return disabled();
		}

		boolean enabled = Boolean.parseBoolean(readTrimmed(properties, "enabled", "false"));
		String playerName = readTrimmed(properties, "player.name", "");
		String serverHost = readTrimmed(properties, "server.host", "");
		int serverPort = parseInt(properties, "server.port", DEFAULT_SERVER_PORT);
		int initialConnectDelayMs = Math.max(0, parseInt(properties, "initial.connect.delay.ms", DEFAULT_INITIAL_CONNECT_DELAY_MS));
		int reconnectIntervalMs = Math.max(250, parseInt(properties, "reconnect.interval.ms", DEFAULT_RECONNECT_INTERVAL_MS));
		boolean openTickChart = Boolean.parseBoolean(readTrimmed(properties, "open.tick.chart", "false"));
		int postJoinActionDelayMs = Math.max(0, parseInt(properties, "post.join.action.delay.ms", DEFAULT_POST_JOIN_ACTION_DELAY_MS));
		boolean commandBridgeEnabled = Boolean.parseBoolean(readTrimmed(properties, "command.bridge.enabled", "true"));
		int commandBridgePollIntervalMs = Math.max(
			10,
			parseInt(properties, "command.bridge.poll.interval.ms", DEFAULT_COMMAND_BRIDGE_POLL_INTERVAL_MS)
		);

		if (!enabled) {
			return disabled();
		}
		if (serverHost.isBlank()) {
			RedstoneLink.LOGGER.warn("Bench client automation config is enabled but server.host is blank: {}", configPath);
			return disabled();
		}

		return new BenchClientAutomationConfig(
			true,
			playerName,
			serverHost,
			serverPort,
			initialConnectDelayMs,
			reconnectIntervalMs,
			openTickChart,
			postJoinActionDelayMs,
			commandBridgeEnabled,
			commandBridgePollIntervalMs
		);
	}

	/**
	 * 返回标准 {@code host:port} 地址字符串。
	 */
	public String serverAddress() {
		return this.serverHost + ":" + this.serverPort;
	}

	private static String readTrimmed(Properties properties, String key, String defaultValue) {
		String value = properties.getProperty(key);
		if (value == null) {
			return defaultValue;
		}
		return value.trim();
	}

	private static int parseInt(Properties properties, String key, int defaultValue) {
		String rawValue = readTrimmed(properties, key, "");
		if (rawValue.isBlank()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(rawValue);
		} catch (NumberFormatException exception) {
			RedstoneLink.LOGGER.warn("Invalid integer in bench client automation config. key={} value={}", key, rawValue);
			return defaultValue;
		}
	}
}
