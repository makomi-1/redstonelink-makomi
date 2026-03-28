package com.makomi.command.argument;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import java.util.List;

/**
 * `key=value` 风格 token 参数类型。
 * <p>
 * 仅负责读取一个空格分隔 token，并允许 bench/internal 命令使用无引号的
 * `fanout=16`、`wrap=true` 这类参数。
 * </p>
 */
public final class KeyValueTokenArgumentType implements ArgumentType<String> {
	private static final Collection<String> EXAMPLES = List.of("fanout=16", "stride=16", "wrap=true");
	private static final SimpleCommandExceptionType EMPTY_EXCEPTION = new SimpleCommandExceptionType(
		new LiteralMessage("key=value token 不能为空")
	);
	private static final DynamicCommandExceptionType INVALID_CHAR_EXCEPTION = new DynamicCommandExceptionType(
		value -> new LiteralMessage("key=value token 包含非法字符: " + value)
	);

	private KeyValueTokenArgumentType() {
	}

	/**
	 * 创建参数类型实例。
	 */
	public static KeyValueTokenArgumentType keyValueToken() {
		return new KeyValueTokenArgumentType();
	}

	/**
	 * 从命令上下文中读取 token。
	 */
	public static String getKeyValueToken(CommandContext<?> context, String name) {
		return context.getArgument(name, String.class);
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		return CommandTokenReadUtil.readRequiredToken(
			reader,
			KeyValueTokenArgumentType::isAllowedChar,
			EMPTY_EXCEPTION,
			INVALID_CHAR_EXCEPTION
		);
	}

	@Override
	public Collection<String> getExamples() {
		return EXAMPLES;
	}

	/**
	 * 允许字母、数字、下划线、连字符与 `=`。
	 */
	private static boolean isAllowedChar(char ch) {
		return (ch >= '0' && ch <= '9')
			|| (ch >= 'a' && ch <= 'z')
			|| (ch >= 'A' && ch <= 'Z')
			|| ch == '_'
			|| ch == '-'
			|| ch == '=';
	}
}
