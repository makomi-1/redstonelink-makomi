package com.makomi.block.entity;

import com.makomi.block.LinkRepeaterBlock;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.data.LinkedTargetDispatchService;
import com.makomi.data.NodeAliasDisplayUtil;
import com.makomi.data.NodeAliasServerSupport;
import com.makomi.data.RepeaterConfigSnapshot;
import com.makomi.data.RepeaterDelay;
import com.makomi.data.RepeaterGraphSnapshotSupport;
import com.makomi.util.NeighborFanoutUtil;
import com.makomi.util.SerialParseUtil;
import com.makomi.util.SignalStrengths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 转发器方块实体。
 * <p>
 * 物理上只放置一个方块实体，但逻辑上同时承载：
 * </p>
 * <ul>
 * <li>`core` 身份：接收上游 `triggerSource` 输入并完成聚合；</li>
 * <li>`triggerSource` 身份：将延迟后的当前真值继续派发给下游 `core`。</li>
 * </ul>
 */
public class LinkRepeaterBlockEntity extends ActivatableTargetBlockEntity {
	private static final String KEY_INPUT_SERIAL_EXPRESSION = "inputSerialExpression";
	private static final String KEY_OUTPUT_SERIAL_EXPRESSION = "outputSerialExpression";
	private static final String KEY_DELAY = "delay";
	private static final String KEY_INPUT_DISPLAY_TEXTS = "inputDisplayTexts";
	private static final String KEY_OUTPUT_DISPLAY_TEXTS = "outputDisplayTexts";
	private static final String KEY_DISPATCHED_OUTPUT_POWER = "dispatchedOutputPower";
	private static final String KEY_LAST_DISPATCH_TICK = "lastDispatchTick";
	private static final String KEY_LAST_DISPATCH_SLOT = "lastDispatchSlot";
	private static final String KEY_LAST_DISPATCH_SEQ = "lastDispatchSeq";
	private static final String KEY_PENDING_DISPATCH_ARMED = "pendingDispatchArmed";
	private static final String KEY_PENDING_DISPATCH_TICK = "pendingDispatchTick";
	private static final String KEY_PENDING_DISPATCH_POWER = "pendingDispatchPower";
	private static final String KEY_NEXT_DISPATCH_SEQ = "nextDispatchSeq";

	private RepeaterConfigSnapshot configSnapshot = RepeaterConfigSnapshot.empty();
	private List<String> inputDisplayTexts = List.of();
	private List<String> outputDisplayTexts = List.of();
	private int dispatchedOutputPower;
	private long lastDispatchTick;
	private int lastDispatchSlot;
	private long lastDispatchSeq;
	private boolean pendingDispatchArmed;
	private long pendingDispatchTick;
	private int pendingDispatchPower;
	private long nextDispatchSeq = 1L;

	public LinkRepeaterBlockEntity(BlockPos blockPos, BlockState blockState) {
		this(com.makomi.registry.ModBlockEntities.LINK_REPEATER, blockPos, blockState);
	}

	protected LinkRepeaterBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	@Override
	protected LinkNodeType getNodeType() {
		return LinkNodeType.CORE;
	}

	@Override
	public boolean matchesNodeIdentity(LinkNodeType nodeType, long serial) {
		if (nodeType == LinkNodeType.TRIGGER_SOURCE && serial > 0L && getSerial() == serial) {
			return true;
		}
		return super.matchesNodeIdentity(nodeType, serial);
	}

	@Override
	public void forEachNodeIdentity(BiConsumer<LinkNodeType, Long> consumer) {
		if (consumer == null) {
			return;
		}
		long serial = getSerial();
		if (serial <= 0L) {
			return;
		}
		consumer.accept(LinkNodeType.CORE, serial);
		consumer.accept(LinkNodeType.TRIGGER_SOURCE, serial);
	}

	/**
	 * @return 当前转发器配置快照
	 */
	public RepeaterConfigSnapshot snapshot() {
		if (level instanceof ServerLevel serverLevel && getSerial() > 0L) {
			return RepeaterGraphSnapshotSupport.resolve(serverLevel, getSerial(), configSnapshot);
		}
		return configSnapshot;
	}

	/**
	 * @return 输入侧节点集展示文本
	 */
	public List<String> inputDisplayTexts() {
		return inputDisplayTexts;
	}

	/**
	 * @return 输出侧节点集展示文本
	 */
	public List<String> outputDisplayTexts() {
		return outputDisplayTexts;
	}

	/**
	 * @return 输入侧当前解析功率
	 */
	public int getCurrentInputPower() {
		return getResolvedOutputPower();
	}

