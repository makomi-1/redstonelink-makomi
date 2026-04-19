package com.makomi.command.bench;

import com.makomi.command.CommandTreeSupport;
import com.makomi.config.RedstoneLinkConfig;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * bench/internal 命令树入口。
 * <p>
 * 仅在 benchmark mode 开启时暴露，避免污染普通玩家命令树。
 * </p>
 */
public final class BenchCommandRegistry {
	private BenchCommandRegistry() {
	}

	/**
	 * 构建 `/redstonelink bench` 命令树。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> createRoot() {
		return Commands
			.literal("bench")
			.requires(source -> RedstoneLinkConfig.command().benchmarkModeEnabled() && CommandTreeSupport.hasOtherCommandPermission(source))
			.then(BenchInputCommandRegistry.createRoot())
			.then(BenchLinkCommandRegistry.createRoot())
			.then(BenchGraphCommandRegistry.createRoot())
			.then(BenchOccCommandRegistry.createRoot())
			.then(BenchTraceCommandRegistry.createRoot());
	}
}
