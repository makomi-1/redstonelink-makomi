package com.makomi.data;

import com.makomi.block.AbstractLinkFilterBlock;
import com.makomi.block.HideChunkActivatorBlock;
import com.makomi.block.HideCoreBlock;
import com.makomi.block.HidePulseEmitterBlock;
import com.makomi.block.HideReceiveFilterBlock;
import com.makomi.block.HideRepeaterBlock;
import com.makomi.block.HideSendFilterBlock;
import com.makomi.block.HideSyncTriggerSourceBlock;
import com.makomi.block.HideToggleEmitterBlock;
import com.makomi.block.LinkChunkActivatorBlock;
import com.makomi.block.LinkCoreBlock;
import com.makomi.block.LinkRepeaterBlock;
import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.registry.ModBlocks;
import com.makomi.registry.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隐藏节点识别与 ghost 映射支撑。
 * <p>
 * 统一回答某个 block/item 是否属于 hide 节点，
 * 以及该 hide 节点在智能眼镜下应映射成哪种普通方块状态。
 * </p>
 */
public final class HideNodeSupport {
	private HideNodeSupport() {
	}

	/**
	 * 判断给定物品是否属于隐藏节点。
	 */
	public static boolean isHideNodeItem(Item item) {
		return item == ModItems.HIDE_CORE
			|| item == ModItems.HIDE_SYNC_TRIGGER_SOURCE
			|| item == ModItems.HIDE_TOGGLE_EMITTER
			|| item == ModItems.HIDE_PULSE_EMITTER
			|| item == ModItems.HIDE_SEND_FILTER
			|| item == ModItems.HIDE_RECEIVE_FILTER
			|| item == ModItems.HIDE_CHUNK_ACTIVATOR
			|| item == ModItems.HIDE_REPEATER;
	}

	/**
	 * 判断给定方块是否属于隐藏节点。
	 */
	public static boolean isHideNodeBlock(Block block) {
		return block instanceof HideCoreBlock
			|| block instanceof HideSyncTriggerSourceBlock
			|| block instanceof HideToggleEmitterBlock
			|| block instanceof HidePulseEmitterBlock
			|| block instanceof HideSendFilterBlock
			|| block instanceof HideReceiveFilterBlock
			|| block instanceof HideChunkActivatorBlock
			|| block instanceof HideRepeaterBlock;
	}

	/**
	 * 将隐藏节点状态映射为智能眼镜下的普通 ghost 显示状态。
	 */
	public static BlockState resolveGhostDisplayState(BlockState hiddenState) {
		if (hiddenState == null) {
			return null;
		}
		Block hiddenBlock = hiddenState.getBlock();
		if (hiddenBlock instanceof HideCoreBlock) {
			return ModBlocks.LINK_REDSTONE_CORE.defaultBlockState().setValue(
				LinkCoreBlock.ACTIVE,
				hiddenState.getValue(LinkCoreBlock.ACTIVE)
			);
		}
		if (hiddenBlock instanceof HideSyncTriggerSourceBlock) {
			return ghostEmitterState(ModBlocks.LINK_SYNC_EMITTER.defaultBlockState(), hiddenState);
		}
		if (hiddenBlock instanceof HideToggleEmitterBlock) {
			return ghostEmitterState(ModBlocks.LINK_TOGGLE_EMITTER.defaultBlockState(), hiddenState);
		}
		if (hiddenBlock instanceof HidePulseEmitterBlock) {
			return ghostEmitterState(ModBlocks.LINK_PULSE_EMITTER.defaultBlockState(), hiddenState);
		}
		if (hiddenBlock instanceof HideSendFilterBlock) {
			return ghostFilterState(ModBlocks.LINK_SEND_FILTER.defaultBlockState(), hiddenState);
		}
		if (hiddenBlock instanceof HideReceiveFilterBlock) {
			return ghostFilterState(ModBlocks.LINK_RECEIVE_FILTER.defaultBlockState(), hiddenState);
		}
		if (hiddenBlock instanceof HideChunkActivatorBlock) {
			return ghostChunkActivatorState(ModBlocks.LINK_CHUNK_ACTIVATOR.defaultBlockState(), hiddenState);
		}
		if (hiddenBlock instanceof HideRepeaterBlock) {
			return ghostRepeaterState(ModBlocks.LINK_REPEATER.defaultBlockState(), hiddenState);
		}
		return null;
	}

	private static BlockState ghostEmitterState(BlockState visibleState, BlockState hiddenState) {
		return visibleState.setValue(LinkSignalEmitterBlock.POWERED, hiddenState.getValue(LinkSignalEmitterBlock.POWERED));
	}

	private static BlockState ghostFilterState(BlockState visibleState, BlockState hiddenState) {
		return visibleState.setValue(AbstractLinkFilterBlock.POWERED, hiddenState.getValue(AbstractLinkFilterBlock.POWERED));
	}

	private static BlockState ghostChunkActivatorState(BlockState visibleState, BlockState hiddenState) {
		return visibleState.setValue(LinkChunkActivatorBlock.POWERED, hiddenState.getValue(LinkChunkActivatorBlock.POWERED));
	}

	private static BlockState ghostRepeaterState(BlockState visibleState, BlockState hiddenState) {
		return visibleState.setValue(LinkRepeaterBlock.ACTIVE, hiddenState.getValue(LinkRepeaterBlock.ACTIVE));
	}
}
