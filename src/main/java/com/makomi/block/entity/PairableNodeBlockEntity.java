package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
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

public abstract class PairableNodeBlockEntity extends BlockEntity {
	protected static final String KEY_SERIAL = "Serial";
	private static final String KEY_LAST_TARGET_SERIAL = "LastTargetSerial";

	private long serial;
	private long lastTargetSerial;

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
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (serial <= 0L) {
			return;
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
		LinkSavedData.get(serverLevel).retireNode(getNodeType(), serial);
	}

	protected abstract LinkNodeType getNodeType();

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
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		if (serial > 0L) {
			tag.putLong(KEY_SERIAL, serial);
		}
		if (lastTargetSerial > 0L) {
			tag.putLong(KEY_LAST_TARGET_SERIAL, lastTargetSerial);
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

	protected void syncToClient() {
		setChanged();
		if (level != null && !level.isClientSide) {
			BlockState state = getBlockState();
			level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		}
	}
}
