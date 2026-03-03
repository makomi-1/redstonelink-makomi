package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkCoreBlockEntity;
import com.makomi.block.entity.LinkPulseButtonBlockEntity;
import com.makomi.block.entity.LinkPulseEmitterBlockEntity;
import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import com.makomi.block.entity.LinkTransparentCoreBlockEntity;
import com.makomi.block.entity.LinkTransparentRedstoneDustCoreBlockEntity;
import com.makomi.block.entity.LinkToggleButtonBlockEntity;
import com.makomi.block.entity.LinkToggleEmitterBlockEntity;
import com.makomi.block.entity.LinkToggleLeverBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

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

	public static final BlockEntityType<LinkToggleLeverBlockEntity> LINK_TOGGLE_LEVER = register(
		"link_toggle_lever",
		BlockEntityType.Builder.of(LinkToggleLeverBlockEntity::new, ModBlocks.LINK_TOGGLE_LEVER).build(null)
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

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成注册。
	}
}
