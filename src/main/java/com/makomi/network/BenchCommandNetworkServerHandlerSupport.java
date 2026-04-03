package com.makomi.network;

import com.makomi.RedstoneLink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * `BenchCommandNetwork` 的服务端命令执行壳。
 * <p>
 * bench 真实客户端通过该 helper 在服务端以 `player.createCommandSourceStack()` 语义执行命令，
 * 并把捕获到的文本反馈结构化回传给客户端文件桥。
 * </p>
 */
final class BenchCommandNetworkServerHandlerSupport {
	private BenchCommandNetworkServerHandlerSupport() {
	}

	/**
	 * 执行 bench 上传的玩家命令，并回传结构化结果。
	 */
	static void handleExecutePlayerCommand(ServerPlayer player, BenchCommandNetwork.ExecutePlayerCommandPayload payload) {
		if (player == null || payload == null) {
			return;
		}
		String command = payload.command();
		if (command.isBlank()) {
			sendResult(player, new ExecutionResult(payload.requestId(), false, 0, "", false, 0L, 0L, "Bench command is blank."));
			return;
		}

		CapturingCommandSource capturingSource = new CapturingCommandSource();
		AtomicBoolean callbackSuccess = new AtomicBoolean(false);
		AtomicInteger resultCode = new AtomicInteger(0);
		CommandSourceStack commandSource = player
			.createCommandSourceStack()
			.withSource(capturingSource)
			.withCallback((success, result) -> {
				callbackSuccess.set(success);
				resultCode.set(result);
			}, (first, second) -> CommandResultCallback.chain(first, second));

		long startTick = payload.captureTickWindow() ? player.serverLevel().getGameTime() : 0L;
		long endTick = startTick;
		String errorDetail = "";
		try {
			player.getServer().getCommands().performPrefixedCommand(commandSource, command);
		} catch (RuntimeException exception) {
			errorDetail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			RedstoneLink.LOGGER.warn(
				"Bench player command execution failed. player={} requestId={} command={}",
				player.getGameProfile().getName(),
				payload.requestId(),
				command,
				exception
			);
		} finally {
			if (payload.captureTickWindow()) {
				endTick = player.serverLevel().getGameTime();
			}
		}

		sendResult(
			player,
			new ExecutionResult(
				payload.requestId(),
				callbackSuccess.get(),
				resultCode.get(),
				capturingSource.joinedOutput(),
				payload.captureTickWindow(),
				startTick,
				endTick,
				errorDetail
			)
		);
	}

	/**
	 * 将结构化结果回传给客户端。
	 */
	private static void sendResult(ServerPlayer player, ExecutionResult result) {
		ServerPlayNetworking.send(
			player,
			new BenchCommandNetwork.PlayerCommandResultPayload(
				result.requestId(),
				result.callbackSuccess(),
				result.resultCode(),
				result.output(),
				result.hasTickWindow(),
				result.tickWindowStart(),
				result.tickWindowEnd(),
				result.errorDetail()
			)
		);
	}

	/**
	 * 用于截获命令输出文本的最小命令源。
	 * <p>
	 * 这里显式关闭管理员广播，避免 bench 玩家命令在 dedicated server 中把断言文本再回灌到聊天或控制台。
	 * </p>
	 */
	private static final class CapturingCommandSource implements CommandSource {
		private final List<String> outputLines = new ArrayList<>();

		@Override
		public void sendSystemMessage(Component component) {
			if (component == null) {
				return;
			}
			String line = component.getString();
			if (!line.isBlank()) {
				outputLines.add(line);
			}
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}

		private String joinedOutput() {
			return String.join("\n", outputLines);
		}
	}

	/**
	 * 服务端执行结果的只读结构。
	 */
	private record ExecutionResult(
		long requestId,
		boolean callbackSuccess,
		int resultCode,
		String output,
		boolean hasTickWindow,
		long tickWindowStart,
		long tickWindowEnd,
		String errorDetail
	) {}
}
