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
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
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
		FabricBlockEntityTypeBuilder.create(LinkCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_CORE).build()
	);

	public static final BlockEntityType<LinkTransparentCoreBlockEntity> LINK_REDSTONE_CORE_TRANSPARENT = register(
		"link_redstone_core_transparent",
		FabricBlockEntityTypeBuilder.create(LinkTransparentCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_CORE_TRANSPARENT).build()
	);

	public static final BlockEntityType<LinkToggleButtonBlockEntity> LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		FabricBlockEntityTypeBuilder.create(LinkToggleButtonBlockEntity::new, ModBlocks.LINK_TOGGLE_BUTTON).build()
	);

	public static final BlockEntityType<LinkSyncLeverBlockEntity> LINK_SYNC_LEVER = register(
		"link_sync_lever",
		FabricBlockEntityTypeBuilder.create(LinkSyncLeverBlockEntity::new, ModBlocks.LINK_SYNC_LEVER).build()
	);

	public static final BlockEntityType<LinkPulseButtonBlockEntity> LINK_PUSH_BUTTON = register(
		"link_push_button",
		FabricBlockEntityTypeBuilder.create(LinkPulseButtonBlockEntity::new, ModBlocks.LINK_PUSH_BUTTON).build()
	);

	public static final BlockEntityType<LinkToggleEmitterBlockEntity> LINK_TOGGLE_EMITTER = register(
		"link_toggle_emitter",
		FabricBlockEntityTypeBuilder.create(LinkToggleEmitterBlockEntity::new, ModBlocks.LINK_TOGGLE_EMITTER).build()
	);

	public static final BlockEntityType<LinkPulseEmitterBlockEntity> LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		FabricBlockEntityTypeBuilder.create(LinkPulseEmitterBlockEntity::new, ModBlocks.LINK_PULSE_EMITTER).build()
	);

	public static final BlockEntityType<LinkSyncEmitterBlockEntity> LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		FabricBlockEntityTypeBuilder.create(LinkSyncEmitterBlockEntity::new, ModBlocks.LINK_SYNC_EMITTER).build()
	);

	public static final BlockEntityType<LinkSendFilterBlockEntity> LINK_SEND_FILTER = register(
		"link_send_filter",
		FabricBlockEntityTypeBuilder.create(LinkSendFilterBlockEntity::new, ModBlocks.LINK_SEND_FILTER).build()
	);

	public static final BlockEntityType<LinkReceiveFilterBlockEntity> LINK_RECEIVE_FILTER = register(
		"link_receive_filter",
		FabricBlockEntityTypeBuilder.create(LinkReceiveFilterBlockEntity::new, ModBlocks.LINK_RECEIVE_FILTER).build()
	);

	public static final BlockEntityType<LinkChunkActivatorBlockEntity> LINK_CHUNK_ACTIVATOR = register(
		"link_chunk_activator",
		FabricBlockEntityTypeBuilder.create(LinkChunkActivatorBlockEntity::new, ModBlocks.LINK_CHUNK_ACTIVATOR).build()
	);

	public static final BlockEntityType<LinkRepeaterBlockEntity> LINK_REPEATER = register(
		"link_repeater",
		FabricBlockEntityTypeBuilder.create(LinkRepeaterBlockEntity::new, ModBlocks.LINK_REPEATER).build()
	);

	public static final BlockEntityType<LinkRedstoneDustCoreBlockEntity> LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		FabricBlockEntityTypeBuilder.create(LinkRedstoneDustCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_DUST_CORE).build()
	);

	public static final BlockEntityType<LinkTransparentRedstoneDustCoreBlockEntity> LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		FabricBlockEntityTypeBuilder.create(
			LinkTransparentRedstoneDustCoreBlockEntity::new,
			ModBlocks.LINK_REDSTONE_DUST_CORE_TRANSPARENT
		).build()
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