	/**
	 * @return 当前已派发输出功率
	 */
	public int getCurrentDispatchedOutputPower() {
		return dispatchedOutputPower;
	}

	/**
	 * 返回当前虚拟 `triggerSource` 对外可见的 replay 快照。
	 */
	public java.util.Optional<SyncReplaySourceBlockEntity.ReplaySyncSnapshot> replaySyncSnapshot() {
		if (lastDispatchTick <= 0L && lastDispatchSeq <= 0L && dispatchedOutputPower <= 0) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(
			new SyncReplaySourceBlockEntity.ReplaySyncSnapshot(
				dispatchedOutputPower,
				EventMeta.of(lastDispatchTick, lastDispatchSlot, lastDispatchSeq)
			)
		);
	}

	/**
	 * 同时应用编辑配置与展示缓存。
	 */
	public void applyEditorState(RepeaterConfigSnapshot snapshot) {
		RepeaterConfigSnapshot fallbackSnapshot = snapshot == null ? RepeaterConfigSnapshot.empty() : snapshot;
		if (level instanceof ServerLevel serverLevel && getSerial() > 0L) {
			this.configSnapshot = RepeaterGraphSnapshotSupport.resolve(serverLevel, getSerial(), fallbackSnapshot);
		} else {
			this.configSnapshot = fallbackSnapshot;
		}
		refreshDisplayTextsFromServer();
		syncToClient();
	}

	/**
	 * 标记转发器正进入真实物理移除路径。
	 */
	public void markRepeaterPhysicalRemovalInProgress() {
		markPhysicalRemovalInProgress();
	}

	/**
	 * 处理延迟到点派发。
	 */
	public void onDelayTick() {
		if (!(level instanceof ServerLevel serverLevel) || !pendingDispatchArmed) {
			return;
		}
		if (serverLevel.getGameTime() < pendingDispatchTick) {
			return;
		}
		pendingDispatchArmed = false;
		applyDispatchedOutputPower(pendingDispatchPower);
	}

	@Override
	public void setLinkData(long serial) {
		super.setLinkData(serial);
		persistCurrentReplaySnapshotIfNeeded();
	}

	@Override
	protected void registerNode() {
		super.registerNode();
		if (!(level instanceof ServerLevel serverLevel) || getSerial() <= 0L) {
			return;
		}
		LinkSavedData.get(serverLevel).registerNode(getSerial(), serverLevel.dimension(), worldPosition, LinkNodeType.TRIGGER_SOURCE);
	}

