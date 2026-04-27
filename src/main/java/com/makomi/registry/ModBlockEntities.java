package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.LinkCoreBlockEntity;
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
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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

	public static final BlockEntityType<LinkPulseEmitterBlockEntity> LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		BlockEntityType.Builder.of(LinkPulseEmitterBlockEntity::new, ModBlocks.LINK_PULSE_EMITTER).build(null)
	);

	public static final BlockEntityType<LinkSyncEmitterBlockEntity> LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		BlockEntityType.Builder.of(LinkSyncEmitterBlockEntity::new, ModBlocks.LINK_SYNC_EMITTER).build(null)
	);

	public static final BlockEntityType<LinkSendFilterBlockEntity> LINK_SEND_FILTER = register(
		"link_send_filter",
		BlockEntityType.Builder.of(LinkSendFilterBlockEntity::new, ModBlocks.LINK_SEND_FILTER).build(null)
	);

	public static final BlockEntityType<LinkReceiveFilterBlockEntity> LINK_RECEIVE_FILTER = register(
		"link_receive_filter",
		BlockEntityType.Builder.of(LinkReceiveFilterBlockEntity::new, ModBlocks.LINK_RECEIVE_FILTER).build(null)
	);

	public static final BlockEntityType<LinkChunkActivatorBlockEntity> LINK_CHUNK_ACTIVATOR = register(
		"link_chunk_activator",
		BlockEntityType.Builder.of(LinkChunkActivatorBlockEntity::new, ModBlocks.LINK_CHUNK_ACTIVATOR).build(null)
	);

	public static final BlockEntityType<LinkRepeaterBlockEntity> LINK_REPEATER = register(
		"link_repeater",
		BlockEntityType.Builder.of(LinkRepeaterBlockEntity::new, ModBlocks.LINK_REPEATER).build(null)
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

	private ModBlockEntities() {
	}

	private static <T extends BlockEntityType<?>> T register(String path, T type) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(path), type);
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
