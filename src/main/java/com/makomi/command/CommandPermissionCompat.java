package com.makomi.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * 兼容 1.21.11 `CommandSourceStack` 权限接口的统一适配层。
 * <p>
 * 旧版本直接暴露 `hasPermission(int)`，新版本改为 `PermissionSet`；
 * 本类负责把旧的 0~4 权限等级映射为当前命令权限对象，避免业务层四处感知底层差异。
 * </p>
 */
public final class CommandPermissionCompat {
	private CommandPermissionCompat() {
	}

	/**
	 * 判断命令源是否具备给定权限等级。
	 */
	public static boolean hasPermission(CommandSourceStack source, int permissionLevel) {
		if (source == null) {
			return false;
		}
		if (permissionLevel <= 0) {
			return true;
		}
		int normalizedLevel = Math.min(4, Math.max(0, permissionLevel));
		return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(normalizedLevel)));
	}

	/**
	 * 判断玩家是否具备给定权限等级。
	 */
	public static boolean hasPermission(ServerPlayer player, int permissionLevel) {
		if (player == null) {
			return false;
		}
		if (permissionLevel <= 0) {
			return true;
		}
		int normalizedLevel = Math.min(4, Math.max(0, permissionLevel));
		return player.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(normalizedLevel)));
	}
}
