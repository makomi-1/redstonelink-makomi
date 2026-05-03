package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.HideChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkCoreBlockEntity;
import com.makomi.block.entity.HideCoreBlockEntity;
import com.makomi.block.entity.HidePulseEmitterBlockEntity;
import com.makomi.block.entity.HideReceiveFilterBlockEntity;
import com.makomi.block.entity.HideRepeaterBlockEntity;
import com.makomi.block.entity.HideSendFilterBlockEntity;
import com.makomi.block.entity.HideSyncTriggerSourceBlockEntity;
import com.makomi.block.entity.HideToggleEmitterBlockEntity;
import com.makomi.block.entity.LinkChunkActivatorBlockEntity;
import com.makomi.block.entity.LinkPulseButtonBlockEntity;
import com.makomi.block.entity.LinkPulseEmitterBlockEntity;
import com.makomi.block.entity.LinkReceiveFilterBlockEntity;
import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import com.makomi.block.entity.LinkRepeaterBlockEntity;
import com.makomi.block.entity.LinkSendFilterBlockEntity;
import com.makomi.block.entity.LinkSyncEmitterBlockEntity;
import com.makomi.block.entity.LinkTransparentCoreBlockEntity;
import com.makomi.block.entity.LinkTransparentRedstoneDustCoreBlockEntity;
import com.makomi.block.entity.LinkToggleButtonBlockEntity;
import com.makomi.block.entity.LinkToggleEmitterBlockEntity;
import com.makomi.block.entity.LinkSyncLeverBlockEntity;
import com.makomi.block.entity.WirelessConverterBlockEntity;
import com.makomi.block.entity.WirelessLeverBlockEntity;
import com.makomi.block.entity.WirelessPistonBlockEntity;
import com.makomi.block.entity.WirelessRedstoneLampBlockEntity;
import com.makomi.block.entity.WirelessStoneButtonBlockEntity;
import com.makomi.block.entity.WirelessStonePressurePlateBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 模组方块实体类型注册表。
 */
public final class ModBlockEntities {
	public static final BlockEntityType<LinkCoreBlockEntity> LINK_REDSTONE_CORE = register(
		"link_redstone_core",
		BlockEntityType.Builder.of(LinkCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_CORE).build(null)
	);

	public static final BlockEntityType<LinkTransparentCoreBlockEntity> LINK_REDSTONE_CORE_TRANSPARENT = register(
		"link_redstone_core_transparent",
		BlockEntityType.Builder.of(LinkTransparentCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT).build(null)
	);

	public static final BlockEntityType<HideCoreBlockEntity> HIDE_CORE = register(
		"hide_core",
		BlockEntityType.Builder.of(HideCoreBlockEntity::new, ModBlocks.HIDE_CORE).build(null)
	);

