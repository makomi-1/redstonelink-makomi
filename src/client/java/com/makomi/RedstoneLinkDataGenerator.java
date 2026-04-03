package com.makomi;

import com.makomi.datagen.RedstoneLinkRecipeProvider;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * RedstoneLink 数据生成入口。
 */
public class RedstoneLinkDataGenerator implements DataGeneratorEntrypoint {
	/**
	 * 初始化 Fabric 数据生成器。
	 *
	 * @param fabricDataGenerator Fabric 提供的数据生成器上下文
	 */
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
		pack.addProvider(RedstoneLinkRecipeProvider::new);
	}
}
