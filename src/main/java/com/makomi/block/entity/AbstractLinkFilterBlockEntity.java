package com.makomi.block.entity;

import com.makomi.data.LinkDispatchFilterService;
import com.makomi.data.LinkFilterConfigSnapshot;
import com.makomi.data.LinkFilterKind;
import com.makomi.data.LinkFilterNodeSetMode;
import com.makomi.data.LinkFilterSignalMode;
import com.makomi.data.LinkFilterSignalThresholdSource;
import com.makomi.data.LinkFilterTargetMode;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.util.SerialParseUtil;
import com.makomi.util.SignalStrengths;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 发送/接收过滤器公共方块实体基类。
 * <p>
 * 只负责配置持久化、客户端同步与已放置过滤器真值更新；
 * 过滤规则求值由 `LinkDispatchFilterService` 与 `PlacedLinkFilterSavedData` 统一处理。
 * </p>
 */
public abstract class AbstractLinkFilterBlockEntity extends BlockEntity {
	private static final String KEY_SERIAL_EXPRESSION = "serialExpression";
	private static final String KEY_TARGET_MODE = "targetMode";
	private static final String KEY_CHANNEL = "channel";
	private static final String KEY_NODE_SET_MODE = "nodeSetMode";
	private static final String KEY_SIGNAL_THRESHOLD_SOURCE = "signalThresholdSource";
	private static final String KEY_FIXED_SIGNAL_THRESHOLD = "fixedSignalThreshold";
	private static final String KEY_SIGNAL_MODE = "signalMode";
	private static final String KEY_DISPLAY_ALIAS = "DisplayAlias";

	private String serialExpression = "";
	private LinkFilterTargetMode targetMode = LinkFilterTargetMode.SERIAL;
	private long channel;
	private Set<Long> serials = Set.of();
	private LinkFilterNodeSetMode nodeSetMode = LinkFilterNodeSetMode.DISABLED;
	private LinkFilterSignalThresholdSource signalThresholdSource = LinkFilterSignalThresholdSource.FIXED_INPUT;
	private int fixedSignalThreshold = 15;
	private LinkFilterSignalMode signalMode = LinkFilterSignalMode.DISABLED;
	private String displayAlias = "";
	private final LinkFilterLifecycleState lifecycleState = new LinkFilterLifecycleState();

