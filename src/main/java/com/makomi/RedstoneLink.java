package com.makomi;

import com.makomi.command.ModCommands;
import com.makomi.data.LinkNodeRetireEvents;
import com.makomi.network.PairingNetwork;
import com.makomi.registry.ModBlockEntities;
import com.makomi.registry.ModBlocks;
import com.makomi.registry.ModItemGroups;
import com.makomi.registry.ModItems;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedstoneLink implements ModInitializer {
	public static final String MOD_ID = "redstonelink";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PairingNetwork.register();
		ModBlocks.register();
		ModBlockEntities.register();
		ModItems.register();
		ModItemGroups.register();
		ModCommands.register();
		LinkNodeRetireEvents.register();
		LOGGER.info("RedstoneLink initialized");
	}
}
