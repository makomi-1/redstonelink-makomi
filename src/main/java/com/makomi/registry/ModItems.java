package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivationMode;
import com.makomi.data.LinkFilterKind;
import com.makomi.item.GraphVisualEditorItem;
import com.makomi.item.ChunkActivatorBlockItem;
import com.makomi.item.LinkerItem;
import com.makomi.item.LinkFilterBlockItem;
import com.makomi.item.PairableBlockItem;
import com.makomi.item.QuickLinkToolItem;
import com.makomi.item.RepeaterBlockItem;
import com.makomi.item.RedstoneLinkComponentItem;
import com.makomi.item.SmartGlassesItem;
import com.makomi.item.SmartNodeContainerItem;
import com.makomi.item.SyncLinkerItem;
import com.makomi.item.StatePanelToolItem;
import com.makomi.data.LinkNodeType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * 模组物品注册表。
 */
public final class ModItems {
	/**
	 * 创造模式分组图标专用物品，不在物品栏展示列表中输出。
	 */
	public static final Item RL_ICON = register(
		"rl-icon",
		new Item(itemProperties("rl-icon"))
	);

	public static final Item REDSTONE_LINK_COMPONENT = register(
		"redstone_link_component",
		new RedstoneLinkComponentItem(itemProperties("redstone_link_component"))
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_WIRELESS_AGE_ICON = register(
		"adv_wireless_age_icon",
		new Item(itemProperties("adv_wireless_age_icon"))
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_COME_FIND_ME_IN_THE_END_ICON = register(
		"adv_come_find_me_in_the_end_icon",
		new Item(itemProperties("adv_come_find_me_in_the_end_icon"))
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_MASTER_STRATEGIST_ICON = register(
		"adv_master_strategist_icon",
		new Item(itemProperties("adv_master_strategist_icon"))
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_CONSTELLATION_ICON = register(
		"adv_constellation_icon",
		new Item(itemProperties("adv_constellation_icon"))
	);

	public static final Item LINK_REDSTONE_CORE = register(
		"link_redstone_core",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_CORE, itemProperties("link_redstone_core").stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_CORE_TRANSPARENT = register(
		"link_redstone_core_transparent",
		new PairableBlockItem(
			ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT,
			itemProperties("link_redstone_core_transparent").stacksTo(1),
			LinkNodeType.CORE
		)
	);

	public static final Item LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_DUST_CORE, itemProperties("link_redstone_dust_core").stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		new PairableBlockItem(
			ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT,
			itemProperties("link_redstone_dust_core_transparent").stacksTo(1),
			LinkNodeType.CORE
		)
	);

	public static final Item LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		new PairableBlockItem(ModBlocks.LINK_TOGGLE_BUTTON, itemProperties("link_toggle_button").stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_SYNC_LEVER = register(
		"link_sync_lever",
		new PairableBlockItem(ModBlocks.LINK_SYNC_LEVER, itemProperties("link_sync_lever").stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_PUSH_BUTTON = register(
		"link_push_button",
		new PairableBlockItem(ModBlocks.LINK_PUSH_BUTTON, itemProperties("link_push_button").stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_TOGGLE_EMITTER = register(
		"link_toggle_emitter",
		new PairableBlockItem(ModBlocks.LINK_TOGGLE_EMITTER, itemProperties("link_toggle_emitter").stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		new PairableBlockItem(ModBlocks.LINK_PULSE_EMITTER, itemProperties("link_pulse_emitter").stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		new PairableBlockItem(ModBlocks.LINK_SYNC_EMITTER, itemProperties("link_sync_emitter").stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_SEND_FILTER = register(
		"link_send_filter",
		new LinkFilterBlockItem(ModBlocks.LINK_SEND_FILTER, itemProperties("link_send_filter").stacksTo(1), LinkFilterKind.SEND)
	);

	public static final Item LINK_RECEIVE_FILTER = register(
		"link_receive_filter",
		new LinkFilterBlockItem(ModBlocks.LINK_RECEIVE_FILTER, itemProperties("link_receive_filter").stacksTo(1), LinkFilterKind.RECEIVE)
	);

	public static final Item LINK_CHUNK_ACTIVATOR = register(
		"link_chunk_activator",
		new ChunkActivatorBlockItem(ModBlocks.LINK_CHUNK_ACTIVATOR, itemProperties("link_chunk_activator").stacksTo(1))
	);

	public static final Item LINK_REPEATER = register(
		"link_repeater",
		new RepeaterBlockItem(ModBlocks.LINK_REPEATER, itemProperties("link_repeater").stacksTo(1))
	);

	public static final Item REDSTONELINK_TOGGLE_LINKER = register(
		"redstonelink_toggle_linker",
		new LinkerItem(itemProperties("redstonelink_toggle_linker").stacksTo(1), ActivationMode.TOGGLE)
	);

	public static final Item REDSTONELINK_PULSE_LINKER = register(
		"redstonelink_pulse_linker",
		new LinkerItem(itemProperties("redstonelink_pulse_linker").stacksTo(1), ActivationMode.PULSE)
	);

	public static final Item REDSTONELINK_SYNC_LINKER = register(
		"redstonelink_sync_linker",
		new SyncLinkerItem(itemProperties("redstonelink_sync_linker").stacksTo(1))
	);

	public static final Item QUICK_LINK_TOOL = register(
		"quick_link_tool",
		new QuickLinkToolItem(itemProperties("quick_link_tool").stacksTo(1))
	);

	public static final Item SMART_GLASSES = register(
		"smart_glasses",
		new SmartGlassesItem(itemProperties("smart_glasses").stacksTo(1))
	);

	public static final Item SMART_NODE_CONTAINER = register(
		"smart_node_container",
		new SmartNodeContainerItem(itemProperties("smart_node_container").stacksTo(1))
	);

	public static final Item GRAPH_VISUAL_EDITOR = register(
		"graph_visual_editor",
		new GraphVisualEditorItem(itemProperties("graph_visual_editor").stacksTo(1))
	);

	public static final Item REDSTONELINK_STATUS_PANEL = register(
		"redstonelink_status_panel",
		new StatePanelToolItem(itemProperties("redstonelink_status_panel").stacksTo(1))
	);

	private ModItems() {
	}

	private static <T extends Item> T register(String path, T item) {
		return Registry.register(BuiltInRegistries.ITEM, id(path), item);
	}

	/**
	 * 1.21.11 起物品构造阶段就会读取 item id，因此必须在 new Item(...) 前先注入注册键。
	 */
	private static Item.Properties itemProperties(String path) {
		return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(path)));
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
