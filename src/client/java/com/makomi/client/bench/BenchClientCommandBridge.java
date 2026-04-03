package com.makomi.client.bench;

import com.makomi.RedstoneLink;
import com.makomi.network.BenchCommandNetwork;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * bench 客户端本地 request/response 文件桥。
 * <p>
 * PowerShell 侧通过实例 `config` 目录写入 request 文件；客户端轮询后把命令转成 C2S 请求，
 * 再把服务端返回的结构化结果写回 response 文件。
 * </p>
 */
public final class BenchClientCommandBridge {
	public static final String REQUEST_FILE_NAME = "redstonelink-bench-command-request.properties";
	public static final String RESPONSE_FILE_NAME = "redstonelink-bench-command-response.properties";
	private static final String DISPATCH_MODE_SERVER_NETWORK = "server_network";
	private static final String DISPATCH_MODE_CLIENT_DIRECT_COMMAND = "client_direct_command";
	private static final long DIRECT_COMMAND_EMPTY_RESPONSE_SETTLE_MS = 250L;
	private static final long DIRECT_COMMAND_OUTPUT_SETTLE_MS = 150L;
	private static final long DIRECT_COMMAND_MAX_WAIT_MS = 2000L;
	private static BenchClientAutomationConfig config = BenchClientAutomationConfig.disabled();
	private static Path requestFilePath;
	private static Path responseFilePath;
	private static long nextPollAtMs = Long.MIN_VALUE;
	private static long inFlightRequestId = Long.MIN_VALUE;
	private static InFlightDirectCommand inFlightDirectCommand;
	private static boolean messageHooksRegistered;

	private BenchClientCommandBridge() {
	}

	/**
	 * 注册客户端消息钩子，用于直发命令模式回收系统消息。
	 */
	public static void registerMessageHooks() {
		if (messageHooksRegistered) {
			return;
		}
		messageHooksRegistered = true;
		ClientReceiveMessageEvents.GAME.register(BenchClientCommandBridge::handleGameMessage);
	}

	/**
	 * 初始化本地文件桥路径与轮询状态。
	 */
	public static void initialize(BenchClientAutomationConfig automationConfig) {
		config = automationConfig == null ? BenchClientAutomationConfig.disabled() : automationConfig;
		Path configDirectory = FabricLoader.getInstance().getConfigDir();
		requestFilePath = configDirectory.resolve(REQUEST_FILE_NAME);
		responseFilePath = configDirectory.resolve(RESPONSE_FILE_NAME);
		nextPollAtMs = Long.MIN_VALUE;
		inFlightRequestId = Long.MIN_VALUE;
		inFlightDirectCommand = null;
	}

	/**
	 * 客户端 tick 驱动 request 文件轮询。
	 */
	public static void onClientTick(Minecraft client) {
		long now = System.currentTimeMillis();
		driveInFlightDirectCommand(client, now);
		if (!config.enabled() || !config.commandBridgeEnabled()) {
			return;
		}
		if (client == null || client.player == null || client.level == null || client.getConnection() == null) {
			return;
		}
		if (inFlightRequestId != Long.MIN_VALUE) {
			return;
		}
		if (nextPollAtMs != Long.MIN_VALUE && now < nextPollAtMs) {
			return;
		}
		nextPollAtMs = now + config.commandBridgePollIntervalMs();
		processPendingRequest(client);
	}

	/**
	 * 处理服务端回包并写回 response 文件。
	 */
	public static void handleCommandResponse(BenchCommandNetwork.PlayerCommandResultPayload payload) {
		if (payload == null || !config.enabled() || !config.commandBridgeEnabled()) {
			return;
		}
		try {
			writeResponseFile(payload);
			deleteRequestFileIfMatches(payload.requestId());
			if (payload.requestId() == inFlightRequestId) {
				inFlightRequestId = Long.MIN_VALUE;
			}
			if (inFlightDirectCommand != null && payload.requestId() == inFlightDirectCommand.requestId()) {
				inFlightDirectCommand = null;
			}
		} catch (IOException exception) {
			RedstoneLink.LOGGER.warn("Failed to write bench command bridge response file: {}", responseFilePath, exception);
		}
	}

	/**
	 * 读取 request 文件并按指定分发模式发起真实玩家命令请求。
	 */
	private static void processPendingRequest(Minecraft client) {
		if (requestFilePath == null || !Files.exists(requestFilePath)) {
			return;
		}
		try {
			PendingRequest pendingRequest = readRequestFile();
			if (pendingRequest == null) {
				return;
			}
			if (pendingRequest.command().isBlank()) {
				writeResponseFile(
					new BenchCommandNetwork.PlayerCommandResultPayload(
						pendingRequest.requestId(),
						false,
						0,
						"",
						false,
						0L,
						0L,
						"Bench command bridge request is blank."
					)
				);
				deleteRequestFileIfMatches(pendingRequest.requestId());
				return;
			}
			inFlightRequestId = pendingRequest.requestId();
			if (DISPATCH_MODE_CLIENT_DIRECT_COMMAND.equals(pendingRequest.dispatchMode())) {
				dispatchDirectCommand(client, pendingRequest);
				return;
			}
			ClientPlayNetworking.send(
				new BenchCommandNetwork.ExecutePlayerCommandPayload(
					pendingRequest.requestId(),
					pendingRequest.command(),
					pendingRequest.captureTickWindow()
				)
			);
		} catch (IOException exception) {
			RedstoneLink.LOGGER.warn("Failed to read bench command bridge request file: {}", requestFilePath, exception);
		}
	}

