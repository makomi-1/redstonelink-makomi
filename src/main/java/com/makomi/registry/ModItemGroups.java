package com.makomi.registry;

import com.makomi.RedstoneLink;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ItemLike;

/**
 * 模组创造模式物品栏分组注册。
 */
public final class ModItemGroups {
	private static final int GROUP_WIDTH = 9;

	public static final CreativeModeTab REDSTONELINK = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB,
		id("redstonelink"),
		FabricItemGroup
			.builder()
			.title(Component.translatable("itemGroup.redstonelink"))
			.icon(() -> new ItemStack(ModItems.RL_ICON))
			.displayItems((parameters, output) -> {
				int[] spacerCounter = { 0 };
				appendSectionWithSeparator(
					output,
					spacerCounter,
					ModItems.CREATIVE_SECTION_TOOLS_MATERIALS,
					new ItemLike[] {
						ModItems.REDSTONELINK_TOGGLE_LINKER,
						ModItems.REDSTONELINK_PULSE_LINKER,
						ModItems.REDSTONELINK_SYNC_LINKER,
						ModItems.QUICK_LINK_TOOL,
						ModItems.SMART_GLASSES,
						ModItems.SMART_NODE_CONTAINER,
						ModItems.GRAPH_VISUAL_EDITOR,
						ModItems.DIRECTIONAL_FACE_EDITOR,
						ModItems.REDSTONELINK_STATUS_PANEL,
						ModItems.REDSTONE_LINK_COMPONENT
					}
				);
				appendSectionWithSeparator(
					output,
					spacerCounter,
					ModItems.CREATIVE_SECTION_PROTOTYPES,
					new ItemLike[] {
						ModItems.LINK_REDSTONE_CORE,
						ModItems.LINK_REDSTONE_DUST_CORE,
						ModItems.LINK_TOGGLE_BUTTON,
						ModItems.LINK_SYNC_LEVER,
						ModItems.LINK_PUSH_BUTTON,
						ModItems.LINK_TOGGLE_EMITTER,
						ModItems.LINK_PULSE_EMITTER,
						ModItems.LINK_SYNC_EMITTER,
						ModItems.LINK_SEND_FILTER,
						ModItems.LINK_RECEIVE_FILTER,
						ModItems.LINK_CHUNK_ACTIVATOR,
						ModItems.LINK_REPEATER,
						ModItems.WIRELESS_CONVERTER
					}
				);
				appendSectionWithSeparator(
					output,
					spacerCounter,
					ModItems.CREATIVE_SECTION_VARIANTS,
					new ItemLike[] {
						ModItems.LINK_REDSTONE_CORE_TRANSPARENT,
						ModItems.HIDE_CORE,
						ModItems.LINK_REDSTONE_DUST_CORE_TRANSPARENT,
						ModItems.HIDE_TOGGLE_EMITTER,
						ModItems.HIDE_PULSE_EMITTER,
						ModItems.HIDE_SYNC_TRIGGER_SOURCE,
						ModItems.HIDE_SEND_FILTER,
						ModItems.HIDE_RECEIVE_FILTER,
						ModItems.HIDE_CHUNK_ACTIVATOR,
						ModItems.HIDE_REPEATER
					}
				);
				appendSectionWithSeparator(
					output,
					spacerCounter,
					ModItems.CREATIVE_SECTION_WIRELESS_PRODUCTS,
					new ItemLike[] {
						ModItems.WIRELESS_LEVER,
						ModItems.WIRELESS_STONE_BUTTON,
						ModItems.WIRELESS_OAK_BUTTON,
						ModItems.WIRELESS_STONE_PRESSURE_PLATE,
						ModItems.WIRELESS_OAK_PRESSURE_PLATE,
						ModItems.WIRELESS_LIGHT_WEIGHTED_PRESSURE_PLATE,
						ModItems.WIRELESS_HEAVY_WEIGHTED_PRESSURE_PLATE,
						ModItems.WIRELESS_PISTON,
						ModItems.WIRELESS_STICKY_PISTON,
						ModItems.WIRELESS_REDSTONE_BLOCK,
						ModItems.WIRELESS_REDSTONE_LAMP,
						ModItems.WIRELESS_SEA_LANTERN,
						ModItems.WIRELESS_LANTERN,
						ModItems.WIRELESS_SOUL_LANTERN,
						ModItems.WIRELESS_JACK_O_LANTERN,
						ModItems.WIRELESS_GLOWSTONE,
						ModItems.WIRELESS_END_ROD,
						ModItems.WIRELESS_OCHRE_FROGLIGHT,
						ModItems.WIRELESS_VERDANT_FROGLIGHT,
						ModItems.WIRELESS_PEARLESCENT_FROGLIGHT,
						ModItems.WIRELESS_OAK_DOOR,
						ModItems.WIRELESS_IRON_DOOR,
						ModItems.WIRELESS_OAK_TRAPDOOR,
						ModItems.WIRELESS_IRON_TRAPDOOR,
						ModItems.WIRELESS_OAK_FENCE_GATE,
						ModItems.WIRELESS_NOTE_BLOCK,
						ModItems.WIRELESS_TNT
					}
				);
			})
			.build()
	);

	private ModItemGroups() {
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}

	/**
	 * 以“整行隔离带 + 中央分类标志物 + 组内容矩形包围盒”的方式输出一组创造标签内容。
	 */
	private static void appendSectionWithSeparator(
		CreativeModeTab.Output output,
		int[] spacerCounter,
		ItemLike header,
		ItemLike[] items
	) {
		appendSeparatorRow(output, spacerCounter, header);
		appendGroupedItems(output, spacerCounter, items);
	}

	/**
	 * 输出一整行隔离带：只有中央一格放分类标志物，其余位置全部用空白占位。
	 */
	private static void appendSeparatorRow(CreativeModeTab.Output output, int[] spacerCounter, ItemLike header) {
		int centerIndex = GROUP_WIDTH / 2;
		for (int index = 0; index < GROUP_WIDTH; index++) {
			if (index == centerIndex) {
				output.accept(header, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
				continue;
			}
			output.accept(createUniqueSeparatorSpacerStack(spacerCounter), CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
		}
	}

	/**
	 * 按固定宽度输出当前组内容，并在行尾补空白占位，保证下一组总从新行开始。
	 */
	private static void appendGroupedItems(CreativeModeTab.Output output, int[] spacerCounter, ItemLike[] items) {
		if (items == null || items.length == 0) {
			appendFullSpacerRow(output, spacerCounter);
			return;
		}
		int columnCount = 0;
		for (ItemLike itemLike : items) {
			output.accept(itemLike, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
			columnCount++;
			if (columnCount == GROUP_WIDTH) {
				columnCount = 0;
			}
		}
		if (columnCount == 0) {
			return;
		}
		for (int index = columnCount; index < GROUP_WIDTH; index++) {
			output.accept(createUniqueGroupFillSpacerStack(spacerCounter), CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
		}
	}

	/**
	 * 输出一整行空白占位，用于空组兜底。
	 */
	private static void appendFullSpacerRow(CreativeModeTab.Output output, int[] spacerCounter) {
		for (int index = 0; index < GROUP_WIDTH; index++) {
			output.accept(createUniqueGroupFillSpacerStack(spacerCounter), CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
		}
	}

	/**
	 * 构建一个带唯一标识的隔离带空白占位栈。
	 * <p>
	 * 原版创造标签会拒绝把“完全相同”的 ItemStack 重复加入；
	 * 因此隔离带里的每一格空白都必须携带不同的自定义数据。
	 * </p>
	 */
	private static ItemStack createUniqueSeparatorSpacerStack(int[] spacerCounter) {
		ItemStack spacerStack = new ItemStack(ModItems.CREATIVE_SECTION_SPACER);
		int uniqueId = spacerCounter[0]++;
		CustomData.update(DataComponents.CUSTOM_DATA, spacerStack, tag -> tag.putInt("creative_spacer_uid", uniqueId));
		return spacerStack;
	}

	/**
	 * 构建一个带唯一标识的组内补齐空白占位栈。
	 * <p>
	 * 组内补齐空白与隔离带空白使用不同物品，
	 * 以便分别接入不同的 item 贴图资源。
	 * </p>
	 */
	private static ItemStack createUniqueGroupFillSpacerStack(int[] spacerCounter) {
		ItemStack spacerStack = new ItemStack(ModItems.CREATIVE_SECTION_EMPTY);
		int uniqueId = spacerCounter[0]++;
		CustomData.update(DataComponents.CUSTOM_DATA, spacerStack, tag -> tag.putInt("creative_spacer_uid", uniqueId));
		return spacerStack;
	}
}
