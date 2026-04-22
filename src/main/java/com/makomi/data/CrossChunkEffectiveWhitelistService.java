package com.makomi.data;

import net.minecraft.server.level.ServerLevel;

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
}
