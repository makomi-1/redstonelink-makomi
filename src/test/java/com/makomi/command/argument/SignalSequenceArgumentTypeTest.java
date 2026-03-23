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
 * 输入序列参数类型测试。
 */
@Tag("stable-core")
class SignalSequenceArgumentTypeTest {
	/**
	 * 应读取到空格前的完整输入序列 token。
	 */
	@Test
	void parseShouldReadTokenUntilWhitespace() throws CommandSyntaxException {
		StringReader reader = new StringReader("15/0/7/0 2");

		String parsed = SignalSequenceArgumentType.signalSequence().parse(reader);

		assertEquals("15/0/7/0", parsed);
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
			() -> SignalSequenceArgumentType.signalSequence().parse(new StringReader("0g01"))
		);
	}

	/**
	 * 输入序列 token 结束后，后续整数参数应仍可继续解析。
	 */
	@Test
	void commandChainShouldContinueParsingFollowingInteger() throws CommandSyntaxException {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		AtomicReference<String> serials = new AtomicReference<>();
		AtomicReference<String> sequence = new AtomicReference<>();
		AtomicInteger phaseTicks = new AtomicInteger(-1);
		dispatcher.register(
			LiteralArgumentBuilder
				.<Object>literal("custom")
				.then(
					RequiredArgumentBuilder
						.<Object, String>argument("serials", SerialBatchArgumentType.serialBatch())
						.then(
							RequiredArgumentBuilder
								.<Object, String>argument("sequence", SignalSequenceArgumentType.signalSequence())
								.then(
									RequiredArgumentBuilder
										.<Object, Integer>argument("phase_ticks", IntegerArgumentType.integer(0))
										.executes((context) -> {
											serials.set(SerialBatchArgumentType.getSerialBatch(context, "serials"));
											sequence.set(SignalSequenceArgumentType.getSignalSequence(context, "sequence"));
											phaseTicks.set(IntegerArgumentType.getInteger(context, "phase_ticks"));
											return 1;
										})
								)
						)
				)
		);

		int result = dispatcher.execute("custom 1/3/4 15/0/7/0 2", new Object());

		assertEquals(1, result);
		assertEquals("1/3/4", serials.get());
		assertEquals("15/0/7/0", sequence.get());
		assertEquals(2, phaseTicks.get());
	}
}
