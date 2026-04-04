package com.makomi.data;

import com.makomi.RedstoneLink;
import com.makomi.util.SerialNbtCodecUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * LinkSavedData 存档编解码 helper。
 * <p>
 * 负责 NBT 读写、非法条目过滤与类型统计日志。
 * </p>
 */
final class LinkSavedDataCodecSupport {
	private LinkSavedDataCodecSupport() {
	}

	/**
	 * 从 NBT 读取 LinkSavedData。
	 */
	static LinkSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
		LinkSavedData data = new LinkSavedData();
		Map<String, Integer> rejectedTypeCounts = new HashMap<>();
		int rejectedTypeRows = 0;

		if (tag.contains(LinkSavedData.KEY_NEXT_CORE_SERIAL, Tag.TAG_LONG)) {
			data.nextCoreSerial = Math.max(1L, tag.getLong(LinkSavedData.KEY_NEXT_CORE_SERIAL));
		}

		if (tag.contains(LinkSavedData.KEY_NEXT_BUTTON_SERIAL, Tag.TAG_LONG)) {
			data.nextButtonSerial = Math.max(1L, tag.getLong(LinkSavedData.KEY_NEXT_BUTTON_SERIAL));
		}

		ListTag nodesTag = tag.getList(LinkSavedData.KEY_NODES, Tag.TAG_COMPOUND);
		for (Tag entryTag : nodesTag) {
			if (!(entryTag instanceof CompoundTag compound)) {
				continue;
			}
			long serial = compound.getLong(LinkSavedData.KEY_SERIAL);
			if (serial <= 0L) {
				continue;
			}

			ResourceLocation dimensionId = ResourceLocation.tryParse(compound.getString(LinkSavedData.KEY_DIMENSION));
			if (dimensionId == null) {
				continue;
			}

			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
			BlockPos pos = BlockPos.of(compound.getLong(LinkSavedData.KEY_POS));
			Optional<LinkNodeType> parsedType = parseStoredNodeType(compound);
			if (parsedType.isEmpty()) {
				rejectedTypeRows++;
				rejectedTypeCounts.merge(normalizeStoredTypeForStats(compound.getString(LinkSavedData.KEY_TYPE)), 1, Integer::sum);
				continue;
			}
			LinkNodeType type = parsedType.get();
			data.nodeMap(type).put(serial, new LinkSavedData.LinkNode(serial, dimension, pos, type));
		}
		if (rejectedTypeRows > 0) {
			RedstoneLink.LOGGER.warn(
				"[DiagRuntime] link_saveddata_type_mismatch rowsDropped={}, distinctRawTypes={}, topRawTypes={}",
				rejectedTypeRows,
				rejectedTypeCounts.size(),
				summarizeTopTypeCounts(rejectedTypeCounts, 8)
			);
		}

		ListTag linksTag = tag.getList(LinkSavedData.KEY_LINKS, Tag.TAG_COMPOUND);
		for (Tag entryTag : linksTag) {
			if (!(entryTag instanceof CompoundTag compound)) {
				continue;
			}
			long sourceSerial = compound.getLong(LinkSavedData.KEY_SOURCE_SERIAL);
			if (sourceSerial <= 0L) {
				continue;
			}

			for (long targetSerial : compound.getLongArray(LinkSavedData.KEY_TARGET_SERIALS)) {
				if (targetSerial <= 0L) {
					continue;
				}
				LinkSavedDataLinkIndexSupport.link(data, sourceSerial, targetSerial);
			}
		}

		ListTag replaySnapshotsTag = tag.getList(LinkSavedData.KEY_TRIGGER_SOURCE_REPLAY_SYNC_SNAPSHOTS, Tag.TAG_COMPOUND);
		for (Tag entryTag : replaySnapshotsTag) {
			if (!(entryTag instanceof CompoundTag compound)) {
				continue;
			}
			long triggerSourceSerial = compound.getLong(LinkSavedData.KEY_SERIAL);
			if (triggerSourceSerial <= 0L) {
				continue;
			}
			LinkSavedData.ReplaySyncSnapshotRecord snapshot = loadReplaySyncSnapshotRecord(compound).orElse(null);
			if (snapshot == null) {
				continue;
			}
			data.triggerSourceReplaySyncSnapshots.put(triggerSourceSerial, snapshot);
		}

