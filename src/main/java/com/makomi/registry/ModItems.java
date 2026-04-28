package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.ActivationMode;
import com.makomi.data.LinkFilterKind;
import com.makomi.item.GraphVisualEditorItem;
import com.makomi.item.ChunkActivatorBlockItem;
import com.makomi.item.DirectionalFaceEditorItem;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
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
		new Item(new Item.Properties())
	);

	public static final Item REDSTONE_LINK_COMPONENT = register(
		"redstone_link_component",
		new RedstoneLinkComponentItem(new Item.Properties())
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_WIRELESS_AGE_ICON = register(
		"adv_wireless_age_icon",
		new Item(new Item.Properties())
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_COME_FIND_ME_IN_THE_END_ICON = register(
		"adv_come_find_me_in_the_end_icon",
		new Item(new Item.Properties())
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_MASTER_STRATEGIST_ICON = register(
		"adv_master_strategist_icon",
		new Item(new Item.Properties())
	);

	/**
	 * 成就图标专用物品，不在创造模式分组中输出。
	 */
	public static final Item ADV_CONSTELLATION_ICON = register(
		"adv_constellation_icon",
		new Item(new Item.Properties())
	);

	public static final Item LINK_REDSTONE_CORE = register(
		"link_redstone_core",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_CORE, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_CORE_TRANSPARENT = register(
		"link_redstone_core_transparent",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item HIDE_CORE = register(
		"hide_core",
		new PairableBlockItem(ModBlocks.HIDE_CORE, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_DUST_CORE, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		new PairableBlockItem(ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT, new Item.Properties().stacksTo(1), LinkNodeType.CORE)
	);

	public static final Item LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		new PairableBlockItem(ModBlocks.LINK_TOGGLE_BUTTON, new Item.Properties().stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_SYNC_LEVER = register(
		"link_sync_lever",
		new PairableBlockItem(ModBlocks.LINK_SYNC_LEVER, new Item.Properties().stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_PUSH_BUTTON = register(
		"link_push_button",
		new PairableBlockItem(ModBlocks.LINK_PUSH_BUTTON, new Item.Properties().stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_TOGGLE_EMITTER = register(
		"link_toggle_emitter",
		new PairableBlockItem(ModBlocks.LINK_TOGGLE_EMITTER, new Item.Properties().stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		new PairableBlockItem(ModBlocks.LINK_PULSE_EMITTER, new Item.Properties().stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		new PairableBlockItem(ModBlocks.LINK_SYNC_EMITTER, new Item.Properties().stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item HIDE_SYNC_TRIGGER_SOURCE = register(
		"hide_sync_trigger_source",
		new PairableBlockItem(ModBlocks.HIDE_SYNC_TRIGGER_SOURCE, new Item.Properties().stacksTo(1), LinkNodeType.TRIGGER_SOURCE)
	);

	public static final Item LINK_SEND_FILTER = register(
		"link_send_filter",
		new LinkFilterBlockItem(ModBlocks.LINK_SEND_FILTER, new Item.Properties().stacksTo(1), LinkFilterKind.SEND)
	);

	public static final Item LINK_RECEIVE_FILTER = register(
		"link_receive_filter",
		new LinkFilterBlockItem(ModBlocks.LINK_RECEIVE_FILTER, new Item.Properties().stacksTo(1), LinkFilterKind.RECEIVE)
	);

	public static final Item LINK_CHUNK_ACTIVATOR = register(
		"link_chunk_activator",
		new ChunkActivatorBlockItem(ModBlocks.LINK_CHUNK_ACTIVATOR, new Item.Properties().stacksTo(1))
	);

	public static final Item LINK_REPEATER = register(
		"link_repeater",
		new RepeaterBlockItem(ModBlocks.LINK_REPEATER, new Item.Properties().stacksTo(1))
	);

	public static final Item REDSTONELINK_TOGGLE_LINKER = register(
		"redstonelink_toggle_linker",
		new LinkerItem(new Item.Properties().stacksTo(1), ActivationMode.TOGGLE)
	);

	public static final Item REDSTONELINK_PULSE_LINKER = register(
		"redstonelink_pulse_linker",
		new LinkerItem(new Item.Properties().stacksTo(1), ActivationMode.PULSE)
	);

	public static final Item REDSTONELINK_SYNC_LINKER = register(
		"redstonelink_sync_linker",
		new SyncLinkerItem(new Item.Properties().stacksTo(1))
	);

	public static final Item QUICK_LINK_TOOL = register(
		"quick_link_tool",
		new QuickLinkToolItem(new Item.Properties().stacksTo(1))
	);

	public static final Item SMART_GLASSES = register(
		"smart_glasses",
		new SmartGlassesItem(ArmorItem.Type.HELMET, new Item.Properties().stacksTo(1))
	);

	public static final Item SMART_NODE_CONTAINER = register(
		"smart_node_container",
		new SmartNodeContainerItem(new Item.Properties().stacksTo(1))
	);

	public static final Item GRAPH_VISUAL_EDITOR = register(
		"graph_visual_editor",
		new GraphVisualEditorItem(new Item.Properties().stacksTo(1))
	);

	public static final Item DIRECTIONAL_FACE_EDITOR = register(
		"directional_face_editor",
		new DirectionalFaceEditorItem(new Item.Properties().stacksTo(1))
	);

	public static final Item REDSTONELINK_STATUS_PANEL = register(
		"redstonelink_status_panel",
		new StatePanelToolItem(new Item.Properties().stacksTo(1))
	);

	private ModItems() {
	}

	private static <T extends Item> T register(String path, T item) {
		return Registry.register(BuiltInRegistries.ITEM, id(path), item);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
