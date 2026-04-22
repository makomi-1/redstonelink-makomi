package com.makomi.block.entity;

import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorMode;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.PlacedChunkActivatorSavedData;
import com.makomi.util.SerialParseUtil;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 区块激活器方块实体。
 * <p>
 * 负责保存节点集配置、展示别名与“当前激活态”真值，
 * 并把激活态下的有效贡献同步到 `PlacedChunkActivatorSavedData`。
 * </p>
 */
public class LinkChunkActivatorBlockEntity extends BlockEntity {
	private static final String KEY_SERIAL_EXPRESSION = "serialExpression";
	private static final String KEY_MODE = "mode";
	private static final String KEY_DISPLAY_ALIAS = "DisplayAlias";
	private static final String KEY_ACTIVE = "active";

	private String serialExpression = "";
	private ChunkActivatorMode mode = ChunkActivatorMode.FORCE_LOAD;
	private Set<Long> serials = Set.of();
	private String displayAlias = "";
	private boolean active;
	private final ChunkActivatorLifecycleState lifecycleState = new ChunkActivatorLifecycleState();

	public LinkChunkActivatorBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_CHUNK_ACTIVATOR, blockPos, blockState);
	}

	/**
	 * 返回当前配置快照。
	 */
	public final ChunkActivatorConfigSnapshot snapshot() {
		return new ChunkActivatorConfigSnapshot(serialExpression, mode);
	}

	/**
	 * @return 当前缓存的展示别名
	 */
	public final String displayAlias() {
		return displayAlias;
	}

	/**
	 * @return 当前持久化的激活态真值
	 */
	public final boolean active() {
		return active;
	}

	/**
	 * 应用新的区块激活器配置快照。
	 */
	public final void applySnapshot(ChunkActivatorConfigSnapshot configSnapshot) {
		applyEditorState(displayAlias, configSnapshot);
	}

	/**
	 * 同时应用别名与区块激活器配置，并同步当前真值。
	 */
	public final void applyEditorState(String rawDisplayAlias, ChunkActivatorConfigSnapshot configSnapshot) {
		ChunkActivatorConfigSnapshot normalized = configSnapshot == null
			? new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD)
			: configSnapshot;
		displayAlias = NodeAliasDisplayUtil.normalizeAlias(rawDisplayAlias);
		serialExpression = normalized.serialExpression();
		mode = normalized.mode();
		serials = parseSerialExpression(serialExpression);
		syncToClient();
		syncPlacedActivatorState();
	}

	/**
	 * 标记当前区块激活器正进入真实物理移除路径。
	 */
	public final void markPhysicalRemovalInProgress() {
		lifecycleState.onPhysicalRemovalStarted();
	}

	/**
	 * 在不重采样邻居输入的前提下恢复已放置区块激活器真值。
	 */
	public final void restorePlacedActivatorState() {
		syncPlacedActivatorState();
	}

	/**
	 * 以当前世界输入重采样区块激活器激活态，并同步真值。
	 */
	public final void refreshPlacedActivatorState() {
		setActiveInternal(sampleNeighborSignalStrength() > 0);
	}

	/**
	 * @return 当前缓存的节点集表达式
	 */
	public final String serialExpression() {
		return serialExpression;
	}

	/**
	 * @return 当前区块激活器模式
	 */
	public final ChunkActivatorMode mode() {
		return mode;
	}

	/**
	 * @return 采样到的邻居最大输入强度
	 */
	public final int sampleNeighborSignalStrength() {
		Level currentLevel = level;
		return currentLevel == null ? 0 : Math.max(0, currentLevel.getBestNeighborSignal(worldPosition));
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		serialExpression = tag.contains(KEY_SERIAL_EXPRESSION, Tag.TAG_STRING) ? tag.getString(KEY_SERIAL_EXPRESSION) : "";
		mode = ChunkActivatorMode.tryParseToken(tag.getString(KEY_MODE)).orElse(ChunkActivatorMode.FORCE_LOAD);
		displayAlias = tag.contains(KEY_DISPLAY_ALIAS, Tag.TAG_STRING)
			? NodeAliasDisplayUtil.normalizeAlias(tag.getString(KEY_DISPLAY_ALIAS))
			: "";
		active = tag.getBoolean(KEY_ACTIVE);
		serials = parseSerialExpression(serialExpression);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (!serialExpression.isBlank()) {
			tag.putString(KEY_SERIAL_EXPRESSION, serialExpression);
		}
		tag.putString(KEY_MODE, mode.token());
		if (!displayAlias.isBlank()) {
			tag.putString(KEY_DISPLAY_ALIAS, displayAlias);
		}
		if (active) {
			tag.putBoolean(KEY_ACTIVE, true);
		}
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		lifecycleState.onContextAttached();
		restorePlacedActivatorState();
	}

	@Override
	public void setRemoved() {
		if (lifecycleState.onContextDetachedShouldRemovePersistedActivator()) {
			removePlacedActivatorState();
		}
		super.setRemoved();
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
		return saveWithoutMetadata(provider);
	}

	/**
	 * 通知客户端刷新方块实体与方块状态。
	 */
	protected final void syncToClient() {
		setChanged();
		if (level != null && !level.isClientSide) {
			BlockState state = getBlockState();
			level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		}
	}

	private void setActiveInternal(boolean nextActive) {
		if (active == nextActive) {
			syncPlacedActivatorState();
			return;
		}
		active = nextActive;
		syncToClient();
		syncPlacedActivatorState();
	}

	private void syncPlacedActivatorState() {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		PlacedChunkActivatorSavedData
			.get(serverLevel)
			.upsert(serverLevel.dimension(), worldPosition, snapshot(), displayAlias, active);
	}

	private void removePlacedActivatorState() {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		PlacedChunkActivatorSavedData.get(serverLevel).remove(serverLevel.dimension(), worldPosition);
	}

	private static Set<Long> parseSerialExpression(String rawExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			rawExpression,
			PlacedChunkActivatorSavedData.MAX_TRIGGER_SOURCE_COUNT
		);
		if (parseResult.orderedTargets().isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(parseResult.orderedTargets()));
	}
}
