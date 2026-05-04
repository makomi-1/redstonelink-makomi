package com.makomi.block;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.function.Supplier;

/**
 * 无线化灵魂灯笼。
 * <p>
 * 行为与无线化灯笼一致，仅复用灵魂灯笼的原版资源。
 * </p>
 */
public class WirelessSoulLanternBlock extends WirelessLanternBlock {
	public WirelessSoulLanternBlock(
		BlockBehaviour.Properties properties,
		Supplier<? extends BlockEntityType<? extends com.makomi.block.entity.PairableNodeBlockEntity>> blockEntityTypeSupplier
	) {
		super(properties, 10, blockEntityTypeSupplier);
	}
}
