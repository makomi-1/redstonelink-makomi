package com.makomi.registry;

import com.makomi.RedstoneLink;
import com.makomi.block.HideCoreBlock;
import com.makomi.block.HideSyncTriggerSourceBlock;
import com.makomi.block.LinkCoreBlock;
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
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 模组方块注册表。
 */
public final class ModBlocks {
	public static final LinkCoreBlock LINK_REDSTONE_CORE = register(
		"link_redstone_core",
		LinkCoreBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK).lightLevel(state -> 0).noOcclusion()
	);

	public static final LinkTransparentCoreBlock LINK_REDSTONE_CORE_TRANSPARENT = register(
		"link_redstone_core_transparent",
		LinkTransparentCoreBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK)
			.lightLevel(state -> 0)
			// 显式声明“类玻璃”行为，避免仅依赖 noOcclusion 的隐式推断。
			.noOcclusion()
			.isViewBlocking((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isRedstoneConductor((state, level, pos) -> false)
			.isValidSpawn((state, level, pos, entityType) -> false)
	);

	public static final HideCoreBlock HIDE_CORE = register(
		"hide_core",
		HideCoreBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK)
			.lightLevel(state -> 0)
			.noOcclusion()
			.noCollision()
			.isViewBlocking((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isRedstoneConductor((state, level, pos) -> false)
			.isValidSpawn((state, level, pos, entityType) -> false)
	);

	public static final LinkToggleButtonBlock LINK_TOGGLE_BUTTON = register(
		"link_toggle_button",
		LinkToggleButtonBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BUTTON)
	);

	public static final LinkSyncLeverBlock LINK_SYNC_LEVER = register(
		"link_sync_lever",
		LinkSyncLeverBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.LEVER)
	);

	public static final LinkPulseButtonBlock LINK_PUSH_BUTTON = register(
		"link_push_button",
		LinkPulseButtonBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_BUTTON)
	);

	public static final LinkToggleEmitterBlock LINK_TOGGLE_EMITTER = register(
		"link_toggle_emitter",
		LinkToggleEmitterBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion()
	);

	public static final LinkPulseEmitterBlock LINK_PULSE_EMITTER = register(
		"link_pulse_emitter",
		LinkPulseEmitterBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion()
	);

	public static final LinkSyncEmitterBlock LINK_SYNC_EMITTER = register(
		"link_sync_emitter",
		LinkSyncEmitterBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion()
	);

	public static final HideSyncTriggerSourceBlock HIDE_SYNC_TRIGGER_SOURCE = register(
		"hide_sync_trigger_source",
		HideSyncTriggerSourceBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER)
			.noOcclusion()
			.noCollision()
			.isViewBlocking((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isRedstoneConductor((state, level, pos) -> false)
			.isValidSpawn((state, level, pos, entityType) -> false)
	);

	public static final LinkSendFilterBlock LINK_SEND_FILTER = register(
		"link_send_filter",
		LinkSendFilterBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion()
	);

	public static final LinkReceiveFilterBlock LINK_RECEIVE_FILTER = register(
		"link_receive_filter",
		LinkReceiveFilterBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_BLOCK).noOcclusion()
	);

	public static final LinkChunkActivatorBlock LINK_CHUNK_ACTIVATOR = register(
		"link_chunk_activator",
		LinkChunkActivatorBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion()
	);

	public static final LinkRepeaterBlock LINK_REPEATER = register(
		"link_repeater",
		LinkRepeaterBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).noOcclusion()
	);

	public static final LinkRedstoneDustCoreBlock LINK_REDSTONE_DUST_CORE = register(
		"link_redstone_dust_core",
		LinkRedstoneDustCoreBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_WIRE)
	);

	public static final LinkTransparentRedstoneDustCoreBlock LINK_REDSTONE_DUST_CORE_TRANSPARENT = register(
		"link_redstone_dust_core_transparent",
		LinkTransparentRedstoneDustCoreBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_WIRE)
	);

	private ModBlocks() {
	}

	private static <T extends Block> T register(
		String path,
		Function<BlockBehaviour.Properties, T> factory,
		BlockBehaviour.Properties properties
	) {
		return Registry.register(BuiltInRegistries.BLOCK, id(path), factory.apply(withBlockId(path, properties)));
	}

	private static <T extends Block> T register(String path, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, id(path), block);
	}

	/**
	 * 1.21.11 起方块构造阶段就会读取 block id，因此必须在 new Block(...) 前先注入注册键。
	 */
	private static BlockBehaviour.Properties withBlockId(String path, BlockBehaviour.Properties properties) {
		return properties.setId(ResourceKey.create(Registries.BLOCK, id(path)));
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(RedstoneLink.MOD_ID, path);
	}

	public static void register() {
		// 触发类加载即可完成静态字段注册。
	}
}
