package com.makomi.data;

import com.makomi.block.entity.ActivationMode;
import com.makomi.util.SignalStrengths;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 跨区块持久队列编解码 helper。
 * <p>
 * 负责 DispatchKey / PendingDispatchEntry / 版本图 的 NBT 读写。
 * </p>
 */
final class CrossChunkDispatchQueueCodecSupport {
	private CrossChunkDispatchQueueCodecSupport() {
	}

	/**
	 * 读取 key -> version 版本图。
	 */
	static void readVersionMap(
		ListTag listTag,
		Map<CrossChunkDispatchQueueSavedData.DispatchKey, Long> target
	) {
		for (Tag element : listTag) {
			if (!(element instanceof CompoundTag entryTag)) {
				continue;
			}
			Optional<CrossChunkDispatchQueueSavedData.DispatchKey> key = parseDispatchKey(entryTag);
			if (key.isEmpty()) {
				continue;
			}
			long version = entryTag.getLong(CrossChunkDispatchQueueSavedData.KEY_VERSION);
			if (version <= 0L) {
				continue;
			}
			target.merge(key.get(), version, Math::max);
		}
	}

	/**
	 * 写出 key -> version 版本图。
	 */
	static ListTag writeVersionMap(Map<CrossChunkDispatchQueueSavedData.DispatchKey, Long> versionByKey) {
		ListTag listTag = new ListTag();
		versionByKey
			.entrySet()
			.stream()
			.filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L)
			.sorted(Map.Entry.comparingByKey(CrossChunkDispatchQueueSavedData.DISPATCH_KEY_COMPARATOR))
			.forEach(entry -> {
				CompoundTag entryTag = new CompoundTag();
				writeDispatchKey(entryTag, entry.getKey());
				entryTag.putLong(CrossChunkDispatchQueueSavedData.KEY_VERSION, entry.getValue());
				listTag.add(entryTag);
			});
		return listTag;
	}

	/**
	 * 将 dispatch key 写入 NBT。
	 */
	static void writeDispatchKey(CompoundTag tag, CrossChunkDispatchQueueSavedData.DispatchKey key) {
		tag.putString(CrossChunkDispatchQueueSavedData.KEY_SOURCE_TYPE, LinkNodeSemantics.toSemanticName(key.sourceType()));
		tag.putLong(CrossChunkDispatchQueueSavedData.KEY_SOURCE_SERIAL, key.sourceSerial());
		tag.putString(CrossChunkDispatchQueueSavedData.KEY_TARGET_TYPE, LinkNodeSemantics.toSemanticName(key.targetType()));
		tag.putLong(CrossChunkDispatchQueueSavedData.KEY_TARGET_SERIAL, key.targetSerial());
		tag.putString(CrossChunkDispatchQueueSavedData.KEY_DISPATCH_KIND, key.dispatchKind().name());
	}

	/**
	 * 从 NBT 解析 dispatch key。
	 */
	static Optional<CrossChunkDispatchQueueSavedData.DispatchKey> parseDispatchKey(CompoundTag tag) {
		Optional<LinkNodeType> sourceType =
			LinkNodeSemantics.tryParseCanonicalType(tag.getString(CrossChunkDispatchQueueSavedData.KEY_SOURCE_TYPE));
		Optional<LinkNodeType> targetType =
			LinkNodeSemantics.tryParseCanonicalType(tag.getString(CrossChunkDispatchQueueSavedData.KEY_TARGET_TYPE));
		if (sourceType.isEmpty() || targetType.isEmpty()) {
			return Optional.empty();
		}
		long sourceSerial = tag.getLong(CrossChunkDispatchQueueSavedData.KEY_SOURCE_SERIAL);
		long targetSerial = tag.getLong(CrossChunkDispatchQueueSavedData.KEY_TARGET_SERIAL);
		if (sourceSerial <= 0L || targetSerial <= 0L) {
			return Optional.empty();
		}
		Optional<CrossChunkDispatchQueueSavedData.DispatchKind> dispatchKind =
			CrossChunkDispatchQueueSavedData.DispatchKind.fromName(tag.getString(CrossChunkDispatchQueueSavedData.KEY_DISPATCH_KIND));
		if (dispatchKind.isEmpty()) {
			return Optional.empty();
		}
		CrossChunkDispatchQueueSavedData.DispatchKey key = new CrossChunkDispatchQueueSavedData.DispatchKey(
			sourceType.get(),
			sourceSerial,
			targetType.get(),
			targetSerial,
			dispatchKind.get()
		);
		if (!LinkNodeSemantics.isAllowedForRole(key.sourceType(), LinkNodeSemantics.Role.SOURCE)) {
			return Optional.empty();
		}
		if (!LinkNodeSemantics.isAllowedForRole(key.targetType(), LinkNodeSemantics.Role.TARGET)) {
			return Optional.empty();
		}
		return Optional.of(key);
	}

	/**
	 * 从 NBT 解析 pending 条目。
	 */
	static Optional<CrossChunkDispatchQueueSavedData.PendingDispatchEntry> parsePendingEntry(CompoundTag tag) {
		Optional<CrossChunkDispatchQueueSavedData.DispatchKey> key = parseDispatchKey(tag);
		if (key.isEmpty()) {
			return Optional.empty();
		}
		ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString(CrossChunkDispatchQueueSavedData.KEY_DIMENSION));
		if (dimensionId == null) {
			return Optional.empty();
		}
		long expireTick = tag.getLong(CrossChunkDispatchQueueSavedData.KEY_EXPIRE_TICK);
		long version = tag.getLong(CrossChunkDispatchQueueSavedData.KEY_VERSION);
		if (expireTick <= 0L || version <= 0L) {
			return Optional.empty();
		}
		ActivationMode activationMode =
			ActivationMode.fromName(tag.getString(CrossChunkDispatchQueueSavedData.KEY_ACTIVATION_MODE));
		CrossChunkDispatchQueueSavedData.DispatchAction dispatchAction =
			CrossChunkDispatchQueueSavedData.DispatchAction.fromName(tag.getString(CrossChunkDispatchQueueSavedData.KEY_DISPATCH_ACTION))
				.orElse(CrossChunkDispatchQueueSavedData.DispatchAction.UPSERT);
		int syncSignalStrength = SignalStrengths.clamp(tag.getInt(CrossChunkDispatchQueueSavedData.KEY_SYNC_SIGNAL_STRENGTH));
		long enqueueTick = Math.max(0L, tag.getLong(CrossChunkDispatchQueueSavedData.KEY_ENQUEUE_TICK));
		int enqueueSlot = Math.max(0, tag.getInt(CrossChunkDispatchQueueSavedData.KEY_ENQUEUE_SLOT));
		BlockPos pos = BlockPos.of(tag.getLong(CrossChunkDispatchQueueSavedData.KEY_POS));
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		return Optional.of(
			new CrossChunkDispatchQueueSavedData.PendingDispatchEntry(
				key.get(),
				dispatchAction,
				dimension,
				pos,
				activationMode,
				syncSignalStrength,
				enqueueTick,
				enqueueSlot,
				expireTick,
				version
			)
		);
	}
}
