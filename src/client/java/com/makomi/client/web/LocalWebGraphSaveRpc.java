package com.makomi.client.web;

import com.makomi.network.StatePanelNetwork;
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
 * 本地网页 graph 保存 RPC 协调器。
 * <p>
 * 负责将本地 HTTP bridge 的同步请求转换为：
 * </p>
 * <ul>
 * <li>客户端主线程上的 C2S 网络发包；</li>
 * <li>S2C 结果回包与等待中的 HTTP 请求配对；</li>
 * <li>超时与断连场景下的最小错误收口。</li>
 * </ul>
 */
public final class LocalWebGraphSaveRpc {
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
	private static final Map<String, CompletableFuture<String>> PENDING_RESPONSES = new ConcurrentHashMap<>();

	private LocalWebGraphSaveRpc() {
	}

	/**
	 * 提交 graph 保存请求，并同步等待服务端结果 JSON。
	 */
	public static String submitAndAwait(String requestJson) {
		return sendAndAwait("graph-save-", requestJson, false);
	}

	/**
	 * 提交 graph 保存预检请求，并同步等待服务端结果 JSON。
	 */
	public static String previewAndAwait(String requestJson) {
		return sendAndAwait("graph-preview-", requestJson, true);
	}

	/**
	 * 统一处理 graph 保存 / 预检的本地 RPC 往返。
	 */
	private static String sendAndAwait(String requestIdPrefix, String requestJson, boolean preview) {
		String requestId = requestIdPrefix + UUID.randomUUID().toString().replace("-", "");
		CompletableFuture<String> responseFuture = new CompletableFuture<>();
		PENDING_RESPONSES.put(requestId, responseFuture);
		String requestLabel = preview ? "graph preview" : "graph save";

		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (minecraft.getConnection() == null || minecraft.player == null) {
				completeResponse(
					requestId,
					LocalWebJsonSupport.buildErrorPayload("No active game connection is available for " + requestLabel + ".")
				);
				return;
			}
			if (preview) {
				ClientPlayNetworking.send(new StatePanelNetwork.PreviewGraphWritePayload(requestId, requestJson));
				return;
			}
			ClientPlayNetworking.send(new StatePanelNetwork.SubmitGraphWritePayload(requestId, requestJson));
		});

		try {
			return responseFuture.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return LocalWebJsonSupport.buildErrorPayload("Graph " + (preview ? "preview" : "save") + " request was interrupted.");
		} catch (ExecutionException exception) {
			return LocalWebJsonSupport.buildErrorPayload("Graph " + (preview ? "preview" : "save") + " request failed.");
		} catch (TimeoutException exception) {
			return LocalWebJsonSupport.buildErrorPayload("Graph " + (preview ? "preview" : "save") + " request timed out.");
		} finally {
			PENDING_RESPONSES.remove(requestId);
		}
	}

	/**
	 * 完成一个等待中的 graph 保存请求。
	 */
	public static void completeResponse(String requestId, String responseJson) {
		if (requestId == null || requestId.isBlank()) {
			return;
		}
		CompletableFuture<String> responseFuture = PENDING_RESPONSES.remove(requestId);
		if (responseFuture == null) {
			return;
		}
		responseFuture.complete(responseJson == null ? LocalWebJsonSupport.buildErrorPayload("Empty graph save response.") : responseJson);
	}
}
