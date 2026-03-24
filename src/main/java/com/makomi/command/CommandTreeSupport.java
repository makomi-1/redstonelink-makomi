package com.makomi.command;

import com.makomi.command.semantic.SemanticCommandMessageAdapter;
import com.makomi.config.RedstoneLinkConfig;
import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import com.makomi.util.SerialCollectionFormatUtil;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 命令树共享支撑工具。
 * <p>
 * 本类只承载跨子注册器复用的轻量命令层能力，不下沉业务逻辑。
 * </p>
 */
public final class CommandTreeSupport {
	private static final int SERIAL_LIST_MAX_ITEMS = 50;
	private static final int SERIAL_LIST_MAX_CHARS = 300;

	private CommandTreeSupport() {
	}

	/**
	 * 统一“其他权限”命令组权限校验。
	 */
	public static boolean hasOtherCommandPermission(CommandSourceStack source) {
		return source.hasPermission(RedstoneLinkConfig.otherCommandPermissionLevel());
	}

	/**
	 * 校验当前命令源是否允许执行测试专用命令。
	 * <p>
	 * 默认仍要求玩家源；仅在命令测试模式开启时，才允许控制台/RCON 直接执行。
	 * </p>
	 */
	public static boolean allowPlayerSourceOrBenchmarkMode(CommandSourceStack source) {
		if (source.getPlayer() != null) {
			return true;
		}
		if (RedstoneLinkConfig.commandBenchmarkModeEnabled()) {
			return true;
		}
		source.sendFailure(Component.translatable("message.redstonelink.player_only"));
		return false;
	}

	/**
	 * 解析节点类型参数。
	 */
	public static LinkNodeType parseNodeTypeArg(CommandSourceStack source, String rawType) {
		return CommandNodeTypeParseUtil.parseCanonicalTypeOrSendFailure(
			source,
			rawType,
			SemanticCommandMessageAdapter::invalidType
		);
	}

	/**
	 * 读取可选整型参数，不存在时返回默认值。
	 */
	public static int getOptionalIntArg(
		CommandContext<CommandSourceStack> context,
		String argumentName,
		int defaultValue
	) {
		try {
			return IntegerArgumentType.getInteger(context, argumentName);
		} catch (IllegalArgumentException ignored) {
			return defaultValue;
		}
	}

	/**
	 * 返回语义化命令类型名。
	 */
	public static String typeCommandName(LinkNodeType type) {
		return LinkNodeSemantics.toSemanticName(type);
	}

	/**
	 * 序列化方块坐标文本。
	 */
	public static String formatBlockPos(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	/**
	 * 以稳定升序格式化序列号列表。
	 */
	public static String formatSerialList(List<Long> serials) {
		return formatSerialCollection(serials);
	}

	/**
	 * 以稳定升序格式化序列号集合。
	 */
	public static String formatSerialSet(Set<Long> serials) {
		return formatSerialCollection(serials);
	}

	/**
	 * 统一格式化序列号集合，限制输出数量与总字符长度，避免命令反馈过长。
	 */
	public static String formatSerialCollection(Collection<Long> serials) {
		return SerialCollectionFormatUtil.formatSortedCsvLimited(
			serials,
			SERIAL_LIST_MAX_ITEMS,
			SERIAL_LIST_MAX_CHARS
		);
	}
}
