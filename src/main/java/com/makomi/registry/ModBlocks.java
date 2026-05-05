package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.LinkCoreBlock;
import com.makomi.block.HideChunkActivatorBlock;
import com.makomi.block.HideCoreBlock;
import com.makomi.block.HidePulseEmitterBlock;
import com.makomi.block.HideReceiveFilterBlock;
import com.makomi.block.HideRepeaterBlock;
import com.makomi.block.HideSendFilterBlock;
import com.makomi.block.HideSyncTriggerSourceBlock;
import com.makomi.block.HideToggleEmitterBlock;
import com.makomi.block.LinkChunkActivatorBlock;
import com.makomi.block.LinkPulseEmitterBlock;
import com.makomi.block.LinkPulseButtonBlock;
import com.makomi.block.LinkReceiveFilterBlock;
import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.block.LinkRepeaterBlock;
import com.makomi.block.LinkSendFilterBlock;
import com.makomi.block.LinkSyncEmitterBlock;
import com.makomi.block.LinkTransparentCoreBlock;
import com.makomi.block.LinkTransparentRedstoneDustCoreBlock;
import com.makomi.block.LinkToggleEmitterBlock;
import com.makomi.block.LinkToggleButtonBlock;
import com.makomi.block.LinkSyncLeverBlock;
import com.makomi.block.WirelessConverterBlock;
import com.makomi.block.WirelessEndRodBlock;
import com.makomi.block.WirelessFroglightBlock;
import com.makomi.block.WirelessGlowstoneBlock;
import com.makomi.block.WirelessIronDoorBlock;
import com.makomi.block.WirelessFenceGateCoreBlock;
import com.makomi.block.WirelessLanternBlock;
import com.makomi.block.WirelessLeverBlock;
import com.makomi.block.WirelessLitPumpkinBlock;
import com.makomi.block.WirelessNoteBlock;
import com.makomi.block.WirelessPistonBlock;
import com.makomi.block.WirelessPistonHeadBlock;
import com.makomi.block.WirelessRedstoneBlock;
import com.makomi.block.WirelessRedstoneLampBlock;
import com.makomi.block.WirelessSeaLanternBlock;
import com.makomi.block.WirelessSoulLanternBlock;
import com.makomi.block.WirelessStoneButtonBlock;
import com.makomi.block.WirelessStonePressurePlateBlock;
import com.makomi.block.WirelessSyncButtonBlock;
import com.makomi.block.WirelessSyncPressurePlateBlock;
import com.makomi.block.WirelessTntBlock;
import com.makomi.block.WirelessTrapdoorCoreBlock;
import com.makomi.block.WirelessWeightedPressurePlateBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.material.PushReaction;

/**
 * 模组方块注册表。
 */
