package com.makomi.command.argument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 批量序号参数类型测试。
 */
@Tag("stable-core")
class SerialBatchArgumentTypeTest {
	/**
	 * 应读取到空格前的完整批量序号 token。
	 */
	@Test
	void parseShouldReadTokenUntilWhitespace() throws CommandSyntaxException {
		StringReader reader = new StringReader("1:10/12 8");

		String parsed = SerialBatchArgumentType.serialBatch().parse(reader);

		assertEquals("1:10/12", parsed);
		assertTrue(reader.canRead());
		assertEquals(' ', reader.peek());
	}

	/**
	 * 非法字符应在参数层被提前拒绝。
	 */
	@Test
	void parseShouldRejectInvalidCharacters() {
		assertThrows(
			CommandSyntaxException.class,
			() -> SerialBatchArgumentType.serialBatch().parse(new StringReader("1,3"))
		);
	}

	/**
	 * 批量序号 token 结束后，后续整数参数应仍可继续解析。
	 */
	@Test
	void commandChainShouldContinueParsingFollowingInteger() throws CommandSyntaxException {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> serials = new AtomicReference<>();
		AtomicInteger periodTicks = new AtomicInteger(-1);
		dispatcher.register(
			LiteralArgumentBuilder
				.<Object>literal("square")
				.then(
					RequiredArgumentBuilder
						.<Object, String>argument("serials", SerialBatchArgumentType.serialBatch())
						.then(
							RequiredArgumentBuilder
								.<Object, Integer>argument("period_ticks", IntegerArgumentType.integer(1))
								.executes((context) -> {
									serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
									periodTicks.set(IntegerArgumentType.getInteger(context, "period_ticks"));
									return 1;
								})
						)
				)
		);

		int result = dispatcher.execute("square 1:10/12 4", new Object());

		assertEquals(1, result);
		assertEquals("1:10/12", serials.get());
		assertEquals(4, periodTicks.get());
	}
}
