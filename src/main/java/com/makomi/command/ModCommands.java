package com.makomi.command;

import com.makomi.command.audit.AuditCommandRegistry;
import com.makomi.command.bench.BenchCommandRegistry;
import com.makomi.command.crosschunk.CrossChunkCommandRegistry;
import com.makomi.command.input.InputCommandRegistry;
import com.makomi.command.link.LinkCommandRegistry;
import com.makomi.command.node.NodeCommandRegistry;
import com.makomi.command.place.PlaceCommandRegistry;
import com.makomi.config.RedstoneLinkConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;

/**
 * RedstoneLink 服务端命令入口。
 * <p>
 * 本类仅负责 `/redstonelink` 根命令装配，不承载具体业务命令实现。
 * </p>
 */
public final class ModCommands {
	private ModCommands() {
	}

	/**
	 * 注册 `/redstonelink` 命令树。
	 */
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands
				.literal("redstonelink")
				.requires(source -> source.hasPermission(RedstoneLinkConfig.command().permissionLevel()))
				.then(NodeCommandRegistry.createRoot())
				.then(InputCommandRegistry.createRoot())
				.then(LinkCommandRegistry.createRoot())
				.then(PlaceCommandRegistry.createRoot(registryAccess))
				.then(AuditCommandRegistry.createRoot())
				.then(CrossChunkCommandRegistry.createRoot())
				.then(BenchCommandRegistry.createRoot())
		));
	}
}
