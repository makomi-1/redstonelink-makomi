package com.makomi.item;

import com.makomi.block.AbstractLinkFilterBlock;
import com.makomi.block.LinkChunkActivatorBlock;
import com.makomi.block.LinkCoreBlock;
import com.makomi.block.LinkRepeaterBlock;
import com.makomi.block.LinkSignalEmitterBlock;
import com.makomi.block.LinkSyncEmitterBlock;
import com.makomi.block.WirelessPistonBlock;
import com.makomi.data.HideDirectionalEditorToolData;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 定向面集编辑工具。
 * <p>
 * 负责编辑块状节点的输入/输出面集，不承担配对或图结构修改逻辑。
 * </p>
 */
public class DirectionalFaceEditorItem extends Item {
	public DirectionalFaceEditorItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getHand() != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		Level level = context.getLevel();
		BlockPos clickedPos = context.getClickedPos();
		BlockState currentState = level.getBlockState(clickedPos);
		if (!isSupportedFaceEditableNode(currentState.getBlock()) || !NodeFaceSetBlockStateSupport.hasFaceProperties(currentState)) {
			return InteractionResult.PASS;
		}

		ItemStack toolStack = context.getItemInHand();
		HideDirectionalEditorToolData.EditMode editMode = HideDirectionalEditorToolData.readMode(toolStack);
		BlockState updatedState = applyEditMode(currentState, context.getClickedFace(), editMode);
		if (!updatedState.equals(currentState)) {
			level.setBlock(clickedPos, updatedState, Block.UPDATE_ALL);
			refreshRuntimeStateIfNeeded(level, clickedPos, updatedState);
		}

		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			serverPlayer.displayClientMessage(
				Component.translatable(
					"message.redstonelink.directional_face_editor.applied",
					Component.translatable(editMode.translationKey()),
					NodeFaceSetBlockStateSupport.buildEnabledFaceTokenText(updatedState)
				),
				true
			);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public void appendHoverText(
		ItemStack stack,
		Item.TooltipContext context,
		List<Component> tooltipComponents,
		TooltipFlag tooltipFlag
	) {
		CreativeTooltipOriginSupport.appendRedstoneLinkOriginLineIfNeeded(stack, tooltipComponents, tooltipFlag);
		HideDirectionalEditorToolData.EditMode editMode = HideDirectionalEditorToolData.readMode(stack);
		tooltipComponents.add(
			Component.translatable(
				"tooltip.redstonelink.directional_face_editor.mode",
				Component.translatable(editMode.translationKey())
			)
		);
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.directional_face_editor.cycle_mode"));
		tooltipComponents.add(Component.translatable("tooltip.redstonelink.directional_face_editor.apply"));
		super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
	}

	/**
	 * 判断当前命中方块是否属于本轮支持的面集可编辑节点。
	 */
	private static boolean isSupportedFaceEditableNode(Block block) {
		return block instanceof LinkCoreBlock
			|| block instanceof LinkRepeaterBlock
			|| block instanceof LinkSignalEmitterBlock
			|| block instanceof AbstractLinkFilterBlock
			|| block instanceof LinkChunkActivatorBlock
			|| block instanceof WirelessPistonBlock;
	}

	/**
	 * 按当前模式对命中面的面集执行编辑。
	 */
	private static BlockState applyEditMode(
		BlockState currentState,
		net.minecraft.core.Direction clickedFace,
		HideDirectionalEditorToolData.EditMode editMode
	) {
		net.minecraft.core.Direction editedFace = resolveEditedFace(currentState, clickedFace);
		HideDirectionalEditorToolData.EditMode resolvedMode = editMode == null
			? HideDirectionalEditorToolData.EditMode.TOGGLE
			: editMode;
		return switch (resolvedMode) {
			case TOGGLE -> NodeFaceSetBlockStateSupport.toggleFace(currentState, editedFace);
			case SINGLE -> NodeFaceSetBlockStateSupport.withSingleFace(currentState, editedFace);
			case ALL -> NodeFaceSetBlockStateSupport.setAllFaces(currentState, true);
			case CLEAR -> NodeFaceSetBlockStateSupport.setAllFaces(currentState, false);
		};
	}

	/**
	 * 将玩家点击的逻辑面转换为实际写入 `BlockState` 的存储面。
	 * <p>
	 * 输出型方块（`core/repeater`）的底层输出方向与存储方向相反，
	 * 因此编辑时需要写入对面；输入型方块仍按原面写入。
	 * </p>
	 */
	private static net.minecraft.core.Direction resolveEditedFace(
		BlockState currentState,
		net.minecraft.core.Direction clickedFace
	) {
		if (clickedFace == null) {
			return null;
		}
		return currentState != null
				&& (currentState.getBlock() instanceof LinkCoreBlock || currentState.getBlock() instanceof LinkRepeaterBlock)
			? clickedFace.getOpposite()
			: clickedFace;
	}

	/**
	 * 按节点类型立即刷新运行态，避免等待下一次邻居变化才生效。
	 */
	private static void refreshRuntimeStateIfNeeded(Level level, BlockPos blockPos, BlockState updatedState) {
		if (updatedState.getBlock() instanceof LinkSignalEmitterBlock signalEmitterBlock) {
			signalEmitterBlock.refreshPoweredStateFromCurrentInputs(level, blockPos, updatedState);
			return;
		}
		if (updatedState.getBlock() instanceof AbstractLinkFilterBlock filterBlock) {
			filterBlock.refreshStateFromCurrentInputs(level, blockPos, updatedState);
			return;
		}
		if (updatedState.getBlock() instanceof LinkChunkActivatorBlock chunkActivatorBlock) {
			chunkActivatorBlock.refreshStateFromCurrentInputs(level, blockPos, updatedState);
			return;
		}
		if (updatedState.getBlock() instanceof WirelessPistonBlock wirelessPistonBlock) {
			wirelessPistonBlock.refreshStateFromCurrentInputs(level, blockPos, updatedState);
		}
	}
}