	/**
	 * 读取 request 文件并解析为结构化请求。
	 */
	private static PendingRequest readRequestFile() throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(requestFilePath, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		long requestId = parseLong(properties.getProperty("request.id"), Long.MIN_VALUE);
		String command = normalizeCommand(decodeBase64(properties.getProperty("command.base64", "")));
		boolean captureTickWindow = Boolean.parseBoolean(properties.getProperty("capture.tick.window", "false"));
		String dispatchMode = normalizeDispatchMode(properties.getProperty("dispatch.mode", DISPATCH_MODE_SERVER_NETWORK));
		if (requestId == Long.MIN_VALUE) {
			return null;
		}
		return new PendingRequest(requestId, command, captureTickWindow, dispatchMode);
	}

	/**
	 * 通过客户端原生命令发送链直发命令，并等待客户端系统消息回收。
	 */
	private static void dispatchDirectCommand(Minecraft client, PendingRequest pendingRequest) {
		if (client == null || client.player == null || client.player.connection == null || client.getConnection() == null) {
			failDirectCommand(pendingRequest.requestId(), "Client connection is not ready for direct bench command dispatch.");
			return;
		}
		try {
			client.player.connection.sendCommand(pendingRequest.command());
			inFlightDirectCommand = new InFlightDirectCommand(pendingRequest.requestId(), System.currentTimeMillis());
		} catch (RuntimeException exception) {
			String errorDetail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			failDirectCommand(pendingRequest.requestId(), errorDetail);
		}
	}

	/**
	 * 客户端直发模式下，按短静默窗口判断命令反馈已稳定，可安全落盘。
	 */
	private static void driveInFlightDirectCommand(Minecraft client, long now) {
		InFlightDirectCommand state = inFlightDirectCommand;
		if (state == null) {
			return;
		}
		if (client == null || client.player == null || client.level == null || client.getConnection() == null) {
			if ((now - state.dispatchedAtMs()) >= DIRECT_COMMAND_OUTPUT_SETTLE_MS) {
				finishDirectCommand(state, "", "Client connection closed before direct bench command response was collected.");
			}
			return;
		}

		long settleAnchorMs = state.hasOutput() ? state.lastObservedMessageAtMs() : state.dispatchedAtMs();
		long settleWindowMs = state.hasOutput() ? DIRECT_COMMAND_OUTPUT_SETTLE_MS : DIRECT_COMMAND_EMPTY_RESPONSE_SETTLE_MS;
		boolean quietWindowReached = settleAnchorMs != Long.MIN_VALUE && (now - settleAnchorMs) >= settleWindowMs;
		boolean maxWaitReached = (now - state.dispatchedAtMs()) >= DIRECT_COMMAND_MAX_WAIT_MS;
		if (!quietWindowReached && !maxWaitReached) {
			return;
		}

		finishDirectCommand(state, state.joinedOutput(), "");
	}

	/**
	 * 记录直发命令回收到的系统消息，仅在命令回收窗口打开时生效。
	 */
	private static void handleGameMessage(Component message, boolean overlay) {
		if (overlay || message == null || !config.enabled() || !config.commandBridgeEnabled()) {
			return;
		}
		InFlightDirectCommand state = inFlightDirectCommand;
		if (state == null) {
			return;
		}
		String line = message.getString();
		if (line == null || line.isBlank()) {
			return;
		}
		state.appendOutput(line);
	}

	/**
	 * 直发命令失败时统一落盘错误结果，避免 PowerShell 长时间挂起。
	 */
	private static void failDirectCommand(long requestId, String errorDetail) {
		finishDirectCommand(new InFlightDirectCommand(requestId, System.currentTimeMillis()), "", errorDetail);
	}

	/**
	 * 完成一次直发命令请求并回写 response 文件。
	 */
	private static void finishDirectCommand(InFlightDirectCommand state, String output, String errorDetail) {
		try {
			writeResponseFile(
				new BenchCommandNetwork.PlayerCommandResultPayload(
					state.requestId(),
					errorDetail == null || errorDetail.isBlank(),
					0,
					output,
					false,
					0L,
					0L,
					errorDetail == null ? "" : errorDetail
				)
			);
			deleteRequestFileIfMatches(state.requestId());
		} catch (IOException exception) {
			RedstoneLink.LOGGER.warn("Failed to finalize bench direct command response: {}", responseFilePath, exception);
		} finally {
			if (inFlightDirectCommand != null && inFlightDirectCommand.requestId() == state.requestId()) {
				inFlightDirectCommand = null;
			}
			if (inFlightRequestId == state.requestId()) {
				inFlightRequestId = Long.MIN_VALUE;
			}
		}
	}

