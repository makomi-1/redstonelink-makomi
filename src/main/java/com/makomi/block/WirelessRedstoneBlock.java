package com.makomi.block;

import com.makomi.block.entity.WirelessRedstoneBlockEntity;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.NodeFaceSetBlockStateSupport;
import com.makomi.util.NeighborFanoutUtil;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线化红石块。
 * <p>
 * 由无线输入控制是否像原版红石块一样持续向六个方向输出弱信号。
 * 同时支持定向面编辑，仅对启用面输出原版红石块式邻居信号。
 * </p>
 */
public class WirelessRedstoneBlock extends Block implements EntityBlock {
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

	public WirelessRedstoneBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(NodeFaceSetBlockStateSupport.setAllFaces(stateDefinition.any().setValue(ACTIVE, false), true));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new WirelessRedstoneBlockEntity(blockPos, blockState);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		WirelessBlockNodeSupport.assignPlacedSerial(
			level,
			pos,
			stack,
			com.makomi.data.LinkNodeType.CORE,
			WirelessRedstoneBlockEntity.class
		);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		return WirelessBlockNodeSupport.inheritDrops(
			this,
			builder,
			super.getDrops(state, builder),
			WirelessRedstoneBlockEntity.class
		);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		boolean movingByWirelessPiston = WirelessPistonNodeMoveSupport.isMoveInProgress(level, pos);
		if (!movingByWirelessPiston) {
			WirelessBlockNodeSupport.unregisterOnRemove(
				this,
				pos,
				newState.getBlock(),
				level,
				WirelessRedstoneBlockEntity.class
			);
			if (!state.is(newState.getBlock())) {
				NeighborFanoutUtil.notifyCenterAndSixNeighbors(level, pos, state.getBlock());
			}
		}
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
		// 无线化红石块不读取原版邻居红石输入。
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
		return InteractionResult.PASS;
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.getBlockEntity(pos) instanceof WirelessRedstoneBlockEntity blockEntity) {
			blockEntity.onPulseTick();
		}
	}

	@Override
	protected boolean isSignalSource(BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return state.getValue(ACTIVE) && NodeFaceSetBlockStateSupport.isFaceEnabled(state, direction) ? 15 : 0;
	}

	@Override
	protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		// 对齐原版红石块语义：只提供邻居弱信号，不额外做强激活。
		return 0;
	}

	/**
	 * 面编辑后主动补一拍邻居扇出，确保关闭/打开输出面时周围红石网络立即收敛。
	 */
	public final void refreshStateFromCurrentInputs(Level level, BlockPos pos, BlockState state) {
		if (level == null || pos == null || state == null || level.isClientSide) {
			return;
		}
		NeighborFanoutUtil.notifyCenterAndSixNeighbors(level, pos, state.getBlock());
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ACTIVE);
		NodeFaceSetBlockStateSupport.appendProperties(builder);
	}
}
