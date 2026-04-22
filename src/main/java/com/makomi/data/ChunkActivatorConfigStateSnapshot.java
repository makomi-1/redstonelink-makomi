package com.makomi.data;

import java.util.Optional;

/**
 * 区块激活器完整配置快照。
 * <p>
 * 同时保留 `triggerSource/core` 两套配置，并记录当前生效的作用类型。
 * </p>
 */
public record ChunkActivatorConfigStateSnapshot(
	LinkNodeType activeType,
	ChunkActivatorConfigSnapshot triggerSourceConfig,
	ChunkActivatorConfigSnapshot coreConfig
) {
	public ChunkActivatorConfigStateSnapshot {
		activeType = normalizeType(activeType);
		triggerSourceConfig = normalizeConfig(triggerSourceConfig);
		coreConfig = normalizeConfig(coreConfig);
	}

	/**
	 * 读取当前生效作用类型对应的配置。
	 */
	public ChunkActivatorConfigSnapshot activeConfig() {
		return configFor(activeType);
	}

	/**
	 * 按作用类型读取配置。
	 */
	public ChunkActivatorConfigSnapshot configFor(LinkNodeType type) {
		return normalizeType(type) == LinkNodeType.CORE ? coreConfig : triggerSourceConfig;
	}

	/**
	 * 以指定作用类型替换对应配置。
	 */
	public ChunkActivatorConfigStateSnapshot withConfig(LinkNodeType type, ChunkActivatorConfigSnapshot configSnapshot) {
		LinkNodeType normalizedType = normalizeType(type);
		ChunkActivatorConfigSnapshot normalizedConfig = normalizeConfig(configSnapshot);
		return normalizedType == LinkNodeType.CORE
			? new ChunkActivatorConfigStateSnapshot(activeType, triggerSourceConfig, normalizedConfig)
			: new ChunkActivatorConfigStateSnapshot(activeType, normalizedConfig, coreConfig);
	}

	/**
	 * 以指定作用类型替换当前生效类型。
	 */
	public ChunkActivatorConfigStateSnapshot withActiveType(LinkNodeType type) {
		return new ChunkActivatorConfigStateSnapshot(normalizeType(type), triggerSourceConfig, coreConfig);
	}

	/**
	 * 规范化区块激活器可接受的作用类型。
	 */
	public static LinkNodeType normalizeType(LinkNodeType type) {
		return type == LinkNodeType.CORE ? LinkNodeType.CORE : LinkNodeType.TRIGGER_SOURCE;
	}

	/**
	 * 解析作用类型 token。
	 */
	public static Optional<LinkNodeType> tryParseTypeToken(String rawToken) {
		return LinkNodeSemantics.tryParseCanonicalType(rawToken).map(ChunkActivatorConfigStateSnapshot::normalizeType);
	}

	/**
	 * 输出作用类型稳定 token。
	 */
	public static String toTypeToken(LinkNodeType type) {
		return LinkNodeSemantics.toSemanticName(normalizeType(type));
	}

	private static ChunkActivatorConfigSnapshot normalizeConfig(ChunkActivatorConfigSnapshot configSnapshot) {
		return configSnapshot == null ? new ChunkActivatorConfigSnapshot("", ChunkActivatorMode.FORCE_LOAD) : configSnapshot;
	}
}
