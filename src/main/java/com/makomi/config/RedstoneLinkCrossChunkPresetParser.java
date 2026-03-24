package com.makomi.config;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * 跨区块白名单与 preset 解析器。
 */
final class RedstoneLinkCrossChunkPresetParser {
	private RedstoneLinkCrossChunkPresetParser() {
	}

	/**
	 * 解析跨区块类型集合配置。
	 */
	static Set<LinkNodeType> parseTypeSet(
		Properties props,
		String key,
		Set<LinkNodeType> defaults,
		LinkNodeSemantics.Role role
	) {
		String raw = props.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return Set.copyOf(defaults);
		}

		Set<LinkNodeType> parsedTypes = new HashSet<>();
		for (String token : raw.split("[,;\\s]+")) {
			if (token == null || token.isBlank()) {
				continue;
			}
			Optional<LinkNodeType> parsedType = resolveConfigTypeToken(key, token, token, role, null);
			if (parsedType.isEmpty()) {
				continue;
			}
			parsedTypes.add(parsedType.get());
		}
		if (parsedTypes.isEmpty()) {
			RedstoneLinkConfig.logger().warn("Config {} did not resolve any valid types; falling back to defaults", key);
			return Set.copyOf(defaults);
		}
		return Set.copyOf(parsedTypes);
	}

	/**
	 * 解析配置中的跨区块 preset 定义。
	 */
	static Map<String, RedstoneLinkConfig.CrossChunkPreset> parsePresets(
		Properties props,
		Set<LinkNodeType> allowedSourceTypes,
		Set<LinkNodeType> allowedTargetTypes
	) {
		final String keyPrefix = "crosschunk.preset.";
		Map<String, MutablePreset> mutablePresets = new HashMap<>();
		for (String propertyKey : props.stringPropertyNames()) {
			if (!propertyKey.startsWith(keyPrefix)) {
				continue;
			}
			String suffix = propertyKey.substring(keyPrefix.length());
			int splitIndex = suffix.lastIndexOf('.');
			if (splitIndex <= 0 || splitIndex >= suffix.length() - 1) {
				continue;
			}
			String presetName = suffix.substring(0, splitIndex).trim().toLowerCase(Locale.ROOT);
			String rolePart = suffix.substring(splitIndex + 1).trim().toLowerCase(Locale.ROOT);
			if (presetName.isEmpty()) {
				continue;
			}
			LinkNodeSemantics.Role role = switch (rolePart) {
				case "sources" -> LinkNodeSemantics.Role.SOURCE;
				case "targets" -> LinkNodeSemantics.Role.TARGET;
				default -> null;
			};
			if (role == null) {
				continue;
			}
			Set<LinkNodeType> allowedTypes = role == LinkNodeSemantics.Role.SOURCE
				? allowedSourceTypes
				: allowedTargetTypes;
			Map<LinkNodeType, Set<Long>> parsedBucket = parsePresetBucket(
				props.getProperty(propertyKey),
				propertyKey,
				role,
				allowedTypes
			);
			if (parsedBucket.isEmpty()) {
				continue;
			}
			MutablePreset mutablePreset = mutablePresets.computeIfAbsent(presetName, ignored -> new MutablePreset());
			Map<LinkNodeType, Set<Long>> mutableBucket = role == LinkNodeSemantics.Role.SOURCE
				? mutablePreset.sources
				: mutablePreset.targets;
			mergeBucket(mutableBucket, parsedBucket);
		}

		Map<String, RedstoneLinkConfig.CrossChunkPreset> snapshots = new HashMap<>();
		for (Map.Entry<String, MutablePreset> entry : mutablePresets.entrySet()) {
			Map<LinkNodeType, Set<Long>> sourceBucket = immutableBucket(entry.getValue().sources);
			Map<LinkNodeType, Set<Long>> targetBucket = immutableBucket(entry.getValue().targets);
			if (sourceBucket.isEmpty() && targetBucket.isEmpty()) {
				continue;
			}
			snapshots.put(entry.getKey(), new RedstoneLinkConfig.CrossChunkPreset(sourceBucket, targetBucket));
		}
		return snapshots.isEmpty() ? Map.of() : Map.copyOf(snapshots);
	}

	/**
	 * 合并所有 preset 的来源或目标桶。
	 */
	static Map<LinkNodeType, Set<Long>> mergePresetBuckets(
		Map<String, RedstoneLinkConfig.CrossChunkPreset> presets,
		LinkNodeSemantics.Role role
	) {
		if (presets.isEmpty()) {
			return Map.of();
		}
		Map<LinkNodeType, Set<Long>> mergedBucket = new HashMap<>();
		for (RedstoneLinkConfig.CrossChunkPreset preset : presets.values()) {
			Map<LinkNodeType, Set<Long>> roleBucket = role == LinkNodeSemantics.Role.SOURCE
				? preset.sources()
				: preset.targets();
			mergeBucket(mergedBucket, roleBucket);
		}
		return immutableBucket(mergedBucket);
	}

	/**
	 * 解析单个 preset 的来源或目标条目。
	 */
	private static Map<LinkNodeType, Set<Long>> parsePresetBucket(
		String raw,
		String key,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		if (raw == null || raw.isBlank()) {
			return Map.of();
		}

		Map<LinkNodeType, Set<Long>> parsedBucket = new HashMap<>();
		for (String token : raw.split("[,;\\s]+")) {
			if (token == null || token.isBlank()) {
				continue;
			}
			int splitIndex = token.indexOf(':');
			if (splitIndex <= 0 || splitIndex >= token.length() - 1) {
				RedstoneLinkConfig.logger().warn("Config {}={} has invalid format; expected type:serial, ignored", key, token);
				continue;
			}
			String typePart = token.substring(0, splitIndex);
			String serialPart = token.substring(splitIndex + 1);
			Optional<LinkNodeType> parsedType = resolveConfigTypeToken(key, token, typePart, role, allowedTypes);
			if (parsedType.isEmpty()) {
				continue;
			}

			long serial;
			try {
				serial = Long.parseLong(serialPart);
			} catch (NumberFormatException ex) {
				RedstoneLinkConfig.logger().warn("Config {}={} has an invalid serial integer, ignored", key, token);
				continue;
			}
			if (serial <= 0L) {
				RedstoneLinkConfig.logger().warn("Config {}={} must use a serial greater than 0, ignored", key, token);
				continue;
			}
			parsedBucket.computeIfAbsent(parsedType.get(), ignored -> new HashSet<>()).add(serial);
		}
		return immutableBucket(parsedBucket);
	}

	/**
	 * 按语义中转层解析并校验配置中的节点类型 token。
	 */
	private static Optional<LinkNodeType> resolveConfigTypeToken(
		String key,
		String tokenText,
		String rawType,
		LinkNodeSemantics.Role role,
		Set<LinkNodeType> allowedTypes
	) {
		var semanticResult = LinkNodeSemantics.resolveStrictTypeForRole(rawType, role, allowedTypes);
		if (semanticResult.isSuccess()) {
			return Optional.of(semanticResult.value());
		}
		switch (semanticResult.error()) {
			case ROLE_NOT_ALLOWED -> RedstoneLinkConfig.logger().warn(
				"Config {}={} does not match the required semantic role, ignored",
				key,
				tokenText
			);
			case CONFIG_NOT_ALLOWED -> RedstoneLinkConfig.logger().warn(
				"Config {}={} is not included in the allowed type list, ignored",
				key,
				tokenText
			);
			case INVALID_TYPE, NONE -> RedstoneLinkConfig.logger().warn(
				"Config {}={} contains an invalid type, ignored",
				key,
				tokenText
			);
		}
		return Optional.empty();
	}

	/**
	 * 将来源桶内容合并到目标桶。
	 */
	private static void mergeBucket(Map<LinkNodeType, Set<Long>> target, Map<LinkNodeType, Set<Long>> source) {
		for (Map.Entry<LinkNodeType, Set<Long>> entry : source.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isEmpty()) {
				continue;
			}
			target.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).addAll(entry.getValue());
		}
	}

	/**
	 * 将可变桶转换为不可变快照。
	 */
	private static Map<LinkNodeType, Set<Long>> immutableBucket(Map<LinkNodeType, Set<Long>> source) {
		if (source.isEmpty()) {
			return Map.of();
		}
		Map<LinkNodeType, Set<Long>> snapshot = new HashMap<>();
		for (Map.Entry<LinkNodeType, Set<Long>> entry : source.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isEmpty()) {
				continue;
			}
			snapshot.put(entry.getKey(), Set.copyOf(entry.getValue()));
		}
		return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
	}

	/**
	 * preset 解析过程中的可变中间态。
	 */
	private static final class MutablePreset {
		private final Map<LinkNodeType, Set<Long>> sources = new HashMap<>();
		private final Map<LinkNodeType, Set<Long>> targets = new HashMap<>();
	}
}
