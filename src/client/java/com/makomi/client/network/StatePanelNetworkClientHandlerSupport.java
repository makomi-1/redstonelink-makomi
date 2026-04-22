package com.makomi.client.network;

import com.makomi.RedstoneLink;
import com.makomi.client.screen.StatePanelRecordingScreen;
import com.makomi.client.screen.StatePanelToolScreen;
import com.makomi.client.web.LocalWebAppBridgeService;
import com.makomi.client.web.LocalWebAssetKind;
import com.makomi.client.web.LocalWebAssetRepository;
import com.makomi.client.web.LocalWebGraphRefreshRpc;
import com.makomi.client.web.LocalWebGraphSaveRpc;
import com.makomi.network.StatePanelNetwork;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 状态面板工具客户端接包处理壳。
 */
public final class StatePanelNetworkClientHandlerSupport {
	private static final Map<String, PendingRecordingExport> PENDING_RECORDING_EXPORTS = new HashMap<>();
	private static final Map<String, PendingRecordingExport> PENDING_GRAPH_EXPORTS = new HashMap<>();
	private static final Set<String> PENDING_GRAPH_FORCE_TRANSFERS = new HashSet<>();

	private StatePanelNetworkClientHandlerSupport() {
	}

	/**
	 * 注册全部客户端接包器。
	 */
	public static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.OpenStatePanelPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> openPanel(payload.subscriptions()));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StatePanelSnapshotPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> applySnapshot(payload.entries()));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StatePanelFeedbackPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> applyFeedback(payload.success(), payload.messageKey(), payload.messageArgs()));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StatePanelRecordingSessionPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> applyRecordingSession(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StatePanelRecordingExportChunkPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> applyRecordingExportChunk(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.StatePanelGraphExportChunkPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> applyGraphExportChunk(payload));
		});
		ClientPlayNetworking.registerGlobalReceiver(StatePanelNetwork.GraphWriteResultPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> LocalWebGraphSaveRpc.completeResponse(payload.requestId(), payload.responseJson()));
		});
	}

	/**
	 * 打开状态面板界面。
	 */
	private static void openPanel(java.util.List<StatePanelNetwork.SubscriptionEntryPayload> subscriptions) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.setScreen(new StatePanelToolScreen(subscriptions));
	}

	/**
	 * 将服务端快照应用到当前状态面板界面。
	 */
	private static void applySnapshot(java.util.List<StatePanelNetwork.StatePanelSnapshotEntry> entries) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof StatePanelToolScreen statePanelToolScreen) {
			statePanelToolScreen.applySnapshot(entries);
		}
	}

	/**
	 * 将服务端录制会话状态应用到当前录制界面。
	 */
	private static void applyRecordingSession(StatePanelNetwork.StatePanelRecordingSessionPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof StatePanelRecordingScreen statePanelRecordingScreen) {
			statePanelRecordingScreen.applySessionSnapshot(payload);
		}
	}

	/**
	 * 将服务端分块导出的 recording bundle 重组成客户端本地资产。
	 */
	private static void applyRecordingExportChunk(StatePanelNetwork.StatePanelRecordingExportChunkPayload payload) {
		if (payload == null || payload.totalChunks() <= 0 || payload.fileName().isBlank()) {
			return;
		}
		PendingRecordingExport pendingRecordingExport = PENDING_RECORDING_EXPORTS.compute(
			payload.fileName(),
			(fileName, current) -> current != null && current.totalChunks() == payload.totalChunks()
				? current
				: new PendingRecordingExport(payload.totalChunks(), payload.autoOpenWeb())
		);
		pendingRecordingExport.acceptChunk(payload.chunkIndex(), payload.chunkBytes());
		if (!pendingRecordingExport.complete()) {
			return;
		}

		PENDING_RECORDING_EXPORTS.remove(payload.fileName());
		try {
			LocalWebAssetRepository repository = LocalWebAssetRepository.createDefault();
			repository.writeAssetBytes(LocalWebAssetKind.RECORDING, payload.fileName(), pendingRecordingExport.joinBytes());
			applyFeedback(true, "message.redstonelink.state_panel.recording.export.saved", List.of(payload.fileName()));
		} catch (IOException | RuntimeException exception) {
			RedstoneLink.LOGGER.warn("客户端写入 recording bundle 失败: file={}", payload.fileName(), exception);
			applyFeedback(false, "message.redstonelink.state_panel.recording.export.write_failed", List.of(payload.fileName()));
			return;
		}
		if (pendingRecordingExport.autoOpenWeb()) {
			try {
				LocalWebAppBridgeService.openAssetEntry(LocalWebAssetKind.RECORDING, payload.fileName());
			} catch (RuntimeException exception) {
				RedstoneLink.LOGGER.warn("客户端打开 recording 网页失败: file={}", payload.fileName(), exception);
				applyFeedback(false, "message.redstonelink.web.open_failed", List.of(exception.getMessage() == null ? "open_failed" : exception.getMessage()));
			}
		}
	}

	/**
	 * 将服务端分块导出的 graph snapshot 重组成客户端本地资产。
	 */
	private static void applyGraphExportChunk(StatePanelNetwork.StatePanelGraphExportChunkPayload payload) {
		if (payload == null || payload.fileName().isBlank()) {
			return;
		}
		if (payload.totalChunks() <= 0) {
			applyCachedGraphExport(payload);
			return;
		}
		PENDING_GRAPH_FORCE_TRANSFERS.remove(payload.fileName());
		PendingRecordingExport pendingRecordingExport = PENDING_GRAPH_EXPORTS.compute(
			payload.fileName(),
			(fileName, current) -> current != null && current.totalChunks() == payload.totalChunks()
				? current
				: new PendingRecordingExport(payload.totalChunks(), payload.autoOpenWeb())
		);
		pendingRecordingExport.acceptChunk(payload.chunkIndex(), payload.chunkBytes());
		if (!pendingRecordingExport.complete()) {
			return;
		}

		PENDING_GRAPH_EXPORTS.remove(payload.fileName());
		try {
			LocalWebAssetRepository repository = LocalWebAssetRepository.createDefault();
			repository.writeAssetBytes(LocalWebAssetKind.GRAPH, payload.fileName(), pendingRecordingExport.joinBytes());
			if (payload.requestId().isBlank()) {
				applyFeedback(true, "message.redstonelink.graph.export.saved", List.of(payload.fileName()));
			}
			LocalWebGraphRefreshRpc.completeResponse(payload.requestId(), payload.fileName());
		} catch (IOException | RuntimeException exception) {
			RedstoneLink.LOGGER.warn("客户端写入 graph snapshot 失败: file={}", payload.fileName(), exception);
			if (payload.requestId().isBlank()) {
				applyFeedback(false, "message.redstonelink.graph.export.write_failed", List.of(payload.fileName()));
			}
			LocalWebGraphRefreshRpc.failResponse(
				payload.requestId(),
				"Failed to write refreshed graph snapshot `%s`.".formatted(payload.fileName())
			);
			return;
		}
		if (pendingRecordingExport.autoOpenWeb()) {
			try {
				LocalWebAppBridgeService.openAssetEntry(LocalWebAssetKind.GRAPH, payload.fileName());
			} catch (RuntimeException exception) {
				RedstoneLink.LOGGER.warn("客户端打开 graph 网页失败: file={}", payload.fileName(), exception);
				applyFeedback(false, "message.redstonelink.web.open_failed", List.of(exception.getMessage() == null ? "open_failed" : exception.getMessage()));
			}
		}
	}

	/**
	 * 命中 graph revision 复用时，直接打开本地已有 graph 文件。
	 */
	private static void applyCachedGraphExport(StatePanelNetwork.StatePanelGraphExportChunkPayload payload) {
		try {
			LocalWebAssetRepository repository = LocalWebAssetRepository.createDefault();
			LocalWebAssetRepository.StorageEntryContent entryContent = repository.readEntry(LocalWebAssetKind.GRAPH, payload.fileName());
			if (entryContent == null) {
				requestForcedGraphTransfer(payload.fileName(), payload.autoOpenWeb(), payload.requestId());
				return;
			}
			PENDING_GRAPH_FORCE_TRANSFERS.remove(payload.fileName());
			LocalWebGraphRefreshRpc.completeResponse(payload.requestId(), payload.fileName());
			if (payload.autoOpenWeb()) {
				LocalWebAppBridgeService.openAssetEntry(LocalWebAssetKind.GRAPH, payload.fileName());
			}
		} catch (IOException | RuntimeException exception) {
			RedstoneLink.LOGGER.warn("客户端复用 graph snapshot 失败: file={}", payload.fileName(), exception);
			requestForcedGraphTransfer(payload.fileName(), payload.autoOpenWeb(), payload.requestId());
		}
	}

	/**
	 * 本地 graph 文件缺失时，自动回源请求一次强制重传，避免跨重启复用链路卡死。
	 */
	private static void requestForcedGraphTransfer(String fileName, boolean autoOpenWeb, String requestId) {
		if (!PENDING_GRAPH_FORCE_TRANSFERS.add(fileName)) {
			if (requestId.isBlank()) {
				applyFeedback(false, "message.redstonelink.graph.export.write_failed", List.of(fileName));
			}
			LocalWebGraphRefreshRpc.failResponse(
				requestId,
				"Failed to force transfer refreshed graph snapshot `%s`.".formatted(fileName)
			);
			return;
		}
		ClientPlayNetworking.send(new StatePanelNetwork.ExportStatePanelGraphPayload(requestId, true, autoOpenWeb));
	}

	/**
	 * 将服务端反馈应用到当前状态面板界面；若界面未打开则走 action bar。
	 */
	private static void applyFeedback(boolean success, String messageKey, java.util.List<String> messageArgs) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof StatePanelToolScreen statePanelToolScreen) {
			statePanelToolScreen.applyFeedback(success, messageKey, messageArgs);
			return;
		}
		if (minecraft.screen instanceof StatePanelRecordingScreen statePanelRecordingScreen) {
			statePanelRecordingScreen.applyFeedback(success, messageKey, messageArgs);
			return;
		}
		if (minecraft.player == null || messageKey == null || messageKey.isBlank()) {
			return;
		}
		Object[] args = messageArgs == null ? new Object[0] : messageArgs.toArray();
		minecraft.player.displayClientMessage(Component.translatable(messageKey, args), true);
	}

	/**
	 * 客户端分块导出重组器。
	 */
	private record PendingRecordingExport(int totalChunks, boolean autoOpenWeb, byte[][] chunks, boolean[] received) {
		private PendingRecordingExport(int totalChunks, boolean autoOpenWeb) {
			this(
				Math.max(1, totalChunks),
				autoOpenWeb,
				new byte[Math.max(1, totalChunks)][],
				new boolean[Math.max(1, totalChunks)]
			);
		}

		private void acceptChunk(int chunkIndex, byte[] chunkBytes) {
			if (chunkIndex < 0 || chunkIndex >= totalChunks) {
				return;
			}
			chunks[chunkIndex] = chunkBytes == null ? new byte[0] : chunkBytes.clone();
			received[chunkIndex] = true;
		}

		private boolean complete() {
			for (boolean chunkReceived : received) {
				if (!chunkReceived) {
					return false;
				}
			}
			return true;
		}

		private byte[] joinBytes() throws IOException {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			for (byte[] chunk : chunks) {
				if (chunk != null && chunk.length > 0) {
					outputStream.write(chunk);
				}
			}
			return outputStream.toByteArray();
		}
	}
}
