package com.makomi.network;

import com.makomi.data.GraphSnapshotExportService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * graph 导出结果网络发送支持。
 * <p>
 * 统一承载 graph 快照的去重导出结果回传，避免命令、状态面板和物品入口各自复制一套分块发送逻辑。
 * </p>
 */
public final class GraphExportNetworkSupport {
	private static final int GRAPH_EXPORT_CHUNK_BYTES = 24576;

	private GraphExportNetworkSupport() {
	}

	/**
	 * 导出当前玩家可见的 serial graph，并立即通过现有客户端 graph 资产链路回传。
	 */
	public static GraphSnapshotExportService.ExportBundle exportVisibleSerialGraph(
		ServerPlayer player,
		boolean forceTransfer,
		boolean autoOpenWeb
	) throws java.io.IOException {
		GraphSnapshotExportService.ExportBundle exportBundle = GraphSnapshotExportService.exportVisibleSerialGraph(
			player,
			forceTransfer
		);
		sendGraphExport(player, exportBundle, autoOpenWeb);
		return exportBundle;
	}

	/**
	 * 分块发送 graph 快照结果，避免单包体积过大。
	 */
	public static void sendGraphExport(
		ServerPlayer player,
		GraphSnapshotExportService.ExportBundle exportBundle,
		boolean autoOpenWeb
	) {
		byte[] compressedBytes = exportBundle == null ? null : exportBundle.compressedBytes();
		if (player == null || exportBundle == null) {
			return;
		}
		if (exportBundle.reusedExisting()) {
			ServerPlayNetworking.send(
				player,
				new StatePanelNetwork.StatePanelGraphExportChunkPayload(
					exportBundle.fileName(),
					0,
					0,
					autoOpenWeb,
					new byte[0]
				)
			);
			return;
		}
		if (compressedBytes == null || compressedBytes.length <= 0) {
			return;
		}
		int totalChunks = Math.max(1, (compressedBytes.length + GRAPH_EXPORT_CHUNK_BYTES - 1) / GRAPH_EXPORT_CHUNK_BYTES);
		for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
			int startOffset = chunkIndex * GRAPH_EXPORT_CHUNK_BYTES;
			int endOffset = Math.min(compressedBytes.length, startOffset + GRAPH_EXPORT_CHUNK_BYTES);
			int chunkLength = Math.max(0, endOffset - startOffset);
			byte[] chunkBytes = new byte[chunkLength];
			System.arraycopy(compressedBytes, startOffset, chunkBytes, 0, chunkLength);
			ServerPlayNetworking.send(
				player,
				new StatePanelNetwork.StatePanelGraphExportChunkPayload(
					exportBundle.fileName(),
					chunkIndex,
					totalChunks,
					autoOpenWeb,
					chunkBytes
				)
			);
		}
	}
}
