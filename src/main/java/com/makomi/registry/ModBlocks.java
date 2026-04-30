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
