package com.makomi.data;

import com.makomi.util.SerialParseUtil;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 跨区块有效白名单查询服务。
 * <p>
 * 统一合并：
 * </p>
 * <ul>
 * <li>手动 `CrossChunkWhitelistSavedData`；</li>
 * <li>激活态区块激活器贡献；</li>
 * <li>调用方自行处理的 preset。</li>
 * </ul>
 */
public final class CrossChunkEffectiveWhitelistService {
	private CrossChunkEffectiveWhitelistService() {
	}

	/**
	 * 判断指定节点是否命中“手动白名单 + 区块激活器贡献”。
	 */
	public static boolean containsWhitelist(
		ServerLevel level,
		LinkNodeType type,
		long serial,
		LinkNodeSemantics.Role role
	) {
		if (level == null) {
			return false;
		}
		return containsWhitelist(
			type,
			serial,
			role,
			CrossChunkWhitelistSavedData.get(level),
			PlacedChunkActivatorSavedData.get(level)
		);
	}

	/**
	 * 判断指定节点是否命中“手动 resident + 区块激活器 resident 贡献”。
	 */
	public static boolean isResident(
		ServerLevel level,
		LinkNodeType type,
		long serial,
		LinkNodeSemantics.Role role
	) {
		if (level == null) {
			return false;
		}
		return isResident(
			type,
			serial,
			role,
			CrossChunkWhitelistSavedData.get(level),
			PlacedChunkActivatorSavedData.get(level)
		);
	}

	/**
	 * 当前是否仍存在有效 resident 白名单。
	 */
	public static boolean hasResidents(ServerLevel level) {
		if (level == null) {
			return false;
		}
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(level);
		PlacedChunkActivatorSavedData chunkActivatorSavedData = PlacedChunkActivatorSavedData.get(level);
		return whitelistSavedData.hasResidents() || chunkActivatorSavedData.hasResidents();
	}

	/**
	 * 统计当前世界“手动 resident + 激活态区块激活器 resident”去重后的唯一节点总量。
	 */
	public static int countDistinctResidents(ServerLevel level) {
		if (level == null) {
			return 0;
		}
		return countDistinctResidents(CrossChunkWhitelistSavedData.get(level), PlacedChunkActivatorSavedData.get(level));
	}

	/**
	 * 预估指定白名单 resident 集合覆盖后的有效 resident 总量。
	 */
	public static int countDistinctResidentsAfterManualResidentChange(
		ServerLevel level,
		LinkNodeType type,
		LinkNodeSemantics.Role role,
		Set<Long> residentSerials
	) {
		if (level == null) {
			return 0;
		}
		return countDistinctResidentsAfterManualResidentChange(
			CrossChunkWhitelistSavedData.get(level),
			PlacedChunkActivatorSavedData.get(level),
			type,
			role,
			residentSerials
		);
	}

	/**
	 * 预估指定区块激活器条目覆盖后的有效 resident 总量。
	 */
	public static int countDistinctResidentsAfterActivatorChange(
		ServerLevel level,
		ResourceKey<Level> dimension,
		BlockPos activatorPos,
		ChunkActivatorConfigStateSnapshot configStateSnapshot,
		boolean active
	) {
		if (level == null) {
			return 0;
		}
		return countDistinctResidentsAfterActivatorChange(
			CrossChunkWhitelistSavedData.get(level),
			PlacedChunkActivatorSavedData.get(level),
			dimension,
			activatorPos,
			configStateSnapshot,
			active
		);
	}

	/**
	 * 遍历当前有效 resident 白名单。
	 */
	public static void forEachResidentSerial(
		ServerLevel level,
		LinkNodeSemantics.Role role,
		CrossChunkWhitelistSavedData.ResidentSerialConsumer consumer
	) {
		if (level == null || role == null || consumer == null) {
			return;
		}
		CrossChunkWhitelistSavedData whitelistSavedData = CrossChunkWhitelistSavedData.get(level);
		whitelistSavedData.forEachResidentSerial(role, consumer);
		PlacedChunkActivatorSavedData chunkActivatorSavedData = PlacedChunkActivatorSavedData.get(level);
		if (role == LinkNodeSemantics.Role.SOURCE) {
			chunkActivatorSavedData.forEachResidentSerial(LinkNodeType.TRIGGER_SOURCE, serial -> consumer.accept(LinkNodeType.TRIGGER_SOURCE, serial));
			return;
		}
		if (role == LinkNodeSemantics.Role.TARGET) {
			chunkActivatorSavedData.forEachResidentSerial(LinkNodeType.CORE, serial -> consumer.accept(LinkNodeType.CORE, serial));
		}
	}

