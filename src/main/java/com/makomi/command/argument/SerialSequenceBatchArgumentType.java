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
 * 多 `serials@sequence` 批量 token 参数类型。
 * <p>
 * bench/internal 命令使用该参数一次性描述多组：
 * `serial_batch@custom_sequence`，组与组之间使用 `;` 分隔。
 * </p>
 */
public final class SerialSequenceBatchArgumentType implements ArgumentType<String> {
	private static final Collection<String> EXAMPLES = List.of(
		"1@15/0/15/0;2@14/0/0/0",
		"1:4@f0f0;8@13/0/0/13"
	);
	private static final SimpleCommandExceptionType EMPTY_EXCEPTION = new SimpleCommandExceptionType(
		new LiteralMessage("serials@sequence 批量 token 不能为空")
	);
	private static final DynamicCommandExceptionType INVALID_CHAR_EXCEPTION = new DynamicCommandExceptionType(
		value -> new LiteralMessage("serials@sequence 批量 token 包含非法字符: " + value)
	);

	private SerialSequenceBatchArgumentType() {
	}

	/**
	 * 创建参数类型实例。
	 */
	public static SerialSequenceBatchArgumentType serialSequenceBatch() {
		return new SerialSequenceBatchArgumentType();
	}

	/**
	 * 从命令上下文中读取原始批量 token。
	 */
	public static String getSerialSequenceBatch(CommandContext<?> context, String name) {
		return context.getArgument(name, String.class);
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		return CommandTokenReadUtil.readRequiredToken(
			reader,
			SerialSequenceBatchArgumentType::isAllowedChar,
			EMPTY_EXCEPTION,
			INVALID_CHAR_EXCEPTION
		);
	}

	@Override
	public Collection<String> getExamples() {
		return EXAMPLES;
	}

	/**
	 * 允许序号批量、序列批量和批次分隔符的最小字符集。
	 */
	private static boolean isAllowedChar(char ch) {
		return (ch >= '0' && ch <= '9')
			|| (ch >= 'a' && ch <= 'f')
			|| (ch >= 'A' && ch <= 'F')
			|| ch == ':'
			|| ch == '/'
			|| ch == ','
			|| ch == '|'
			|| ch == ';'
			|| ch == '@';
	}
}
