package com.makomi.block;

import com.makomi.block.entity.LinkRedstoneDustCoreBlockEntity;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.network.PairingNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

public class LinkRedstoneDustCoreBlock extends RedStoneWireBlock implements EntityBlock {
	public LinkRedstoneDustCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkRedstoneDustCoreBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity)) {
			return;
		}

		long serial = LinkItemData.resolvePlacementSerial(stack, serverLevel, LinkNodeType.CORE, pos);
		coreBlockEntity.setLinkData(serial);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		List<ItemStack> drops = new ArrayList<>(super.getDrops(state, builder));
		if (drops.isEmpty()) {
			drops.add(new ItemStack(asItem()));
		}

		if (!(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity)) {
			return drops;
		}

		long serial = coreBlockEntity.getSerial();
		if (serial <= 0L) {
			return drops;
		}

		for (ItemStack drop : drops) {
			if (drop.is(asItem())) {
				LinkItemData.setSerial(drop, serial);
				LinkItemData.setDestroyRetireCandidate(drop, true);
				if (coreBlockEntity.getLevel() instanceof ServerLevel serverLevel) {
					LinkItemData.setLinkedSerials(drop, LinkSavedData.get(serverLevel).getLinkedButtons(serial));
				}
			}
		}
		return drops;
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
				coreBlockEntity.unregisterNode();
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected void neighborChanged(
		BlockState state,
		Level level,
		BlockPos pos,
		net.minecraft.world.level.block.Block block,
		BlockPos fromPos,
		boolean movedByPiston
	) {
		if (!isCoreActive(level, pos)) {
			super.neighborChanged(state, level, pos, block, fromPos, movedByPiston);
			return;
		}

		if (!state.canSurvive(level, pos)) {
			dropResources(state, level, pos);
			level.removeBlock(pos, false);
			return;
		}

		if (state.getValue(POWER) != 15) {
			level.setBlock(pos, state.setValue(POWER, 15), UPDATE_ALL);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		BlockHitResult hitResult
	) {
		if (player.isShiftKeyDown() && isPlayerEmptyHanded(player)) {
			openPairingScreen(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		if (!level.isClientSide && level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			coreBlockEntity.triggerByPlayer();
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			coreBlockEntity.onPulseTick();
		}
	}

	private static void openPairingScreen(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity) {
			long serial = coreBlockEntity.getSerial();
			if (serial <= 0L) {
				serial = LinkSavedData.get(serverLevel).allocateSerial(LinkNodeType.CORE);
				coreBlockEntity.setLinkData(serial);
			}
			if (serial > 0L) {
				PairingNetwork.openCorePairing(serverPlayer, serial);
			}
		}
	}

	private static boolean isPlayerEmptyHanded(Player player) {
		return player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
	}

	private static boolean isCoreActive(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof LinkRedstoneDustCoreBlockEntity coreBlockEntity && coreBlockEntity.isActive();
	}
}
