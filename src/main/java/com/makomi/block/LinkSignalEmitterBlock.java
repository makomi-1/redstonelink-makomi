package com.makomi.block;

import com.makomi.block.entity.LinkButtonBlockEntity;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 红石信号触发基类：
 * 1. 维持按钮节点序号与掉落继承；
 * 2. 监听邻居红石输入，按配置的边沿策略触发联动；
 * 3. 支持按配置策略打开按钮配对界面。
 */
public abstract class LinkSignalEmitterBlock extends Block implements EntityBlock {
	// 发射器属于触发器类，实现上复用 LinkButtonBlockEntity（TRIGGER_SOURCE 节点）链路。
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	protected LinkSignalEmitterBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(POWERED, false));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return createEmitterBlockEntity(blockPos, blockState);
	}

	/**
	 * 创建发射器方块实体（切换/脉冲模式由子类方块实体决定）。
	 */
	protected abstract LinkButtonBlockEntity createEmitterBlockEntity(BlockPos blockPos, BlockState blockState);

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
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (!oldState.is(state.getBlock())) {
			updatePoweredState(level, pos, state);
		}
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
		super.neighborChanged(state, level, pos, block, fromPos, movedByPiston);
		updatePoweredState(level, pos, state);
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
		return InteractionResult.PASS;
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

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

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

	/**
	 * 统一红石输入处理：基于一次输入采样判定是否触发绑定目标，避免持续高电平重复触发。
	 */
	protected final void updatePoweredState(Level level, BlockPos pos, BlockState state) {
		if (level.isClientSide) {
			return;
		}
		int signalStrength = resolveInputSignalStrength(level, pos);
		boolean hasSignal = signalStrength > 0;
		boolean wasPowered = state.getValue(POWERED);
		boolean shouldTrigger = shouldTriggerOnSignalUpdate(state, level, pos, wasPowered, hasSignal, signalStrength);
		if (hasSignal == wasPowered) {
			if (shouldTrigger) {
				onSignalTriggered(level, pos, state, wasPowered, hasSignal, signalStrength);
			}
			return;
		}

		BlockState updatedState = state.setValue(POWERED, hasSignal);
		level.setBlock(pos, updatedState, Block.UPDATE_ALL);
		if (shouldTrigger) {
			onSignalTriggered(level, pos, updatedState, wasPowered, hasSignal, signalStrength);
		}
	}

	/**
	 * 解析当前输入强度。
	 */
	protected int resolveInputSignalStrength(Level level, BlockPos pos) {
		return Math.max(0, level.getBestNeighborSignal(pos));
	}

	/**
	 * 钩子：基于本次输入采样决定是否触发。
	 * <p>
	 * 默认语义：按配置边沿模式（rising/falling/both）在 POWERED 变化时触发；
	 * 同态不触发。同步发射器可覆盖为“强度变化驱动”。
	 * </p>
	 */
	protected boolean shouldTriggerOnSignalUpdate(
		BlockState state,
		Level level,
		BlockPos pos,
		boolean wasPowered,
		boolean hasSignal,
		int signalStrength
	) {
		return wasPowered != hasSignal && RedstoneLinkConfig.emitterEdgeMode().shouldTrigger(wasPowered, hasSignal);
	}

	/**
	 * 钩子：触发成立后执行具体动作。
	 */
	protected void onSignalTriggered(
		Level level,
		BlockPos pos,
		BlockState updatedState,
		boolean wasPowered,
		boolean hasSignal,
		int signalStrength
	) {
		if (level.getBlockEntity(pos) instanceof LinkButtonBlockEntity buttonBlockEntity) {
			buttonBlockEntity.triggerLinkedTargets(null);
		}
	}
}
