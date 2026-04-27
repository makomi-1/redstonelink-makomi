package com.makomi.block.entity;

import com.makomi.data.ChunkActivatorConfigSnapshot;
import com.makomi.data.ChunkActivatorConfigStateSnapshot;
import com.makomi.data.ChunkActivatorImmediateEffectService;
import com.makomi.data.ChunkActivatorMode;
import com.makomi.data.LinkNodeType;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasServerSupport;
import com.makomi.data.PlacedChunkActivatorSavedData;
import com.makomi.util.SerialParseUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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
	private static final String KEY_ACTIVE_TYPE = "activeType";
	private static final String KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION = "triggerSourceSerialExpression";
	private static final String KEY_TRIGGER_SOURCE_MODE = "triggerSourceMode";
	private static final String KEY_CORE_SERIAL_EXPRESSION = "coreSerialExpression";
	private static final String KEY_CORE_MODE = "coreMode";
	private static final String KEY_DISPLAY_ALIAS = "DisplayAlias";
	private static final String KEY_ACTIVE = "active";
	private static final String KEY_LEGACY_SERIAL_EXPRESSION = "serialExpression";
	private static final String KEY_LEGACY_MODE = "mode";
	private static final String KEY_TRIGGER_SOURCE_NODE_SET_DISPLAY_TEXTS = "triggerSourceNodeSetDisplayTexts";
	private static final String KEY_CORE_NODE_SET_DISPLAY_TEXTS = "coreNodeSetDisplayTexts";

	private LinkNodeType activeType = LinkNodeType.TRIGGER_SOURCE;
	private ChunkActivatorConfigSnapshot triggerSourceConfig = new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD);
	private ChunkActivatorConfigSnapshot coreConfig = new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD);
	private List<String> triggerSourceNodeSetDisplayTexts = List.of();
	private List<String> coreNodeSetDisplayTexts = List.of();
	private String displayAlias = "";
	private boolean active;
	private final ChunkActivatorLifecycleState lifecycleState = new ChunkActivatorLifecycleState();

	public LinkChunkActivatorBlockEntity(BlockPos blockPos, BlockState blockState) {
		super(com.makomi.registry.ModBlockEntities.LINK_CHUNK_ACTIVATOR, blockPos, blockState);
	}

	/**
	 * 返回当前配置快照。
	 */
	public final ChunkActivatorConfigStateSnapshot snapshot() {
		return new ChunkActivatorConfigStateSnapshot(activeType, triggerSourceConfig, coreConfig);
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
	public final void applySnapshot(ChunkActivatorConfigStateSnapshot configSnapshot) {
		applyEditorState(displayAlias, configSnapshot);
	}

	/**
	 * 同时应用别名与区块激活器配置，并同步当前真值。
	 */
	public final void applyEditorState(String rawDisplayAlias, ChunkActivatorConfigStateSnapshot configSnapshot) {
		ChunkActivatorConfigStateSnapshot normalized = configSnapshot == null
			? new ChunkActivatorConfigStateSnapshot(LinkNodeType.TRIGGER_SOURCE, null, null)
			: configSnapshot;
		displayAlias = NodeAliasDisplayUtil.normalizeAlias(rawDisplayAlias);
		activeType = ChunkActivatorConfigStateSnapshot.normalizeType(normalized.activeType());
		triggerSourceConfig = normalized.triggerSourceConfig();
		coreConfig = normalized.coreConfig();
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
	 * @return 当前生效作用类型
	 */
	public final LinkNodeType activeType() {
		return activeType;
	}

	/**
	 * @return 指定作用类型的配置快照
	 */
	public final ChunkActivatorConfigSnapshot configFor(LinkNodeType type) {
		return snapshot().configFor(type);
	}

	/**
	 * @return 当前生效配置快照
	 */
	public final ChunkActivatorConfigSnapshot activeConfig() {
		return snapshot().activeConfig();
	}

	/**
	 * @return 当前生效节点集的客户端展示文本快照
	 */
	public final List<String> activeNodeSetDisplayTexts() {
		return nodeSetDisplayTextsFor(activeType);
	}

	/**
	 * 按节点类型读取客户端展示文本快照。
	 */
	public final List<String> nodeSetDisplayTextsFor(LinkNodeType type) {
		return ChunkActivatorConfigStateSnapshot.normalizeType(type) == LinkNodeType.CORE
			? coreNodeSetDisplayTexts
			: triggerSourceNodeSetDisplayTexts;
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
		ChunkActivatorConfigSnapshot legacyConfig = new ChunkActivatorConfigSnapshot(
			tag.contains(KEY_LEGACY_SERIAL_EXPRESSION, Tag.TAG_STRING) ? tag.getString(KEY_LEGACY_SERIAL_EXPRESSION) : "",
			ChunkActivatorMode.tryParseToken(tag.getString(KEY_LEGACY_MODE)).orElse(ChunkActivatorMode.FORCE_LOAD)
		);
		activeType =
			ChunkActivatorConfigStateSnapshot
				.tryParseTypeToken(tag.contains(KEY_ACTIVE_TYPE, Tag.TAG_STRING) ? tag.getString(KEY_ACTIVE_TYPE) : "")
				.orElse(LinkNodeType.TRIGGER_SOURCE);
		triggerSourceConfig = new ChunkActivatorConfigSnapshot(
			tag.contains(KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION, Tag.TAG_STRING)
				? tag.getString(KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION)
				: legacyConfig.serialExpression(),
			ChunkActivatorMode
				.tryParseToken(
					tag.contains(KEY_TRIGGER_SOURCE_MODE, Tag.TAG_STRING)
						? tag.getString(KEY_TRIGGER_SOURCE_MODE)
						: legacyConfig.mode().token()
				)
				.orElse(legacyConfig.mode())
		);
		coreConfig = new ChunkActivatorConfigSnapshot(
			tag.contains(KEY_CORE_SERIAL_EXPRESSION, Tag.TAG_STRING) ? tag.getString(KEY_CORE_SERIAL_EXPRESSION) : "",
			ChunkActivatorMode
				.tryParseToken(
					tag.contains(KEY_CORE_MODE, Tag.TAG_STRING)
						? tag.getString(KEY_CORE_MODE)
						: ChunkActivatorMode.FORCE_LOAD.token()
				)
				.orElse(ChunkActivatorMode.FORCE_LOAD)
		);
		displayAlias = tag.contains(KEY_DISPLAY_ALIAS, Tag.TAG_STRING)
			? NodeAliasDisplayUtil.normalizeAlias(tag.getString(KEY_DISPLAY_ALIAS))
			: "";
		active = tag.getBoolean(KEY_ACTIVE);
		triggerSourceNodeSetDisplayTexts = readDisplayTexts(
			tag,
			KEY_TRIGGER_SOURCE_NODE_SET_DISPLAY_TEXTS,
			parseOrderedSerials(triggerSourceConfig.serialExpression())
		);
		coreNodeSetDisplayTexts = readDisplayTexts(
			tag,
			KEY_CORE_NODE_SET_DISPLAY_TEXTS,
			parseOrderedSerials(coreConfig.serialExpression())
		);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		tag.putString(KEY_ACTIVE_TYPE, ChunkActivatorConfigStateSnapshot.toTypeToken(activeType));
		tag.putString(KEY_TRIGGER_SOURCE_SERIAL_EXPRESSION, triggerSourceConfig.serialExpression());
		tag.putString(KEY_TRIGGER_SOURCE_MODE, triggerSourceConfig.mode().token());
		tag.putString(KEY_CORE_SERIAL_EXPRESSION, coreConfig.serialExpression());
		tag.putString(KEY_CORE_MODE, coreConfig.mode().token());
		if (!displayAlias.isBlank()) {
			tag.putString(KEY_DISPLAY_ALIAS, displayAlias);
		}
		if (active) {
			tag.putBoolean(KEY_ACTIVE, true);
		}
		tag.remove(KEY_LEGACY_SERIAL_EXPRESSION);
		tag.remove(KEY_LEGACY_MODE);
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
		CompoundTag tag = saveWithoutMetadata(provider);
		writeDisplayTexts(
			tag,
			KEY_TRIGGER_SOURCE_NODE_SET_DISPLAY_TEXTS,
			resolveNodeSetDisplayTextsForSync(LinkNodeType.TRIGGER_SOURCE)
		);
		writeDisplayTexts(tag, KEY_CORE_NODE_SET_DISPLAY_TEXTS, resolveNodeSetDisplayTextsForSync(LinkNodeType.CORE));
		return tag;
	}

	/**
	 * 强制刷新客户端外显同步包。
	 */
	public final void forceSyncToClient() {
		syncToClient();
	}

	/**
	 * 通知客户端刷新方块实体与方块状态。
	 */
	protected final void syncToClient() {
		setChanged();
		if (level != null && !level.isClientSide()) {
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
		PlacedChunkActivatorSavedData savedData = PlacedChunkActivatorSavedData.get(serverLevel);
		Optional<PlacedChunkActivatorSavedData.ActivatorEntry> previousEntry = savedData.findEntry(serverLevel.dimension(), worldPosition);
		boolean changed = savedData.upsert(serverLevel.dimension(), worldPosition, snapshot(), displayAlias, active);
		if (!changed) {
			return;
		}
		ChunkActivatorImmediateEffectService.applyAfterUpsert(
			serverLevel,
			previousEntry.orElse(null),
			savedData.findEntry(serverLevel.dimension(), worldPosition).orElse(null)
		);
	}

	private void removePlacedActivatorState() {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		PlacedChunkActivatorSavedData.get(serverLevel).remove(serverLevel.dimension(), worldPosition);
	}

	private List<String> resolveNodeSetDisplayTextsForSync(LinkNodeType type) {
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(type);
		List<Long> orderedSerials = parseOrderedSerials(configFor(normalizedType).serialExpression());
		if (orderedSerials.isEmpty()) {
			return List.of();
		}
		if (!(level instanceof ServerLevel serverLevel)) {
			return NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, nodeSetDisplayTextsFor(normalizedType));
		}
		List<String> displayTexts = new ArrayList<>(orderedSerials.size());
		for (long serial : orderedSerials) {
			displayTexts.add(NodeAliasServerSupport.resolveDisplayText(serverLevel, normalizedType, serial));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(orderedSerials, displayTexts);
	}

	private static List<Long> parseOrderedSerials(String rawExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			rawExpression,
			PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE
		);
		if (parseResult.orderedTargets().isEmpty()) {
			return List.of();
		}
		return List.copyOf(parseResult.orderedTargets());
	}

	private static void writeDisplayTexts(CompoundTag tag, String key, List<String> displayTexts) {
		if (tag == null || key == null || key.isBlank()) {
			return;
		}
		if (displayTexts == null || displayTexts.isEmpty()) {
			tag.remove(key);
			return;
		}
		ListTag listTag = new ListTag();
		for (String displayText : displayTexts) {
			String normalizedText = NodeAliasDisplayUtil.normalizeAlias(displayText);
			if (!normalizedText.isEmpty()) {
				listTag.add(StringTag.valueOf(normalizedText));
			}
		}
		if (listTag.isEmpty()) {
			tag.remove(key);
			return;
		}
		tag.put(key, listTag);
	}

	private static List<String> readDisplayTexts(CompoundTag tag, String key, List<Long> serials) {
		if (serials == null || serials.isEmpty()) {
			return List.of();
		}
		if (tag == null || key == null || key.isBlank() || !tag.contains(key, Tag.TAG_LIST)) {
			return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, List.of());
		}
		ListTag listTag = tag.getList(key, Tag.TAG_STRING);
		List<String> displayTexts = new ArrayList<>(listTag.size());
		for (int index = 0; index < listTag.size(); index++) {
			displayTexts.add(listTag.getString(index));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, displayTexts);
	}
}