	@Override
	public void unregisterNode(boolean enqueuePendingRetire) {
		unregisterVirtualTriggerSource(enqueuePendingRetire);
		super.unregisterNode(enqueuePendingRetire);
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		syncRepeaterActiveBlockState(dispatchedOutputPower > 0);
		if (level instanceof ServerLevel serverLevel && pendingDispatchArmed) {
			int delayTicks = Math.max(1, (int) Math.max(0L, pendingDispatchTick - serverLevel.getGameTime()));
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), delayTicks);
		}
		persistCurrentReplaySnapshotIfNeeded();
	}

	@Override
	protected void onActiveChanged(boolean active) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		armDelayedDispatch(serverLevel, getResolvedOutputPower());
	}

	@Override
	protected void syncBlockStateFromDerivedState(boolean active) {
		// 转发器外显由“已派发输出态”驱动，而不是输入侧即时真值。
	}

	@Override
	protected boolean shouldQueueLoadBlockStateSync(boolean active) {
		return false;
	}

	@Override
	protected void schedulePulseReset(int pulseTicks) {
		if (level instanceof ServerLevel serverLevel) {
			serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), Math.max(1, pulseTicks));
		}
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		configSnapshot = new RepeaterConfigSnapshot(
			tag.contains(KEY_INPUT_SERIAL_EXPRESSION, Tag.TAG_STRING) ? tag.getString(KEY_INPUT_SERIAL_EXPRESSION) : "",
			tag.contains(KEY_OUTPUT_SERIAL_EXPRESSION, Tag.TAG_STRING) ? tag.getString(KEY_OUTPUT_SERIAL_EXPRESSION) : "",
			RepeaterDelay.fromToken(tag.contains(KEY_DELAY, Tag.TAG_STRING) ? tag.getString(KEY_DELAY) : "")
		);
		inputDisplayTexts = readDisplayTexts(
			tag,
			KEY_INPUT_DISPLAY_TEXTS,
			parseOrderedSerials(configSnapshot.inputSerialExpression())
		);
		outputDisplayTexts = readDisplayTexts(
			tag,
			KEY_OUTPUT_DISPLAY_TEXTS,
			parseOrderedSerials(configSnapshot.outputSerialExpression())
		);
		dispatchedOutputPower = SignalStrengths.clamp(tag.getInt(KEY_DISPATCHED_OUTPUT_POWER));
		lastDispatchTick = Math.max(0L, tag.getLong(KEY_LAST_DISPATCH_TICK));
		lastDispatchSlot = Math.max(0, tag.getInt(KEY_LAST_DISPATCH_SLOT));
		lastDispatchSeq = Math.max(0L, tag.getLong(KEY_LAST_DISPATCH_SEQ));
		pendingDispatchArmed = tag.getBoolean(KEY_PENDING_DISPATCH_ARMED);
		pendingDispatchTick = Math.max(0L, tag.getLong(KEY_PENDING_DISPATCH_TICK));
		pendingDispatchPower = SignalStrengths.clamp(tag.getInt(KEY_PENDING_DISPATCH_POWER));
		nextDispatchSeq = Math.max(1L, tag.getLong(KEY_NEXT_DISPATCH_SEQ));
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (!configSnapshot.inputSerialExpression().isBlank()) {
			tag.putString(KEY_INPUT_SERIAL_EXPRESSION, configSnapshot.inputSerialExpression());
		}
		if (!configSnapshot.outputSerialExpression().isBlank()) {
			tag.putString(KEY_OUTPUT_SERIAL_EXPRESSION, configSnapshot.outputSerialExpression());
		}
		tag.putString(KEY_DELAY, configSnapshot.delay().token());
		writeDisplayTexts(tag, KEY_INPUT_DISPLAY_TEXTS, inputDisplayTexts);
		writeDisplayTexts(tag, KEY_OUTPUT_DISPLAY_TEXTS, outputDisplayTexts);
		if (dispatchedOutputPower > 0) {
			tag.putInt(KEY_DISPATCHED_OUTPUT_POWER, dispatchedOutputPower);
		}
		if (lastDispatchTick > 0L) {
			tag.putLong(KEY_LAST_DISPATCH_TICK, lastDispatchTick);
		}
		if (lastDispatchSlot > 0) {
			tag.putInt(KEY_LAST_DISPATCH_SLOT, lastDispatchSlot);
		}
		if (lastDispatchSeq > 0L) {
			tag.putLong(KEY_LAST_DISPATCH_SEQ, lastDispatchSeq);
		}
		if (pendingDispatchArmed) {
			tag.putBoolean(KEY_PENDING_DISPATCH_ARMED, true);
			tag.putLong(KEY_PENDING_DISPATCH_TICK, pendingDispatchTick);
			tag.putInt(KEY_PENDING_DISPATCH_POWER, pendingDispatchPower);
		}
		tag.putLong(KEY_NEXT_DISPATCH_SEQ, nextDispatchSeq);
	}

	private void armDelayedDispatch(ServerLevel serverLevel, int outputPower) {
		pendingDispatchArmed = true;
		pendingDispatchTick = Math.max(0L, serverLevel.getGameTime()) + configSnapshot.delay().delayTicks();
		pendingDispatchPower = SignalStrengths.clamp(outputPower);
		setChanged();
		serverLevel.scheduleTick(worldPosition, getBlockState().getBlock(), configSnapshot.delay().delayTicks());
	}

	private void applyDispatchedOutputPower(int outputPower) {
		if (!(level instanceof ServerLevel serverLevel) || getSerial() <= 0L) {
			return;
		}
		int normalizedPower = SignalStrengths.clamp(outputPower);
		SyncReplaySourceBlockEntity.ReplaySyncSnapshot previousSnapshot = replaySyncSnapshot().orElse(null);
		boolean changed = dispatchedOutputPower != normalizedPower;
		dispatchedOutputPower = normalizedPower;
		lastDispatchTick = Math.max(0L, serverLevel.getGameTime());
		lastDispatchSlot = 0;
		lastDispatchSeq = nextDispatchSeq++;
		LinkSavedData.get(serverLevel).putTriggerSourceReplaySyncSnapshot(
			getSerial(),
			EventMeta.of(lastDispatchTick, lastDispatchSlot, lastDispatchSeq),
			dispatchedOutputPower
		);
		if (changed) {
			BlockState state = syncRepeaterActiveBlockState(dispatchedOutputPower > 0);
			NeighborFanoutUtil.notifyCenterAndSixNeighbors(
				level,
				worldPosition,
				state.getBlock(),
				lastDispatchTick,
				lastDispatchSlot,
				dispatchedOutputPower > 0,
				dispatchedOutputPower
			);
			syncToClient();
		} else {
			setChanged();
		}
		LinkedTargetDispatchService.dispatchSyncSignal(
			serverLevel,
			LinkNodeType.TRIGGER_SOURCE,
			getSerial(),
			worldPosition,
			LinkNodeType.CORE,
			dispatchedOutputPower,
			previousSnapshot,
			EventMeta.of(lastDispatchTick, lastDispatchSlot, lastDispatchSeq)
		);
	}

	private void persistCurrentReplaySnapshotIfNeeded() {
		if (!(level instanceof ServerLevel serverLevel) || getSerial() <= 0L) {
			return;
		}
		LinkSavedData.get(serverLevel).putTriggerSourceReplaySyncSnapshot(
			getSerial(),
			EventMeta.of(lastDispatchTick, lastDispatchSlot, lastDispatchSeq),
			dispatchedOutputPower
		);
	}

	private void unregisterVirtualTriggerSource(boolean enqueuePendingRetire) {
		if (!(level instanceof ServerLevel serverLevel) || getSerial() <= 0L) {
			return;
		}
		long serial = getSerial();
		LinkSavedData savedData = LinkSavedData.get(serverLevel);
		java.util.Set<Long> linkedPeers = new java.util.HashSet<>(savedData.getLinkedPeersByNodeType(LinkNodeType.TRIGGER_SOURCE, serial));
		if (!linkedPeers.isEmpty()) {
			com.makomi.data.InternalDispatchDeltaEvents.publishTriggerSourceInvalidation(
				serverLevel,
				LinkNodeType.TRIGGER_SOURCE,
				serial,
				linkedPeers,
				EventMeta.of(serverLevel.getGameTime(), 0, 0L)
			);
		}
		if (enqueuePendingRetire) {
			LinkNodeRetireEvents.enqueuePendingRetire(serverLevel, LinkNodeType.TRIGGER_SOURCE, serial, worldPosition);
		}
		savedData.removeNode(LinkNodeType.TRIGGER_SOURCE, serial);
	}

	private void refreshDisplayTextsFromServer() {
		if (!(level instanceof ServerLevel serverLevel)) {
			inputDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(
				parseOrderedSerials(configSnapshot.inputSerialExpression()),
				inputDisplayTexts
			);
			outputDisplayTexts = NodeAliasDisplayUtil.normalizeDisplayTexts(
				parseOrderedSerials(configSnapshot.outputSerialExpression()),
				outputDisplayTexts
			);
			return;
		}
		inputDisplayTexts = resolveDisplayTexts(
			serverLevel,
			LinkNodeType.TRIGGER_SOURCE,
			parseOrderedSerials(configSnapshot.inputSerialExpression())
		);
		outputDisplayTexts = resolveDisplayTexts(
			serverLevel,
			LinkNodeType.CORE,
			parseOrderedSerials(configSnapshot.outputSerialExpression())
		);
	}

	private BlockState syncRepeaterActiveBlockState(boolean active) {
		if (level == null) {
			return getBlockState();
		}
		BlockState state = level.getBlockState(worldPosition);
		if (!(state.getBlock() instanceof LinkRepeaterBlock) || state.getValue(LinkRepeaterBlock.ACTIVE) == active) {
			return state;
		}
		BlockState updatedState = state.setValue(LinkRepeaterBlock.ACTIVE, active);
		level.setBlock(worldPosition, updatedState, Block.UPDATE_CLIENTS);
		return updatedState;
	}

	private static List<Long> parseOrderedSerials(String rawExpression) {
		return List.copyOf(SerialParseUtil.parseTargetsOrdered(rawExpression, 0).orderedTargets());
	}

	private static List<String> resolveDisplayTexts(ServerLevel level, LinkNodeType type, List<Long> serials) {
		if (level == null || type == null || serials == null || serials.isEmpty()) {
			return List.of();
		}
		List<String> displayTexts = new ArrayList<>(serials.size());
		for (long serial : serials) {
			displayTexts.add(NodeAliasServerSupport.resolveDisplayText(level, type, serial));
		}
		return NodeAliasDisplayUtil.normalizeDisplayTexts(serials, displayTexts);
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
			String normalized = NodeAliasDisplayUtil.normalizeAlias(displayText);
			if (!normalized.isEmpty()) {
				listTag.add(StringTag.valueOf(normalized));
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
