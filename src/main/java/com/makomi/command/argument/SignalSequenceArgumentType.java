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
 * 输入序列参数类型。
 * <p>
 * 仅限制 token 的字符集与空格终止行为，
 * 具体波形语义仍交由 `InputWaveformSpec.parseCustomSequence` 处理。
 * </p>
 */
public final class SignalSequenceArgumentType implements ArgumentType<String> {
	private static final Collection<String> EXAMPLES = List.of("0101", "f0f0", "15/0/7/0");
	private static final SimpleCommandExceptionType EMPTY_EXCEPTION = new SimpleCommandExceptionType(
		new LiteralMessage("输入序列不能为空")
	);
	private static final DynamicCommandExceptionType INVALID_CHAR_EXCEPTION = new DynamicCommandExceptionType(
		value -> new LiteralMessage("输入序列包含非法字符: " + value)
	);

	private SignalSequenceArgumentType() {
	}

	/**
	 * 创建输入序列参数类型实例。
	 */
	public static SignalSequenceArgumentType signalSequence() {
		return new SignalSequenceArgumentType();
	}

	/**
	 * 从命令上下文中读取输入序列原始文本。
	 *
	 * @param context 命令上下文
	 * @param name 参数名
	 * @return 原始输入序列文本
	 */
	public static String getSignalSequence(CommandContext<?> context, String name) {
		return context.getArgument(name, String.class);
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		return CommandTokenReadUtil.readRequiredToken(
			reader,
			SignalSequenceArgumentType::isAllowedChar,
			EMPTY_EXCEPTION,
			INVALID_CHAR_EXCEPTION
		);
	}

	@Override
	public Collection<String> getExamples() {
		return EXAMPLES;
	}

	/**
	 * 判断字符是否属于输入序列允许的最小字符集。
	 */
	private static boolean isAllowedChar(char ch) {
		return (ch >= '0' && ch <= '9')
			|| (ch >= 'a' && ch <= 'f')
			|| (ch >= 'A' && ch <= 'F')
			|| ch == '/'
			|| ch == ','
			|| ch == ':'
			|| ch == '|';
	}
}
