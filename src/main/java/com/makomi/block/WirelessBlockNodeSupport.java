package com.makomi.block;

import com.makomi.block.entity.PlacedPairableNodeGuiOpenSupport;
import com.makomi.block.entity.PairableNodeBlockEntity;
import com.makomi.data.LinkGuiDisplayContext;
import com.makomi.data.LinkItemData;
import com.makomi.data.LinkNodeType;
import com.makomi.network.PairingNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * 无线化节点方块公共辅助工具。
 * <p>
 * 统一封装 serial 放置继承、掉落回写、物理移除注销与配对界面打开逻辑，
 * 让无线化原版件的具体方块只关注原版行为转接本身。
 * </p>
 */
public final class WirelessBlockNodeSupport {
	private WirelessBlockNodeSupport() {}

	/**
	 * 在服务端把物品 serial 继承到放置后的节点实体。
	 */
	public static void assignPlacedSerial(
		Level level,
		BlockPos pos,
		ItemStack stack,
		LinkNodeType nodeType,
		Class<? extends PairableNodeBlockEntity> blockEntityClass
	) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!blockEntityClass.isInstance(level.getBlockEntity(pos))) {
			return;
		}
		PairableNodeBlockEntity blockEntity = (PairableNodeBlockEntity) level.getBlockEntity(pos);
		long serial = LinkItemData.resolvePlacementSerial(stack, serverLevel, nodeType, pos);
		blockEntity.setLinkData(serial);
	}

	/**
	 * 把节点实体上的 serial 与链接快照回写到掉落物。
	 */
	public static List<ItemStack> inheritDrops(
		Block block,
		LootParams.Builder builder,
		List<ItemStack> originalDrops,
		Class<? extends PairableNodeBlockEntity> blockEntityClass
	) {
		List<ItemStack> drops = new ArrayList<>(originalDrops);
		if (drops.isEmpty()) {
			drops.add(new ItemStack(block.asItem()));
		}
		if (!blockEntityClass.isInstance(builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY))) {
			return drops;
		}
		PairableNodeBlockEntity blockEntity = (PairableNodeBlockEntity) builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		if (blockEntity == null || blockEntity.getSerial() <= 0L) {
			return drops;
		}
		for (ItemStack drop : drops) {
			if (!drop.is(block.asItem())) {
				continue;
			}
			LinkItemData.setSerial(drop, blockEntity.getSerial());
			LinkItemData.setDestroyRetireCandidate(drop, true);
			if (blockEntity.getLevel() instanceof ServerLevel serverLevel) {
				LinkItemData.syncCurrentLinksSnapshotIfSingle(drop, serverLevel);
			}
		}
		return drops;
	}

	/**
	 * 在真实替换时注销旧节点，避免在线表残留。
	 */
	public static void unregisterOnRemove(
		Block block,
		BlockPos pos,
		Block newBlock,
		Level level,
		Class<? extends PairableNodeBlockEntity> blockEntityClass
	) {
		if (block == newBlock) {
			return;
		}
		if (!blockEntityClass.isInstance(level.getBlockEntity(pos))) {
			return;
		}
		PairableNodeBlockEntity blockEntity = (PairableNodeBlockEntity) level.getBlockEntity(pos);
		blockEntity.markPhysicalRemovalInProgress();
		blockEntity.unregisterNode(true);
	}

	/**
	 * 打开 triggerSource 配对界面。
	 */
	public static void openTriggerSourcePairing(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof PairableNodeBlockEntity blockEntity)) {
			return;
		}
		long serial = PlacedPairableNodeGuiOpenSupport.ensureSerialReadyForPairingOpen(serverLevel, pos, blockEntity);
		if (serial <= 0L) {
			return;
		}
		PairingNetwork.openTriggerSourcePairing(
			serverPlayer,
			serial,
			LinkGuiDisplayContext.resolvePairingContextToken(level.getBlockState(pos).getBlock(), LinkNodeType.TRIGGER_SOURCE)
		);
	}

	/**
	 * 打开 core 配对界面。
	 */
	public static void openCorePairing(Level level, BlockPos pos, Player player) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof PairableNodeBlockEntity blockEntity)) {
			return;
		}
		long serial = PlacedPairableNodeGuiOpenSupport.ensureSerialReadyForPairingOpen(serverLevel, pos, blockEntity);
		if (serial <= 0L) {
			return;
		}
		PairingNetwork.openCorePairing(
			serverPlayer,
			serial,
			LinkGuiDisplayContext.resolvePairingContextToken(level.getBlockState(pos).getBlock(), LinkNodeType.CORE)
		);
	}
}
