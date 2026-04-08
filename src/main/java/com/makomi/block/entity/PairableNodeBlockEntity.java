package com.makomi.block.entity;

import com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta;
import com.makomi.data.InternalDispatchDeltaEvents;
import com.makomi.data.LinkNodeLifecycleDispatchEvents;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkRetireCoordinator;
import com.makomi.data.LinkSavedData;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 可配对节点方块实体基类。
 * <p>
 * 统一维护节点序列号与“上线/下线到 LinkSavedData”的同步逻辑。
 * 所有可进入红石联动图的方块实体都应继承该类。
 * </p>
 */
public abstract class PairableNodeBlockEntity extends BlockEntity {
	protected static final String KEY_SERIAL = "Serial";

	private long serial;
	private long cachedDisplaySerial = Long.MIN_VALUE;
	private String cachedDisplayText = "";
	private final PairableNodeLifecycleState lifecycleState = new PairableNodeLifecycleState();

	protected PairableNodeBlockEntity(
		BlockEntityType<? extends PairableNodeBlockEntity> blockEntityType,
		BlockPos blockPos,
		BlockState blockState
	) {
		super(blockEntityType, blockPos, blockState);
	}

	public long getSerial() {
		return serial;
	}

	/**
	 * 获取用于客户端外显的序号文本（十进制分组格式）。
	 */
	public String getSerialDisplayText() {
		long currentSerial = serial;
		if (cachedDisplaySerial == currentSerial) {
			return cachedDisplayText;
		}
		cachedDisplaySerial = currentSerial;
		cachedDisplayText = formatSerialDisplayText(currentSerial);
		return cachedDisplayText;
	}

	/**
	 * 对外暴露节点类型，供命令与运维路径复用统一类型判断。
	 */
	public final LinkNodeType getLinkNodeType() {
		return getNodeType();
	}

	/**
	 * 设置节点序列号并刷新在线注册状态。
	 * <p>
	 * 若序列号发生变化，会先注销旧节点再注册新节点，避免同一方块实体残留旧映射。
	 * </p>
	 */
	public void setLinkData(long serial) {
		if (this.serial > 0L && this.serial != serial) {
			unregisterNode();
			lifecycleState.onNodeIdentityChangedWhileAttached();
		}

		this.serial = serial;
		registerNode();
		flushPendingContextAttachIfReady();
		syncToClient();
	}

	public void unregisterNode() {
		unregisterNode(false);
	}

	/**
	 * 强制同步当前节点数据到客户端。
	 */
	public final void forceSyncToClient() {
		syncToClient();
	}

	/**
	 * 从在线节点表移除当前节点，并可选登记“待确认退役”。
	 * <p>
	 * 当节点由方块移除触发下线时，建议传入 {@code true}：
	 * 若后续未发现对应掉落物实体，将由自动清理流程补做退役。
	 * </p>
	 */
	public void unregisterNode(boolean enqueuePendingRetire) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (serial <= 0L) {
			return;
		}
		LinkSavedData savedData = LinkSavedData.get(serverLevel);
		if (LinkNodeSemantics.isAllowedForRole(getNodeType(), LinkNodeSemantics.Role.SOURCE)) {
			Set<Long> linkedPeers = new HashSet<>(savedData.getLinkedPeersByNodeType(getNodeType(), serial));
			if (!linkedPeers.isEmpty()) {
				InternalDispatchDeltaEvents.publishTriggerSourceInvalidation(
					serverLevel,
					getNodeType(),
					serial,
					linkedPeers,
					EventMeta.of(serverLevel.getGameTime(), 0, 0L)
				);
			}
		}
		if (enqueuePendingRetire) {
			LinkNodeRetireEvents.enqueuePendingRetire(serverLevel, getNodeType(), serial, worldPosition);
		}
		savedData.removeNode(getNodeType(), serial);
	}

	public void retireNode() {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (serial <= 0L) {
			return;
		}
		LinkRetireCoordinator.retireAndSyncWhitelist(serverLevel, getNodeType(), serial);
	}

	protected abstract LinkNodeType getNodeType();

	/**
	 * 标记当前实例正在进入真实物理移除路径。
	 * <p>
	 * 该入口应由 block 的 `onRemove(...)` 提前调用，
	 * 用于让后续 `setRemoved()` 不再把这次移除误判为普通区块/上下文脱附。
	 * </p>
	 */
	public final void markPhysicalRemovalInProgress() {
		lifecycleState.onPhysicalRemovalStarted();
	}

	/**
	 * 将当前节点写入在线节点表。
	 */
	protected void registerNode() {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (serial <= 0L) {
			return;
		}
		LinkSavedData.get(serverLevel).registerNode(serial, serverLevel.dimension(), worldPosition, getNodeType());
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		if (tag.contains(KEY_SERIAL, Tag.TAG_LONG)) {
			serial = tag.getLong(KEY_SERIAL);
		}
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		lifecycleState.onContextAttached();
		flushPendingContextAttachIfReady();
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (serial > 0L) {
			tag.putLong(KEY_SERIAL, serial);
		}
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
	 * 通知客户端刷新方块实体数据。
	 */
	protected void syncToClient() {
		setChanged();
		if (level != null && !level.isClientSide) {
			BlockState state = getBlockState();
			level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		}
	}

	@Override
	public void setRemoved() {
		boolean shouldPublishDetach = lifecycleState.onContextDetached(isLifecycleEventPublishReady());
		super.setRemoved();
		if (!shouldPublishDetach || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		LinkNodeLifecycleDispatchEvents.publishNodeContextDetached(
			serverLevel,
			getNodeType(),
			serial,
			worldPosition.immutable()
		);
	}

	/**
	 * 在服务端上下文和 serial 都已就绪时，冲刷一次待发布 attach。
	 * <p>
	 * 这样可同时覆盖两条路径：
	 * </p>
	 * <ul>
	 * <li>读档恢复：`serial` 已先从 NBT 读入，`clearRemoved()` 后即可立即发布；</li>
	 * <li>新放置：`clearRemoved()` 先挂起，等 `setLinkData(...)` 再发布。</li>
	 * </ul>
	 */
	protected final void flushPendingContextAttachIfReady() {
		if (!lifecycleState.tryMarkAttachPublished(isLifecycleEventPublishReady())) {
			return;
		}
		if (!(level instanceof ServerLevel)) {
			return;
		}
		LinkNodeLifecycleDispatchEvents.publishNodeContextAttached(this);
	}

	/**
	 * 当前实例是否满足发布生命周期事件的最小条件。
	 */
	private boolean isLifecycleEventPublishReady() {
		return level instanceof ServerLevel && serial > 0L && !lifecycleState.physicalRemovalInProgress();
	}

	/**
	 * 将序号转为十进制分组文本，便于快速阅读。
	 */
	private static String formatSerialDisplayText(long serial) {
		if (serial <= 0L) {
			return "";
		}
		return String.format(Locale.ROOT, "%,d", serial);
	}
}
