package com.makomi.data;

import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * GUI 显示上下文 token 归一化工具。
 * <p>
 * 统一把物品/方块注册名、翻译键或网络下发的原始 token 收敛为稳定短 token，
 * 供客户端标题、图标和主题细节做可复用映射。
 * </p>
 */
public final class LinkGuiDisplayContext {
	public static final String TRIGGER_SOURCE = "trigger_source";
	public static final String CORE = "core";
	public static final String LINK_REDSTONE_CORE = "link_redstone_core";
	public static final String LINK_REDSTONE_CORE_TRANSPARENT = "link_redstone_core_transparent";
	public static final String HIDE_CORE = "hide_core";
	public static final String LINK_REDSTONE_DUST_CORE = "link_redstone_dust_core";
	public static final String LINK_REDSTONE_DUST_CORE_TRANSPARENT = "link_redstone_dust_core_transparent";
	public static final String LINK_TOGGLE_BUTTON = "link_toggle_button";
	public static final String LINK_PUSH_BUTTON = "link_push_button";
	public static final String LINK_SYNC_LEVER = "link_sync_lever";
	public static final String LINK_TOGGLE_EMITTER = "link_toggle_emitter";
	public static final String LINK_PULSE_EMITTER = "link_pulse_emitter";
	public static final String LINK_SYNC_EMITTER = "link_sync_emitter";
	public static final String HIDE_TOGGLE_EMITTER = "hide_toggle_emitter";
	public static final String HIDE_PULSE_EMITTER = "hide_pulse_emitter";
	public static final String HIDE_SYNC_TRIGGER_SOURCE = "hide_sync_trigger_source";
	public static final String LINK_REPEATER = "link_repeater";
	public static final String REDSTONELINK_TOGGLE_LINKER = "redstonelink_toggle_linker";
	public static final String REDSTONELINK_PULSE_LINKER = "redstonelink_pulse_linker";
	public static final String REDSTONELINK_SYNC_LINKER = "redstonelink_sync_linker";
	public static final String WIRELESS_LEVER = "wireless_lever";
	public static final String WIRELESS_STONE_BUTTON = "wireless_stone_button";
	public static final String WIRELESS_STONE_PRESSURE_PLATE = "wireless_stone_pressure_plate";
	public static final String WIRELESS_OAK_BUTTON = "wireless_oak_button";
	public static final String WIRELESS_OAK_PRESSURE_PLATE = "wireless_oak_pressure_plate";
	public static final String WIRELESS_LIGHT_WEIGHTED_PRESSURE_PLATE = "wireless_light_weighted_pressure_plate";
	public static final String WIRELESS_HEAVY_WEIGHTED_PRESSURE_PLATE = "wireless_heavy_weighted_pressure_plate";
	public static final String WIRELESS_PISTON = "wireless_piston";
	public static final String WIRELESS_STICKY_PISTON = "wireless_sticky_piston";
	public static final String WIRELESS_REDSTONE_LAMP = "wireless_redstone_lamp";
	public static final String WIRELESS_SEA_LANTERN = "wireless_sea_lantern";
	public static final String WIRELESS_LANTERN = "wireless_lantern";
	public static final String WIRELESS_SOUL_LANTERN = "wireless_soul_lantern";
	public static final String WIRELESS_JACK_O_LANTERN = "wireless_jack_o_lantern";
	public static final String WIRELESS_GLOWSTONE = "wireless_glowstone";
	public static final String WIRELESS_END_ROD = "wireless_end_rod";
	public static final String WIRELESS_OCHRE_FROGLIGHT = "wireless_ochre_froglight";
	public static final String WIRELESS_VERDANT_FROGLIGHT = "wireless_verdant_froglight";
	public static final String WIRELESS_PEARLESCENT_FROGLIGHT = "wireless_pearlescent_froglight";
	public static final String WIRELESS_OAK_DOOR = "wireless_oak_door";
	public static final String WIRELESS_IRON_DOOR = "wireless_iron_door";
	public static final String WIRELESS_OAK_TRAPDOOR = "wireless_oak_trapdoor";
	public static final String WIRELESS_IRON_TRAPDOOR = "wireless_iron_trapdoor";
	public static final String WIRELESS_OAK_FENCE_GATE = "wireless_oak_fence_gate";
	public static final String WIRELESS_NOTE_BLOCK = "wireless_note_block";
	public static final String WIRELESS_TNT = "wireless_tnt";
	public static final String WIRELESS_REDSTONE_BLOCK = "wireless_redstone_block";

