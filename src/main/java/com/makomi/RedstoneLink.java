package com.makomi;

import com.makomi.command.ModCommands;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CrossChunkDispatchService;
import com.makomi.data.InternalDispatchDeltaProjector;
import com.makomi.data.LinkNodeLifecycleDispatchEvents;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.NodeStateTraceService;
import com.makomi.network.PairingNetwork;
import com.makomi.registry.ModBlockEntities;
import com.makomi.registry.ModBlocks;
import com.makomi.registry.ModItemGroups;
import com.makomi.registry.ModItems;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RedstoneLink 服务端主入口。
 */
public class RedstoneLink implements ModInitializer {
	public static final String MOD_ID = "redstonelink";

	// 使用 mod id 作为 logger 名称，便于在混合日志中快速定位来源。
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 初始化顺序：配置 -> 网络 -> 注册表 -> 命令 -> 事件。
		RedstoneLinkConfig.load();
		PairingNetwork.register();
		ModBlocks.register();
		ModBlockEntities.register();
		ModItems.register();
		ModItemGroups.register();
		ModCommands.register();
		InternalDispatchDeltaProjector.register();
		LinkNodeLifecycleDispatchEvents.register();
		LinkNodeRetireEvents.register();
		NodeStateTraceService.register();
		CrossChunkDispatchService.register();
		LOGGER.info("RedstoneLink initialized");
	}
}
