package com.makomi.data;

import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 节点运行态探针。
 * <p>
 * 负责将 `core` 与 `sync triggerSource` 的当前方块实体状态统一转为
 * {@link NodeRuntimeSnapshot}，供命令、采样器和后续状态面板复用。
 * </p>
 */
public final class NodeRuntimeProbe {
	private NodeRuntimeProbe() {
	}

	/**
	 * 受支持的运行态采样类型。
	 */
	public enum TraceNodeKind {
		CORE("core"),
		SYNC_TRIGGER_SOURCE("syncTriggerSource");

		private final String commandName;

		TraceNodeKind(String commandName) {
			this.commandName = commandName;
		}

		/**
		 * 命令/输出统一使用的稳定名称。
		 */
		public String commandName() {
			return commandName;
		}
	}

	/**
	 * 解析当前节点可用的探针类型，并返回即时快照。
	 * <p>
	 * `core` 可在离线状态下直接解析为 `CORE` 探针；
	 * `triggerSource` 仅当当前在线且为 sync 类来源时才返回可用结果。
	 * </p>
	 */
	public static Optional<ProbeResolution> resolveCurrent(MinecraftServer server, LinkNodeType nodeType, long serial) {
		if (server == null || nodeType == null || serial <= 0L) {
			return Optional.empty();
		}
		if (nodeType == LinkNodeType.CORE) {
			return Optional.of(new ProbeResolution(TraceNodeKind.CORE, snapshot(server, nodeType, serial, TraceNodeKind.CORE)));
		}

		OnlineNodeContext onlineContext = resolveOnlineNodeContext(server, nodeType, serial).orElse(null);
		if (onlineContext == null) {
			return Optional.empty();
		}
		if (onlineContext.blockEntity() instanceof SyncReplaySourceBlockEntity) {
			return Optional.of(
				new ProbeResolution(
					TraceNodeKind.SYNC_TRIGGER_SOURCE,
					snapshot(server, nodeType, serial, TraceNodeKind.SYNC_TRIGGER_SOURCE)
				)
			);
		}
		return Optional.empty();
	}

	/**
	 * 按已知探针类型构建当前快照。
	 * <p>
	 * 若节点暂时离线，则返回离线快照，而不是抛错。
	 * </p>
	 */
	public static NodeRuntimeSnapshot snapshot(
		MinecraftServer server,
		LinkNodeType nodeType,
		long serial,
		TraceNodeKind traceKind
	) {
		if (server == null || nodeType == null || traceKind == null || serial <= 0L) {
			return offlineSnapshot(traceKind, nodeType, serial, false, false, 0L, null, null);
		}

		ServerLevel overworld = server.overworld();
		LinkSavedData savedData = LinkSavedData.get(overworld);
		boolean allocated = savedData.isSerialAllocated(nodeType, serial);
		boolean retired = savedData.isSerialRetired(nodeType, serial);
		long sampleTick = Math.max(0L, overworld.getGameTime());
		LinkSavedData.LinkNode onlineNode = savedData.findNode(nodeType, serial).orElse(null);
		if (onlineNode == null) {
			return offlineSnapshot(traceKind, nodeType, serial, allocated, retired, sampleTick, null, null);
		}

		ServerLevel nodeLevel = server.getLevel(onlineNode.dimension());
		if (nodeLevel == null || !nodeLevel.isLoaded(onlineNode.pos())) {
			return offlineSnapshot(traceKind, nodeType, serial, allocated, retired, sampleTick, onlineNode.dimension(), onlineNode.pos());
		}
		BlockEntity blockEntity = nodeLevel.getBlockEntity(onlineNode.pos());
		if (traceKind == TraceNodeKind.CORE && blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity) {
			return buildCoreSnapshot(targetBlockEntity, nodeType, serial, allocated, retired, sampleTick);
		}
		if (
			traceKind == TraceNodeKind.SYNC_TRIGGER_SOURCE
				&& blockEntity instanceof SyncReplaySourceBlockEntity replaySourceBlockEntity
		) {
			return buildSyncTriggerSourceSnapshot(
				nodeLevel,
				onlineNode.pos(),
				replaySourceBlockEntity,
				nodeType,
				serial,
				allocated,
				retired,
				sampleTick
			);
		}
		return offlineSnapshot(traceKind, nodeType, serial, allocated, retired, sampleTick, onlineNode.dimension(), onlineNode.pos());
	}

