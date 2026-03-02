package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkCoreBlockEntity;
import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
	public static final BlockEntityType<LinkCoreBlockEntity> LINK_REDSTONE_CORE = register(
		"link_redstone_core",
		BlockEntityType.Builder.of(LinkCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_CORE).build(null)
	);

	public static final BlockEntityType<LinkButtonBlockEntity> LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		BlockEntityType.Builder.of(LinkButtonBlockEntity::new, ModBlocks.LINK_TOGGLE_BUTTON).build(null)
	);

	public static final BlockEntityType<LinkRedstoneDustCoreBlockEntity> LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		BlockEntityType.Builder.of(LinkRedstoneDustCoreBlockEntity::new, ModBlocks.LINK_REDSTONE_DUST_CORE).build(null)
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
