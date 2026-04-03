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
 * 多 `serials@sequence` 批量 token 参数类型测试。
 */
@Tag("stable-core")
class SerialSequenceBatchArgumentTypeTest {
	/**
	 * 应读取到空格前的完整批量 token。
	 */
	@Test
	void parseShouldReadTokenUntilWhitespace() throws CommandSyntaxException {
		StringReader reader = new StringReader("1@15/0/15/0;2@14/0/0/0 8");

		String parsed = SerialSequenceBatchArgumentType.serialSequenceBatch().parse(reader);

		assertEquals("1@15/0/15/0;2@14/0/0/0", parsed);
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
			() -> SerialSequenceBatchArgumentType.serialSequenceBatch().parse(new StringReader("1@15/0?"))
		);
	}

	/**
	 * 批量 token 结束后，后续整数参数应仍可继续解析。
	 */
	@Test
	void commandChainShouldContinueParsingFollowingInteger() throws CommandSyntaxException {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> batchToken = new AtomicReference<>();
		AtomicInteger totalTicks = new AtomicInteger(-1);
		dispatcher.register(
			LiteralArgumentBuilder
				.<Object>literal("custom_batch")
				.then(
					RequiredArgumentBuilder
						.<Object, String>argument("serial_sequence_batch", SerialSequenceBatchArgumentType.serialSequenceBatch())
						.then(
							RequiredArgumentBuilder
								.<Object, Integer>argument("total_ticks", IntegerArgumentType.integer(0))
								.executes((context) -> {
									batchToken.set(
										SerialSequenceBatchArgumentType.getSerialSequenceBatch(context, "serial_sequence_batch")
									);
									totalTicks.set(IntegerArgumentType.getInteger(context, "total_ticks"));
									return 1;
								})
						)
				)
		);

		int result = dispatcher.execute("custom_batch 1@15/0/15/0;2@14/0/0/0 8", new Object());

		assertEquals(1, result);
		assertEquals("1@15/0/15/0;2@14/0/0/0", batchToken.get());
		assertEquals(8, totalTicks.get());
	}
}