	static int countDistinctResidents(
		CrossChunkWhitelistSavedData whitelistSavedData,
		PlacedChunkActivatorSavedData chunkActivatorSavedData
	) {
		Set<ResidentEntryKey> residentEntries = new LinkedHashSet<>();
		appendManualResidents(residentEntries, whitelistSavedData, null, null, null);
		appendActivatorResidents(residentEntries, chunkActivatorSavedData, null, null, null, false);
		return residentEntries.size();
	}

	static int countDistinctResidentsAfterManualResidentChange(
		CrossChunkWhitelistSavedData whitelistSavedData,
		PlacedChunkActivatorSavedData chunkActivatorSavedData,
		LinkNodeType type,
		LinkNodeSemantics.Role role,
		Set<Long> residentSerials
	) {
		Set<ResidentEntryKey> residentEntries = new LinkedHashSet<>();
		appendManualResidents(
			residentEntries,
			whitelistSavedData,
			ChunkActivatorConfigStateSnapshot.normalizeType(type),
			role,
			residentSerials
		);
		appendActivatorResidents(residentEntries, chunkActivatorSavedData, null, null, null, false);
		return residentEntries.size();
	}

	static int countDistinctResidentsAfterActivatorChange(
		CrossChunkWhitelistSavedData whitelistSavedData,
		PlacedChunkActivatorSavedData chunkActivatorSavedData,
		ResourceKey<Level> dimension,
		BlockPos activatorPos,
		ChunkActivatorConfigStateSnapshot configStateSnapshot,
		boolean active
	) {
		Set<ResidentEntryKey> residentEntries = new LinkedHashSet<>();
		appendManualResidents(residentEntries, whitelistSavedData, null, null, null);
		appendActivatorResidents(residentEntries, chunkActivatorSavedData, dimension, activatorPos, configStateSnapshot, active);
		return residentEntries.size();
	}

	static boolean containsWhitelist(
		LinkNodeType type,
		long serial,
		LinkNodeSemantics.Role role,
		CrossChunkWhitelistSavedData whitelistSavedData,
		PlacedChunkActivatorSavedData chunkActivatorSavedData
	) {
		if (type == null || serial <= 0L || role == null || whitelistSavedData == null) {
			return false;
		}
		if (whitelistSavedData.contains(type, serial, role)) {
			return true;
		}
		return contributesByChunkActivator(type, serial, role, chunkActivatorSavedData, false);
	}

	static boolean isResident(
		LinkNodeType type,
		long serial,
		LinkNodeSemantics.Role role,
		CrossChunkWhitelistSavedData whitelistSavedData,
		PlacedChunkActivatorSavedData chunkActivatorSavedData
	) {
		if (type == null || serial <= 0L || role == null || whitelistSavedData == null) {
			return false;
		}
		if (whitelistSavedData.isResident(type, serial, role)) {
			return true;
		}
		return contributesByChunkActivator(type, serial, role, chunkActivatorSavedData, true);
	}

	private static boolean contributesByChunkActivator(
		LinkNodeType type,
		long serial,
		LinkNodeSemantics.Role role,
		PlacedChunkActivatorSavedData chunkActivatorSavedData,
		boolean residentOnly
	) {
		if (type == null || role == null || chunkActivatorSavedData == null || !LinkNodeSemantics.isAllowedForRole(type, role)) {
			return false;
		}
		return residentOnly ? chunkActivatorSavedData.containsActiveResident(type, serial) : chunkActivatorSavedData.containsActiveForceLoad(type, serial);
	}

	private static void appendManualResidents(
		Set<ResidentEntryKey> residentEntries,
		CrossChunkWhitelistSavedData whitelistSavedData,
		LinkNodeType replacedType,
		LinkNodeSemantics.Role replacedRole,
		Set<Long> replacementResidentSerials
	) {
		if (residentEntries == null || whitelistSavedData == null) {
			return;
		}
		for (LinkNodeSemantics.Role currentRole : new LinkNodeSemantics.Role[] {
			LinkNodeSemantics.Role.SOURCE,
			LinkNodeSemantics.Role.TARGET
		}) {
			whitelistSavedData.forEachResidentSerial(currentRole, (type, serial) -> {
				if (matchesResidentBucket(type, currentRole, replacedType, replacedRole)) {
					return;
				}
				appendResidentEntry(residentEntries, currentRole, type, serial);
			});
		}
		if (!matchesResidentBucket(replacedType, replacedRole, replacedType, replacedRole) || replacementResidentSerials == null) {
			return;
		}
		for (Long serial : replacementResidentSerials) {
			if (serial != null) {
				appendResidentEntry(residentEntries, replacedRole, replacedType, serial);
			}
		}
	}

