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
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
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
	static LinkSavedData load(CompoundTag tag) {
		LinkSavedData data = new LinkSavedData();
		Map<String, Integer> rejectedTypeCounts = new HashMap<>();
		int rejectedTypeRows = 0;

		data.nextCoreSerial = Math.max(1L, tag.getLongOr(LinkSavedData.KEY_NEXT_CORE_SERIAL, data.nextCoreSerial));
		data.nextTriggerSourceSerial = Math.max(
			1L,
			tag.getLongOr(LinkSavedData.KEY_NEXT_TRIGGER_SOURCE_SERIAL, data.nextTriggerSourceSerial)
		);
		SerialNbtCodecUtil.readSerialSet(tag, LinkSavedData.KEY_REPEATER_SERIALS, data.repeaterSerials);

		ListTag nodesTag = tag.getListOrEmpty(LinkSavedData.KEY_NODES);
		for (Tag entryTag : nodesTag) {
			if (!(entryTag instanceof CompoundTag compound)) {
				continue;
			}
			long serial = compound.getLongOr(LinkSavedData.KEY_SERIAL, 0L);
			if (serial <= 0L) {
				continue;
			}

			Identifier dimensionId = Identifier.tryParse(compound.getStringOr(LinkSavedData.KEY_DIMENSION, ""));
			if (dimensionId == null) {
				continue;
			}

			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
			BlockPos pos = BlockPos.of(compound.getLongOr(LinkSavedData.KEY_POS, 0L));
			Optional<LinkNodeType> parsedType = parseStoredNodeType(compound);
			if (parsedType.isEmpty()) {
				rejectedTypeRows++;
				rejectedTypeCounts.merge(normalizeStoredTypeForStats(compound.getStringOr(LinkSavedData.KEY_TYPE, "")), 1, Integer::sum);
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

		ListTag linksTag = tag.getListOrEmpty(LinkSavedData.KEY_LINKS);
		for (Tag entryTag : linksTag) {
			if (!(entryTag instanceof CompoundTag compound)) {
				continue;
			}
			long sourceSerial = compound.getLongOr(LinkSavedData.KEY_SOURCE_SERIAL, 0L);
			if (sourceSerial <= 0L) {
				continue;
			}

			for (long targetSerial : compound.getLongArray(LinkSavedData.KEY_TARGET_SERIALS).orElseGet(() -> new long[0])) {
				if (targetSerial <= 0L) {
					continue;
				}
				if (LinkSavedDataLinkIndexSupport.isRepeaterSelfLink(data, sourceSerial, targetSerial)) {
					continue;
				}
				LinkSavedDataLinkIndexSupport.linkTriggerSourceCore(data, sourceSerial, targetSerial);
			}
		}

		ListTag replaySnapshotsTag = tag.getListOrEmpty(LinkSavedData.KEY_TRIGGER_SOURCE_REPLAY_SYNC_SNAPSHOTS);
		for (Tag entryTag : replaySnapshotsTag) {
			if (!(entryTag instanceof CompoundTag compound)) {
				continue;
			}
			long triggerSourceSerial = compound.getLongOr(LinkSavedData.KEY_SERIAL, 0L);
			if (triggerSourceSerial <= 0L) {
				continue;
			}
			LinkSavedData.ReplaySyncSnapshotRecord snapshot = loadReplaySyncSnapshotRecord(compound).orElse(null);
			if (snapshot == null) {
				continue;
			}
			data.triggerSourceReplaySyncSnapshots.put(triggerSourceSerial, snapshot);
		}

		loadChannelConfigs(tag.getListOrEmpty(LinkSavedData.KEY_TRIGGER_SOURCE_CHANNEL_CONFIGS), data, LinkNodeType.TRIGGER_SOURCE);
		loadChannelConfigs(tag.getListOrEmpty(LinkSavedData.KEY_CORE_CHANNEL_CONFIGS), data, LinkNodeType.CORE);

		boolean hasAllocatedCore = tag.contains(LinkSavedData.KEY_ALLOCATED_CORE_SERIALS);
		boolean hasAllocatedTriggerSource = tag.contains(LinkSavedData.KEY_ALLOCATED_TRIGGER_SOURCE_SERIALS);
		if (hasAllocatedCore) {
			SerialNbtCodecUtil.readSerialSet(tag, LinkSavedData.KEY_ALLOCATED_CORE_SERIALS, data.allocatedCoreSerials);
		}
		if (hasAllocatedTriggerSource) {
			SerialNbtCodecUtil.readSerialSet(
				tag,
				LinkSavedData.KEY_ALLOCATED_TRIGGER_SOURCE_SERIALS,
				data.allocatedTriggerSourceSerials
			);
		}
		SerialNbtCodecUtil.readSerialSet(tag, LinkSavedData.KEY_RETIRED_CORE_SERIALS, data.retiredCoreSerials);
		SerialNbtCodecUtil.readSerialSet(
			tag,
			LinkSavedData.KEY_RETIRED_TRIGGER_SOURCE_SERIALS,
			data.retiredTriggerSourceSerials
		);
		LinkSavedDataSerialSupport.ensureKnownSerialsAllocated(data);
		LinkSavedDataSerialSupport.correctNextSerials(data);
		return data;
	}

	/**
	 * 将 LinkSavedData 写回 NBT。
	 */
	static CompoundTag save(LinkSavedData data, CompoundTag tag) {
		tag.putLong(LinkSavedData.KEY_NEXT_CORE_SERIAL, data.nextCoreSerial);
		tag.putLong(LinkSavedData.KEY_NEXT_TRIGGER_SOURCE_SERIAL, data.nextTriggerSourceSerial);
		tag.putLongArray(LinkSavedData.KEY_ALLOCATED_CORE_SERIALS, SerialNbtCodecUtil.toSortedLongArray(data.allocatedCoreSerials));
		tag.putLongArray(
			LinkSavedData.KEY_ALLOCATED_TRIGGER_SOURCE_SERIALS,
			SerialNbtCodecUtil.toSortedLongArray(data.allocatedTriggerSourceSerials)
		);
		tag.putLongArray(LinkSavedData.KEY_RETIRED_CORE_SERIALS, SerialNbtCodecUtil.toSortedLongArray(data.retiredCoreSerials));
		tag.putLongArray(
			LinkSavedData.KEY_RETIRED_TRIGGER_SOURCE_SERIALS,
			SerialNbtCodecUtil.toSortedLongArray(data.retiredTriggerSourceSerials)
		);
		tag.putLongArray(LinkSavedData.KEY_REPEATER_SERIALS, SerialNbtCodecUtil.toSortedLongArray(data.repeaterSerials));

		ListTag nodesTag = new ListTag();
		saveNodeMap(nodesTag, data.coreNodes);
		saveNodeMap(nodesTag, data.triggerSourceNodes);
		tag.put(LinkSavedData.KEY_NODES, nodesTag);

		ListTag linksTag = new ListTag();
		for (Map.Entry<Long, Set<Long>> entry : data.triggerSourceToCores.entrySet()) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			List<Long> visibleTargetSerials = entry
				.getValue()
				.stream()
				.filter(targetSerial -> !LinkSavedDataLinkIndexSupport.isRepeaterSelfLink(data, entry.getKey(), targetSerial))
				.toList();
			if (visibleTargetSerials.isEmpty()) {
				continue;
			}
			CompoundTag compound = new CompoundTag();
			compound.putLong(LinkSavedData.KEY_SOURCE_SERIAL, entry.getKey());
			compound.putLongArray(
				LinkSavedData.KEY_TARGET_SERIALS,
				visibleTargetSerials.stream().mapToLong(Long::longValue).toArray()
			);
			linksTag.add(compound);
		}
		tag.put(LinkSavedData.KEY_LINKS, linksTag);

		ListTag replaySnapshotsTag = new ListTag();
		saveReplaySnapshots(replaySnapshotsTag, data.triggerSourceReplaySyncSnapshots);
		tag.put(LinkSavedData.KEY_TRIGGER_SOURCE_REPLAY_SYNC_SNAPSHOTS, replaySnapshotsTag);

		ListTag triggerSourceChannelConfigsTag = new ListTag();
		saveChannelConfigs(triggerSourceChannelConfigsTag, data.triggerSourceChannelConfigs);
		tag.put(LinkSavedData.KEY_TRIGGER_SOURCE_CHANNEL_CONFIGS, triggerSourceChannelConfigsTag);

		ListTag coreChannelConfigsTag = new ListTag();
		saveChannelConfigs(coreChannelConfigsTag, data.coreChannelConfigs);
		tag.put(LinkSavedData.KEY_CORE_CHANNEL_CONFIGS, coreChannelConfigsTag);
		return tag;
	}

	/**
	 * 保存一个节点映射。
	 */
	static void saveNodeMap(ListTag nodesTag, Map<Long, LinkSavedData.LinkNode> map) {
		for (LinkSavedData.LinkNode node : map.values()) {
			CompoundTag entry = new CompoundTag();
			entry.putLong(LinkSavedData.KEY_SERIAL, node.serial());
			entry.putString(LinkSavedData.KEY_DIMENSION, node.dimension().identifier().toString());
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
	 * 保存节点频道配置。
	 */
	static void saveChannelConfigs(ListTag channelConfigsTag, Map<Long, Long> channelConfigs) {
		List<Map.Entry<Long, Long>> entries = new ArrayList<>(channelConfigs.entrySet());
		entries.sort(Map.Entry.comparingByKey());
		for (Map.Entry<Long, Long> entry : entries) {
			long serial = entry.getKey() == null ? 0L : entry.getKey();
			long channel = entry.getValue() == null ? 0L : entry.getValue();
			if (serial <= 0L || !LinkSavedDataChannelSupport.isValidChannel(channel)) {
				continue;
			}
			CompoundTag configTag = new CompoundTag();
			configTag.putLong(LinkSavedData.KEY_SERIAL, serial);
			configTag.putLong(LinkSavedData.KEY_CHANNEL, channel);
			channelConfigsTag.add(configTag);
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
				com.makomi.util.SignalStrengths.clamp(compound.getIntOr(LinkSavedData.KEY_SIGNAL_STRENGTH, 0)),
				com.makomi.block.entity.ActivatableTargetBlockEntity.EventMeta.of(
					Math.max(0L, compound.getLongOr(LinkSavedData.KEY_TICK, 0L)),
					Math.max(0, compound.getIntOr(LinkSavedData.KEY_SLOT, 0)),
					Math.max(0L, compound.getLongOr(LinkSavedData.KEY_SEQ, 0L))
				)
			)
		);
	}

	/**
	 * 读取指定节点类型的频道配置列表。
	 */
	private static void loadChannelConfigs(ListTag configsTag, LinkSavedData data, LinkNodeType type) {
		if (configsTag == null || data == null || type == null) {
			return;
		}
		for (Tag entryTag : configsTag) {
			if (!(entryTag instanceof CompoundTag compound)) {
				continue;
			}
			long serial = compound.getLongOr(LinkSavedData.KEY_SERIAL, 0L);
			long channel = compound.getLongOr(LinkSavedData.KEY_CHANNEL, 0L);
			if (serial <= 0L || !LinkSavedDataChannelSupport.isValidChannel(channel)) {
				continue;
			}
			LinkSavedDataChannelSupport.configMap(data, type).put(serial, channel);
		}
	}

	/**
	 * 解析存档节点类型文本。
	 */
	static Optional<LinkNodeType> parseStoredNodeType(CompoundTag compound) {
		if (compound == null) {
			return Optional.empty();
		}
		return LinkNodeSemantics.tryParseCanonicalType(compound.getStringOr(LinkSavedData.KEY_TYPE, ""));
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
