package com.makomi.data;

import java.util.OptionalLong;
import net.minecraft.server.level.ServerLevel;

/**
 * 转发器别名镜像支撑。
 * <p>
 * 转发器对外是一个统一节点，但内部同时占用同号 `core/triggerSource` 双身份；
 * 因此任何节点别名写入都需要在双身份之间做镜像，避免两侧展示漂移。
 * </p>
 */
public final class RepeaterAliasMirrorSupport {
	private RepeaterAliasMirrorSupport() {
	}

	/**
	 * 按“转发器统一别名”语义写入别名；非转发器节点回退为普通单节点写入。
	 */
	public static NodeAliasSavedData.UpsertResult upsert(
		ServerLevel level,
		LinkNodeType type,
		long serial,
		String rawAlias
	) {
		NodeAliasSavedData.ValidationResult validation = NodeAliasSavedData.validateAlias(rawAlias);
		if (level == null || type == null || serial <= 0L || !validation.valid()) {
			return NodeAliasSavedData.UpsertResult.invalid(validation);
		}

		NodeAliasSavedData aliasSavedData = NodeAliasSavedData.get(level);
		if (!LinkSavedData.get(level).isRepeaterSerial(serial)) {
			return aliasSavedData.upsert(type, serial, validation.normalizedAlias());
		}

		LinkNodeType peerType = peerType(type);
		String normalizedAlias = validation.normalizedAlias();
		OptionalLong selfConflict = aliasSavedData.resolveSerial(type, normalizedAlias);
		if (selfConflict.isPresent() && selfConflict.getAsLong() != serial) {
			return NodeAliasSavedData.UpsertResult.conflict(selfConflict.getAsLong(), normalizedAlias);
		}
		OptionalLong peerConflict = aliasSavedData.resolveSerial(peerType, normalizedAlias);
		if (peerConflict.isPresent() && peerConflict.getAsLong() != serial) {
			return NodeAliasSavedData.UpsertResult.conflict(peerConflict.getAsLong(), normalizedAlias);
		}

		String previousAlias = aliasSavedData.getAlias(type, serial).orElse("");
		String previousPeerAlias = aliasSavedData.getAlias(peerType, serial).orElse("");
		NodeAliasSavedData.UpsertResult primaryResult = aliasSavedData.upsert(type, serial, normalizedAlias);
		NodeAliasSavedData.UpsertResult peerResult = aliasSavedData.upsert(peerType, serial, normalizedAlias);
		if (!primaryResult.valid() || primaryResult.conflict()) {
			return primaryResult;
		}
		if (!peerResult.valid() || peerResult.conflict()) {
			return peerResult;
		}
		if (!primaryResult.changed() && !peerResult.changed()) {
			return NodeAliasSavedData.UpsertResult.unchanged(normalizedAlias);
		}
		String previousDisplayAlias = NodeAliasDisplayUtil.normalizeAlias(previousAlias.isEmpty() ? previousPeerAlias : previousAlias);
		return NodeAliasSavedData.UpsertResult.changed(previousDisplayAlias, normalizedAlias);
	}

	/**
	 * 按“转发器统一别名”语义移除别名；非转发器节点回退为普通单节点移除。
	 */
	public static NodeAliasSavedData.RemoveResult remove(ServerLevel level, LinkNodeType type, long serial) {
		if (level == null || type == null || serial <= 0L) {
			return NodeAliasSavedData.RemoveResult.notFound();
		}
		NodeAliasSavedData aliasSavedData = NodeAliasSavedData.get(level);
		if (!LinkSavedData.get(level).isRepeaterSerial(serial)) {
			return aliasSavedData.remove(type, serial);
		}
		NodeAliasSavedData.RemoveResult primaryResult = aliasSavedData.remove(type, serial);
		NodeAliasSavedData.RemoveResult peerResult = aliasSavedData.remove(peerType(type), serial);
		if (primaryResult.removed()) {
			return primaryResult;
		}
		if (peerResult.removed()) {
			return peerResult;
		}
		return NodeAliasSavedData.RemoveResult.notFound();
	}

	/**
	 * 统一刷新转发器双身份的展示缓存。
	 */
	public static void syncDisplaysAfterAliasChanged(ServerLevel level, LinkNodeType type, long serial) {
		if (level == null || type == null || serial <= 0L) {
			return;
		}
		NodeAliasServerSupport.syncDisplaysAfterAliasChanged(level, type, serial);
		if (LinkSavedData.get(level).isRepeaterSerial(serial)) {
			NodeAliasServerSupport.syncDisplaysAfterAliasChanged(level, peerType(type), serial);
		}
	}

	private static LinkNodeType peerType(LinkNodeType type) {
		return type == LinkNodeType.CORE ? LinkNodeType.TRIGGER_SOURCE : LinkNodeType.CORE;
	}
}