	/**
	 * 将结构化结果写入 response 文件。
	 */
	private static void writeResponseFile(BenchCommandNetwork.PlayerCommandResultPayload payload) throws IOException {
		if (responseFilePath == null) {
			return;
		}
		String fileContent = String.join(
			System.lineSeparator(),
			"request.id=" + payload.requestId(),
			"callback.success=" + payload.callbackSuccess(),
			"result.code=" + payload.resultCode(),
			"has.tick.window=" + payload.hasTickWindow(),
			"tick.window.start=" + payload.tickWindowStart(),
			"tick.window.end=" + payload.tickWindowEnd(),
			"output.base64=" + encodeBase64(payload.output()),
			"error.detail.base64=" + encodeBase64(payload.errorDetail()),
			""
		);
		writeFileAtomically(responseFilePath, fileContent);
	}

	/**
	 * 若 request 文件仍对应当前响应，则在落盘后删除，避免重复发送。
	 */
	private static void deleteRequestFileIfMatches(long requestId) throws IOException {
		if (requestFilePath == null || !Files.exists(requestFilePath)) {
			return;
		}
		PendingRequest currentRequest = readRequestFile();
		if (currentRequest != null && currentRequest.requestId() == requestId) {
			Files.deleteIfExists(requestFilePath);
		}
	}

	/**
	 * 原子写入小文件，尽量避免 PowerShell 侧读到半写状态。
	 */
	private static void writeFileAtomically(Path targetPath, String content) throws IOException {
		Path parentPath = targetPath.getParent();
		if (parentPath != null) {
			Files.createDirectories(parentPath);
		}
		Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
		Files.writeString(tempPath, content, StandardCharsets.UTF_8);
		try {
			Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * 解析 long 值；失败时返回默认值，避免 request 文件半写状态直接炸掉主循环。
	 */
	private static long parseLong(String rawValue, long defaultValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(rawValue.trim());
		} catch (NumberFormatException exception) {
			return defaultValue;
		}
	}

	/**
	 * 标准化 request 中的分发模式；未知模式回退到兼容网络桥。
	 */
	private static String normalizeDispatchMode(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return DISPATCH_MODE_SERVER_NETWORK;
		}
		String normalizedValue = rawValue.trim().toLowerCase(java.util.Locale.ROOT);
		if (DISPATCH_MODE_CLIENT_DIRECT_COMMAND.equals(normalizedValue)) {
			return normalizedValue;
		}
		if (!DISPATCH_MODE_SERVER_NETWORK.equals(normalizedValue)) {
			RedstoneLink.LOGGER.warn("Unknown bench command bridge dispatch mode: {}. Fallback to {}.", rawValue, DISPATCH_MODE_SERVER_NETWORK);
		}
		return DISPATCH_MODE_SERVER_NETWORK;
	}

	/**
	 * 标准化命令文本，兼容 bench request 写入前可能残留的斜杠与空白。
	 */
	private static String normalizeCommand(String rawCommand) {
		if (rawCommand == null) {
			return "";
		}
		String normalizedCommand = rawCommand.trim();
		if (normalizedCommand.startsWith("/")) {
			normalizedCommand = normalizedCommand.substring(1).trim();
		}
		return normalizedCommand;
	}

	/**
	 * UTF-8 文本转 Base64，避免 properties 文件对换行和特殊字符做二次转义。
	 */
	private static String encodeBase64(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Base64 还原 UTF-8 文本；解析失败时返回空串，避免中断轮询。
	 */
	private static String decodeBase64(String encodedText) {
		if (encodedText == null || encodedText.isBlank()) {
			return "";
		}
		try {
			return new String(Base64.getDecoder().decode(encodedText.trim()), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException exception) {
			return "";
		}
	}

	/**
	 * request 文件的只读结构。
	 */
	private record PendingRequest(long requestId, String command, boolean captureTickWindow, String dispatchMode) {}

	/**
	 * 客户端直发命令模式的短生命周期状态。
	 */
	private static final class InFlightDirectCommand {
		private final long requestId;
		private final long dispatchedAtMs;
		private final List<String> outputLines = new ArrayList<>();
		private long lastObservedMessageAtMs = Long.MIN_VALUE;

		private InFlightDirectCommand(long requestId, long dispatchedAtMs) {
			this.requestId = requestId;
			this.dispatchedAtMs = dispatchedAtMs;
		}

		private long requestId() {
			return requestId;
		}

		private long dispatchedAtMs() {
			return dispatchedAtMs;
		}

		private long lastObservedMessageAtMs() {
			return lastObservedMessageAtMs;
		}

		private boolean hasOutput() {
			return !outputLines.isEmpty();
		}

		private void appendOutput(String line) {
			outputLines.add(line);
			lastObservedMessageAtMs = System.currentTimeMillis();
		}

		private String joinedOutput() {
			return String.join("\n", outputLines);
		}
	}
}
