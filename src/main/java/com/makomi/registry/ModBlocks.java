package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.LinkCoreBlock;
import com.makomi.block.LinkPulseEmitterBlock;
import com.makomi.block.LinkPulseButtonBlock;
import com.makomi.block.LinkRedstoneDustCoreBlock;
import com.makomi.block.LinkSyncEmitterBlock;
import com.makomi.block.LinkTransparentCoreBlock;
import com.makomi.block.LinkTransparentRedstoneDustCoreBlock;
import com.makomi.block.LinkToggleEmitterBlock;
import com.makomi.block.LinkToggleButtonBlock;
import com.makomi.block.LinkToggleLeverBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

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

	public static final LinkToggleButtonBlock LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		new LinkToggleButtonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BUTTON))
	);

	public static final LinkToggleLeverBlock LINK_TOGGLE_LEVER = register(
		"link_toggle_lever",
		new LinkToggleLeverBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.LEVER))
	);

	public static final LinkPulseButtonBlock LINK_PUSH_BUTTON = register(
		"link_push_button",
		new LinkPulseButtonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_BUTTON))
	);

	public static final LinkToggleEmitterBlock LINK_TOGGLE_EMITTER = register(
		"link_toggle_emitter",
		new LinkToggleEmitterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final LinkPulseEmitterBlock LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		new LinkPulseEmitterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final LinkSyncEmitterBlock LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		new LinkSyncEmitterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion())
	);

	public static final LinkRedstoneDustCoreBlock LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		new LinkRedstoneDustCoreBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_WIRE))
	);

	public static final LinkTransparentRedstoneDustCoreBlock LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		new LinkTransparentRedstoneDustCoreBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_WIRE))
	);

	private ModBlocks() {
	}

	private static <T extends Block> T register(String path, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, id(path), block);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