	private static NodeRuntimeSnapshot buildCoreSnapshot(
		ActivatableTargetBlockEntity targetBlockEntity,
		LinkNodeType nodeType,
		long serial,
		boolean allocated,
		boolean retired,
		long sampleTick
	) {
		List<Long> maxSourceSerials = targetBlockEntity.getSyncMaxSourceSerialsSnapshot();
		int resolvedStrength = targetBlockEntity.getResolvedStrength();
		return new NodeRuntimeSnapshot(
			TraceNodeKind.CORE,
			nodeType,
			serial,
			allocated,
			retired,
			true,
			targetBlockEntity.getLevel() == null ? null : targetBlockEntity.getLevel().dimension(),
			targetBlockEntity.getBlockPos(),
			sampleTick,
			0,
			targetBlockEntity.isActive(),
			resolvedStrength,
			targetBlockEntity.getResolvedOutputPower(),
			targetBlockEntity.getConfiguredMode().name().toLowerCase(java.util.Locale.ROOT),
			targetBlockEntity.getEffectiveMode().name().toLowerCase(java.util.Locale.ROOT),
			resolvedStrength,
			maxSourceSerials,
			0,
			0
		);
	}

	private static NodeRuntimeSnapshot buildSyncTriggerSourceSnapshot(
		ServerLevel nodeLevel,
		BlockPos blockPos,
		SyncReplaySourceBlockEntity replaySourceBlockEntity,
		LinkNodeType nodeType,
		long serial,
		boolean allocated,
		boolean retired,
		long sampleTick
	) {
		int inputPower = resolveCurrentInputPower(nodeLevel, blockPos, replaySourceBlockEntity);
		int lastObservedInputPower = replaySourceBlockEntity instanceof LinkSyncEmitterBlockEntity syncEmitterBlockEntity
			? syncEmitterBlockEntity.getLastObservedSignalStrength()
			: inputPower;
		int lastDispatchedPower = replaySourceBlockEntity.replaySyncSnapshot()
			.map(SyncReplaySourceBlockEntity.ReplaySyncSnapshot::signalStrength)
			.orElse(inputPower);
		return new NodeRuntimeSnapshot(
			TraceNodeKind.SYNC_TRIGGER_SOURCE,
			nodeType,
			serial,
			allocated,
			retired,
			true,
			nodeLevel.dimension(),
			blockPos,
			sampleTick,
			0,
			lastDispatchedPower > 0,
			inputPower,
			lastDispatchedPower,
			"sync",
			"sync",
			lastDispatchedPower,
			List.of(),
			lastObservedInputPower,
			lastDispatchedPower
		);
	}

	private static int resolveCurrentInputPower(
		ServerLevel nodeLevel,
		BlockPos blockPos,
		SyncReplaySourceBlockEntity replaySourceBlockEntity
	) {
		if (replaySourceBlockEntity instanceof LinkSyncEmitterBlockEntity) {
			return Math.max(0, nodeLevel.getBestNeighborSignal(blockPos));
		}
		var state = nodeLevel.getBlockState(blockPos);
		if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
			return 15;
		}
		return 0;
	}

	private static NodeRuntimeSnapshot offlineSnapshot(
		TraceNodeKind traceKind,
		LinkNodeType nodeType,
		long serial,
		boolean allocated,
		boolean retired,
		long sampleTick,
		net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
		BlockPos pos
	) {
		return new NodeRuntimeSnapshot(
			traceKind,
			nodeType,
			serial,
			allocated,
			retired,
			false,
			dimension,
			pos,
			sampleTick,
			0,
			false,
			0,
			0,
			traceKind == TraceNodeKind.CORE ? "-" : "sync",
			traceKind == TraceNodeKind.CORE ? "-" : "sync",
			0,
			List.of(),
			0,
			0
		);
	}

	private static Optional<OnlineNodeContext> resolveOnlineNodeContext(MinecraftServer server, LinkNodeType nodeType, long serial) {
		ServerLevel overworld = server == null ? null : server.overworld();
		if (overworld == null || nodeType == null || serial <= 0L) {
			return Optional.empty();
		}
		LinkSavedData.LinkNode node = LinkSavedData.get(overworld).findNode(nodeType, serial).orElse(null);
		if (node == null) {
			return Optional.empty();
		}
		ServerLevel nodeLevel = server.getLevel(node.dimension());
		if (nodeLevel == null || !nodeLevel.isLoaded(node.pos())) {
			return Optional.empty();
		}
		BlockEntity blockEntity = nodeLevel.getBlockEntity(node.pos());
		if (blockEntity == null) {
			return Optional.empty();
		}
		return Optional.of(new OnlineNodeContext(nodeLevel, node, blockEntity));
	}

	/**
	 * 当前节点已解析出的探针类型与即时快照。
	 */
	public record ProbeResolution(TraceNodeKind traceKind, NodeRuntimeSnapshot snapshot) {}

	private record OnlineNodeContext(ServerLevel level, LinkSavedData.LinkNode node, BlockEntity blockEntity) {}
}
