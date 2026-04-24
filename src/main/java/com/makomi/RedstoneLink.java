package com.makomi;

import com.makomi.command.ModCommands;
import com.makomi.command.argument.ModCommandArgumentTypes;
import com.makomi.data.ChannelDispatchScheduler;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.CoreDispatchBatchScheduler;
import com.makomi.data.CrossChunkDispatchService;
import com.makomi.data.InternalDispatchDeltaProjector;
import com.makomi.data.LinkDispatchFilterService;
import com.makomi.data.LinkNodeLifecycleDispatchEvents;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.data.NodeStateTraceService;
import com.makomi.data.PairableItemAggregateMenuNormalizationService;
import com.makomi.data.StatePanelRecordingSessionService;
import com.makomi.data.input.InputPlaybackService;
import com.makomi.network.BenchCommandNetwork;
import com.makomi.network.ChunkActivatorNetwork;
import com.makomi.network.LinkFilterNetwork;
import com.makomi.network.PairingNetwork;
import com.makomi.network.QuickLinkNetwork;
import com.makomi.network.RepeaterNetwork;
import com.makomi.network.StatePanelNetwork;
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
		BenchCommandNetwork.register();
		PairingNetwork.register();
		QuickLinkNetwork.register();
		LinkFilterNetwork.register();
		ChunkActivatorNetwork.register();
		RepeaterNetwork.register();
		ModBlocks.register();
		ModBlockEntities.register();
		ModItems.register();
		ModItemGroups.register();
		ModCommandArgumentTypes.register();
		ModCommands.register();
		InternalDispatchDeltaProjector.register();
		LinkNodeLifecycleDispatchEvents.register();
		LinkNodeRetireEvents.register();
		PairableItemAggregateMenuNormalizationService.register();
		LinkDispatchFilterService.register();
		NodeStateTraceService.register();
		if (RedstoneLinkConfig.command().inputEnabled()) {
			InputPlaybackService.register();
		}
		CrossChunkDispatchService.register();
		ChannelDispatchScheduler.register();
		CoreDispatchBatchScheduler.register();
		// recording 采样需要晚于输入投递与 scheduler flush；auto-stop 再通过网络层晚于 recording 采样。
		StatePanelRecordingSessionService.register();
		StatePanelNetwork.register();
		LOGGER.info("RedstoneLink initialized");
	}
}