	public static final BlockEntityType<LinkToggleButtonBlockEntity> LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		BlockEntityType.Builder.of(LinkToggleButtonBlockEntity::new, ModBlocks.LINK_TOGGLE_BUTTON).build(null)
	);

	public static final BlockEntityType<LinkSyncLeverBlockEntity> LINK_SYNC_LEVER = register(
		"link_sync_lever",
		BlockEntityType.Builder.of(LinkSyncLeverBlockEntity::new, ModBlocks.LINK_SYNC_LEVER).build(null)
	);

	public static final BlockEntityType<LinkPulseButtonBlockEntity> LINK_PUSH_BUTTON = register(
		"link_push_button",
		BlockEntityType.Builder.of(LinkPulseButtonBlockEntity::new, ModBlocks.LINK_PUSH_BUTTON).build(null)
	);

	public static final BlockEntityType<LinkToggleEmitterBlockEntity> LINK_TOGGLE_EMITTER = register(
		"link_toggle_emitter",
		BlockEntityType.Builder.of(LinkToggleEmitterBlockEntity::new, ModBlocks.LINK_TOGGLE_EMITTER).build(null)
	);

	public static final BlockEntityType<HideToggleEmitterBlockEntity> HIDE_TOGGLE_EMITTER = register(
		"hide_toggle_emitter",
		BlockEntityType.Builder.of(HideToggleEmitterBlockEntity::new, ModBlocks.HIDE_TOGGLE_EMITTER).build(null)
	);

	public static final BlockEntityType<LinkPulseEmitterBlockEntity> LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		BlockEntityType.Builder.of(LinkPulseEmitterBlockEntity::new, ModBlocks.LINK_PULSE_EMITTER).build(null)
	);

	public static final BlockEntityType<HidePulseEmitterBlockEntity> HIDE_PULSE_EMITTER = register(
		"hide_pulse_emitter",
		BlockEntityType.Builder.of(HidePulseEmitterBlockEntity::new, ModBlocks.HIDE_PULSE_EMITTER).build(null)
	);

	public static final BlockEntityType<LinkSyncEmitterBlockEntity> LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		BlockEntityType.Builder.of(LinkSyncEmitterBlockEntity::new, ModBlocks.LINK_SYNC_EMITTER).build(null)
	);

	public static final BlockEntityType<HideSyncTriggerSourceBlockEntity> HIDE_SYNC_TRIGGER_SOURCE = register(
		"hide_sync_trigger_source",
		BlockEntityType.Builder.of(HideSyncTriggerSourceBlockEntity::new, ModBlocks.HIDE_SYNC_TRIGGER_SOURCE).build(null)
	);

	public static final BlockEntityType<LinkSendFilterBlockEntity> LINK_SEND_FILTER = register(
		"link_send_filter",
		BlockEntityType.Builder.of(LinkSendFilterBlockEntity::new, ModBlocks.LINK_SEND_FILTER).build(null)
	);

	public static final BlockEntityType<HideSendFilterBlockEntity> HIDE_SEND_FILTER = register(
		"hide_send_filter",
		BlockEntityType.Builder.of(HideSendFilterBlockEntity::new, ModBlocks.HIDE_SEND_FILTER).build(null)
	);

	public static final BlockEntityType<LinkReceiveFilterBlockEntity> LINK_RECEIVE_FILTER = register(
		"link_receive_filter",
		BlockEntityType.Builder.of(LinkReceiveFilterBlockEntity::new, ModBlocks.LINK_RECEIVE_FILTER).build(null)
	);

	public static final BlockEntityType<HideReceiveFilterBlockEntity> HIDE_RECEIVE_FILTER = register(
		"hide_receive_filter",
		BlockEntityType.Builder.of(HideReceiveFilterBlockEntity::new, ModBlocks.HIDE_RECEIVE_FILTER).build(null)
	);

	public static final BlockEntityType<LinkChunkActivatorBlockEntity> LINK_CHUNK_ACTIVATOR = register(
		"link_chunk_activator",
		BlockEntityType.Builder.of(LinkChunkActivatorBlockEntity::new, ModBlocks.LINK_CHUNK_ACTIVATOR).build(null)
	);

	public static final BlockEntityType<HideChunkActivatorBlockEntity> HIDE_CHUNK_ACTIVATOR = register(
		"hide_chunk_activator",
		BlockEntityType.Builder.of(HideChunkActivatorBlockEntity::new, ModBlocks.HIDE_CHUNK_ACTIVATOR).build(null)
	);

	public static final BlockEntityType<LinkRepeaterBlockEntity> LINK_REPEATER = register(
		"link_repeater",
		BlockEntityType.Builder.of(LinkRepeaterBlockEntity::new, ModBlocks.LINK_REPEATER).build(null)
	);

	public static final BlockEntityType<HideRepeaterBlockEntity> HIDE_REPEATER = register(
		"hide_repeater",
		BlockEntityType.Builder.of(HideRepeaterBlockEntity::new, ModBlocks.HIDE_REPEATER).build(null)
	);

	public static final BlockEntityType<LinkRedstoneDustCoreBlockEntity> LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		BlockEntityType.Builder.of(LinkRedstoneDustCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_DUST_CORE).build(null)
	);

	public static final BlockEntityType<LinkTransparentRedstoneDustCoreBlockEntity> LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		BlockEntityType.Builder.of(
			LinkTransparentRedstoneDustCoreBlockEntity::new,
			ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT
		).build(null)
	);

	public static final BlockEntityType<WirelessConverterBlockEntity> WIRELESS_CONVERTER = register(
		"wireless_converter",
		BlockEntityType.Builder.of(WirelessConverterBlockEntity::new, ModBlocks.WIRELESS_CONVERTER).build(null)
	);

	public static final BlockEntityType<WirelessLeverBlockEntity> WIRELESS_LEVER = register(
		"wireless_lever",
		BlockEntityType.Builder.of(WirelessLeverBlockEntity::new, ModBlocks.WIRELESS_LEVER).build(null)
	);

	public static final BlockEntityType<WirelessStoneButtonBlockEntity> WIRELESS_STONE_BUTTON = register(
		"wireless_stone_button",
		BlockEntityType.Builder.of(WirelessStoneButtonBlockEntity::new, ModBlocks.WIRELESS_STONE_BUTTON).build(null)
	);

	public static final BlockEntityType<WirelessStonePressurePlateBlockEntity> WIRELESS_STONE_PRESSURE_PLATE = register(
		"wireless_stone_pressure_plate",
		BlockEntityType.Builder.of(
			WirelessStonePressurePlateBlockEntity::new,
			ModBlocks.WIRELESS_STONE_PRESSURE_PLATE
		).build(null)
	);

	public static final BlockEntityType<WirelessPistonBlockEntity> WIRELESS_PISTON = register(
		"wireless_piston",
		BlockEntityType.Builder.of(WirelessPistonBlockEntity::new, ModBlocks.WIRELESS_PISTON).build(null)
	);

	public static final BlockEntityType<WirelessRedstoneLampBlockEntity> WIRELESS_REDSTONE_LAMP = register(
		"wireless_redstone_lamp",
		BlockEntityType.Builder.of(
			WirelessRedstoneLampBlockEntity::new,
			ModBlocks.WIRELESS_REDSTONE_LAMP
		).build(null)
	);

	private ModBlockEntities() {
	}

	private static <T extends BlockEntityType<?>> T register(String path, T type) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(path), type);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
