package com.makomi.data;

import com.makomi.config.RedstoneLinkConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网页功能权限判定服务。
 * <p>
 * 统一收口 recording 与 graph 两条网页功能链路的服务端权限门，
 * 避免命令、物品与网络入口各自散落不同的配置读取逻辑。
 * </p>
 */
public final class WebFeaturePermissionService {
	private WebFeaturePermissionService() {
	}

	/**
	 * 判断玩家是否可使用 recording 网页功能。
	 */
	public static boolean canUseRecordingFeature(ServerPlayer player) {
		return player != null && com.makomi.command.CommandPermissionCompat.hasPermission(player, RedstoneLinkConfig.web().recordingPermissionLevel());
	}

	/**
	 * 判断玩家是否可使用 graph 网页功能。
	 */
	public static boolean canUseGraphFeature(ServerPlayer player) {
		return player != null && com.makomi.command.CommandPermissionCompat.hasPermission(player, RedstoneLinkConfig.web().graphPermissionLevel());
	}

	/**
	 * 判断命令源是否可使用 graph 网页功能。
	 * <p>
	 * bench / RCON 场景可能没有玩家实体，因此这里优先按命令源权限等级判断。
	 * </p>
	 */
	public static boolean canUseGraphFeature(CommandSourceStack source) {
		if (source == null) {
			return false;
		}
		ServerPlayer player = source.getPlayer();
		if (player != null) {
			return canUseGraphFeature(player);
		}
		return com.makomi.command.CommandPermissionCompat.hasPermission(source, RedstoneLinkConfig.web().graphPermissionLevel());
	}
}
