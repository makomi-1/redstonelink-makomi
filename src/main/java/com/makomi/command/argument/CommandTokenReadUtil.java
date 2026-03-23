package com.makomi.command.argument;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

/**
 * 命令 token 读取工具。
 * <p>
 * 用于读取“从当前游标到下一个空白字符”为止的参数片段，
 * 便于保留后续参数树，同时为领域参数类型补上最小字符集约束。
 * </p>
 */
public final class CommandTokenReadUtil {
	private CommandTokenReadUtil() {
	}

	/**
	 * token 字符判定器。
	 */
	@FunctionalInterface
	public interface TokenCharPredicate {
		/**
		 * 判断当前字符是否允许出现在 token 中。
		 *
		 * @param ch 当前字符
		 * @return `true` 表示允许继续读取
		 */
		boolean test(char ch);
	}

	/**
	 * 从当前游标读取一个必填 token。
	 *
	 * @param reader Brigadier 字符读取器
	 * @param charPredicate token 合法字符判定器
	 * @param emptyException 空 token 时抛出的异常
	 * @param invalidCharException 遇到非法字符时抛出的异常
	 * @return 读取到的 token 文本
	 * @throws CommandSyntaxException 参数为空或存在非法字符
	 */
	public static String readRequiredToken(
		StringReader reader,
		TokenCharPredicate charPredicate,
		SimpleCommandExceptionType emptyException,
		DynamicCommandExceptionType invalidCharException
	) throws CommandSyntaxException {
		int startCursor = reader.getCursor();
		if (!reader.canRead() || Character.isWhitespace(reader.peek())) {
			throw emptyException.createWithContext(reader);
		}
		while (reader.canRead()) {
			char ch = reader.peek();
			if (Character.isWhitespace(ch)) {
				break;
			}
			if (!charPredicate.test(ch)) {
				throw invalidCharException.createWithContext(reader, String.valueOf(ch));
			}
			reader.skip();
		}
		return reader.getString().substring(startCursor, reader.getCursor());
	}
}
