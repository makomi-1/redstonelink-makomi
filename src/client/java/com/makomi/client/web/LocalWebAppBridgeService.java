package com.makomi.client.web;

import com.makomi.RedstoneLink;
import com.makomi.client.web.LocalWebAssetRepository.StorageEntryContent;
import com.makomi.client.web.LocalWebAssetRepository.StorageIndex;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import net.minecraft.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地网页桥接服务。
 * <p>
 * 当前已推进到 `P1`，职责包括：
 * </p>
 * <ul>
 * <li>从 Jar/classpath 提供离线网页静态资源</li>
 * <li>提供桥接状态接口 `/api/ping`</li>
 * <li>提供本地资产索引与单条资产读取接口</li>
 * </ul>
 */
public final class LocalWebAppBridgeService {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedstoneLink.MOD_ID + "/client-web");
	private static final String WEBAPP_RESOURCE_ROOT = "assets/" + RedstoneLink.MOD_ID + "/webapp";
	private static final String HOME_PAGE_RESOURCE = "index.html";
	private static final String BRIDGE_VERSION = "p1";
	private static final LocalWebAssetRepository ASSET_REPOSITORY = LocalWebAssetRepository.createDefault();
	private static volatile BridgeRuntime runtime;

	private LocalWebAppBridgeService() {
	}

	/**
	 * 确保本地网页桥已启动。
	 *
	 * @return 本地网页根地址
	 */
	public static URI ensureStarted() {
		BridgeRuntime current = runtime;
		if (current != null) {
			return current.baseUri();
		}
		synchronized (LocalWebAppBridgeService.class) {
			if (runtime != null) {
				return runtime.baseUri();
			}
			runtime = startRuntime();
			return runtime.baseUri();
		}
	}

	/**
	 * 打开本地网页首页。
	 *
	 * @return 已打开的首页地址
	 */
	public static URI openHomePage() {
		BridgeRuntime current = resolveRuntime();
		URI homePageUri = current.baseUri().resolve("/");
		Util.getPlatform().openUri(homePageUri);
		return homePageUri;
	}

	/**
	 * 打开 recording 独立曲线查看页。
	 *
	 * @return 已打开的 recording 页面地址
	 */
	public static URI openRecordingPage() {
		BridgeRuntime current = resolveRuntime();
		URI recordingPageUri = current.baseUri().resolve("./?page=recording");
		Util.getPlatform().openUri(recordingPageUri);
		return recordingPageUri;
	}

	/**
	 * 打开指定本地资产条目的网页查看页。
	 */
	public static URI openAssetEntry(LocalWebAssetKind assetKind, String fileName) {
		BridgeRuntime current = resolveRuntime();
		LocalWebAssetKind resolvedAssetKind = assetKind == null ? LocalWebAssetKind.RECORDING : assetKind;
		String normalizedFileName = fileName == null ? "" : fileName.trim();
		String entryUri = resolvedAssetKind == LocalWebAssetKind.RECORDING
			? "./?page=recording&kind=%s&name=%s".formatted(
				urlEncode(resolvedAssetKind.token()),
				urlEncode(normalizedFileName)
			)
			: "./?page=home&kind=%s&name=%s".formatted(
				urlEncode(resolvedAssetKind.token()),
				urlEncode(normalizedFileName)
			);
		URI targetUri = current.baseUri().resolve(entryUri);
		Util.getPlatform().openUri(targetUri);
		return targetUri;
	}

	/**
	 * 停止本地网页桥，避免客户端退出时残留本地监听器。
	 */
	public static void stop() {
		BridgeRuntime current = runtime;
		if (current == null) {
			return;
		}
		synchronized (LocalWebAppBridgeService.class) {
			current = runtime;
			if (current == null) {
				return;
			}
			runtime = null;
			current.server().stop(0);
			current.executor().shutdownNow();
			LOGGER.info("RedstoneLink 本地网页桥已停止: {}", current.baseUri());
		}
	}

	private static BridgeRuntime resolveRuntime() {
		URI ignored = ensureStarted();
		BridgeRuntime current = runtime;
		if (current == null) {
			throw new IllegalStateException("local web bridge is not running");
		}
		return current;
	}

	/**
	 * 启动本地 HTTP 服务，仅监听回环地址，避免暴露到局域网。
	 */
	private static BridgeRuntime startRuntime() {
		try {
			ASSET_REPOSITORY.ensureInitialized();
			InetSocketAddress bindAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
			HttpServer httpServer = HttpServer.create(bindAddress, 0);
			ExecutorService executor = Executors.newCachedThreadPool(webBridgeThreadFactory());
			httpServer.setExecutor(executor);
			long startedAtEpochMillis = Instant.now().toEpochMilli();
			httpServer.createContext("/api/ping", LocalWebAppBridgeService::handlePingRequest);
			httpServer.createContext("/api/storage/index", LocalWebAppBridgeService::handleStorageIndexRequest);
			httpServer.createContext("/api/storage/entry", LocalWebAppBridgeService::handleStorageEntryRequest);
			httpServer.createContext("/", LocalWebAppBridgeService::handleStaticRequest);
			httpServer.start();
			URI baseUri = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/");
			BridgeRuntime finalizedRuntime = new BridgeRuntime(
				httpServer,
				executor,
				baseUri,
				startedAtEpochMillis
			);
			LOGGER.info("RedstoneLink 本地网页桥已启动: {}", baseUri);
			return finalizedRuntime;
		} catch (IOException exception) {
			throw new IllegalStateException("failed to start local web bridge", exception);
		}
	}

	/**
	 * 处理最小桥接验链接口，供网页端确认本地离线容器已生效。
	 */
	private static void handlePingRequest(HttpExchange exchange) throws IOException {
		if (!isAllowedMethod(exchange)) {
			sendJsonResponse(exchange, 405, LocalWebJsonSupport.buildErrorPayload("Method Not Allowed"));
			return;
		}
		BridgeRuntime currentRuntime = resolveRuntime();
		String payload = LocalWebJsonSupport.buildPingPayload(
			RedstoneLink.MOD_ID,
			BRIDGE_VERSION,
			currentRuntime.startedAtEpochMillis(),
			currentRuntime.baseUri().toString()
		);
		sendJsonResponse(exchange, 200, payload);
	}

	/**
	 * 返回本地资产目录索引，供网页端列出 recordings/graphs/drafts/layouts 四类资产。
	 */
	private static void handleStorageIndexRequest(HttpExchange exchange) throws IOException {
		if (!isAllowedMethod(exchange)) {
			sendJsonResponse(exchange, 405, LocalWebJsonSupport.buildErrorPayload("Method Not Allowed"));
			return;
		}
		try {
			StorageIndex storageIndex = ASSET_REPOSITORY.index();
			sendJsonResponse(exchange, 200, LocalWebJsonSupport.buildStorageIndexPayload(storageIndex));
		} catch (IOException exception) {
			LOGGER.warn("读取本地网页资产索引失败", exception);
			sendJsonResponse(exchange, 500, LocalWebJsonSupport.buildErrorPayload("Failed to build storage index."));
		}
	}

	/**
	 * 返回单条本地资产内容预览。
	 */
	private static void handleStorageEntryRequest(HttpExchange exchange) throws IOException {
		if (!isAllowedMethod(exchange)) {
			sendJsonResponse(exchange, 405, LocalWebJsonSupport.buildErrorPayload("Method Not Allowed"));
			return;
		}
		Map<String, String> queryParameters = parseQueryParameters(exchange.getRequestURI());
		LocalWebAssetKind assetKind = LocalWebAssetKind.tryParse(queryParameters.get("kind")).orElse(null);
		String fileName = queryParameters.get("name");
		if (assetKind == null || fileName == null || fileName.isBlank()) {
			sendJsonResponse(exchange, 400, LocalWebJsonSupport.buildErrorPayload("Query parameters `kind` and `name` are required."));
			return;
		}
		try {
			StorageEntryContent entryContent = ASSET_REPOSITORY.readEntry(assetKind, fileName);
			if (entryContent == null) {
				sendJsonResponse(exchange, 404, LocalWebJsonSupport.buildErrorPayload("Storage entry was not found."));
				return;
			}
			sendJsonResponse(exchange, 200, LocalWebJsonSupport.buildStorageEntryPayload(entryContent));
		} catch (IOException exception) {
			LOGGER.warn("读取本地网页资产内容失败: kind={}, file={}", assetKind.token(), fileName, exception);
			sendJsonResponse(exchange, 500, LocalWebJsonSupport.buildErrorPayload("Failed to read storage entry."));
		}
	}

	/**
	 * 提供 Jar 内网页资源；若命中 SPA 路由则统一回退到 `index.html`。
	 */
	private static void handleStaticRequest(HttpExchange exchange) throws IOException {
		if (!isAllowedMethod(exchange)) {
			sendTextResponse(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
			return;
		}
		String normalizedPath = normalizeStaticResourcePath(exchange.getRequestURI().getPath());
		if (normalizedPath == null) {
			sendTextResponse(exchange, 400, "text/plain; charset=utf-8", "Bad Request");
			return;
		}

		byte[] resourceBytes = readEmbeddedResource(normalizedPath);
		String contentType = resolveContentType(normalizedPath);
		if (resourceBytes == null) {
			if (HOME_PAGE_RESOURCE.equals(normalizedPath)) {
				sendTextResponse(exchange, 503, "text/html; charset=utf-8", buildMissingWebAppHtml());
				return;
			}
			sendTextResponse(exchange, 404, "text/plain; charset=utf-8", "Not Found");
			return;
		}
		sendBinaryResponse(exchange, 200, contentType, resourceBytes);
	}

	/**
	 * 将浏览器请求路径收敛到 Jar 内静态资源路径，同时阻断目录穿越。
	 */
	static String normalizeStaticResourcePath(String rawPath) {
		if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
			return HOME_PAGE_RESOURCE;
		}
		String normalized = rawPath.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		if (normalized.isBlank()) {
			return HOME_PAGE_RESOURCE;
		}
		if (normalized.contains("..") || normalized.contains(":")) {
			return null;
		}
		if (normalized.endsWith("/")) {
			return HOME_PAGE_RESOURCE;
		}
		if (!normalized.contains(".")) {
			return HOME_PAGE_RESOURCE;
		}
		return normalized;
	}

	/**
	 * 根据资源后缀返回最小可用的 MIME 类型集合。
	 */
	static String resolveContentType(String resourcePath) {
		String normalized = resourcePath == null ? "" : resourcePath.toLowerCase(Locale.ROOT);
		if (normalized.endsWith(".html")) {
			return "text/html; charset=utf-8";
		}
		if (normalized.endsWith(".js")) {
			return "text/javascript; charset=utf-8";
		}
		if (normalized.endsWith(".css")) {
			return "text/css; charset=utf-8";
		}
		if (normalized.endsWith(".json") || normalized.endsWith(".map")) {
			return "application/json; charset=utf-8";
		}
		if (normalized.endsWith(".svg")) {
			return "image/svg+xml";
		}
		if (normalized.endsWith(".png")) {
			return "image/png";
		}
		if (normalized.endsWith(".ico")) {
			return "image/x-icon";
		}
		if (normalized.endsWith(".txt")) {
			return "text/plain; charset=utf-8";
		}
		return "application/octet-stream";
	}

	private static byte[] readEmbeddedResource(String relativePath) throws IOException {
		String resourcePath = WEBAPP_RESOURCE_ROOT + "/" + relativePath;
		try (InputStream inputStream = LocalWebAppBridgeService.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				return null;
			}
			return inputStream.readAllBytes();
		}
	}

	private static boolean isAllowedMethod(HttpExchange exchange) {
		String requestMethod = exchange.getRequestMethod();
		return "GET".equalsIgnoreCase(requestMethod) || "HEAD".equalsIgnoreCase(requestMethod);
	}

	private static void sendBinaryResponse(HttpExchange exchange, int statusCode, String contentType, byte[] body) throws IOException {
		applyCommonHeaders(exchange, contentType);
		if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(statusCode, -1);
			exchange.close();
			return;
		}
		exchange.sendResponseHeaders(statusCode, body.length);
		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(body);
		}
	}

	private static void sendTextResponse(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
		sendBinaryResponse(exchange, statusCode, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void sendJsonResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
		sendTextResponse(exchange, statusCode, "application/json; charset=utf-8", body);
	}

	private static void applyCommonHeaders(HttpExchange exchange, String contentType) {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
	}

	private static ThreadFactory webBridgeThreadFactory() {
		return Thread.ofPlatform().name("redstonelink-web-bridge-", 0).daemon(true).factory();
	}

	private static String buildMissingWebAppHtml() {
		return """
			<!doctype html>
			<html lang="zh-CN">
			  <head>
			    <meta charset="utf-8" />
			    <title>RedstoneLink Web Tools Unavailable</title>
			    <style>
			      body {
			        margin: 0;
			        min-height: 100vh;
			        display: grid;
			        place-items: center;
			        background: #171317;
			        color: #f7ebe1;
			        font-family: "Microsoft YaHei UI", sans-serif;
			      }
			      main {
			        width: min(720px, calc(100vw - 32px));
			        padding: 28px;
			        border-radius: 20px;
			        background: rgba(36, 24, 23, 0.92);
			        border: 1px solid rgba(255, 210, 188, 0.14);
			      }
			      h1 {
			        margin-top: 0;
			      }
			      code {
			        color: #ffd0ad;
			      }
			    </style>
			  </head>
			  <body>
			    <main>
			      <h1>未找到嵌入网页资源</h1>
			      <p>本地网页桥已启动，但当前 classpath 中没有 <code>assets/redstonelink/webapp</code> 资源。</p>
			      <p>请先执行 Gradle 构建链路，例如 <code>runClient</code>、<code>build</code> 或显式执行 <code>buildWebapp</code>。</p>
			    </main>
			  </body>
			</html>
			""";
	}

	/**
	 * 解析请求查询参数。
	 */
	private static Map<String, String> parseQueryParameters(URI requestUri) {
		String rawQuery = requestUri == null ? null : requestUri.getRawQuery();
		if (rawQuery == null || rawQuery.isBlank()) {
			return Map.of();
		}
		Map<String, String> queryParameters = new LinkedHashMap<>();
		for (String pair : rawQuery.split("&")) {
			if (pair == null || pair.isBlank()) {
				continue;
			}
			int separatorIndex = pair.indexOf('=');
			String rawKey = separatorIndex >= 0 ? pair.substring(0, separatorIndex) : pair;
			String rawValue = separatorIndex >= 0 ? pair.substring(separatorIndex + 1) : "";
			String decodedKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
			String decodedValue = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
			queryParameters.put(decodedKey, decodedValue);
		}
		return queryParameters;
	}

	private static String urlEncode(String rawValue) {
		return java.net.URLEncoder.encode(rawValue == null ? "" : rawValue, StandardCharsets.UTF_8);
	}

	/**
	 * 本地桥运行时快照。
	 */
	private record BridgeRuntime(HttpServer server, ExecutorService executor, URI baseUri, long startedAtEpochMillis) {}
}
