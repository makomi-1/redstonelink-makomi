package com.makomi.client.web;

import com.makomi.network.StatePanelNetwork;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * 本地网页 graph 刷新 RPC 协调器。
 * <p>
 * 该协调器专门负责“网页保存成功后刷新最新 graph 基线”的同步等待链路：
 * </p>
 * <ul>
 * <li>在客户端主线程上发出 graph export 请求；</li>
 * <li>等待 graph export 分块写入本地资产仓完成；</li>
 * <li>以最新 graph 文件名作为结果返回给 bridge HTTP 层。</li>
 * </ul>
 */
public final class LocalWebGraphRefreshRpc {
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
	private static final Map<String, CompletableFuture<String>> PENDING_RESPONSES = new ConcurrentHashMap<>();

	private LocalWebGraphRefreshRpc() {
	}

	/**
	 * 请求服务端导出当前最新 graph，并同步等待本地资产落盘完成。
	 */
	public static String refreshLatestGraphAndAwait() throws IOException {
		String requestId = "graph-refresh-" + UUID.randomUUID().toString().replace("-", "");
		CompletableFuture<String> responseFuture = new CompletableFuture<>();
		PENDING_RESPONSES.put(requestId, responseFuture);

		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (minecraft.getConnection() == null || minecraft.player == null) {
				failResponse(requestId, "No active game connection is available for graph refresh.");
				return;
			}
			ClientPlayNetworking.send(new StatePanelNetwork.ExportStatePanelGraphPayload(requestId, true, false));
		});

		try {
			String fileName = responseFuture.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (fileName == null || fileName.isBlank()) {
				throw new IOException("Graph refresh completed without a file name.");
			}
			return fileName;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Graph refresh was interrupted.", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof IOException ioException) {
				throw ioException;
			}
			throw new IOException(cause == null ? "Graph refresh failed." : cause.getMessage(), cause);
		} catch (TimeoutException exception) {
			throw new IOException("Graph refresh timed out.", exception);
		} finally {
			PENDING_RESPONSES.remove(requestId);
		}
	}

	/**
	 * 完成一个等待中的 graph 刷新请求。
	 */
	public static void completeResponse(String requestId, String fileName) {
		if (requestId == null || requestId.isBlank()) {
			return;
		}
		CompletableFuture<String> responseFuture = PENDING_RESPONSES.remove(requestId);
		if (responseFuture == null) {
			return;
		}
		responseFuture.complete(fileName == null ? "" : fileName.trim());
	}

	/**
	 * 以明确错误结束一个等待中的 graph 刷新请求。
	 */
	public static void failResponse(String requestId, String message) {
		if (requestId == null || requestId.isBlank()) {
			return;
		}
		CompletableFuture<String> responseFuture = PENDING_RESPONSES.remove(requestId);
		if (responseFuture == null) {
			return;
		}
		responseFuture.completeExceptionally(new IOException(message == null || message.isBlank() ? "Graph refresh failed." : message));
	}
}
