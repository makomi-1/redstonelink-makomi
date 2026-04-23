package com.makomi.block.entity;

import com.makomi.data.LinkNodeType;
import com.makomi.data.LinkSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 已放置节点打开配对 GUI 前的序列号自愈支撑。
 * <p>
 * 统一复用放置链路已有的序列号解析规则：
 * 未分配、已退役、分配集合缺口、位置冲突都交给
 * {@link LinkSavedData#resolvePlacementSerial(LinkNodeType, long, ResourceKey, BlockPos)}
 * 处理，再按需回写到方块实体。
 * </p>
 */
public final class PlacedPairableNodeGuiOpenSupport {
	private PlacedPairableNodeGuiOpenSupport() {
	}

	/**
	 * 为已放置节点解析“本次打开 GUI 应使用的有效 serial”。
	 * <p>
	 * 若解析结果与当前方块实体 serial 不一致，会同步回写，
	 * 从而复用 {@link PairableNodeBlockEntity#setLinkData(long)} 的在线注册刷新逻辑。
	 * </p>
	 *
	 * @param level 服务端维度
	 * @param pos 当前节点坐标
	 * @param nodeBlockEntity 已放置节点实体
	 * @return 可用于继续打开 GUI 的有效 serial；无法解析时返回 {@code 0}
	 */
	public static long ensureSerialReadyForPairingOpen(
		ServerLevel level,
		BlockPos pos,
		PairableNodeBlockEntity nodeBlockEntity
	) {
		if (level == null || pos == null || nodeBlockEntity == null || nodeBlockEntity.getLinkNodeType() == null) {
			return 0L;
		}
		long currentSerial = nodeBlockEntity.getSerial();
		long resolvedSerial = resolveSerialForPairingOpen(
			LinkSavedData.get(level),
			nodeBlockEntity.getLinkNodeType(),
			currentSerial,
			level.dimension(),
			pos
		);
		if (resolvedSerial > 0L && resolvedSerial != currentSerial) {
			nodeBlockEntity.setLinkData(resolvedSerial);
		}
		return resolvedSerial;
	}

	/**
	 * 解析 reopen GUI 时应使用的 serial。
	 * <p>
	 * 单元测试直接覆盖这层纯逻辑，避免为服务端方块实体搭建额外桩。
	 * </p>
	 */
	static long resolveSerialForPairingOpen(
		LinkSavedData savedData,
		LinkNodeType nodeType,
		long currentSerial,
		ResourceKey<Level> dimension,
		BlockPos pos
	) {
		if (savedData == null || nodeType == null || dimension == null || pos == null) {
			return 0L;
		}
		return savedData.resolvePlacementSerial(nodeType, currentSerial, dimension, pos);
	}
}