	private LinkGuiDisplayContext() {
	}

	/**
	 * 归一化配对界面上下文 token。
	 *
	 * @param rawToken 原始 token、翻译键或注册路径
	 * @param fallbackType 兜底节点类型
	 * @return 稳定短 token
	 */
	public static String normalizePairingContextToken(String rawToken, LinkNodeType fallbackType) {
		String normalized = normalizeKnownToken(rawToken);
		if (normalized != null) {
			return normalized;
		}
		return fallbackPairingToken(fallbackType);
	}

	/**
	 * 根据物品栈解析配对界面上下文 token。
	 *
	 * @param stack 物品栈
	 * @param fallbackType 兜底节点类型
	 * @return 稳定短 token
	 */
	public static String resolvePairingContextToken(ItemStack stack, LinkNodeType fallbackType) {
		if (stack == null || stack.isEmpty()) {
			return fallbackPairingToken(fallbackType);
		}
		String registryPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		return normalizePairingContextToken(registryPath, fallbackType);
	}

	/**
	 * 根据方块解析配对界面上下文 token。
	 *
	 * @param block 方块实例
	 * @param fallbackType 兜底节点类型
	 * @return 稳定短 token
	 */
	public static String resolvePairingContextToken(Block block, LinkNodeType fallbackType) {
		if (block == null) {
			return fallbackPairingToken(fallbackType);
		}
		String registryPath = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return normalizePairingContextToken(registryPath, fallbackType);
	}

	/**
	 * 根据来源节点类型返回兜底配对 token。
	 *
	 * @param sourceType 节点类型
	 * @return `trigger_source/core`
	 */
	public static String fallbackPairingToken(LinkNodeType sourceType) {
		return sourceType == LinkNodeType.CORE ? CORE : TRIGGER_SOURCE;
	}

