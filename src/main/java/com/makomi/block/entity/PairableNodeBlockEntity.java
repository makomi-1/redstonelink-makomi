package com.makomi.block.entity;

import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkRetireCoordinator;
import com.makomi.data.LinkSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * 统一维护节点序列号、最近目标序列号与“上线/下线到 LinkSavedData”的同步逻辑。
 * 所有可进入红石联动图的方块实体都应继承该类。
 * </p>
 */
public abstract class PairableNodeBlockEntity extends BlockEntity {
	protected static final String KEY_SERIAL = "Serial";
	private static final String KEY_LAST_TARGET_SERIAL = "LastTargetSerial";
	private static final String KEY_LINKED_TARGET_SERIALS = "LinkedTargetSerials";

	private long serial;
	private long lastTargetSerial;
	private long cachedDisplaySerial = Long.MIN_VALUE;
	private String cachedDisplayText = "";
	private List<Long> linkedTargetSerialsSnapshot = List.of();

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

	public long getLastTargetSerial() {
		return lastTargetSerial;
	}

	/**
	 * 获取“当前连接”客户端快照（按升序）。
	 * <p>
	 * 该列表由服务端在方块实体同步包中下发，供客户端近外显与 tooltip 风格展示复用。
	 * </p>
	 */
	public List<Long> getLinkedTargetSerialsSnapshot() {
		return linkedTargetSerialsSnapshot;
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
		}

		this.serial = serial;
		registerNode();
		syncToClient();
	}

	public void setLastTargetSerial(long targetSerial) {
		long normalized = Math.max(0L, targetSerial);
		if (lastTargetSerial == normalized) {
			return;
		}
		lastTargetSerial = normalized;
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
		if (enqueuePendingRetire) {
			LinkNodeRetireEvents.enqueuePendingRetire(serverLevel, getNodeType(), serial, worldPosition);
		}
		LinkSavedData.get(serverLevel).removeNode(getNodeType(), serial);
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
		if (tag.contains(KEY_LAST_TARGET_SERIAL, Tag.TAG_LONG)) {
			lastTargetSerial = Math.max(0L, tag.getLong(KEY_LAST_TARGET_SERIAL));
		}
		if (tag.contains(KEY_LINKED_TARGET_SERIALS, Tag.TAG_LONG_ARRAY)) {
			linkedTargetSerialsSnapshot = normalizeLinkedTargetSnapshot(tag.getLongArray(KEY_LINKED_TARGET_SERIALS));
		} else {
			linkedTargetSerialsSnapshot = List.of();
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (level instanceof ServerLevel) {
			linkedTargetSerialsSnapshot = resolveLinkedTargetSerialsSnapshot();
		}
		if (serial > 0L) {
			tag.putLong(KEY_SERIAL, serial);
		}
		if (lastTargetSerial > 0L) {
			tag.putLong(KEY_LAST_TARGET_SERIAL, lastTargetSerial);
		}
		if (!linkedTargetSerialsSnapshot.isEmpty()) {
			tag.putLongArray(KEY_LINKED_TARGET_SERIALS, linkedTargetSerialsSnapshot);
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

	/**
	 * 将序号转为十进制分组文本，便于快速阅读。
	 */
	private static String formatSerialDisplayText(long serial) {
		if (serial <= 0L) {
			return "";
		}
		return String.format(Locale.ROOT, "%,d", serial);
	}

	/**
	 * 基于服务端当前链路快照构建“当前连接”列表（升序）。
	 */
	private List<Long> resolveLinkedTargetSerialsSnapshot() {
		if (!(level instanceof ServerLevel serverLevel) || serial <= 0L) {
			return List.of();
		}
		List<Long> targets = new ArrayList<>(LinkSavedData.get(serverLevel).getLinkedTargetsBySourceType(getNodeType(), serial));
		targets.removeIf(value -> value == null || value <= 0L);
		targets.sort(Long::compareTo);
		return targets.isEmpty() ? List.of() : List.copyOf(targets);
	}

	/**
	 * 规范化“当前连接”快照，过滤非法值并按升序去重。
	 */
	private static List<Long> normalizeLinkedTargetSnapshot(long[] rawTargets) {
		if (rawTargets == null || rawTargets.length == 0) {
			return List.of();
		}
		List<Long> targets = new ArrayList<>(rawTargets.length);
		for (long value : rawTargets) {
			if (value > 0L) {
				targets.add(value);
			}
		}
		targets.sort(Long::compareTo);
		List<Long> deduplicated = new ArrayList<>(targets.size());
		long previous = Long.MIN_VALUE;
		for (long value : targets) {
			if (value == previous) {
				continue;
			}
			deduplicated.add(value);
			previous = value;
		}
		return deduplicated.isEmpty() ? List.of() : List.copyOf(deduplicated);
	}
}
