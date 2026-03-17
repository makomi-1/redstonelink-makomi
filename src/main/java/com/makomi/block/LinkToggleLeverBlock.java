package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
import com.makomi.block.entity.LinkToggleLeverBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CurrentLinksPrivacyService;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 联动拨杆方块。
 * <p>
 * 保留拨杆交互手感，但屏蔽原版红石输出，仅作为按钮源节点触发联动网络。
 * </p>
 */
public class LinkToggleLeverBlock extends LeverBlock implements EntityBlock {
	// 拉杆属于触发器类，实现上复用 LinkButtonBlockEntity（TRIGGER_SOURCE 节点）链路。
	public LinkToggleLeverBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkToggleLeverBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity)) {
			return;
		}

		long serial = LinkItemData.resolvePlacementSerial(stack, serverLevel, LinkNodeType.TRIGGER_SOURCE, pos);
		buttonBlockEntity.setLinkData(serial);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		List<ItemStack> drops = new ArrayList<>(super.getDrops(state, builder));
		if (drops.isEmpty()) {
			drops.add(new ItemStack(asItem()));
		}

		if (!(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof LinkButtonBlockEntity buttonBlockEntity)) {
			return drops;
		}

		long serial = buttonBlockEntity.getSerial();
		if (serial <= 0L) {
			return drops;
		}

		for (ItemStack drop : drops) {
			if (drop.is(asItem())) {
				LinkItemData.setSerial(drop, serial);
				LinkItemData.setDestroyRetireCandidate(drop, true);
				if (buttonBlockEntity.getLevel() instanceof ServerLevel serverLevel) {
					LinkItemData.setLinkedSerials(
						drop,
						CurrentLinksPrivacyService.resolveItemSnapshotTargets(
							serverLevel,
							LinkNodeType.TRIGGER_SOURCE,
							serial,
							LinkSavedData.get(serverLevel).getLinkedCores(serial)
						)
					);
				}
			}
		}
		return drops;
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			if (level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity) {
				buttonBlockEntity.unregisterNode(true);
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
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
			openPairingScreen(level, pos, player);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		InteractionResult result = super.useWithoutItem(state, level, pos, player, hitResult);
		if (!level.isClientSide && result.consumesAction()) {
			if (level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity) {
				buttonBlockEntity.triggerLinkedTargets(player);
			}
		}
		return result;
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return false;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return 0;
	}

	/**
	 * 打开按钮配对界面；若尚未分配序列号则先分配。
	 */
	private static void openPairingScreen(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity) {
			long serial = buttonBlockEntity.getSerial();
			if (serial <= 0L) {
				serial = LinkSavedData.get(serverLevel).allocateSerial(LinkNodeType.TRIGGER_SOURCE);
				buttonBlockEntity.setLinkData(serial);
			}
			if (serial > 0L) {
				PairingNetwork.openTriggerSourcePairing(serverPlayer, serial);
			}
		}
	}

}
