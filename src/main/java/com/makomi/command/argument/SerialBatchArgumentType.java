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
 * 批量序号参数类型。
 * <p>
 * 仅负责读取一个以空格终止的 token，并校验字符集是否符合批量序号格式，
 * 具体区间语义仍由 `SerialParseUtil` 在业务层解析。
 * </p>
 */
public final class SerialBatchArgumentType implements ArgumentType<String> {
	private static final Collection<String> EXAMPLES = List.of("1:10", "1/3/4", "101:132/200");
	private static final SimpleCommandExceptionType EMPTY_EXCEPTION = new SimpleCommandExceptionType(
		new LiteralMessage("批量序号不能为空")
	);
	private static final DynamicCommandExceptionType INVALID_CHAR_EXCEPTION = new DynamicCommandExceptionType(
		value -> new LiteralMessage("批量序号包含非法字符: " + value)
	);

	private SerialBatchArgumentType() {
	}

	/**
	 * 创建批量序号参数类型实例。
	 */
	public static SerialBatchArgumentType serialBatch() {
		return new SerialBatchArgumentType();
	}

	/**
	 * 从命令上下文中读取批量序号原始文本。
	 *
	 * @param context 命令上下文
	 * @param name 参数名
	 * @return 原始批量序号文本
	 */
	public static String getSerialBatch(CommandContext<?> context, String name) {
		return context.getArgument(name, String.class);
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		return CommandTokenReadUtil.readRequiredToken(
			reader,
			SerialBatchArgumentType::isAllowedChar,
			EMPTY_EXCEPTION,
			INVALID_CHAR_EXCEPTION
		);
	}

	@Override
	public Collection<String> getExamples() {
		return EXAMPLES;
	}

	/**
	 * 判断字符是否属于批量序号允许的最小字符集。
	 */
	private static boolean isAllowedChar(char ch) {
		return (ch >= '0' && ch <= '9') || ch == ':' || ch == '/';
	}
}
