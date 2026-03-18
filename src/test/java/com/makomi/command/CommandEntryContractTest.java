package com.makomi.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.makomi.command.crosschunk.CrossChunkCommandRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 命令出入口解析契约测试。
 */
@Tag("stable-core")
class CommandEntryContractTest {
	/**
	 * link set 的 confirm 后缀应被识别并剥离。
	 */
	@Test
	void modCommandsConfirmSuffixShouldParseAndStripSuffix() throws Exception {
		Object parseResult = invokeModConfirmSuffixParse("1:3/9 confirm");
		assertEquals("1:3/9", readRecordString(parseResult, "payload"));
		assertTrue(readRecordBoolean(parseResult, "confirmed"));
	}

	/**
	 * link set 后缀解析保持大小写敏感，防止误匹配非标准指令。
	 */
	@Test
	void modCommandsConfirmSuffixShouldKeepCaseSensitiveBehavior() throws Exception {
		Object parseResult = invokeModConfirmSuffixParse("1:3/9 Confirm");
		assertEquals("1:3/9 Confirm", readRecordString(parseResult, "payload"));
		assertFalse(readRecordBoolean(parseResult, "confirmed"));
	}

	/**
	 * crosschunk whitelist set 支持 resident + confirm 组合后缀。
	 */
	@Test
	void crossChunkConfirmSuffixShouldSupportResidentAndConfirm() throws Exception {
		Object parseResult = invokeCrossChunkConfirmSuffixParse("1:3 resident confirm");
		assertEquals("1:3", readRecordString(parseResult, "payload"));
		assertTrue(readRecordBoolean(parseResult, "resident"));
		assertTrue(readRecordBoolean(parseResult, "confirmed"));
	}

	/**
	 * crosschunk 后缀解析应大小写不敏感，且支持 confirm/resident 顺序互换。
	 */
	@Test
	void crossChunkConfirmSuffixShouldSupportCaseInsensitiveAndReverseOrder() throws Exception {
		Object parseResult = invokeCrossChunkConfirmSuffixParse("1:3 CONFIRM RESIDENT");
		assertEquals("1:3", readRecordString(parseResult, "payload"));
		assertTrue(readRecordBoolean(parseResult, "resident"));
		assertTrue(readRecordBoolean(parseResult, "confirmed"));
	}

	/**
	 * 空输入应返回空 payload，且 resident/confirmed 都为 false。
	 */
	@Test
	void crossChunkConfirmSuffixShouldReturnUnconfirmedForBlankInput() throws Exception {
		Object parseResult = invokeCrossChunkConfirmSuffixParse("   ");
		assertEquals("", readRecordString(parseResult, "payload"));
		assertFalse(readRecordBoolean(parseResult, "resident"));
		assertFalse(readRecordBoolean(parseResult, "confirmed"));
	}

	/**
	 * 反射调用 ModCommands 的私有 confirm 后缀解析入口。
	 */
	private static Object invokeModConfirmSuffixParse(String rawText) throws Exception {
		Method parseMethod = ModCommands.class.getDeclaredMethod("parseConfirmSuffix", String.class);
		parseMethod.setAccessible(true);
		return parseMethod.invoke(null, rawText);
	}

	/**
	 * 反射调用 CrossChunkCommandRegistry 的私有后缀解析入口。
	 */
	private static Object invokeCrossChunkConfirmSuffixParse(String rawText) throws Exception {
		Method parseMethod = CrossChunkCommandRegistry.class.getDeclaredMethod("parseConfirmSuffix", String.class);
		parseMethod.setAccessible(true);
		return parseMethod.invoke(null, rawText);
	}

	/**
	 * 读取 record 字符串访问器。
	 */
	private static String readRecordString(Object recordSnapshot, String accessorName) throws Exception {
		Method accessor = recordSnapshot.getClass().getDeclaredMethod(accessorName);
		accessor.setAccessible(true);
		return (String) accessor.invoke(recordSnapshot);
	}

	/**
	 * 读取 record 布尔访问器。
	 */
	private static boolean readRecordBoolean(Object recordSnapshot, String accessorName) throws Exception {
		Method accessor = recordSnapshot.getClass().getDeclaredMethod(accessorName);
		accessor.setAccessible(true);
		return (boolean) accessor.invoke(recordSnapshot);
	}
}
