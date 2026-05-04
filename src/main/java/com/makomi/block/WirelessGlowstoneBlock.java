package com.makomi.block;

import java.util.function.Supplier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 无线化萤石。
 * <p>
 * 当前原型保留萤石恒亮外观，仅把节点身份与远外显接入无线 `core` 体系。
 * </p>
 */
public class WirelessGlowstoneBlock extends WirelessDecorativeLightBlock {
	public WirelessGlowstoneBlock(
		BlockBehaviour.Properties properties,
		Supplier<? extends BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity>> blockEntityTypeSupplier
	) {
		super(properties, 15, blockEntityTypeSupplier);
	}
}