	/**
	 * 解析已知短 token；未知值返回 `null`。
	 */
	private static String normalizeKnownToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return null;
		}
		String normalized = rawToken.trim().toLowerCase(Locale.ROOT);
		int namespaceSeparator = normalized.indexOf(':');
		if (namespaceSeparator >= 0 && namespaceSeparator + 1 < normalized.length()) {
			normalized = normalized.substring(namespaceSeparator + 1);
		}
		if (normalized.startsWith("item.redstonelink.")) {
			normalized = normalized.substring("item.redstonelink.".length());
		}
		if (normalized.startsWith("block.redstonelink.")) {
			normalized = normalized.substring("block.redstonelink.".length());
		}

		return switch (normalized) {
			case TRIGGER_SOURCE -> TRIGGER_SOURCE;
			case CORE -> CORE;
			case LINK_REDSTONE_CORE -> LINK_REDSTONE_CORE;
			case LINK_REDSTONE_CORE_TRANSPARENT -> LINK_REDSTONE_CORE_TRANSPARENT;
			case HIDE_CORE -> HIDE_CORE;
			case LINK_REDSTONE_DUST_CORE -> LINK_REDSTONE_DUST_CORE;
			case LINK_REDSTONE_DUST_CORE_TRANSPARENT -> LINK_REDSTONE_DUST_CORE_TRANSPARENT;
			case LINK_TOGGLE_BUTTON -> LINK_TOGGLE_BUTTON;
			case LINK_PUSH_BUTTON -> LINK_PUSH_BUTTON;
			case LINK_SYNC_LEVER -> LINK_SYNC_LEVER;
			case LINK_TOGGLE_EMITTER -> LINK_TOGGLE_EMITTER;
			case LINK_PULSE_EMITTER -> LINK_PULSE_EMITTER;
			case LINK_SYNC_EMITTER -> LINK_SYNC_EMITTER;
			case HIDE_TOGGLE_EMITTER -> HIDE_TOGGLE_EMITTER;
			case HIDE_PULSE_EMITTER -> HIDE_PULSE_EMITTER;
			case HIDE_SYNC_TRIGGER_SOURCE -> HIDE_SYNC_TRIGGER_SOURCE;
			case LINK_REPEATER -> LINK_REPEATER;
			case REDSTONELINK_TOGGLE_LINKER -> REDSTONELINK_TOGGLE_LINKER;
			case REDSTONELINK_PULSE_LINKER -> REDSTONELINK_PULSE_LINKER;
			case REDSTONELINK_SYNC_LINKER -> REDSTONELINK_SYNC_LINKER;
			case WIRELESS_LEVER -> WIRELESS_LEVER;
			case WIRELESS_STONE_BUTTON -> WIRELESS_STONE_BUTTON;
			case WIRELESS_STONE_PRESSURE_PLATE -> WIRELESS_STONE_PRESSURE_PLATE;
			case WIRELESS_OAK_BUTTON -> WIRELESS_OAK_BUTTON;
			case WIRELESS_OAK_PRESSURE_PLATE -> WIRELESS_OAK_PRESSURE_PLATE;
			case WIRELESS_LIGHT_WEIGHTED_PRESSURE_PLATE -> WIRELESS_LIGHT_WEIGHTED_PRESSURE_PLATE;
			case WIRELESS_HEAVY_WEIGHTED_PRESSURE_PLATE -> WIRELESS_HEAVY_WEIGHTED_PRESSURE_PLATE;
			case WIRELESS_PISTON -> WIRELESS_PISTON;
			case WIRELESS_STICKY_PISTON -> WIRELESS_STICKY_PISTON;
			case WIRELESS_REDSTONE_LAMP -> WIRELESS_REDSTONE_LAMP;
			case WIRELESS_SEA_LANTERN -> WIRELESS_SEA_LANTERN;
			case WIRELESS_LANTERN -> WIRELESS_LANTERN;
			case WIRELESS_SOUL_LANTERN -> WIRELESS_SOUL_LANTERN;
			case WIRELESS_JACK_O_LANTERN -> WIRELESS_JACK_O_LANTERN;
			case WIRELESS_GLOWSTONE -> WIRELESS_GLOWSTONE;
			case WIRELESS_END_ROD -> WIRELESS_END_ROD;
			case WIRELESS_OCHRE_FROGLIGHT -> WIRELESS_OCHRE_FROGLIGHT;
			case WIRELESS_VERDANT_FROGLIGHT -> WIRELESS_VERDANT_FROGLIGHT;
			case WIRELESS_PEARLESCENT_FROGLIGHT -> WIRELESS_PEARLESCENT_FROGLIGHT;
			case WIRELESS_OAK_DOOR -> WIRELESS_OAK_DOOR;
			case WIRELESS_IRON_DOOR -> WIRELESS_IRON_DOOR;
			case WIRELESS_OAK_TRAPDOOR -> WIRELESS_OAK_TRAPDOOR;
			case WIRELESS_IRON_TRAPDOOR -> WIRELESS_IRON_TRAPDOOR;
			case WIRELESS_OAK_FENCE_GATE -> WIRELESS_OAK_FENCE_GATE;
			case WIRELESS_NOTE_BLOCK -> WIRELESS_NOTE_BLOCK;
			case WIRELESS_TNT -> WIRELESS_TNT;
			case WIRELESS_REDSTONE_BLOCK -> WIRELESS_REDSTONE_BLOCK;
			default -> null;
		};
	}
}