	private static void appendActivatorResidents(
		Set<ResidentEntryKey> residentEntries,
		PlacedChunkActivatorSavedData chunkActivatorSavedData,
		ResourceKey<Level> replacedDimension,
		BlockPos replacedPos,
		ChunkActivatorConfigStateSnapshot replacementSnapshot,
		boolean replacementActive
	) {
		if (residentEntries == null || chunkActivatorSavedData == null) {
			return;
		}
		for (PlacedChunkActivatorSavedData.ActivatorEntry entry : chunkActivatorSavedData.entriesSnapshot()) {
			if (sameActivatorLocation(entry, replacedDimension, replacedPos)) {
				continue;
			}
			appendActivatorResidentEntries(residentEntries, entry.configStateSnapshot(), entry.active());
		}
		if (replacedDimension == null || replacedPos == null) {
			return;
		}
		appendActivatorResidentEntries(residentEntries, replacementSnapshot, replacementActive);
	}

	private static void appendActivatorResidentEntries(
		Set<ResidentEntryKey> residentEntries,
		ChunkActivatorConfigStateSnapshot configStateSnapshot,
		boolean active
	) {
		if (residentEntries == null || !active || configStateSnapshot == null) {
			return;
		}
		ChunkActivatorConfigStateSnapshot normalizedSnapshot = new ChunkActivatorConfigStateSnapshot(
			configStateSnapshot.activeType(),
			configStateSnapshot.triggerSourceConfig(),
			configStateSnapshot.coreConfig()
		);
		ChunkActivatorConfigSnapshot activeConfig = normalizedSnapshot.activeConfig();
		if (!activeConfig.mode().contributesResident()) {
			return;
		}
		LinkNodeType activeType = normalizedSnapshot.activeType();
		LinkNodeSemantics.Role role = roleForResidentType(activeType);
		if (role == null) {
			return;
		}
		for (Long serial : parseDistinctSerials(activeConfig.serialExpression())) {
			appendResidentEntry(residentEntries, role, activeType, serial);
		}
	}

	private static Set<Long> parseDistinctSerials(String serialExpression) {
		SerialParseUtil.OrderedTargetParseResult parseResult = SerialParseUtil.parseTargetsOrdered(
			serialExpression,
			PlacedChunkActivatorSavedData.MAX_NODE_SET_SIZE
		);
		if (parseResult.orderedTargets().isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(new LinkedHashSet<>(parseResult.orderedTargets()));
	}

	private static void appendResidentEntry(
		Set<ResidentEntryKey> residentEntries,
		LinkNodeSemantics.Role role,
		LinkNodeType type,
		long serial
	) {
		if (
			residentEntries == null
				|| role == null
				|| type == null
				|| serial <= 0L
				|| !LinkNodeSemantics.isAllowedForRole(type, role)
		) {
			return;
		}
		residentEntries.add(new ResidentEntryKey(role, type, serial));
	}

	private static boolean matchesResidentBucket(
		LinkNodeType candidateType,
		LinkNodeSemantics.Role candidateRole,
		LinkNodeType replacedType,
		LinkNodeSemantics.Role replacedRole
	) {
		return candidateType != null
			&& candidateRole != null
			&& replacedType != null
			&& replacedRole != null
			&& candidateType == replacedType
			&& candidateRole == replacedRole;
	}

	private static boolean sameActivatorLocation(
		PlacedChunkActivatorSavedData.ActivatorEntry entry,
		ResourceKey<Level> dimension,
		BlockPos activatorPos
	) {
		return entry != null && dimension != null && activatorPos != null && dimension.equals(entry.dimension()) && activatorPos.equals(entry.activatorPos());
	}

	private static LinkNodeSemantics.Role roleForResidentType(LinkNodeType type) {
		LinkNodeType normalizedType = ChunkActivatorConfigStateSnapshot.normalizeType(type);
		if (normalizedType == LinkNodeType.TRIGGER_SOURCE) {
			return LinkNodeSemantics.Role.SOURCE;
		}
		if (normalizedType == LinkNodeType.CORE) {
			return LinkNodeSemantics.Role.TARGET;
		}
		return null;
	}

	private record ResidentEntryKey(LinkNodeSemantics.Role role, LinkNodeType type, long serial) {}
}
