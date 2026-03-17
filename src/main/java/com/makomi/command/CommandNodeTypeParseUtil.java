package com.makomi.command;

import com.makomi.data.LinkNodeSemantics;
import com.makomi.data.LinkNodeType;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * 命令层节点类型解析工具。
 * <p>
 * 统一 canonical 词表（triggerSource/core）解析失败反馈，避免各命令注册器重复实现。
 * </p>
 */
public final class CommandNodeTypeParseUtil {
	private CommandNodeTypeParseUtil() {
	}

	/**
	 * 解析 canonical 类型并在失败时发送指定错误消息。
	 *
	 * @param source 命令来源
	 * @param rawType 原始类型参数
	 * @param invalidMessageFactory 失败消息构造器
	 * @return 解析成功返回类型；失败返回 null
	 */
	public static LinkNodeType parseCanonicalTypeOrSendFailure(
		CommandSourceStack source,
		String rawType,
		Function<String, Component> invalidMessageFactory
	) {
		var parsedType = LinkNodeSemantics.tryParseCanonicalType(rawType);
		if (parsedType.isPresent()) {
			return parsedType.get();
		}
		source.sendFailure(invalidMessageFactory.apply(rawType));
		return null;
	}

	/**
	 * 解析 canonical 类型并在失败时发送默认“invalid_type”消息。
	 *
	 * @param source 命令来源
	 * @param rawType 原始类型参数
	 * @return 解析成功返回类型；失败返回 null
	 */
	public static LinkNodeType parseCanonicalTypeOrSendDefaultFailure(CommandSourceStack source, String rawType) {
		return parseCanonicalTypeOrSendFailure(
			source,
			rawType,
			value -> Component.translatable("message.redstonelink.node.invalid_type", value)
		);
	}
}
