package com.makomi.block;

import com.mojang.serialization.MapCodec;
import com.makomi.block.entity.LinkCoreBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CurrentLinksPrivacyService;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import com.makomi.network.PairingNetwork;
import com.makomi.util.NeighborFanoutUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 核心节点方块。
 * <p>
 * 作为被触发目标输出红石功率：激活态输出配置功率，非激活态输出 0。
 * 同时承载序列号持久化、配对界面打开与脉冲回落调度。
 * </p>
 */
public class LinkCoreBlock extends BaseEntityBlock {
	public static final MapCodec<LinkCoreBlock> CODEC = simpleCodec(LinkCoreBlock::new);
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

	public LinkCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new LinkCoreBlockEntity(blockPos, blockState);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState blockState) {
		return RenderShape.MODEL;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof LinkCoreBlockEntity coreBlockEntity)) {
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

		if (!(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof LinkCoreBlockEntity coreBlockEntity)) {
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
					LinkItemData.setLinkedSerials(
						drop,
						CurrentLinksPrivacyService.resolveItemSnapshotTargets(
							serverLevel,
							LinkNodeType.CORE,
							serial,
							LinkSavedData.get(serverLevel).getLinkedButtons(serial)
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
			if (level.getBlockEntity(pos) instanceof LinkCoreBlockEntity coreBlockEntity) {
				coreBlockEntity.unregisterNode(true);
			}
			if (!level.isClientSide) {
				// 核心块被破坏时主动补齐二级扇出：中心 + 六方向。
				// 目的：让与核心块相邻及次邻接的红石网络在同 tick 内完成收敛。
				NeighborFanoutUtil.notifyCenterAndSixNeighbors(level, pos, state.getBlock());
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return resolveOutputPower(state, level, pos);
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return resolveOutputPower(state, level, pos);
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

		if (!level.isClientSide && level.getBlockEntity(pos) instanceof LinkCoreBlockEntity coreBlockEntity) {
			coreBlockEntity.triggerByPlayer();
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ACTIVE);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof LinkCoreBlockEntity coreBlockEntity) {
			// 脉冲模式到点回落。
			coreBlockEntity.onPulseTick();
		}
	}

	/**
	 * 打开核心配对界面；若尚未分配序列号则先分配。
	 */
	private static void openPairingScreen(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof LinkCoreBlockEntity coreBlockEntity) {
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

	/**
	 * 解析核心输出功率。
	 * <p>
	 * 优先读取目标实体解析结果（可承载 sync 强度）；实体缺失时回退到旧逻辑。
	 * </p>
	 */
	private static int resolveOutputPower(BlockState state, BlockGetter level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof LinkCoreBlockEntity coreBlockEntity) {
			return coreBlockEntity.getResolvedOutputPower();
		}
		return state.getValue(ACTIVE) ? RedstoneLinkConfig.coreOutputPower() : 0;
	}

}
