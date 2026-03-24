package com.makomi.data;

import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.block.entity.ActivatableTargetBlockEntity;
import com.makomi.block.entity.LinkPulseEmitterBlockEntity;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import com.makomi.block.entity.LinkToggleEmitterBlockEntity;
import com.makomi.block.entity.LinkTriggerSourceBlockEntity;
import com.makomi.block.entity.SyncReplaySourceBlockEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 节点运行态探针。
 * <p>
 * 负责将 `core` 与在线 emitter `triggerSource` 的当前方块实体状态统一转为
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
		PULSE_TRIGGER_SOURCE("pulseTriggerSource"),
		TOGGLE_TRIGGER_SOURCE("toggleTriggerSource"),
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
	 * `triggerSource` 仅当当前在线且为受支持的 emitter 类来源时才返回可用结果。
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
		if (onlineContext.blockEntity() instanceof LinkSyncEmitterBlockEntity) {
			return Optional.of(
				new ProbeResolution(
					TraceNodeKind.SYNC_TRIGGER_SOURCE,
					snapshot(server, nodeType, serial, TraceNodeKind.SYNC_TRIGGER_SOURCE)
				)
			);
		}
		if (onlineContext.blockEntity() instanceof LinkPulseEmitterBlockEntity) {
			return Optional.of(
				new ProbeResolution(
					TraceNodeKind.PULSE_TRIGGER_SOURCE,
					snapshot(server, nodeType, serial, TraceNodeKind.PULSE_TRIGGER_SOURCE)
				)
			);
		}
		if (onlineContext.blockEntity() instanceof LinkToggleEmitterBlockEntity) {
			return Optional.of(
				new ProbeResolution(
					TraceNodeKind.TOGGLE_TRIGGER_SOURCE,
					snapshot(server, nodeType, serial, TraceNodeKind.TOGGLE_TRIGGER_SOURCE)
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
			return offlineSnapshot(
				traceKind,
				new NodeIdentitySnapshot(nodeType, serial, false, false, false, null, null),
				0L
			);
		}

		ServerLevel overworld = server.overworld();
		NodeIdentitySnapshot identity = NodeIdentitySnapshot.resolve(overworld, nodeType, serial);
		long sampleTick = Math.max(0L, overworld.getGameTime());
		if (!identity.online()) {
			return offlineSnapshot(traceKind, identity, sampleTick);
		}

		ServerLevel nodeLevel = server.getLevel(identity.dimension());
		if (nodeLevel == null || identity.pos() == null || !nodeLevel.isLoaded(identity.pos())) {
			return offlineSnapshot(traceKind, identity.withOnline(false), sampleTick);
		}
		BlockEntity blockEntity = nodeLevel.getBlockEntity(identity.pos());
		if (traceKind == TraceNodeKind.CORE && blockEntity instanceof ActivatableTargetBlockEntity targetBlockEntity) {
			return buildCoreSnapshot(targetBlockEntity, identity, sampleTick);
		}
		if (
			traceKind == TraceNodeKind.PULSE_TRIGGER_SOURCE
				&& blockEntity instanceof LinkPulseEmitterBlockEntity pulseEmitterBlockEntity
		) {
			return buildEmitterTriggerSourceSnapshot(
				nodeLevel,
				identity.pos(),
				pulseEmitterBlockEntity,
				traceKind,
				identity,
				sampleTick
			);
		}
		if (
			traceKind == TraceNodeKind.TOGGLE_TRIGGER_SOURCE
				&& blockEntity instanceof LinkToggleEmitterBlockEntity toggleEmitterBlockEntity
		) {
			return buildEmitterTriggerSourceSnapshot(
				nodeLevel,
				identity.pos(),
				toggleEmitterBlockEntity,
				traceKind,
				identity,
				sampleTick
			);
		}
		if (
			traceKind == TraceNodeKind.SYNC_TRIGGER_SOURCE
				&& blockEntity instanceof LinkSyncEmitterBlockEntity syncEmitterBlockEntity
		) {
			return buildSyncTriggerSourceSnapshot(
				nodeLevel,
				identity.pos(),
				syncEmitterBlockEntity,
				identity,
				sampleTick
			);
		}
		return offlineSnapshot(traceKind, identity.withOnline(false), sampleTick);
	}

	private static NodeRuntimeSnapshot buildCoreSnapshot(
		ActivatableTargetBlockEntity targetBlockEntity,
		NodeIdentitySnapshot identity,
		long sampleTick
	) {
		List<Long> maxSourceSerials = targetBlockEntity.getSyncMaxSourceSerialsSnapshot();
		int resolvedStrength = targetBlockEntity.getResolvedStrength();
		return new NodeRuntimeSnapshot(
			TraceNodeKind.CORE,
			new NodeIdentitySnapshot(
				identity.nodeType(),
				identity.serial(),
				identity.allocated(),
				identity.retired(),
				true,
				targetBlockEntity.getLevel() == null ? identity.dimension() : targetBlockEntity.getLevel().dimension(),
				targetBlockEntity.getBlockPos()
			),
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

	private static NodeRuntimeSnapshot buildEmitterTriggerSourceSnapshot(
		ServerLevel nodeLevel,
		BlockPos blockPos,
		LinkTriggerSourceBlockEntity triggerSourceBlockEntity,
		TraceNodeKind traceKind,
		NodeIdentitySnapshot identity,
		long sampleTick
	) {
		EmitterLiveState liveState = resolveEmitterLiveState(nodeLevel, blockPos).orElse(null);
		if (liveState == null) {
			return offlineSnapshot(
				traceKind,
				new NodeIdentitySnapshot(
					identity.nodeType(),
					identity.serial(),
					identity.allocated(),
					identity.retired(),
					false,
					nodeLevel.dimension(),
					blockPos
				),
				sampleTick
			);
		}
		String modeName = defaultModeName(traceKind);
		return new NodeRuntimeSnapshot(
			traceKind,
			new NodeIdentitySnapshot(
				identity.nodeType(),
				identity.serial(),
				identity.allocated(),
				identity.retired(),
				true,
				nodeLevel.dimension(),
				blockPos
			),
			sampleTick,
			0,
			liveState.visiblePowered(),
			liveState.inputPower(),
			liveState.visiblePower(),
			modeName,
			modeName,
			liveState.visiblePower(),
			List.of(),
			liveState.inputPower(),
			liveState.visiblePower()
		);
	}

	private static NodeRuntimeSnapshot buildSyncTriggerSourceSnapshot(
		ServerLevel nodeLevel,
		BlockPos blockPos,
		LinkSyncEmitterBlockEntity syncEmitterBlockEntity,
		NodeIdentitySnapshot identity,
		long sampleTick
	) {
		EmitterLiveState liveState = resolveEmitterLiveState(nodeLevel, blockPos).orElse(null);
		if (liveState == null) {
			return offlineSnapshot(
				TraceNodeKind.SYNC_TRIGGER_SOURCE,
				new NodeIdentitySnapshot(
					identity.nodeType(),
					identity.serial(),
					identity.allocated(),
					identity.retired(),
					false,
					nodeLevel.dimension(),
					blockPos
				),
				sampleTick
			);
		}
		int lastObservedInputPower = syncEmitterBlockEntity.getLastObservedSignalStrength();
		int lastDispatchedPower = syncEmitterBlockEntity.replaySyncSnapshot()
			.map(SyncReplaySourceBlockEntity.ReplaySyncSnapshot::signalStrength)
			.orElse(liveState.inputPower());
		return new NodeRuntimeSnapshot(
			TraceNodeKind.SYNC_TRIGGER_SOURCE,
			new NodeIdentitySnapshot(
				identity.nodeType(),
				identity.serial(),
				identity.allocated(),
				identity.retired(),
				true,
				nodeLevel.dimension(),
				blockPos
			),
			sampleTick,
			0,
			liveState.visiblePowered(),
			liveState.inputPower(),
			lastDispatchedPower,
			"sync",
			"sync",
			lastDispatchedPower,
			List.of(),
			lastObservedInputPower,
			lastDispatchedPower
		);
	}

	private static Optional<EmitterLiveState> resolveEmitterLiveState(ServerLevel nodeLevel, BlockPos blockPos) {
		if (nodeLevel == null || blockPos == null) {
			return Optional.empty();
		}
		BlockState blockState = nodeLevel.getBlockState(blockPos);
		if (!(blockState.getBlock() instanceof LinkSignalEmitterBlock signalEmitterBlock)) {
			return Optional.empty();
		}
		int inputPower = signalEmitterBlock.sampleInputSignalStrength(nodeLevel, blockPos);
		boolean visiblePowered = blockState.hasProperty(BlockStateProperties.POWERED)
			&& blockState.getValue(BlockStateProperties.POWERED);
		return Optional.of(new EmitterLiveState(inputPower, visiblePowered, visiblePowered ? 15 : 0));
	}

	private static NodeRuntimeSnapshot offlineSnapshot(
		TraceNodeKind traceKind,
		NodeIdentitySnapshot identity,
		long sampleTick
	) {
		return new NodeRuntimeSnapshot(
			traceKind,
			identity == null ? new NodeIdentitySnapshot(LinkNodeType.CORE, 0L, false, false, false, null, null) : identity,
			sampleTick,
			0,
			false,
			0,
			0,
			defaultModeName(traceKind),
			defaultModeName(traceKind),
			0,
			List.of(),
			0,
			0
		);
	}

	private static String defaultModeName(TraceNodeKind traceKind) {
		if (traceKind == null) {
			return "-";
		}
		return switch (traceKind) {
			case CORE -> "-";
			case PULSE_TRIGGER_SOURCE -> "pulse";
			case TOGGLE_TRIGGER_SOURCE -> "toggle";
			case SYNC_TRIGGER_SOURCE -> "sync";
		};
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

	private record EmitterLiveState(int inputPower, boolean visiblePowered, int visiblePower) {}
}