		boolean hasAllocatedCore = tag.contains(LinkSavedData.KEY_ALLOCATED_CORE_SERIALS, Tag.TAG_LONG_ARRAY);
		boolean hasAllocatedButton = tag.contains(LinkSavedData.KEY_ALLOCATED_BUTTON_SERIALS, Tag.TAG_LONG_ARRAY);
		if (hasAllocatedCore) {
			SerialNbtCodecUtil.readSerialSet(tag, LinkSavedData.KEY_ALLOCATED_CORE_SERIALS, data.allocatedCoreSerials);
		}
		if (hasAllocatedButton) {
			SerialNbtCodecUtil.readSerialSet(tag, LinkSavedData.KEY_ALLOCATED_BUTTON_SERIALS, data.allocatedButtonSerials);
		}
		SerialNbtCodecUtil.readSerialSet(tag, LinkSavedData.KEY_RETIRED_CORE_SERIALS, data.retiredCoreSerials);
		SerialNbtCodecUtil.readSerialSet(tag, LinkSavedData.KEY_RETIRED_BUTTON_SERIALS, data.retiredButtonSerials);
		LinkSavedDataSerialSupport.ensureKnownSerialsAllocated(data);
		LinkSavedDataSerialSupport.correctNextSerials(data);
		return data;
	}

	/**
	 * 将 LinkSavedData 写回 NBT。
	 */
	static CompoundTag save(LinkSavedData data, CompoundTag tag) {
		tag.putLong(LinkSavedData.KEY_NEXT_CORE_SERIAL, data.nextCoreSerial);
		tag.putLong(LinkSavedData.KEY_NEXT_BUTTON_SERIAL, data.nextButtonSerial);
		tag.putLongArray(LinkSavedData.KEY_ALLOCATED_CORE_SERIALS, SerialNbtCodecUtil.toSortedLongArray(data.allocatedCoreSerials));
		tag.putLongArray(LinkSavedData.KEY_ALLOCATED_BUTTON_SERIALS, SerialNbtCodecUtil.toSortedLongArray(data.allocatedButtonSerials));
		tag.putLongArray(LinkSavedData.KEY_RETIRED_CORE_SERIALS, SerialNbtCodecUtil.toSortedLongArray(data.retiredCoreSerials));
		tag.putLongArray(LinkSavedData.KEY_RETIRED_BUTTON_SERIALS, SerialNbtCodecUtil.toSortedLongArray(data.retiredButtonSerials));

		ListTag nodesTag = new ListTag();
		saveNodeMap(nodesTag, data.coreNodes);
		saveNodeMap(nodesTag, data.buttonNodes);
		tag.put(LinkSavedData.KEY_NODES, nodesTag);

		ListTag linksTag = new ListTag();
		for (Map.Entry<Long, Set<Long>> entry : data.buttonToCores.entrySet()) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			CompoundTag compound = new CompoundTag();
			compound.putLong(LinkSavedData.KEY_SOURCE_SERIAL, entry.getKey());
			compound.putLongArray(LinkSavedData.KEY_TARGET_SERIALS, entry.getValue().stream().toList());
			linksTag.add(compound);
		}
		tag.put(LinkSavedData.KEY_LINKS, linksTag);

		ListTag replaySnapshotsTag = new ListTag();
		saveReplaySnapshots(replaySnapshotsTag, data.triggerSourceReplaySyncSnapshots);
		tag.put(LinkSavedData.KEY_TRIGGER_SOURCE_REPLAY_SYNC_SNAPSHOTS, replaySnapshotsTag);
		return tag;
	}

	/**
	 * 保存一个节点映射。
	 */
	static void saveNodeMap(ListTag nodesTag, Map<Long, LinkSavedData.LinkNode> map) {
		for (LinkSavedData.LinkNode node : map.values()) {
			CompoundTag entry = new CompoundTag();
			entry.putLong(LinkSavedData.KEY_SERIAL, node.serial());
			entry.putString(LinkSavedData.KEY_DIMENSION, node.dimension().location().toString());
			entry.putLong(LinkSavedData.KEY_POS, node.pos().asLong());
			entry.putString(LinkSavedData.KEY_TYPE, LinkNodeSemantics.toSemanticName(node.type()));
			nodesTag.add(entry);
		}
	}

	/**
	 * 保存 triggerSource 最近一次真实 sync replay 快照。
	 */
	static void saveReplaySnapshots(
		ListTag replaySnapshotsTag,
		Map<Long, LinkSavedData.ReplaySyncSnapshotRecord> replaySnapshots
	) {
		List<Map.Entry<Long, LinkSavedData.ReplaySyncSnapshotRecord>> entries = new ArrayList<>(replaySnapshots.entrySet());
		entries.sort(Map.Entry.comparingByKey());
		for (Map.Entry<Long, LinkSavedData.ReplaySyncSnapshotRecord> entry : entries) {
			if (entry.getKey() == null || entry.getKey() <= 0L || entry.getValue() == null || entry.getValue().eventMeta() == null) {
				continue;
			}
			CompoundTag snapshotTag = new CompoundTag();
			snapshotTag.putLong(LinkSavedData.KEY_SERIAL, entry.getKey());
			snapshotTag.putInt(LinkSavedData.KEY_SIGNAL_STRENGTH, entry.getValue().signalStrength());
			snapshotTag.putLong(LinkSavedData.KEY_TICK, entry.getValue().eventMeta().timeKey().tick());
			snapshotTag.putInt(LinkSavedData.KEY_SLOT, entry.getValue().eventMeta().timeKey().slot());
			snapshotTag.putLong(LinkSavedData.KEY_SEQ, entry.getValue().eventMeta().seq());
			replaySnapshotsTag.add(snapshotTag);
		}
	}

	/**
	 * 读取一条 triggerSource sync replay 快照记录。
	 */
	static Optional<LinkSavedData.ReplaySyncSnapshotRecord> loadReplaySyncSnapshotRecord(CompoundTag compound) {
		if (compound == null) {
			return Optional.empty();
		}
		return Optional.of(
			new LinkSavedData.ReplaySyncSnapshotRecord(
				com.makomi.util.SignalStrengths.clamp(compound.getInt(LinkSavedData.KEY_SIGNAL_STRENGTH)),
				com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta.of(
					Math.max(0L, compound.getLong(LinkSavedData.KEY_TICK)),
					Math.max(0, compound.getInt(LinkSavedData.KEY_SLOT)),
					Math.max(0L, compound.getLong(LinkSavedData.KEY_SEQ))
				)
			)
		);
	}

	/**
	 * 解析存档节点类型文本。
	 */
	static Optional<LinkNodeType> parseStoredNodeType(CompoundTag compound) {
		if (compound == null) {
			return Optional.empty();
		}
		return LinkNodeSemantics.tryParseCanonicalType(compound.getString(LinkSavedData.KEY_TYPE));
	}

	/**
	 * 归一化存档中的原始类型文本，便于统计输出。
	 */
	static String normalizeStoredTypeForStats(String rawType) {
		if (rawType == null) {
			return "<null>";
		}
		String normalized = rawType.trim();
		return normalized.isEmpty() ? "<empty>" : normalized;
	}

	/**
	 * 汇总类型计数 TopN 文本，供日志快速查看。
	 */
	static String summarizeTopTypeCounts(Map<String, Integer> counts, int limit) {
		if (counts == null || counts.isEmpty()) {
			return "-";
		}
		List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
		entries.sort(
			Comparator
				.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
				.reversed()
				.thenComparing(Map.Entry::getKey)
		);
		int max = Math.min(Math.max(1, limit), entries.size());
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < max; index++) {
			Map.Entry<String, Integer> entry = entries.get(index);
			if (index > 0) {
				builder.append(", ");
			}
			builder.append(entry.getKey()).append(":").append(entry.getValue());
		}
		if (entries.size() > max) {
			builder.append(" (+").append(entries.size() - max).append(" types)");
		}
		return builder.toString();
	}

}