	protected AbstractLinkFilterBlockEntity(
		BlockEntityType<? extends AbstractLinkFilterBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	/**
	 * @return 当前过滤器种类
	 */
	public abstract LinkFilterKind filterKind();

	/**
	 * 返回当前配置快照。
	 */
	public final LinkFilterConfigSnapshot snapshot() {
		return new LinkFilterConfigSnapshot(
			serialExpression,
			targetMode,
			channel,
			nodeSetMode,
			signalThresholdSource,
			fixedSignalThreshold,
			signalMode
		);
	}

	/**
	 * @return 当前缓存的过滤器别名
	 */
	public final String displayAlias() {
		return displayAlias;
	}

	/**
	 * 应用一份新的配置快照，并同步客户端与运行时索引。
	 *
	 * @param configSnapshot 新配置快照
	 */
	public final void applySnapshot(LinkFilterConfigSnapshot configSnapshot) {
		applyEditorState(displayAlias, configSnapshot);
	}

	/**
	 * 同时应用过滤器别名与规则配置，并同步客户端与运行时索引。
	 */
	public final void applyEditorState(String rawDisplayAlias, LinkFilterConfigSnapshot configSnapshot) {
		LinkFilterConfigSnapshot normalized = configSnapshot == null
			? new LinkFilterConfigSnapshot("", LinkFilterTargetMode.SERIAL, 0L, null, null, 15, null)
			: configSnapshot;
		displayAlias = NodeAliasDisplayUtil.normalizeAlias(rawDisplayAlias);
		serialExpression = normalized.serialExpression().trim();
		targetMode = normalized.targetMode();
		channel = normalized.channel();
		nodeSetMode = normalized.nodeSetMode();
		signalThresholdSource = normalized.signalThresholdSource();
		fixedSignalThreshold = SignalStrengths.clamp(normalized.fixedSignalThreshold());
		signalMode = normalized.signalMode();
		serials = targetMode == LinkFilterTargetMode.SERIAL ? parseSerialExpression(serialExpression) : Set.of();
		syncToClient();
		LinkDispatchFilterService.refreshFilterWithCurrentNeighborSignal(this);
	}

	/**
	 * 标记当前过滤器正进入真实物理移除路径。
	 */
	public final void markPhysicalRemovalInProgress() {
		lifecycleState.onPhysicalRemovalStarted();
	}

	/**
	 * 在不重采样邻居输入的前提下恢复已放置过滤器真值。
	 */
	public final void restorePlacedFilterState() {
		LinkDispatchFilterService.upsertFilter(this);
	}

	/**
	 * 以当前世界输入重采样结果刷新已放置过滤器真值。
	 */
	public final void refreshPlacedFilterState() {
		LinkDispatchFilterService.refreshFilterWithCurrentNeighborSignal(this);
	}

	/**
	 * @return 当前缓存的序号表达式
	 */
	public final String serialExpression() {
		return serialExpression;
	}

	/**
	 * @return 当前过滤目标模式
	 */
	public final LinkFilterTargetMode targetMode() {
		return targetMode;
	}

	/**
	 * @return 当前频道过滤值；非频道模式返回 0
	 */
	public final long channel() {
		return channel;
	}

	/**
	 * @return 当前节点集合过滤模式
	 */
	public final LinkFilterNodeSetMode nodeSetMode() {
		return nodeSetMode;
	}

	/**
	 * @return 当前信号阈值来源
	 */
	public final LinkFilterSignalThresholdSource signalThresholdSource() {
		return signalThresholdSource;
	}

	/**
	 * @return 当前固定阈值
	 */
	public final int fixedSignalThreshold() {
		return fixedSignalThreshold;
	}

	/**
	 * @return 当前信号判定模式
	 */
	public final LinkFilterSignalMode signalMode() {
		return signalMode;
	}

	/**
	 * 采样过滤器自身邻居最大输入。
	 */
	public final int sampleNeighborSignalStrength() {
		return level == null ? 0 : Math.max(0, level.getBestNeighborSignal(worldPosition));
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		serialExpression = tag.contains(KEY_SERIAL_EXPRESSION, Tag.TAG_STRING) ? tag.getString(KEY_SERIAL_EXPRESSION) : "";
		targetMode = LinkFilterTargetMode.tryParseToken(tag.getString(KEY_TARGET_MODE)).orElseGet(() -> inferTargetMode(tag, serialExpression));
		channel = targetMode == LinkFilterTargetMode.CHANNEL ? Math.max(0L, tag.getLong(KEY_CHANNEL)) : 0L;
		nodeSetMode = LinkFilterNodeSetMode
			.tryParseToken(tag.getString(KEY_NODE_SET_MODE))
			.orElse(LinkFilterNodeSetMode.DISABLED);
		signalThresholdSource = LinkFilterSignalThresholdSource
			.tryParseToken(tag.getString(KEY_SIGNAL_THRESHOLD_SOURCE))
			.orElse(LinkFilterSignalThresholdSource.FIXED_INPUT);
		fixedSignalThreshold = SignalStrengths.clamp(tag.getInt(KEY_FIXED_SIGNAL_THRESHOLD));
		signalMode = LinkFilterSignalMode
			.tryParseToken(tag.getString(KEY_SIGNAL_MODE))
			.orElse(LinkFilterSignalMode.DISABLED);
		displayAlias = tag.contains(KEY_DISPLAY_ALIAS, Tag.TAG_STRING)
			? NodeAliasDisplayUtil.normalizeAlias(tag.getString(KEY_DISPLAY_ALIAS))
			: "";
		serials = targetMode == LinkFilterTargetMode.SERIAL ? parseSerialExpression(serialExpression) : Set.of();
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (!serialExpression.isBlank()) {
			tag.putString(KEY_SERIAL_EXPRESSION, serialExpression);
		}
		tag.putString(KEY_TARGET_MODE, targetMode.token());
		if (channel > 0L) {
			tag.putLong(KEY_CHANNEL, channel);
		}
		tag.putString(KEY_NODE_SET_MODE, nodeSetMode.token());
		tag.putString(KEY_SIGNAL_THRESHOLD_SOURCE, signalThresholdSource.token());
		tag.putInt(KEY_FIXED_SIGNAL_THRESHOLD, SignalStrengths.clamp(fixedSignalThreshold));
		tag.putString(KEY_SIGNAL_MODE, signalMode.token());
		if (!displayAlias.isBlank()) {
			tag.putString(KEY_DISPLAY_ALIAS, displayAlias);
		}
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		lifecycleState.onContextAttached();
		restorePlacedFilterState();
	}

	@Override
	public void setRemoved() {
		if (lifecycleState.onContextDetachedShouldRemovePersistedFilter()) {
			LinkDispatchFilterService.removeFilter(this);
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

	/**
	 * 解析序号表达式，统一过滤非法与重复项。
	 */
	private static Set<Long> parseSerialExpression(String rawExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(rawExpression, 0);
		if (parseResult.orderedTargets().isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(parseResult.orderedTargets()));
	}

	/**
	 * 从旧存档字段推断过滤目标模式；无显式模式字段时保持序号模式兼容。
	 */
	private static LinkFilterTargetMode inferTargetMode(CompoundTag tag, String serialExpression) {
		if ((serialExpression == null || serialExpression.isBlank()) && tag.contains(KEY_CHANNEL, Tag.TAG_LONG) && tag.getLong(KEY_CHANNEL) > 0L) {
			return LinkFilterTargetMode.CHANNEL;
		}
		return LinkFilterTargetMode.SERIAL;
	}
}