public final class ModBlocks {
	public static final LinkCoreBlock LINK_REDSTONE_CORE = register(
		"link_redstone_core",
		new LinkCoreBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK).lightLevel(state -> 0).noOcclusion()
		)
	);

	public static final LinkTransparentCoreBlock LINK_REDSTONE_CORE_TRANSPARENT = register(
		"link_redstone_core_transparent",
		new LinkTransparentCoreBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK)
				.lightLevel(state -> 0)
				// 显式声明“类玻璃”行为，避免仅依赖 noOcclusion 的隐式推断。
				.noOcclusion()
				.isViewBlocking((state, level, pos) -> false)
				.isSuffocating((state, level, pos) -> false)
				.isRedstoneConductor((state, level, pos) -> false)
				.isValidSpawn((state, level, pos, entityType) -> false)
		)
	);

	public static final HideCoreBlock HIDE_CORE = register(
		"hide_core",
		new HideCoreBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK)
				.lightLevel(state -> 0)
				.noOcclusion()
				.noCollission()
				.isViewBlocking((state, level, pos) -> false)
				.isSuffocating((state, level, pos) -> false)
				.isRedstoneConductor((state, level, pos) -> false)
				.isValidSpawn((state, level, pos, entityType) -> false)
		)
	);

	public static final LinkToggleButtonBlock LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		new LinkToggleButtonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BUTTON))
	);

	public static final LinkSyncLeverBlock LINK_SYNC_LEVER = register(
		"link_sync_lever",
		new LinkSyncLeverBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.LEVER))
	);

	public static final LinkPulseButtonBlock LINK_PUSH_BUTTON = register(
		"link_push_button",
		new LinkPulseButtonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_BUTTON))
	);

	public static final LinkToggleEmitterBlock LINK_TOGGLE_EMITTER = register(
		"link_toggle_emitter",
		new LinkToggleEmitterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final HideToggleEmitterBlock HIDE_TOGGLE_EMITTER = register(
		"hide_toggle_emitter",
		new HideToggleEmitterBlock(createHideObserverLikeProperties())
	);

	public static final LinkPulseEmitterBlock LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		new LinkPulseEmitterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final HidePulseEmitterBlock HIDE_PULSE_EMITTER = register(
		"hide_pulse_emitter",
		new HidePulseEmitterBlock(createHideObserverLikeProperties())
	);

	public static final LinkSyncEmitterBlock LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		new LinkSyncEmitterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final HideSyncTriggerSourceBlock HIDE_SYNC_TRIGGER_SOURCE = register(
		"hide_sync_trigger_source",
		new HideSyncTriggerSourceBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER)
				.noOcclusion()
				.noCollission()
				.isViewBlocking((state, level, pos) -> false)
				.isSuffocating((state, level, pos) -> false)
				.isRedstoneConductor((state, level, pos) -> false)
				.isValidSpawn((state, level, pos, entityType) -> false)
		)
	);

	public static final LinkSendFilterBlock LINK_SEND_FILTER = register(
		"link_send_filter",
		new LinkSendFilterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final HideSendFilterBlock HIDE_SEND_FILTER = register(
		"hide_send_filter",
		new HideSendFilterBlock(createHideObserverLikeProperties())
	);

	public static final LinkReceiveFilterBlock LINK_RECEIVE_FILTER = register(
		"link_receive_filter",
		new LinkReceiveFilterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK).noOcclusion())
	);

	public static final HideReceiveFilterBlock HIDE_RECEIVE_FILTER = register(
		"hide_receive_filter",
		new HideReceiveFilterBlock(createHideRedstoneBlockLikeProperties())
	);

	public static final LinkChunkActivatorBlock LINK_CHUNK_ACTIVATOR = register(
		"link_chunk_activator",
		new LinkChunkActivatorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final HideChunkActivatorBlock HIDE_CHUNK_ACTIVATOR = register(
		"hide_chunk_activator",
		new HideChunkActivatorBlock(createHideObserverLikeProperties())
	);

	public static final LinkRepeaterBlock LINK_REPEATER = register(
		"link_repeater",
		new LinkRepeaterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final HideRepeaterBlock HIDE_REPEATER = register(
		"hide_repeater",
		new HideRepeaterBlock(createHideObserverLikeProperties())
	);

	public static final LinkRedstoneDustCoreBlock LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		new LinkRedstoneDustCoreBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_WIRE))
	);

	public static final LinkTransparentRedstoneDustCoreBlock LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		new LinkTransparentRedstoneDustCoreBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_WIRE))
	);

	public static final WirelessConverterBlock WIRELESS_CONVERTER = register(
		"wireless_converter",
		new WirelessConverterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.FURNACE))
	);

	public static final WirelessLeverBlock WIRELESS_LEVER = register(
		"wireless_lever",
		new WirelessLeverBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.LEVER).pushReaction(PushReaction.NORMAL))
	);

	public static final WirelessStoneButtonBlock WIRELESS_STONE_BUTTON = register(
		"wireless_stone_button",
		new WirelessStoneButtonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BUTTON).pushReaction(PushReaction.NORMAL))
	);

	public static final WirelessSyncButtonBlock WIRELESS_OAK_BUTTON = register(
		"wireless_oak_button",
		new WirelessSyncButtonBlock(
			BlockSetType.OAK,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_SPRUCE_BUTTON = register(
		"wireless_spruce_button",
		new WirelessSyncButtonBlock(
			BlockSetType.SPRUCE,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_BIRCH_BUTTON = register(
		"wireless_birch_button",
		new WirelessSyncButtonBlock(
			BlockSetType.BIRCH,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.BIRCH_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_JUNGLE_BUTTON = register(
		"wireless_jungle_button",
		new WirelessSyncButtonBlock(
			BlockSetType.JUNGLE,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.JUNGLE_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_ACACIA_BUTTON = register(
		"wireless_acacia_button",
		new WirelessSyncButtonBlock(
			BlockSetType.ACACIA,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.ACACIA_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_CHERRY_BUTTON = register(
		"wireless_cherry_button",
		new WirelessSyncButtonBlock(
			BlockSetType.CHERRY,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.CHERRY_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_DARK_OAK_BUTTON = register(
		"wireless_dark_oak_button",
		new WirelessSyncButtonBlock(
			BlockSetType.DARK_OAK,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.DARK_OAK_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_MANGROVE_BUTTON = register(
		"wireless_mangrove_button",
		new WirelessSyncButtonBlock(
			BlockSetType.MANGROVE,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.MANGROVE_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_BAMBOO_BUTTON = register(
		"wireless_bamboo_button",
		new WirelessSyncButtonBlock(
			BlockSetType.BAMBOO,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_CRIMSON_BUTTON = register(
		"wireless_crimson_button",
		new WirelessSyncButtonBlock(
			BlockSetType.CRIMSON,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.CRIMSON_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_WARPED_BUTTON = register(
		"wireless_warped_button",
		new WirelessSyncButtonBlock(
			BlockSetType.WARPED,
			30,
			BlockBehaviour.Properties.ofFullCopy(Blocks.WARPED_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncButtonBlock WIRELESS_POLISHED_BLACKSTONE_BUTTON = register(
		"wireless_polished_blackstone_button",
		new WirelessSyncButtonBlock(
			BlockSetType.POLISHED_BLACKSTONE,
			20,
			BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_BLACKSTONE_BUTTON).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessStonePressurePlateBlock WIRELESS_STONE_PRESSURE_PLATE = register(
		"wireless_stone_pressure_plate",
		new WirelessStonePressurePlateBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_OAK_PRESSURE_PLATE = register(
		"wireless_oak_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.OAK,
			BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_SPRUCE_PRESSURE_PLATE = register(
		"wireless_spruce_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.SPRUCE,
			BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_BIRCH_PRESSURE_PLATE = register(
		"wireless_birch_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.BIRCH,
			BlockBehaviour.Properties.ofFullCopy(Blocks.BIRCH_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_JUNGLE_PRESSURE_PLATE = register(
		"wireless_jungle_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.JUNGLE,
			BlockBehaviour.Properties.ofFullCopy(Blocks.JUNGLE_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_ACACIA_PRESSURE_PLATE = register(
		"wireless_acacia_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.ACACIA,
			BlockBehaviour.Properties.ofFullCopy(Blocks.ACACIA_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_CHERRY_PRESSURE_PLATE = register(
		"wireless_cherry_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.CHERRY,
			BlockBehaviour.Properties.ofFullCopy(Blocks.CHERRY_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_DARK_OAK_PRESSURE_PLATE = register(
		"wireless_dark_oak_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.DARK_OAK,
			BlockBehaviour.Properties.ofFullCopy(Blocks.DARK_OAK_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_MANGROVE_PRESSURE_PLATE = register(
		"wireless_mangrove_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.MANGROVE,
			BlockBehaviour.Properties.ofFullCopy(Blocks.MANGROVE_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_BAMBOO_PRESSURE_PLATE = register(
		"wireless_bamboo_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.BAMBOO,
			BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_CRIMSON_PRESSURE_PLATE = register(
		"wireless_crimson_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.CRIMSON,
			BlockBehaviour.Properties.ofFullCopy(Blocks.CRIMSON_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_WARPED_PRESSURE_PLATE = register(
		"wireless_warped_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.WARPED,
			BlockBehaviour.Properties.ofFullCopy(Blocks.WARPED_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessSyncPressurePlateBlock WIRELESS_POLISHED_BLACKSTONE_PRESSURE_PLATE = register(
		"wireless_polished_blackstone_pressure_plate",
		new WirelessSyncPressurePlateBlock(
			BlockSetType.POLISHED_BLACKSTONE,
			BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessWeightedPressurePlateBlock WIRELESS_LIGHT_WEIGHTED_PRESSURE_PLATE = register(
		"wireless_light_weighted_pressure_plate",
		new WirelessWeightedPressurePlateBlock(
			15,
			BlockSetType.GOLD,
			BlockBehaviour.Properties.ofFullCopy(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessWeightedPressurePlateBlock WIRELESS_HEAVY_WEIGHTED_PRESSURE_PLATE = register(
		"wireless_heavy_weighted_pressure_plate",
		new WirelessWeightedPressurePlateBlock(
			150,
			BlockSetType.IRON,
			BlockBehaviour.Properties.ofFullCopy(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE).pushReaction(PushReaction.NORMAL)
		)
	);

	public static final WirelessPistonBlock WIRELESS_PISTON = register(
		"wireless_piston",
		new WirelessPistonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.PISTON))
	);

	public static final WirelessPistonBlock WIRELESS_STICKY_PISTON = register(
		"wireless_sticky_piston",
		new WirelessPistonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STICKY_PISTON), true)
	);

	public static final WirelessPistonHeadBlock WIRELESS_PISTON_HEAD = register(
		"wireless_piston_head",
		new WirelessPistonHeadBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.PISTON_HEAD))
	);

	public static final WirelessRedstoneLampBlock WIRELESS_REDSTONE_LAMP = register(
		"wireless_redstone_lamp",
		new WirelessRedstoneLampBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_LAMP).pushReaction(PushReaction.NORMAL))
	);

	public static final WirelessSeaLanternBlock WIRELESS_SEA_LANTERN = register(
		"wireless_sea_lantern",
		new WirelessSeaLanternBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SEA_LANTERN).pushReaction(PushReaction.NORMAL))
	);

	public static final WirelessLanternBlock WIRELESS_LANTERN = register(
		"wireless_lantern",
		new WirelessLanternBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.LANTERN).pushReaction(PushReaction.NORMAL),
			15,
			() -> ModBlockEntities.WIRELESS_LANTERN
		)
	);

	public static final WirelessSoulLanternBlock WIRELESS_SOUL_LANTERN = register(
		"wireless_soul_lantern",
		new WirelessSoulLanternBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.SOUL_LANTERN).pushReaction(PushReaction.NORMAL),
			() -> ModBlockEntities.WIRELESS_SOUL_LANTERN
		)
	);

	public static final WirelessLitPumpkinBlock WIRELESS_JACK_O_LANTERN = register(
		"wireless_jack_o_lantern",
		new WirelessLitPumpkinBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.JACK_O_LANTERN).pushReaction(PushReaction.NORMAL),
			() -> ModBlockEntities.WIRELESS_JACK_O_LANTERN
		)
	);

	public static final WirelessGlowstoneBlock WIRELESS_GLOWSTONE = register(
		"wireless_glowstone",
		new WirelessGlowstoneBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.GLOWSTONE).pushReaction(PushReaction.NORMAL),
			() -> ModBlockEntities.WIRELESS_GLOWSTONE
		)
	);

	public static final WirelessEndRodBlock WIRELESS_END_ROD = register(
		"wireless_end_rod",
		new WirelessEndRodBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.END_ROD).pushReaction(PushReaction.NORMAL),
			() -> ModBlockEntities.WIRELESS_END_ROD
		)
	);

	public static final WirelessFroglightBlock WIRELESS_OCHRE_FROGLIGHT = register(
		"wireless_ochre_froglight",
		new WirelessFroglightBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.OCHRE_FROGLIGHT).pushReaction(PushReaction.NORMAL),
			() -> ModBlockEntities.WIRELESS_OCHRE_FROGLIGHT
		)
	);

	public static final WirelessFroglightBlock WIRELESS_VERDANT_FROGLIGHT = register(
		"wireless_verdant_froglight",
		new WirelessFroglightBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.VERDANT_FROGLIGHT).pushReaction(PushReaction.NORMAL),
			() -> ModBlockEntities.WIRELESS_VERDANT_FROGLIGHT
		)
	);

	public static final WirelessFroglightBlock WIRELESS_PEARLESCENT_FROGLIGHT = register(
		"wireless_pearlescent_froglight",
		new WirelessFroglightBlock(
			BlockBehaviour.Properties.ofFullCopy(Blocks.PEARLESCENT_FROGLIGHT).pushReaction(PushReaction.NORMAL),
			() -> ModBlockEntities.WIRELESS_PEARLESCENT_FROGLIGHT
		)
	);

	public static final com.makomi.block.WirelessOakDoorBlock WIRELESS_OAK_DOOR = register(
		"wireless_oak_door",
		new com.makomi.block.WirelessOakDoorBlock(
			net.minecraft.world.level.block.state.properties.BlockSetType.OAK,
			BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_DOOR)
		)
	);

	public static final WirelessIronDoorBlock WIRELESS_IRON_DOOR = register(
		"wireless_iron_door",
		new WirelessIronDoorBlock(BlockSetType.IRON, BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_DOOR))
	);

	public static final WirelessTrapdoorCoreBlock WIRELESS_OAK_TRAPDOOR = register(
		"wireless_oak_trapdoor",
		new WirelessTrapdoorCoreBlock(BlockSetType.OAK, BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_TRAPDOOR))
	);

	public static final WirelessTrapdoorCoreBlock WIRELESS_IRON_TRAPDOOR = register(
		"wireless_iron_trapdoor",
		new WirelessTrapdoorCoreBlock(BlockSetType.IRON, BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_TRAPDOOR))
	);

	public static final WirelessFenceGateCoreBlock WIRELESS_OAK_FENCE_GATE = register(
		"wireless_oak_fence_gate",
		new WirelessFenceGateCoreBlock(WoodType.OAK, BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_FENCE_GATE))
	);

	public static final WirelessNoteBlock WIRELESS_NOTE_BLOCK = register(
		"wireless_note_block",
		new WirelessNoteBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK).pushReaction(PushReaction.NORMAL))
	);

	public static final WirelessTntBlock WIRELESS_TNT = register(
		"wireless_tnt",
		new WirelessTntBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.TNT).pushReaction(PushReaction.NORMAL))
	);

	public static final WirelessRedstoneBlock WIRELESS_REDSTONE_BLOCK = register(
		"wireless_redstone_block",
		new WirelessRedstoneBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK).pushReaction(PushReaction.NORMAL))
	);

	private ModBlocks() {
	}

	private static <T extends Block> T register(String path, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, id(path), block);
	}

	/**
	 * 构建 observer 风格 hide 节点的统一方块属性。
	 */
	private static BlockBehaviour.Properties createHideObserverLikeProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER)
			.noOcclusion()
			.noCollission()
			.isViewBlocking((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isRedstoneConductor((state, level, pos) -> false)
			.isValidSpawn((state, level, pos, entityType) -> false);
	}

	/**
	 * 构建 redstone block 风格 hide 节点的统一方块属性。
	 */
	private static BlockBehaviour.Properties createHideRedstoneBlockLikeProperties() {
		return BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK)
			.lightLevel(state -> 0)
			.noOcclusion()
			.noCollission()
			.isViewBlocking((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isRedstoneConductor((state, level, pos) -> false)
			.isValidSpawn((state, level, pos, entityType) -> false);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
