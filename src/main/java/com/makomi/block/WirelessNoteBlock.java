package com.makomi.block;

import com.makomi.block.entity.WirelessNoteBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化音符盒。
 * <p>
 * 保留原版调音、手动演奏与音高状态，
 * 但不再读取原版邻居红石输入，只承接无线输入触发演奏。
 * </p>
 */
public class WirelessNoteBlock extends NoteBlock implements EntityBlock {
	public WirelessNoteBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessNoteBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.CORE,
			WirelessNoteBlockEntity.class
		);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessNoteBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		WirelessBlockNodeSupport.unregisterOnRemove(
			this,
			pos,
			newState.getBlock(),
			level,
			WirelessNoteBlockEntity.class
		);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected void neighborChanged(
		BlockState state,
		Level level,
		BlockPos pos,
		Block block,
		BlockPos fromPos,
		boolean movedByPiston
	) {
		// 无线化音符盒不读取原版邻居红石输入。
	}

	@Override
	protected InteractionResult useWithoutItem(
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		BlockHitResult hitResult
	) {
		if (RedstoneLinkConfig.canOpenPairingByPlacedBlock(player)) {
			WirelessBlockNodeSupport.openCorePairing(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
		return super.useWithoutItem(state, level, pos, player, hitResult);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof WirelessNoteBlockEntity blockEntity) {
			blockEntity.onPulseTick();
		}
	}

	/**
	 * 按无线激活态同步音符盒的受电表现。
	 * <p>
	 * 仅在 `false -> true` 的上升沿触发一次演奏，和原版受红石供电时一致。
	 * </p>
	 */
	public void syncWirelessPoweredState(Level level, BlockPos pos, BlockState state, boolean active) {
		if (state.getBlock() != this || state.getValue(POWERED) == active) {
			return;
		}
		if (active) {
			playWirelessNote(null, level, pos, state);
		}
		level.setBlock(pos, state.setValue(POWERED, active), 3);
	}

	/**
	 * 复用原版音符盒“上方空气/特殊乐器方块”判定，派发一次演奏事件。
	 */
	public void playWirelessNote(Entity sourceEntity, Level level, BlockPos pos, BlockState state) {
		if (state.getValue(INSTRUMENT).worksAboveNoteBlock() || level.getBlockState(pos.above()).isAir()) {
			level.blockEvent(pos, this, 0, 0);
			level.gameEvent(sourceEntity, GameEvent.NOTE_BLOCK_PLAY, pos);
		}
	}
}
